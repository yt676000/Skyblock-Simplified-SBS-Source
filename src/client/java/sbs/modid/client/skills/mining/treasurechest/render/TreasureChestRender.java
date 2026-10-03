/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.treasurechest.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.skills.mining.treasurechest.logic.LockpickSpot;
import sbs.modid.client.skills.mining.treasurechest.logic.TreasureChestTracker;

import java.util.List;

/**
 * The chest you uncovered, outlined, and a small box on the spot the lockpick burst marks.
 *
 * <p>Drawing only. The player aims and clicks; nothing here moves the camera or sends input.
 */
public final class TreasureChestRender {

    private static final int CHEST_COLOR = 0xFFE0B040;
    private static final int SPOT_EDGE = 0xFFFFFFFF;
    private static final int SPOT_FILL = 0x9040E0FF;
    /** Half the marker's edge: small enough to say "here", big enough to see at arm's length. */
    private static final double SPOT_HALF = 0.07;

    private TreasureChestRender() {
    }

    /** Called from {@code HudMixin.extractHotbarAndDecorations} at TAIL; self-gating. */
    public static void render(GuiGraphicsExtractor g) {
        MiningHelpersSettings cfg = ConfigManager.getInstance().get().miningHelpers;
        if (!cfg.enabled || (!cfg.treasureChestBox && !cfg.lockpickMarker)) {
            return;
        }
        List<TreasureChestTracker.Chest> chests = TreasureChestTracker.getInstance().chests();
        if (chests.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        long now = System.currentTimeMillis();

        for (TreasureChestTracker.Chest chest : chests) {
            if (cfg.treasureChestBox) {
                // A chest's model is inset a sixteenth on every side and lower than a block; the
                // outline follows it so it hugs the chest rather than the empty block around it.
                WorldRender.boxEdges(g, viewProjection, camPos,
                        chest.x() + 0.0625, chest.y(), chest.z() + 0.0625,
                        chest.x() + 0.9375, chest.y() + 0.875, chest.z() + 0.9375,
                        CHEST_COLOR, 2);
            }
            if (cfg.lockpickMarker) {
                LockpickSpot.Spot spot = chest.spot(now);
                if (spot != null) {
                    WorldRender.fillBox(g, viewProjection, camPos,
                            spot.x() - SPOT_HALF, spot.y() - SPOT_HALF, spot.z() - SPOT_HALF,
                            spot.x() + SPOT_HALF, spot.y() + SPOT_HALF, spot.z() + SPOT_HALF,
                            SPOT_FILL);
                    WorldRender.boxEdges(g, viewProjection, camPos,
                            spot.x() - SPOT_HALF, spot.y() - SPOT_HALF, spot.z() - SPOT_HALF,
                            spot.x() + SPOT_HALF, spot.y() + SPOT_HALF, spot.z() + SPOT_HALF,
                            SPOT_EDGE, 1);
                }
            }
        }
    }
}
