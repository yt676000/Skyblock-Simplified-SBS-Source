/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Damage Overlay module (Combat): a card with the predicted damage of your next hit on the mob
 * under the crosshair – normal hit, crit, DPS and swings-to-kill, computed with the full SkyBlock
 * damage formula against the mob's live HP bar (Execute / Prosecute / Giant Killer are exactly the
 * enchants whose value changes with that bar). Player stats are captured from the SkyBlock Menu;
 * per-mob accuracy improves automatically from your own attributed splashes. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class DamageOverlayModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public DamageOverlayModule() {
    }

    @Override
    public String id() {
        return "damage_overlay";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Damage Overlay";
    }

    @Override
    public String description() {
        return "Predicted hit / crit / DPS / swings-to-kill for the mob you are looking at, "
                + "live against its HP bar";
    }

    @Override
    public int accentColor() {
        return 0xFFE08A3C;
    }

    private static SBSConfig.DamageOverlaySettings cfg() {
        return ConfigManager.getInstance().get().damageOverlay;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Damage Overlay", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Shows a card with the damage your next hit should do to the mob "
                                + "you are aiming at - single hit, crit, DPS and how many swings "
                                + "the kill takes. Enchants like Execute and Prosecute are "
                                + "recomputed live from the mob's health bar."),
                SettingRow.label("Open the SkyBlock Menu once so it can read your stats"),
                SettingRow.label("Numbers self-correct per mob from your real (attributed) hits"),

                SettingRow.rangeSlider("Target Range", 8, 50, () -> cfg().range,
                        v -> { cfg().range = v; save(); }, " blocks")
                        .describe("How far ahead the crosshair looks for a mob. Longer helps "
                                + "picking targets before engaging; shorter keeps the card from "
                                + "jumping between far-away mobs."),
                SettingRow.toggle("Show DPS", () -> cfg().showDps,
                        () -> { cfg().showDps = !cfg().showDps; save(); })
                        .describe("Adds the damage-per-second line: average hit (crit chance "
                                + "weighted) times attack speed times Ferocity."),
                SettingRow.toggle("Show Swings To Kill", () -> cfg().showHitsToKill,
                        () -> { cfg().showHitsToKill = !cfg().showHitsToKill; save(); })
                        .describe("Adds the 'kill in ~N hits' line. Simulated swing by swing, so "
                                + "Execute getting stronger as the mob drops is counted properly."),
                SettingRow.toggle("Show Details Line", () -> cfg().showDetails,
                        () -> { cfg().showDetails = !cfg().showDetails; save(); })
                        .describe("Adds the small footer with the defense value used and the "
                                + "learned per-mob correction factor."),

                SettingRow.toggle("Auto-Calibrate", () -> cfg().autoCalibrate,
                        () -> { cfg().autoCalibrate = !cfg().autoCalibrate; save(); })
                        .describe("Compares the prediction with your real damage numbers (the ones "
                                + "Damage Attribution confirms as yours) and corrects future "
                                + "estimates per mob. Covers the mob's unknown defense and buffs "
                                + "the client cannot see. Needs the Damage Attribution module ON."),
                SettingRow.rangeSlider("Combat Level", 0, 60, () -> cfg().combatLevel,
                        v -> { cfg().combatLevel = v; save(); }, "")
                        .describe("Your Combat skill level (+4% damage per level up to 50, +1% "
                                + "beyond). Captured automatically from menus that show it - this "
                                + "slider is the manual override."),
                SettingRow.text("Defense Overrides", "mob:defense, mob:defense", 256,
                        () -> cfg().mobDefenseOverrides == null ? "" : cfg().mobDefenseOverrides,
                        v -> { cfg().mobDefenseOverrides = v == null ? "" : v; save(); })
                        .describe("Manual defense values per mob, e.g. 'Magma Boss:70, Enderman:0'. "
                                + "Only needed when auto-calibration is off - defense values are "
                                + "not published anywhere, so learned corrections usually beat "
                                + "hand-tuning."),

                SettingRow.button("Move / Resize Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.DAMAGE_ESTIMATE}, "Edit Damage Estimate")))
                        .describe("Opens the editor where you drag the card anywhere on the screen "
                                + "and scale it."),
                SettingRow.toggle("Debug Chat Log", () -> cfg().debugLog,
                        () -> { cfg().debugLog = !cfg().debugLog; save(); })
                        .describe("Prints stat captures and calibration samples to chat. For "
                                + "checking why a number is off - noisy, leave it off normally."),
                SettingRow.label("Estimates cover melee hits; ability damage has its own card"));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
