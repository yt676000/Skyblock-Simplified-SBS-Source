/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.traps.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.traps.logic.TrapIndex;
import sbs.modid.client.dungeons.traps.model.Trap;
import sbs.modid.client.dungeons.traps.model.TripwireRun;

/**
 * Draws the indexed traps: a box per dispenser, one shape per tripwire run.
 *
 * <p><b>Line of sight is tested by hand.</b> {@link WorldRender} projects world points onto the HUD
 * and does no depth testing anywhere, so nothing is ever hidden by terrain on its own - but "hidden
 * by terrain" can still be answered, by casting one ray from the camera to the trap and seeing what
 * it hits first. That is what {@link #visible} does, the same {@link ClipContext.Block#COLLIDER}
 * check the pathfinding and Necron-healer renderers already use. So the toggle below is real and
 * honoured; an earlier note in this file claimed the renderer could not offer one, which was wrong -
 * it cannot depth-test, which is a different thing.
 *
 * <p><b>A trap block that is itself solid counts as seen.</b> A dispenser stops the ray, so a naive
 * "something is in the way" test would hide every dispenser from every angle. A ray ending on the
 * trap's own block is therefore line of sight, not an obstruction - the distinction the Necron
 * waypoint makes for the same reason. Tripwire has no collider at all and the ray passes straight
 * through it, so a wire run is judged by what sits between the camera and the wire instead.
 *
 * <p><b>Distance capped.</b> A big room can hold a lot of both, and fifty boxes at once is a screen
 * nobody can read. The cap is measured from the camera to the block, so what is drawn is what is
 * close enough to walk into.
 *
 * <p><b>The facing spike is the point of the dispenser box.</b> A box says "there is a dispenser
 * here"; the short spike out of its face says which way the arrows come, which is what decides
 * whether the player hugs this wall or the opposite one. It is read from the blockstate, never
 * guessed from which side happens to touch air.
 */
public final class TrapEsp {

    /** How far out of the block face the facing spike reaches. */
    private static final double SPIKE = 0.9;

    /** Wire sits low in its block; the run is drawn as a slab at roughly the wire's own height. */
    private static final double WIRE_LOW = 0.05;
    private static final double WIRE_HIGH = 0.25;

    private TrapEsp() {
    }

    /** Called from the HUD world-render pass once per frame. Self-gating. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.TrapHighlightSettings cfg = ConfigManager.getInstance().get().trapHighlight;
        if (!TrapIndex.armed()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        TrapIndex index = TrapIndex.getInstance();
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        double limit = (double) cfg.renderDistance * cfg.renderDistance;

        if (cfg.dispensers) {
            int color = cfg.dispenserColor.argb();
            for (Trap trap : index.dispensers()) {
                BlockPos pos = trap.pos();
                Vec3 centre = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (camPos.distanceToSqr(centre) > limit) {
                    continue;
                }
                if (!cfg.showThroughWalls && !visible(minecraft, camPos, centre, pos)) {
                    continue;
                }
                WorldRender.boxEdges(g, viewProjection, camPos,
                        pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0, color, 2);
                if (cfg.showFacing && trap.facing() != null) {
                    drawFacing(g, viewProjection, camPos, pos, trap.facing(), color);
                }
            }
        }

        if (cfg.tripwires) {
            int color = cfg.tripwireColor.argb();
            for (TripwireRun run : index.tripwireRuns()) {
                if (camPos.distanceToSqr(run.centreX(), run.centreY(), run.centreZ()) > limit) {
                    continue;
                }
                if (!cfg.showThroughWalls && !runVisible(minecraft, camPos, run)) {
                    continue;
                }
                BlockPos min = run.min();
                BlockPos max = run.max();
                WorldRender.boxEdges(g, viewProjection, camPos,
                        min.getX(), min.getY() + WIRE_LOW, min.getZ(),
                        max.getX() + 1.0, max.getY() + WIRE_HIGH, max.getZ() + 1.0, color, 2);
            }
        }
    }

    /**
     * Whether the camera has a clear line to {@code target}.
     *
     * <p>{@code own} is the trap's own block, and a ray ending there is line of sight rather than an
     * obstruction - without that exception every dispenser would hide itself, since a dispenser is a
     * solid block and stops the ray it is the target of. {@code null} for anything with no collider
     * of its own, where only what lies in between can block the view.
     */
    private static boolean visible(Minecraft minecraft, Vec3 camPos, Vec3 target, BlockPos own) {
        if (minecraft.level == null || minecraft.player == null) {
            return true;
        }
        HitResult hit = minecraft.level.clip(new ClipContext(camPos, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return true;
        }
        if (own != null && hit instanceof BlockHitResult block && own.equals(block.getBlockPos())) {
            return true;
        }
        // A hit beyond the target is not in the way; the tolerance keeps a ray that grazes the
        // target's own face from reading as an obstruction.
        return hit.getLocation().distanceToSqr(camPos) >= target.distanceToSqr(camPos) - 1.0;
    }

    /**
     * Whether any part of a wire run can be seen.
     *
     * <p>Three samples rather than one: a trip line long enough to matter usually crosses a doorway,
     * so its far end can be behind a wall while the near end is in plain sight. Testing only the
     * centre would drop the whole line the moment its middle went behind a pillar - and a trap you
     * are about to walk into is the wrong thing to hide.
     */
    private static boolean runVisible(Minecraft minecraft, Vec3 camPos, TripwireRun run) {
        BlockPos min = run.min();
        BlockPos max = run.max();
        Vec3[] samples = {
                new Vec3(run.centreX(), run.centreY(), run.centreZ()),
                new Vec3(min.getX() + 0.5, min.getY() + 0.5, min.getZ() + 0.5),
                new Vec3(max.getX() + 0.5, max.getY() + 0.5, max.getZ() + 0.5)};
        for (Vec3 sample : samples) {
            if (visible(minecraft, camPos, sample, null)) {
                return true;
            }
        }
        return false;
    }

    /** A short spike out of the dispenser's face - the direction its arrows travel. */
    private static void drawFacing(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                   BlockPos pos, Direction facing, int color) {
        Vec3 centre = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3 tip = centre.add(facing.getStepX() * SPIKE, facing.getStepY() * SPIKE,
                facing.getStepZ() * SPIKE);
        WorldRender.line3d(g, viewProjection, camPos, centre, tip, color, 3);
    }
}
