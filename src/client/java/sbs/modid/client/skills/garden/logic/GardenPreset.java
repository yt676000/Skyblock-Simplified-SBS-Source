/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramOwner;
import sbs.modid.client.core.build.logic.PlacementSnaps;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.build.render.GhostModels;
import sbs.modid.client.core.build.render.GhostStyle;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.garden.model.GardenPlot;

/**
 * Garden Blueprint as a hologram owner: the plot-grid preset of the shared build hologram.
 *
 * <p>What makes a hologram a Garden one is decided here and nowhere else:
 * <ul>
 *   <li><b>Placement.</b> A plot copy ({@link SchematicHeader.Source#PLOT}) with no pin re-anchors to
 *       the 96x96 plot the player stands on - copy one plot, rebuild it on any other. A custom-area
 *       copy with no pin stays on the world spot it was copied from, to check a build in place.</li>
 *   <li><b>The gate.</b> "Only In Garden" hides an unpinned plot copy off the Garden; a custom area or
 *       a pinned ghost is anchored to a world spot and is never gated.</li>
 *   <li><b>The look</b> - Garden Blueprint's own colours, radius and model settings, unchanged.</li>
 * </ul>
 *
 * <p>A plot copy also carries two numbers in its header so it can be hung at the right height on
 * another plot: {@link #FLOOR_DEPTH} (how far the capture reaches below the floor it was taken
 * standing on) and {@link #BEDROCK_HEIGHT} (how far its lowest layer sits above the bedrock slab).
 */
public final class GardenPreset implements HologramOwner {

    public static final GardenPreset INSTANCE = new GardenPreset();

    /** Header extra: layers the capture reaches below the floor it was taken standing on. */
    public static final String FLOOR_DEPTH = "garden.floorDepth";

    /** Header extra: height of the capture's lowest layer above the bedrock slab under it. */
    public static final String BEDROCK_HEIGHT = "garden.bedrockHeight";

    /** How far below the feet {@link #bedrockUnder} looks for the plot floor. */
    private static final int BEDROCK_SEARCH_DEPTH = 24;

    /** {@link #bedrockUnder} when no bedrock floor was found. */
    public static final int NO_BEDROCK = Integer.MIN_VALUE;

    /** The plot-grid snap, for any hologram - see {@link #snapToPlot}. */
    public static final PlacementSnaps.Snap PLOT_SNAP = new PlacementSnaps.Snap() {
        @Override
        public String id() {
            return "plot";
        }

        @Override
        public String label() {
            return "plot grid";
        }

        @Override
        public BlockPos snap(Player player, Level level, Schematic schematic) {
            return snapToPlot(player, level, schematic);
        }
    };

    private GardenPreset() {
    }

    private static SBSConfig.GardenBlueprintSettings cfg() {
        return ConfigManager.getInstance().get().gardenBlueprint;
    }

    @Override
    public String name() {
        return "Garden Blueprint";
    }

    /** True for a plot copy - the hologram then follows the plot grid. */
    public static boolean plotRelative(Schematic schematic) {
        return schematic.header().source() == SchematicHeader.Source.PLOT;
    }

    @Override
    public boolean visible(Hologram hologram, Player player) {
        SBSConfig.GardenBlueprintSettings cfg = cfg();
        if (!cfg.enabled) {
            return false;
        }
        return !(cfg.onlyInGarden && hologram.anchor() == null && plotRelative(hologram.display())
                && !GardenBlueprintManager.inGarden());
    }

    @Override
    public BlockPos unpinnedBase(Hologram hologram, Player player) {
        SchematicHeader header = hologram.display().header();
        if (header.origin() == null) {
            return null;
        }
        if (plotRelative(hologram.display())) {
            GardenPlot.Bounds target = GardenPlot.at(player.getX(), player.getZ());
            return new BlockPos(target.minX(), header.originY(0), target.minZ());
        }
        return new BlockPos(header.originX(0), header.originY(0), header.originZ(0));
    }

    @Override
    public GhostStyle style() {
        SBSConfig.GardenBlueprintSettings cfg = cfg();
        return new GhostStyle(
                Math.max(4, Math.min(48, cfg.renderRadius)),
                GhostStyle.alpha(cfg.opacity, 10, 100),
                Math.max(1, Math.min(4, cfg.lineWidth)),
                cfg.fillGhosts,
                GhostStyle.alpha(cfg.fillOpacity, 5, 80),
                cfg.ghostModels,
                Math.max(GhostModels.MIN_OPACITY, Math.min(100, cfg.modelOpacity)),
                cfg.ghostModelsOnWrong,
                cfg.showCorrect,
                GhostStyle.rgb(cfg.ghostColorHex, 0x3FB4FF),
                GhostStyle.rgb(cfg.errorColorHex, 0xFF2020),
                GhostStyle.rgb(cfg.correctColorHex, 0x30E030),
                GhostStyle.rgb(cfg.errorColorHex, 0xFF2020));
    }

    /**
     * The Y of the bedrock floor under {@code (x, z)}, searching down from {@code y}, or
     * {@link #NO_BEDROCK} when there is none within {@link #BEDROCK_SEARCH_DEPTH} blocks.
     *
     * <p>Every Garden plot sits on the same bedrock slab, so it is the one landmark that means the
     * same thing on every plot - which makes it the reference a copied plot is re-placed against.
     * Off the Garden (or with the floor not loaded) there is no such landmark and the caller falls
     * back to the block underfoot.
     */
    public static int bedrockUnder(Level level, int x, int y, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int lowest = Math.max(level.getMinY(), y - BEDROCK_SEARCH_DEPTH);
        for (int scanY = y; scanY >= lowest; scanY--) {
            if (level.getBlockState(pos.set(x, scanY, z)).is(Blocks.BEDROCK)) {
                return scanY;
            }
        }
        return NO_BEDROCK;
    }

    /**
     * Where a plot copy's min corner goes when snapped to the plot under {@code player}: the plot's
     * 96-grid corner in X/Z, and in Y hung off the bedrock slab so it lands at the height it was built
     * at over the floor.
     *
     * <p>Bedrock is the reference because it is the one layer every Garden plot shares - the surface
     * above it is whatever the player farmed there, so measuring against that would sink or lift the
     * ghost by however deep the target plot happens to be dug. Without bedrock (off the Garden, or the
     * floor not loaded) the block underfoot is used, dropped by the capture's floor depth so its floor
     * still lands on it.
     */
    public static BlockPos snapToPlot(Player player, Level level, Schematic schematic) {
        GardenPlot.Bounds plot = GardenPlot.at(player.getX(), player.getZ());
        SchematicHeader header = schematic.header();
        int feetY = player.getBlockY();
        int y = (feetY - 1) - header.extra(FLOOR_DEPTH, 0);
        if (header.hasExtra(BEDROCK_HEIGHT)) {
            int bedrockY = bedrockUnder(level, player.getBlockX(), feetY, player.getBlockZ());
            if (bedrockY != NO_BEDROCK) {
                y = bedrockY + header.extra(BEDROCK_HEIGHT, 0);
            }
        }
        return new BlockPos(plot.minX(), y, plot.minZ());
    }
}
