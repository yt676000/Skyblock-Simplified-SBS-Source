/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import sbs.modid.client.dungeons.run.logic.DungeonDebug;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomMatcher;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;
import sbs.modid.client.dungeons.rooms.DungeonRoom;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Dev-mode <b>secret recorder</b>: while armed (from the Scan-Room dialog's start/stop toggle), every
 * secret the developer triggers inside the bound room is saved as a waypoint of that room
 * automatically – no name dialog per secret:
 * <ul>
 *   <li><b>chests / levers / essence heads</b> – recorded the moment they are right-clicked
 *       (fed by {@code BlockInteractMixin});</li>
 *   <li><b>secret bats</b> – every bat that appears inside the room is tracked from its first-seen
 *       position (≈ its spawn); when it dies after taking a hit, a waypoint is saved <b>at the spawn
 *       position</b>, which is where the secret lives.</li>
 * </ul>
 *
 * <p>Waypoints are auto-named ({@code Chest 1}, {@code Lever 2}, {@code Bat 1}, {@code Essence 1} …,
 * continuing after names that already exist in the export) and stored in the same door-relative,
 * rotation-normalised frame as every other dev waypoint. The binding (room name, anchor, facing,
 * borders) is captured at start time from the live room lock: a database match supplies its exact
 * canonical frame, a freshly scanned room uses the canonical NORTH frame – identical to
 * {@link DevActions}' manual waypoint flow. Clicks outside the bound borders are ignored, so walking
 * through a doorway can never pollute the room.
 */
public final class SecretScanner {

    private static final SecretScanner INSTANCE = new SecretScanner();

    /** One tracked bat: where it first appeared (≈ its spawn) and whether it ever took a hit. */
    private static final class BatTrack {
        final BlockPos spawn;
        boolean hurt;

        BatTrack(BlockPos spawn) {
            this.spawn = spawn;
        }
    }

    /** A resolved room binding: the name to save under and the canonical frame to save in. */
    private record RoomBinding(String roomName, Direction facing, BlockPos anchor,
                               DungeonRoomBorders.Borders borders) {
    }

    private boolean active;
    private String roomName;
    private Direction facing;
    private BlockPos anchor;
    private DungeonRoomBorders.Borders borders;
    private final Set<BlockPos> savedPositions = new HashSet<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Map<Integer, BatTrack> bats = new HashMap<>();
    private int savedCount;

    private SecretScanner() {
    }

    public static SecretScanner getInstance() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active;
    }

    /** Flips the scan on/off (the Scan-Room dialog's toggle button). */
    public void toggle() {
        if (active) {
            stop();
        } else {
            start();
        }
    }

    /**
     * Arms the scanner on the room the player is standing in. Requires a live room lock plus a room
     * name (database match, a dev-scanned room from {@code Waypoints.json}, or a scan this session) –
     * otherwise it explains what is missing and stays off.
     */
    public void start() {
        if (!DevMode.ACTIVE) {
            return;
        }
        RoomBinding binding = resolveBinding();
        if (binding == null) {
            overlay("§cSecret scan: no named room lock — scan the room first (§7then it is recognised)");
            return;
        }
        roomName = binding.roomName();
        facing = binding.facing();
        anchor = binding.anchor();
        borders = binding.borders();
        savedPositions.clear();
        bats.clear();
        savedCount = 0;
        usedNames.clear();
        usedNames.addAll(WaypointExporter.waypointNames(roomName));
        active = true;
        DungeonDebug.chat("§aSecret scan STARTED for '§b" + roomName
                + "§a' — click chests / levers / essence heads, kill secret bats");
    }

    /**
     * Resolves the room the player is currently standing in and the canonical frame to save under:
     * <ol>
     *   <li>a bundled-database match ({@link DungeonRoomTracker#activeMatch()});</li>
     *   <li>else a match against the dev {@code Waypoints.json} rooms – so a room scanned in an
     *       earlier session is recognised across restarts (the config is pulled to identify it);</li>
     *   <li>else the room scanned <b>this session</b> ({@link DevActions#activeRoomNameAt(BlockPos)}),
     *       in the canonical NORTH frame at the tracker anchor – only when that scan was made at this
     *       very room's anchor, so a name never travels into the next room.</li>
     * </ol>
     * {@code null} when there is no room lock, or none of the three yields a name.
     */
    private RoomBinding resolveBinding() {
        DungeonRoomTracker tracker = DungeonRoomTracker.getInstance();
        BlockPos trackerAnchor = tracker.anchor();
        DungeonRoomBorders.Borders trackerBorders = tracker.borders();
        if (trackerAnchor == null || trackerBorders == null) {
            return null;
        }
        DungeonRoomMatcher.RoomMatch match = tracker.activeMatch();
        if (match != null) {
            return new RoomBinding(match.name(), match.facing(), match.anchor(), trackerBorders);
        }
        DungeonRoomMatcher.RoomMatch devMatch = matchDevRoom(trackerBorders);
        if (devMatch != null) {
            return new RoomBinding(devMatch.name(), devMatch.facing(), devMatch.anchor(), trackerBorders);
        }
        // Anchor-checked: only a scan of THIS room may lend its name. Unconditionally, the last
        // scanned name armed the scanner in whatever room the player had walked into next, filing its
        // secrets under the previous room (see DevActions#activeRoomNameAt).
        String name = DevActions.activeRoomNameAt(trackerAnchor);
        if (name != null) {
            return new RoomBinding(name, Direction.NORTH, trackerAnchor, trackerBorders);
        }
        return null;
    }

    /** Matches the current footprint against the dev {@code Waypoints.json} rooms (signature blocks). */
    private static DungeonRoomMatcher.RoomMatch matchDevRoom(DungeonRoomBorders.Borders borders) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        Map<String, DungeonRoom> devRooms = WaypointExporter.loadAsDatabase();
        if (devRooms.isEmpty()) {
            return null;
        }
        DungeonRoomMatcher.BlockLookup lookup = pos ->
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        return DungeonRoomMatcher.identify(lookup, borders, devRooms);
    }

    // ------------------------------------------------------------------ item secret (one-shot)

    /**
     * Saves the block the player's <b>legs</b> occupy ({@code player.blockPosition()} – one above the
     * block being stood on) as an <b>item</b> secret of the room the player is standing in. A one-shot
     * action (its own dev keybind), independent of whether the running scan is armed: it resolves the
     * current room the same way {@link #resolveBinding()} does, auto-names {@code Item N}, and saves in
     * the room's canonical frame.
     */
    public void saveItemSecretAtLegs() {
        if (!DevMode.ACTIVE) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        RoomBinding binding = resolveBinding();
        if (binding == null) {
            overlay("§cItem secret: no named room — scan the room first");
            return;
        }
        BlockPos legs = player.blockPosition();   // block at the legs = one above the block stood on
        if (!binding.borders().contains(legs)) {
            overlay("§cItem secret: you are outside the room borders");
            return;
        }
        String name = nextItemName(binding.roomName());
        BlockPos relative = RoomRotation.actualToRelative(binding.facing(), binding.anchor(), legs);
        WaypointExporter.saveWaypoint(binding.roomName(), RoomMapReader.read(), binding.facing(),
                binding.borders().shape(), name, WaypointExporter.WaypointType.ITEM, relative);
        // Keep the armed scan's name set in sync so a following auto-save never reuses the number.
        usedNames.add(name);
        DungeonDebug.chat("§aItem secret saved: §b" + name + " §7in '§b" + binding.roomName()
                + "§7' (rel " + relative.getX() + "," + relative.getY() + "," + relative.getZ() + ")");
    }

    /** The lowest free "Item N" name, read fresh from the export (persisted after every save). */
    private static String nextItemName(String roomName) {
        Set<String> used = WaypointExporter.waypointNames(roomName);
        for (int i = 1; ; i++) {
            String candidate = "Item " + i;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** Stops the scan and reports how many secrets were recorded. */
    public void stop() {
        if (active) {
            DungeonDebug.chat("§7Secret scan stopped — §b" + savedCount + "§7 secret(s) saved in '§b"
                    + roomName + "§7'");
        }
        active = false;
        bats.clear();
    }

    // ------------------------------------------------------------------ block clicks

    /** Every right-clicked block (from {@code BlockInteractMixin}); saves the click-type secrets. */
    public void onBlockClicked(BlockPos pos) {
        if (!active || !DevMode.ACTIVE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || borders == null || !borders.contains(pos)) {
            return;
        }
        Block block = minecraft.level.getBlockState(pos).getBlock();
        String prefix;
        WaypointExporter.WaypointType type;
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            prefix = "Chest";
            type = WaypointExporter.WaypointType.CHEST;
        } else if (block == Blocks.LEVER) {
            prefix = "Lever";
            type = WaypointExporter.WaypointType.LEVER;
        } else if (isEssenceHead(block)) {
            prefix = "Essence";
            type = WaypointExporter.WaypointType.ESSENCE;
        } else {
            return;
        }
        if (!savedPositions.add(pos.immutable())) {
            return;   // this exact block is already recorded
        }
        save(prefix, type, pos);
    }

    /** Wither / undead essences are placed heads (player heads with custom textures, or skulls). */
    private static boolean isEssenceHead(Block block) {
        return block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD
                || block == Blocks.SKELETON_SKULL || block == Blocks.SKELETON_WALL_SKULL
                || block == Blocks.WITHER_SKELETON_SKULL || block == Blocks.WITHER_SKELETON_WALL_SKULL;
    }

    // ------------------------------------------------------------------ bats (client tick)

    /** Called once per client tick: tracks bat spawns in the room and saves killed ones. */
    public void tick(Minecraft minecraft) {
        if (!active || !DevMode.ACTIVE) {
            return;
        }
        ClientLevel level = minecraft.level;
        if (level == null) {
            stop();   // left the world - the binding is gone
            return;
        }
        // New bats inside the room start a track at their first-seen position (≈ the spawn, since
        // this runs every tick); already-tracked bats just update their "was hurt" flag.
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof Bat bat) || !bat.isAlive()) {
                continue;
            }
            BatTrack track = bats.get(bat.getId());
            if (track == null) {
                BlockPos spawn = bat.blockPosition();
                if (borders != null && borders.contains(spawn)) {
                    bats.put(bat.getId(), new BatTrack(spawn));
                }
            } else if (bat.hurtTime > 0 || bat.isDeadOrDying()) {
                track.hurt = true;
            }
        }
        // A tracked bat that is gone counts as the secret if it took a hit first (killed, not
        // despawned); the waypoint goes to its SPAWN position - that is where the secret sits.
        Iterator<Map.Entry<Integer, BatTrack>> iterator = bats.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, BatTrack> entry = iterator.next();
            Entity entity = level.getEntity(entry.getKey());
            if (entity != null && entity.isAlive()) {
                continue;
            }
            iterator.remove();
            BatTrack track = entry.getValue();
            if (track.hurt && savedPositions.add(track.spawn)) {
                save("Bat", WaypointExporter.WaypointType.BAT, track.spawn);
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    private void save(String prefix, WaypointExporter.WaypointType type, BlockPos world) {
        String name = nextName(prefix);
        BlockPos relative = RoomRotation.actualToRelative(facing, anchor, world);
        WaypointExporter.saveWaypoint(roomName, RoomMapReader.read(), facing, borders.shape(),
                name, type, relative);
        savedCount++;
        DungeonDebug.chat("§aSecret saved: §b" + name + " §7(rel " + relative.getX() + ","
                + relative.getY() + "," + relative.getZ() + ")");
    }

    /** The lowest free "{prefix} N" name, skipping names already present in the export. */
    private String nextName(String prefix) {
        for (int i = 1; ; i++) {
            String candidate = prefix + " " + i;
            if (usedNames.add(candidate)) {
                return candidate;
            }
        }
    }

    private static void overlay(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendOverlayMessage(Component.literal(message));
        }
        DungeonDebug.chat(message);
    }
}
