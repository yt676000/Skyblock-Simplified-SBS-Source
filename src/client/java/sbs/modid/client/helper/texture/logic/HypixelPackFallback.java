/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.texture.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Keeps the Hypixel SkyBlock pack loaded at the <b>bottom</b> of the pack stack while "Ignore
 * Enforced Texture Packs" is on.
 *
 * <p>This is the safety net of the pack-bypass approach: the server push is blocked (so the pack no
 * longer overrides anything on top), but its assets still exist at the lowest priority – so any
 * {@code hypixel_skyblock:*} reference the vanilla remap does not cover (brand-new items, GUI font
 * glyphs, tooltip sprites) resolves instead of turning into the pink/black missing texture. User
 * packs and the remap both take precedence, so visuals stay vanilla wherever we map them.
 *
 * <p><b>The mod downloads nothing.</b> The pack is the copy Minecraft itself downloaded and cached
 * the last time the player joined with the server pack accepted: vanilla's download queue keeps
 * server packs in {@code <game dir>/downloads/<pack uuid>/<sha1>} and appends one JSON line per
 * download to {@code downloads/log.json} with the source URL and that relative file name (verified
 * against the 26.2 {@code DownloadQueue} bytecode and a real instance). This class reads that log,
 * picks the newest SkyBlock pack that is still on disk, and hands the file to the pack repository
 * read-only. It is never copied anywhere.
 *
 * <p>Why not let the blocked push download as usual and only skip applying it: vanilla's
 * {@code ServerPackManager} downloads and applies in one flow, so separating them means accepting
 * the pack, filtering it out of the reload, and paying a full resource reload on every join for a
 * pack that is then thrown away. Not worth it for a fallback. So with the bypass on from the very
 * first join there is nothing cached, and the fallback stays empty until one normal join.
 *
 * <p>Builds up to 1.0.0-beta.9 downloaded the pack into {@code config/sbs/repo/hypixel_pack.zip};
 * {@link #removeLegacyCopy} deletes that file once.
 */
public final class HypixelPackFallback {

    private static final String PACK_ID = "sbs_hypixel_fallback";

    /** Last file reported in the log, so a reload does not log the same pick again. */
    private static volatile Path lastLogged;

    /** Whether "nothing cached" has been logged since the last pick, for the same reason. */
    private static volatile boolean loggedEmpty;

    private HypixelPackFallback() {
    }

    /** Minecraft's server-pack download cache ({@code DownloadedPackSource}'s queue directory). */
    private static Path downloadsDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("downloads");
    }

    /** Whether Minecraft has a cached SkyBlock pack the fallback can use. */
    public static boolean isCached() {
        return cachedPack().isPresent();
    }

    /**
     * The newest cached SkyBlock pack still on disk, read from vanilla's download log. Empty when
     * the player has never joined with the pack accepted, or vanilla has since pruned the file.
     */
    public static Optional<Path> cachedPack() {
        Path dir = downloadsDir();
        Path log = dir.resolve("log.json");
        if (!Files.isRegularFile(log)) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                Optional<Path> file = cachedFile(dir, lines.get(i));
                if (file.isPresent()) {
                    return file;
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Pack] could not read {}", log, e);
        }
        return Optional.empty();
    }

    /** One log line → its file, if it is a SkyBlock pack that is still there. */
    private static Optional<Path> cachedFile(Path dir, String line) {
        if (line.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonObject entry = JsonParser.parseString(line).getAsJsonObject();
            if (!isSkyBlockPackUrl(string(entry, "url")) || !entry.has("file")
                    || !entry.get("file").isJsonObject()) {
                return Optional.empty();
            }
            String name = string(entry.getAsJsonObject("file"), "name");
            if (name == null || name.isEmpty()) {
                return Optional.empty();
            }
            Path file = dir.resolve(name.replace('\\', '/')).normalize();
            // Only ever a file inside the cache; a hand-edited log cannot point us elsewhere.
            return file.startsWith(dir.normalize()) && Files.isRegularFile(file)
                    ? Optional.of(file) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty(); // a torn or foreign line
        }
    }

    /**
     * Whether a logged download URL is Hypixel's SkyBlock pack - a {@code hypixel.net} host with a
     * path under {@code /SkyBlock/}. Package-private for the test.
     */
    static boolean isSkyBlockPackUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            return host != null && path != null
                    && (host.equalsIgnoreCase("hypixel.net")
                            || host.toLowerCase(Locale.ROOT).endsWith(".hypixel.net"))
                    && path.startsWith("/SkyBlock/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /**
     * Deletes the copy older builds downloaded into the mod's folder. Our own file, created by us;
     * the mod must not keep Hypixel's pack in its folders. Called once from client init.
     */
    public static void removeLegacyCopy() {
        Path legacy = FabricLoader.getInstance().getConfigDir().resolve("sbs").resolve("repo")
                .resolve("hypixel_pack.zip");
        try {
            if (Files.deleteIfExists(legacy)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pack] removed the old downloaded {}", legacy);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Pack] could not remove {}", legacy, e);
        }
    }

    /**
     * The repository source injected into the client {@code PackRepository}: contributes Minecraft's
     * cached SkyBlock pack as a required, fixed, bottom-position pack while the toggle is on.
     * Contributing nothing is always safe – vanilla just sees one source fewer.
     */
    public static final class Source implements RepositorySource {

        @Override
        public void loadPacks(Consumer<Pack> onLoad) {
            if (!ConfigManager.getInstance().get().texturePack.ignoreEnforcedPacks) {
                return;
            }
            Optional<Path> cached = cachedPack();
            if (cached.isEmpty()) {
                if (!loggedEmpty) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pack] no cached Hypixel pack - the "
                            + "fallback is empty until one join with the server pack accepted");
                    loggedEmpty = true;
                    lastLogged = null;
                }
                return;
            }
            Path file = cached.get();
            if (!file.equals(lastLogged)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pack] fallback uses Minecraft's cached {}", file);
                lastLogged = file;
                loggedEmpty = false;
            }
            try {
                PackLocationInfo location = new PackLocationInfo(PACK_ID,
                        Component.literal("SBS: Hypixel Fallback"), PackSource.BUILT_IN,
                        Optional.empty());
                PackSelectionConfig selection =
                        new PackSelectionConfig(true, Pack.Position.BOTTOM, true);
                Pack pack = Pack.readMetaAndCreate(location,
                        new FilePackResources.FileResourcesSupplier(file),
                        PackType.CLIENT_RESOURCES, selection);
                if (pack != null) {
                    onLoad.accept(pack);
                }
            } catch (Throwable t) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Pack] fallback pack unreadable", t);
            }
        }
    }
}
