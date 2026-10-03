/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import sbs.modid.client.core.dev.NameInputScreen;
import sbs.modid.client.core.dev.RoomRotation;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomMatcher.RoomMatch;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;
import sbs.modid.client.dungeons.run.logic.DungeonRouteStore;

/**
 * The route-waypoint hotkeys, available to <b>every</b> player (no dev mode): pressing one opens the
 * SBS name dialog (text field + Confirm / Cancel) and then saves a waypoint for the room the player is
 * currently standing in – "standing" at the player's feet or "looking" at the crosshair block.
 *
 * <p>Requires the room to be <b>identified</b> (matched against the bundled database), because the
 * waypoint is stored relative-only in the room's canonical frame ({@link RoomRotation} with the match's
 * facing + anchor) – exactly the frame the database uses, so route waypoints resolve and render through
 * the normal room match like any database waypoint. Persisted via {@link DungeonRouteStore}.
 */
public final class RouteActions {

    private RouteActions() {
    }

    /** Route waypoint at the player's feet. */
    public static void beginStanding() {
        begin("standing", false);
    }

    /** Route waypoint at the block in the crosshair. */
    public static void beginLooking() {
        begin("looking", true);
    }

    private static void begin(String type, boolean looking) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        DungeonRoomTracker tracker = DungeonRoomTracker.getInstance();
        RoomMatch match = tracker.activeMatch();
        DungeonRoomBorders.Borders borders = tracker.borders();
        if (match == null || borders == null) {
            overlay(player, "§cRoom not identified yet — route waypoints need a recognized room");
            return;
        }
        BlockPos target;
        if (looking) {
            HitResult hit = minecraft.hitResult;
            if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
                overlay(player, "§cLook at a block first");
                return;
            }
            target = blockHit.getBlockPos();
        } else {
            target = player.blockPosition();
        }
        if (!borders.contains(target)) {
            overlay(player, "§cWaypoint outside the room borders");
            return;
        }

        // Canonical frame of the matched room = the frame its database entry was scanned in.
        BlockPos relative = RoomRotation.actualToRelative(match.facing(), match.anchor(), target);
        String roomName = match.name();
        minecraft.execute(() -> minecraft.setScreenAndShow(new NameInputScreen(
                Component.literal(looking ? "Route Waypoint (Looking)" : "Route Waypoint (Standing)"),
                "Waypoint name...", name -> {
                    DungeonRouteStore.add(roomName, name, type,
                            relative.getX(), relative.getY(), relative.getZ());
                    tracker.refreshWaypoints();
                    overlay(player, "§aRoute waypoint '" + name + "' saved in '" + roomName + "' (rel "
                            + relative.getX() + "," + relative.getY() + "," + relative.getZ() + ")");
                })));
    }

    private static void overlay(LocalPlayer player, String message) {
        player.sendOverlayMessage(Component.literal(message));
    }
}
