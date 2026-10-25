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

package de.linzn.mirra.agent.attachment;

import java.util.Locale;
import java.util.Map;

/**
 * A file somebody sent to Mirra (Discord attachment, WhatsApp media).
 *
 * @param fileName file name, may be generated for media without a name
 * @param mimeType MIME type as reported by the chat service, may be null
 * @param data     content
 * @param voice    recorded voice message
 */
public record Attachment(String fileName, String mimeType, byte[] data, boolean voice) {

    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"), Map.entry("image/png", "png"), Map.entry("image/webp", "webp"),
            Map.entry("image/gif", "gif"), Map.entry("application/pdf", "pdf"),
            Map.entry("audio/ogg", "ogg"), Map.entry("audio/mpeg", "mp3"), Map.entry("audio/mp4", "m4a"),
            Map.entry("audio/aac", "aac"), Map.entry("audio/wav", "wav"), Map.entry("audio/webm", "webm"),
            Map.entry("video/mp4", "mp4"), Map.entry("text/plain", "txt"), Map.entry("text/csv", "csv"),
            Map.entry("application/json", "json"));

    public Attachment {
        if (mimeType != null) {
            /* "audio/ogg; codecs=opus" -> "audio/ogg" */
            int separator = mimeType.indexOf(';');
            mimeType = (separator >= 0 ? mimeType.substring(0, separator) : mimeType).trim().toLowerCase(Locale.ROOT);
            if (mimeType.isEmpty()) {
                mimeType = null;
            }
        }
        if (fileName == null || fileName.isBlank()) {
            String extension = mimeType == null ? "bin" : EXTENSIONS.getOrDefault(mimeType, "bin");
            fileName = (voice ? "voice" : "file") + "." + extension;
        }
    }

    /**
     * @return lower case extension without dot, empty if there is none
     */
    public String extension() {
        int dot = this.fileName.lastIndexOf('.');
        return dot < 0 || dot == this.fileName.length() - 1 ? "" : this.fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public long size() {
        return this.data == null ? 0 : this.data.length;
    }
}
