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
import de.linzn.mirra.agent.openai.OpenAIClient;
import de.linzn.mirra.agent.tools.AssistantTools;
import de.linzn.mirra.agent.tools.HomeTools;
import de.linzn.mirra.agent.tools.StemTools;
import de.linzn.mirra.agent.tools.ToolRegistry;
import de.linzn.mirra.core.reminder.ReminderEngine;
import de.linzn.mirra.discord.DiscordManager;
import de.linzn.mirra.identitySystem.IdentityManager;
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

        OpenAIClient client = new OpenAIClient(this.mirraConfig.openAIBaseUrl, this.mirraConfig.openAIToken, this.mirraConfig.openAITimeoutSeconds);
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
        this.imageService = new ImageService(this, client, this.mirraConfig.imageModel);
        this.attachmentProcessor = new AttachmentProcessor(client, this.getPluginLogger(), this.mirraConfig.transcriptionModel, this.mirraConfig.attachmentMaxSizeMB);

        ToolRegistry tools = new ToolRegistry(this.getPluginLogger());
        tools.registerAll(StemTools.create(this, this.mirraConfig));
        tools.registerAll(HomeTools.create());
        tools.registerAll(AssistantTools.create(this));
        this.agent = new MirraAgent(client, this.mirraConfig, tools, repository, memory, keeper, this.getPluginLogger(),
                task -> this.getStemApi().scheduler().runAsync(task), this.getStemApi().system().getZone(), this.attachmentProcessor);
        this.getPluginLogger().info("Mirra agent ready with " + tools.getTools().size() + " tools, model " + this.mirraConfig.textModel);

        this.identityManager = new IdentityManager(this);
        this.whatsappManager = new WhatsappManager(this);
        this.discordManager = new DiscordManager(this);
        this.reminderEngine = new ReminderEngine(this);
        /* everything registered through the API is removed again on disable */
        this.getStemApi().events().register(new StemEventListener(this));
        this.getStemApi().events().register(new MirraReminderListener(this));
        this.registerAiTextEngine();
        this.scheduleSocialStatus();
    }

    @Override
    public void onDisable() {
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

    private void scheduleSocialStatus() {
        if (!this.mirraConfig.socialStatusEnabled) {
            return;
        }
        this.getStemApi().scheduler().runCron(this::chooseSocialStatus, this.mirraConfig.socialStatusCron);
        if (this.mirraConfig.socialStatusOnStartup) {
            this.getStemApi().scheduler().runLater(this::chooseSocialStatus, 1, TimeUnit.MINUTES);
        }
    }

    private void chooseSocialStatus() {
        try {
            JSONObject schema = new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject().put("status", new JSONObject().put("type", "string")))
                    .put("required", new org.json.JSONArray().put("status"))
                    .put("additionalProperties", false);
            JSONObject result = this.agent.generateJson("Choose a new social status for yourself for Discord and WhatsApp. "
                    + "German, at most 100 characters, maybe about MirraNET, STEM or your day. Be creative.", "social_status", schema);
            String status = this.updateSocialStatus(result.optString("status", ""));
            this.getPluginLogger().info("New social status: " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            this.getPluginLogger().warning("Could not choose a social status: " + e.getMessage());
        }
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
