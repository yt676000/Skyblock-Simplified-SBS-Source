/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The rules of the HotM Upgrade Reminder, with no game types: which cached perks are affordable,
 * when a watched perk is announced, when the summary may speak, and what a stale cache suppresses.
 * {@link HotmReminder} feeds it live data and delivers what it returns.
 *
 * <p><b>Costs come only from the menu.</b> A perk's next-level cost is the one the HotM menu printed
 * when it was last read ({@link HotmTreeStore.NodeState#nextCost}). Nothing here derives a cost, and
 * while the cache is stale nothing is announced from it - a reminder for a price the player has
 * already moved past is a wrong number stated with confidence.
 *
 * <p><b>Arming.</b> A watched perk fires once per cost. It fires again only when the cost changes
 * (the menu was read again, usually after an upgrade) or after the powder fell below the cost and
 * rose back. Without the second rule a spend-and-regrind would never be announced; without the
 * first, one upgrade would silence the perk for good.
 *
 * <p>Not thread-safe; the controller calls it from the client thread only.
 */
public final class HotmReminders {

    /** The summary may speak at most this often. */
    public static final long SUMMARY_COOLDOWN_MS = 10 * 60_000L;

    /**
     * One perk as the cache holds it.
     *
     * @param id        stable catalogue id (or the reader's slug for an unknown perk)
     * @param name      display name, for the message
     * @param level     current level; 0 means locked
     * @param maxLevel  the level it stops at
     * @param nextCost  powder for the next level as the menu printed it; 0 when unknown or maxed
     * @param powder    the powder that cost is in, upper case ({@code MITHRIL}, ...); empty if none
     */
    public record Perk(String id, String name, int level, int maxLevel, long nextCost, String powder) {

        public Perk {
            powder = powder == null ? "" : powder.toUpperCase(Locale.ROOT);
        }

        /** Levelled, not maxed, and with a cost the menu actually stated. */
        public boolean upgradeKnown() {
            return level > 0 && level < maxLevel && nextCost > 0 && !powder.isEmpty();
        }
    }

    /** What happened, for the controller to route to its own toggle and channels. */
    public enum Kind {
        WATCHED, SUMMARY, STALE_HINT, TOKENS, TIER_UP
    }

    /** One message to deliver. {@code title} is the headline, {@code detail} the chat line. */
    public record Notice(Kind kind, String title, String detail) {
    }

    /** Watched perk id -> the cost it was last announced at. Absent means armed. */
    private final Map<String, Long> announcedAt = new HashMap<>();
    /** The affordable count the summary last spoke for; it speaks again only above this. */
    private int summaryCount;
    private long summaryAt = Long.MIN_VALUE / 2;
    private boolean staleHinted;
    private boolean tokensReminded;

    /** Whether {@code perk}'s next level is payable from {@code powder} (keys upper case). */
    public static boolean affordable(Perk perk, Map<String, Long> powder) {
        if (!perk.upgradeKnown() || powder == null) {
            return false;
        }
        Long have = powder.get(perk.powder());
        return have != null && have >= perk.nextCost();
    }

    /**
     * The powder to judge against: the last known totals per profile, overridden by whatever the tab
     * serves now. Keys upper case, whichever case the source used ({@code Mithril} from the tab,
     * {@code MITHRIL} from the menu).
     */
    public static Map<String, Long> currentPowder(Map<String, Long> tab, Map<String, Long> lastKnown) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (lastKnown != null) {
            lastKnown.forEach((kind, amount) -> out.put(kind.toUpperCase(Locale.ROOT), amount));
        }
        if (tab != null) {
            tab.forEach((kind, amount) -> out.put(kind.toUpperCase(Locale.ROOT), amount));
        }
        return out;
    }

    /** How many cached perks have an affordable next level. */
    public static int affordableCount(List<Perk> perks, Map<String, Long> powder) {
        int count = 0;
        for (Perk perk : perks) {
            if (affordable(perk, powder)) {
                count++;
            }
        }
        return count;
    }

    /**
     * One evaluation pass.
     *
     * @param perks    the cached tree
     * @param watched  ids the player watches
     * @param powder   current powder by upper-case type; empty when unknown
     * @param stale    whether the cache may be out of date
     * @param summary  whether the summary is switched on
     * @param silent   true while the HotM menu is open: state advances, nothing is returned, so the
     *                 perks the player is looking at are not announced when the menu closes
     * @param nowMs    the clock, passed in so the cooldown is testable
     */
    public List<Notice> evaluate(List<Perk> perks, Set<String> watched, Map<String, Long> powder,
                                 boolean stale, boolean summary, boolean silent, long nowMs) {
        List<Notice> out = new ArrayList<>(2);
        if (perks.isEmpty() || powder == null || powder.isEmpty()) {
            return out;
        }
        if (stale) {
            if (!staleHinted && !silent) {
                staleHinted = true;
                out.add(new Notice(Kind.STALE_HINT, "HotM costs out of date",
                        "HotM: open /hotm to refresh upgrade costs."));
            }
            return out;
        }

        for (Perk perk : perks) {
            if (!watched.contains(perk.id())) {
                continue;
            }
            if (!affordable(perk, powder)) {
                // Below the cost (or no longer upgradeable): re-armed for when it is reached again.
                announcedAt.remove(perk.id());
                continue;
            }
            Long at = announcedAt.get(perk.id());
            if (at != null && at == perk.nextCost()) {
                continue;
            }
            announcedAt.put(perk.id(), perk.nextCost());
            if (!silent) {
                out.add(watchedNotice(perk));
            }
        }
        announcedAt.keySet().retainAll(watched);

        int count = affordableCount(perks, powder);
        if (count < summaryCount) {
            // Fewer than last time (a spend): a later rise to the old number is news again.
            summaryCount = count;
        }
        if (summary && count > summaryCount) {
            if (silent) {
                // Seen in the menu: counts as said, whatever the cooldown.
                summaryCount = count;
            } else if (nowMs - summaryAt >= SUMMARY_COOLDOWN_MS) {
                summaryCount = count;
                summaryAt = nowMs;
                out.add(new Notice(Kind.SUMMARY, "HotM: " + count + " upgrade" + (count == 1 ? "" : "s")
                        + " affordable", "HotM: " + count + " upgrade" + (count == 1 ? "" : "s")
                        + " affordable - open /hotm."));
            }
        }
        return out;
    }

    /**
     * Entering a mining island. Speaks once per session, and only while the menu last reported
     * unspent tokens; {@code stale} turns the number into a lower bound rather than a claim.
     */
    public Notice onEnterMiningIsland(int tokens, boolean stale) {
        if (tokensReminded || tokens <= 0) {
            return null;
        }
        tokensReminded = true;
        String count = (stale ? "at least " : "") + tokens;
        String noun = tokens == 1 ? "Token" : "Tokens";
        return new Notice(Kind.TOKENS, count + " unspent " + noun + " of the Mountain",
                "You have " + count + " unspent " + noun + " of the Mountain - open /hotm.");
    }

    /** A HotM tier-up line. The new token count is not known until the menu is read. */
    public Notice onTierUp() {
        return new Notice(Kind.TIER_UP, "HotM tier up",
                "HotM tier up - open /hotm to spend your new Token of the Mountain.");
    }

    /** New profile or new session: everything starts armed again. */
    public void reset() {
        announcedAt.clear();
        summaryCount = 0;
        summaryAt = Long.MIN_VALUE / 2;
        staleHinted = false;
        tokensReminded = false;
    }

    static Notice watchedNotice(Perk perk) {
        String kind = perk.powder().charAt(0) + perk.powder().substring(1).toLowerCase(Locale.ROOT);
        String line = "HotM: " + perk.name() + " " + (perk.level() + 1) + " affordable ("
                + String.format(Locale.ROOT, "%,d", perk.nextCost()) + " " + kind + ")";
        return new Notice(Kind.WATCHED, line, line);
    }
}
