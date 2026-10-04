/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.texture.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Keep Hypixel Pack Loaded": Hypixel pushes its SkyBlock pack on every entry to SkyBlock and pops
 * it on every exit, and each push or pop is a full resource reload. With this on, the pack Minecraft
 * cached on an earlier join stays at the bottom of the stack all session ({@link HypixelPackFallback},
 * without the item-model remap, so items look exactly as Hypixel draws them), and:
 *
 * <ul>
 *   <li><b>push, same hash as the loaded pack</b>: answered ACCEPTED → DOWNLOADED →
 *       SUCCESSFULLY_LOADED, never handed to vanilla - no download, no reload;</li>
 *   <li><b>push, new hash</b>: let through once. Vanilla downloads it into its cache, which makes it
 *       the newest entry {@link HypixelPackFallback#cachedPack} picks, so the next start loads it;</li>
 *   <li><b>pop of a SkyBlock pack</b>: ignored, so leaving SkyBlock reloads nothing. Pops of any
 *       other pack, and a pop-all, go to vanilla as usual.</li>
 * </ul>
 *
 * <p><b>Precedence</b>: Ignore Enforced Packs, then this, then Auto-Accept. Ignore Enforced already
 * answers every push without loading anything, so this has nothing to add while it is on; a push
 * this lets through is still auto-accepted.
 *
 * <p>The status order is vanilla's, read from the 26.2 bytecode: {@code ServerPackManager.acceptPack}
 * reports ACCEPTED, {@code onDownload} DOWNLOADED, the reload APPLIED, which
 * {@code DownloadedPackSource}'s feedback sends as {@code SUCCESSFULLY_LOADED}. A pop
 * ({@code RemovalReason.SERVER_REMOVED}) has no server response at all, so ignoring one sends nothing.
 */
public final class HypixelPackKeeper {

    /** What to do with one push. */
    public enum PushDecision { VANILLA, SKIP }

    /** SkyBlock pack ids pushed this connection - their pops are the ones ignored. */
    private static final Set<UUID> SKYBLOCK_IDS = ConcurrentHashMap.newKeySet();

    /**
     * Hashes of SkyBlock packs let through to vanilla this connection. Their pops are ignored, so
     * vanilla still has them loaded, and a later push of the same hash is skipped like the bottom one.
     */
    private static final Set<String> VANILLA_HELD = ConcurrentHashMap.newKeySet();

    /** Last logged hash per event kind, so a lobby hop does not log the same line again. */
    private static volatile String lastSkipLogged;
    private static volatile String lastUpdateLogged;

    private HypixelPackKeeper() {
    }

    /** Whether Keep Loaded is the setting in charge (on, and not overruled by Ignore Enforced). */
    public static boolean active() {
        SBSConfig.TexturePackSettings settings = ConfigManager.getInstance().get().texturePack;
        return settings.keepHypixelPackLoaded && !settings.ignoreEnforcedPacks;
    }

    /** Decides one push. Called on the network thread from the push mixin. */
    public static PushDecision onPush(UUID id, String url, String hash) {
        if (!active() || !HypixelPackFallback.isSkyBlockPackUrl(url)) {
            return PushDecision.VANILLA;
        }
        SKYBLOCK_IDS.add(id);
        Optional<Path> loaded = HypixelPackFallback.activePack();
        String pushed = hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
        PushDecision decision = VANILLA_HELD.contains(pushed) && !pushed.isEmpty()
                ? PushDecision.SKIP
                : decide(loaded.map(HypixelPackFallback::sha1).orElse(null), hash);
        if (decision == PushDecision.SKIP) {
            if (!pushed.equals(lastSkipLogged)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pack] push skipped (same hash {})", pushed);
                lastSkipLogged = pushed;
            }
        } else if (!pushed.equals(lastUpdateLogged)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pack] pack updated: server {} vs loaded {} - "
                    + "loading it now, the next start keeps the new one",
                    pushed.isEmpty() ? "(no hash)" : pushed, loaded.map(HypixelPackFallback::sha1).orElse("(none)"));
            lastUpdateLogged = pushed;
        }
        if (decision == PushDecision.VANILLA && !pushed.isEmpty()) {
            VANILLA_HELD.add(pushed);
        }
        return decision;
    }

    /**
     * Skip only when the pack on screen is provably the pushed one. No loaded pack, or a push without
     * a hash, cannot be proven equal and goes to vanilla. Package-private for the test.
     */
    static PushDecision decide(String loadedSha1, String pushedHash) {
        if (loadedSha1 == null || pushedHash == null || pushedHash.isBlank()) {
            return PushDecision.VANILLA;
        }
        return loadedSha1.equalsIgnoreCase(pushedHash.trim()) ? PushDecision.SKIP : PushDecision.VANILLA;
    }

    /**
     * Whether a pop should be swallowed: a pop of one SkyBlock pack id while Keep Loaded is in charge.
     * A pop-all ({@code id} empty) goes to vanilla - it may carry other servers' packs too.
     */
    public static boolean ignorePop(Optional<UUID> id) {
        return active() && id.isPresent() && SKYBLOCK_IDS.contains(id.get());
    }

    /** Disconnect: the next server's ids are its own. */
    public static void reset() {
        SKYBLOCK_IDS.clear();
        VANILLA_HELD.clear();
    }
}
