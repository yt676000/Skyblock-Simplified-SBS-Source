/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.contest;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.timers.EventTimers;
import sbs.modid.client.skills.farming.contest.JacobContestParser.Bracket;
import sbs.modid.client.skills.farming.contest.JacobContestParser.Standing;
import sbs.modid.client.skills.farming.model.CropType;

import java.util.List;

/**
 * The running Jacob's Contest: when it runs, which crop, what the sidebar says about your standing,
 * your pace, and the two alerts.
 *
 * <p><b>When a contest runs</b> is decided by three signals, any one enough: the verified start line
 * within the last 20 minutes (a contest is one SkyBlock day), the sidebar showing the contest, or
 * {@link EventTimers#jacobSecondsLeft()} from the learned cycle. None alone is trusted to be there.
 *
 * <p><b>The probe.</b> While a contest runs, every 10 s the whole sidebar is logged under
 * {@code [SBS][Jacob]}, plus any Jacob or contest chat line, and one line when the contest ends. One
 * farmed contest gives the real lines the ESTIMATED patterns in {@link JacobContestParser} wait on.
 * The probe logs only lines that changed since the last one, so a quiet sidebar costs nothing.
 */
public final class JacobContestTracker {

    private static final JacobContestTracker INSTANCE = new JacobContestTracker();

    private static final long CONTEST_MS = 20 * 60_000L;
    private static final long SCAN_MS = 1000L;
    private static final long PROBE_MS = 10_000L;

    private final ContestRate rate = new ContestRate();

    private long lastScanAt;
    private long lastProbeAt;
    private String lastProbe = "";
    private long startSeenAt;
    private boolean active;
    private CropType anitaCrop;
    private volatile Standing standing = Standing.NONE;
    private Bracket bestBracket;
    private boolean lastMinuteAlerted;

    private JacobContestTracker() {
    }

    public static JacobContestTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FarmingSettings cfg() {
        return ConfigManager.getInstance().get().farming;
    }

    // ------------------------------------------------------------------ reads (render thread)

    public boolean active() {
        return active;
    }

    public Standing standing() {
        return standing;
    }

    /** The contest crop: the sidebar's, else the Anita line's, else null. */
    public CropType crop() {
        Standing s = standing;
        return s.crop() != null ? s.crop() : anitaCrop;
    }

    /** Seconds left: the sidebar's, else the learned cycle's, else -1. */
    public int secondsLeft() {
        Standing s = standing;
        if (s.secondsLeft() >= 0) {
            return s.secondsLeft();
        }
        long cycle = EventTimers.getInstance().jacobSecondsLeft();
        return cycle < 0 ? -1 : (int) cycle;
    }

    public double perMinute() {
        return rate.perMinute();
    }

    public long projection() {
        return rate.projection(standing.collected(), secondsLeft());
    }

    // ------------------------------------------------------------------ chat

    /** Every incoming chat line. */
    public void onChat(String text) {
        String plain = PlainText.strip(text).trim();
        if (JacobContestParser.isStart(plain)) {
            startSeenAt = System.currentTimeMillis();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Jacob] contest start line seen");
            return;
        }
        CropType anita = JacobContestParser.anitaCrop(plain);
        if (anita != null) {
            anitaCrop = anita;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Jacob] Anita line: crop {} (+{} fortune)",
                    anita.displayName(), JacobContestParser.anitaFortune(plain));
            return;
        }
        // Probe: the end/results lines have never been captured. Log anything that looks like one,
        // during a contest and for two minutes after it.
        boolean window = active || System.currentTimeMillis() - startSeenAt < CONTEST_MS + 120_000L;
        if (window && cfg().contestProbe && (plain.contains("Jacob") || plain.toUpperCase().contains("CONTEST"))) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Jacob] chat: {}", plain);
        }
    }

    // ------------------------------------------------------------------ tick

    /** Client tick; throttles itself to once a second. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_MS) {
            return;
        }
        lastScanAt = now;
        List<String> sidebar = SkyBlockLocation.sidebarLines();
        Standing read = JacobContestParser.standing(sidebar);
        boolean running = read.contest()
                || (startSeenAt > 0 && now - startSeenAt < CONTEST_MS)
                || EventTimers.getInstance().jacobSecondsLeft() >= 0;
        if (!running) {
            if (active) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Jacob] contest over (last standing: {})", standing);
                reset();
            }
            return;
        }
        active = true;
        standing = read;
        rate.add(now, read.collected());
        // The probe logs only where a contest can actually be seen on the sidebar: a learned contest
        // day alone would otherwise log a dungeon's sidebar for twenty minutes.
        if (read.contest() || now - startSeenAt < CONTEST_MS || SkyBlockLocation.onIsland("The Garden")) {
            probe(now, sidebar);
        }
        alerts(read);
    }

    private void probe(long now, List<String> sidebar) {
        if (!cfg().contestProbe || now - lastProbeAt < PROBE_MS) {
            return;
        }
        lastProbeAt = now;
        String joined = String.join(" | ", sidebar);
        if (!joined.equals(lastProbe)) {
            lastProbe = joined;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Jacob] sidebar: [{}] parsed={}", joined, standing);
        }
    }

    private void alerts(Standing read) {
        SBSConfig.FarmingSettings cfg = cfg();
        if (read.bracket() != null && (bestBracket == null || read.bracket().ordinal() > bestBracket.ordinal())) {
            boolean first = bestBracket == null;
            bestBracket = read.bracket();
            // The first reading is where you already stood, not a promotion.
            if (!first && cfg.contestBracketAlert) {
                Alerts.send(new Alerts.Alert("Jacob's Contest: " + read.bracket().label(),
                        "You reached " + read.bracket().label() + " with "
                                + NumberDisplay.format(read.collected()) + ".",
                        SbsAudio.Tone.CHIME, null), cfg.contestAlertChannels);
            }
        }
        int left = secondsLeft();
        if (!lastMinuteAlerted && left >= 0 && left < 60) {
            lastMinuteAlerted = true;
            if (cfg.contestLastMinuteAlert) {
                Alerts.send(new Alerts.Alert("Jacob's Contest: 1 minute left",
                        "Under a minute of the contest is left.", SbsAudio.Tone.BLIP, null),
                        cfg.contestAlertChannels);
            }
        }
    }

    private void reset() {
        active = false;
        standing = Standing.NONE;
        anitaCrop = null;
        bestBracket = null;
        lastMinuteAlerted = false;
        lastProbe = "";
        rate.clear();
    }
}
