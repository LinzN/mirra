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

import de.linzn.stem.api.StemLogger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long term memory of Mirra: short facts about people (one owner per person) and the household
 * ({@link #GLOBAL}). All entries are cached in RAM; search uses embeddings (semantic) and falls back to
 * keywords if no vector is available.
 */
public class LongTermMemory {

    /**
     * Owner of memories everybody may see.
     */
    public static final String GLOBAL = "global";
    public static final List<String> CATEGORIES = List.of("fact", "preference", "person", "event", "plan", "other");

    /* minimum similarity for a memory to be considered relevant */
    private static final double MIN_SCORE = 0.25;
    /* a new memory this similar to an existing one of the same owner replaces it instead of being added */
    private static final double DUPLICATE_SCORE = 0.92;

    private final MemoryRepository repository;
    private final Embedder embedder;
    private final StemLogger logger;
    private final Map<Integer, MemoryEntry> entries = new ConcurrentHashMap<>();

    /**
     * Computes embeddings; implemented with the OpenAI embeddings endpoint.
     */
    public interface Embedder {
        List<float[]> embed(List<String> texts) throws Exception;

        String model();
    }

    /**
     * @param entry memory
     * @param score relevance, higher is better
     */
    public record ScoredMemory(MemoryEntry entry, double score) {
    }

    public LongTermMemory(MemoryRepository repository, Embedder embedder, StemLogger logger) {
        this.repository = repository;
        this.embedder = embedder;
        this.logger = logger;
    }

    /**
     * Load all memories into the cache.
     */
    public void load() {
        this.entries.clear();
        for (MemoryEntry entry : this.repository.loadMemories()) {
            this.entries.put(entry.id(), entry);
        }
        this.logger.info("Loaded " + this.entries.size() + " long term memories");
    }

    /**
     * Compute missing embeddings, e.g. of imported memories or after a change of the embedding model.
     * Takes a while for many memories, so call it in the background.
     *
     * @return number of memories that got a new embedding
     */
    public int embedMissing() {
        List<MemoryEntry> missing = new ArrayList<>();
        for (MemoryEntry entry : this.entries.values()) {
            if (entry.embedding() == null || !this.embedder.model().equals(entry.embeddingModel())) {
                missing.add(entry);
            }
        }
        int done = 0;
        for (int from = 0; from < missing.size(); from += 64) {
            List<MemoryEntry> batch = missing.subList(from, Math.min(missing.size(), from + 64));
            try {
                List<float[]> vectors = this.embedder.embed(batch.stream().map(MemoryEntry::content).toList());
                for (int i = 0; i < batch.size(); i++) {
                    MemoryEntry current = this.entries.get(batch.get(i).id());
                    if (current == null) {
                        continue;
                    }
                    MemoryEntry updated = current.withEmbedding(vectors.get(i), this.embedder.model());
                    this.repository.updateMemory(updated);
                    this.entries.put(updated.id(), updated);
                    done++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                this.logger.warning("Could not compute embeddings for " + batch.size() + " memories, keyword search is used for them: " + e.getMessage());
                break;
            }
        }
        if (done > 0) {
            this.logger.info("Computed embeddings for " + done + " memories");
        }
        return done;
    }

    public int size() {
        return this.entries.size();
    }

    public Optional<MemoryEntry> get(int id) {
        return Optional.ofNullable(this.entries.get(id));
    }

    /**
     * @return vector for a query, null if embeddings are not available
     */
    public float[] embedQuery(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return this.embedder.embed(List.of(text)).get(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            this.logger.warning("Embedding failed, using keyword search: " + e.getMessage());
            return null;
        }
    }

    /**
     * Find the most relevant memories of the given owners.
     *
     * @param owners      owners whose memories may be returned
     * @param query       search text (used for the keyword fallback)
     * @param queryVector embedding of the query, may be null
     * @param limit       maximum number of results
     */
    public List<ScoredMemory> search(Collection<String> owners, String query, float[] queryVector, int limit) {
        Set<String> ownerSet = new HashSet<>(owners);
        Set<String> words = keywords(query);
        List<ScoredMemory> scored = new ArrayList<>();
        for (MemoryEntry entry : this.entries.values()) {
            if (!ownerSet.contains(entry.owner())) {
                continue;
            }
            double score;
            if (queryVector != null && entry.embedding() != null && entry.embedding().length == queryVector.length) {
                score = VectorMath.cosine(queryVector, entry.embedding());
            } else {
                score = keywordScore(words, entry.content());
            }
            /* important memories win close races */
            score += 0.02 * (entry.importance() - 3);
            if (score >= MIN_SCORE) {
                scored.add(new ScoredMemory(entry, score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
        return scored.size() <= limit ? scored : new ArrayList<>(scored.subList(0, limit));
    }

    /**
     * @return memories of the owner with at least the given importance, most important first
     */
    public List<MemoryEntry> important(String owner, int minImportance, int limit) {
        return this.entries.values().stream()
                .filter(entry -> entry.owner().equals(owner) && entry.importance() >= minImportance)
                .sorted(Comparator.comparingInt(MemoryEntry::importance).reversed().thenComparing(MemoryEntry::updated, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    public List<MemoryEntry> byOwner(String owner) {
        return this.entries.values().stream()
                .filter(entry -> entry.owner().equals(owner))
                .sorted(Comparator.comparingInt(MemoryEntry::id))
                .toList();
    }

    /**
     * Store a memory. A very similar memory of the same owner is replaced instead.
     *
     * @return the stored entry
     */
    public synchronized MemoryEntry remember(String owner, String category, String content, int importance, String source) {
        String text = content.trim();
        String cat = CATEGORIES.contains(category) ? category : "other";
        int imp = Math.max(1, Math.min(5, importance));
        float[] vector = this.embedQuery(text);
        if (vector != null) {
            for (ScoredMemory similar : this.search(List.of(owner), text, vector, 1)) {
                if (similar.score() - 0.02 * (similar.entry().importance() - 3) >= DUPLICATE_SCORE) {
                    return this.update(similar.entry().id(), text, cat, Math.max(imp, similar.entry().importance()), vector);
                }
            }
        }
        Instant now = Instant.now();
        MemoryEntry entry = new MemoryEntry(0, owner, cat, text, imp, vector, vector == null ? null : this.embedder.model(), source, now, now, null, 0);
        MemoryEntry stored = this.repository.insertMemory(entry);
        this.entries.put(stored.id(), stored);
        return stored;
    }

    /**
     * Change the content of a memory.
     */
    public synchronized MemoryEntry update(int id, String content, String category, int importance) {
        return this.update(id, content.trim(), category, importance, this.embedQuery(content.trim()));
    }

    private MemoryEntry update(int id, String content, String category, int importance, float[] vector) {
        MemoryEntry existing = this.entries.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("No memory with id " + id);
        }
        String cat = CATEGORIES.contains(category) ? category : existing.category();
        MemoryEntry updated = existing.withContent(content, cat, Math.max(1, Math.min(5, importance)));
        if (vector != null) {
            updated = updated.withEmbedding(vector, this.embedder.model());
        }
        this.repository.updateMemory(updated);
        this.entries.put(id, updated);
        return updated;
    }

    public synchronized boolean forget(int id) {
        if (this.entries.remove(id) == null) {
            return false;
        }
        this.repository.deleteMemory(id);
        return true;
    }

    /**
     * Remember that these memories were used in a conversation.
     */
    public void markUsed(Collection<MemoryEntry> used) {
        List<Integer> ids = new ArrayList<>();
        for (MemoryEntry entry : used) {
            this.entries.computeIfPresent(entry.id(), (key, current) -> current.used());
            ids.add(entry.id());
        }
        this.repository.markUsed(ids);
    }

    private static Set<String> keywords(String text) {
        Set<String> words = new HashSet<>();
        if (text == null) {
            return words;
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3) {
                words.add(word);
            }
        }
        return words;
    }

    private static double keywordScore(Set<String> words, String content) {
        if (words.isEmpty()) {
            return 0;
        }
        Set<String> contentWords = keywords(content);
        int hits = 0;
        for (String word : words) {
            if (contentWords.contains(word)) {
                hits++;
            }
        }
        /* one matching word is enough to be relevant */
        return hits == 0 ? 0 : 0.25 + 0.5 * hits / words.size();
    }
}
