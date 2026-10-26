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

package de.linzn.mirra.agent.tasks;

import de.linzn.stem.api.StemApi;
import de.linzn.stem.modules.databaseModule.DataContainer;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The STEM events seen so far, with how often they occur and an example. Mirra uses it to find the right event for
 * a task. Stored in the STEM data store so it survives restarts.
 */
public class EventCatalog {

    private static final String KEY = "mirra-event-catalog";
    private static final Duration EXAMPLE_AGE = Duration.ofMinutes(10);

    public record Entry(String name, long count, Instant lastSeen, JSONObject example, Instant exampleTime) {
    }

    private final StemApi api;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public EventCatalog(StemApi api) {
        this.api = api;
    }

    public void load() {
        try {
            this.api.data().get(KEY).map(DataContainer::getJSON).ifPresent(json -> {
                for (String name : json.keySet()) {
                    JSONObject entry = json.getJSONObject(name);
                    this.entries.put(name, new Entry(name, entry.optLong("count"), Instant.parse(entry.getString("lastSeen")),
                            entry.optJSONObject("example", new JSONObject()), Instant.parse(entry.getString("lastSeen"))));
                }
            });
        } catch (Exception ignored) {
            /* no catalog yet or unreadable: start empty */
        }
    }

    /**
     * Count an event; the example is refreshed at most every ten minutes, so frequent events cost little.
     */
    public void seen(String name, Supplier<JSONObject> example) {
        Instant now = Instant.now();
        boolean[] isNew = new boolean[1];
        this.entries.compute(name, (key, old) -> {
            if (old == null) {
                isNew[0] = true;
                return new Entry(name, 1, now, example.get(), now);
            }
            boolean refresh = Duration.between(old.exampleTime(), now).compareTo(EXAMPLE_AGE) > 0;
            return new Entry(name, old.count() + 1, now, refresh ? example.get() : old.example(), refresh ? now : old.exampleTime());
        });
        if (isNew[0]) {
            this.api.scheduler().runAsync(this::save);
        }
    }

    public List<Entry> list() {
        List<Entry> list = new ArrayList<>(this.entries.values());
        list.sort(Comparator.comparing(Entry::lastSeen).reversed());
        return list;
    }

    public void save() {
        JSONObject json = new JSONObject();
        for (Entry entry : this.entries.values()) {
            json.put(entry.name(), new JSONObject().put("count", entry.count()).put("lastSeen", entry.lastSeen().toString())
                    .put("example", entry.example()));
        }
        this.api.data().set(KEY, json);
    }
}
