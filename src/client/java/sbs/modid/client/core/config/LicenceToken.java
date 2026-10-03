/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config;

import com.google.gson.JsonObject;
import sbs.modid.SkyblockSimplifiedSBS;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The SBS licence token, stored in {@code config/sbs/license/token.json} and shared by every config
 * profile.
 *
 * <p>It used to sit in the config, which was fine while there was one config. With
 * {@link ConfigProfiles} there is one per profile, and the token went with it: creating a profile
 * started you on a fresh config with no token, so the price API answered 401 until you pasted it
 * again - per profile, forever. The token is not a preference about how the mod behaves, it is
 * <b>who you are</b>, so it belongs outside the thing that varies.
 *
 * <p>Loaded lazily and cached in memory; the file is tiny and written only when the token changes.
 */
public final class LicenceToken {

    private static LicenceToken instance;

    /** {@code null} until first read from disk; {@code ""} means "no token set". */
    private String token;

    private LicenceToken() {
    }

    public static LicenceToken getInstance() {
        if (instance == null) {
            instance = new LicenceToken();
        }
        return instance;
    }

    /** The token, or {@code ""} when none is set. Never {@code null}. */
    public String get() {
        if (token == null) {
            token = read();
        }
        return token;
    }

    /** True when a token is configured - the check every API caller makes before sending a request. */
    public boolean isSet() {
        return !get().isBlank();
    }

    /** Stores the token (trimmed) and writes it straight through. */
    public void set(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.equals(get())) {
            return;
        }
        token = trimmed;
        write(trimmed);
    }

    /**
     * Adopts a token from an old config that still carried one, unless a token file already exists.
     * Called once from {@link ConfigManager}'s load; the config field is dropped afterwards.
     */
    void adoptLegacy(String legacy) {
        if (legacy == null || legacy.isBlank() || Files.exists(SBSFiles.licenseTokenFile())) {
            return;
        }
        token = legacy.trim();
        write(token);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Moved the licence token out of the config into {}",
                SBSFiles.licenseTokenFile());
    }

    private String read() {
        Path path = SBSFiles.licenseTokenFile();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    JsonObject root = SBSFiles.GSON.fromJson(reader, JsonObject.class);
                    if (root != null && root.has("token")) {
                        return root.get("token").getAsString().trim();
                    }
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to read the licence token", e);
        }
        return adoptFromBaseConfig();
    }

    /**
     * Last resort before answering "no token": read it straight out of the base {@code config.json}.
     *
     * <p>The token always lived in the Default profile's config, and {@link ConfigManager}'s
     * migration only sees the config it actually loads - so a player sitting on a custom profile
     * would stay tokenless until they switched back to Default just to hand it over. Reading the base
     * config here makes the move happen wherever they are, once.
     */
    private String adoptFromBaseConfig() {
        try {
            Path base = SBSFiles.configFile();
            if (!Files.exists(base)) {
                return "";
            }
            try (Reader reader = Files.newBufferedReader(base, StandardCharsets.UTF_8)) {
                JsonObject root = SBSFiles.GSON.fromJson(reader, JsonObject.class);
                JsonObject licence = root != null && root.has("licence")
                        ? root.getAsJsonObject("licence") : null;
                if (licence == null || !licence.has("token")) {
                    return "";
                }
                String legacy = licence.get("token").getAsString().trim();
                if (!legacy.isEmpty()) {
                    write(legacy);
                    SkyblockSimplifiedSBS.LOGGER.info(
                            "[SBS] Adopted the licence token from config.json into {}",
                            SBSFiles.licenseTokenFile());
                }
                return legacy;
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to adopt the licence token from the config", e);
            return "";
        }
    }

    private void write(String value) {
        Path path = SBSFiles.licenseTokenFile();
        try {
            SBSFiles.ensureParent(path);
            JsonObject root = new JsonObject();
            root.addProperty("token", value);
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                SBSFiles.GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to write the licence token", e);
        }
    }
}
