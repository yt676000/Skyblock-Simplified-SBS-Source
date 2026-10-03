/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;

import java.util.List;
import java.util.function.Consumer;

/**
 * Orchestrates the developer actions: scanning a room and dropping waypoints (border-anchor model).
 *
 * <p>The anchor is the centre of the room's void-detected footprint, taken from the live
 * {@link DungeonRoomTracker} lock – standing anywhere inside a recognised room is enough, no door
 * needed. Blocks and waypoints are exported as pure {@code world − anchor} deltas (NORTH-canonical
 * identity; the runtime matcher factors out the room's actual spawn rotation by trying all four
 * facings around the same rotation-invariant centre). Waypoints are validated against the room
 * borders so a coordinate from a neighbouring room can never be exported.
 */
public final class DevActions {

    /** Exports are written in the canonical (identity) frame; rotation is resolved at match time. */
    private static final Direction CANONICAL_FACING = Direction.NORTH;

    private static String activeRoomName;
    private static RoomMapReader.RoomInfo activeRoomInfo;
    /** The room anchor {@link #activeRoomName} was scanned at – the name is only valid there. */
    private static BlockPos activeRoomAnchor;

    private DevActions() {
    }

    /**
     * The name of the last dev scan this session, but <b>only while the player is still in that very
     * room</b> ({@code anchor} = the tracker's current room anchor); {@code null} otherwise.
     *
     * <p>The anchor check is the whole point. This name is the last resort for naming a room the
     * database does not know, and it used to be handed out unconditionally – so after scanning room A
     * and walking on, arming the secret scanner in an unidentified room B bound it to <b>A</b>, and
     * every secret clicked in B was filed under A's entry with B-relative coordinates. That is how a
     * 1x1 room ended up with waypoints at relative 57/60 (a puzzle room's secrets written into "Big
     * Red Flag"). Refusing the stale name makes the scanner say "scan the room first" instead.
     */
    static String activeRoomNameAt(BlockPos anchor) {
        return anchor != null && anchor.equals(activeRoomAnchor) ? activeRoomName : null;
    }

    /**
     * Scan the room the player is standing in. Opens the scan dialog (name + optional room-size
     * verification + the secret-scan toggle), then scans on confirm. When a size is entered and it
     * does not match the detected footprint, <b>nothing is saved</b> – the scan needs rework.
     */
    public static void beginScan() {
        if (!DevMode.ACTIVE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        DungeonRoomTracker tracker = DungeonRoomTracker.getInstance();
        BlockPos anchor = tracker.anchor();
        DungeonRoomBorders.Borders borders = tracker.borders();
        if (anchor == null || borders == null) {
            overlay(player, "§cNo room lock — stand inside a room (yellow border box must be visible)");
            return;
        }
        RoomMapReader.RoomInfo info = RoomMapReader.read();
        boolean onDungeonGrid = tracker.locatedFromMap();

        minecraft.execute(() -> minecraft.setScreenAndShow(new NameInputScreen(
                Component.literal("Scan Room"), "Room name...", "Room size to verify (e.g. 1x4) - optional",
                (name, sizeInput) -> {
                    String detected = borders.shape();
                    boolean verify = sizeInput != null && !sizeInput.isBlank();
                    if (verify && !sizeMatches(sizeInput, detected)) {
                        overlay(player, "§cScan needs rework — detected " + detected + ", expected "
                                + sizeInput.trim() + " (nothing saved)");
                        return;
                    }
                    List<RoomScanner.ScannedBlock> blocks =
                            RoomScanner.scan(anchor, CANONICAL_FACING, borders, onDungeonGrid);
                    WaypointExporter.saveRoom(name, info, CANONICAL_FACING, detected, blocks);
                    activeRoomName = name;
                    activeRoomInfo = info;
                    activeRoomAnchor = anchor;
                    overlay(player, "§aScanned '" + name + "' — " + blocks.size() + " blocks (" + detected
                            + ", anchor " + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ() + ")"
                            + (verify ? " §a— scan match room size" : ""));
                })));
    }

    /**
     * Whether an entered room size matches the detected one. {@code AxB} inputs are compared
     * orientation-free ({@code 1x4} equals {@code 4x1} – the entered size does not know the room's
     * world rotation); anything else (e.g. {@code L (3 cells)}) compares case-insensitively.
     */
    static boolean sizeMatches(String entered, String detected) {
        int[] a = parseSize(entered);
        int[] b = parseSize(detected);
        if (a != null && b != null) {
            return a[0] == b[0] && a[1] == b[1];
        }
        return entered.trim().equalsIgnoreCase(detected.trim());
    }

    /** Parses "AxB" into sorted {min,max} dims, or {@code null} when the text has no AxB. */
    private static int[] parseSize(String text) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(\\d+)\\s*[xX]\\s*(\\d+)").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        int p = Integer.parseInt(matcher.group(1));
        int q = Integer.parseInt(matcher.group(2));
        return new int[] {Math.min(p, q), Math.max(p, q)};
    }

    /** Create a waypoint from the current player position ("standing"). */
    public static void beginStandingWaypoint() {
        beginWaypoint(WaypointExporter.WaypointType.STANDING);
    }

    /** Create a waypoint from the block in the crosshair ("looking"). */
    public static void beginLookingWaypoint() {
        beginWaypoint(WaypointExporter.WaypointType.LOOKING);
    }

    private static void beginWaypoint(WaypointExporter.WaypointType type) {
        if (!DevMode.ACTIVE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        DungeonRoomTracker tracker = DungeonRoomTracker.getInstance();
        BlockPos anchor = tracker.anchor();
        DungeonRoomBorders.Borders borders = tracker.borders();
        if (anchor == null || borders == null) {
            overlay(player, "§cNo room lock — stand inside a room (yellow border box must be visible)");
            return;
        }
        // Waypoints can be added to freshly scanned rooms AND to rooms the database already knows: a
        // live match supplies both the room name and the exact canonical frame its entry was scanned
        // in, so appended waypoints line up with the existing data without a re-scan.
        Direction facing = CANONICAL_FACING;
        // Anchor-checked: a name from a room the player has since left must never claim this waypoint.
        String roomName = activeRoomNameAt(anchor);
        sbs.modid.client.dungeons.run.logic.DungeonRoomMatcher.RoomMatch match = tracker.activeMatch();
        if (match != null) {
            roomName = match.name();
            facing = match.facing();
            anchor = match.anchor();
        }
        if (roomName == null) {
            overlay(player, "§cScan the room first (it is not in the database yet)");
            return;
        }
        BlockPos target;
        if (type == WaypointExporter.WaypointType.LOOKING) {
            HitResult hit = minecraft.hitResult;
            if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
                overlay(player, "§cLook at a block first");
                return;
            }
            target = blockHit.getBlockPos();
        } else {
            target = player.blockPosition();
        }

        // The waypoint must lie inside the void-detected room borders – coordinates from a neighbouring
        // room (or from inside a doorway gap) are rejected outright.
        if (!borders.contains(target)) {
            overlay(player, "§cWaypoint outside the room borders (" + borders.shape() + ")");
            return;
        }

        BlockPos relative = RoomRotation.actualToRelative(facing, anchor, target);
        final String targetRoom = roomName;
        final Direction frameFacing = facing;
        openNameGui(Component.literal(type == WaypointExporter.WaypointType.LOOKING ? "Looking Waypoint" : "Standing Waypoint"),
                "Waypoint name...", name -> {
                    // Read the CURRENT room's colour/size, never the cached one: it only fills a
                    // newly created entry, and the last scan's info belongs to a different room.
                    WaypointExporter.saveWaypoint(targetRoom, RoomMapReader.read(), frameFacing,
                            borders.shape(), name, type, relative);
                    overlay(player, "§aSaved waypoint '" + name + "' in '" + targetRoom + "' (rel "
                            + relative.getX() + "," + relative.getY() + "," + relative.getZ() + ")");
                });
    }

    private static void openNameGui(Component prompt, String hint, Consumer<String> onSubmit) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreenAndShow(new NameInputScreen(prompt, hint, onSubmit)));
    }

    private static void overlay(LocalPlayer player, String message) {
        player.sendOverlayMessage(Component.literal(message));
    }
}
