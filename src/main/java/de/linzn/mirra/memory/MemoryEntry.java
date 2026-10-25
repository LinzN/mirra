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

import java.time.Instant;

/**
 * One long term memory. Immutable; changes create a new instance.
 *
 * @param id             database id
 * @param owner          {@code identity:<name>}, {@code token:<source>:<id>} or {@link LongTermMemory#GLOBAL}
 * @param category       fact, preference, person, event, plan, other
 * @param content        short standalone statement
 * @param importance     1 (trivial) to 5 (essential)
 * @param embedding      vector of the content, null if not computed yet
 * @param embeddingModel model that computed the vector
 * @param source         auto, tool, legacy
 */
public record MemoryEntry(int id, String owner, String category, String content, int importance,
                          float[] embedding, String embeddingModel, String source,
                          Instant created, Instant updated, Instant lastUsed, int useCount) {

    public MemoryEntry withContent(String newContent, String newCategory, int newImportance) {
        return new MemoryEntry(id, owner, newCategory, newContent, newImportance, null, null, source, created, Instant.now(), lastUsed, useCount);
    }

    public MemoryEntry withEmbedding(float[] vector, String model) {
        return new MemoryEntry(id, owner, category, content, importance, vector, model, source, created, updated, lastUsed, useCount);
    }

    public MemoryEntry used() {
        return new MemoryEntry(id, owner, category, content, importance, embedding, embeddingModel, source, created, updated, Instant.now(), useCount + 1);
    }

    public MemoryEntry withId(int newId) {
        return new MemoryEntry(newId, owner, category, content, importance, embedding, embeddingModel, source, created, updated, lastUsed, useCount);
    }

    public boolean isGlobal() {
        return LongTermMemory.GLOBAL.equals(this.owner);
    }
}
