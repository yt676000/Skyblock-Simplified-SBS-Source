/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.combat.diana.model.SpadeTier;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.helper.texture.logic.SkyblockItemModels;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether the Mythological Ritual is running for this player, and therefore whether any of the Diana
 * toolkit may do anything at all.
 *
 * <h2>Two readings, no API</h2>
 *
 * <p>The tab list publishes the officials by name - {@code Mayor: Aatrox}, {@code Minister: Foxy},
 * {@code Year: 507} - and it publishes them continuously, between elections as well as during one.
 * That answers "is Diana sitting" for free and locally. It is worth saying why that matters: the
 * backend's mayor endpoint goes through {@code SbsApi} and therefore needs a licence token, so a
 * feature gated on it would be dark for every unlicensed player. This is gated on nothing.
 *
 * <p>The tab is not the whole answer, though, and it used to be treated as one. The Mythological
 * Ritual is <b>not</b> a mayor event: burrows spawn whenever somebody uses an Ancestral Spade, and
 * Diana in office only makes them worth more. Gating on the election therefore left the toolkit dark
 * for most of the year, and Jerry's Perkpocalypse - where a borrowed perk, possibly Diana's, is the
 * live one and the tab still says "Jerry" - is invisible to the tab as well.
 *
 * <p>So the second reading stands on its own: a spade in the inventory. That is the tool the ritual
 * is done with, and carrying one is what makes any of this worth scanning for.
 *
 * <table>
 *   <caption>The whole rule</caption>
 *   <tr><th>Tab says</th><th>Spade</th><th>State</th></tr>
 *   <tr><td>Mayor or Minister is Diana</td><td>irrelevant</td><td>{@link State#ACTIVE}</td></tr>
 *   <tr><td>anything else</td><td>carried</td><td>{@link State#ARMED}</td></tr>
 *   <tr><td>anything else</td><td>none</td><td>{@link State#DORMANT}</td></tr>
 * </table>
 *
 * <p>An unreadable tab with no spade behind it fails <b>dormant</b>. A feature that scans particles
 * and paints the world because it could not tell what was going on is worse than one that does
 * nothing - but a player holding a spade has told it what is going on without the tab's help.
 *
 * <h2>Derived, never accumulated</h2>
 *
 * <p>There is deliberately no state to reset on a world change, a server hop or the end of the
 * event: every answer here is recomputed from the tab list and the inventory as they are right now,
 * behind a one-second cache that exists purely to keep the inventory scan off the render path. A
 * lobby whose tab has not loaded yet reads as dormant and starts working the moment it does.
 */
public final class DianaEvent {

    /** What the toolkit is allowed to do. */
    public enum State {

        /** Nothing at all: no particle scanning, no waypoints, no HUD. */
        DORMANT,

        /**
         * A Griffin spade is on the player, whoever is in office. The Mythological Ritual belongs to
         * the spade and not to the mayor: burrows spawn year-round and Diana only makes them pay
         * better, so the tool in the inventory is the honest signal and the election is not one.
         *
         * <p>Scanning may run. With no ritual happening no burrow particle ever arrives and nothing
         * is drawn, so arming for a spade nobody is using costs one predicate per particle packet.
         */
        ARMED,

        /** Diana holds the perk as Mayor or Minister. Confirmed, not inferred. */
        ACTIVE;

        /** Whether the toolkit may read the world. False only for {@link #DORMANT}. */
        public boolean awake() {
            return this != DORMANT;
        }
    }

    private static final String DIANA = "diana";

    /** The tab headers this reads, lower-cased. */
    public static final String MAYOR_ROW = "mayor";
    public static final String MINISTER_ROW = "minister";

    /**
     * The spade tiers as their own ids. Any other id ending in {@code _SPADE} arms as well - a tier
     * added later must not silently stop the feature working, and the cost of a false positive is
     * only "armed", which draws nothing without real burrow particles behind it. The
     * exact tier is a separate question ({@link #spadeTier}) and that one does not guess.
     */
    private static final Set<String> SPADE_IDS =
            Set.of("ANCESTRAL_SPADE", "ARCHAIC_SPADE", "DEIFIC_SPADE");

    /** How long a reading is reused. Short enough to feel live, long enough to keep off the hot path. */
    private static final long CACHE_MS = 1_000L;

    private static volatile State cached = State.DORMANT;
    private static volatile long cachedAt;

    private DianaEvent() {
    }

    /** The live state, recomputed at most once a second. Safe to call every frame. */
    public static State state() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MS) {
            return cached;
        }
        cachedAt = now;
        cached = evaluate(TabWidgets.lines(), carriesSpade());
        return cached;
    }

    /** Convenience for the common guard: {@code if (!DianaEvent.awake()) return;}. */
    public static boolean awake() {
        return state().awake();
    }

    /**
     * The rule itself, as a pure function of its two inputs - which is what makes it testable
     * without a client, a server or a live event.
     *
     * @param tabLines     the tab list's widget lines, in order
     * @param spadeCarried whether a Griffin spade is anywhere in the player's inventory
     */
    public static State evaluate(List<String> tabLines, boolean spadeCarried) {
        String mayor = official(tabLines, MAYOR_ROW);
        if (DIANA.equals(mayor) || DIANA.equals(official(tabLines, MINISTER_ROW))) {
            return State.ACTIVE;
        }
        if (spadeCarried) {
            return State.ARMED;
        }
        return State.DORMANT;
    }

    /**
     * One official's name from the tab, lower-cased, or {@code ""} when the row is absent or empty.
     *
     * <p>Reads all three widget shapes for the same reason {@code MayorVoteTracker} does: which one
     * a given row uses is not something to depend on. The election widget writes the name on the
     * header line with no punctuation at all ({@code Mayor Diana ♲}); other widgets put it after a
     * colon ({@code Mayor: Aatrox}); some put the header on its own line and the value on the next.
     *
     * <p>The bare shape is the one that matters and the one this used to get wrong. Requiring the
     * colon made {@code Mayor Diana ♲} read as an empty value, which fell through to the next line -
     * the perk - so the whole toolkit stayed dormant through an election it was built for.
     *
     * <p>Public so the event calendar gates mayor-only events on the same reading rather than a copy
     * of it.
     */
    public static String official(List<String> tabLines, String header) {
        if (tabLines == null) {
            return "";
        }
        for (int i = 0; i < tabLines.size(); i++) {
            String line = tabLines.get(i) == null ? "" : tabLines.get(i).trim();
            if (!line.toLowerCase(Locale.ROOT).startsWith(header)) {
                continue;
            }
            String value = valueOf(line, header);
            if (value.isEmpty() && i + 1 < tabLines.size() && tabLines.get(i + 1) != null) {
                value = tabLines.get(i + 1).trim();
            }
            // Hypixel decorates some rows ("Diana ✦", "Aatrox!"); the name is the leading word.
            return firstWord(value).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    /**
     * The part of the row after the header, trimmed - {@code "Mayor: Aatrox"} and
     * {@code "Mayor Diana ♲"} both to the name, {@code "Mayor"} alone to {@code ""}.
     *
     * <p>The colon is dropped when there is one and the header word is skipped when there is not,
     * so a row that carries its value inline is read inline whichever way Hypixel punctuates it.
     */
    private static String valueOf(String line, String header) {
        int colon = line.indexOf(':');
        if (colon >= 0) {
            return line.substring(colon + 1).trim();
        }
        return line.substring(header.length()).trim();
    }

    /** Letters only, up to the first thing that is not one - so punctuation and icons drop off. */
    private static String firstWord(String value) {
        int end = 0;
        while (end < value.length() && Character.isLetter(value.charAt(end))) {
            end++;
        }
        return value.substring(0, end);
    }

    // ------------------------------------------------------------------
    // The spade
    // ------------------------------------------------------------------

    /** Whether a Griffin spade of any tier is in the player's inventory right now. */
    public static boolean carriesSpade() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isSpade(SkyblockItemModels.skyblockId(inventory.getItem(slot)))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a SkyBlock item id names a Griffin spade. See {@link #SPADE_IDS} for the loose tail. */
    public static boolean isSpade(String skyblockId) {
        if (skyblockId == null || skyblockId.isBlank()) {
            return false;
        }
        String id = skyblockId.toUpperCase(Locale.ROOT);
        return SPADE_IDS.contains(id) || id.endsWith("_SPADE");
    }

    /**
     * The spade's tier, or {@code null} when the id is not one this build knows.
     *
     * <p>Unlike {@link #isSpade} this refuses to guess: the tier feeds the chain length, and a
     * chain reported as four steps when it is five walks the player away from a burrow that is
     * still there. Callers must treat {@code null} as "length unknown" and say so, not substitute
     * a default.
     */
    public static SpadeTier spadeTier(String skyblockId) {
        if (skyblockId == null) {
            return null;
        }
        return switch (skyblockId.toUpperCase(Locale.ROOT)) {
            case "ANCESTRAL_SPADE" -> SpadeTier.ANCESTRAL;
            case "ARCHAIC_SPADE" -> SpadeTier.ARCHAIC;
            case "DEIFIC_SPADE" -> SpadeTier.DEIFIC;
            default -> null;
        };
    }

    /** The tier of the spade the player is holding, or {@code null} when they hold none we know. */
    public static SpadeTier heldSpadeTier() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        ItemStack held = player.getMainHandItem();
        return spadeTier(SkyblockItemModels.skyblockId(held));
    }
}
