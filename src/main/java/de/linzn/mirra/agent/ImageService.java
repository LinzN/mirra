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

import de.linzn.mirra.agent.openai.OpenAIClient;
import de.linzn.stem.api.cloud.CloudFileHandle;
import de.linzn.stem.modules.pluginModule.STEMPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

/**
 * Creates images with OpenAI and shares them through the STEM cloud.
 */
public class ImageService {

    private final STEMPlugin plugin;
    private final OpenAIClient client;
    private final String model;

    public ImageService(STEMPlugin plugin, OpenAIClient client, String model) {
        this.plugin = plugin;
        this.client = client;
        this.model = model;
    }

    /**
     * Generate an image.
     *
     * @return the PNG data
     */
    public byte[] generate(String description) throws Exception {
        OpenAIClient.GeneratedImage image = this.client.generateImage(this.model, description);
        return image.base64().isPresent()
                ? Base64.getDecoder().decode(image.base64().get())
                : this.client.download(image.url().orElseThrow());
    }

    /**
     * Generate an image and share it.
     *
     * @return a link to the image: a cloud share link, or the OpenAI link if no cloud is configured
     */
    public String create(String description) throws Exception {
        OpenAIClient.GeneratedImage image = this.client.generateImage(this.model, description);
        boolean cloud = this.plugin.getStemApi().cloud().isEnabled();
        if (!cloud && image.url().isPresent()) {
            /* OpenAI links expire after about an hour */
            return image.url().get();
        }
        if (!cloud) {
            throw new IllegalStateException("The image model returns image data only, a STEM cloud is needed to share it");
        }
        byte[] data = image.base64().isPresent()
                ? Base64.getDecoder().decode(image.base64().get())
                : this.client.download(image.url().orElseThrow());
        return this.upload(data, "mirra_", ".png", "/GeneratedImages/");
    }

    /**
     * Upload data to the STEM cloud and create a public link.
     */
    public String upload(byte[] data, String prefix, String suffix, String folder) throws Exception {
        if (!this.plugin.getStemApi().cloud().isEnabled()) {
            throw new IllegalStateException("No STEM cloud configured");
        }
        Path tempFolder = this.plugin.getTempFolder().toPath().resolve("cloudUpload");
        Files.createDirectories(tempFolder);
        Path file = Files.createTempFile(tempFolder, prefix, suffix);
        try {
            Files.write(file, data);
            Optional<CloudFileHandle> handle = this.plugin.getStemApi().cloud().uploadWithRandomName(file.toFile(), folder);
            return handle.map(CloudFileHandle::createPublicShareLink)
                    .orElseThrow(() -> new IllegalStateException("Upload to the cloud failed"));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
