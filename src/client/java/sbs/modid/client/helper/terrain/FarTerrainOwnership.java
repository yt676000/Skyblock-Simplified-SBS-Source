/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whose garden (or private island) this is - the question that decides whether remembered terrain
 * may be written to disk.
 *
 * <p>The Garden and the Private Island are the two <b>player-shaped</b> maps: everyone's occupies
 * the same coordinates, so one file cannot hold two of them. Visiting someone therefore used to
 * overwrite your own remembered garden with theirs, and it stayed overwritten until you walked
 * around your own long enough to capture it back.
 *
 * <p><b>Nothing is written until the owner is known.</b> Three states, and only one of them touches
 * a file:
 * <ul>
 *   <li><b>Ours</b> - captured and saved exactly as before.</li>
 *   <li><b>Visiting</b> - a session-only store, thrown away on the next server hop. Their garden is
 *       still drawn while you are there; it simply never reaches disk.</li>
 *   <li><b>Unknown</b> - no store is opened at all. Not a fallback to "probably ours": guessing
 *       wrong in that direction is the bug this exists to fix, and the cost of waiting is a second
 *       of no far terrain on arrival.</li>
 * </ul>
 *
 * <p>Arriving anywhere on SkyBlock announces itself in chat, which is where the answer comes from.
 * The patterns below are deliberately loose - the exact wording is Hypixel's and may change - and
 * any line that mentions a garden or an island without matching one is logged under
 * {@code [SBS][Garden]}, so a wording this does not know can be read off a single visit and added.
 */
public final class FarTerrainOwnership {

    /** Who the current player-shaped map belongs to. */
    public enum Owner {
        /** Confirmed the player's own - the only state that may write to disk. */
        OURS,
        /** Confirmed someone else's - session-only, never persisted. */
        VISITING,
        /** Not yet established. Nothing is captured, served or saved. */
        UNKNOWN
    }

    /**
     * Someone else's, naming them: "... visiting Bob's Garden", "Welcome to Bob's Island". The name
     * is captured so it can be compared against the player's own - a visit to your <i>own</i> garden
     * through the visitor menu is still your garden.
     */
    private static final Pattern OTHERS = Pattern.compile(
            "(?i)(?:visiting|welcome to)\\s+(\\w{1,16})'s\\s+(?:garden|island)");

    /** Explicitly the player's own, however Hypixel chooses to phrase it. */
    private static final Pattern OURS_PATTERN = Pattern.compile(
            "(?i)welcome (?:back )?to your (?:own )?(?:garden|island)");

    /** Lines worth reporting when nothing matched, so an unknown wording can be added. */
    private static final Pattern CANDIDATE = Pattern.compile("(?i)(garden|island)");

    private static Owner owner = Owner.UNKNOWN;

    /** How many unmatched candidates have been logged this session - a hint, not a firehose. */
    private static int reported;

    private static final int MAX_REPORTS = 6;

    private FarTerrainOwnership() {
    }

    public static Owner state() {
        return owner;
    }

    /**
     * Forgets the answer. Called on every level change: a warp is exactly when the garden under you
     * can become somebody else's, so the previous answer is worth nothing.
     */
    public static void reset() {
        owner = Owner.UNKNOWN;
    }

    /** Fed every chat line from the shared chat funnel. */
    public static void onChat(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher others = OTHERS.matcher(text);
        if (others.find()) {
            String name = others.group(1);
            set(name.equalsIgnoreCase(self()) ? Owner.OURS : Owner.VISITING, name);
            return;
        }
        if (OURS_PATTERN.matcher(text).find()) {
            set(Owner.OURS, self());
            return;
        }
        // Unmatched but plausible: the wording that would have answered this, unread.
        if (owner == Owner.UNKNOWN && reported < MAX_REPORTS && CANDIDATE.matcher(text).find()
                && FarTerrainManager.onPlayerShapedMap()) {
            reported++;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Garden] unmatched line while the owner is unknown: \"{}\" - if this names "
                            + "whose garden this is, its wording needs adding", text);
        }
    }

    private static void set(Owner next, String name) {
        if (owner == next) {
            return;
        }
        owner = next;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Garden] this one is {} ({})",
                next == Owner.OURS ? "yours - it will be remembered"
                        : "someone else's - session only, nothing saved",
                name == null || name.isEmpty() ? "unnamed" : name);
    }

    /** The player's own name, lower-cased comparisons only; {@code ""} before the session exists. */
    private static String self() {
        try {
            String name = Minecraft.getInstance().getUser().getName();
            return name == null ? "" : name;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Whether a map name is one of the player-shaped ones, i.e. one this question applies to. */
    static boolean appliesTo(String island) {
        if (island == null) {
            return false;
        }
        String name = island.trim().toLowerCase(Locale.ROOT);
        return name.equals("the garden") || name.equals("garden")
                || name.equals("private island") || name.equals("your island");
    }
}
