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

package de.linzn.mirra.messaging;

import java.util.Locale;
import java.util.Map;

/**
 * A file Mirra sends.
 *
 * @param fileName name shown to the receiver
 * @param mimeType MIME type
 * @param data     content
 */
public record OutgoingFile(String fileName, String mimeType, byte[] data) {

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("txt", "text/plain"), Map.entry("log", "text/plain"), Map.entry("md", "text/markdown"),
            Map.entry("csv", "text/csv"), Map.entry("json", "application/json"), Map.entry("xml", "application/xml"),
            Map.entry("yml", "text/yaml"), Map.entry("yaml", "text/yaml"), Map.entry("html", "text/html"),
            Map.entry("sh", "text/x-shellscript"), Map.entry("py", "text/x-python"), Map.entry("java", "text/x-java"),
            Map.entry("js", "text/javascript"), Map.entry("sql", "application/sql"), Map.entry("ics", "text/calendar"),
            Map.entry("pdf", "application/pdf"), Map.entry("zip", "application/zip"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"), Map.entry("gif", "image/gif"), Map.entry("svg", "image/svg+xml"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("ogg", "audio/ogg"), Map.entry("opus", "audio/ogg"),
            Map.entry("wav", "audio/wav"), Map.entry("mp4", "video/mp4"));

    /**
     * Create a file and guess the MIME type from the file name.
     */
    public static OutgoingFile of(String fileName, byte[] data) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return new OutgoingFile(fileName, TYPES.getOrDefault(extension, "application/octet-stream"), data);
    }

    /**
     * @return image, video, audio or document (the categories of WhatsApp)
     */
    public String category() {
        if (this.mimeType.equals("image/png") || this.mimeType.equals("image/jpeg") || this.mimeType.equals("image/webp")) {
            return "image";
        }
        if (this.mimeType.startsWith("video/")) {
            return "video";
        }
        if (this.mimeType.startsWith("audio/")) {
            return "audio";
        }
        return "document";
    }
}
