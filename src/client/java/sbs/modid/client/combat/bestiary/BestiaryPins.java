/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.bestiary;

import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The pinned-mob list, per profile: toggled by the ★ in the Bestiary menu, read by the HUD and by
 * Mob Highlight. Pure over the settings object so the rules can be tested without a game.
 *
 * <p>Pins are keyed by the normalized mob name ({@link BestiaryTracker#normalize}), so "Zombie" from
 * the menu and "zombie" from an old config are one pin. A blank profile is a bucket of its own: a pin
 * made before the profile is known is not lost, it is simply not merged into a real profile.
 */
public final class BestiaryPins {

    private BestiaryPins() {
    }

    /** The pins of {@code profile}, in HUD order; created empty on first use. */
    public static List<SBSConfig.BestiaryPin> of(SBSConfig.BestiarySettings cfg, String profile) {
        if (cfg.pins == null) {
            cfg.pins = new LinkedHashMap<>();
        }
        return cfg.pins.computeIfAbsent(profile == null ? "" : profile, p -> new ArrayList<>());
    }

    /** The pin for {@code name} on {@code profile}, or {@code null}. */
    public static SBSConfig.BestiaryPin find(SBSConfig.BestiarySettings cfg, String profile, String name) {
        String key = BestiaryTracker.normalize(name);
        for (SBSConfig.BestiaryPin pin : of(cfg, profile)) {
            if (pin.key.equals(key)) {
                return pin;
            }
        }
        return null;
    }

    public static boolean isPinned(SBSConfig.BestiarySettings cfg, String profile, String name) {
        return find(cfg, profile, name) != null;
    }

    /**
     * Pins {@code name} (at the end, highlight per the global default) or unpins it.
     *
     * @return whether it is pinned now
     */
    public static boolean toggle(SBSConfig.BestiarySettings cfg, String profile, String name) {
        String key = BestiaryTracker.normalize(name);
        if (key.isEmpty()) {
            return false;
        }
        List<SBSConfig.BestiaryPin> pins = of(cfg, profile);
        if (pins.removeIf(pin -> pin.key.equals(key))) {
            return false;
        }
        SBSConfig.BestiaryPin pin = new SBSConfig.BestiaryPin();
        pin.key = key;
        pin.name = name.trim();
        pin.highlight = cfg.highlightDefault;
        pins.add(pin);
        return true;
    }

    /**
     * Moves the old free-text {@code pinnedMob} into {@code profile}'s list, once, and clears it.
     * Waits for a real profile so the pin lands where the player will look for it.
     *
     * @return whether the settings changed (and need saving)
     */
    public static boolean migrate(SBSConfig.BestiarySettings cfg, String profile) {
        String old = cfg.pinnedMob;
        if (old == null || old.isBlank() || profile == null || profile.isEmpty()) {
            return false;
        }
        if (!isPinned(cfg, profile, old)) {
            toggle(cfg, profile, old);
        }
        cfg.pinnedMob = "";
        return true;
    }
}
