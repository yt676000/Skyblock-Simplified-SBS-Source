/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.AlertChannel;
import sbs.modid.client.core.alert.AlertChannels;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.foraging.model.SweepChop;
import sbs.modid.client.skills.progress.SkillTracker;

/**
 * The live effective Sweep, read off Hypixel's per-chop foraging detail message.
 *
 * <p><b>Why chat and not the stat.</b> The Sweep in the stat menu leaves out every conditional
 * bonus - melee-only bonuses, the first hit on a tree, tree-specific bonuses and the thrown-axe
 * penalty. The per-chop message is the only place the value that actually applied is published, so
 * it is the only source that can answer "what is my Sweep right now".
 *
 * <p><b>Two hooks, two jobs</b>, the pattern {@code AbilityDamageTracker} documents:
 * {@link #onChat} runs from the shared chat-parse mixin at the <i>public</i> {@code ChatComponent}
 * entry points and records the chop; {@link #shouldHide} runs later at the display funnel and tells
 * Better Chat's mixin to drop the line. Recording therefore always happens before hiding, so a
 * hidden chop still reaches the card.
 *
 * <p><b>Nothing here is persisted.</b> This is state about the swing you just made; a value carried
 * across a restart could only ever be a confidently wrong number. It is cleared on a world change, a
 * profile switch and an island change - see {@link #onWorldChange()} and {@link #tick()}.
 *
 * <p><b>The HUD pulls; nothing pushes.</b> The card reads {@link #last()} once per frame, so a
 * thousand messages a second and one message a second cost the render path exactly the same. That
 * is the whole throttle, and it is why there is no timer here.
 */
public final class SweepTracker {

    private static final SweepTracker INSTANCE = new SweepTracker();

    /** How long Foraging XP must flow with no sweep message before the setup hint is offered. */
    private static final long HINT_AFTER_MS = 30_000L;

    /** A near-miss is logged at most this often, so a reworded message cannot flood the log. */
    private static final long NEAR_MISS_LOG_MS = 60_000L;

    /** The newest parsed chop. Written from the network thread, read while rendering. */
    private volatile SweepChop last;

    /** Session statistics, guarded by {@code this}. */
    private double sessionMax = -1;
    private double sessionSum;
    private int sessionCount;

    /** When a sweep message was last understood, and when Foraging XP last moved. */
    private volatile long lastMessageAt;
    private volatile long foragingSinceAt;

    /** The island the session statistics belong to, so leaving it cannot carry them along. */
    private volatile String statsIsland = "";

    private volatile long lastNearMissLogAt;

    private SweepTracker() {
    }

    public static SweepTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.SweepSettings cfg() {
        return ConfigManager.getInstance().get().sweep;
    }

    // ------------------------------------------------------------------ input

    /**
     * Every chat line, raw as the funnel hands it over.
     *
     * <p>Stripping happens here rather than in the caller because Hypixel colours words mid-phrase,
     * and the cheap candidate gate runs on the raw string first - a line that cannot be ours costs
     * one scan and no allocation.
     */
    public void onChat(String raw) {
        if (!cfg().enabled || raw == null || !SweepParser.isCandidate(raw)) {
            return;
        }
        String stripped = StyledText.strip(raw);
        long now = System.currentTimeMillis();
        SweepChop chop = SweepParser.parse(stripped, now);
        if (chop == null) {
            reportNearMiss(stripped, now);
            return;
        }
        last = chop;
        lastMessageAt = now;
        if (chop.hasSweep()) {
            recordSession(chop.effectiveSweep());
        }
    }

    private synchronized void recordSession(double sweep) {
        String island = SkyBlockLocation.island();
        if (!island.equals(statsIsland)) {
            // A different island is different trees and different conditional bonuses; a max
            // carried across is another island's number reported with full confidence.
            statsIsland = island;
            sessionMax = -1;
            sessionSum = 0;
            sessionCount = 0;
        }
        sessionMax = Math.max(sessionMax, sweep);
        sessionSum += sweep;
        sessionCount++;
    }

    /**
     * A line that named the stat but carried no field we could read.
     *
     * <p>Logged, and deliberately not silent: {@code AGENTS.md} treats a feature that quietly went
     * dark as a bug, and a reworded message is exactly that. One throttled line is what makes it
     * diagnosable without a capture session.
     */
    private void reportNearMiss(String stripped, long now) {
        if (now - lastNearMissLogAt < NEAR_MISS_LOG_MS) {
            return;
        }
        lastNearMissLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Foraging] a line named Sweep but no field could be read - the wording may have "
                        + "moved. Run /sbs sweep capture on and send the file back. Line: \"{}\"",
                stripped);
    }

    /**
     * Display-funnel hook: whether this line is a sweep message the player asked to have hidden.
     *
     * <p>Hides <b>only a line the parser actually read</b>. {@code AGENTS.md} is explicit that
     * rewriting a message you did not fully match is a bug, so a variant wording we cannot read
     * stays visible - which is right twice over: the player keeps their information, and the
     * visible line is the bug report.
     */
    public boolean shouldHide(String text) {
        SBSConfig.SweepSettings cfg = cfg();
        if (!cfg.enabled || !cfg.hideChatMessages || text == null || !SweepParser.isCandidate(text)) {
            return false;
        }
        return SweepParser.parse(StyledText.strip(text), 0L) != null;
    }

    // ------------------------------------------------------------------ ticking

    /**
     * Client tick. Two jobs, both cheap: notice that the island changed under the session
     * statistics, and decide whether the player is chopping without the detail messages switched on.
     */
    public void tick() {
        if (!cfg().enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean foraging = SkillTracker.getInstance().foragingActive();
        if (!foraging) {
            foragingSinceAt = 0;
            return;
        }
        if (foragingSinceAt == 0) {
            foragingSinceAt = now;
        }
        maybeHint(now);
    }

    /**
     * The one-time hint, when Foraging XP has been flowing for a while and not one sweep message
     * has arrived.
     *
     * <p><b>Phrased as a suggestion, never as a diagnosis.</b> Whether the setting is on is
     * server-side state and nothing tells the client about it; the absence of messages is the only
     * evidence there will ever be. If Hypixel is quiet for some other reason a suggestion is
     * harmless, where "your setting is off" would simply be wrong.
     */
    private void maybeHint(long now) {
        SBSConfig.SweepSettings cfg = cfg();
        if (cfg.sweepHintShown || now - foragingSinceAt < HINT_AFTER_MS) {
            return;
        }
        if (lastMessageAt != 0 && now - lastMessageAt < HINT_AFTER_MS) {
            return; // messages are arriving; nothing to suggest
        }
        if (cfg.onlyForagingIslands && !SkillIslands.foragingAllowed()) {
            return;
        }
        cfg.sweepHintShown = true;
        ConfigManager.getInstance().save();
        Alerts.send(Alerts.Alert.of("Sweep card",
                        "You are chopping but Hypixel is not sending the per-chop detail lines. "
                                + "Switch the extra foraging details on in your SkyBlock settings and "
                                + "the card fills in by itself."),
                AlertChannels.has(cfg.hintChannels, AlertChannel.CHAT)
                        ? cfg.hintChannels : AlertChannel.CHAT.bit());
    }

    // ------------------------------------------------------------------ resets

    /** World change / server hop: everything goes, session statistics included. */
    public void onWorldChange() {
        last = null;
        lastMessageAt = 0;
        foragingSinceAt = 0;
        synchronized (this) {
            statsIsland = "";
            sessionMax = -1;
            sessionSum = 0;
            sessionCount = 0;
        }
    }

    /** Profile switch: a different profile is different gear, so it is a different Sweep. */
    public void onProfileChange() {
        onWorldChange();
    }

    // ------------------------------------------------------------------ output

    /** The newest chop, or {@code null} when none has been read this session. */
    public SweepChop last() {
        return last;
    }

    /**
     * Whether a log came down within {@code seconds}.
     *
     * <p><b>Measured from Foraging XP, not from a block-break hook</b>, and that is the accurate
     * choice rather than the convenient one: a thrown axe breaks its blocks server-side, so the
     * client's own break path never runs for it and a block-break gate would call a thrown-axe
     * player idle while they chopped. Hypixel pays out Foraging XP for both, which is why this
     * follows the payout - and via {@link SkillTracker#payoutAt} rather than {@code foragingActive},
     * so the window is the player's rather than that class's own eight seconds.
     */
    public boolean choppedWithin(int seconds) {
        long at = SkillTracker.getInstance().payoutAt("Foraging");
        return at != 0 && System.currentTimeMillis() - at <= Math.max(1, seconds) * 1000L;
    }

    /** Whether the last chop is older than the configured staleness delay. */
    public boolean stale() {
        SweepChop chop = last;
        if (chop == null) {
            return true;
        }
        long limit = Math.max(1, cfg().staleAfterSeconds) * 1000L;
        return System.currentTimeMillis() - chop.at() > limit;
    }

    public synchronized double sessionMax() {
        return sessionMax;
    }

    public synchronized double sessionAverage() {
        return sessionCount == 0 ? -1 : sessionSum / sessionCount;
    }

    public synchronized int sessionChops() {
        return sessionCount;
    }

    /**
     * The best available effective Sweep and how much that answer is worth - the shared value the
     * planned block-preview feature reads instead of the stat.
     *
     * @param value     the effective Sweep, or {@code -1} when nothing is known
     * @param certainty {@link Certainty#CONFIRMED} for a fresh chop, {@link Certainty#ESTIMATED} for
     *                  a stale one, {@link Certainty#UNKNOWN} when no chop has been read
     * @param age       how long ago the value was reported, in milliseconds
     */
    public record SweepReading(double value, Certainty certainty, long age) {

        /** Whether a caller may act on this at all. */
        public boolean known() {
            return value >= 0 && certainty != Certainty.UNKNOWN;
        }
    }

    /**
     * What the tracker can honestly say right now.
     *
     * <p>A stale value is {@link Certainty#ESTIMATED} rather than confirmed: gear has not changed in
     * the last ten seconds, but a different tree carries a different conditional bonus, so it is a
     * decent guess and not a fact. Nothing read at all is {@link Certainty#UNKNOWN} - the stat is
     * deliberately <b>not</b> substituted here, because the whole point of this feature is that the
     * stat is the wrong number.
     */
    public SweepReading reading() {
        SweepChop chop = last;
        if (chop == null || !chop.hasSweep()) {
            return new SweepReading(-1, Certainty.UNKNOWN, 0);
        }
        long age = System.currentTimeMillis() - chop.at();
        return new SweepReading(chop.effectiveSweep(),
                stale() ? Certainty.ESTIMATED : Certainty.CONFIRMED, age);
    }
}
