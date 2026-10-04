/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.ui.hud.logic.HypixelHudState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one record of which dungeon secrets are collected this run, per room - what both waypoint
 * renderers (room waypoints and Secret Routes) hide by.
 *
 * <p><b>One signal.</b> Whatever notices a collection - a powered lever, a chest opened at its block,
 * or a secret counter rising while the player stands near a secret - calls
 * {@link DungeonEvents#fireSecretFound}. This class is a listener on that event and nothing else writes
 * it, so a secret one system detected is hidden by the other too, and re-entering a room keeps its
 * secrets hidden (the old Secret Routes memory was cleared on every room change).
 *
 * <p><b>Positions match loosely.</b> The two systems put the same secret on different blocks: the room
 * database marks the chest itself, a hand-placed route point the block under the player's feet beside
 * it. A record therefore covers every point within {@link #MATCH_RADIUS_SQR}.
 *
 * <p><b>The counter path</b> ({@link #tick}) covers what no block shows: an item, a bat, an essence.
 * Both counters are fused by {@link SecretCounterFusion} so one pickup hides exactly one secret - the
 * nearest uncollected candidate within {@link #PICKUP_RADIUS_SQR} of the player. Candidates come from
 * every registered {@link #addCandidateSource source}, so the dependency runs one way (route code
 * registers here; this package never imports it).
 */
public final class CollectedSecrets implements DungeonEvents.Listener {

    private static final CollectedSecrets INSTANCE = new CollectedSecrets();

    /** Two points this close (squared blocks) are the same secret - chest block vs feet block beside it. */
    static final int MATCH_RADIUS_SQR = 3;

    /** A counter rise is attributed to a secret no further than this (squared blocks) from the player. */
    static final double PICKUP_RADIUS_SQR = 30.0;

    /** The room's action-bar counter: "3/7 Secrets". */
    private static final Pattern BAR_SECRETS = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)\\s+Secrets");

    /** Key used when the event fires with no identified room. */
    private static final String NO_ROOM = "";

    private final Map<String, List<BlockPos>> byRoom = new HashMap<>();
    private final SecretCounterFusion counters = new SecretCounterFusion();
    private final List<Supplier<List<BlockPos>>> candidateSources = new CopyOnWriteArrayList<>();
    private long ticks;

    private CollectedSecrets() {
    }

    public static CollectedSecrets getInstance() {
        return INSTANCE;
    }

    /** Registers the listener; call once at client init. */
    public static void init() {
        DungeonEvents.register(INSTANCE);
    }

    /**
     * Adds a supplier of the current room's still-uncollected secret positions, offered to the
     * counter path. Each renderer registers its own so a pickup can land on either one's point.
     */
    public void addCandidateSource(Supplier<List<BlockPos>> source) {
        candidateSources.add(source);
    }

    // ------------------------------------------------------------------ store

    @Override
    public void onSecretFound(DungeonEvents.Secret secret) {
        if (secret == null || secret.world() == null) {
            return;
        }
        if (!record(currentRoom(), secret.world())) {
            return; // the other system (or a counter) already reported this one
        }
        if (!"counter".equals(secret.kind())) {
            counters.noteDirect(ticks); // the counters will rise for this one too
        }
        if (ConfigManager.getInstance().get().dungeons.secretClickedFeedback) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.8f);
                player.sendOverlayMessage(Component.literal("§aSecret collected §7(" + secret.kind() + ")"));
            }
        }
    }

    /** Records a collected secret; {@code false} when it was already recorded (or one beside it). */
    public boolean record(String room, BlockPos world) {
        if (isCollected(room, world)) {
            return false;
        }
        byRoom.computeIfAbsent(key(room), k -> new ArrayList<>()).add(world.immutable());
        return true;
    }

    /** Whether the secret at (or right beside) {@code world} in {@code room} is collected this run. */
    public boolean isCollected(String room, BlockPos world) {
        List<BlockPos> found = byRoom.get(key(room));
        if (found == null || world == null) {
            return false;
        }
        for (BlockPos pos : found) {
            if (pos.distSqr(world) <= MATCH_RADIUS_SQR) {
                return true;
            }
        }
        return false;
    }

    /** New run (or left the dungeon): nothing is collected. */
    public void clear() {
        byRoom.clear();
        counters.reset();
    }

    // ------------------------------------------------------------------ counter path

    /** Per client tick, after the room tracker and the state manager have read this tick's state. */
    public void tick(Minecraft minecraft) {
        ticks++;
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        String room = currentRoom();
        int tab = DungeonStateManager.getInstance().secretsFound();
        int bar = barFound(HypixelHudState.getInstance().lastActionBar());
        int fresh = counters.onTick(ticks, tab, bar, room);
        for (int i = 0; i < fresh && room != null; i++) {
            List<BlockPos> candidates = new ArrayList<>();
            for (Supplier<List<BlockPos>> source : candidateSources) {
                candidates.addAll(source.get());
            }
            BlockPos picked = nearestUncollected(room, player.position(), candidates);
            if (picked == null) {
                break; // nothing near enough to be what was picked up - better hide none than a wrong one
            }
            DungeonEvents.fireSecretFound(new DungeonEvents.Secret("counter", picked));
        }
    }

    /** The nearest candidate not yet collected, within {@link #PICKUP_RADIUS_SQR}; {@code null} if none. */
    BlockPos nearestUncollected(String room, Vec3 player, List<BlockPos> candidates) {
        BlockPos best = null;
        double bestDistance = PICKUP_RADIUS_SQR;
        for (BlockPos pos : candidates) {
            if (isCollected(room, pos)) {
                continue;
            }
            double distance = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    /** The found part of the action bar's "x/y Secrets", or {@code -1} when it shows none. */
    static int barFound(String actionBar) {
        if (actionBar == null) {
            return -1;
        }
        Matcher matcher = BAR_SECRETS.matcher(actionBar);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String currentRoom() {
        return DungeonRoomTracker.getInstance().activeRoomName();
    }

    private static String key(String room) {
        return room != null ? room : NO_ROOM;
    }
}
