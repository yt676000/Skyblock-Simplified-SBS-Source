/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.dungeons.run.logic.DungeonRoomMatcher;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker.WorldWaypoint;
import sbs.modid.client.dungeons.run.logic.WitherDoorTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * World-space highlight for dungeon waypoints, drawn as HUD markers.
 *
 * <p>1.26.2's world render pipeline is GPU-buffer based (no classic {@code renderLineBox}), so instead of
 * a world-render mixin we project each world point to the screen ourselves – exactly like the vanilla
 * {@code GameRenderer.projectPointToScreen} – and draw a box marker through the proven
 * {@link GuiGraphicsExtractor} HUD pipeline. We do the projection manually so we can read the clip-space
 * {@code w} and cull points behind the camera.
 *
 * <p>Layers:
 * <ul>
 *   <li><b>Waypoints</b> (feature): the active room's waypoints, drawn whenever "Room Waypoints" is on.</li>
 *   <li><b>Debug</b> (dev only): a red box on the tested door, and green/red boxes on every scanned block
 *       (green = the database block is really there, red = mismatch), plus {@code /sbstest} markers.</li>
 * </ul>
 */
public final class DungeonHighlight {

    private static final DungeonHighlight INSTANCE = new DungeonHighlight();

    private static final int COLOR_WAYPOINT = 0xFF00E5FF; // cyan
    private static final int COLOR_TEST = 0xFFFFD000;     // amber
    private static final int COLOR_DOOR = 0xFFFF2020;     // red
    private static final int COLOR_MATCH = 0xFF30E030;    // green
    private static final int COLOR_MISS = 0xFFFF2020;     // red

    /** {@code /sbstest} markers (dev only). */
    private final List<BlockPos> testWaypoints = new ArrayList<>();

    private static final int COLOR_COMPLEX = 0xFFFFE000; // gold – room complex box
    private static final int COLOR_STARRED = 0xFFFFE000; // yellow – starred mob hitbox
    /** Height (blocks) of the drawn room-complex box above the floor. */
    private static final int BOX_HEIGHT = 6;

    /** Edge thickness in GUI pixels - matches the 2px line this class used to draw itself. */
    private static final int LINE_THICKNESS = 2;

    /** Starred (✯) dungeon mobs to box, refreshed every client tick by the tracker. */
    private volatile List<net.minecraft.world.entity.Entity> starredMobs = List.of();

    /** Debug scan overlay (dev only): the tested door + each scanned block and whether it matched. */
    private volatile BlockPos debugDoor;
    private volatile Direction debugFacing;
    private volatile List<DungeonRoomMatcher.BlockResult> debugBlocks = List.of();

    /** Debug room-border boxes (dev only): one {min,max} pair per footprint rect, pre-computed. */
    private volatile List<BlockPos[]> debugComplexBoxes = List.of();

    /** One horizontal room-outline segment (world XZ, axis-aligned; drawn at floor and roof height). */
    public record OutlineSegment(int x0, int z0, int x1, int z1) {
    }

    /** Debug room outline (dev only): the merged border polygon of the footprint, pre-computed. */
    private volatile List<OutlineSegment> debugOutline = List.of();
    private volatile int debugOutlineY;

    private DungeonHighlight() {
    }

    public static DungeonHighlight getInstance() {
        return INSTANCE;
    }

    // ---- external mutators -------------------------------------------------------------------------

    public void addTestWaypoint(BlockPos pos) {
        testWaypoints.add(pos.immutable());
    }

    public void clearTestWaypoints() {
        testWaypoints.clear();
    }

    public int testWaypointCount() {
        return testWaypoints.size();
    }

    /** Records the current debug scan (door + per-block matches) for the highlight overlay. */
    public void setDebugScan(BlockPos door, Direction facing, List<DungeonRoomMatcher.BlockResult> blocks) {
        this.debugDoor = door == null ? null : door.immutable();
        this.debugFacing = facing;
        this.debugBlocks = blocks == null ? List.of() : blocks;
    }

    public void clearDebugScan() {
        this.debugDoor = null;
        this.debugFacing = null;
        this.debugBlocks = List.of();
    }

    /**
     * Records the detected room-border boxes ({@code {min,max}} pairs) for the dev line boxes. The list
     * is cached as-is: the render tick only projects the pre-computed corners, it never re-detects.
     */
    public void setDebugComplex(List<BlockPos[]> boxes) {
        this.debugComplexBoxes = boxes == null ? List.of() : List.copyOf(boxes);
        this.debugOutline = List.of();
    }

    public void clearDebugComplex() {
        this.debugComplexBoxes = List.of();
        this.debugOutline = List.of();
    }

    /**
     * Records the room's merged border outline (one connected polygon around the whole footprint,
     * bridging the 1-block cell gaps) for the dev overlay. Replaces the per-rect boxes: when an
     * outline is set, {@link #setDebugComplex} boxes are cleared and vice versa.
     */
    public void setDebugOutline(List<OutlineSegment> segments, int floorY) {
        this.debugOutline = segments == null ? List.of() : List.copyOf(segments);
        this.debugOutlineY = floorY;
        this.debugComplexBoxes = List.of();
    }

    /** Replaces the starred-mob list (collected once per client tick, never in the render pass). */
    public void setStarredMobs(List<net.minecraft.world.entity.Entity> mobs) {
        this.starredMobs = mobs == null ? List.of() : List.copyOf(mobs);
    }

    public void clearStarredMobs() {
        this.starredMobs = List.of();
    }

    // ---- rendering ---------------------------------------------------------------------------------

    /** Draws every active marker. Called from the HUD render hook. */
    public void render(GuiGraphicsExtractor g) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        boolean tracers = ConfigManager.getInstance().get().dungeons.showTracers;

        // Feature: the active room's waypoints (hidden ones filtered; secrets tick in the client tick).
        if (ConfigManager.getInstance().get().dungeons.roomWaypoints) {
            DungeonRoomTracker tracker = DungeonRoomTracker.getInstance();
            for (WorldWaypoint waypoint : tracker.waypoints()) {
                if (tracker.isHidden(waypoint.name())) {
                    continue;
                }
                Vec3 center = center(waypoint.world());
                marker(g, font, viewProjection, camPos, center, COLOR_WAYPOINT, waypoint.name(), true);
                if (tracers) {
                    sbs.modid.client.core.render.WorldRender.tracer(g, viewProjection, camPos,
                            center, COLOR_WAYPOINT, 2);
                }
            }
        }

        // Feature: coal wither / blood doors, boxed in the locked colour (or the key colour on the
        // current room's door while a wither key is held). The list is built on the client tick.
        if (ConfigManager.getInstance().get().dungeons.witherDoors) {
            var dungeons = ConfigManager.getInstance().get().dungeons;
            for (WitherDoorTracker.DoorBox door : WitherDoorTracker.getInstance().doorBoxes()) {
                int color = (door.green() ? dungeons.witherDoorKeyColor : dungeons.witherDoorColor).argb();
                BlockPos min = door.min();
                BlockPos max = door.max();
                drawBoxEdges(g, viewProjection, camPos, min.getX(), min.getY(), min.getZ(),
                        max.getX() + 1, max.getY() + 1, max.getZ() + 1, color);
                if (tracers) {
                    sbs.modid.client.core.render.WorldRender.tracer(g, viewProjection, camPos,
                            new Vec3((min.getX() + max.getX() + 1) / 2.0,
                                    (min.getY() + max.getY() + 1) / 2.0,
                                    (min.getZ() + max.getZ() + 1) / 2.0), color, 2);
                }
            }
        }

        // Feature: yellow hitbox around every visible starred (✯) mob (list is tick-cached).
        for (net.minecraft.world.entity.Entity mob : starredMobs) {
            if (mob.isAlive()) {
                net.minecraft.world.phys.AABB box = mob.getBoundingBox();
                drawBoxEdges(g, viewProjection, camPos,
                        box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, COLOR_STARRED);
                if (tracers) {
                    sbs.modid.client.core.render.WorldRender.tracerToBox(g, viewProjection, camPos,
                            box, COLOR_STARRED, 2);
                }
            }
        }

        // Debug layer: only in developer mode.
        if (DevMode.ACTIVE) {
            for (BlockPos test : testWaypoints) {
                marker(g, font, viewProjection, camPos, center(test), COLOR_TEST, "TEST", true);
            }
            if (debugDoor != null) {
                marker(g, font, viewProjection, camPos, center(debugDoor), COLOR_DOOR, "ANCHOR " + debugFacing, true);
            }
            for (DungeonRoomMatcher.BlockResult block : debugBlocks) {
                marker(g, font, viewProjection, camPos, center(block.world()),
                        block.matched() ? COLOR_MATCH : COLOR_MISS, null, false);
            }
            for (BlockPos[] box : debugComplexBoxes) {
                drawComplexBox(g, viewProjection, camPos, box[0], box[1]);
            }
            drawOutline(g, viewProjection, camPos);
        }
    }

    /**
     * Draws the merged room outline: every segment once at floor height and once at roof height
     * ({@value #BOX_HEIGHT} above), plus one vertical post per unique segment endpoint – a single
     * connected border that visibly encloses the whole room, instead of per-cell boxes.
     */
    private void drawOutline(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos) {
        List<OutlineSegment> outline = debugOutline;
        if (outline.isEmpty()) {
            return;
        }
        int floorY = debugOutlineY;
        int roofY = floorY + BOX_HEIGHT;
        java.util.Set<Long> posts = new java.util.HashSet<>();
        for (OutlineSegment seg : outline) {
            line3D(g, vp, camPos, new Vec3(seg.x0(), floorY, seg.z0()), new Vec3(seg.x1(), floorY, seg.z1()));
            line3D(g, vp, camPos, new Vec3(seg.x0(), roofY, seg.z0()), new Vec3(seg.x1(), roofY, seg.z1()));
            posts.add(packXZ(seg.x0(), seg.z0()));
            posts.add(packXZ(seg.x1(), seg.z1()));
        }
        for (long post : posts) {
            int x = (int) (post >> 32);
            int z = (int) post;
            line3D(g, vp, camPos, new Vec3(x, floorY, z), new Vec3(x, roofY, z));
        }
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static void line3D(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos, Vec3 a, Vec3 b) {
        sbs.modid.client.core.render.WorldRender.line3d(g, vp, camPos, a, b, COLOR_COMPLEX, LINE_THICKNESS);
    }

    /** Draws a 3D line box around the room complex (its 32x32-cell footprint, {@value #BOX_HEIGHT} high). */
    private static void drawComplexBox(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos, BlockPos min, BlockPos max) {
        drawBoxEdges(g, vp, camPos, min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, min.getY() + BOX_HEIGHT, max.getZ() + 1, COLOR_COMPLEX);
    }

    /** Projects and draws the 12 edges of an arbitrary world-space box. */
    /**
     * Room-complex box edges.
     *
     * <p>Delegated to {@link sbs.modid.client.core.render.WorldRender}, which clips each edge against
     * the near plane instead of dropping the ones with a corner behind the camera. This class used to
     * carry its own copy of the projection and drop them, which is why a room border came apart and
     * snapped back as you turned inside the room - the very place you are standing when you need it.
     * The 2px line it drew is exactly {@code WorldRender.line} at {@link #LINE_THICKNESS}.
     */
    private static void drawBoxEdges(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos,
                                     double x0, double y0, double z0, double x1, double y1, double z1, int color) {
        sbs.modid.client.core.render.WorldRender.boxEdges(g, vp, camPos, x0, y0, z0, x1, y1, z1,
                color, LINE_THICKNESS);
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** Projects {@code world} to the screen and draws a box marker (and an optional label + distance). */
    private static void marker(GuiGraphicsExtractor g, Font font, Matrix4f viewProjection, Vec3 camPos,
                               Vec3 world, int color, String label, boolean big) {
        Vec3 relative = world.subtract(camPos);
        Vector4f clip = viewProjection.transform(
                new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1.0f));
        if (clip.w <= 1.0e-4f) {
            return; // behind the camera
        }
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;

        int sx = Math.round((ndcX * 0.5f + 0.5f) * g.guiWidth());
        int sy = Math.round((0.5f - ndcY * 0.5f) * g.guiHeight());

        double distance = camPos.distanceTo(world);
        int half = big
                ? clamp((int) Math.round(60.0 / Math.max(1.0, distance)), 3, 24)
                : clamp((int) Math.round(24.0 / Math.max(1.0, distance)), 2, 12);
        boxOutline(g, sx - half, sy - half, sx + half, sy + half, color, big ? 2 : 1);

        if (label != null) {
            String text = label + " §7" + (int) Math.round(distance) + "m";
            g.centeredText(font, Component.literal(text), sx, sy - half - font.lineHeight - 1, color);
        }
    }

    private static void boxOutline(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color, int thickness) {
        g.fill(x1, y1, x2, y1 + thickness, color);       // top
        g.fill(x1, y2 - thickness, x2, y2, color);       // bottom
        g.fill(x1, y1, x1 + thickness, y2, color);       // left
        g.fill(x2 - thickness, y1, x2, y2, color);       // right
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
