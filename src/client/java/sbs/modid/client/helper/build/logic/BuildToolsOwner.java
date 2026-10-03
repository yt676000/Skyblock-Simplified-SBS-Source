/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramOwner;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.build.render.GhostModels;
import sbs.modid.client.core.build.render.GhostStyle;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * Build Tools as a hologram owner: shown while the module is on, drawn in its own colours.
 *
 * <p>The colours depend on what the hologram is for. Before a paste a wrong cell is a
 * <b>collision</b> (a block the paste would overwrite); while building along it is a mistake; in an
 * edit preview the added and removed colours take over. Never gated by location - a Build Tools
 * hologram is pinned to a world spot and is useful anywhere.
 */
public final class BuildToolsOwner implements HologramOwner {

    public static final BuildToolsOwner INSTANCE = new BuildToolsOwner();

    private BuildToolsOwner() {
    }

    static SBSConfig.BuildToolsSettings cfg() {
        return ConfigManager.getInstance().get().buildTools;
    }

    @Override
    public String name() {
        return "Build Tools";
    }

    @Override
    public boolean visible(Hologram hologram, Player player) {
        return cfg().enabled;
    }

    @Override
    public BlockPos unpinnedBase(Hologram hologram, Player player) {
        SchematicHeader header = hologram.display().header();
        if (header.origin() == null) {
            return null;
        }
        return new BlockPos(header.originX(0), header.originY(0), header.originZ(0));
    }

    @Override
    public GhostStyle style() {
        SBSConfig.BuildToolsSettings cfg = cfg();
        Hologram active = sbs.modid.client.core.build.logic.HologramManager.getInstance().active();
        boolean preview = active != null && active.mode() == Hologram.Mode.PREVIEW;
        int added = GhostStyle.rgb(cfg.addedColorHex, 0x30E030);
        int removed = GhostStyle.rgb(cfg.removedColorHex, 0xFF2020);
        return new GhostStyle(
                Math.max(4, Math.min(64, cfg.renderRadius)),
                GhostStyle.alpha(cfg.edgeOpacity, 10, 100),
                Math.max(1, Math.min(4, cfg.lineWidth)),
                cfg.fillGhosts,
                GhostStyle.alpha(cfg.fillOpacity, 5, 80),
                cfg.ghostModels,
                Math.max(GhostModels.MIN_OPACITY, Math.min(100, cfg.modelOpacity)),
                // A preview's changed blocks are what the edit puts there, so they get a model like
                // an added one; elsewhere a model over a wrong block only muddies the red box.
                preview,
                !preview && cfg.showCorrect,
                preview ? added : GhostStyle.rgb(cfg.ghostColorHex, 0x3FB4FF),
                preview ? added : GhostStyle.rgb(cfg.collisionColorHex, 0xFF2020),
                GhostStyle.rgb(cfg.correctColorHex, 0x30E030),
                removed);
    }
}
