/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.beacon.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.sound.NotePitch;
import sbs.modid.client.core.sound.SoundListeners;
import sbs.modid.client.skills.foraging.beacon.model.BeatReading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens to the Moonglade beacon's beat and reports its <b>pitch as a note</b> and its <b>speed as a
 * measured interval</b>.
 *
 * <p><b>What this is, honestly.</b> It is the reader half of the requested tuner, not the solver.
 * It answers "what is the beat doing" — which is the half the request calls valuable, because pitch
 * is otherwise matched by ear. It does not answer "what are my controls set to", because that lives
 * in a menu nobody has captured yet, so there is no current-versus-target here and no adjustment
 * direction. Adding those needs one menu probe, not a redesign; see the spec.
 *
 * <p><b>Nothing about the beat is assumed.</b> The request asked for the beat to be measured rather
 * than matched against a fixed set of speeds, and that is what happens: the repeating sound is found
 * by observation (the id heard most often in the window), its interval is the median gap between
 * consecutive plays, and its pitch is decoded through {@link NotePitch}, which refuses to name a note
 * for a pitch that is not on the note-block grid.
 *
 * <p><b>The feature resolves its own open question.</b> It is not known whether Hypixel drives the
 * beat with a real note-block pitch or a pre-pitched sample, and that decides whether a pitch solver
 * can exist at all. Rather than guess, this tracks whether the pitch ever <i>varies</i>: a pitch that
 * moves between beats is real data, and one that never moves cannot be told from a fixed cue —
 * because {@code 1.0} is itself a valid note (F#4), a single constant reading proves nothing. The
 * display says which of the two it is seeing, so the answer arrives from normal play instead of from
 * a capture session.
 *
 * <p><b>Subscribed only where it is needed.</b> {@code SoundEngine.play} is the funnel for every
 * sound in the game, so this registers with {@link SoundListeners} on arrival at the beacon's zone
 * and unregisters on leaving. Off the marsh the sound path costs one static boolean read.
 *
 * <p>Sounds arrive on the client thread; {@link #reading()} is read by the renderer on the same
 * thread, and the published reading is swapped in as one immutable object.
 */
public final class BeaconBeatReader {

    private static final BeaconBeatReader INSTANCE = new BeaconBeatReader();

    /** The zone the beacon stands in. Asked of {@link SkyBlockLocation}, never of the scoreboard. */
    private static final String BEACON_ZONE = "South Reaches";
    private static final String BEACON_ISLAND = "Moonglade Marsh";

    /** Sounds further than this from the player are somebody else's. */
    private static final double RADIUS = 24.0;

    /** How many recent plays of one id are kept. Enough for a stable median, small enough to be free. */
    private static final int WINDOW = 24;

    /** Beats at least this far apart are separate beats rather than one chord's worth of layers. */
    private static final long MIN_GAP_MS = 60L;

    /** A beat this old is stale: the beacon stopped, or it was never the beat. */
    private static final long STALE_MS = 12_000L;

    /** Gaps must agree within this fraction of the median before the speed is called measured. */
    private static final double SPEED_TOLERANCE = 0.25;

    /** Fewest beats before anything is published. Two points make a line and prove nothing. */
    private static final int MIN_BEATS = 4;

    /** Per sound id, the recent plays. Bounded by how many distinct ids play near the beacon. */
    private final Map<String, Track> tracks = new HashMap<>();

    private volatile BeatReading reading = BeatReading.NONE;
    private boolean subscribed;

    /** The listener is a field so the same instance is passed to add and remove. */
    private final SoundListeners.Listener listener = this::onSound;

    private BeaconBeatReader() {
    }

    public static BeaconBeatReader getInstance() {
        return INSTANCE;
    }

    /** The latest reading; never {@code null}, {@link BeatReading#NONE} until something is heard. */
    public BeatReading reading() {
        return reading;
    }

    /** True while the player is where the beacon is and the module is on. */
    public boolean atBeacon() {
        return ConfigManager.getInstance().get().beaconTuner.enabled
                && SkyBlockLocation.onIsland(BEACON_ISLAND)
                && BEACON_ZONE.equalsIgnoreCase(SkyBlockLocation.zone());
    }

    /**
     * Ticked from the client tick: subscribes on arrival, unsubscribes and forgets on leaving.
     *
     * <p>Clearing on the way out is the reset rule, and it covers the world change and the server hop
     * for free — both land the player somewhere that is not this zone, so the state is dropped
     * without needing a hook per boundary.
     */
    public void tick(Minecraft minecraft) {
        boolean want = minecraft.player != null && atBeacon();
        if (want == subscribed) {
            if (want) {
                expire();
            }
            return;
        }
        if (want) {
            SoundListeners.add(listener);
            subscribed = true;
        } else {
            SoundListeners.remove(listener);
            subscribed = false;
            tracks.clear();
            reading = BeatReading.NONE;
        }
    }

    /** Drops a reading whose beat has stopped, so the card never shows a beat that is over. */
    private void expire() {
        BeatReading current = reading;
        if (current.heard() && System.currentTimeMillis() - current.lastBeatAt() > STALE_MS) {
            tracks.clear();
            reading = BeatReading.NONE;
        }
    }

    // ------------------------------------------------------------------
    // Listening
    // ------------------------------------------------------------------

    /** One sound, on the client thread, only while subscribed. Records and returns; no work here. */
    private void onSound(SoundInstance instance) {
        Player player = Minecraft.getInstance().player;
        if (player == null || instance.getIdentifier() == null) {
            return;
        }
        double dx = instance.getX() - player.getX();
        double dy = instance.getY() - player.getY();
        double dz = instance.getZ() - player.getZ();
        if (dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) {
            return;
        }
        long now = System.currentTimeMillis();
        Track track = tracks.computeIfAbsent(instance.getIdentifier().toString(), id -> new Track());
        track.add(now, instance.getPitch());
        publish(now);
    }

    /**
     * Rebuilds the published reading from whichever id is beating most.
     *
     * <p>"Most beats in the window" is the whole of the beat-finding rule, and it is deliberately
     * that simple: anything cleverer would be a guess about which sound Hypixel chose, which is
     * exactly what the request said not to assume.
     */
    private void publish(long now) {
        Track best = null;
        String bestId = null;
        for (Map.Entry<String, Track> entry : tracks.entrySet()) {
            Track track = entry.getValue();
            if (track.fresh(now) && (best == null || track.times.size() > best.times.size())) {
                best = track;
                bestId = entry.getKey();
            }
        }
        if (best == null || best.times.size() < MIN_BEATS) {
            reading = BeatReading.NONE;
            return;
        }
        reading = best.toReading(bestId, now);
    }

    // ------------------------------------------------------------------
    // One sound id's recent history
    // ------------------------------------------------------------------

    private static final class Track {
        private final List<Long> times = new ArrayList<>(WINDOW);
        private final List<Float> pitches = new ArrayList<>(WINDOW);

        void add(long now, float pitch) {
            // Two plays inside MIN_GAP_MS are one beat layered, not two beats: counting both would
            // halve every interval this reports.
            if (!times.isEmpty() && now - times.get(times.size() - 1) < MIN_GAP_MS) {
                return;
            }
            times.add(now);
            pitches.add(pitch);
            while (times.size() > WINDOW) {
                times.remove(0);
                pitches.remove(0);
            }
        }

        boolean fresh(long now) {
            return !times.isEmpty() && now - times.get(times.size() - 1) <= STALE_MS;
        }

        BeatReading toReading(String id, long now) {
            long last = times.get(times.size() - 1);
            float pitch = pitches.get(pitches.size() - 1);

            List<Long> gaps = new ArrayList<>(times.size());
            for (int i = 1; i < times.size(); i++) {
                gaps.add(times.get(i) - times.get(i - 1));
            }
            long median = 0;
            BeatReading.Confidence speed = BeatReading.Confidence.UNKNOWN;
            if (gaps.size() >= MIN_BEATS - 1) {
                List<Long> sorted = new ArrayList<>(gaps);
                Collections.sort(sorted);
                median = sorted.get(sorted.size() / 2);
                // An irregular beat is not a slow beat. Only call the speed measured when the gaps
                // actually agree; otherwise this is not a metronome and saying "1.4s" would be a
                // number the player cannot act on.
                boolean steady = median > 0;
                for (long gap : sorted) {
                    if (Math.abs(gap - median) > median * SPEED_TOLERANCE) {
                        steady = false;
                        break;
                    }
                }
                speed = steady ? BeatReading.Confidence.MEASURED : BeatReading.Confidence.UNREADABLE;
            }

            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (float value : pitches) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            boolean varies = max - min > 1.0E-4f;
            Integer note = NotePitch.noteOf(pitch);
            // Varying pitch is proof the value carries information; constant pitch is not proof of
            // the opposite, so it is reported as FIXED rather than as a confident note.
            BeatReading.Confidence pitchState =
                    varies ? BeatReading.Confidence.MEASURED : BeatReading.Confidence.FIXED;

            return new BeatReading(id, note, pitch, pitchState, median, speed, times.size(), last);
        }
    }
}
