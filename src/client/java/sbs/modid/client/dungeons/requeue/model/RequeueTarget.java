/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.requeue.model;

/**
 * One instance Auto Requeue can queue: a Catacombs floor (normal or Master Mode) or a Kuudra tier.
 *
 * <p>{@link #step} walks one ladder per category: E, F1 … F7, M1 … M7 for the Catacombs (F7 goes up
 * to M1, M1 down to F7) and T1 … T5 for Kuudra, which never crosses into the Catacombs. The Entrance
 * is floor 0 of normal mode; Master Mode has no Entrance.
 */
public record RequeueTarget(Category category, boolean master, int number) {

    public enum Category {
        DUNGEONS("Dungeons"),
        KUUDRA("Kuudra");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** {@code 'E'} / {@code 'F'} / {@code 'M'} plus a floor number; {@code null} when that is no floor. */
    public static RequeueTarget floor(char type, int number) {
        RequeueTarget target = switch (type) {
            case 'E' -> new RequeueTarget(Category.DUNGEONS, false, 0);
            case 'F' -> new RequeueTarget(Category.DUNGEONS, false, number);
            case 'M' -> new RequeueTarget(Category.DUNGEONS, true, number);
            default -> null;
        };
        return target != null && target.valid() ? target : null;
    }

    /** Kuudra tier 1-5; {@code null} otherwise. */
    public static RequeueTarget kuudra(int tier) {
        RequeueTarget target = new RequeueTarget(Category.KUUDRA, false, tier);
        return target.valid() ? target : null;
    }

    public boolean valid() {
        return instanceId() != null;
    }

    /** The {@code /joininstance} argument. */
    public String instanceId() {
        return category == Category.KUUDRA
                ? InstanceIds.kuudra(number) : InstanceIds.dungeon(master, number);
    }

    /** "E", "F6", "M7", "T3" - how players say it in party chat. */
    public String label() {
        if (category == Category.KUUDRA) {
            return "T" + number;
        }
        if (!master && number == 0) {
            return "E";
        }
        return (master ? "M" : "F") + number;
    }

    /** Rungs per Catacombs mode: E = 0, F7 = 7, M1 = 8, M7 = 14. */
    private static final int FLOORS_PER_MODE = 7;

    /** {@code delta} rungs along the category's ladder, or {@code null} past the top or bottom. */
    public RequeueTarget step(int delta) {
        if (category == Category.KUUDRA) {
            return kuudra(number + delta);
        }
        int rung = (master ? FLOORS_PER_MODE : 0) + number + delta;
        if (rung < 0 || rung > 2 * FLOORS_PER_MODE) {
            return null;
        }
        RequeueTarget next = rung > FLOORS_PER_MODE
                ? new RequeueTarget(Category.DUNGEONS, true, rung - FLOORS_PER_MODE)
                : new RequeueTarget(Category.DUNGEONS, false, rung);
        return next.valid() ? next : null;
    }

    @Override
    public String toString() {
        return label();
    }
}
