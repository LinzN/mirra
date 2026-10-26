/*
 * Copyright (c) 2026 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.linzn.mirra.agent.autonomy;

import de.linzn.mirra.MirraConfig;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.openai.OpenAIClient;
import de.linzn.mirra.agent.openai.OpenAIException;
import org.json.JSONObject;

import java.time.*;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches the OpenAI credits:
 * <ul>
 *     <li>alerts at once when OpenAI reports that the quota is used up or the key is invalid</li>
 *     <li>with an admin key: daily check of the costs against the remaining prepaid credit and the monthly budget</li>
 * </ul>
 * Alerts go to the approver (by default the default user) and as STEM notification.
 */
public class BillingMonitor {

    /* the same alert is repeated at most this often */
    private static final Duration ALERT_REPEAT = Duration.ofHours(6);

    private final MirraPlugin plugin;
    private final MirraConfig config;
    private final OpenAIClient client;
    private final UsageTracker usage;
    private final Map<String, Instant> lastAlerts = new ConcurrentHashMap<>();
    private volatile Instant quotaExceededSince;

    public BillingMonitor(MirraPlugin plugin, MirraConfig config, OpenAIClient client, UsageTracker usage) {
        this.plugin = plugin;
        this.config = config;
        this.client = client;
        this.usage = usage;
    }

    /**
     * @return true while OpenAI reported no credits within the last hour
     */
    public boolean isQuotaExceeded() {
        Instant since = this.quotaExceededSince;
        return since != null && Duration.between(since, Instant.now()).toHours() < 1;
    }

    /**
     * Called by the OpenAI client for errors that need a person.
     */
    public void onError(OpenAIException error) {
        if (error.isQuotaExceeded()) {
            this.quotaExceededSince = Instant.now();
            this.alert("quota", "🚨 OpenAI-Guthaben aufgebraucht! Mirra kann gerade nicht antworten. Bitte Credits aufladen "
                    + "(platform.openai.com → Billing). Meldung: " + error.getMessage());
        } else if (error.isAuthError()) {
            this.alert("auth", "🚨 Der OpenAI-API-Key von Mirra ist ungültig oder wurde gesperrt. Meldung: " + error.getMessage());
        }
    }

    /**
     * Report usage and costs; also used by the daily check.
     */
    public JSONObject report() {
        ZoneId zone = this.plugin.getStemApi().system().getZone();
        LocalDate today = LocalDate.now(zone);
        LocalDate monthStart = today.withDayOfMonth(1);
        JSONObject report = new JSONObject()
                .put("tokens_today", this.usage.totals(today.toString()))
                .put("tokens_this_month", this.usage.totals(monthStart.toString()))
                .put("quota_exceeded_recently", this.isQuotaExceeded());
        if (this.config.openAIAdminKey.isEmpty()) {
            report.put("costs", "not available: set openAI.adminKey (an OpenAI admin key) to read costs");
            return report;
        }
        try {
            LocalDate from = monthStart;
            LocalDate balanceDate = this.balanceDate();
            if (balanceDate != null && balanceDate.isBefore(from)) {
                from = balanceDate;
            }
            SortedMap<Long, Double> costs = this.client.costs(this.config.openAIAdminKey, from.atStartOfDay(ZoneOffset.UTC).toInstant());
            double month = sumFrom(costs, monthStart);
            double todayCost = sumFrom(costs, today);
            report.put("costs_usd", new JSONObject().put("today", round(todayCost)).put("this_month", round(month)));
            if (this.config.billingMonthlyBudget > 0) {
                report.put("monthly_budget_usd", this.config.billingMonthlyBudget)
                        .put("budget_used_percent", Math.round(month / this.config.billingMonthlyBudget * 100));
            }
            if (this.config.billingCreditBalance > 0 && balanceDate != null) {
                double spent = sumFrom(costs, balanceDate);
                report.put("credit_balance_usd", this.config.billingCreditBalance).put("balance_date", balanceDate.toString())
                        .put("spent_since_balance_usd", round(spent))
                        .put("estimated_remaining_usd", round(this.config.billingCreditBalance - spent));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            report.put("costs_error", e.getMessage());
        }
        return report;
    }

    /**
     * Daily check: warn if the credit runs low or the budget is reached.
     */
    public void check() {
        JSONObject report = this.report();
        if (report.has("estimated_remaining_usd")) {
            double remaining = report.getDouble("estimated_remaining_usd");
            if (remaining <= this.config.billingWarnBelow) {
                this.alert("low-credit", String.format(Locale.GERMANY,
                        "⚠️ OpenAI-Guthaben wird knapp: noch etwa %.2f $ (Stand %s: %.2f $, seitdem verbraucht %.2f $). "
                                + "Bitte aufladen und billing.creditBalance/billing.balanceDate in der Mirra-Config anpassen.",
                        remaining, report.getString("balance_date"), report.getDouble("credit_balance_usd"),
                        report.getDouble("spent_since_balance_usd")));
            }
        }
        if (report.has("budget_used_percent")) {
            long percent = report.getLong("budget_used_percent");
            String month = YearMonth.now(this.plugin.getStemApi().system().getZone()).toString();
            if (percent >= 100) {
                this.alertOnce("budget-100-" + month, "🚨 Das OpenAI-Monatsbudget von " + this.config.billingMonthlyBudget
                        + " $ ist aufgebraucht (" + percent + " %).");
            } else if (percent >= 80) {
                this.alertOnce("budget-80-" + month, "⚠️ " + percent + " % des OpenAI-Monatsbudgets von "
                        + this.config.billingMonthlyBudget + " $ sind verbraucht.");
            }
        }
        if (report.has("costs_error")) {
            this.plugin.getPluginLogger().warning("OpenAI costs could not be read: " + report.getString("costs_error"));
        }
    }

    private LocalDate balanceDate() {
        try {
            return this.config.billingBalanceDate.isEmpty() ? null : LocalDate.parse(this.config.billingBalanceDate);
        } catch (Exception e) {
            return null;
        }
    }

    private void alert(String key, String text) {
        Instant last = this.lastAlerts.get(key);
        if (last != null && Duration.between(last, Instant.now()).compareTo(ALERT_REPEAT) < 0) {
            return;
        }
        this.lastAlerts.put(key, Instant.now());
        this.plugin.notifyApprover(text, true);
    }

    private void alertOnce(String key, String text) {
        if (this.lastAlerts.putIfAbsent(key, Instant.now()) == null) {
            this.plugin.notifyApprover(text, true);
        }
    }

    private static double sumFrom(SortedMap<Long, Double> costs, LocalDate day) {
        long from = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        return costs.tailMap(from).values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
