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

import de.linzn.stem.api.StemApi;
import de.linzn.stem.modules.databaseModule.DataContainer;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts OpenAI requests and tokens per day and model. Works without an admin key; stored in the STEM data store.
 */
public class UsageTracker {

    private static final String KEY = "mirra-openai-usage";
    private static final int KEEP_DAYS = 62;

    private record Counter(AtomicLong requests, AtomicLong input, AtomicLong output) {
        Counter() {
            this(new AtomicLong(), new AtomicLong(), new AtomicLong());
        }
    }

    private final StemApi api;
    private final ZoneId zone;
    /* day -> model -> counter */
    private final Map<String, Map<String, Counter>> days = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public UsageTracker(StemApi api) {
        this.api = api;
        this.zone = api.system().getZone();
    }

    public void load() {
        try {
            this.api.data().get(KEY).map(DataContainer::getJSON).ifPresent(json -> {
                for (String day : json.keySet()) {
                    JSONObject models = json.getJSONObject(day);
                    Map<String, Counter> counters = new ConcurrentHashMap<>();
                    for (String model : models.keySet()) {
                        JSONObject value = models.getJSONObject(model);
                        counters.put(model, new Counter(new AtomicLong(value.optLong("requests")), new AtomicLong(value.optLong("input")),
                                new AtomicLong(value.optLong("output"))));
                    }
                    this.days.put(day, counters);
                }
            });
        } catch (Exception ignored) {
            /* start empty */
        }
    }

    public void record(String model, long input, long output) {
        String day = LocalDate.now(this.zone).toString();
        Counter counter = this.days.computeIfAbsent(day, key -> new ConcurrentHashMap<>()).computeIfAbsent(model, key -> new Counter());
        counter.requests().incrementAndGet();
        counter.input().addAndGet(input);
        counter.output().addAndGet(output);
        this.dirty = true;
    }

    public void saveIfDirty() {
        if (!this.dirty) {
            return;
        }
        this.dirty = false;
        String oldest = LocalDate.now(this.zone).minusDays(KEEP_DAYS).toString();
        this.days.keySet().removeIf(day -> day.compareTo(oldest) < 0);
        this.api.data().set(KEY, this.toJson(null));
    }

    /**
     * @param fromDay first day (inclusive, yyyy-MM-dd), null for all
     * @return day -> model -> {requests, input, output}
     */
    public JSONObject toJson(String fromDay) {
        JSONObject json = new JSONObject();
        new TreeMap<>(this.days).forEach((day, models) -> {
            if (fromDay != null && day.compareTo(fromDay) < 0) {
                return;
            }
            JSONObject entry = new JSONObject();
            models.forEach((model, counter) -> entry.put(model, new JSONObject().put("requests", counter.requests().get())
                    .put("input", counter.input().get()).put("output", counter.output().get())));
            json.put(day, entry);
        });
        return json;
    }

    /**
     * @return total {requests, input, output} per model since a day
     */
    public JSONObject totals(String fromDay) {
        Map<String, long[]> sums = new TreeMap<>();
        this.days.forEach((day, models) -> {
            if (day.compareTo(fromDay) < 0) {
                return;
            }
            models.forEach((model, counter) -> {
                long[] sum = sums.computeIfAbsent(model, key -> new long[3]);
                sum[0] += counter.requests().get();
                sum[1] += counter.input().get();
                sum[2] += counter.output().get();
            });
        });
        JSONObject json = new JSONObject();
        sums.forEach((model, sum) -> json.put(model, new JSONObject().put("requests", sum[0]).put("input_tokens", sum[1]).put("output_tokens", sum[2])));
        return json;
    }
}
