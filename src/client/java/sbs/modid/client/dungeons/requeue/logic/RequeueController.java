/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.requeue.logic;

import sbs.modid.client.dungeons.requeue.model.RequeueTarget;
import sbs.modid.client.dungeons.requeue.model.RunEnd;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The Auto Requeue state machine. Pure - time and the game state come in as arguments - so every
 * path, and above all "never a second send", is covered by tests.
 *
 * <pre>
 * IDLE --run completed--&gt; PENDING --server offers its re-queue, or outside for ARM_GRACE_MS--&gt; COUNTDOWN
 * COUNTDOWN --delay elapsed, checks pass, leader--&gt; SENT (exactly one command)
 * SENT --transferred (in place) / in an instance again (from outside)--&gt; IDLE
 * SENT --neither after FAIL_MS--&gt; IDLE, "did not go through"
 * any state --abort (input, cancel, !dt, party changed, no key, limit, idle, off)--&gt; IDLE + reason
 * </pre>
 *
 * <p><b>Rule binding</b> (maintainer decision 2026-10-06, Option B of
 * {@code docs/features/auto-requeue.md}, under the no-automation exception in the root
 * {@code AGENTS.md}, as {@code RejoinTimerSettings.autoRejoin} is). These are properties of this class,
 * not settings: off by default per category; at most {@link #MAX_CONSECUTIVE_LIMIT} requeues in a row,
 * never unlimited; one command per completed run and never a retry - {@link State#SENT} only leaves by
 * success or by telling the player it failed; any player input during the countdown cancels it; only
 * the party leader sends.
 */
public final class RequeueController {

    public enum State { IDLE, PENDING, COUNTDOWN, SENT }

    /** Why a requeue did not happen. Shown once, on the HUD and in chat. */
    public enum Abort {
        FEATURE_OFF("Auto Requeue is off"),
        LOST("Run lost - no requeue"),
        UNKNOWN_TARGET("Could not tell which instance was played"),
        IDLE("You were idle - no requeue"),
        EXPIRED("Did not leave the instance in time"),
        DOWNTIME("Downtime"),
        PARTY_CHANGED("Party changed since the run"),
        NO_KEY("No Kuudra key for"),
        LIMIT("Stopped after the requeue limit"),
        INPUT("Cancelled - you took over"),
        CANCELLED("Cancelled"),
        FAILED("Requeue did not go through"),
        LEADER_QUEUES("The party leader requeues");

        private final String text;

        Abort(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /**
     * The game state one tick sees.
     *
     * @param downtime  pending {@code !dt} entries as one line, {@code null} when none
     * @param outsideMs how long the player has been continuously outside any instance
     * @param input     player input since the countdown started
     */
    public record Env(boolean enabled, boolean leader, String downtime, Set<String> party,
                      boolean keyPresent, long outsideMs, boolean inInstance, boolean input,
                      long delayMs, int maxConsecutive) {
    }

    /** The hard ceiling on the "stop after N" setting. Not unlimited, by the rule. */
    public static final int MAX_CONSECUTIVE_LIMIT = 10;
    /**
     * Sustained presence outside the instance before the countdown may start. Covers the location
     * blip on a swap ({@code KuudraTracker.LEAVE_GRACE_MS}, 1.2 s) and Hypixel's ~3 s transfer
     * cooldown ({@code TransferCooldown}) - arriving in the hub is itself a transfer.
     */
    public static final long ARM_GRACE_MS = 3_500L;
    /** A run end whose instance is not left within this is dropped. */
    public static final long EXPIRE_MS = 5 * 60_000L;
    /** Still outside this long after the send = it did not go through. There is no acknowledgement. */
    public static final long FAIL_MS = 20_000L;
    /** No input for longer than this before the run ended = nobody is at the keyboard. */
    public static final long IDLE_LIMIT_MS = 60_000L;

    private State state = State.IDLE;
    private RequeueTarget.Category category;
    private RequeueTarget runTarget;
    /** The last instance a run completed in; what !up / !down step from between runs. */
    private RequeueTarget lastTarget;
    private Set<String> partyAtEnd = Set.of();
    private long pendingSince;
    private long fireAt;
    private long sentAt;
    private RequeueTarget sentTarget;
    /** Requeues sent in a row; reset by a cancel, a failure, the limit and switching off. */
    private int consecutive;
    /** +1 / -1 for the next requeue only; 0 = the same instance. */
    private int step;
    /** Whether this client sends; a non-leader only watches the countdown. */
    private boolean sender;
    /**
     * The server offered its own re-queue for this run ("Click HERE to re-queue"), so the countdown
     * runs inside the finished instance. Without it the player has to leave first.
     */
    private boolean offered;
    /** The send went out from inside the instance; success is the transfer, not entering one. */
    private boolean sentInPlace;
    /** The server confirmed the move ("You have been re-queued!" or a new world). */
    private boolean transferred;

    private Abort lastAbort;
    private String lastAbortDetail;
    private long lastAbortAt;
    /** Bumped on every abort, so the caller tells the player exactly once. */
    private int abortSeq;

    // ------------------------------------------------------------------ inputs

    /** A run ended. {@code idleMs} is how long the player had been still when it did. */
    public void onRunEnd(RunEnd end, boolean enabled, boolean requeueAfterLoss, Set<String> party,
                         long idleMs, long now) {
        if (end == null || !enabled) {
            return;
        }
        if (end.target() != null && end.kind() == RunEnd.Kind.COMPLETED) {
            lastTarget = end.target();
        }
        if (state == State.PENDING || state == State.COUNTDOWN) {
            return; // the first end wins; a second one cannot be a new run
        }
        category = end.category();
        runTarget = end.target();
        if (end.kind() == RunEnd.Kind.LOST && !requeueAfterLoss) {
            abort(Abort.LOST, null, now);
            return;
        }
        if (runTarget == null) {
            abort(Abort.UNKNOWN_TARGET, null, now);
            return;
        }
        if (idleMs > IDLE_LIMIT_MS) {
            abort(Abort.IDLE, null, now);
            return;
        }
        partyAtEnd = lowerCase(party);
        pendingSince = now;
        offered = false;
        state = State.PENDING;
    }

    /**
     * Hypixel printed its "Click HERE to re-queue" line: requeueing works from where the player
     * stands, so the countdown need not wait for them to leave. Ignored outside {@link State#PENDING}.
     */
    public void onRequeueOffered() {
        if (state == State.PENDING) {
            offered = true;
        }
    }

    /**
     * "You have been re-queued!". After our send, that is the success. While still pending, someone
     * else (the leader by hand) requeued the party: this run's requeue is done, so stand down quietly -
     * otherwise the next run's end would be swallowed by "the first end wins".
     */
    public void onRequeued() {
        if (state == State.SENT) {
            transferred = true;
        } else if (state == State.PENDING || state == State.COUNTDOWN) {
            state = State.IDLE;
        }
    }

    /**
     * A new world. After an in-place send, that is the transfer. While pending, the player left the
     * instance before the countdown: the server's offer is gone, so fall back to queueing from outside.
     */
    public void onWorldChange() {
        if (state == State.SENT) {
            transferred = true;
        } else if (state == State.PENDING) {
            offered = false;
        }
    }

    /** Whether this run's countdown runs inside the finished instance (send the server's re-queue). */
    public boolean inPlace() {
        return offered;
    }

    /**
     * One tick. Returns the instance id to send with {@code /joininstance} - at most once per
     * completed run - or {@code null}.
     */
    public String tick(Env env, long now) {
        switch (state) {
            case PENDING -> {
                if (!env.enabled()) {
                    abort(Abort.FEATURE_OFF, null, now);
                } else if (now - pendingSince > EXPIRE_MS) {
                    abort(Abort.EXPIRED, null, now);
                } else if ((offered || env.outsideMs() >= ARM_GRACE_MS) && checks(env, now)) {
                    sender = env.leader();
                    fireAt = now + Math.max(0, env.delayMs());
                    state = State.COUNTDOWN;
                }
            }
            case COUNTDOWN -> {
                if (!env.enabled()) {
                    abort(Abort.FEATURE_OFF, null, now);
                } else if (env.input() && sender) {
                    consecutive = 0;
                    abort(Abort.INPUT, null, now);
                } else if (now >= fireAt && checks(env, now)) {
                    if (!sender || !env.leader()) {
                        abort(Abort.LEADER_QUEUES, null, now);
                        return null;
                    }
                    sentTarget = target();
                    step = 0;
                    consecutive++;
                    sentAt = now;
                    sentInPlace = offered && env.inInstance();
                    transferred = false;
                    state = State.SENT;
                    return sentTarget.instanceId();
                }
            }
            case SENT -> {
                if (sentInPlace ? transferred : env.inInstance()) {
                    state = State.IDLE;
                } else if (now - sentAt > FAIL_MS) {
                    consecutive = 0;
                    abort(Abort.FAILED, sentTarget.label(), now);
                }
            }
            default -> {
            }
        }
        return null;
    }

    /** The checks that hold at arming and again at the moment of sending. */
    private boolean checks(Env env, long now) {
        if (env.downtime() != null) {
            abort(Abort.DOWNTIME, env.downtime(), now);
            return false;
        }
        if (!partyAtEnd.equals(lowerCase(env.party()))) {
            abort(Abort.PARTY_CHANGED, null, now);
            return false;
        }
        if (!env.leader()) {
            return true; // a watcher: no key, no counter - the leader's client decides
        }
        if (consecutive >= Math.min(MAX_CONSECUTIVE_LIMIT, Math.max(1, env.maxConsecutive()))) {
            String detail = String.valueOf(consecutive);
            consecutive = 0;
            abort(Abort.LIMIT, detail, now);
            return false;
        }
        if (category == RequeueTarget.Category.KUUDRA && !env.keyPresent()) {
            abort(Abort.NO_KEY, target().label(), now);
            return false;
        }
        return true;
    }

    /** Manual cancel (key, command). Resets the counter. {@code true} when something was running. */
    public boolean cancel(long now) {
        boolean active = state == State.PENDING || state == State.COUNTDOWN;
        consecutive = 0;
        step = 0;
        if (active) {
            abort(Abort.CANCELLED, null, now);
        }
        return active;
    }

    /** Both categories switched off: forget everything, the counter included. */
    public void onDisabled() {
        state = State.IDLE;
        consecutive = 0;
        step = 0;
    }

    /**
     * {@code !up} (+1), {@code !down} (-1), {@code !same} (0) - for the next requeue only. Stepping
     * follows {@link RequeueTarget#step}: F7 up is M1, M1 down is F7; it stops at E / M7 and T1 / T5.
     *
     * @param live the instance the player is in right now, if any; may be null
     * @return the reply for party chat
     */
    public String requestStep(int delta, RequeueTarget live) {
        RequeueTarget base = state == State.PENDING || state == State.COUNTDOWN ? runTarget
                : live != null ? live : lastTarget;
        if (base == null) {
            return "No run to go from yet";
        }
        if (delta == 0) {
            step = 0;
            return "Next run: " + base.label();
        }
        RequeueTarget next = base.step(delta);
        if (next == null) {
            return (delta > 0 ? "Already highest (" : "Already lowest (") + base.label() + ")";
        }
        step = delta;
        return "Next run: " + next.label();
    }

    // ------------------------------------------------------------------ outputs

    private void abort(Abort why, String detail, long now) {
        state = State.IDLE;
        lastAbort = why;
        lastAbortDetail = detail;
        lastAbortAt = now;
        abortSeq++;
    }

    /** What the countdown will send: the run's instance, stepped once by !up / !down if it can be. */
    public RequeueTarget target() {
        if (runTarget == null) {
            return null;
        }
        RequeueTarget stepped = step == 0 ? null : runTarget.step(step);
        return stepped != null ? stepped : runTarget;
    }

    public State state() {
        return state;
    }

    public RequeueTarget.Category category() {
        return category;
    }

    public boolean sender() {
        return sender;
    }

    /** Whole seconds left in the countdown, rounded up; 0 outside it. */
    public int secondsLeft(long now) {
        return state == State.COUNTDOWN ? (int) Math.max(0, (fireAt - now + 999) / 1000) : 0;
    }

    public int consecutive() {
        return consecutive;
    }

    public int pendingStep() {
        return step;
    }

    public Abort lastAbort() {
        return lastAbort;
    }

    public String lastAbortDetail() {
        return lastAbortDetail;
    }

    public long lastAbortAt() {
        return lastAbortAt;
    }

    public int abortSeq() {
        return abortSeq;
    }

    private static Set<String> lowerCase(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String name : names) {
            out.add(name.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
