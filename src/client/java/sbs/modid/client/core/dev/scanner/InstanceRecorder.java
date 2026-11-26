/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import com.google.gson.JsonObject;

/**
 * The auto-record half of the Server Scanner, shared by every instance preset (Kuudra now, the M7
 * boss later): one session per run, started when a feature's tracker says the run began, phase and
 * sub-event marks while it lasts, stopped when the run ends - right away, or after a tail that waits
 * for what comes after the end (the reward chest).
 *
 * <p>Pure: the decisions live here, the session itself behind {@link Sink}, the clock is passed in.
 * A feature owns one recorder and feeds it; it never talks to {@link ServerScanner} directly.
 *
 * <p><b>Never touches a manual session.</b> {@link Sink#start} refuses while anything is recording,
 * and a recorder only marks into and stops the session it started itself.
 */
public final class InstanceRecorder {

    /** Where the recorder's decisions go. {@link ServerScanner#sink()} in the game, a fake in tests. */
    public interface Sink {
        /** Start a session with {@code preset}; {@code false} when one is already running. */
        boolean start(ScanPreset preset, String instance, JsonObject info);

        /** Whether the session this recorder started is still the one running. */
        boolean recording();

        /** A typed event on the mark channel; {@code phase} non-null also switches the phase label. */
        void mark(String type, String phase, JsonObject fields);

        /** Stop the session, recording how the run ended. */
        void stop(String reason, String result);
    }

    public enum State { IDLE, RECORDING, ENDING }

    private final Sink sink;
    private final ScanPreset preset;
    private final String instance;
    private State state = State.IDLE;
    private long stopAt;
    private String result;
    private String phase;

    public InstanceRecorder(Sink sink, ScanPreset preset, String instance) {
        this.sink = sink;
        this.preset = preset;
        this.instance = instance;
    }

    public State state() {
        return state;
    }

    /**
     * The run began. Starts a session only when auto-record is on and the run is inside the instance
     * the preset is for; a run already being recorded is not restarted.
     */
    public boolean onRunStart(boolean enabled, boolean inInstance, JsonObject info) {
        if (!enabled || !inInstance || state != State.IDLE) {
            return false;
        }
        if (!sink.start(preset, instance, info)) {
            return false;
        }
        state = State.RECORDING;
        result = null;
        phase = null;
        return true;
    }

    /** A phase change: a {@code phase} mark, and the label every later event is counted under. */
    public void onPhase(String next, JsonObject fields) {
        if (!live() || next.equals(phase)) {
            return;
        }
        JsonObject event = fields == null ? new JsonObject() : fields;
        event.addProperty("from", phase);
        event.addProperty("to", next);
        phase = next;
        sink.mark("phase", next, event);
    }

    /** A sub-event a tracker knows (a crate placed, a chest opened...). */
    public void onEvent(String type, JsonObject fields) {
        if (live()) {
            sink.mark(type, null, fields == null ? new JsonObject() : fields);
        }
    }

    /**
     * The run ended with {@code result}. {@code tailMs} 0 stops now; otherwise the session keeps
     * recording that long (the reward chest), cut short by {@link #onTailSeen}.
     */
    public void onRunEnd(String result, long tailMs, long now) {
        if (!live()) {
            return;
        }
        this.result = result;
        JsonObject event = new JsonObject();
        event.addProperty("result", result);
        sink.mark("run_end", null, event);
        if (tailMs <= 0) {
            stop("run ended (" + result + ")");
            return;
        }
        state = State.ENDING;
        stopAt = now + tailMs;
    }

    /** What the tail waited for has arrived: stop {@code graceMs} later, never later than planned. */
    public void onTailSeen(long graceMs, long now) {
        if (state == State.ENDING) {
            stopAt = Math.min(stopAt, now + graceMs);
        }
    }

    /** The instance was left (or the feature switched off): stop now, whatever the phase. */
    public void onLeave(String why) {
        if (state == State.IDLE) {
            return;
        }
        if (result == null) {
            result = "left";
        }
        stop(why);
    }

    /** Once per tick: ends a due tail, and forgets a session someone else stopped. */
    public void tick(long now) {
        if (state == State.IDLE) {
            return;
        }
        if (!sink.recording()) {
            state = State.IDLE;   // stopped by hand, by the size cap or a write failure
            return;
        }
        if (state == State.ENDING && now >= stopAt) {
            stop("run ended (" + result + ")");
        }
    }

    private boolean live() {
        if (state != State.IDLE && !sink.recording()) {
            state = State.IDLE;
        }
        return state != State.IDLE;
    }

    private void stop(String reason) {
        state = State.IDLE;
        if (sink.recording()) {
            sink.stop(reason, result);
        }
    }
}
