/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.cape;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * SBS Cape (Wardrobe): paints the SBS cape onto your own player.
 *
 * <p><b>Local, and only local.</b> The cape is applied to the render state on this machine, after
 * the server has said what your skin is - so it costs Hypixel nothing, tells it nothing, and no
 * other player can see it. The settings page states that outright: a cosmetic nobody else sees is
 * otherwise reported as broken rather than understood as what a client mod can do.
 *
 * <p>Everything that draws lives in {@code core/mixin/SbsCapeMixin}; this class is the settings page
 * and the texture's name, nothing more. Self-registered through
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class CapeModule implements SbsModule {

    /**
     * The cape art: 1024x512, which is exactly 16x the 64x32 UV grid {@code PlayerCapeModel} samples,
     * so the 128x128 badge sits on the outer face at 1:1 with no resampling at all. The whole sheet
     * is opaque on purpose - the cape is drawn with an entity-solid render type, which discards alpha
     * and would otherwise turn the badge's transparent corners black.
     */
    public static final Identifier TEXTURE = SkyblockSimplifiedSBS.id("textures/cape/sbs.png");

    /** The cape's ground colour, as asked for. Kept here so the swatch and the art cannot drift. */
    private static final int CAPE_COLOR = 0xFF0E1F35;

    /** ServiceLoader needs a public no-arg constructor. */
    public CapeModule() {
    }

    @Override
    public String id() {
        return "sbs_cape";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.WARDROBE;
    }

    @Override
    public String displayName() {
        return "SBS Cape";
    }

    @Override
    public String description() {
        return "Wear the SBS cape - drawn by your own client, so only you see it";
    }

    @Override
    public int accentColor() {
        return CAPE_COLOR;
    }

    private static SBSConfig.WardrobeSettings cfg() {
        return ConfigManager.getInstance().get().wardrobe;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("SBS Cape", () -> cfg().sbsCape,
                        () -> { cfg().sbsCape = !cfg().sbsCape; save(); })
                        .describe("Draws the SBS cape on your player. On by default. While it is on "
                                + "it replaces any cape the account already has, so switching it off "
                                + "is how you get a Mojang cape back."),
                SettingRow.label("Only you can see it - the cape is drawn by your client, not by Hypixel"),
                SettingRow.label("Third person or an inventory preview: a cape is behind you"));
    }
}
