/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * Dispatches the two waypoint keybinds and the room-scan keybind while dev mode is active.
 *
 * <p>Called from {@code CommandKeyMixin} on every fresh key press that happens in-world with no screen
 * open (the same gate the command keybinds use). All three keys are read from the persisted
 * {@link SBSConfig.DevSettings}, so they are configurable; they do nothing at all unless
 * {@link DevMode#ACTIVE} is {@code true}.
 */
public final class DevKeybinds {

    private DevKeybinds() {
    }

    /** Set once the duplicate-binding audit has run, so it costs one boolean per press after that. */
    private static boolean audited;

    public static void onKeyPressed(int keyCode) {
        if (!DevMode.ACTIVE) {
            return;
        }
        SBSConfig.DevSettings dev = ConfigManager.getInstance().get().dev;
        if (dev == null) {
            return;
        }
        auditOnce(dev);
        if (keyCode == dev.scanRoomKey) {
            DevActions.beginScan();
        } else if (keyCode == dev.waypointStandingKey) {
            DevActions.beginStandingWaypoint();
        } else if (keyCode == dev.waypointLookingKey) {
            DevActions.beginLookingWaypoint();
        } else if (keyCode == dev.scanSecretsKey) {
            // Standalone secret recorder: auto-binds to the room you are standing in (needs a name,
            // i.e. a scanned or database-known room). Separate from Scan Room on purpose.
            SecretScanner.getInstance().toggle();
        } else if (keyCode == dev.saveItemSecretKey) {
            // One-shot: the block at your legs is an item secret; auto-assigned to the current room.
            SecretScanner.getInstance().saveItemSecretAtLegs();
        } else if (keyCode == dev.questObjectiveKey) {
            QuestCapture.getInstance().markObjective();
        } else if (keyCode == dev.questMenuKey) {
            QuestCapture.getInstance().captureMenu();
        } else if (keyCode == dev.questStateKey) {
            QuestCapture.getInstance().captureState();
        }
        // Pathfinding module (in testing): drop / remove a routing waypoint. Both unbound by
        // default, and separate from the dungeon room waypoints above.
        SBSConfig.PathfindingSettings pathfinding = ConfigManager.getInstance().get().pathfinding;
        auditPathfinding(dev, pathfinding);
        if (pathfinding != null) {
            if (pathfinding.addWaypointKey != 0 && keyCode == pathfinding.addWaypointKey) {
                sbs.modid.client.core.pathfinding.WaypointStore.addAtPlayer();
            } else if (pathfinding.removeWaypointKey != 0 && keyCode == pathfinding.removeWaypointKey) {
                sbs.modid.client.core.pathfinding.WaypointStore.removeNearest();
            }
        }
    }

    /**
     * Names any two dev keys bound to the same code, once.
     *
     * <p>The dispatch above is an {@code else if} chain, so a duplicate is not a conflict the player
     * resolves - the later branch simply never runs, silently. That shipped once: the quest-capture
     * state key was left on 327, which the standing-waypoint key already owned, and the capture it
     * drove could not fire at all. Nothing pointed at it, because an unreachable branch looks exactly
     * like a key nobody pressed.
     */
    private static void auditOnce(SBSConfig.DevSettings dev) {
        if (audited) {
            return;
        }
        audited = true;
        java.util.Map<Integer, String> seen = new java.util.LinkedHashMap<>();
        record Binding(String name, int code) {
        }
        for (Binding binding : new Binding[] {
                new Binding("scanRoomKey", dev.scanRoomKey),
                new Binding("waypointStandingKey", dev.waypointStandingKey),
                new Binding("waypointLookingKey", dev.waypointLookingKey),
                new Binding("scanSecretsKey", dev.scanSecretsKey),
                new Binding("saveItemSecretKey", dev.saveItemSecretKey),
                new Binding("questObjectiveKey", dev.questObjectiveKey),
                new Binding("questMenuKey", dev.questMenuKey),
                new Binding("questStateKey", dev.questStateKey)}) {
            if (binding.code() == 0) {
                continue;   // unbound
            }
            String previous = seen.putIfAbsent(binding.code(), binding.name());
            if (previous != null) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][Dev] '{}' and '{}' are both bound to key {} - only '{}' will ever fire, "
                                + "because the dispatch is an else-if chain.",
                        previous, binding.name(), binding.code(), previous);
            }
        }
    }

    /** The pathfinding keys share the same dispatch, so they share the same failure. */
    private static void auditPathfinding(SBSConfig.DevSettings dev,
                                         SBSConfig.PathfindingSettings pathfinding) {
        if (pathfinding == null) {
            return;
        }
        for (int code : new int[] {pathfinding.addWaypointKey, pathfinding.removeWaypointKey}) {
            if (code == 0) {
                continue;
            }
            if (code == dev.questObjectiveKey || code == dev.questMenuKey
                    || code == dev.questStateKey || code == dev.scanRoomKey) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][Dev] A pathfinding key shares code {} with a dev key; both will fire.",
                        code);
            }
        }
    }
}
