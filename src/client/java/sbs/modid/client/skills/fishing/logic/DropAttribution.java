/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import sbs.modid.client.skills.fishing.model.SeaCreatureDropTable;

import java.util.ArrayList;
import java.util.List;

/**
 * The rule that decides whether a rare item is a sea-creature drop of this session: an item counts
 * only when a creature that can drop it died near the player shortly before.
 *
 * <p>Kept free of Minecraft so the rule is tested rather than fished for. {@link SeaCreatureDrops}
 * feeds it deaths from the world; {@link FishingTracker} asks it about every arrival of a table item.
 *
 * <p>Each death pays out each item at most once. A Lord Jawbus kill followed by a fragment pickup
 * books one fragment; a second fragment picked up a minute later, with no second Jawbus behind it,
 * is somebody else's or came from somewhere else, and is refused.
 */
public final class DropAttribution {

    /** Deaths older than this are dropped outright, whatever the configured window. */
    static final long MAX_WINDOW_MS = 10 * 60_000L;

    /** One eligible death: which creature, when, and which items it has already paid out. */
    private static final class Death {
        final String creature;
        final long at;
        final List<String> paid = new ArrayList<>(2);

        Death(String creature, long at) {
            this.creature = creature;
            this.at = at;
        }
    }

    private final List<Death> deaths = new ArrayList<>();

    /** A creature died within the eligibility radius of the player. */
    public void onDeath(String creature, long now) {
        prune(now);
        deaths.add(new Death(creature, now));
    }

    /**
     * The creature that explains {@code itemId} arriving at {@code now}, consuming that pay-out, or
     * null when no eligible creature able to drop it died within {@code windowMs}. Newest death
     * first: the kill just before the pickup is the likeliest source.
     */
    public String attribute(String itemId, long now, long windowMs) {
        prune(now);
        List<SeaCreatureDropTable.Drop> sources = SeaCreatureDropTable.sourcesOf(itemId);
        if (sources.isEmpty()) {
            return null;
        }
        for (int i = deaths.size() - 1; i >= 0; i--) {
            Death death = deaths.get(i);
            if (now - death.at > windowMs || death.paid.contains(itemId)) {
                continue;
            }
            for (SeaCreatureDropTable.Drop source : sources) {
                if (source.creature().equals(death.creature)) {
                    death.paid.add(itemId);
                    return death.creature;
                }
            }
        }
        return null;
    }

    /** The most recent eligible death within {@code windowMs}, for the unexplained-pickup log. */
    public String recentDeath(long now, long windowMs) {
        for (int i = deaths.size() - 1; i >= 0; i--) {
            if (now - deaths.get(i).at <= windowMs) {
                return deaths.get(i).creature;
            }
        }
        return null;
    }

    private void prune(long now) {
        deaths.removeIf(death -> now - death.at > MAX_WINDOW_MS);
    }

    /** Forgets every death: a new world, or a fresh session. */
    public void reset() {
        deaths.clear();
    }
}
