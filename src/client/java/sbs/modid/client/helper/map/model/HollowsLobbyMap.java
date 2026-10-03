/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.model;

import sbs.modid.client.core.location.hollows.HollowsStructure;
import sbs.modid.client.core.location.hollows.StructureFix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the Crystal Hollows map knows about one lobby: the trail, the player's own markers,
 * structures others reported, and - while this is not the live lobby - the structures the player
 * found here before.
 *
 * <p>While the lobby is live, the player's own structures are not held here: {@code HollowsDetector}
 * owns them, and {@link #storedFixes()} is only the copy handed over when the lobby was left or read
 * back from disk.
 */
public final class HollowsLobbyMap {

    /** Most hand-placed markers one lobby keeps. */
    public static final int MAX_MARKERS = 30;

    /** Longest label a marker keeps. */
    public static final int MAX_LABEL = 32;

    private final String lobby;
    private long lastSeenAt;
    private final HollowsTrail trail = new HollowsTrail();
    private final List<HollowsMarker> markers = new ArrayList<>();
    private final Map<HollowsStructure, SharedSighting> shared = new EnumMap<>(HollowsStructure.class);
    private final Map<HollowsStructure, StructureFix> stored = new EnumMap<>(HollowsStructure.class);

    public HollowsLobbyMap(String lobby, long lastSeenAt) {
        this.lobby = lobby == null ? "" : lobby;
        this.lastSeenAt = lastSeenAt;
    }

    public String lobby() {
        return lobby;
    }

    public long lastSeenAt() {
        return lastSeenAt;
    }

    public void touch(long now) {
        lastSeenAt = Math.max(lastSeenAt, now);
    }

    public HollowsTrail trail() {
        return trail;
    }

    public List<HollowsMarker> markers() {
        return Collections.unmodifiableList(markers);
    }

    /**
     * Adds a marker. Returns {@code false} when the lobby already holds {@link #MAX_MARKERS} or the
     * label is blank.
     */
    public boolean addMarker(String label, int x, int y, int z, long now) {
        if (label == null || label.isBlank() || markers.size() >= MAX_MARKERS) {
            return false;
        }
        String clean = label.strip();
        if (clean.length() > MAX_LABEL) {
            clean = clean.substring(0, MAX_LABEL);
        }
        markers.add(new HollowsMarker(clean, x, y, z, now));
        return true;
    }

    /** Removes every marker with this label, ignoring case. Returns how many went. */
    public int removeMarkers(String label) {
        int before = markers.size();
        markers.removeIf(marker -> marker.label().equalsIgnoreCase(label.strip()));
        return before - markers.size();
    }

    /** Removes the marker nearest to a position. Returns it, or {@code null} when there is none. */
    public HollowsMarker removeNearest(int x, int y, int z) {
        HollowsMarker best = null;
        long bestDistance = Long.MAX_VALUE;
        for (HollowsMarker marker : markers) {
            long dx = marker.x() - x;
            long dy = marker.y() - y;
            long dz = marker.z() - z;
            long distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = marker;
            }
        }
        if (best != null) {
            markers.remove(best);
        }
        return best;
    }

    public Map<HollowsStructure, SharedSighting> shared() {
        return Collections.unmodifiableMap(shared);
    }

    /**
     * Takes a sighting someone else reported. A newer report replaces an older one; an older one never
     * replaces a newer one, so a late-arriving stale message cannot move a marker back.
     */
    public void offerShared(SharedSighting sighting) {
        if (sighting == null || sighting.structure() == null) {
            return;
        }
        SharedSighting known = shared.get(sighting.structure());
        if (known == null || sighting.receivedAt() >= known.receivedAt()) {
            shared.put(sighting.structure(), sighting);
        }
    }

    public Collection<StructureFix> storedFixes() {
        return Collections.unmodifiableCollection(stored.values());
    }

    /** Replaces the stored own fixes with the ones the detector handed over. */
    public void storeFixes(Collection<StructureFix> fixes) {
        stored.clear();
        for (StructureFix fix : fixes) {
            stored.put(fix.structure(), fix.copy());
        }
    }

    /**
     * Takes another map of the same lobby into this one: trail cells, markers up to the cap, shared
     * sightings by recency, stored fixes where this one has none.
     */
    public void mergeFrom(HollowsLobbyMap other) {
        trail.addAll(other.trail);
        for (HollowsMarker marker : other.markers) {
            if (markers.size() >= MAX_MARKERS) {
                break;
            }
            if (!markers.contains(marker)) {
                markers.add(marker);
            }
        }
        for (SharedSighting sighting : other.shared.values()) {
            offerShared(sighting);
        }
        for (StructureFix fix : other.stored.values()) {
            stored.putIfAbsent(fix.structure(), fix.copy());
        }
        touch(other.lastSeenAt);
    }
}
