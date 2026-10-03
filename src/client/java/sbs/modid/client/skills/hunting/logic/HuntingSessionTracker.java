/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.tracker.TrackerStore;

import java.util.Locale;
import java.util.Set;

/**
 * Shards caught this session, wherever you are - the all-island counterpart to {@code SafariTracker}.
 *
 * <p>{@code SafariTracker} answers "what did that Safari trip give me" and is bounded to the Safari
 * zone; this one runs on every hunting island and never ends on its own. They share
 * {@link ShardCatchParser} rather than a second copy of the patterns, and each keeps its own parser
 * instance so their dedupe windows cannot interfere.
 *
 * <p><b>What this deliberately does not do, and why.</b> No coins, and no per-method breakdown.
 * Neither is an omission to fill in later without new information:
 *
 * <ul>
 *   <li><b>Coins</b> would need surplus separated from shards the player still needs to level an
 *       attribute, and nothing in the mod can read attribute levels. Pricing needed stock is how a
 *       tracker talks somebody into selling what they were saving.
 *   <li><b>Per-method</b> would need the catch line to name the method, and it does not - "You caught
 *       a Lapis Zombie Shard!" is identical from a lasso, a net, a trap or a passive proc. A guessed
 *       coins-per-hour per method is exactly the number somebody plans an evening around.
 * </ul>
 *
 * <p>Both are recorded in {@code docs/features/hunting-profit-tracker.md} with what would settle them.
 *
 * <p><b>Visibility follows the island rule</b>: the card hides off the hunting islands and the session
 * is kept, not ended - leaving is not finishing.
 */
public final class HuntingSessionTracker {

    private static final HuntingSessionTracker INSTANCE = new HuntingSessionTracker();

    /** Where this tracker applies. Resolved through the location service, never compared literally. */
    private static final Set<String> HUNTING_ISLANDS =
            Set.of("Moonglade Marsh", "Torrhus Canyon");

    /** Lifetime tally name, one file under {@code config/sbs/tracker/}. */
    private static final String TRACKER = "huntingtracker";

    /** Rarity colour codes, dullest to rarest - the order Hypixel paints shard names in. */
    private static final String RARITY_ORDER = "faf7ae95d6c4b";

    private final ShardCatchParser parser = new ShardCatchParser();
    private final HuntingSession session = new HuntingSession();
    private long lastUnmatchedLogAt;

    private HuntingSessionTracker() {
    }

    public static HuntingSessionTracker getInstance() {
        return INSTANCE;
    }

    public HuntingSession session() {
        return session;
    }

    private static SBSConfig.HuntingSettings cfg() {
        return ConfigManager.getInstance().get().hunting;
    }

    private static boolean enabled() {
        SBSConfig.HuntingSettings hunting = cfg();
        return hunting != null && hunting.sessionTracker;
    }

    /**
     * Whether the card should be on screen right now.
     *
     * <p>The island-binding rule: a tracker is visible where its content applies and nowhere else.
     * Resolved through {@link SkyBlockLocation#onIsland}, which goes through the island catalogue -
     * a literal name compare is what hid every Garden overlay while the player stood on the Garden.
     */
    public boolean visible() {
        if (!enabled()) {
            return false;
        }
        for (String island : HUNTING_ISLANDS) {
            if (SkyBlockLocation.onIsland(island)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every incoming chat line, raw. The raw form is needed as well as the stripped one: the colour
     * code in front of a shard's name is its rarity, and it is the only place that is readable.
     */
    public void onChat(String raw) {
        if (raw == null || !enabled()) {
            return;
        }
        String line = raw.replaceAll("§.", "");
        long now = System.currentTimeMillis();

        var booked = parser.accept(line, now);
        if (booked.isPresent()) {
            ShardCatchParser.Catch shard = booked.get();
            session.record(shard.shard(), shard.amount(), SkyBlockLocation.island(),
                    rarityRank(raw, shard.shard()), now);
            TrackerStore.record(TRACKER, shard.shard() + " Shard", shard.amount());
            return;
        }

        // A shard-flavoured line that matched nothing is how a Hypixel reword becomes visible instead
        // of the tracker just quietly counting nothing. Throttled - a busy lobby says "shard" often.
        if (ShardCatchParser.looksLikeShard(line) && now - lastUnmatchedLogAt > 10_000L) {
            lastUnmatchedLogAt = now;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Hunting] Unmatched shard-ish line, patterns may need updating: {}", line);
        }
    }

    /**
     * How rare the shard's name was painted, or {@code -1} when the colour cannot be read.
     *
     * <p>Only ever used to pick "rarest this session". A shard whose colour is unreadable simply never
     * wins that slot rather than being guessed at a rank.
     */
    private static int rarityRank(String raw, String shard) {
        if (raw == null || shard == null || shard.isBlank()) {
            return -1;
        }
        int at = raw.toLowerCase(Locale.ROOT).indexOf(shard.toLowerCase(Locale.ROOT));
        if (at < 2 || raw.charAt(at - 2) != '§') {
            return -1;
        }
        return RARITY_ORDER.indexOf(Character.toLowerCase(raw.charAt(at - 1)));
    }

    /** Manual reset, from the settings row and the command. */
    public void reset() {
        session.reset();
        parser.reset();
    }
}
