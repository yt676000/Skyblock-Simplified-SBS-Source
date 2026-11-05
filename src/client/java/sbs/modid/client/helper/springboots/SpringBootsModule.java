/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.springboots;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.springboots.logic.SpringBootsCharge;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boots Charge (Quality of Life): a meter showing how far the "To the Moon!" sneak jump is
 * charged. Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p><b>Display only.</b> It reads the player's own sneak and feet slot and draws a meter; it never
 * sneaks, jumps or releases for the player, so it is outside the automation rule entirely.
 */
public final class SpringBootsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public SpringBootsModule() {
    }

    @Override
    public String id() {
        return "spring_boots";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public String displayName() {
        return "Spring Boots Charge";
    }

    @Override
    public String description() {
        return "Shows how far your Spring Boots jump is charged while you sneak";
    }

    @Override
    public int accentColor() {
        return 0xFF57D977;
    }

    private static SBSConfig.SpringBootsSettings cfg() {
        return ConfigManager.getInstance().get().springBoots;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(10);
        rows.add(SettingRow.toggle("Spring Boots Charge", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("While you sneak wearing Spring Boots, a small meter shows how far the "
                        + "To the Moon! jump is charged, for example Charge 4/6. It turns green at full "
                        + "charge and disappears when you stop sneaking. Default: on.")
                .anchor("spring_boots_enabled"));
        rows.add(SettingRow.label("§8The step count is §eestimated§8 from how long you sneak ("
                + SpringBootsCharge.MAX_STEPS + " steps of " + SpringBootsCharge.TICKS_PER_STEP
                + " ticks); a §f~§8 marks it."));
        rows.add(SettingRow.label("§8Nobody has timed a real charge yet, so treat the number as a guide."));
        rows.add(SettingRow.toggle("Pin To Crosshair", () -> cfg().pinToCrosshair,
                        () -> { cfg().pinToCrosshair = !cfg().pinToCrosshair; save(); })
                .describe("Draws the meter just under the crosshair, where your eyes already are. "
                        + "Switch it off to place the meter yourself with Move / Resize Meter. "
                        + "Default: on.")
                .anchor("spring_boots_pin_crosshair"));
        rows.add(SettingRow.button("Move / Resize Meter", () -> net.minecraft.client.Minecraft.getInstance()
                        .setScreenAndShow(new HudEditorScreen(
                                new HudElement[] {HudElement.SPRING_BOOTS_CHARGE}, "Edit Spring Boots Charge")))
                .describe("Opens the editor where you drag the meter anywhere on the screen and scale "
                        + "it. Only used while Pin To Crosshair is off."));
        rows.add(SettingRow.toggle("Show Jump Height", () -> cfg().showHeight,
                        () -> { cfg().showHeight = !cfg().showHeight; save(); })
                .describe("Adds how high a jump with this much charge went, for example +12.4m. The "
                        + "height is the one measured on your own last jump at that step this "
                        + "session - nothing is shown until you have made one, because a guessed "
                        + "height would be worse than none. Default: on.")
                .anchor("spring_boots_show_height"));
        rows.add(SettingRow.toggle("Sound At Full Charge", () -> cfg().fullChargeSound,
                        () -> { cfg().fullChargeSound = !cfg().fullChargeSound; save(); })
                .describe("One short ping when the meter reaches full charge. Plays only while the "
                        + "SBS Sounds master switch is on. Default: off.")
                .anchor("spring_boots_full_sound"));
        rows.add(SettingRow.text("Charge Sound Id", "", 80,
                        () -> cfg().chargeSound,
                        value -> { cfg().chargeSound = value; save(); })
                .describe("Part of the id of the sound Hypixel plays for each charge step, for "
                        + "example note_block. When set, every such sound near you counts as one "
                        + "step and the ~ disappears; when empty, steps are estimated from time. "
                        + "Left empty on purpose until the real sound is confirmed.")
                .anchor("spring_boots_charge_sound"));
        return rows;
    }
}
