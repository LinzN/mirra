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

import java.util.List;

/**
 * Persistence of chat history, conversation summaries and long term memories.
 */
public interface MemoryRepository {

    /**
     * A chat message.
     *
     * @param id      database id, increasing
     * @param role    user or assistant
     * @param content text
     */
    record StoredMessage(long id, String role, String content) {
    }

    /**
     * Summary of the older part of a chat.
     *
     * @param text         summary text
     * @param coveredUntil id of the last message the summary contains
     */
    record Summary(String text, long coveredUntil) {
        public static final Summary EMPTY = new Summary("", 0);
    }

    /* chat history */

    long appendMessage(String model, String chatKey, String role, String content);

    /**
     * @return the newest messages, oldest first
     */
    List<StoredMessage> recentMessages(String model, String chatKey, int limit);

    /**
     * @return messages with an id greater than {@code afterId}, oldest first
     */
    List<StoredMessage> messagesAfter(String model, String chatKey, long afterId, int limit);

    /* summaries */

    Summary loadSummary(String model, String chatKey);

    void saveSummary(String model, String chatKey, Summary summary);

    /* long term memory */

    List<MemoryEntry> loadMemories();

    /**
     * @return the entry with its new id
     */
    MemoryEntry insertMemory(MemoryEntry entry);

    void updateMemory(MemoryEntry entry);

    void deleteMemory(int id);

    void markUsed(List<Integer> ids);
}
