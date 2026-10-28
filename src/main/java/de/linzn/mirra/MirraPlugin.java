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

package de.linzn.mirra;

import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.agent.ImageService;
import de.linzn.mirra.agent.MirraAgent;
import de.linzn.mirra.agent.attachment.AttachmentProcessor;
import de.linzn.mirra.agent.autonomy.Watchdog;
import de.linzn.mirra.messaging.ChatMessenger;
import de.linzn.mirra.messaging.MessengerException;
import de.linzn.mirra.agent.openai.OpenAIClient;
import de.linzn.mirra.agent.tools.AssistantTools;
import de.linzn.mirra.agent.tools.ControlTools;
import de.linzn.mirra.agent.tools.MessagingTools;
import de.linzn.mirra.agent.tools.TaskTools;
import de.linzn.mirra.agent.tools.UserTools;
import de.linzn.mirra.agent.tasks.TaskEventListener;
import de.linzn.mirra.agent.tasks.TaskManager;
import de.linzn.mirra.agent.tasks.TaskRepository;
import de.linzn.mirra.agent.tools.HomeTools;
import de.linzn.mirra.agent.tools.StemTools;
import de.linzn.mirra.agent.tools.ToolRegistry;
import de.linzn.mirra.core.reminder.ReminderEngine;
import de.linzn.mirra.discord.DiscordManager;
import de.linzn.mirra.identitySystem.AccessControl;
import de.linzn.mirra.identitySystem.IdentityManager;
import de.linzn.mirra.identitySystem.OwnerActions;
import de.linzn.mirra.identitySystem.LegacyMigration;
import de.linzn.mirra.identitySystem.UserRepository;
import de.linzn.mirra.listener.MirraReminderListener;
import de.linzn.mirra.listener.StemEventListener;
import de.linzn.mirra.memory.DatabaseMemoryRepository;
import de.linzn.mirra.memory.LongTermMemory;
import de.linzn.mirra.memory.MemoryKeeper;
import de.linzn.mirra.whatsapp.WhatsappManager;
import de.linzn.stem.modules.pluginModule.STEMPlugin;
import org.json.JSONObject;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;


public class MirraPlugin extends STEMPlugin {

    private static final int MAX_STATUS_LENGTH = 128;

    public static MirraPlugin mirraPlugin;

    private MirraConfig mirraConfig;
    private IdentityManager identityManager;
    private WhatsappManager whatsappManager;
    private DiscordManager discordManager;
    private ReminderEngine reminderEngine;
    private MirraAgent agent;
    private ImageService imageService;
    private AttachmentProcessor attachmentProcessor;
    private ChatMessenger messenger;
    private TaskManager taskManager;
    private AccessControl accessControl;
    private OwnerActions ownerActions;
    private Watchdog watchdog;
    private de.linzn.mirra.voice.MirraVoice voice;

    public MirraPlugin() {
        mirraPlugin = this;
    }

    @Override
    public void onEnable() {
        this.mirraConfig = new MirraConfig(this.getDefaultConfig());

        DatabaseMemoryRepository repository = new DatabaseMemoryRepository(this.getStemApi().data(), this.getPluginLogger());
        try {
            repository.createSchema();
            if (!Boolean.TRUE.equals(this.getDefaultConfig().get("memory.legacyImported", false))) {
                int imported = repository.importLegacyMemories();
                this.getDefaultConfig().set("memory.legacyImported", true);
                this.getDefaultConfig().save();
                this.getPluginLogger().info("Imported " + imported + " memories of the old long term memory");
            }
        } catch (SQLException e) {
            this.getPluginLogger().error("Could not prepare the database tables of Mirra", e);
        }
        /* user profiles: create tables and migrate the identities of earlier versions once,
         * before the long term memory is loaded, because the migration renames memory owners */
        UserRepository userRepository = new UserRepository(this.getStemApi().data(), this.getPluginLogger());
        try {
            userRepository.createSchema();
            if (!Boolean.TRUE.equals(this.getDefaultConfig().get("users.migrated", false))) {
                if (userRepository.countUsers() == 0) {
                    LegacyMigration.Result result = LegacyMigration.run(userRepository, this.getPluginLogger());
                    this.getPluginLogger().info("Migrated identities to user profiles: " + result);
                }
                this.getDefaultConfig().set("users.migrated", true);
                this.getDefaultConfig().save();
            }
        } catch (SQLException e) {
            this.getPluginLogger().error("Could not prepare or migrate the user profiles", e);
        }
        this.identityManager = new IdentityManager(this, userRepository, this.mirraConfig.autoCreateGuests, this.mirraConfig.requireApproval);
        this.accessControl = new AccessControl(this, this.mirraConfig);
        this.ownerActions = new OwnerActions(this);

        OpenAIClient client = new OpenAIClient(this.mirraConfig.openAIBaseUrl, this.mirraConfig.openAIToken, this.mirraConfig.openAITimeoutSeconds);
        /* when the credits run out, Mirra pauses: no requests are sent, chats get the paused message */
        client.setErrorListener(new OpenAIClient.ErrorListener() {
            private volatile java.time.Instant lastAuthAlert;

            @Override
            public void onError(de.linzn.mirra.agent.openai.OpenAIException error) {
                if (error.isQuotaExceeded()) {
                    notifyApprover("🚨 OpenAI-Guthaben aufgebraucht! Mirra ist pausiert und antwortet nur noch mit dem Standardtext. "
                            + "Bitte Credits aufladen (platform.openai.com → Billing); Mirra prüft alle 15 Minuten, ob es weitergeht.", true);
                } else if (error.isAuthError() && (this.lastAuthAlert == null
                        || java.time.Duration.between(this.lastAuthAlert, java.time.Instant.now()).toHours() >= 6)) {
                    this.lastAuthAlert = java.time.Instant.now();
                    notifyApprover("🚨 Der OpenAI-API-Key von Mirra ist ungültig oder wurde gesperrt: " + error.getMessage(), true);
                }
            }

            @Override
            public void onRecovered() {
                notifyApprover("✅ OpenAI-Guthaben ist wieder da, Mirra ist wieder aktiv.", false);
            }
        });
        LongTermMemory memory = new LongTermMemory(repository, new LongTermMemory.Embedder() {
            @Override
            public List<float[]> embed(List<String> texts) throws Exception {
                return client.embed(mirraConfig.embeddingModel, texts);
            }

            @Override
            public String model() {
                return mirraConfig.embeddingModel;
            }
        }, this.getPluginLogger());
        memory.load();
        this.getStemApi().scheduler().runAsync(memory::embedMissing);

        MemoryKeeper keeper = new MemoryKeeper(client, memory, repository, this.getPluginLogger(), this.mirraConfig.modelName,
                this.mirraConfig.memoryModel, this.mirraConfig.historyMessages, this.mirraConfig.summaryThreshold);
        keeper.setReasoningEffort(this.mirraConfig.memoryReasoningEffort);
        this.imageService = new ImageService(this, client, this.mirraConfig.imageModel);
        this.messenger = new ChatMessenger(this, this.mirraConfig, client);
        this.voice = new de.linzn.mirra.voice.MirraVoice(this, this.mirraConfig, client);
        this.attachmentProcessor = new AttachmentProcessor(client, this.getPluginLogger(), this.mirraConfig.transcriptionModel, this.mirraConfig.attachmentMaxSizeMB);

        ToolRegistry tools = new ToolRegistry(this.getPluginLogger());
        tools.registerAll(StemTools.create(this, this.mirraConfig));
        tools.registerAll(HomeTools.create());
        tools.registerAll(AssistantTools.create(this));
        tools.registerAll(MessagingTools.create(this));
        tools.registerAll(ControlTools.create(this, this.mirraConfig));
        tools.registerAll(TaskTools.create(this));
        tools.registerAll(UserTools.create(this));
        this.agent = new MirraAgent(client, this.mirraConfig, tools, repository, memory, keeper, this.getPluginLogger(),
                task -> this.getStemApi().scheduler().runAsync(task), this.getStemApi().system().getZone(), this.attachmentProcessor);
        this.agent.setProfiles(this.identityManager);
        this.getPluginLogger().info("Mirra agent ready with " + tools.getTools().size() + " tools, model " + this.mirraConfig.textModel);

        this.whatsappManager = new WhatsappManager(this);
        this.discordManager = new DiscordManager(this);
        this.reminderEngine = new ReminderEngine(this);
        /* everything registered through the API is removed again on disable */
        this.getStemApi().events().register(new StemEventListener(this));
        this.getStemApi().events().register(new MirraReminderListener(this));
        this.taskManager = new TaskManager(this, new TaskRepository(this.getStemApi().data(), this.getPluginLogger()));
        this.taskManager.start();
        this.getStemApi().events().register(new TaskEventListener(this.taskManager));
        this.watchdog = new Watchdog(this, this.mirraConfig, client::isPaused);
        if (this.mirraConfig.autonomyEnabled) {
            this.watchdog.start();
        }
        this.registerAiTextEngine();
        this.scheduleSocialStatus();
    }

    @Override
    public void onDisable() {
        if (this.taskManager != null) {
            this.taskManager.stop();
        }
        if (this.discordManager != null) {
            this.discordManager.shutdown();
        }
    }

    /**
     * Lets STEM phrase information texts (e.g. "door ring pressed") through Mirra.
     */
    private void registerAiTextEngine() {
        this.getStemApi().information().registerAiTextEngine(event -> {
            try {
                String text = this.agent.generate("A smart home event happened: " + event
                        + "\nWrite a short, friendly notification about it for " + this.mirraConfig.defaultIdentityName
                        + " in German. Only the message, no greeting formula.", null);
                return text == null || text.isBlank() ? event : text;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return event;
            } catch (Exception e) {
                this.getPluginLogger().warning("AI text engine failed: " + e.getMessage());
                return event;
            }
        });
    }

    private static final String SOCIAL_STATUS_KEY = "mirra-social-status";
    private static final String[] SOCIAL_STATUS_IDEAS = {
            "something nerdy about servers, networks or code", "how your week in the smart home went",
            "a joke about STEM, MirraNET or smart homes", "the season or the weather outside",
            "a small everyday observation", "your mood today", "something you would like to learn",
            "a tech tip in one line", "a bit of self-irony about being an AI", "the weekday and what it feels like",
            "music, games or a series you would like", "a cheeky motto for the next days"};

    /**
     * The social status changes at a random time every few days ({@code agent.socialStatus.minDays} to
     * {@code maxDays}, between {@code earliestHour} and {@code latestHour}). The planned time and the last status
     * are kept in the STEM data store, so a restart neither resets the plan nor posts a new status.
     */
    private void scheduleSocialStatus() {
        if (!this.mirraConfig.socialStatusEnabled) {
            return;
        }
        JSONObject state = this.socialStatusState();
        String last = state.optString("status", "");
        if (!last.isBlank()) {
            /* the Discord activity is lost on restart; set the last status again, without a new WhatsApp status */
            this.getStemApi().scheduler().runLater(() -> this.discordManager.setActivity(last), 1, TimeUnit.MINUTES);
        }
        java.time.ZonedDateTime next = null;
        try {
            next = state.has("next") ? java.time.ZonedDateTime.parse(state.getString("next")) : null;
        } catch (Exception ignored) {
            /* plan a new time */
        }
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(this.getStemApi().system().getZone());
        if (next == null) {
            /* first start: a new status soon, at a random time within the next day */
            next = this.randomTimeOfDay(now.plusDays(java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 2)), now);
        } else if (next.isBefore(now)) {
            /* missed while STEM was offline: catch up a little later */
            next = now.plusMinutes(java.util.concurrent.ThreadLocalRandom.current().nextInt(10, 120));
        }
        this.planSocialStatus(next);
    }

    private void planSocialStatus(java.time.ZonedDateTime next) {
        JSONObject state = this.socialStatusState().put("next", next.toString());
        this.getStemApi().data().set(SOCIAL_STATUS_KEY, state);
        long delay = Math.max(0, java.time.Duration.between(java.time.ZonedDateTime.now(next.getZone()), next).toMillis());
        this.getStemApi().scheduler().runLater(this::chooseSocialStatus, delay, TimeUnit.MILLISECONDS);
        this.getPluginLogger().info("Next social status planned for " + next.toLocalDateTime().withSecond(0).withNano(0));
    }

    private java.time.ZonedDateTime randomTimeOfDay(java.time.ZonedDateTime day, java.time.ZonedDateTime notBefore) {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        int from = this.mirraConfig.socialStatusEarliestHour * 60;
        int to = this.mirraConfig.socialStatusLatestHour * 60 + 59;
        java.time.ZonedDateTime time = day.toLocalDate().atStartOfDay(day.getZone()).plusMinutes(random.nextInt(from, to + 1));
        return time.isAfter(notBefore) ? time : notBefore.plusMinutes(random.nextInt(10, 180));
    }

    private JSONObject socialStatusState() {
        try {
            return this.getStemApi().data().get(SOCIAL_STATUS_KEY).map(de.linzn.stem.modules.databaseModule.DataContainer::getJSON)
                    .orElseGet(JSONObject::new);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void chooseSocialStatus() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(this.getStemApi().system().getZone());
        String previous = this.socialStatusState().optString("status", "");
        try {
            JSONObject schema = new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject().put("status", new JSONObject().put("type", "string")))
                    .put("required", new org.json.JSONArray().put("status"))
                    .put("additionalProperties", false);
            String idea = SOCIAL_STATUS_IDEAS[random.nextInt(SOCIAL_STATUS_IDEAS.length)];
            JSONObject result = this.agent.generateJson("Choose a new social status for yourself for Discord and WhatsApp. "
                    + "German, at most 100 characters, in your own style. Theme for this time: " + idea + ". "
                    + "It is " + now.getDayOfWeek() + ", " + now.toLocalDate() + ". "
                    + (previous.isBlank() ? "" : "Your previous status was: \"" + previous + "\" - write something clearly different."),
                    "social_status", schema);
            String status = this.updateSocialStatus(result.optString("status", ""));
            this.getPluginLogger().info("New social status: " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            this.getPluginLogger().warning("Could not choose a social status: " + e.getMessage());
        }
        int days = random.nextInt(this.mirraConfig.socialStatusMinDays, this.mirraConfig.socialStatusMaxDays + 1);
        this.planSocialStatus(this.randomTimeOfDay(now.plusDays(days), now));
    }

    /**
     * Set the Discord activity and post a WhatsApp status.
     *
     * @return the status that was set
     */
    public String updateSocialStatus(String status) {
        String text = status == null ? "" : status.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Empty status");
        }
        if (text.length() > MAX_STATUS_LENGTH) {
            text = text.substring(0, MAX_STATUS_LENGTH);
        }
        this.discordManager.setActivity(text);
        try {
            this.getStemApi().data().set(SOCIAL_STATUS_KEY, this.socialStatusState().put("status", text)
                    .put("changed", java.time.ZonedDateTime.now(this.getStemApi().system().getZone()).toString()));
        } catch (Exception e) {
            this.getPluginLogger().debug("Could not store the social status: " + e.getMessage());
        }
        if (this.whatsappManager.isEnabled()) {
            try {
                this.whatsappManager.getEvolutionApi().getWebApiProvider().CreateStatusStorie(text, this.whatsappManager.getEvolutionApi().getWebApiProvider().getContacts());
            } catch (Exception e) {
                this.getPluginLogger().warning("Could not set WhatsApp status: " + e.getMessage());
            }
        }
        return text;
    }

    /**
     * Context of a chat with a sender.
     */
    public ChatContext chatContext(de.linzn.mirra.identitySystem.UserToken userToken, String displayName) {
        return new ChatContext(userToken, this.identityManager.getIdentityUserByToken(userToken), displayName);
    }

    public MirraConfig getMirraConfig() {
        return mirraConfig;
    }

    public MirraAgent getAgent() {
        return agent;
    }

    /**
     * @return the task manager, null until Mirra is started
     */
    public TaskManager getTaskManager() {
        return taskManager;
    }

    public Watchdog getWatchdog() {
        return watchdog;
    }

    /**
     * Send a plain message (no AI) to the approver, e.g. alerts when OpenAI is not usable.
     *
     * @param stemNotification also push it as STEM notification
     */
    public void notifyApprover(String text, boolean stemNotification) {
        this.getPluginLogger().warning(text);
        if (stemNotification) {
            try {
                this.getStemApi().notifications().push(text, de.linzn.stem.modules.notificationModule.NotificationPriority.HIGH);
            } catch (Exception e) {
                this.getPluginLogger().debug("Could not push STEM notification: " + e.getMessage());
            }
        }
        try {
            java.util.Optional<de.linzn.mirra.identitySystem.UserToken> token = this.identityManager.findIdentity(this.mirraConfig.approverName)
                    .flatMap(user -> this.messenger.tokenOf(user, null));
            if (token.isPresent()) {
                this.messenger.sendText(token.get(), text);
            }
        } catch (Exception e) {
            this.getPluginLogger().warning("Could not notify the approver: " + e.getMessage());
        }
    }

    /**
     * Speech interface for voice devices; device plugins register their speaker here.
     */
    public de.linzn.mirra.voice.MirraVoice getVoice() {
        return voice;
    }

    public OwnerActions getOwnerActions() {
        return ownerActions;
    }

    public AccessControl getAccessControl() {
        return accessControl;
    }

    public ChatMessenger getMessenger() {
        return messenger;
    }

    /**
     * Send the answer of a chat turn as text, or as voice message if voice replies are enabled and the person spoke.
     */
    public void deliverAnswer(ChatContext chat, String answer, boolean incomingVoice) {
        if (answer == null || answer.isBlank()) {
            return;
        }
        if (incomingVoice && !chat.isGuest() && this.mirraConfig.voiceReplyToVoice && this.mirraConfig.voiceEnabled) {
            try {
                this.messenger.sendVoice(chat.userToken(), answer);
                return;
            } catch (MessengerException e) {
                this.getPluginLogger().warning("Voice reply failed, sending text: " + e.getMessage());
            }
        }
        try {
            this.messenger.sendText(chat.userToken(), answer);
        } catch (MessengerException e) {
            this.getPluginLogger().error("Unable to deliver answer to " + chat.name() + ": " + e.getMessage());
        }
    }

    public AttachmentProcessor getAttachmentProcessor() {
        return attachmentProcessor;
    }

    public ImageService getImageService() {
        return imageService;
    }

    public WhatsappManager getWhatsappManager() {
        return whatsappManager;
    }

    public DiscordManager getDiscordManager() {
        return discordManager;
    }

    public IdentityManager getIdentityManager() {
        return identityManager;
    }

    public ReminderEngine getReminderEngine() {
        return reminderEngine;
    }
}
