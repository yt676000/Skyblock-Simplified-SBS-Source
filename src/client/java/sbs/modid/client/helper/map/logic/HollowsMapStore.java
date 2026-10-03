/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import sbs.modid.client.core.location.hollows.HollowsStructure;
import sbs.modid.client.core.location.hollows.StructureFix;
import sbs.modid.client.helper.map.model.HollowsLobbyMap;
import sbs.modid.client.helper.map.model.HollowsMarker;
import sbs.modid.client.helper.map.model.HollowsTrail;
import sbs.modid.client.helper.map.model.KnownStructure;
import sbs.modid.client.helper.map.model.SharedSighting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Crystal Hollows map's state for one account: a {@link HollowsLobbyMap} per lobby id, which one
 * is current, and the JSON form of the lot ({@code ch_map.json}).
 *
 * <p>No game types and no file access - {@code HollowsMapTracker} feeds it and does the IO - so the
 * lobby switch, the merge and the 6-hour expiry are unit-tested directly.
 *
 * <p><b>The unknown lobby.</b> For the first moments on the Hollows the tab list may not have named
 * the lobby yet. What is recorded meanwhile goes into a pending map with id {@code ""}, which is never
 * saved, and is folded into the real lobby as soon as its id is known - it was recorded there.
 */
public final class HollowsMapStore {

    /** A lobby not seen for this long is dropped: the Hollows it described have been rebuilt. */
    public static final long EXPIRY_MS = 6L * 60 * 60 * 1000;

    /** The save format. A file with a higher one is refused rather than half-read. */
    static final int SCHEMA = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, HollowsLobbyMap> lobbies = new LinkedHashMap<>();

    private HollowsLobbyMap current;

    /** The live lobby's map, or {@code null} off the Hollows. */
    public HollowsLobbyMap current() {
        return current;
    }

    /** A lobby's map whether or not it is current, or {@code null}. */
    public HollowsLobbyMap lobby(String id) {
        return lobbies.get(id);
    }

    public int lobbyCount() {
        return lobbies.size();
    }

    /**
     * Makes {@code lobby} the current one and returns its map: the stored one if this lobby was seen
     * before, a fresh one otherwise. Switching never carries anything from one lobby into another,
     * except the pending {@code ""} map into the lobby it turned out to be.
     */
    public HollowsLobbyMap switchTo(String lobby, long now) {
        String id = lobby == null ? "" : lobby;
        if (current != null && current.lobby().equals(id)) {
            current.touch(now);
            return current;
        }
        if (id.isEmpty()) {
            current = new HollowsLobbyMap("", now);
            return current;
        }
        HollowsLobbyMap target = lobbies.computeIfAbsent(id, key -> new HollowsLobbyMap(key, now));
        if (current != null && current.lobby().isEmpty()) {
            target.mergeFrom(current);
        }
        target.touch(now);
        current = target;
        return current;
    }

    /** Off the Hollows: nothing is current. Stored lobbies stay. */
    public void leave() {
        current = null;
    }

    /** Keeps the own fixes the detector handed over for a lobby that is being left. */
    public void storeOwn(String lobby, Collection<StructureFix> fixes, long now) {
        if (lobby == null || lobby.isEmpty()) {
            return;
        }
        HollowsLobbyMap map = lobbies.computeIfAbsent(lobby, key -> new HollowsLobbyMap(key, now));
        map.storeFixes(fixes);
        map.touch(now);
    }

    /**
     * The seam Structure Sharing plugs into: a structure location another player reported for a
     * lobby, with its confirmation count.
     */
    public void offerShared(String lobby, HollowsStructure structure, int x, int y, int z,
                            int confirmations, long now) {
        if (lobby == null || lobby.isEmpty() || structure == null) {
            return;
        }
        lobbies.computeIfAbsent(lobby, key -> new HollowsLobbyMap(key, now))
                .offerShared(new SharedSighting(structure, x, y, z, Math.max(0, confirmations), now));
    }

    /** Drops every lobby not seen within {@link #EXPIRY_MS}, except the current one. */
    public int prune(long now) {
        int dropped = 0;
        Iterator<HollowsLobbyMap> it = lobbies.values().iterator();
        while (it.hasNext()) {
            HollowsLobbyMap map = it.next();
            if (map != current && now - map.lastSeenAt() > EXPIRY_MS) {
                it.remove();
                dropped++;
            }
        }
        return dropped;
    }

    /** Takes a store read from disk into this one, lobby by lobby. */
    public void absorb(HollowsMapStore loaded) {
        for (HollowsLobbyMap map : loaded.lobbies.values()) {
            HollowsLobbyMap mine = lobbies.get(map.lobby());
            if (mine == null) {
                lobbies.put(map.lobby(), map);
            } else {
                mine.mergeFrom(map);
            }
        }
    }

    /**
     * Every structure the map can draw for a lobby, one per type: the player's own fix where there is
     * one (always confirmed), else the shared sighting.
     *
     * @param own the live fixes when {@code map} is the current lobby; {@code null} to use the stored
     */
    public static List<KnownStructure> known(HollowsLobbyMap map, Collection<StructureFix> own) {
        List<KnownStructure> out = new ArrayList<>();
        if (map == null) {
            return out;
        }
        Collection<StructureFix> fixes = own != null ? own : map.storedFixes();
        for (HollowsStructure structure : HollowsStructure.values()) {
            StructureFix fix = null;
            for (StructureFix candidate : fixes) {
                if (candidate.structure() == structure && candidate.samples() > 0) {
                    fix = candidate;
                    break;
                }
            }
            if (fix != null) {
                out.add(new KnownStructure(structure, fix.x(), fix.y(), fix.z(), true, 0, true));
                continue;
            }
            SharedSighting sighting = map.shared().get(structure);
            if (sighting != null) {
                out.add(new KnownStructure(structure, sighting.x(), sighting.y(), sighting.z(), false,
                        sighting.confirmations(), sighting.confirmed()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ JSON

    /**
     * The save file's text. Expired lobbies are pruned first; the pending lobby is never written.
     *
     * @param liveFixes the detector's fixes for the current lobby, written in place of its stored ones
     */
    public String toJson(long now, Collection<StructureFix> liveFixes) {
        prune(now);
        FileDto file = new FileDto();
        file.schema = SCHEMA;
        for (HollowsLobbyMap map : lobbies.values()) {
            LobbyDto dto = new LobbyDto();
            dto.lastSeen = map.lastSeenAt();
            Collection<StructureFix> fixes = map == current && liveFixes != null ? liveFixes : map.storedFixes();
            for (StructureFix fix : fixes) {
                FixDto f = new FixDto();
                f.structure = fix.structure().name();
                f.firstSeen = fix.firstSeenAt();
                f.x = fix.x();
                f.y = fix.y();
                f.z = fix.z();
                f.samples = fix.samples();
                f.box = new int[]{fix.minX(), fix.minY(), fix.minZ(), fix.maxX(), fix.maxY(), fix.maxZ()};
                dto.own.add(f);
            }
            for (SharedSighting sighting : map.shared().values()) {
                SharedDto s = new SharedDto();
                s.structure = sighting.structure().name();
                s.x = sighting.x();
                s.y = sighting.y();
                s.z = sighting.z();
                s.confirmations = sighting.confirmations();
                s.received = sighting.receivedAt();
                dto.shared.add(s);
            }
            dto.upper = map.trail().encode(HollowsTrail.Layer.UPPER);
            dto.magma = map.trail().encode(HollowsTrail.Layer.MAGMA);
            for (HollowsMarker marker : map.markers()) {
                MarkerDto m = new MarkerDto();
                m.label = marker.label();
                m.x = marker.x();
                m.y = marker.y();
                m.z = marker.z();
                m.created = marker.createdAt();
                dto.markers.add(m);
            }
            file.lobbies.put(map.lobby(), dto);
        }
        return GSON.toJson(file);
    }

    /**
     * Reads a save file. Lobbies older than {@link #EXPIRY_MS} are dropped on the way in, unknown
     * structure ids are skipped, and a newer schema or malformed text gives an empty store.
     *
     * @throws JsonParseException when the text is not JSON at all - the caller logs and carries on
     */
    public static HollowsMapStore fromJson(String json, long now) {
        HollowsMapStore store = new HollowsMapStore();
        FileDto file = GSON.fromJson(json, FileDto.class);
        if (file == null || file.schema > SCHEMA || file.lobbies == null) {
            return store;
        }
        for (Map.Entry<String, LobbyDto> entry : file.lobbies.entrySet()) {
            LobbyDto dto = entry.getValue();
            if (entry.getKey() == null || entry.getKey().isEmpty() || dto == null
                    || now - dto.lastSeen > EXPIRY_MS) {
                continue;
            }
            HollowsLobbyMap map = new HollowsLobbyMap(entry.getKey(), dto.lastSeen);
            List<StructureFix> fixes = new ArrayList<>();
            for (FixDto f : nonNull(dto.own)) {
                HollowsStructure structure = HollowsStructure.fromId(f.structure);
                if (structure == null) {
                    continue;
                }
                int[] b = f.box != null && f.box.length == 6 ? f.box
                        : new int[]{f.x, f.y, f.z, f.x, f.y, f.z};
                fixes.add(StructureFix.restore(structure, f.firstSeen, f.x, f.y, f.z, f.samples,
                        b[0], b[1], b[2], b[3], b[4], b[5]));
            }
            map.storeFixes(fixes);
            for (SharedDto s : nonNull(dto.shared)) {
                HollowsStructure structure = HollowsStructure.fromId(s.structure);
                if (structure != null) {
                    map.offerShared(new SharedSighting(structure, s.x, s.y, s.z, s.confirmations, s.received));
                }
            }
            map.trail().restore(HollowsTrail.Layer.UPPER, dto.upper);
            map.trail().restore(HollowsTrail.Layer.MAGMA, dto.magma);
            for (MarkerDto m : nonNull(dto.markers)) {
                map.addMarker(m.label, m.x, m.y, m.z, m.created);
            }
            store.lobbies.put(map.lobby(), map);
        }
        return store;
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    // Gson shapes of ch_map.json. Field names are the file format: never rename one.

    private static final class FileDto {
        int schema;
        Map<String, LobbyDto> lobbies = new LinkedHashMap<>();
    }

    private static final class LobbyDto {
        long lastSeen;
        List<FixDto> own = new ArrayList<>();
        List<SharedDto> shared = new ArrayList<>();
        int[] upper;
        int[] magma;
        List<MarkerDto> markers = new ArrayList<>();
    }

    private static final class FixDto {
        String structure;
        long firstSeen;
        int x;
        int y;
        int z;
        int samples;
        int[] box;
    }

    private static final class SharedDto {
        String structure;
        int x;
        int y;
        int z;
        int confirmations;
        long received;
    }

    private static final class MarkerDto {
        String label;
        int x;
        int y;
        int z;
        long created;
    }
}
