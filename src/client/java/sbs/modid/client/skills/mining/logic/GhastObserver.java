/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.skills.mining.model.GhastSighting;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Accumulates Powder Ghast sightings across sessions and derives the spawn interval from them - or
 * says it does not know one, which is the state it ships in.
 *
 * <p><b>Nothing here encodes a spawn mechanic.</b> The Powder Ghast's timing is unverified: no wiki
 * figure is entered, no interval is assumed, and the observer starts with an empty history. Whatever
 * this reports is a statistic over sightings this client actually watched happen. That is the whole
 * design - a player waiting on an invented countdown has lost real time, which is worse than the
 * feature not existing, so "unknown" is the honest answer until the observations say otherwise.
 *
 * <p><b>History persists, the countdown does not.</b> The sighting list survives restarts (an
 * interval needs several plays to accumulate), but the live "time since the last one" is cleared on
 * every world change. A countdown carried across a server hop describes the instance you left, and
 * the two pieces of state are separated precisely so persistence cannot leak into it.
 *
 * <p><b>Two sightings are only an interval if nothing could have hidden a third.</b> Pairs are taken
 * within one world session only. A gap spanning a relog is discarded rather than averaged in, since
 * the player was not there to see what happened in it.
 *
 * <p><b>The known weakness</b>, stated because it bounds what the result can ever mean: an
 * {@link GhastSighting.Source#ENTITY} sighting dates when the player <i>looked</i>, not when the
 * ghast spawned. A series of them bounds the interval from above and no more. Only a chat
 * announcement dates the spawn itself, and whether one exists is exactly what
 * {@link #CHAT_MENTION} is here to find out - it matches any line naming the mob and logs it
 * verbatim rather than assuming a wording.
 */
public final class GhastObserver {

    private static final GhastObserver INSTANCE = new GhastObserver();

    /** The mob's name as it appears in a nametag, matched case-insensitively on stripped text. */
    private static final String GHAST_NAME = "powder ghast";

    /**
     * Any chat line naming the mob. Deliberately broad: the point is to discover whether a spawn is
     * announced and in what words, so a narrow pattern written from a guess would defeat the
     * exercise by matching nothing and looking like an answer.
     */
    private static final Pattern CHAT_MENTION =
            Pattern.compile("powder\\s+ghast", Pattern.CASE_INSENSITIVE);

    /** World scan cadence. A ghast lives for a while; twice a second is ample and costs nothing. */
    private static final long SCAN_INTERVAL_MS = 500L;

    /**
     * How long one entity stays "already counted". Hypixel reuses entity ids, and a ghast drifting
     * out of render distance and back is the same ghast - without this, one spawn would be booked as
     * several sightings and the derived interval would collapse towards zero.
     */
    private static final long ENTITY_REARM_MS = 120_000L;

    /**
     * Intervals needed before any figure is reported. Five is the point at which a spread is
     * meaningful at all; below it a median is one unlucky observation away from being wrong, and the
     * UI says how many are still missing rather than showing a provisional number.
     */
    public static final int MIN_INTERVALS = 5;

    /**
     * Relative spread below which the sightings are called a fixed interval. Above it the observer
     * reports the observed range instead - "somewhere between 4 and 19 minutes" is a true statement
     * about a variable spawn, while its median is a false statement about a fixed one.
     */
    private static final double FIXED_SPREAD_LIMIT = 0.15;

    private static final Type LIST_TYPE = new TypeToken<List<GhastSighting>>() {
    }.getType();

    /** Every sighting ever recorded, oldest first. Persisted. */
    private final List<GhastSighting> sightings = new ArrayList<>();

    /** Entity id -> when it was last booked, so one ghast is one sighting. Not persisted. */
    private final Map<Integer, Long> countedEntities = new HashMap<>();

    /** The current world session. Bumped on every world change; part of every sighting. */
    private long session = 1L;

    /** When a ghast was last seen in THIS session, or 0. Cleared on world change - never persisted. */
    private long lastSeenThisSession;

    private long lastScanAt;
    private boolean loaded;
    private boolean dirty;

    private GhastObserver() {
    }

    public static GhastObserver getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ input

    /** Called every client tick (throttled internally). */
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (minecraft.player == null || level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        load();
        expireCounted(now);
        scanWorld(level, now);
        save();
    }

    /**
     * Every chat line, as the funnel hands it over - which is <b>raw</b>, colour codes and all, so
     * the stripping happens here. It has to: Hypixel colours mob names mid-phrase ("§cPowder §fGhast"),
     * and a pattern expecting a plain space between the two words matches nothing against that. The
     * pattern is otherwise kept broad so an announcement is discovered rather than presumed.
     */
    public void onChat(String raw) {
        if (raw == null) {
            return;
        }
        String plain = StyledText.strip(raw);
        if (plain == null || !CHAT_MENTION.matcher(plain).find()) {
            return;
        }
        load();
        // Logged verbatim whether or not it is booked: the wording is the artifact, and a line that
        // mentions the mob without announcing a spawn (a commission, a kill message) is itself worth
        // seeing when the pattern is later narrowed.
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Ghast] chat line mentioning the mob: \"{}\"", plain);
        record(GhastSighting.Source.CHAT, plain, System.currentTimeMillis());
    }

    /**
     * World change / server hop. Drops the live countdown and starts a new session, so no pair
     * spanning the boundary is ever read as an interval. The history itself is kept and flushed.
     */
    public void onWorldChange() {
        lastSeenThisSession = 0L;
        countedEntities.clear();
        session++;
        save();
    }

    private void scanWorld(ClientLevel level, long now) {
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !living.hasCustomName()) {
                continue;
            }
            var custom = living.getCustomName();
            String raw = custom == null ? null : StyledText.strip(custom.getString());
            if (raw == null || !raw.toLowerCase(Locale.ROOT).contains(GHAST_NAME)) {
                continue;
            }
            // An ArmorStand here is the nametag rather than the mob, which is fine - the nametag is
            // what carries the name, and either way it is one sighting keyed on one entity id.
            String detail = (living instanceof ArmorStand ? "nametag: " : "entity: ") + raw;
            if (countedEntities.putIfAbsent(living.getId(), now) == null) {
                record(GhastSighting.Source.ENTITY, detail, now);
            }
        }
    }

    private void expireCounted(long now) {
        countedEntities.entrySet().removeIf(entry -> now - entry.getValue() > ENTITY_REARM_MS);
    }

    private void record(GhastSighting.Source source, String detail, long now) {
        GhastSighting sighting = new GhastSighting(now, session, SkyBlockLocation.island(),
                SkyBlockLocation.zone(), source, detail);
        sightings.add(sighting);
        dirty = true;
        long since = lastSeenThisSession == 0 ? -1 : now - lastSeenThisSession;
        lastSeenThisSession = now;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Ghast] sighting #{} source={} island={} zone={} sinceLastThisSession={} detail=\"{}\"",
                sightings.size(), source, sighting.island(), sighting.zone(),
                since < 0 ? "(first this session)" : (since / 1000) + "s", detail);
        Estimate estimate = estimate();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Ghast] {}", estimate.describe());
    }

    // ------------------------------------------------------------------ derivation

    /**
     * What the sightings support saying, and nothing more.
     *
     * @param certainty     {@link Certainty#UNKNOWN} until there are enough intervals to speak
     * @param intervals     how many usable intervals the history yielded
     * @param medianMs      median interval, or {@code 0} while unknown
     * @param minMs         shortest observed interval, or {@code 0} while unknown
     * @param maxMs         longest observed interval, or {@code 0} while unknown
     * @param fixedInterval whether the spread is tight enough to call it a fixed interval
     */
    public record Estimate(Certainty certainty, int intervals, long medianMs, long minMs, long maxMs,
                           boolean fixedInterval) {

        /** Whether a countdown may be drawn at all. */
        public boolean known() {
            return certainty != Certainty.UNKNOWN;
        }

        /** How many more intervals are needed before anything is reported. */
        public int stillNeeded() {
            return Math.max(0, MIN_INTERVALS - intervals);
        }

        /** One line for the log and the settings screen; never a number the data does not support. */
        public String describe() {
            if (!known()) {
                return "spawn interval unknown - " + intervals + " of " + MIN_INTERVALS
                        + " observations, " + stillNeeded() + " more needed";
            }
            if (fixedInterval) {
                return "spawn interval ~" + (medianMs / 1000) + "s (" + intervals
                        + " observations, spread " + (minMs / 1000) + "-" + (maxMs / 1000) + "s)";
            }
            return "spawn is not a fixed interval - observed " + (minMs / 1000) + "-" + (maxMs / 1000)
                    + "s over " + intervals + " observations";
        }
    }

    /**
     * The interval derived from the history. Only consecutive pairs inside one session count, and
     * {@link GhastSighting.Source#CHAT} sightings are preferred outright when there are enough of
     * them: they date the spawn rather than the player's arrival, so mixing the two would let the
     * weaker source blur a figure the stronger one already answers.
     */
    public Estimate estimate() {
        load();
        List<Long> gaps = gaps(GhastSighting.Source.CHAT);
        if (gaps.size() < MIN_INTERVALS) {
            List<Long> all = gaps(null);
            if (all.size() > gaps.size()) {
                gaps = all;
            }
        }
        if (gaps.size() < MIN_INTERVALS) {
            return new Estimate(Certainty.UNKNOWN, gaps.size(), 0, 0, 0, false);
        }
        gaps.sort(Comparator.naturalOrder());
        long median = gaps.get(gaps.size() / 2);
        long min = gaps.get(0);
        long max = gaps.get(gaps.size() - 1);
        boolean fixed = median > 0 && (max - min) / (double) median <= FIXED_SPREAD_LIMIT;
        // ESTIMATED, never CONFIRMED: this is inferred from behaviour with nothing to check it
        // against. Promotion is a human act after watching it happen, per the certainty ladder.
        return new Estimate(Certainty.ESTIMATED, gaps.size(), median, min, max, fixed);
    }

    /** Consecutive same-session gaps, optionally restricted to one source. */
    private List<Long> gaps(GhastSighting.Source only) {
        List<Long> gaps = new ArrayList<>();
        GhastSighting previous = null;
        for (GhastSighting sighting : sightings) {
            if (only != null && sighting.source() != only) {
                continue;
            }
            if (previous != null && previous.session() == sighting.session()) {
                long gap = sighting.at() - previous.at();
                if (gap > 0) {
                    gaps.add(gap);
                }
            }
            previous = sighting;
        }
        return gaps;
    }

    /** How long since the last sighting in THIS session, or {@code -1} when there has been none. */
    public long sinceLastMs() {
        return lastSeenThisSession == 0 ? -1L : System.currentTimeMillis() - lastSeenThisSession;
    }

    /** Every sighting recorded so far, for the settings screen's count. */
    public int sightingCount() {
        load();
        return sightings.size();
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (!Files.exists(SBSFiles.ghastSightingsFile())) {
                return;
            }
            String json = Files.readString(SBSFiles.ghastSightingsFile(), StandardCharsets.UTF_8);
            List<GhastSighting> stored = SBSFiles.GSON.fromJson(json, LIST_TYPE);
            if (stored != null) {
                for (GhastSighting sighting : stored) {
                    if (sighting != null && sighting.at() > 0 && sighting.source() != null) {
                        sightings.add(sighting);
                    }
                }
                sightings.sort(Comparator.comparingLong(GhastSighting::at));
            }
            // Sessions are per-file, so continue past the highest stored one rather than restarting
            // at 1 - otherwise this run's sightings would pair with a previous run's.
            for (GhastSighting sighting : sightings) {
                session = Math.max(session, sighting.session() + 1);
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Ghast] loaded {} sighting(s); {}",
                    sightings.size(), estimateForLog());
        } catch (IOException | JsonSyntaxException e) {
            // A corrupt cache is the expected data-loading case: log at info, keep the empty history
            // and carry on. Losing observations is a nuisance; refusing to start is a bug.
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Ghast] sighting history unreadable, starting empty: {}", e.toString());
        }
    }

    private String estimateForLog() {
        List<Long> gaps = gaps(null);
        return gaps.size() < MIN_INTERVALS
                ? "spawn interval unknown (" + gaps.size() + " of " + MIN_INTERVALS + " observations)"
                : "see next line";
    }

    private void save() {
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            Files.createDirectories(SBSFiles.trackerDir());
            Files.writeString(SBSFiles.ghastSightingsFile(), SBSFiles.GSON.toJson(sightings),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Ghast] could not write sighting history", e);
        }
    }
}
