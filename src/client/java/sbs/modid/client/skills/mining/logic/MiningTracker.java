/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.mining.model.Commission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Dwarven Mines / Crystal Hollows tab widget: the active commissions and their progress,
 * the Heart of the Mountain tier, and the powder counters.
 *
 * <p><b>Why the tab list.</b> Hypixel publishes all of this as widget lines already, so there is
 * nothing to infer from the world and nothing to keep in step across a relog – whatever the tab says
 * right now IS the state. The parsing is deliberately shape-based rather than a list of known
 * commission names: commissions are added and renamed every mining update, and a name list would
 * quietly stop showing the new ones.
 *
 * <p><b>Rates</b> are measured, not read: powder totals are absolute, so the session's gain is the
 * current total minus the total first seen this session, and the per-hour figure divides that by how
 * long the session has actually been running.
 *
 * <p>The line shapes below are a best guess against a live server and can be tuned from the throttled
 * {@code [SBS][Mining]} log, which prints the widget block it decided was the commission section.
 */
public final class MiningTracker {

    private static final MiningTracker INSTANCE = new MiningTracker();

    /** The tab list is re-read at this cadence – it changes on a server tick, not on a frame. */
    private static final long SCAN_INTERVAL_MS = 500L;
    /** The card hides itself this long after the last commission line was served. */
    private static final long STALE_MS = 15_000L;

    /** "Lava Springs Mithril: 45%" / "Goblin Slayer: 12.5%". */
    private static final Pattern COMMISSION_PERCENT =
            Pattern.compile("^(.{2,48}?):\\s*(\\d{1,3}(?:\\.\\d+)?)%$");
    /** "Goblin Slayer: DONE" – Hypixel words a finished commission, it does not print 100%. */
    private static final Pattern COMMISSION_DONE =
            Pattern.compile("^(.{2,48}?):\\s*(DONE|COMPLETE[D]?)$", Pattern.CASE_INSENSITIVE);
    /**
     * The powder rows as the widget actually serves them: a "{@code Powders:}" header followed by
     * bare "{@code Mithril: 8,422}" lines. Only used while inside that section, so any name is
     * accepted - a powder type added later shows up without a code change.
     *
     * <p>This is what was broken: the only pattern used to be {@link #POWDER_LEGACY} below, which
     * demands the word "Powder" on the value line. The widget does not put it there, so nothing ever
     * matched and both the powder and Heart of the Mountain cards stayed permanently empty.
     */
    private static final Pattern POWDER_ENTRY =
            Pattern.compile("^(.{2,24}?):\\s*([\\d,.]+)$");

    /** "Mithril Powder: 1,234" - the older one-line spelling, matched anywhere for safety. */
    private static final Pattern POWDER_LEGACY =
            Pattern.compile("^(Mithril|Gemstone|Glacite)\\s+Powder:\\s*([\\d,.]+)$",
                    Pattern.CASE_INSENSITIVE);
    /** "Heart of the Mountain: Tier 7" / "HotM: Tier 7". */
    private static final Pattern HOTM_TIER = Pattern.compile(
            "^(?:Heart of the Mountain|HotM)(?:\\s+Tier)?:\\s*(?:Tier\\s*)?(\\d{1,2})$",
            Pattern.CASE_INSENSITIVE);

    /** Widget headers that end whatever block was open – anything that is plainly a new section. */
    private static final List<String> SECTION_HEADERS = List.of(
            "powder", "profile", "area", "server", "players", "forge", "pets", "collection",
            "skills", "essence", "dungeon", "event", "jacob", "pests", "upgrades", "info");

    /** The section the reader is currently inside; see {@link #sectionOf}. */
    private static final String SECTION_NONE = "";
    private static final String SECTION_POWDERS = "powders";
    private static final String SECTION_COMMISSIONS = "commissions";

    // ---- live state, rebuilt from the tab list ----
    private volatile List<Commission> commissions = List.of();
    private volatile int hotmTier;
    /** Powder totals as the tab last reported them, by powder name. */
    private final Map<String, Long> powder = new LinkedHashMap<>();
    /** The totals first seen this session, so the gain is a difference rather than a guess. */
    private final Map<String, Long> powderBaseline = new LinkedHashMap<>();

    private long sessionStartMs;
    private long lastSeenMs;
    /** When the tab last served ANY mining line – what says the mining session is still going. */
    private long lastTabSeenMs;
    private long lastScanAt;
    private long lastLogAt;

    /** Completion state of the last scan, so the alert fires on the transition and not every scan. */
    private final Map<String, Boolean> wasDone = new LinkedHashMap<>();

    private MiningTracker() {
    }

    public static MiningTracker getInstance() {
        return INSTANCE;
    }

    private static MiningHelpersSettings cfg() {
        return ConfigManager.getInstance().get().miningHelpers;
    }

    // ------------------------------------------------------------------ tick

    /** Called every client tick (throttled internally). */
    public void onClientTick() {
        MiningHelpersSettings settings = cfg();
        if (!settings.enabled || Minecraft.getInstance().player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        if (!SkillIslands.miningAllowed()) {
            return;   // keep the last numbers; they are still the truth about your mining session
        }
        readTab(now, settings);
    }

    /**
     * Reads the widget one section at a time.
     *
     * <p>The widget is a list of headers ("{@code Forges:}", "{@code Powders:}",
     * "{@code Commissions:}") each followed by their own rows, and a row only means anything in the
     * light of the header above it - "{@code Mithril: 8,422}" is a powder total, while
     * "{@code Rampart's Quarry Mithril: 0%}" is a commission. Tracking the current section is what
     * lets both be read without one pattern having to be clever enough to exclude the other.
     */
    /** Everything one tab snapshot yielded. Plain data, so {@link #scan} can be tested on its own. */
    record Scan(List<Commission> commissions, Map<String, Long> powders, int hotmTier) {
    }

    /**
     * Pure line scan - no clock, no instance state, no game. This is deliberately separate from
     * {@link #readTab}: the widget's wording is the part that breaks, and keeping the parse callable
     * with a plain list of strings is what lets it be checked against real widget text rather than by
     * reading it and hoping.
     */
    static Scan scan(List<String> lines) {
        List<Commission> found = new ArrayList<>(6);
        Map<String, Long> powders = new LinkedHashMap<>();
        int tier = 0;
        String section = SECTION_NONE;
        for (String line : lines) {
            String plain = line.trim();
            String header = sectionOf(plain);
            if (header != null) {
                section = header;
                continue;
            }
            Matcher legacy = POWDER_LEGACY.matcher(plain);
            if (legacy.matches()) {
                powders.put(capitalise(legacy.group(1)), parseAmount(legacy.group(2)));
                continue;
            }
            Matcher hotm = HOTM_TIER.matcher(plain);
            if (hotm.matches()) {
                tier = parseInt(hotm.group(1));
                continue;
            }
            if (SECTION_POWDERS.equals(section)) {
                Matcher entry = POWDER_ENTRY.matcher(plain);
                if (entry.matches()) {
                    powders.put(capitalise(entry.group(1)), parseAmount(entry.group(2)));
                }
                continue;
            }
            if (SECTION_COMMISSIONS.equals(section)) {
                Commission commission = parseCommission(plain);
                if (commission != null) {
                    found.add(commission);
                }
            }
        }
        return new Scan(found, powders, tier);
    }

    private void readTab(long now, MiningHelpersSettings settings) {
        List<String> lines = TabWidgets.lines();
        if (lines.isEmpty()) {
            return;
        }
        Scan result = scan(lines);
        List<Commission> found = result.commissions();

        for (Map.Entry<String, Long> entry : result.powders().entrySet()) {
            notePowder(entry.getKey(), entry.getValue(), now);
            lastTabSeenMs = now;
        }
        if (result.hotmTier() > 0) {
            hotmTier = result.hotmTier();
            lastTabSeenMs = now;
        }
        if (!found.isEmpty()) {
            commissions = List.copyOf(found);
            lastSeenMs = now;
            if (settings.commissionDoneAlert) {
                alertNewlyDone(found);
            }
        }
        log(now, lines, found);
    }

    /**
     * The section a line opens, or {@code null} when it is an ordinary row.
     *
     * <p>A header has to be the section word <i>alone</i> (bar a trailing colon), not merely start
     * with it. Matching on a prefix is what let a commission whose name happens to begin with a
     * section word - a "Powder Ghast" hunt, a "Forge" errand - silently close the commission block
     * and swallow every row under it.
     */
    private static String sectionOf(String line) {
        String lower = line.toLowerCase(Locale.ROOT).replace(":", "").trim();
        if (lower.isEmpty()) {
            return null;
        }
        if (lower.equals("commissions") || lower.equals("commission")) {
            return SECTION_COMMISSIONS;
        }
        if (lower.equals("powders") || lower.equals("powder")) {
            return SECTION_POWDERS;
        }
        for (String header : SECTION_HEADERS) {
            if (lower.equals(header) || lower.equals(header + "s")) {
                return SECTION_NONE;   // some other widget section: end whatever block we were in
            }
        }
        return null;
    }

    private static Commission parseCommission(String line) {
        Matcher done = COMMISSION_DONE.matcher(line);
        if (done.matches()) {
            return new Commission(done.group(1).trim(), 100.0, true);
        }
        Matcher percent = COMMISSION_PERCENT.matcher(line);
        if (percent.matches()) {
            return new Commission(percent.group(1).trim(), parseDouble(percent.group(2)), false);
        }
        return null;
    }

    /** Chat + ping the first scan a commission reads as finished, never again while it stays that way. */
    private void alertNewlyDone(List<Commission> found) {
        for (Commission commission : found) {
            boolean previously = Boolean.TRUE.equals(wasDone.get(commission.name()));
            wasDone.put(commission.name(), commission.done());
            if (commission.done() && !previously) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.sendOverlayMessage(
                            Component.literal("§aCommission done: §f" + commission.name()));
                    mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 1.5f);
                }
            }
        }
    }

    private void notePowder(String kind, long amount, long now) {
        if (amount < 0) {
            return;
        }
        String name = capitalise(kind);
        powder.put(name, amount);
        // The first reading of a session is the zero point, not a gain of everything you own.
        powderBaseline.putIfAbsent(name, amount);
        if (sessionStartMs == 0) {
            sessionStartMs = now;
        }
        // A total that dropped means a different profile (or a spend), so the baseline follows it
        // down rather than reporting a negative session gain forever.
        if (amount < powderBaseline.get(name)) {
            powderBaseline.put(name, amount);
        }
    }

    // ------------------------------------------------------------------ read-only state

    /** Whether the commission card has anything current to draw. */
    public boolean commissionsFresh() {
        return !commissions.isEmpty() && System.currentTimeMillis() - lastSeenMs < STALE_MS;
    }

    /** Whether the Heart of the Mountain card has anything current to draw - i.e. a known tier. */
    public boolean hotmFresh() {
        return hotmTier > 0 && System.currentTimeMillis() - lastTabSeenMs < STALE_MS;
    }

    /**
     * Whether the powder card has anything current to draw. The powder totals are kept once read –
     * the session's gain is measured against them – so "do we know a total" is true for the rest of
     * the session and is a reason to draw the card, never a reason to stop. The tab serving those
     * lines is what says you are still mining, so that is what the card follows.
     */
    public boolean powderFresh() {
        return !powder.isEmpty() && System.currentTimeMillis() - lastTabSeenMs < STALE_MS;
    }

    public List<Commission> commissions() {
        return commissions;
    }

    /** The Heart of the Mountain tier the tab reported, or {@code 0} while it has not said. */
    public int hotmTier() {
        return hotmTier;
    }

    /** The powder totals as last read, in the order the tab served them. */
    public Map<String, Long> powder() {
        return powder;
    }

    /** How much of one powder has been gained since this session's first reading. */
    public long powderGained(String name) {
        Long total = powder.get(name);
        Long base = powderBaseline.get(name);
        return total == null || base == null ? 0L : Math.max(0L, total - base);
    }

    /** The measured per-hour rate of one powder, or {@code 0} before a minute has passed. */
    public long powderPerHour(String name) {
        long elapsed = sessionStartMs == 0 ? 0 : System.currentTimeMillis() - sessionStartMs;
        if (elapsed < 60_000L) {
            return 0L;   // too short a sample to state a rate with a straight face
        }
        return Math.round(powderGained(name) * 3_600_000.0 / elapsed);
    }

    /** Restarts the session gain / rate measurement without touching the live totals. */
    public void resetSession() {
        powderBaseline.clear();
        powderBaseline.putAll(powder);
        sessionStartMs = System.currentTimeMillis();
    }

    /**
     * World change, server hop or profile switch: drop everything read from the previous instance.
     *
     * <p>Called from the shared world-change edge, alongside the storage index and the Rift's visit
     * clock. Everything here was read from one server's tab list and describes that server only, so
     * none of it may outlive it - commissions belong to the old instance, and the powder totals
     * belong to whichever profile was loaded.
     *
     * <p><b>Why the whole baseline goes, not just the totals.</b> {@link #notePowder} follows a total
     * that dropped, which covers spending powder and covers switching to a profile with less of it.
     * It cannot cover the opposite: switch to a profile with <i>more</i> powder and the old, smaller
     * baseline stays, so the difference between two profiles is reported as this session's gain and
     * the per-hour rate is inflated by it. Clearing here is what makes the first reading after a
     * boundary a zero point again rather than a comparison across profiles.
     */
    public void onWorldChange() {
        commissions = List.of();
        hotmTier = 0;
        powder.clear();
        powderBaseline.clear();
        wasDone.clear();
        sessionStartMs = 0L;
        lastSeenMs = 0L;
        lastTabSeenMs = 0L;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * One line every 30s naming the commission block that was found, or the whole widget when none
     * was. The tab layout is the one thing here that Hypixel can change under us, so this is what
     * turns "the card is empty" into a fixable observation instead of a guess.
     */
    private void log(long now, List<String> lines, List<Commission> found) {
        if (now - lastLogAt < 30_000L) {
            return;
        }
        lastLogAt = now;
        // Dump the raw widget whenever ANY of the three came back empty, not just the commissions.
        // Only the commission case used to be logged, which is precisely why the powder and HotM
        // cards could stay blank indefinitely without leaving a trace of why.
        if (found.isEmpty() || powder.isEmpty() || hotmTier == 0) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Mining] incomplete parse (commissions={} powder={} hotmTier={}) "
                            + "- tab widget lines: {}",
                    found.size(), powder.size(), hotmTier, lines);
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Mining] commissions={} hotmTier={} powder={}",
                found, hotmTier, powder);
    }

    private static String capitalise(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static long parseAmount(String raw) {
        try {
            return Long.parseLong(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
