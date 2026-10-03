/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.damage;

import sbs.modid.client.combat.damage.logic.AbilityDamageTracker;
import sbs.modid.client.combat.damage.model.AbilityDamageMode;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Ability Damage module (Combat): decides where Hypixel's "Your Implosion hit 1 enemy for
 * 1,394,599.3 damage." lines go – chat as usual, gone, or the movable HUD card. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class AbilityDamageModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public AbilityDamageModule() {
    }

    @Override
    public String id() {
        return "ability_damage";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Ability Damage";
    }

    @Override
    public String description() {
        return "Keep, hide or move the \"Your X hit N enemies for Y damage\" lines onto a HUD card";
    }

    @Override
    public int accentColor() {
        return 0xFFE0605F;
    }

    private static SBSConfig.AbilityDamageSettings cfg() {
        return ConfigManager.getInstance().get().abilityDamage;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.enumOptions("Ability Damage Lines", () -> cfg().mode,
                        value -> {
                            cfg().mode = value;
                            // Switching away from the card leaves no stale hits behind.
                            if (cfg().mode != AbilityDamageMode.HUD) {
                                AbilityDamageTracker.getInstance().clear();
                            }
                            save();
                        }, v -> v.displayName())
                        .describe("Where the \"Your X hit N enemies for Y damage\" messages go: "
                                + "left in chat as normal, hidden completely, or moved onto a HUD "
                                + "card (and removed from chat). Click to switch between the "
                                + "three."),
                SettingRow.label("Keep in chat  •  Hide  •  HUD card (removed from chat)"),

                SettingRow.rangeSlider("Card Lines", 1, 10, () -> cfg().hudLines,
                        value -> { cfg().hudLines = value; save(); }, "")
                        .describe("How many of your latest ability hits the HUD card shows at "
                                + "once."),
                SettingRow.rangeSlider("Keep On Card", 2, 30, () -> cfg().hudHoldSeconds,
                        value -> { cfg().hudHoldSeconds = value; save(); }, "s")
                        .describe("How many seconds each hit stays on the card before it fades. "
                                + "The card hides itself once every hit has expired."),
                SettingRow.toggle("Show Total", () -> cfg().hudTotal,
                        () -> { cfg().hudTotal = !cfg().hudTotal; save(); })
                        .describe("Adds a line summing the damage of every hit currently on the "
                                + "card - a rough burst-damage readout."),

                SettingRow.button("Move / Resize Overlay", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.ABILITY_DAMAGE}, "Edit Ability Damage")))
                        .describe("Opens the editor where you drag the ability-damage card anywhere "
                                + "on the screen and scale it."),
                SettingRow.label("Shows while a hit is still inside the keep-on-card time"));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
