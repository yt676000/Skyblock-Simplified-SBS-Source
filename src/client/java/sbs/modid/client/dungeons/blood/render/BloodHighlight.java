/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.blood.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.blood.logic.BloodRoomTracker;

/**
 * The blood room as it is drawn: a line from the crosshair to every mob, a box on each, and the one
 * you should hit next picked out of them. Pure view over {@link BloodRoomTracker}'s tick cache -
 * nothing is decided here.
 *
 * <p><b>The lines are the feature, not the decoration.</b> The blood room is fought standing still
 * and turning, with mobs behind you, behind pillars and behind each other. A box only helps once the
 * mob is already on screen, which is the moment you no longer need help; a line points at what you
 * cannot see. So the lines go to <b>every</b> mob and they go <b>through walls</b>, on their own
 * toggle - "lines only, no boxes" is a perfectly good setup and needs no boxes turned on.
 *
 * <p><b>The colours are a kill order, not a palette.</b> Violet is not a mob yet - it is a body that
 * has landed with the milliseconds left before it can be hit. Red is a "Master", the kind that
 * actually kills people. Blue is everything else. The next target - closest live mob, Masters first -
 * is drawn thicker than the rest so one glance answers "where do I swing".
 */
public final class BloodHighlight {

    private static final int COLOR_MASTER = 0xFFFF4040; // red
    private static final int COLOR_NORMAL = 0xFF60C0FF; // calm blue
    private static final int COLOR_SPAWN = 0xFFB070FF;  // violet - not a mob yet
    private static final int COLOR_TARGET = 0xFF7CFF6A; // green - the one to swing at
    private static final int COLOR_ARC = 0xFFFFC24A;    // amber - where one starts falling: the shot
    private static final int COLOR_AIM_FILL = 0x38FFC24A; // the same amber, thin enough to see through
    private static final int COLOR_MARK = 0xFFFF8A3D;   // orange - the Watcher's spawn skull

    /** Line and box weight: the next target is thicker, a spawn-in thinner than a live mob. */
    private static final int WEIGHT_TARGET = 3;
    private static final int WEIGHT_MOB = 2;
    private static final int WEIGHT_SPAWN = 1;

    /** Half-length of the arms of the aiming cross, in GUI pixels. */
    private static final int AIM_CROSS = 5;

    private BloodHighlight() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.BloodSettings cfg = ConfigManager.getInstance().get().blood;
        Minecraft minecraft = Minecraft.getInstance();
        if (!cfg.enabled || minecraft.player == null || minecraft.level == null) {
            return;
        }
        BloodRoomTracker tracker = BloodRoomTracker.getInstance();
        if (!tracker.running() || (!cfg.highlightMobs && !cfg.showTracers && !cfg.showSpawns)) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;
        // Matched by entity id, not by instance: the two snapshots are published a moment apart and a
        // frame that straddles them would otherwise lose the target highlight for that frame.
        BloodRoomTracker.BloodMob target = tracker.target();
        int targetId = target == null ? -1 : target.entity().getId();

        // Underneath everything: the next second of the room. The Watcher's skulls mark where his
        // mobs are about to appear, and the arcs say where the ones already thrown will come down -
        // both are places to stand before there is anything there to hit.
        if (cfg.showSpawns) {
            for (BloodRoomTracker.BloodDrop drop : tracker.drops()) {
                arc(g, font, viewProjection, camPos, drop);
            }
            for (BloodRoomTracker.BloodMark mark : tracker.marks()) {
                mark(g, font, viewProjection, camPos, cfg, mark);
            }
        }

        // Back to front: the mobs still spawning in are drawn first, so a live mob's box and the
        // target's line end up on top of them rather than under them.
        for (int pass = 0; pass < 2; pass++) {
            boolean livePass = pass == 1;
            for (BloodRoomTracker.BloodMob mob : tracker.mobs()) {
                if (mob.ready() != livePass || !mob.entity().isAlive()) {
                    continue;
                }
                draw(g, font, viewProjection, camPos, cfg, mob, mob.entity().getId() == targetId);
            }
        }
    }

    private static void draw(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                             SBSConfig.BloodSettings cfg, BloodRoomTracker.BloodMob mob,
                             boolean isTarget) {
        AABB box = mob.entity().getBoundingBox();
        int color = !mob.ready() ? COLOR_SPAWN
                : isTarget ? COLOR_TARGET
                : (mob.kind() == BloodRoomTracker.Kind.MASTER ? COLOR_MASTER : COLOR_NORMAL);
        int weight = !mob.ready() ? WEIGHT_SPAWN : (isTarget ? WEIGHT_TARGET : WEIGHT_MOB);

        // The line goes out to every mob, seen or not - it is what tells you which way to turn. The
        // box is the part that stays behind the line-of-sight filter.
        if (cfg.showTracers) {
            WorldRender.tracerToBox(g, vp, camPos, box, color, weight);
        }
        boolean boxed = mob.ready() ? cfg.highlightMobs && mob.visible() : cfg.showSpawns;
        if (!boxed) {
            return;
        }
        WorldRender.boxEdges(g, vp, camPos,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, weight);
        // A spawn-in always says how long is left - that number is the whole reason to mark it. A
        // live mob's name is optional, because by then the box already says where it is.
        if (!mob.ready()) {
            label(g, font, vp, camPos, box, BloodRoomTracker.spawnLabel(mob), color);
        } else if (cfg.showLabels && !mob.name().isEmpty()) {
            label(g, font, vp, camPos, box, mob.name(), color);
        }
    }

    /**
     * A spawn skull: boxed where it hangs, with what is left of its wait. This is the earliest thing
     * the room shows - the mob is not there yet and cannot be - so it is drawn as a marker rather
     * than as a target, and it gets the tracer treatment because the whole point is to be standing
     * there before it turns into something that hits back.
     *
     * <p>Only the thrown ones reach this method. The room's own skulls hang there as decoration and
     * boxing them said nothing at all, so the tracker keeps the ones that came in through the air.
     */
    private static void mark(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                             SBSConfig.BloodSettings cfg, BloodRoomTracker.BloodMark mark) {
        if (!mark.entity().isAlive()) {
            return;
        }
        AABB box = mark.entity().getBoundingBox().inflate(0.15);
        if (cfg.showTracers) {
            WorldRender.tracerToBox(g, vp, camPos, box, COLOR_MARK, WEIGHT_MOB);
        }
        WorldRender.boxEdges(g, vp, camPos,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, COLOR_MARK, WEIGHT_MOB);
        label(g, font, vp, camPos, box,
                mark.etaMs() < 0 ? "spawn" : "spawn " + mark.etaMs() + "ms", COLOR_MARK);
    }

    /**
     * A thrown mob's flight: one <b>straight line</b> from the mob to the end of its throw, and its
     * <b>hitbox drawn in the air</b> there, with a crosshair in the middle of it and the wait until
     * the shot is live.
     *
     * <p><b>The line is straight because the throw is.</b> The server carries these mobs in a
     * straight angled line of fixed length and drops them at its end - a curve would be drawing
     * physics that is not happening, and the curve this used to draw is exactly why the mark and the
     * mob disagreed. The end of the line is where the mob stops, hangs and becomes hittable, so
     * that is the one point marked.
     *
     * <p><b>And it is the mob's box, not a marker's.</b> An aiming mark whose size has nothing to do
     * with the target answers "roughly over there" when the question is "am I on it" - so the box is
     * the entity's own bounding box, translated to the drop point. Where its outline ends is where
     * the shot stops connecting.
     */
    private static void arc(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                            BloodRoomTracker.BloodDrop drop) {
        // The mob's own hitbox, moved to where it is going to be hanging - so what is drawn is the
        // size of the thing the shot has to hit, at the height it will be at. The marker used to be
        // a fixed 1x1 cube centred on the drop point, and a drop point is a mob's FEET: on anything
        // taller than a chicken it sat half a block under the target and covered none of it.
        AABB aim = drop.entity().getBoundingBox()
                .move(drop.dropPoint().subtract(drop.entity().position()));
        WorldRender.line3d(g, vp, camPos, drop.entity().position(), aim.getCenter(), COLOR_ARC, 1);
        WorldRender.fillBox(g, vp, camPos,
                aim.minX, aim.minY, aim.minZ, aim.maxX, aim.maxY, aim.maxZ, COLOR_AIM_FILL);
        WorldRender.boxEdges(g, vp, camPos,
                aim.minX, aim.minY, aim.minZ, aim.maxX, aim.maxY, aim.maxZ, COLOR_ARC, WEIGHT_TARGET);
        // A cross in the middle of it: the box says how big the target is and the cross says where
        // the shot goes, which at the far end of the room is the only part of it still readable.
        crosshair(g, vp, camPos, aim.getCenter());
        // "now" rather than "0ms": the mob is at the end of its line and the shot is live. The time
        // comes off the mob's measured pace this tick, so lag stretches the number instead of
        // breaking it; while the pace is unreadable the box stands without one.
        label(g, font, vp, camPos, aim,
                drop.etaMs() < 0 ? "shoot" : drop.etaMs() < 50 ? "shoot now"
                        : "shoot " + drop.etaMs() + "ms", COLOR_ARC);
    }

    /** A small screen-space cross on the point to put the crosshair on. */
    private static void crosshair(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos, Vec3 at) {
        int[] screen = WorldRender.projectToScreen(vp, camPos, at, g.guiWidth(), g.guiHeight());
        if (screen == null) {
            return;
        }
        int x = screen[0];
        int y = screen[1];
        WorldRender.line(g, x - AIM_CROSS, y, x + AIM_CROSS, y, COLOR_ARC, 1);
        WorldRender.line(g, x, y - AIM_CROSS, x, y + AIM_CROSS, COLOR_ARC, 1);
    }

    private static void label(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                              AABB box, String text, int color) {
        Vec3 top = new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.5, (box.minZ + box.maxZ) / 2);
        int[] screen = WorldRender.projectToScreen(vp, camPos, top, g.guiWidth(), g.guiHeight());
        if (screen != null) {
            g.centeredText(font, Component.literal(text), screen[0], screen[1], color);
        }
    }
}
