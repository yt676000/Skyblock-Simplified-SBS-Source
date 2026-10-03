/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.texture.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.server.packs.repository.PackRepository;
import sbs.modid.SkyblockSimplifiedSBS;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A third-party SkyBlock resource pack the Texture Pack module can switch to - <b>once the player
 * has installed it themselves</b>.
 *
 * <p>The mod never downloads a pack. "Open download page" sends the player to the pack's official
 * page; they put the file into {@code resourcepacks/}, and {@link #installedIds} finds it there by
 * its file name or, failing that, by the description in its {@code pack.mcmeta}. From then on
 * {@link #setActive} turns it on and off through the normal pack selection, exactly as the vanilla
 * pack screen's Done button does.
 *
 * <p>Both packs used to be fetched from Modrinth's API by the mod. CurseForge rejected a build for
 * fetching Hypixel's own pack the same way, and these were the same pattern waiting to be.
 */
public enum UserPack {

    HYPIXEL_PLUS("Hypixel+", "https://modrinth.com/resourcepack/hypixel-plus",
            "hypixel+", "hypixelplus", "hypixel plus"),
    FURFSKY_REBORN("Furfsky Reborn", "https://modrinth.com/resourcepack/furfsky-reborn",
            "furfsky");

    /** How long a folder scan is trusted - the mode picker asks every time the page draws. */
    private static final long SCAN_TTL_MS = 2000L;

    /** "Never scanned". Not {@code Long.MIN_VALUE}: {@code now - NEVER} must not overflow. */
    private static final long NEVER = Long.MIN_VALUE / 2;

    private final String displayName;
    private final String pageUrl;
    private final String[] markers;

    private volatile List<String> cachedIds = List.of();
    private volatile long scannedAt = NEVER;

    UserPack(String displayName, String pageUrl, String... markers) {
        this.displayName = displayName;
        this.pageUrl = pageUrl;
        this.markers = markers;
    }

    public String displayName() {
        return displayName;
    }

    /** The pack's official download page - opened, never fetched. */
    public String pageUrl() {
        return pageUrl;
    }

    /** Whether the player has this pack in their resourcepacks folder. */
    public boolean isInstalled() {
        return !installedIds().isEmpty();
    }

    /** Opens the official download page, behind vanilla's "open this link?" confirmation. */
    public void openDownloadPage() {
        ConfirmLinkScreen.confirmLinkNow(Minecraft.getInstance().gui.screen(), pageUrl);
    }

    /**
     * Pack ids ({@code file/<name>}) of every copy in {@code resourcepacks/}, sorted by name. More
     * than one is possible - an old and a new version side by side.
     */
    public List<String> installedIds() {
        long now = System.currentTimeMillis();
        if (now - scannedAt < SCAN_TTL_MS) {
            return cachedIds;
        }
        List<String> ids = new ArrayList<>();
        Path dir = Minecraft.getInstance().getResourcePackDirectory();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    if (isThisPack(entry)) {
                        ids.add("file/" + entry.getFileName());
                    }
                }
            } catch (Exception e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Pack] could not scan {}", dir, e);
            }
        }
        ids.sort(null);
        cachedIds = List.copyOf(ids);
        scannedAt = now;
        return cachedIds;
    }

    private boolean isThisPack(Path entry) {
        String name = entry.getFileName().toString();
        boolean zip = name.toLowerCase(Locale.ROOT).endsWith(".zip") && Files.isRegularFile(entry);
        if (!zip && !Files.isDirectory(entry)) {
            return false;
        }
        return matches(name) || description(entry, zip).map(this::matches).orElse(false);
    }

    /** Whether a file name or pack description names this pack. Package-private for the test. */
    boolean matches(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        String squashed = lower.replace(" ", "");
        for (String marker : markers) {
            if (lower.contains(marker) || squashed.contains(marker.replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }

    /** The {@code pack.description} of a zip or folder pack, as JSON text. */
    private static Optional<String> description(Path entry, boolean zip) {
        try {
            if (zip) {
                try (ZipFile file = new ZipFile(entry.toFile())) {
                    ZipEntry meta = file.getEntry("pack.mcmeta");
                    if (meta == null) {
                        return Optional.empty();
                    }
                    try (InputStream in = file.getInputStream(meta)) {
                        return readDescription(in);
                    }
                }
            }
            Path meta = entry.resolve("pack.mcmeta");
            if (!Files.isRegularFile(meta)) {
                return Optional.empty();
            }
            try (InputStream in = Files.newInputStream(meta)) {
                return readDescription(in);
            }
        } catch (Exception e) {
            return Optional.empty(); // not a readable pack - so not ours
        }
    }

    private static Optional<String> readDescription(InputStream in) {
        JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject pack = root.getAsJsonObject("pack");
        if (pack == null || !pack.has("description")) {
            return Optional.empty();
        }
        // A text component may be a string, an object or an array; its JSON text holds the words.
        return Optional.of(pack.get("description").toString());
    }

    /**
     * Enables/disables the installed pack via the normal pack-repository selection + resource
     * reload. Must run on the client thread. Enabling picks the last copy by name (usually the
     * newest version); disabling removes every copy. No-ops when nothing is installed.
     */
    public void setActive(boolean active) {
        scannedAt = NEVER; // the player may have just dropped a file in
        List<String> ids = installedIds();
        Minecraft minecraft = Minecraft.getInstance();
        PackRepository repository = minecraft.getResourcePackRepository();
        repository.reload();
        boolean changed = false;
        if (active) {
            boolean alreadyOn = ids.stream().anyMatch(repository.getSelectedIds()::contains);
            for (int i = ids.size() - 1; i >= 0 && !changed && !alreadyOn; i--) {
                String id = ids.get(i);
                changed = repository.getAvailableIds().contains(id) && repository.addPack(id);
            }
        } else {
            // removePack only works on selected packs; recompute the selection without ours.
            List<String> selected = new ArrayList<>(repository.getSelectedIds());
            changed = selected.removeAll(ids);
            if (changed) {
                repository.setSelected(selected);
            }
        }
        if (changed) {
            // Persists options.resourcePacks AND triggers the resource reload itself (verified in
            // the 26.2 bytecode) - no extra reloadResourcePacks() call, or everything loads twice.
            minecraft.options.updateResourcePacks(repository);
        }
    }
}
