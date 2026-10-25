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

package de.linzn.mirra.memory;

import de.linzn.mirra.agent.openai.AgentResponse;
import de.linzn.mirra.agent.openai.OpenAIClient;
import de.linzn.stem.api.StemLogger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps the memory of Mirra up to date after every conversation turn:
 * <ul>
 *     <li>extracts lasting facts from the exchange and adds, updates or deletes long term memories</li>
 *     <li>summarizes older parts of a chat, so the context stays small but nothing is forgotten</li>
 * </ul>
 * Both run in the background and never delay the answer.
 */
public class MemoryKeeper {

    private static final String EXTRACTION_PROMPT = """
            You maintain the long term memory of the assistant "Mirra". Read the latest exchange and decide which
            information is worth remembering beyond this conversation.

            Remember: stable facts about the person (names, family, birthdays, work, home, devices they own),
            preferences and habits, plans and appointments with a date, and facts about the household.
            Do not remember: small talk, greetings, questions without new information, temporary states
            ("I am tired"), live sensor values, anything the assistant said on its own, or secrets like passwords.

            Rules:
            - Write each memory as one short standalone statement in English with the name of the person,
              e.g. "Niklas' birthday is on May 4th".
            - If a known memory is outdated or contradicted, use "update" with its id instead of adding a new one.
            - If the person asks to forget something, use "delete" with the id.
            - Use scope "global" only for facts about the household or everybody, otherwise "user".
            - Importance: 5 essential identity facts, 4 important preferences/relations, 3 normal, 1-2 minor.
            - Return an empty list if there is nothing worth remembering. That is the normal case.
            """;

    private static final String SUMMARY_PROMPT = """
            Summarize this chat between the assistant "Mirra" and the user for Mirra's own reference.
            Keep everything that matters for later conversations: topics, decisions, open questions, promises,
            requests that are still pending. Leave out greetings and small talk. Write compact bullet points in
            the language of the chat, at most 250 words. Merge the previous summary with the new messages.
            """;

    private final OpenAIClient client;
    private final LongTermMemory memory;
    private final MemoryRepository repository;
    private final StemLogger logger;
    private final String memoryModel;
    private final String chatModel;
    private final int historyMessages;
    private final int summaryThreshold;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * @param chatModel        model name used as key of the chat history
     * @param memoryModel      OpenAI model for extraction and summaries
     * @param historyMessages  messages the agent sends as history; these are never summarized away
     * @param summaryThreshold summarize when this many messages are not covered by the summary yet
     */
    public MemoryKeeper(OpenAIClient client, LongTermMemory memory, MemoryRepository repository, StemLogger logger,
                        String chatModel, String memoryModel, int historyMessages, int summaryThreshold) {
        this.client = client;
        this.memory = memory;
        this.repository = repository;
        this.logger = logger;
        this.chatModel = chatModel;
        this.memoryModel = memoryModel;
        this.historyMessages = historyMessages;
        this.summaryThreshold = summaryThreshold;
    }

    /**
     * Extract memories from one exchange.
     *
     * @param owner       memory owner of the person
     * @param personName  name of the person
     * @param allowGlobal whether the person may create household memories
     * @param userMessage message of the person
     * @param answer      answer of Mirra
     * @return number of changed memories
     */
    public int extract(String owner, String personName, boolean allowGlobal, String userMessage, String answer) {
        ReentrantLock lock = this.locks.computeIfAbsent(owner, key -> new ReentrantLock());
        lock.lock();
        try {
            String exchange = personName + ": " + userMessage + "\nMirra: " + answer;
            List<String> owners = allowGlobal ? List.of(owner, LongTermMemory.GLOBAL) : List.of(owner);
            float[] vector = this.memory.embedQuery(userMessage);
            List<LongTermMemory.ScoredMemory> related = this.memory.search(owners, userMessage, vector, 15);

            JSONArray known = new JSONArray();
            Set<Integer> knownIds = new HashSet<>();
            for (LongTermMemory.ScoredMemory scored : related) {
                MemoryEntry entry = scored.entry();
                knownIds.add(entry.id());
                known.put(new JSONObject().put("id", entry.id()).put("scope", entry.isGlobal() ? "global" : "user")
                        .put("category", entry.category()).put("content", entry.content()));
            }
            String input = "Today: " + LocalDate.now() + "\nPerson: " + personName
                    + "\n\nKnown related memories:\n" + known.toString(2)
                    + "\n\nLatest exchange:\n" + exchange;

            JSONObject request = new JSONObject()
                    .put("model", this.memoryModel)
                    .put("instructions", EXTRACTION_PROMPT)
                    .put("input", input)
                    .put("store", false)
                    .put("text", new JSONObject().put("format", operationsFormat()));
            AgentResponse response = this.client.createResponse(request);
            JSONObject result = new JSONObject(response.text());
            return this.apply(result.optJSONArray("operations"), owner, allowGlobal, knownIds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            this.logger.warning("Memory extraction failed: " + e.getMessage());
            return 0;
        } finally {
            lock.unlock();
        }
    }

    private int apply(JSONArray operations, String owner, boolean allowGlobal, Set<Integer> knownIds) {
        if (operations == null) {
            return 0;
        }
        int changed = 0;
        for (int i = 0; i < operations.length(); i++) {
            JSONObject operation = operations.optJSONObject(i);
            if (operation == null) {
                continue;
            }
            String action = operation.optString("action");
            String content = operation.optString("content", "").trim();
            String category = operation.optString("category", "other");
            int importance = operation.optInt("importance", 3);
            String targetOwner = allowGlobal && "global".equals(operation.optString("scope")) ? LongTermMemory.GLOBAL : owner;
            Integer id = operation.isNull("id") ? null : operation.optInt("id");
            try {
                switch (action) {
                    case "add" -> {
                        if (!content.isEmpty()) {
                            MemoryEntry entry = this.memory.remember(targetOwner, category, content, importance, "auto");
                            this.logger.debug("Memory added #" + entry.id() + " for " + targetOwner + ": " + content);
                            changed++;
                        }
                    }
                    case "update" -> {
                        /* only memories the model has seen and the person may change */
                        if (id != null && knownIds.contains(id) && !content.isEmpty() && this.mayChange(id, owner, allowGlobal)) {
                            this.memory.update(id, content, category, importance);
                            this.logger.debug("Memory updated #" + id + ": " + content);
                            changed++;
                        }
                    }
                    case "delete" -> {
                        if (id != null && knownIds.contains(id) && this.mayChange(id, owner, allowGlobal) && this.memory.forget(id)) {
                            this.logger.debug("Memory deleted #" + id);
                            changed++;
                        }
                    }
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                this.logger.warning("Could not apply memory operation " + operation + ": " + e.getMessage());
            }
        }
        return changed;
    }

    private boolean mayChange(int id, String owner, boolean allowGlobal) {
        return this.memory.get(id)
                .map(entry -> entry.owner().equals(owner) || (allowGlobal && entry.isGlobal()))
                .orElse(false);
    }

    /**
     * Summarize the older part of a chat if enough new messages came in.
     *
     * @return true if a new summary was written
     */
    public boolean summarizeIfNeeded(String chatKey) {
        ReentrantLock lock = this.locks.computeIfAbsent("chat:" + chatKey, key -> new ReentrantLock());
        if (!lock.tryLock()) {
            return false;
        }
        try {
            MemoryRepository.Summary summary = this.repository.loadSummary(this.chatModel, chatKey);
            List<MemoryRepository.StoredMessage> pending = this.repository.messagesAfter(this.chatModel, chatKey, summary.coveredUntil(), 1000);
            /* the newest messages are sent as history anyway and stay out of the summary */
            int summarizeCount = pending.size() - this.historyMessages;
            if (pending.size() < this.summaryThreshold || summarizeCount < 1) {
                return false;
            }
            List<MemoryRepository.StoredMessage> toSummarize = pending.subList(0, summarizeCount);
            StringBuilder input = new StringBuilder();
            if (!summary.text().isBlank()) {
                input.append("Previous summary:\n").append(summary.text()).append("\n\n");
            }
            input.append("New messages:\n");
            for (MemoryRepository.StoredMessage message : toSummarize) {
                input.append(message.role().equals("assistant") ? "Mirra" : "User").append(": ").append(message.content()).append('\n');
            }
            JSONObject request = new JSONObject()
                    .put("model", this.memoryModel)
                    .put("instructions", SUMMARY_PROMPT)
                    .put("input", input.toString())
                    .put("store", false);
            String text = this.client.createResponse(request).text();
            if (text.isBlank()) {
                return false;
            }
            long coveredUntil = toSummarize.get(toSummarize.size() - 1).id();
            this.repository.saveSummary(this.chatModel, chatKey, new MemoryRepository.Summary(text, coveredUntil));
            this.logger.debug("Chat " + chatKey + " summarized up to message " + coveredUntil);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            this.logger.warning("Chat summary failed: " + e.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    private static JSONObject operationsFormat() {
        JSONObject operation = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("action", new JSONObject().put("type", "string").put("enum", new JSONArray().put("add").put("update").put("delete")))
                        .put("id", new JSONObject().put("type", new JSONArray().put("integer").put("null")))
                        .put("scope", new JSONObject().put("type", "string").put("enum", new JSONArray().put("user").put("global")))
                        .put("category", new JSONObject().put("type", "string").put("enum", new JSONArray(LongTermMemory.CATEGORIES)))
                        .put("content", new JSONObject().put("type", "string"))
                        .put("importance", new JSONObject().put("type", "integer")))
                .put("required", new JSONArray().put("action").put("id").put("scope").put("category").put("content").put("importance"))
                .put("additionalProperties", false);
        JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject().put("operations", new JSONObject().put("type", "array").put("items", operation)))
                .put("required", new JSONArray().put("operations"))
                .put("additionalProperties", false);
        return new JSONObject().put("type", "json_schema").put("name", "memory_operations").put("schema", schema).put("strict", true);
    }
}
