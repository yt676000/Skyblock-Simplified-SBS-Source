/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.minions.model.MinionStopReason;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ties each hologram the island scan saw to the minion standing under it, works out whether that
 * minion has stopped, and tells the player once when the answer changes.
 *
 * <p>Fed from {@link MinionStateCapture}'s existing 5 s stand scan - there is deliberately no
 * second scan. What arrives here is already partitioned: the minions it identified by skull
 * texture, and every named stand in the same 128-block box.
 *
 * <p><b>The offsets below are {@code ESTIMATED}.</b> Nobody has measured how far above its minion
 * Hypixel hangs the line, so the window is generous vertically and tight horizontally - minions sit
 * shoulder to shoulder, and attributing a neighbour's hologram to the wrong stand would send the
 * player to the wrong minion, which is worse than missing one.
 *
 * <p><b>Everything seen is logged</b> under {@code [SBS][Minions]}, matched or not. That log line
 * is how the guessed wording in {@link MinionStopRules} gets replaced by the real one after a
 * single trip to the island, with no update in between.
 */
public final class MinionStoppages {

    /** A minion the scan identified, with where it stands. */
    public record MinionStand(long pos, double x, double y, double z, String type, int tier) {
    }

    /** A named armor stand: a hologram candidate, colour codes already stripped. */
    public record Hologram(String text, double x, double y, double z) {
    }

    /** Horizontal distance a hologram may sit from its minion. ESTIMATED. */
    private static final double MATCH_RADIUS = 1.5;
    /** Vertical window above the minion's feet a hologram may occupy. ESTIMATED. */
    private static final double MIN_DY = 0.0;
    private static final double MAX_DY = 3.0;

    private static final long LOG_INTERVAL_MS = 15_000L;

    /** Roman numerals for minion tiers. Hypixel names them I-XII; the list is one longer for room. */
    private static final String[] ROMAN = {
            "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"};

    /** What each minion was last known to be doing, so only a change is announced. */
    private static final Map<Long, MinionStopReason> lastState = new HashMap<>();

    private static long lastLogAt;

    private MinionStoppages() {
    }

    private static SBSConfig.MinionCalcSettings cfg() {
        return ConfigManager.getInstance().get().minionCalc;
    }

    /**
     * Processes one scan: classify, persist, alert on the changes.
     *
     * @param minions   every minion stand this scan identified
     * @param holograms every named stand in the same box
     */
    public static void record(List<MinionStand> minions, List<Hologram> holograms) {
        SBSConfig.MinionCalcSettings cfg = cfg();
        if (!cfg.stoppedWarning) {
            lastState.clear();
            return;
        }

        Set<Long> seen = new HashSet<>(minions.size());
        List<MinionStateStore.Stopped> stopped = new ArrayList<>();
        StringBuilder sample = System.currentTimeMillis() - lastLogAt > LOG_INTERVAL_MS
                ? new StringBuilder() : null;

        for (MinionStand minion : minions) {
            seen.add(minion.pos());
            Hologram above = nearest(minion, holograms);
            if (above == null) {
                continue;
            }
            if (sample != null) {
                sample.append('[').append(label(minion)).append(" -> \"")
                        .append(above.text()).append("\"] ");
            }
            MinionStopReason reason = MinionStopRules.classify(above.text(),
                    cfg.stoppedFullWords, cfg.stoppedBlockedWords, cfg.stoppedFlagUnknown);
            if (reason == null) {
                continue;
            }
            MinionStateStore.Stopped entry = new MinionStateStore.Stopped();
            entry.pos = minion.pos();
            entry.x = minion.x();
            entry.y = minion.y();
            entry.z = minion.z();
            entry.type = minion.type();
            entry.tier = minion.tier();
            entry.reason = reason;
            entry.text = above.text();
            entry.seenAt = System.currentTimeMillis();
            stopped.add(entry);
        }

        if (sample != null && sample.length() > 0) {
            lastLogAt = System.currentTimeMillis();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Minions] hologram(s) over minions: {}",
                    sample.toString().trim());
        }

        MinionStateStore.getInstance().recordStopped(seen, stopped);
        announce(seen, stopped, cfg);
    }

    /**
     * The hologram closest to this minion inside the match window, or {@code null}.
     *
     * <p>Nearest rather than first: with minions in a row, several holograms can fall inside one
     * window and only the closest is plausibly this one's.
     */
    private static Hologram nearest(MinionStand minion, List<Hologram> holograms) {
        Hologram best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Hologram hologram : holograms) {
            double dy = hologram.y() - minion.y();
            if (dy < MIN_DY || dy > MAX_DY) {
                continue;
            }
            double dx = hologram.x() - minion.x();
            double dz = hologram.z() - minion.z();
            double horizontal = dx * dx + dz * dz;
            if (horizontal > MATCH_RADIUS * MATCH_RADIUS) {
                continue;
            }
            if (horizontal < bestDistance) {
                bestDistance = horizontal;
                best = hologram;
            }
        }
        return best;
    }

    /**
     * One alert per minion per change of state, and nothing at all while the island looks the same.
     *
     * <p>A minion the scan <i>saw</i> and did not report stopped has its record dropped, which
     * re-arms it: that is what makes emptying one clear the flag. A minion the scan did not see is
     * out of range, not fixed, so its record is left where it is.
     */
    private static void announce(Set<Long> seen, List<MinionStateStore.Stopped> stopped,
                                 SBSConfig.MinionCalcSettings cfg) {
        Map<Long, MinionStateStore.Stopped> byPos = new HashMap<>();
        for (MinionStateStore.Stopped entry : stopped) {
            byPos.put(entry.pos, entry);
        }
        // Anything looked at and not stopped is working again.
        for (Long pos : seen) {
            if (!byPos.containsKey(pos)) {
                lastState.remove(pos);
            }
        }

        int full = 0;
        for (MinionStateStore.Stopped entry : stopped) {
            if (entry.reason == MinionStopReason.FULL) {
                full++;
            }
        }
        String summary = MinionStopRules.summary(stopped.size(), full);

        for (MinionStateStore.Stopped entry : stopped) {
            MinionStopReason previous = lastState.put(entry.pos, entry.reason);
            if (previous == entry.reason) {
                continue; // unchanged since the last scan: already said
            }
            String name = label(entry.type, entry.tier);
            Alerts.send(new Alerts.Alert(
                    name + ": " + entry.reason.displayName(),
                    summary,
                    SbsAudio.Tone.BLIP,
                    null), cfg.stoppedChannels);
        }
    }

    /** "Snow Minion V", from the generator type and tier. */
    public static String label(String type, int tier) {
        String name = prettyType(type);
        String numeral = tier > 0 && tier < ROMAN.length ? ROMAN[tier] : String.valueOf(tier);
        return numeral.isEmpty() ? name + " Minion" : name + " Minion " + numeral;
    }

    private static String label(MinionStand minion) {
        return label(minion.type(), minion.tier());
    }

    /** "NETHER_WARTS" -> "Nether Warts". The catalog id is the only name a stand scan produces. */
    private static String prettyType(String type) {
        if (type == null || type.isEmpty()) {
            return "Minion";
        }
        StringBuilder out = new StringBuilder(type.length());
        for (String word : type.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.length() == 0 ? "Minion" : out.toString();
    }
}
