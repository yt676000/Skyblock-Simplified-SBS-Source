/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.abilitytimers;

import sbs.modid.client.combat.abilitytimers.logic.AbilityCooldownTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Death-Save Timers module (Combat): cooldown clocks for Bonzo's Mask, the Spirit Mask and the
 * Phoenix pet, started by their proc message in chat. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class AbilityTimersModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public AbilityTimersModule() {
    }

    @Override
    public String id() {
        return "ability_timers";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Death-Save Timers";
    }

    @Override
    public String description() {
        return "Cooldown card for Bonzo's Mask, Spirit Mask and the Phoenix pet";
    }

    @Override
    public int accentColor() {
        return 0xFFFF77DD;
    }

    private static SBSConfig.AbilityTimerSettings cfg() {
        return ConfigManager.getInstance().get().abilityTimers;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Death-Save Timers", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Shows a cooldown clock for each death-save item after it "
                                + "triggers (Bonzo's Mask, Spirit Mask, Phoenix pet), so you know "
                                + "whether you are protected or would really die right now."),
                SettingRow.label("Each clock starts on the save's own chat message, and only then"),

                SettingRow.toggle("Bonzo's Mask", () -> cfg().bonzoMask,
                        () -> { cfg().bonzoMask = !cfg().bonzoMask; save(); })
                        .describe("Track the Bonzo's Mask save cooldown."),
                SettingRow.toggle("Spirit Mask", () -> cfg().spiritMask,
                        () -> { cfg().spiritMask = !cfg().spiritMask; save(); })
                        .describe("Track the Spirit Mask save cooldown."),
                SettingRow.toggle("Phoenix Pet", () -> cfg().phoenixPet,
                        () -> { cfg().phoenixPet = !cfg().phoenixPet; save(); })
                        .describe("Track the Phoenix pet's revive cooldown."),

                SettingRow.toggle("Keep Showing When Ready", () -> cfg().showReady,
                        () -> { cfg().showReady = !cfg().showReady; save(); })
                        .describe("Keeps a row on screen saying READY after the cooldown ends, as "
                                + "a reassurance the save is up again. Off, the row simply "
                                + "disappears when it is ready."),
                SettingRow.label("Off: a row disappears the moment its cooldown is over"),

                SettingRow.intField("Bonzo Cooldown", 1, 600, () -> cfg().bonzoSeconds,
                        value -> { cfg().bonzoSeconds = value; save(); }, "s")
                        .describe("The Bonzo's Mask cooldown in seconds. Only change this if a "
                                + "game update retunes the item - the default is the live value."),
                SettingRow.intField("Spirit Cooldown", 1, 600, () -> cfg().spiritSeconds,
                        value -> { cfg().spiritSeconds = value; save(); }, "s")
                        .describe("The Spirit Mask cooldown in seconds. Only change this if a game "
                                + "update retunes the item."),
                SettingRow.intField("Phoenix Cooldown", 1, 600, () -> cfg().phoenixSeconds,
                        value -> { cfg().phoenixSeconds = value; save(); }, "s")
                        .describe("The Phoenix pet cooldown in seconds. Only change this if a game "
                                + "update retunes the pet."),
                SettingRow.label("§8Correct these if a balance patch retunes an item - defaults are the live values"),

                SettingRow.button("Reset Timers", () -> AbilityCooldownTracker.getInstance().reset())
                        .describe("Clears all running clocks, e.g. after a run ended and the "
                                + "cooldowns no longer matter."),
                SettingRow.button("Edit Timer GUI", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.ABILITY_TIMERS}, "Edit Timer GUI")))
                        .describe("Opens the editor where you drag the timer rows anywhere on the "
                                + "screen and scale them."));
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
