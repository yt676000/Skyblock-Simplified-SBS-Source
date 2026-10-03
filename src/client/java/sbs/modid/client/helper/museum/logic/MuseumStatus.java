/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.museum.logic;

import sbs.modid.client.helper.museum.model.MuseumCatalog;
import sbs.modid.client.helper.museum.model.MuseumCatalog.Donation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Is this item still missing from my museum?" - the one question the tooltip line, the slot marker
 * and the missing screen all ask, answered in one place so they cannot disagree.
 *
 * <p>Answers {@link #UNKNOWN} unless the item's category has been fully seen for this profile; only
 * a known answer is ever shown. Cached per item id and dropped whenever the catalogue or the store
 * changes, so the per-frame callers pay one map lookup.
 */
public final class MuseumStatus {

    /** The item's donation (its own, its set's, or its base's) is not in the museum. */
    public record Missing(Donation donation, long seenAt, boolean higherTierDonated) {
    }

    /** No answer: not a museum item, or its category has not been fully seen. */
    public static final Missing UNKNOWN = null;

    private static final Missing DONATED_OR_UNKNOWN = new Missing(null, 0L, false);

    private static final Map<String, Missing> CACHE = new HashMap<>();
    private static MuseumCatalog cachedCatalog;
    private static int cachedGeneration = -1;

    private MuseumStatus() {
    }

    /**
     * The missing donation this item would fill, or {@code null} when it fills none (donated, not
     * a museum item, or not known yet).
     */
    public static synchronized Missing missing(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        MuseumCatalog catalog = MuseumCatalog.current();
        MuseumStore store = MuseumStore.getInstance();
        int generation = store.generation();
        if (catalog != cachedCatalog || generation != cachedGeneration) {
            CACHE.clear();
            cachedCatalog = catalog;
            cachedGeneration = generation;
        }
        Missing cached = CACHE.get(itemId);
        if (cached == null) {
            cached = resolve(catalog, store, itemId);
            CACHE.put(itemId, cached);
        }
        return cached == DONATED_OR_UNKNOWN ? null : cached;
    }

    private static Missing resolve(MuseumCatalog catalog, MuseumStore store, String itemId) {
        List<String> keys = catalog.keysFor(itemId);
        for (String key : keys) {
            Donation donation = catalog.donation(key);
            if (donation == null) {
                continue;
            }
            Boolean donated = store.donated(donation.category(), key);
            if (Boolean.FALSE.equals(donated)) {
                return new Missing(donation, store.seenAt(donation.category()),
                        higherTierDonated(catalog, store, donation));
            }
        }
        return DONATED_OR_UNKNOWN;
    }

    /**
     * Whether any higher tier on this donation's chain is donated. Whether that counts for this one
     * is unverified, so callers say "may count" rather than hiding the line.
     */
    public static boolean higherTierDonated(MuseumCatalog catalog, MuseumStore store, Donation donation) {
        String next = donation.higherTier();
        int guard = 0;
        while (next != null && guard++ < 16) {
            Donation higher = catalog.donation(next);
            if (higher == null) {
                return false;
            }
            if (Boolean.TRUE.equals(store.donated(higher.category(), next))) {
                return true;
            }
            next = higher.higherTier();
        }
        return false;
    }

    /** The tooltip text, e.g. {@code Museum: not donated · +5 XP}, or {@code null}. */
    public static String tooltipLine(String itemId, long now) {
        Missing missing = missing(itemId);
        if (missing == null) {
            return null;
        }
        Donation donation = missing.donation();
        StringBuilder line = new StringBuilder(donation.set()
                ? "Museum: set not donated · set: +" + donation.xp() + " XP"
                : "Museum: not donated · +" + donation.xp() + " XP");
        if (missing.higherTierDonated()) {
            line.append(" (a higher tier is donated - may count)");
        }
        String age = age(missing.seenAt(), now);
        if (age != null) {
            line.append(" (seen ").append(age).append(" ago)");
        }
        return line.toString();
    }

    /** "5d" when the data is older than three days, else {@code null}. */
    static String age(long seenAt, long now) {
        if (seenAt <= 0) {
            return null;
        }
        long days = (now - seenAt) / 86_400_000L;
        return days > 3 ? days + "d" : null;
    }
}
