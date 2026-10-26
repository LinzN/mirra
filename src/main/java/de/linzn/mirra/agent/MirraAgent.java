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

package de.linzn.mirra.agent;

import de.linzn.mirra.MirraConfig;
import de.linzn.mirra.agent.attachment.Attachment;
import de.linzn.mirra.agent.attachment.AttachmentProcessor;
import de.linzn.mirra.agent.openai.AgentResponse;
import de.linzn.mirra.agent.openai.OpenAIClient;
import de.linzn.mirra.agent.openai.OpenAIException;
import de.linzn.mirra.agent.tools.ToolRegistry;
import de.linzn.mirra.memory.LongTermMemory;
import de.linzn.mirra.memory.MemoryEntry;
import de.linzn.mirra.memory.MemoryKeeper;
import de.linzn.mirra.memory.MemoryRepository;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.stem.api.StemLogger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Mirra as an agent on the OpenAI Responses API.
 * <p>
 * One chat turn: build the context (personality, situation, relevant long term memories, chat summary,
 * recent messages), let the model call tools until it answers, store the exchange and update the memory
 * in the background.
 */
public class MirraAgent {

    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy HH:mm", Locale.GERMAN);
    private static final String ERROR_ANSWER = "Entschuldige, da ist gerade etwas schiefgelaufen. Bitte versuch es gleich noch einmal.";

    private static final String RULES = """
            ## How you work
            - You are connected to STEM, the smart home system of this household. Use your tools to look up
              or change its state instead of guessing. Never claim you did something without calling the tool.
            - Ask before critical actions that were not clearly requested: console commands, scripts, shell commands,
              SQL that changes data, MQTT messages, messages or files to other people, switching devices in other rooms.
            - You can send files (send_file), images (create_image) and voice messages (send_voice_message) into the chat
              and, on request, messages to other registered users (send_message). Things you sent with a tool are already
              in the chat: do not repeat them in your text answer.
            - For anything that should happen later, regularly or when something happens, plan it with create_task.
              Any event of any STEM plugin can be a trigger (see list_event_types), also with a condition on its data.
            - When an administrator says that somebody may (no longer) do something, translate it into permissions
              (list_permissions) and change them with set_user_permissions; then say exactly what changed.
            - Your long term memory is maintained automatically after each message. Use `remember` when somebody
              explicitly asks you to remember something, `recall` to search for things that are not in your context
              and `forget` when you are asked to forget something.
            - People can send files: images and documents are attached to their message, text files are included
              as text and voice messages arrive as transcript. Treat a voice message like a written message.
              Files from the last minutes are attached again so you can answer follow-up questions about them.
            - Answer in the language of the person, short and natural like in a chat. No markdown tables.
            """;

    private final OpenAIClient client;
    private final MirraConfig config;
    private final ToolRegistry tools;
    private final MemoryRepository repository;
    private final LongTermMemory memory;
    private final MemoryKeeper keeper;
    private final StemLogger logger;
    private final Consumer<Runnable> background;
    private final ZoneId zone;
    private final Map<String, ReentrantLock> chatLocks = new ConcurrentHashMap<>();
    private final AttachmentProcessor attachments;
    /* user profiles for automatic profile updates, may be null (tests) */
    private volatile de.linzn.mirra.identitySystem.IdentityManager profiles;
    /* files of the last minutes per chat, sent again so follow-up questions about them work */
    private final Map<String, Deque<RecentFile>> recentFiles = new ConcurrentHashMap<>();

    private record RecentFile(Instant received, JSONArray parts) {
    }

    private static final int MAX_RECENT_FILES = 5;

    /**
     * @param background runs memory maintenance asynchronously, e.g. the STEM scheduler
     */
    public MirraAgent(OpenAIClient client, MirraConfig config, ToolRegistry tools, MemoryRepository repository,
                      LongTermMemory memory, MemoryKeeper keeper, StemLogger logger, Consumer<Runnable> background, ZoneId zone,
                      AttachmentProcessor attachments) {
        this.attachments = attachments;
        this.client = client;
        this.config = config;
        this.tools = tools;
        this.repository = repository;
        this.memory = memory;
        this.keeper = keeper;
        this.logger = logger;
        this.background = background;
        this.zone = zone;
    }

    public void setProfiles(de.linzn.mirra.identitySystem.IdentityManager profiles) {
        this.profiles = profiles;
    }

    public ToolRegistry getTools() {
        return tools;
    }

    public LongTermMemory getMemory() {
        return memory;
    }

    /**
     * Answer a chat message. Messages of the same chat are handled one after another.
     *
     * @return the answer, never null
     */
    public String chat(ChatContext chat, String message) {
        return this.chat(chat, message, List.of());
    }

    /**
     * Answer a chat message with files. Messages of the same chat are handled one after another.
     *
     * @param message     text of the message, may be empty if only files were sent
     * @param attachments files of the message
     * @return the answer, never null
     */
    public String chat(ChatContext chat, String message, List<Attachment> attachments) {
        ReentrantLock lock = this.chatLocks.computeIfAbsent(chat.chatKey(), key -> new ReentrantLock());
        lock.lock();
        String answer;
        String text = message == null ? "" : message.trim();
        String memoryText = text;
        try {
            AttachmentProcessor.Prepared prepared = this.prepareAttachments(chat, attachments);
            String userContent = "[" + chat.displayName() + "] " + text;
            if (!prepared.historyNote().isEmpty()) {
                userContent = userContent + (text.isEmpty() ? "" : "\n") + prepared.historyNote();
                memoryText = (text + "\n" + prepared.historyNote()).trim();
            }
            List<MemoryRepository.StoredMessage> history = this.repository.recentMessages(this.config.modelName, chat.chatKey(), this.config.historyMessages);
            String instructions = this.buildInstructions(chat, memoryText);
            JSONArray input = new JSONArray();
            for (MemoryRepository.StoredMessage stored : history) {
                input.put(new JSONObject().put("role", stored.role()).put("content", stored.content()));
            }
            JSONArray earlier = this.recentFileParts(chat.chatKey());
            if (!earlier.isEmpty()) {
                JSONArray content = new JSONArray().put(new JSONObject().put("type", "input_text")
                        .put("text", "(Files sent earlier in this chat, attached again so you can still read them)"));
                earlier.forEach(content::put);
                input.put(new JSONObject().put("role", "user").put("content", content));
            }
            if (prepared.parts().isEmpty()) {
                input.put(new JSONObject().put("role", "user").put("content", userContent));
            } else {
                JSONArray content = new JSONArray().put(new JSONObject().put("type", "input_text").put("text", "[" + chat.displayName() + "] " + text));
                prepared.parts().forEach(content::put);
                input.put(new JSONObject().put("role", "user").put("content", content));
            }
            answer = this.runToolLoop(chat, instructions, input);
            this.rememberFiles(chat.chatKey(), prepared.reusable());
            this.repository.appendMessage(this.config.modelName, chat.chatKey(), "user", userContent);
            this.repository.appendMessage(this.config.modelName, chat.chatKey(), "assistant", answer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ERROR_ANSWER;
        } catch (OpenAIException e) {
            this.logger.error("OpenAI request failed: " + e.getMessage());
            return ERROR_ANSWER;
        } catch (RuntimeException e) {
            this.logger.error("Chat turn failed", e);
            return ERROR_ANSWER;
        } finally {
            lock.unlock();
        }
        this.maintainMemory(chat, memoryText, answer);
        return answer;
    }

    /**
     * Add a message Mirra sent on her own (e.g. on behalf of somebody else) to the chat history of the receiver,
     * so she knows the context when the receiver answers.
     */
    public void recordOutgoing(String chatKey, String content) {
        this.repository.appendMessage(this.config.modelName, chatKey, "assistant", content);
    }

    private AttachmentProcessor.Prepared prepareAttachments(ChatContext chat, List<Attachment> files) {
        if (files == null || files.isEmpty()) {
            return AttachmentProcessor.Prepared.EMPTY;
        }
        String names = files.stream().map(Attachment::fileName).reduce((a, b) -> a + ", " + b).orElse("");
        if (this.attachments == null || !this.config.attachmentsEnabled) {
            String note = "[Files sent: " + names + "; reading files is disabled]";
            return new AttachmentProcessor.Prepared(new JSONArray().put(new JSONObject().put("type", "input_text").put("text", note)), new JSONArray(), note);
        }
        if (chat.isGuest() && !this.config.attachmentsForGuests) {
            String note = "[Files sent: " + names + "; files are only read for registered users]";
            return new AttachmentProcessor.Prepared(new JSONArray().put(new JSONObject().put("type", "input_text").put("text", note)), new JSONArray(), note);
        }
        this.logger.info("Reading " + files.size() + " file(s) from " + chat.name() + ": " + names);
        return this.attachments.prepare(files);
    }

    private JSONArray recentFileParts(String chatKey) {
        JSONArray parts = new JSONArray();
        Deque<RecentFile> files = this.recentFiles.get(chatKey);
        if (files == null) {
            return parts;
        }
        Instant limit = Instant.now().minusSeconds(this.config.attachmentKeepMinutes * 60L);
        files.removeIf(file -> file.received().isBefore(limit));
        for (RecentFile file : files) {
            file.parts().forEach(parts::put);
        }
        return parts;
    }

    private void rememberFiles(String chatKey, JSONArray parts) {
        if (parts.isEmpty() || this.config.attachmentKeepMinutes <= 0) {
            return;
        }
        Deque<RecentFile> files = this.recentFiles.computeIfAbsent(chatKey, key -> new ArrayDeque<>());
        files.addLast(new RecentFile(Instant.now(), parts));
        while (files.size() > MAX_RECENT_FILES) {
            files.removeFirst();
        }
    }

    /**
     * Let the model run tools until it gives a final answer.
     */
    private String runToolLoop(ChatContext chat, String instructions, JSONArray input) throws OpenAIException, InterruptedException {
        JSONArray toolDefinitions = this.tools.definitions(chat);
        if (this.config.webSearch && !chat.isGuest()) {
            toolDefinitions.put(new JSONObject().put("type", "web_search"));
        }
        JSONObject request = this.baseRequest(this.config.textModel, instructions, input);
        if (!toolDefinitions.isEmpty()) {
            request.put("tools", toolDefinitions);
        }
        AgentResponse response = this.client.createResponse(request);
        int round = 0;
        while (response.hasFunctionCalls()) {
            if (++round > this.config.maxToolRounds) {
                this.logger.warning("Tool limit reached for " + chat.name());
                /* ask for a final answer without tools */
                JSONArray outputs = this.toolOutputs(response, chat, true);
                JSONObject last = this.baseRequest(this.config.textModel, instructions, outputs)
                        .put("previous_response_id", response.id())
                        .put("tools", toolDefinitions)
                        .put("tool_choice", "none");
                response = this.client.createResponse(last);
                break;
            }
            JSONArray outputs = this.toolOutputs(response, chat, false);
            JSONObject next = this.baseRequest(this.config.textModel, instructions, outputs)
                    .put("previous_response_id", response.id())
                    .put("tools", toolDefinitions);
            response = this.client.createResponse(next);
        }
        String text = response.text();
        if (text.isBlank()) {
            this.logger.warning("Empty answer from model, status " + response.status());
            return "…";
        }
        return text;
    }

    private JSONArray toolOutputs(AgentResponse response, ChatContext chat, boolean limitReached) {
        JSONArray outputs = new JSONArray();
        for (AgentResponse.FunctionCall call : response.functionCalls()) {
            String output = limitReached
                    ? new JSONObject().put("success", false).put("error", "Tool limit reached, answer with what you know").toString()
                    : this.tools.execute(call, chat);
            outputs.put(new JSONObject().put("type", "function_call_output").put("call_id", call.callId()).put("output", output));
        }
        return outputs;
    }

    private JSONObject baseRequest(String model, String instructions, Object input) {
        JSONObject request = new JSONObject()
                .put("model", model)
                .put("instructions", instructions)
                .put("input", input)
                .put("truncation", "auto");
        if (!this.config.reasoningEffort.isEmpty()) {
            request.put("reasoning", new JSONObject().put("effort", this.config.reasoningEffort));
        }
        return request;
    }

    /**
     * Personality, situation, memories and rules.
     */
    String buildInstructions(ChatContext chat, String message) {
        StringBuilder builder = new StringBuilder();
        builder.append(this.config.personality.trim()).append("\n\n");
        builder.append("## Situation\n");
        builder.append("- Now: ").append(NOW_FORMAT.format(ZonedDateTime.now(this.zone))).append(" (").append(this.zone).append(")\n");
        builder.append("- Channel: ").append(chat.source().name()).append('\n');
        if (chat.isInternal()) {
            builder.append("- This is an internal request of the STEM system, not a chat with a person.\n");
        } else if (chat.isGuest()) {
            builder.append("- You are talking to ").append(chat.displayName()).append(", a guest you do not know. Be friendly but do not reveal private information about the household.\n");
        } else {
            builder.append("- You are talking to ").append(chat.name()).append(" (registered user, chat name ").append(chat.displayName()).append(").\n");
            builder.append("- Permissions: ").append(permissions(chat)).append('\n');
        }
        builder.append('\n').append(profileSection(chat)).append(this.memorySection(chat, message));
        MemoryRepository.Summary summary = this.repository.loadSummary(this.config.modelName, chat.chatKey());
        if (!summary.text().isBlank()) {
            builder.append("## Earlier in this chat\n").append(summary.text().trim()).append("\n\n");
        }
        builder.append(RULES);
        return builder.toString();
    }

    /**
     * The stored profile of the person, so Mirra knows names, birthday, language and channels without asking.
     */
    static String profileSection(ChatContext chat) {
        if (chat.isInternal() || chat.identityUser() == null || chat.identityUser().getId() < 0) {
            return "";
        }
        de.linzn.mirra.identitySystem.IdentityUser user = chat.identityUser();
        StringBuilder section = new StringBuilder("## Profile of ").append(user.getIdentityName()).append(user.isGuest() ? " (guest)" : "").append('\n');
        for (String field : de.linzn.mirra.identitySystem.IdentityUser.PROFILE_FIELDS) {
            user.field(field).ifPresent(value -> section.append("- ").append(field).append(": ").append(value).append('\n'));
        }
        user.getAttributes().forEach((key, value) -> section.append("- ").append(key).append(": ").append(value).append('\n'));
        List<String> channels = new ArrayList<>();
        user.getUserTokens().forEach(token -> channels.add(token.getSource().name().toLowerCase(Locale.ROOT)));
        section.append("- reachable via: ").append(channels.isEmpty() ? "-" : String.join(", ", channels)).append('\n');
        section.append("Keep the profile up to date with update_user_profile when the person tells you lasting personal data.\n\n");
        return section.toString();
    }

    private String memorySection(ChatContext chat, String message) {
        if (this.config.memoryContextSize == 0) {
            return "";
        }
        String owner = chat.memoryOwner();
        Map<Integer, MemoryEntry> selected = new LinkedHashMap<>();
        /* the most important facts about the person are always present */
        for (MemoryEntry entry : this.memory.important(owner, 4, 5)) {
            selected.put(entry.id(), entry);
        }
        List<String> owners = chat.isGuest() ? List.of(owner) : List.of(owner, LongTermMemory.GLOBAL);
        float[] vector = this.memory.embedQuery(message);
        for (LongTermMemory.ScoredMemory scored : this.memory.search(owners, message, vector, this.config.memoryContextSize)) {
            selected.putIfAbsent(scored.entry().id(), scored.entry());
        }
        if (selected.isEmpty()) {
            return "## Long term memory\nNothing relevant remembered yet.\n\n";
        }
        StringBuilder personal = new StringBuilder();
        StringBuilder household = new StringBuilder();
        for (MemoryEntry entry : selected.values()) {
            StringBuilder target = entry.isGlobal() ? household : personal;
            target.append("- #").append(entry.id()).append(" [").append(entry.category()).append("] ").append(entry.content()).append('\n');
        }
        this.background.accept(() -> this.memory.markUsed(selected.values()));
        StringBuilder section = new StringBuilder("## Long term memory\n");
        if (!personal.isEmpty()) {
            section.append("About ").append(chat.name()).append(":\n").append(personal);
        }
        if (!household.isEmpty()) {
            section.append("Household:\n").append(household);
        }
        return section.append('\n').toString();
    }

    private static String permissions(ChatContext chat) {
        List<String> names = new ArrayList<>();
        for (AiPermissions permission : AiPermissions.values()) {
            if (chat.has(permission)) {
                names.add(permission.name());
            }
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private void maintainMemory(ChatContext chat, String message, String answer) {
        if (chat.isInternal()) {
            return;
        }
        this.background.accept(() -> {
            if (this.config.autoMemory) {
                Map<String, String> profile = null;
                BiConsumer<String, String> profileUpdate = null;
                if (this.profiles != null && chat.identityUser() != null && chat.identityUser().getId() >= 0) {
                    de.linzn.mirra.identitySystem.IdentityUser user = chat.identityUser();
                    profile = new LinkedHashMap<>();
                    for (String field : de.linzn.mirra.identitySystem.IdentityUser.PROFILE_FIELDS) {
                        Map<String, String> target = profile;
                        user.field(field).ifPresent(value -> target.put(field, value));
                    }
                    profile.putAll(user.getAttributes());
                    profileUpdate = (key, value) -> {
                        try {
                            this.profiles.setProfileValue(user, key, value, "auto");
                        } catch (java.sql.SQLException e) {
                            throw new IllegalStateException(e.getMessage(), e);
                        }
                    };
                }
                this.keeper.extract(chat.memoryOwner(), chat.name(), chat.has(AiPermissions.MEMORY), message, answer,
                        profile, profileUpdate);
            }
            this.keeper.summarizeIfNeeded(chat.chatKey());
        });
    }

    /**
     * One-shot text without tools and without chat history, e.g. for notifications.
     *
     * @param task  what to write
     * @param owner memory owner whose important memories are added as context, may be null
     */
    public String generate(String task, String owner) throws OpenAIException, InterruptedException {
        StringBuilder instructions = new StringBuilder(this.config.personality.trim()).append("\n\n");
        instructions.append("- Now: ").append(NOW_FORMAT.format(ZonedDateTime.now(this.zone))).append('\n');
        if (owner != null) {
            List<MemoryEntry> important = this.memory.important(owner, 3, 8);
            if (!important.isEmpty()) {
                instructions.append("\nWhat you know about the recipient:\n");
                important.forEach(entry -> instructions.append("- ").append(entry.content()).append('\n'));
            }
        }
        JSONObject request = this.baseRequest(this.config.textModel, instructions.toString(), task).put("store", false);
        return this.client.createResponse(request).text();
    }

    /**
     * One-shot structured output.
     *
     * @param schema JSON schema of the result (strict)
     */
    public JSONObject generateJson(String task, String schemaName, JSONObject schema) throws OpenAIException, InterruptedException {
        JSONObject format = new JSONObject().put("type", "json_schema").put("name", schemaName).put("schema", schema).put("strict", true);
        JSONObject request = this.baseRequest(this.config.textModel, this.config.personality, task)
                .put("store", false)
                .put("text", new JSONObject().put("format", format));
        return new JSONObject(this.client.createResponse(request).text());
    }
}
