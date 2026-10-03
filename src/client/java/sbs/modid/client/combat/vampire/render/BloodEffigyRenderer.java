/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.vampire.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.combat.vampire.logic.BloodEffigyTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.core.render.WorldRender;

import java.util.List;
import java.util.Locale;

/**
 * Draws the Blood Effigies: how much of each is left, and when a broken one comes back.
 *
 * <p>A standing effigy is boxed around its whole three-block stack, which is also the shape you
 * break; a broken one is boxed around its base alone, because the base is what is left and the base
 * is the thing you must not hit. That difference is the warning made visual - the marker changes
 * into the shape of the mistake.
 *
 * <p>Informational only. Nothing here writes to input, rotation or item use.
 */
public final class BloodEffigyRenderer {

    /** Effigies further than this are drawn without a label. */
    private static final double LABEL_RANGE = 80.0;

    private BloodEffigyRenderer() {
    }

    private static SBSConfig.VampireSettings cfg() {
        return ConfigManager.getInstance().get().vampire;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.VampireSettings cfg = cfg();
        if (!cfg.enabled || !cfg.effigies) {
            return;
        }
        List<BloodEffigyTracker.Effigy> effigies = BloodEffigyTracker.getInstance().effigies();
        if (effigies.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        for (BloodEffigyTracker.Effigy effigy : effigies) {
            double baseX = effigy.base().getX();
            double baseY = effigy.base().getY();
            double baseZ = effigy.base().getZ();
            // A standing effigy is boxed around what is left of its stack; a broken one around the
            // base alone, which is exactly the block the reset warning is about.
            double top = baseY + 1 + Math.max(0, effigy.blocksLeft());
            Vec3 centre = new Vec3(baseX + 0.5, (baseY + top) / 2.0, baseZ + 0.5);

            if (!cfg.throughWalls && occluded(minecraft, camPos, centre)) {
                continue;
            }
            int color = effigy.broken()
                    ? colorOf(cfg.brokenColor, cfg.brokenColorHex, cfg.brokenOpacity)
                    : colorOf(cfg.standingColor, cfg.standingColorHex, cfg.standingOpacity);
            WorldRender.boxEdges(g, viewProjection, camPos,
                    baseX, baseY, baseZ, baseX + 1, top, baseZ + 1, color, 2);

            double distance = camPos.distanceTo(centre);
            if (distance > LABEL_RANGE) {
                continue;
            }
            int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                    centre.add(0, (top - baseY) / 2.0 + 0.5, 0), g.guiWidth(), g.guiHeight());
            if (screen == null) {
                continue;
            }
            g.centeredText(font, Component.literal(label(cfg, effigy)),
                    screen[0], screen[1], 0xFFFFFFFF);
            if (effigy.broken()) {
                g.centeredText(font, Component.literal("§cDo not hit the base"),
                        screen[0], screen[1] + font.lineHeight, 0xFFFFFFFF);
            }
        }
    }

    /** "Effigy 2/3" while it stands, "Effigy back in 12:04 ?" once it is broken. */
    private static String label(SBSConfig.VampireSettings cfg, BloodEffigyTracker.Effigy effigy) {
        if (!effigy.broken()) {
            return "§cEffigy §7" + effigy.blocksLeft() + "/3";
        }
        long remaining = effigy.respawnIn();
        if (!cfg.effigyRespawnTimer || remaining < 0) {
            return "§7Effigy broken";
        }
        // The '?' is not decoration: the respawn length is a wiki figure nobody has timed here yet,
        // and a countdown that looks authoritative is worse than one that admits what it is.
        String suffix = BloodEffigyTracker.respawnCertainty().trusted() ? "" : " §8?";
        return "§7Effigy back in §f" + formatMillis(remaining) + suffix;
    }

    private static String formatMillis(long millis) {
        long totalSeconds = millis / 1000L;
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static int colorOf(OverlayColor preset, String hex, int opacityPercent) {
        Integer custom = OverlayColor.parseHex(hex);
        int rgb = custom != null ? custom : preset.rgb();
        int alpha = Math.max(0, Math.min(255, Math.round(opacityPercent * 255f / 100f)));
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static boolean occluded(Minecraft minecraft, Vec3 camPos, Vec3 target) {
        HitResult hit = minecraft.level.clip(new ClipContext(camPos, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getLocation().distanceToSqr(camPos) < target.distanceToSqr(camPos) - 1.0;
    }
}
