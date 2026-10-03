/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * What the existing trackers report at one moment: a session is the difference of two of these.
 * Pure, so the subtraction - the only arithmetic this feature does - is unit-tested.
 *
 * @param crops      crop display name -> the Collection Tracker's session gain so far
 * @param pestKills  Pest Profit's session kill count
 * @param pestCoins  Pest Profit's session value
 * @param drops      rare drop name -> the drop tracker's session count
 * @param dropCoins  the drop tracker's session value
 * @param dropUnpriced the drop tracker could not price something
 */
public record FarmingTotals(Map<String, Long> crops, int pestKills, long pestCoins,
                            Map<String, Integer> drops, long dropCoins, boolean dropUnpriced) {

    public static final FarmingTotals ZERO = new FarmingTotals(Map.of(), 0, 0, Map.of(), 0, false);

    /**
     * The session between {@code start} and {@code end}.
     *
     * <p>Every difference is floored at zero: a tracker that reset in between (a profile switch,
     * the player pressing its reset button) would otherwise turn into a negative harvest.
     *
     * @param cropPrice per-crop sell price in coins, or a negative value when unpriced
     */
    public static FarmingSessionRecord between(FarmingSessionClock.Ended times, FarmingTotals start,
                                               FarmingTotals end, ToLongFunction<String> cropPrice) {
        FarmingSessionRecord out = new FarmingSessionRecord();
        out.startedAt = times.startedAt();
        out.endedAt = times.endedAt();
        out.activeMs = times.activeMs();
        out.endReason = times.reason().text();

        for (Map.Entry<String, Long> e : end.crops().entrySet()) {
            long gained = Math.max(0, e.getValue() - start.crops().getOrDefault(e.getKey(), 0L));
            if (gained == 0) {
                continue;
            }
            out.crops.put(e.getKey(), gained);
            long price = cropPrice.applyAsLong(e.getKey());
            if (price < 0) {
                out.cropUnpriced = true;
            } else {
                out.cropCoins += gained * price;
            }
        }

        out.pestKills = Math.max(0, end.pestKills() - start.pestKills());
        out.pestCoins = Math.max(0, end.pestCoins() - start.pestCoins());

        Map<String, Integer> drops = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : end.drops().entrySet()) {
            int gained = Math.max(0, e.getValue() - start.drops().getOrDefault(e.getKey(), 0));
            if (gained > 0) {
                drops.put(e.getKey(), gained);
            }
        }
        out.drops = drops;
        out.dropCoins = drops.isEmpty() ? 0 : Math.max(0, end.dropCoins() - start.dropCoins());
        out.dropUnpriced = !drops.isEmpty() && end.dropUnpriced();
        return out;
    }
}
