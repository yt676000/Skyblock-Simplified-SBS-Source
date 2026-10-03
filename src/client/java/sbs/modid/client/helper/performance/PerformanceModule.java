/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.performance;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.performance.logic.ArmorStandCulling;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Performance module (Visuals): stop paying for armor stands the player never sees.
 *
 * <p>Hypixel implements holograms, health bars, decorations and script anchors as armor stands -
 * hundreds per lobby - and vanilla renders and ticks every single one. This module skips the ones
 * that draw nothing at all and culls the rest by distance; the rules live in
 * {@link ArmorStandCulling}, hooked into the render dispatcher and the client entity tick.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class PerformanceModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public PerformanceModule() {
    }

    @Override
    public String id() {
        return "performance";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.VISUALS;
    }

    @Override
    public String displayName() {
        return "Performance";
    }

    @Override
    public String description() {
        return "Skip the invisible helper armor stands behind holograms and scripts, "
                + "and cull the rest by distance";
    }

    @Override
    public int accentColor() {
        return 0xFF8FD14D;
    }

    private static SBSConfig.PerformanceSettings cfg() {
        return ConfigManager.getInstance().get().performance;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /** Live counts for the label row, so the page shows what the filter is doing right now. */
    private static String summary() {
        // Null client as well as null level: the settings rows are also built by offline tooling,
        // where there is no Minecraft instance at all.
        var minecraft = Minecraft.getInstance();
        var level = minecraft == null ? null : minecraft.level;
        var player = minecraft == null ? null : minecraft.player;
        if (level == null || player == null) {
            return "Join a world to see armor stand counts";
        }
        int total = 0;
        int skipped = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof ArmorStand) {
                total++;
                if (ArmorStandCulling.skipRender(entity, player.getX(), player.getY(), player.getZ())) {
                    skipped++;
                }
            }
        }
        return total + " armor stands loaded, " + skipped + " currently skipped";
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Performance", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Master switch. Hypixel builds holograms, health bars and "
                                + "decorations out of armor stands - hundreds per lobby - and the "
                                + "game renders and ticks every one. On, the pointless ones are "
                                + "skipped; off restores vanilla behaviour instantly."),
                SettingRow.toggle("Hide Invisible Helper Stands", () -> cfg().hideChromeStands,
                        () -> { cfg().hideChromeStands = !cfg().hideChromeStands; save(); })
                        .describe("Skips armor stands that draw nothing at all: invisible, no "
                                + "visible name, nothing equipped. Hypixel scatters these as "
                                + "script anchors and hologram spacers. A stand that gains a "
                                + "name or an item reappears the same frame."),
                SettingRow.rangeSlider("Armor Stand Distance", 0, 128,
                        () -> cfg().standRenderDistance,
                        value -> { cfg().standRenderDistance = value; save(); }, "blocks")
                        .describe("Armor stands farther away than this are not rendered - "
                                + "hologram text is unreadable at that range anyway. 0 means no "
                                + "limit (vanilla). Your own boxes and trackers are unaffected: "
                                + "they draw independently of the stand itself."),
                SettingRow.toggle("Skip Hidden Stand Ticking", () -> cfg().tickCulling,
                        () -> { cfg().tickCulling = !cfg().tickCulling; save(); })
                        .describe("Armor stands that are not rendered anyway also stop being "
                                + "calculated each tick. Stands carrying another entity keep "
                                + "ticking (their tick moves the rider), and stands resume a "
                                + "little before they come back into view so nothing snaps."),
                SettingRow.label(summary()));
    }
}
