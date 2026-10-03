/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.scoreboard;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Upgrades a saved scoreboard layout from the old text-derived keys to stable element ids.
 *
 * <p>The old layout stored three kinds of key side by side, and they do not all survive:
 * <ul>
 *   <li><b>{@code cat:*}</b> - the eight hand-written categories. A clean 1:1 rename onto the
 *       element of the same name; carried over.</li>
 *   <li><b>{@code sbs:*}</b> - the mod's own rows, already keyed on their label. Unchanged.</li>
 *   <li><b>a raw line signature</b> ({@code "purse: #"}, {@code "⏣ hub"}) - every row the old
 *       classifier did not recognise. Some can be recovered by running the new patterns over the
 *       stored signature; the rest name a line by text that no longer identifies anything, and are
 *       dropped.</li>
 * </ul>
 *
 * <p><b>Dropping is announced, never silent.</b> A layout that comes back subtly rearranged with no
 * explanation is worse than one that resets and says so, so anything unmappable sets
 * {@link SBSConfig.CustomScoreboardSettings#layoutResetNotice} and the player is told once.
 *
 * <p><b>An empty old layout is not a layout.</b> The old fields defaulted to empty lists, so every
 * config ever written carries them; only a non-empty one means the player actually arranged
 * something.
 */
public final class ScoreboardLayoutMigration {

    /** The prefix the superseded category keys carried. */
    private static final String LEGACY_CATEGORY_PREFIX = "cat:";

    /** The layout format this build writes. */
    private static final int CURRENT_VERSION = 1;

    private ScoreboardLayoutMigration() {
    }

    /**
     * Tells the player once that part of their old layout could not be carried over.
     *
     * <p>Held until there is a player to tell: the migration runs while the config file is being
     * read, long before anyone has joined a world. Costs two field reads a tick and clears itself the
     * first time it fires.
     */
    public static void tickNotice(net.minecraft.client.Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        SBSConfig.CustomScoreboardSettings settings =
                sbs.modid.client.core.config.ConfigManager.getInstance().get().customScoreboard;
        if (settings == null || !settings.layoutResetNotice) {
            return;
        }
        settings.layoutResetNotice = false;
        sbs.modid.client.core.config.ConfigManager.getInstance().save();
        minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§8[§bSBS§8]§r §eCustom Scoreboard:§r your saved line order used the old text-based "
                        + "keys. The rows that could be identified kept their place; the rest were "
                        + "reset. Re-arrange them under §bReorder Lines§r."));
    }

    /**
     * Migrates {@code settings} in place if it still holds an old layout, then drops the old fields
     * so the next write is rid of them.
     */
    @SuppressWarnings("deprecation")
    public static void migrate(SBSConfig.CustomScoreboardSettings settings) {
        if (settings == null || settings.layoutVersion >= CURRENT_VERSION) {
            clearLegacy(settings);
            return;
        }
        settings.layoutVersion = CURRENT_VERSION;
        if (!isCustomised(settings.lineOrder) && !isCustomised(settings.hiddenLines)) {
            // Nothing was ever arranged: stamp the version and say nothing. Most configs are this.
            clearLegacy(settings);
            return;
        }

        boolean lost = false;
        if (isCustomised(settings.lineOrder)) {
            Converted order = convert(settings.lineOrder);
            settings.elementOrder = order.ids();
            lost = order.dropped();
            // The old form had no explicit "everything else" slot - unlisted rows were appended at
            // the bottom, which is the behaviour this rework exists to end. Recreating that position
            // exactly is what keeps a migrated panel looking like the one the player built.
            if (!settings.elementOrder.contains(ScoreboardElements.UNRECOGNIZED)) {
                settings.elementOrder.add(ScoreboardElements.UNRECOGNIZED);
            }
        }
        if (isCustomised(settings.hiddenLines)) {
            Converted hidden = convert(settings.hiddenLines);
            settings.hiddenElements = hidden.ids();
            lost |= hidden.dropped();
        }
        if (lost) {
            settings.layoutResetNotice = true;
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Scoreboard] layout migrated to element ids: {} placed, {} hidden{}",
                settings.elementOrder.size(), settings.hiddenElements.size(),
                lost ? " (some rows could not be mapped and were dropped)" : "");
        clearLegacy(settings);
    }

    /** Nulls the superseded fields so Gson stops writing them to {@code config.json}. */
    @SuppressWarnings("deprecation")
    private static void clearLegacy(SBSConfig.CustomScoreboardSettings settings) {
        if (settings != null) {
            settings.lineOrder = null;
            settings.hiddenLines = null;
        }
    }

    private static boolean isCustomised(List<String> legacy) {
        return legacy != null && !legacy.isEmpty();
    }

    /** The converted ids, and whether anything had to be thrown away to get them. */
    private record Converted(List<String> ids, boolean dropped) {
    }

    /**
     * Old keys mapped onto element ids, keeping their order and losing what cannot be mapped.
     *
     * <p>De-duplicated: two old keys can map onto one element (an objective header and one of its
     * body lines both become {@code objective}), and a layout must never place the same element
     * twice.
     */
    private static Converted convert(List<String> legacy) {
        Set<String> seen = new LinkedHashSet<>();
        boolean dropped = false;
        for (String key : legacy) {
            String id = idFor(key);
            if (id == null) {
                dropped = true;
            } else {
                seen.add(id);
            }
        }
        return new Converted(new ArrayList<>(seen), dropped);
    }

    /**
     * One old key as an element id, or {@code null} when it names nothing this build can place.
     *
     * <p>The last branch is the interesting one: an unmapped raw signature is re-run through the new
     * patterns, which recovers the rows the old classifier simply had no category for ({@code
     * "motes: #"}, {@code "cleared: #%"}). It is tried twice - once as stored, and once with the
     * {@code #} placeholders turned back into digits, because a pattern that matches on the
     * <i>shape</i> of a number ({@code \d{2}/\d{2}/\d{2}}) cannot match a signature the digits were
     * taken out of. Undoing the old encoding here is right where a looser live pattern would be
     * wrong: only the migration ever sees a {@code #}.
     */
    private static String idFor(String key) {
        if (key == null || key.isBlank()) {
            // The old form gave every blank row the same empty key, and de-duplicated them into a
            // single slot. There is no honest way to turn that back into per-row spacing.
            return null;
        }
        if (key.startsWith(ScoreboardElements.SBS_PREFIX)) {
            return ScoreboardElements.byId(key) != null ? key : null;
        }
        if (key.startsWith(LEGACY_CATEGORY_PREFIX)) {
            String id = key.substring(LEGACY_CATEGORY_PREFIX.length()).toLowerCase(Locale.ROOT);
            return ScoreboardElements.byId(id) != null ? id : null;
        }
        String direct = ScoreboardElements.classifyText(key);
        return direct != null ? direct : ScoreboardElements.classifyText(key.replace("#", "00"));
    }
}
