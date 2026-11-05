/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.dragonprofit.logic;

import sbs.modid.client.combat.dragonprofit.model.DragonType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Totals over finished fights, overall and per dragon type. Pure and Gson-friendly: the all-time
 * copy is the profile file.
 */
public final class DragonStats {

    /** One bucket's tally. */
    public static final class Totals {
        public long fights;
        public long eyes;
        public long dropValue;
        public long eyeCost;
        public long superior;
        public long positionSum;
        public long positionCount;
        public String bestDropId;
        public long bestDropValue;

        public long profit() {
            return dropValue - eyeCost;
        }

        public long profitPerFight() {
            return fights == 0 ? 0L : profit() / fights;
        }

        /** Average damage position, or -1 when no fight had one. */
        public double averagePosition() {
            return positionCount == 0 ? -1 : (double) positionSum / positionCount;
        }

        void add(DragonFightLedger.Result r) {
            fights++;
            eyes += r.eyes();
            dropValue += r.dropValue();
            eyeCost += r.eyeCost();
            if (r.type() == DragonType.SUPERIOR) {
                superior++;
            }
            if (r.position() > 0) {
                positionSum += r.position();
                positionCount++;
            }
            if (r.bestDropId() != null && r.bestDropValue() > bestDropValue) {
                bestDropId = r.bestDropId();
                bestDropValue = r.bestDropValue();
            }
        }
    }

    private Totals total = new Totals();
    private Map<DragonType, Totals> byType = new EnumMap<>(DragonType.class);
    private DragonFightLedger.Result last;

    public void add(DragonFightLedger.Result result) {
        total().add(result);
        if (result.type() != null) {
            types().computeIfAbsent(result.type(), t -> new Totals()).add(result);
        }
        last = result;
    }

    public Totals total() {
        if (total == null) {
            total = new Totals();
        }
        return total;
    }

    /** This type's tally, or {@code null} when it was never fought. */
    public Totals get(DragonType type) {
        return types().get(type);
    }

    /** The last finished fight, or {@code null}. */
    public DragonFightLedger.Result last() {
        return last;
    }

    private Map<DragonType, Totals> types() {
        if (byType == null) {
            byType = new EnumMap<>(DragonType.class);
        }
        return byType;
    }
}
