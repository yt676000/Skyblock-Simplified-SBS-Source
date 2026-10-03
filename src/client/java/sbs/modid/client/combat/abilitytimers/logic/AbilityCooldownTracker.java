/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.abilitytimers.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Cooldown clocks for the three "you would have died" saves: Bonzo's Mask, the Spirit Mask and the
 * Phoenix pet's Rekindle. Each starts when the game says the save fired and counts down to ready.
 *
 * <p><b>Chat-driven, not inventory-driven.</b> The proc line is the one moment the server tells you
 * unambiguously that the cooldown started; reading the item's lore would mean re-scanning the
 * inventory every tick and still guessing about the pet, which is not in an inventory slot at all.
 *
 * <p><b>The durations are settings, not constants.</b> Hypixel has re-tuned all three of these more
 * than once, and a hard-coded number that is 30 seconds off is worse than no timer - it tells you
 * you are safe when you are not. The defaults are the current live values; if a balance patch moves
 * one, it is a field to correct rather than a release to wait for.
 */
public final class AbilityCooldownTracker {

    private static final AbilityCooldownTracker INSTANCE = new AbilityCooldownTracker();

    /** The three tracked saves, in the order they are listed on the card. */
    public enum Ability {
        BONZO("Bonzo Mask", 0xFFFF77DD),
        SPIRIT("Spirit Mask", 0xFF77E0FF),
        PHOENIX("Phoenix", 0xFFFFA030);

        private final String label;
        private final int color;

        Ability(String label, int color) {
            this.label = label;
            this.color = color;
        }

        public String label() {
            return label;
        }

        public int color() {
            return color;
        }
    }

    /** When each ability's cooldown started (ms), absent when it never fired this session. */
    private final Map<Ability, Long> startedAt = new EnumMap<>(Ability.class);

    private AbilityCooldownTracker() {
        // Each proc line. The item names carry a ⚚ / ✪ prefix and colour codes in game; the patterns
        // key on the words that survive every reforge, star and colour code.
        ChatPatternRegistry.getInstance().register(
                "(?i)Bonzo'?s? Mask saved your life",
                matcher -> trigger(Ability.BONZO), "cooldown: bonzo mask");
        ChatPatternRegistry.getInstance().register(
                "(?i)Spirit Mask saved your life",
                matcher -> trigger(Ability.SPIRIT), "cooldown: spirit mask");
        ChatPatternRegistry.getInstance().register(
                "(?i)Your Phoenix Pet saved you",
                matcher -> trigger(Ability.PHOENIX), "cooldown: phoenix pet");
    }

    public static AbilityCooldownTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.AbilityTimerSettings cfg() {
        return ConfigManager.getInstance().get().abilityTimers;
    }

    // ---- read by the HUD ----------------------------------------------------------------------

    /** One row on the card: the ability, its seconds left and whether it is ready again. */
    public record Entry(Ability ability, int secondsLeft, boolean ready) {
    }

    /**
     * The rows to draw right now: every ability that is enabled and has fired at least once, with
     * ready ones dropped unless the card is set to keep showing them.
     */
    public List<Entry> entries() {
        SBSConfig.AbilityTimerSettings settings = cfg();
        List<Entry> rows = new ArrayList<>(Ability.values().length);
        for (Ability ability : Ability.values()) {
            if (!enabled(settings, ability)) {
                continue;
            }
            Long started = startedAt.get(ability);
            if (started == null) {
                continue; // never used this session - nothing honest to show
            }
            int total = duration(settings, ability);
            long elapsedMs = System.currentTimeMillis() - started;
            int left = (int) Math.ceil(Math.max(0, total * 1000L - elapsedMs) / 1000.0);
            boolean ready = left <= 0;
            if (ready && !settings.showReady) {
                continue;
            }
            rows.add(new Entry(ability, left, ready));
        }
        return rows;
    }

    /** Whether there is anything to draw at all. */
    public boolean active() {
        return cfg().enabled && !entries().isEmpty();
    }

    // ---- state --------------------------------------------------------------------------------

    /** A save fired: (re)start its clock. */
    private void trigger(Ability ability) {
        if (!cfg().enabled) {
            return;
        }
        startedAt.put(ability, System.currentTimeMillis());
    }

    /**
     * Called every client tick. Only job: forget the clocks when there is no world any more, so a
     * cooldown from the last session does not carry into the next login as a fake "ready in 12s".
     */
    public void onClientTick() {
        if (Minecraft.getInstance().level == null && !startedAt.isEmpty()) {
            startedAt.clear();
        }
    }

    /** Clears every clock (settings button). */
    public void reset() {
        startedAt.clear();
    }

    private static boolean enabled(SBSConfig.AbilityTimerSettings settings, Ability ability) {
        return switch (ability) {
            case BONZO -> settings.bonzoMask;
            case SPIRIT -> settings.spiritMask;
            case PHOENIX -> settings.phoenixPet;
        };
    }

    private static int duration(SBSConfig.AbilityTimerSettings settings, Ability ability) {
        return switch (ability) {
            case BONZO -> settings.bonzoSeconds;
            case SPIRIT -> settings.spiritSeconds;
            case PHOENIX -> settings.phoenixSeconds;
        };
    }
}
