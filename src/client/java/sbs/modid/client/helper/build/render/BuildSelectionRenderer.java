/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramManager;
import sbs.modid.client.core.build.logic.SelectionManager;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.Selection;
import sbs.modid.client.core.build.render.GhostStyle;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.helper.build.logic.BuildToolsOwner;
import sbs.modid.client.helper.build.logic.MagicStickInput;

import java.util.Locale;

/**
 * The selection box with its size label ({@code W×H×L · N blocks}) over its top, and the outline of
 * a Build Tools hologram's whole footprint.
 *
 * <p>The footprint outline matters for big pastes: only the cells near the player are drawn as
 * blocks, so without it a large hologram shows as a patch around you and nothing says where it ends.
 */
public final class BuildSelectionRenderer {

    private BuildSelectionRenderer() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.BuildToolsSettings cfg = ConfigManager.getInstance().get().buildTools;
        Minecraft minecraft = Minecraft.getInstance();
        // Cinematic freecam is a pure camera: no selection, outline or preview in the shot.
        if (!cfg.enabled || minecraft.player == null || minecraft.level == null
                || sbs.modid.client.helper.build.logic.Freecam.cinematic()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        int thickness = Math.max(1, Math.min(4, cfg.lineWidth));
        if (sbs.modid.client.helper.build.logic.SelectionActions.visible()) {
            drawSelection(g, viewProjection, camPos, cfg, thickness, minecraft.font);
        }
        drawFootprint(g, viewProjection, camPos, cfg, minecraft);
        sbs.modid.client.helper.build.logic.BuildGuide.renderHighlight(g, viewProjection, camPos);
    }

    private static void drawSelection(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos,
                                      SBSConfig.BuildToolsSettings cfg, int thickness, Font font) {
        SBSConfig.GardenBlueprintSettings garden = ConfigManager.getInstance().get().gardenBlueprint;
        SelectionManager selection = SelectionManager.getInstance();
        BlockPos a = selection.corner1();
        BlockPos b = selection.corner2();
        int fillAlpha = Math.max(0, Math.min(60, cfg.previewOpacity)) * 255 / 100;
        int liveRgb = GhostStyle.rgb(cfg.previewColorHex, 0x7FD4FF);
        int lockedRgb = GhostStyle.rgb(cfg.selectionColorHex, 0xFFE24B);

        // Targeting (build input - stick or build freecam - or a corner key held): the block that would
        // be selected is outlined with its name, and from corner 1 the live box follows it until corner 2.
        boolean targeting = sbs.modid.client.helper.build.logic.BuildInput.active()
                || sbs.modid.client.helper.build.logic.BuildTargeting.cornerKeyHeld();
        if (targeting) {
            sbs.modid.client.helper.build.logic.BuildTargeting.Target target =
                    sbs.modid.client.helper.build.logic.BuildTargeting.target();
            BlockPos aimed = target == null ? null : target.pos();
            boolean live = cfg.livePreview && a != null && aimed != null && (MagicStickInput.awaitingCorner2() || b == null);
            if (live) {
                drawVolume(g, vp, camPos, font, Math.min(a.getX(), aimed.getX()), Math.min(a.getY(), aimed.getY()),
                        Math.min(a.getZ(), aimed.getZ()), Math.max(a.getX(), aimed.getX()) + 1,
                        Math.max(a.getY(), aimed.getY()) + 1, Math.max(a.getZ(), aimed.getZ()) + 1,
                        liveRgb, fillAlpha, thickness, cfg.sizeLabel, true);
            }
            if (aimed != null && cfg.showTargetedBlock) {
                drawTarget(g, vp, camPos, font, aimed, liveRgb);
            }
            if (live) {
                return;
            }
        }
        if (a == null && b == null) {
            return;
        }
        // Garden Blueprint draws the same box in custom-area mode; one box, not two on top of each other.
        boolean gardenDraws = garden.enabled && garden.customArea;
        if (gardenDraws) {
            return;
        }
        if (a == null || b == null) {
            BlockPos only = a != null ? a : b;
            // A lone corner carries the clear hint, so ending the selection is never a mystery.
            drawVolume(g, vp, camPos, font, only.getX(), only.getY(), only.getZ(), only.getX() + 1, only.getY() + 1,
                    only.getZ() + 1, lockedRgb, 0, thickness, cfg.sizeLabel, a != null);
            return;
        }
        Selection box = selection.selection();
        drawVolume(g, vp, camPos, font, box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1,
                box.maxZ() + 1, lockedRgb, fillAlpha, thickness, cfg.sizeLabel, false);
        // The two corners the player set, marked so they can tell which is which.
        WorldRender.boxEdges(g, vp, camPos, a.getX(), a.getY(), a.getZ(), a.getX() + 1, a.getY() + 1,
                a.getZ() + 1, 0xFFFF8A3D, 1);
        WorldRender.boxEdges(g, vp, camPos, b.getX(), b.getY(), b.getZ(), b.getX() + 1, b.getY() + 1,
                b.getZ() + 1, 0xFF3DC8FF, 1);
    }

    /** The targeted block: an outline, and its name and position (plus the step depth) above it. */
    private static void drawTarget(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos, Font font, BlockPos pos, int rgb) {
        WorldRender.boxEdges(g, vp, camPos, pos.getX() - 0.01, pos.getY() - 0.01, pos.getZ() - 0.01,
                pos.getX() + 1.01, pos.getY() + 1.01, pos.getZ() + 1.01, 0xFF000000 | rgb, 2);
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        int depth = sbs.modid.client.helper.build.logic.BuildTargeting.depth();
        String text = level.getBlockState(pos).getBlock().getName().getString() + "  •  " + pos.getX() + ", "
                + pos.getY() + ", " + pos.getZ() + (depth > 0 ? "  •  +" + depth + " deep" : "");
        int[] screen = WorldRender.projectToScreen(vp, camPos, new Vec3(pos.getX() + 0.5, pos.getY() + 1.25,
                pos.getZ() + 0.5), g.guiWidth(), g.guiHeight());
        if (screen != null) {
            int w = font.width(text);
            g.fill(screen[0] - w / 2 - 3, screen[1] - 2, screen[0] + w / 2 + 3, screen[1] + font.lineHeight + 1, 0xB0000000);
            g.text(font, Component.literal(text), screen[0] - w / 2, screen[1], 0xFFFFFFFF);
        }
    }

    /**
     * The one look for a box of blocks - live selection, locked selection, hologram footprint: faces
     * lightly tinted (low alpha, so the blocks behind stay readable, in dark mode too), a solid outline
     * that carries the shape, and the size floating over it on a dark plate.
     */
    static void drawVolume(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos, Font font,
                           int x0, int y0, int z0, int x1, int y1, int z1,
                           int rgb, int fillAlpha, int thickness, boolean label, boolean onlyCorner1) {
        if (fillAlpha > 0) {
            WorldRender.fillBox(g, vp, camPos, x0, y0, z0, x1, y1, z1, (fillAlpha << 24) | rgb);
        }
        WorldRender.boxEdges(g, vp, camPos, x0, y0, z0, x1, y1, z1, 0xFF000000 | rgb, thickness);
        if (!label) {
            return;
        }
        String text = sbs.modid.client.helper.build.model.SelectionUx.label(x1 - x0, y1 - y0, z1 - z0, onlyCorner1);
        Vec3 top = new Vec3((x0 + x1) / 2.0, y1 + 0.4, (z0 + z1) / 2.0);
        int[] screen = WorldRender.projectToScreen(vp, camPos, top, g.guiWidth(), g.guiHeight());
        if (screen != null) {
            int w = font.width(text);
            g.fill(screen[0] - w / 2 - 3, screen[1] - 2, screen[0] + w / 2 + 3, screen[1] + font.lineHeight + 1,
                    0xB0000000);
            g.text(font, Component.literal(text), screen[0] - w / 2, screen[1], 0xFF000000 | rgb);
        }
    }

    private static void drawFootprint(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos,
                                      SBSConfig.BuildToolsSettings cfg, Minecraft minecraft) {
        Hologram hologram = HologramManager.getInstance().active();
        if (hologram == null || hologram.owner() != BuildToolsOwner.INSTANCE) {
            return;
        }
        BlockPos base = hologram.base(minecraft.player);
        if (base == null) {
            return;
        }
        Schematic shown = hologram.display();
        int rgb = hologram.mode() == Hologram.Mode.PREVIEW
                ? GhostStyle.rgb(cfg.addedColorHex, 0x30E030) : GhostStyle.rgb(cfg.previewColorHex, 0x7FD4FF);
        // Same look as the selection, fainter so the ghost blocks inside stay the focus.
        int fillAlpha = Math.max(0, Math.min(60, cfg.previewOpacity)) * 255 / 200;
        drawVolume(g, vp, camPos, minecraft.font, base.getX(), base.getY(), base.getZ(),
                base.getX() + shown.width(), base.getY() + shown.height(), base.getZ() + shown.length(),
                rgb, fillAlpha, 1, cfg.sizeLabel, false);
    }
}
