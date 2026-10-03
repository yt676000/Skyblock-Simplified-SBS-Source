/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.logic;

import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.location.hollows.HollowsDetector;
import sbs.modid.client.core.location.hollows.StructureFix;
import sbs.modid.client.helper.map.model.HollowsLobbyMap;
import sbs.modid.client.helper.map.model.HollowsMarker;
import sbs.modid.client.helper.map.model.KnownStructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs the Crystal Hollows map: samples the trail, follows the lobby {@link HollowsDetector}
 * reports, and keeps {@code config/sbs/Accounts/<account>/ch_map.json} in step.
 *
 * <p><b>Only the player's own position is sampled</b>, every {@link #SAMPLE_TICKS} ticks, and only on
 * the Hollows. Nothing about other players, mobs or blocks is read.
 *
 * <p><b>Saving</b> builds the JSON text on the client thread and writes it on
 * {@link SbsExecutors#io()}: every {@link #SAVE_MS} while something changed, on a lobby change and on
 * leaving the island. There is no shutdown hook, so the last half minute before quitting the game
 * can be lost - noted in the spec.
 */
public final class HollowsMapTracker implements HollowsDetector.Listener {

    private static final HollowsMapTracker INSTANCE = new HollowsMapTracker();

    /** Trail sampling interval: 10 ticks, half a second. */
    static final int SAMPLE_TICKS = 10;

    private static final long SAVE_MS = 30_000L;

    private static final String FILE = "ch_map.json";

    private HollowsMapStore store = new HollowsMapStore();

    /** The account {@link #store} belongs to; {@code null} until one has been loaded. */
    private String account;

    private int ticks;
    private boolean dirty;
    private long lastSaveAt;

    private HollowsMapTracker() {
        HollowsDetector.getInstance().addListener(this);
    }

    public static HollowsMapTracker getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().map.enabled;
    }

    public HollowsMapStore store() {
        return store;
    }

    /** The live lobby's map, or {@code null} off the Hollows. */
    public HollowsLobbyMap current() {
        return store.current();
    }

    /** Every structure the map can show for the live lobby. */
    public List<KnownStructure> known() {
        return HollowsMapStore.known(store.current(), HollowsDetector.getInstance().snapshot());
    }

    /** Called from the shared client-tick list. Off the Hollows this is a config read and a counter. */
    public void onClientTick() {
        if (++ticks < SAMPLE_TICKS) {
            return;
        }
        ticks = 0;
        long now = System.currentTimeMillis();
        followAccount();
        HollowsDetector detector = HollowsDetector.getInstance();
        if (!enabled() || !detector.onHollows()) {
            if (store.current() != null) {
                store.leave();
                HollowsTarget.getInstance().clear();
                save(now);
            }
            return;
        }
        String lobby = detector.lobby();
        HollowsLobbyMap before = store.current();
        HollowsLobbyMap map = store.switchTo(lobby, now);
        if (before != map) {
            if (before != null && !before.lobby().isEmpty()) {
                // A different lobby is a different cave system: a target from the last one points
                // at nothing here.
                HollowsTarget.getInstance().clear();
            }
            detector.restore(lobby, map.storedFixes());
            dirty = true;
        }
        Player player = Minecraft.getInstance().player;
        if (player != null && map.trail().sample(player.getBlockX(), player.getBlockY(), player.getBlockZ())) {
            dirty = true;
        }
        if (dirty && now - lastSaveAt >= SAVE_MS) {
            save(now);
        }
    }

    @Override
    public void lobbyClosing(String lobby, List<StructureFix> fixes) {
        long now = System.currentTimeMillis();
        store.storeOwn(lobby, fixes, now);
        dirty = true;
        save(now);
    }

    @Override
    public void lobbyOpened(String lobby) {
        // The tick reconciles with detector.lobby(); nothing to do on the event itself.
    }

    // ------------------------------------------------------------------ markers

    /** {@code /sbs chmap mark <label>}. Returns the line to show the player. */
    public String mark(String label) {
        HollowsLobbyMap map = store.current();
        Player player = Minecraft.getInstance().player;
        if (map == null || player == null) {
            return "Markers can only be placed on the Crystal Hollows.";
        }
        if (label == null || label.isBlank()) {
            return "Give the marker a name: /sbs chmap mark <label>";
        }
        if (map.markers().size() >= HollowsLobbyMap.MAX_MARKERS) {
            return "This lobby already has " + HollowsLobbyMap.MAX_MARKERS
                    + " markers - remove one with /sbs chmap unmark <label>.";
        }
        map.addMarker(label, player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                System.currentTimeMillis());
        dirty = true;
        HollowsMarker added = map.markers().getLast();
        return "Marked '" + added.label() + "' at " + added.x() + ", " + added.y() + ", " + added.z()
                + " (" + map.markers().size() + "/" + HollowsLobbyMap.MAX_MARKERS + ", only on this PC).";
    }

    /** {@code /sbs chmap unmark [label]}: by label, or the nearest one without. */
    public String unmark(String label) {
        HollowsLobbyMap map = store.current();
        Player player = Minecraft.getInstance().player;
        if (map == null || player == null) {
            return "Markers can only be removed on the Crystal Hollows.";
        }
        if (label == null || label.isBlank()) {
            HollowsMarker removed = map.removeNearest(player.getBlockX(), player.getBlockY(), player.getBlockZ());
            if (removed == null) {
                return "There are no markers in this lobby.";
            }
            dirty = true;
            clearTargetIfMarker(removed);
            return "Removed '" + removed.label() + "'.";
        }
        List<HollowsMarker> matching = map.markers().stream()
                .filter(marker -> marker.label().equalsIgnoreCase(label.strip())).toList();
        int count = map.removeMarkers(label);
        if (count == 0) {
            return "No marker called '" + label.strip() + "' in this lobby.";
        }
        dirty = true;
        matching.forEach(this::clearTargetIfMarker);
        return "Removed " + count + " marker(s) called '" + label.strip() + "'.";
    }

    /** The target key a marker is known by - shared with the map screen so a click can toggle it. */
    public static String markerKey(HollowsMarker marker) {
        return "M:" + marker.label() + "@" + marker.x() + "," + marker.y() + "," + marker.z();
    }

    private void clearTargetIfMarker(HollowsMarker marker) {
        if (HollowsTarget.getInstance().is(markerKey(marker))) {
            HollowsTarget.getInstance().clear();
        }
    }

    // ------------------------------------------------------------------ file

    private static Path file(String account) {
        return SBSFiles.accountsDir().resolve(account).resolve(FILE);
    }

    /**
     * Loads the account's file on first use and whenever the logged-in account changes. The read is a
     * small file on the IO thread; the result is merged on the client thread, so anything recorded
     * while it was in flight is kept.
     */
    private void followAccount() {
        String now = ProfileContext.getInstance().account();
        if (now == null || now.isEmpty() || "default".equals(now) || now.equals(account)) {
            return;
        }
        if (account != null) {
            save(System.currentTimeMillis());
            store = new HollowsMapStore();
        }
        account = now;
        String loadingFor = now;
        Path path = file(now);
        SbsExecutors.compute(() -> path, HollowsMapTracker::read, loaded -> {
            if (loaded != null && loadingFor.equals(account)) {
                store.absorb(loaded);
                HollowsLobbyMap current = store.current();
                if (current != null) {
                    HollowsDetector.getInstance().restore(current.lobby(), current.storedFixes());
                }
            }
        }, Minecraft.getInstance());
    }

    private static HollowsMapStore read(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            HollowsMapStore loaded = HollowsMapStore.fromJson(
                    Files.readString(path, StandardCharsets.UTF_8), System.currentTimeMillis());
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hollows] loaded {} lobby map(s) from {}",
                    loaded.lobbyCount(), path.getFileName());
            return loaded;
        } catch (IOException | JsonParseException e) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hollows] could not read {}, starting empty: {}",
                    path, e.toString());
            return null;
        }
    }

    private void save(long now) {
        dirty = false;
        lastSaveAt = now;
        if (account == null) {
            return;
        }
        String json = store.toJson(now, HollowsDetector.getInstance().snapshot());
        Path path = file(account);
        SbsExecutors.io().execute(() -> {
            try {
                SBSFiles.ensureParent(path);
                Files.writeString(path, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Hollows] could not save {}", path, e);
            }
        });
    }
}
