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

package de.linzn.mirra.ssh;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A server Mirra can log in to, with its own key pair in the {@link SshKeyStore}.
 *
 * @param name         name in Mirra, also the file name of the key and the host key alias
 * @param host         host name or address
 * @param port         SSH port
 * @param user         login user on the server
 * @param level        level as installed on the server (read back with {@code __mirra_info__})
 * @param from         source addresses for the from="..." option of the key, "-" for any
 * @param hostKeys     host keys of the server as "type base64", pinned once trusted
 * @param trusted      an owner confirmed the host key fingerprints
 * @param enabled      false: Mirra does not use the server
 * @param created      when the server was added
 * @param keyCreated   when the current key pair was generated
 * @param lastUsed     last command, may be null
 */
public record SshServer(String name, String host, int port, String user, SshLevel level, String from,
                        List<String> hostKeys, boolean trusted, boolean enabled, Instant created, Instant keyCreated,
                        Instant lastUsed) {

    public SshServer {
        hostKeys = List.copyOf(hostKeys);
    }

    public SshServer withTrusted(boolean value) {
        return new SshServer(name, host, port, user, level, from, hostKeys, value, enabled, created, keyCreated, lastUsed);
    }

    public SshServer withEnabled(boolean value) {
        return new SshServer(name, host, port, user, level, from, hostKeys, trusted, value, created, keyCreated, lastUsed);
    }

    public SshServer withLevel(SshLevel value) {
        return new SshServer(name, host, port, user, value, from, hostKeys, trusted, enabled, created, keyCreated, lastUsed);
    }

    public SshServer withKeyCreated(Instant value) {
        return new SshServer(name, host, port, user, level, from, hostKeys, trusted, enabled, created, value, lastUsed);
    }

    public SshServer withLastUsed(Instant value) {
        return new SshServer(name, host, port, user, level, from, hostKeys, trusted, enabled, created, keyCreated, value);
    }

    public SshServer withHostKeys(List<String> value) {
        return new SshServer(name, host, port, user, level, from, value, false, enabled, created, keyCreated, lastUsed);
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("name", name).put("host", host).put("port", port).put("user", user).put("level", level.id())
                .put("from", from).put("hostKeys", new JSONArray(hostKeys)).put("trusted", trusted).put("enabled", enabled)
                .put("created", created.toString()).put("keyCreated", keyCreated.toString())
                .put("lastUsed", lastUsed == null ? JSONObject.NULL : lastUsed.toString());
    }

    public static SshServer fromJson(JSONObject json) {
        List<String> keys = new ArrayList<>();
        JSONArray array = json.optJSONArray("hostKeys");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                keys.add(array.getString(i));
            }
        }
        String lastUsed = json.optString("lastUsed", "");
        return new SshServer(json.getString("name"), json.getString("host"), json.getInt("port"), json.getString("user"),
                SshLevel.parse(json.getString("level")), json.optString("from", "-"), keys, json.optBoolean("trusted"),
                json.optBoolean("enabled", true), Instant.parse(json.getString("created")),
                Instant.parse(json.getString("keyCreated")),
                lastUsed.isEmpty() || lastUsed.equals("null") ? null : Instant.parse(lastUsed));
    }
}
