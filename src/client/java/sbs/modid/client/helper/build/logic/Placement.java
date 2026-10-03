/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramManager;
import sbs.modid.client.core.build.logic.PlacementSnaps;
import sbs.modid.client.core.build.model.BuildDir;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.build.model.SchematicTransform;
import sbs.modid.client.core.build.render.GhostCollector;

/**
 * A hologram being placed: it floats where you looked, and your keys move it until you press Enter.
 *
 * <p>While placing - and only then, and only with no screen open - these keys belong to the
 * hologram instead of the game: the arrows move it (relative to where you face, so Up is always
 * "away from me"), Page Up / Page Down and the mouse wheel raise and lower it, Shift makes a step
 * five blocks, R turns it a quarter clockwise (Shift+R back), F mirrors it left-right as you see it
 * (Shift+F upside down), G snaps it to the plot grid, Enter places it and Escape drops it. Nothing
 * is consumed outside placing, so the game's own use of those keys is untouched the rest of the time.
 *
 * <p>Enter "places" by what the world allows: in singleplayer the paste is applied through the edit
 * engine; on a server the hologram is pinned where it floats and stays as a guide to build along
 * with - the mod never places a block there.
 */
public final class Placement {

    /** How far in front of the player a paste floats when the crosshair is on nothing. */
    private static final int LOOK_FALLBACK_DISTANCE = 5;

    private static volatile boolean placing;

    /** What Enter does in singleplayer; installed by the edit engine, pin-only without it. */
    private static volatile Applier applier;

    private Placement() {
    }

    /** Applies a placed hologram to the world in singleplayer. */
    public interface Applier {
        void apply(Hologram hologram, BlockPos base);
    }

    public static void setApplier(Applier value) {
        applier = value;
    }

    /** True while a Build Tools hologram is floating and owns the placement keys. */
    public static boolean active() {
        if (!placing) {
            return false;
        }
        HologramManager manager = HologramManager.getInstance();
        Hologram hologram = manager.hologram();
        if (hologram == null || hologram.owner() != BuildToolsOwner.INSTANCE || !manager.shown()
                || manager.preview() != null || !BuildToolsOwner.cfg().enabled) {
            placing = false;
            return false;
        }
        return true;
    }

    /** Starts placing {@code schematic}: a floating paste hologram at the player's look point. */
    public static boolean start(Schematic schematic) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return false;
        }
        BlockPos anchor = null;
        if (schematic.header().source() == SchematicHeader.Source.PLOT) {
            // A plot copy lands on the plot grid by default - it was made to fit one.
            PlacementSnaps.Snap plot = PlacementSnaps.get("plot");
            if (plot != null) {
                anchor = plot.snap(player, minecraft.level, schematic);
            }
        }
        if (anchor == null) {
            anchor = lookAnchor(minecraft, player, schematic);
        }
        HologramManager.getInstance().set(
                Hologram.of(schematic, BuildToolsOwner.INSTANCE, anchor, Hologram.Mode.PASTE));
        placing = true;
        GhostCollector.invalidate();
        return true;
    }

    /**
     * Where a paste first appears: footprint centred on the block in front of the face you look at,
     * standing on it; or a few blocks ahead at your feet' height when you look at nothing.
     */
    static BlockPos lookAnchor(Minecraft minecraft, Player player, Schematic schematic) {
        BlockPos target;
        BuildTargeting.Target aimed = BuildTargeting.target();
        if (aimed != null) {
            target = aimed.pos().relative(aimed.face());
        } else {
            Vec3 ahead = player.position().add(player.getLookAngle().multiply(1, 0, 1).normalize()
                    .scale(LOOK_FALLBACK_DISTANCE));
            target = BlockPos.containing(ahead.x, player.getY(), ahead.z);
        }
        return target.offset(-schematic.width() / 2, 0, -schematic.length() / 2);
    }

    /** Stops placing and removes the floating hologram. */
    public static void cancel() {
        if (!placing) {
            return;
        }
        placing = false;
        HologramManager.getInstance().clearIfOwnedBy(BuildToolsOwner.INSTANCE);
        BuildChat.info("Placing cancelled");
    }

    /** Puts a hologram whose apply was refused back under the placing keys. */
    public static void resume() {
        Hologram hologram = current();
        if (hologram != null && hologram.owner() == BuildToolsOwner.INSTANCE) {
            placing = true;
        }
    }

    /** Leaving a world ends placing silently; the hologram itself is kept. */
    public static void onWorldLeave() {
        placing = false;
    }

    /**
     * Handles a key press while placing. Returns whether it was consumed - the input mixin cancels
     * the game's handling only when this says so.
     */
    public static boolean onKey(int key, int modifiers, boolean repeat) {
        if (!active()) {
            return false;
        }
        if (repeat && !BuildKeys.repeats(key)) {
            return owns(key);
        }
        boolean shift = (modifiers & 0x0001) != 0;
        int step = shift ? 5 : 1;
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        BuildDir facing = BuildDir.horizontalFromYaw(player.getYRot());
        switch (key) {
            case Keys.UP -> move(facing, step);
            case Keys.DOWN -> move(facing.opposite(), step);
            case Keys.LEFT -> move(facing.clockwise().opposite(), step);
            case Keys.RIGHT -> move(facing.clockwise(), step);
            case Keys.PAGE_UP -> move(BuildDir.UP, step);
            case Keys.PAGE_DOWN -> move(BuildDir.DOWN, step);
            case Keys.R -> turn(shift ? 3 : 1);
            case Keys.F -> flip(shift ? SchematicTransform.Axis.Y
                    : (facing == BuildDir.NORTH || facing == BuildDir.SOUTH)
                    ? SchematicTransform.Axis.X : SchematicTransform.Axis.Z);
            case Keys.G -> snap();
            case Keys.ENTER, Keys.KP_ENTER -> confirm();
            case Keys.ESCAPE -> cancel();
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code key} is one of the keys placing borrows. */
    private static boolean owns(int key) {
        return switch (key) {
            case Keys.UP, Keys.DOWN, Keys.LEFT, Keys.RIGHT, Keys.PAGE_UP, Keys.PAGE_DOWN, Keys.R, Keys.F,
                 Keys.G, Keys.ENTER, Keys.KP_ENTER, Keys.ESCAPE -> true;
            default -> false;
        };
    }

    /** Mouse wheel while placing: up and down, Shift for five. Returns whether it was consumed. */
    public static boolean onScroll(double yOffset, boolean shift) {
        if (!active() || yOffset == 0) {
            return false;
        }
        move(yOffset > 0 ? BuildDir.UP : BuildDir.DOWN, shift ? 5 : 1);
        return true;
    }

    private static Hologram current() {
        return HologramManager.getInstance().hologram();
    }

    private static void replace(Hologram hologram) {
        HologramManager.getInstance().update(hologram);
        GhostCollector.invalidate();
    }

    static void move(BuildDir dir, int step) {
        Hologram hologram = current();
        if (hologram == null || hologram.anchor() == null) {
            return;
        }
        replace(hologram.withAnchor(hologram.anchor().offset(dir.dx * step, dir.dy * step, dir.dz * step)));
    }

    static void turn(int quarterTurns) {
        Hologram hologram = current();
        if (hologram != null) {
            replace(hologram.rotated(quarterTurns));
        }
    }

    static void flip(SchematicTransform.Axis axis) {
        Hologram hologram = current();
        if (hologram != null) {
            replace(hologram.flipped(axis));
        }
    }

    static void snap() {
        Minecraft minecraft = Minecraft.getInstance();
        Hologram hologram = current();
        PlacementSnaps.Snap plot = PlacementSnaps.get("plot");
        if (hologram == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (plot == null) {
            BuildChat.warn("No grid to snap to - Garden Blueprint provides the plot grid");
            return;
        }
        BlockPos snapped = plot.snap(minecraft.player, minecraft.level, hologram.display());
        if (snapped != null) {
            replace(hologram.withAnchor(snapped));
            BuildChat.info("Snapped to the " + plot.label());
        }
    }

    /** Enter: apply in singleplayer, pin as a guide on a server. */
    static void confirm() {
        Hologram hologram = current();
        if (hologram == null || hologram.anchor() == null) {
            return;
        }
        placing = false;
        Applier apply = applier;
        if (BuildGate.singleplayer() && apply != null) {
            apply.apply(hologram, hologram.anchor());
            return;
        }
        replace(hologram.withMode(Hologram.Mode.COMPARE));
        BlockPos at = hologram.anchor();
        BuildChat.info("Hologram pinned at " + at.getX() + ", " + at.getY() + ", " + at.getZ()
                + " - build along with it (//hologram off to hide)");
    }

    /** GLFW key codes used while placing. */
    public static final class Keys {
        public static final int ESCAPE = 256;
        public static final int ENTER = 257;
        public static final int KP_ENTER = 335;
        public static final int RIGHT = 262;
        public static final int LEFT = 263;
        public static final int DOWN = 264;
        public static final int UP = 265;
        public static final int PAGE_UP = 266;
        public static final int PAGE_DOWN = 267;
        public static final int F = 70;
        public static final int G = 71;
        public static final int R = 82;

        private Keys() {
        }
    }
}
