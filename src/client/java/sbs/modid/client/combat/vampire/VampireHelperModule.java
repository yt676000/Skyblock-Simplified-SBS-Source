/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.vampire;

import sbs.modid.client.combat.vampire.logic.BloodEffigyTracker;
import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Vampire helper (Blood Effigies): which of the six around Stillgore Château are standing, and the
 * one mistake that costs you a respawn.
 *
 * <p><b>Scope, deliberately narrow.</b> The Bloodfiend's own fight cues - the Twinclaws warning, the
 * Blood Ichor and Killer Spring highlights - already live in the Slayer module, and duplicating them
 * here would mean two features flashing at the same event. What was missing is the effigies, which
 * belong to the château rather than to a boss fight and are not slayer state at all.
 *
 * <p><b>Informational only.</b> This module shows what is happening and when; it never acts. There
 * is no code path in it that writes to input, rotation or item use.
 */
public final class VampireHelperModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public VampireHelperModule() {
    }

    @Override
    public String id() {
        return "vampire_helper";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Blood Effigies";
    }

    @Override
    public String description() {
        return "Effigy state around Stillgore Château, and the base you must not hit";
    }

    @Override
    public int accentColor() {
        return 0xFFE0605F;
    }

    private static SBSConfig.VampireSettings cfg() {
        return ConfigManager.getInstance().get().vampire;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(List.of(
                SettingRow.toggle("Blood Effigies", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Marks the Blood Effigies around Stillgore Château. Only scans "
                                + "while you are actually at the château - nowhere else in the "
                                + "Rift, and nowhere outside it."),
                SettingRow.label("Stillgore Château only; found by looking, not from a coordinate list"),

                SettingRow.toggle("Show Markers", () -> cfg().effigies,
                        () -> { cfg().effigies = !cfg().effigies; save(); })
                        .describe("Boxes each effigy with how much of it is left. They break top to "
                                + "bottom, three blocks each, and the count is read from the world "
                                + "rather than guessed - so it is exact even if somebody else "
                                + "started breaking it."),
                SettingRow.toggle("Respawn Timer", () -> cfg().effigyRespawnTimer,
                        () -> { cfg().effigyRespawnTimer = !cfg().effigyRespawnTimer; save(); })
                        .describe("Counts down to a broken effigy coming back. The twenty minutes "
                                + "it counts is a wiki figure that has not been timed here yet, so "
                                + "the countdown carries a '?' until it has."),
                SettingRow.toggle("Warn Before Resetting", () -> cfg().effigyResetWarning,
                        () -> { cfg().effigyResetWarning = !cfg().effigyResetWarning; save(); })
                        .describe("Warns while you are aiming at the base of a broken effigy. "
                                + "Hitting it restarts that effigy's respawn from the beginning - "
                                + "silently, with no message and no way to undo it - and it is "
                                + "easy to do by accident while swinging at something else. The "
                                + "warning comes before the swing, not after."),
                SettingRow.toggle("Through Walls", () -> cfg().throughWalls,
                        () -> { cfg().throughWalls = !cfg().throughWalls; save(); })
                        .describe("Keeps markers visible with the château in the way."),

                SettingRow.label("§8—— Colours ——"),
                SettingRow.enumOptions("Standing Colour", () -> cfg().standingColor,
                        value -> {
                            cfg().standingColor = value;
                            cfg().standingColorHex = "";
                            save();
                        }, v -> v.displayName()),
                SettingRow.rangeSlider("Standing Opacity", 10, 100, () -> cfg().standingOpacity,
                        value -> { cfg().standingOpacity = value; save(); }, "%"),
                SettingRow.enumOptions("Broken Colour", () -> cfg().brokenColor,
                        value -> {
                            cfg().brokenColor = value;
                            cfg().brokenColorHex = "";
                            save();
                        }, v -> v.displayName()),
                SettingRow.rangeSlider("Broken Opacity", 0, 100, () -> cfg().brokenOpacity,
                        value -> { cfg().brokenOpacity = value; save(); }, "%")));

        rows.addAll(AlertChannelRows.forAlert("broken_effigy", "you aim at a broken effigy's base",
                () -> cfg().alertChannels,
                mask -> { cfg().alertChannels = mask; save(); }));

        rows.add(SettingRow.label("§8—— Status ——"));
        rows.add(SettingRow.label("§8" + statusLine()));
        rows.add(SettingRow.label("§8The fight's own cues (Twinclaws, Ichor, Killer Spring) are in "
                + "the Slayer module - they belong to the boss, not to the château."));
        rows.add(SettingRow.label("§8This helper only tells you what and when. It never clicks, "
                + "aims or uses an item for you."));
        return List.copyOf(rows);
    }

    private static String statusLine() {
        List<BloodEffigyTracker.Effigy> effigies = BloodEffigyTracker.getInstance().effigies();
        if (effigies.isEmpty()) {
            return "No effigies in range - this only reads at Stillgore Château";
        }
        return BloodEffigyTracker.getInstance().standingCount() + " of " + effigies.size()
                + " seen effigies standing (" + BloodEffigyTracker.EFFIGY_COUNT + " exist)";
    }
}
