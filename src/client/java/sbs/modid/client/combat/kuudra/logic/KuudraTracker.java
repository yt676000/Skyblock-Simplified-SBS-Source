/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.kuudra.model.KuudraPhase;
import sbs.modid.client.combat.kuudra.model.KuudraSide;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that answers "what is happening in this Kuudra run right now": whether we are in one
 * at all, which tier, which phase, how long each phase took, where Kuudra is and how much of him is
 * left. Every other class in the module reads from here and decides nothing itself.
 *
 * <p><b>Why a state machine instead of asking the game.</b> Hypixel exposes almost nothing about a
 * Kuudra run through anything queryable. The scoreboard carries the tier and the build percentage and
 * that is it; everything else - which phase, when it started, whether the run is over - only ever
 * appears once, as a line of chat, and is gone. So the run is reconstructed: chat drives the
 * transitions ({@link KuudraPhase}), the tick fills in the two things chat cannot say (the drop into
 * the final phase, and where Kuudra surfaced), and both are thrown away the moment the instance is
 * left.
 *
 * <p><b>Leaving is timed, not instant.</b> The location read lags a moment behind an instance swap
 * and blips empty while the tab list is being re-served, so a single frame that does not say
 * "Kuudra's Hollow" is not a run ending - {@value #LEAVE_GRACE_MS} ms of them is. Without that grace
 * every run ended and restarted itself at least once.
 */
public final class KuudraTracker {

    private static final KuudraTracker INSTANCE = new KuudraTracker();

    /** The island, as both the scoreboard zone and the tab list's Area line spell it. */
    private static final String HOLLOW = "kuudra's hollow";

    /** How long the location has to disagree before the run is considered over. */
    private static final long LEAVE_GRACE_MS = 1_200L;

    /**
     * Below this Y the arena floor is gone and the fight is the final phase. Chat says nothing when
     * that happens; the fall is the only signal, and it is a big one - the platform sits near Y 78.
     */
    private static final double BOSS_FLOOR_Y = 10.0;

    /** How Kuudra himself is told apart from every other magma cube: nothing else is remotely this big. */
    private static final int KUUDRA_CUBE_SIZE = 30;
    private static final float KUUDRA_MIN_MAX_HEALTH = 10_000f;

    /** How far out to look for him. The arena is wide and he surfaces at its far edge. */
    private static final double BOSS_SCAN_RADIUS = 160.0;

    /** The tier as the sidebar writes it: "Kuudra's Hollow (T4)". */
    private static final Pattern TIER = Pattern.compile("\\(T([1-5])\\)");

    /** The build objective, which doubles as the ballista progress: "Protect Elle (43%)". */
    private static final Pattern PROTECT_ELLE = Pattern.compile("Protect Elle\\s*\\((\\d+)%\\)");

    private volatile boolean inHollow;
    private volatile KuudraPhase phase = KuudraPhase.NONE;
    private volatile int tier;
    private volatile int buildPercent;
    private volatile KuudraSide side = KuudraSide.UNKNOWN;
    private volatile MagmaCube boss;

    private volatile long runStart;
    private volatile long phaseStart;
    private volatile long runEnd;
    private volatile long lastSeenHollow;

    /** How long each phase lasted, in ms. Written once when the phase is left. */
    private final Map<KuudraPhase, Long> splits = new EnumMap<>(KuudraPhase.class);

    private KuudraTracker() {
    }

    public static KuudraTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.KuudraSettings cfg() {
        return ConfigManager.getInstance().get().kuudra;
    }

    // ------------------------------------------------------------------ what everyone else reads

    /** Whether the player is inside Kuudra's Hollow. True in the lobby part of it too. */
    public boolean inHollow() {
        return inHollow;
    }

    /** Whether a run is live - i.e. inside the Hollow <i>and</i> past Elle's opening line. */
    public boolean running() {
        return inHollow && phase.inRun();
    }

    public KuudraPhase phase() {
        return phase;
    }

    /** 1-5, or {@code 0} while the sidebar has not been read. */
    public int tier() {
        return tier;
    }

    /** Ballista completion, 0-100. Only meaningful during {@link KuudraPhase#BUILD}. */
    public int buildPercent() {
        return buildPercent;
    }

    public KuudraSide side() {
        return side;
    }

    /** Kuudra's entity, or {@code null} while he is not loaded (which is most of a run). */
    public MagmaCube boss() {
        return boss;
    }

    /** Milliseconds since the run started, or {@code 0} outside one. Frozen once the run ends. */
    public long runMs() {
        if (runStart == 0) {
            return 0;
        }
        return (runEnd > 0 ? runEnd : System.currentTimeMillis()) - runStart;
    }

    /** Milliseconds spent in the current phase. */
    public long phaseMs() {
        return phaseStart == 0 ? 0 : System.currentTimeMillis() - phaseStart;
    }

    /** The recorded length of a finished phase in ms, or {@code -1} when it has not finished. */
    public long split(KuudraPhase of) {
        return splits.getOrDefault(of, -1L);
    }

    /** {@code m:ss.S} - the format every split and clock in the module is written in. */
    public static String clock(long ms) {
        if (ms < 0) {
            return "-";
        }
        long tenths = ms / 100;
        return String.format(Locale.US, "%d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10);
    }

    // ------------------------------------------------------------------ the tick

    /** Called once per client tick from the shared tick hook. Cheap and self-gating. */
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        // Switching the module off mid-run has to close the run down, not freeze it: leaving the
        // state behind would have the card and the markers reappear on the next toggle, describing
        // a run that finished an hour ago.
        if (player == null || !cfg().enabled) {
            if (inHollow) {
                endRun(player == null ? "left the world" : "module switched off");
            }
            return;
        }
        updateLocation();
        if (!inHollow) {
            return;
        }
        readSidebar();
        if (!phase.inRun()) {
            return;
        }
        // The drop into the final phase. Only from the two phases that can precede it, so a pearl
        // that goes badly wrong during supplies cannot fake a boss phase.
        if ((phase == KuudraPhase.SKIP || phase == KuudraPhase.DPS)
                && player.position().y < BOSS_FLOOR_Y) {
            setPhase(KuudraPhase.BOSS, "fell to the arena floor");
        }
        if (phase.bossUp()) {
            trackBoss(minecraft.level, player);
        }
        // The rest of the module ticks from here rather than from the shared tick hook: they are all
        // meaningless outside a run, and hanging them off the one place that already knows whether
        // there is a run keeps four dead calls per tick out of everyone else's game.
        SupplyTracker.getInstance().onClientTick();
        FreshTracker.getInstance().onClientTick();
        DangerZone.getInstance().onClientTick();
    }

    /**
     * Whether we are in the Hollow, with the leave debounced. The scoreboard zone is checked as well
     * as the island because the two are served by different Hypixel widgets and one of them is
     * usually populated a moment before the other.
     */
    private void updateLocation() {
        boolean here = SkyBlockLocation.island().toLowerCase(Locale.ROOT).contains(HOLLOW)
                || SkyBlockLocation.zone().toLowerCase(Locale.ROOT).contains(HOLLOW);
        long now = System.currentTimeMillis();
        if (here) {
            lastSeenHollow = now;
            inHollow = true;
            return;
        }
        if (inHollow && now - lastSeenHollow > LEAVE_GRACE_MS) {
            endRun("left the Hollow");
        }
    }

    /** Tier and the build percentage, both straight off the sidebar. */
    private void readSidebar() {
        for (String line : SkyBlockLocation.sidebarLines()) {
            Matcher tierMatch = TIER.matcher(line);
            if (tierMatch.find()) {
                tier = Integer.parseInt(tierMatch.group(1));
            }
            Matcher build = PROTECT_ELLE.matcher(line);
            if (build.find()) {
                buildPercent = Integer.parseInt(build.group(1));
            }
        }
    }

    /**
     * Finds Kuudra and reads which side he is on.
     *
     * <p>Identified by shape rather than by name: he is a magma cube of size {@value #KUUDRA_CUBE_SIZE}
     * with a five-figure health pool, and nothing else in the instance is either of those things. The
     * nametag would have worked too but it is an armor stand somewhere above him, which is one more
     * thing to get wrong for no gain.
     */
    private void trackBoss(ClientLevel level, LocalPlayer player) {
        if (level == null) {
            return;
        }
        MagmaCube found = null;
        AABB area = player.getBoundingBox().inflate(BOSS_SCAN_RADIUS);
        for (MagmaCube cube : level.getEntitiesOfClass(MagmaCube.class, area, KuudraTracker::isKuudra)) {
            found = cube;
            break;
        }
        boss = found;
        if (found == null) {
            return;
        }
        KuudraSide now = KuudraSide.of(found.position());
        if (now != KuudraSide.UNKNOWN && now != side) {
            side = now;
            log("side -> {} at {}", now.displayName(), describe(found.position()));
        }
    }

    private static boolean isKuudra(MagmaCube cube) {
        return cube.isAlive()
                && cube.getSize() == KUUDRA_CUBE_SIZE
                && cube.getMaxHealth() >= KUUDRA_MIN_MAX_HEALTH;
    }

    // ------------------------------------------------------------------ chat

    /**
     * One colour-stripped chat line. Registered through the shared chat registry rather than called
     * directly, so this is only reached for lines that could matter.
     */
    public void onChat(String text) {
        // The instance transfer lines fire on the way in AND on the way out. Either way the run that
        // was is over, so they are handled before anything else can re-open one.
        if (text.contains("Sending to server") || text.startsWith("Starting in ")) {
            endRun("instance transfer");
            return;
        }
        if (!inHollow) {
            return;
        }
        if (text.equals("DEFEAT") && phase.bossUp()) {
            endRun("defeat");
            return;
        }
        KuudraPhase spoken = KuudraPhase.fromChat(text);
        if (spoken != null) {
            if (spoken == KuudraPhase.SUPPLIES && !phase.inRun()) {
                startRun();
            }
            setPhase(spoken, "chat");
            if (spoken == KuudraPhase.DOWN) {
                runEnd = System.currentTimeMillis();
            }
            return;
        }
        // Two phases whose lines name a player instead of being fixed text.
        if (text.contains("has been eaten by Kuudra!") && !text.contains("Elle")) {
            setPhase(KuudraPhase.STUN, "someone was eaten");
        } else if (text.contains("destroyed one of Kuudra's pods!")) {
            setPhase(KuudraPhase.DPS, "a pod went down");
        }
    }

    // ------------------------------------------------------------------ run lifecycle

    private void startRun() {
        long now = System.currentTimeMillis();
        runStart = now;
        runEnd = 0;
        splits.clear();
        side = KuudraSide.UNKNOWN;
        boss = null;
        buildPercent = 0;
        SupplyTracker.getInstance().onRunStart();
        FreshTracker.getInstance().reset();
        log("run started (T{})", tier);
    }

    /**
     * Records the split for the phase being left and moves on. Re-entering the phase you are already
     * in is ignored: Elle repeats herself, and a repeat would otherwise reset that phase's clock.
     */
    private void setPhase(KuudraPhase next, String why) {
        if (next == phase) {
            return;
        }
        long now = System.currentTimeMillis();
        if (phase.inRun() && phaseStart > 0) {
            splits.put(phase, now - phaseStart);
        }
        KuudraPhase previous = phase;
        phase = next;
        phaseStart = now;
        log("phase {} -> {} ({})", previous.displayName(), next.displayName(), why);
        KuudraEvents.getInstance().onPhaseChanged(previous, next);
    }

    /** Drops everything about the run. Safe to call when there is nothing to end. */
    private void endRun(String why) {
        boolean had = phase.inRun() || inHollow;
        inHollow = false;
        phase = KuudraPhase.NONE;
        phaseStart = 0;
        runStart = 0;
        runEnd = 0;
        tier = 0;
        buildPercent = 0;
        side = KuudraSide.UNKNOWN;
        boss = null;
        splits.clear();
        SupplyTracker.getInstance().reset();
        FreshTracker.getInstance().reset();
        KuudraEvents.getInstance().onRunEnd();
        if (had) {
            log("run ended ({})", why);
        }
    }

    // ------------------------------------------------------------------ tuning log

    /**
     * The module's tuning log. Several of the detections here are read off Hypixel messages that can
     * be re-worded without notice, so every state change is written out under one prefix and the
     * whole state machine can be checked against a real run by reading it back.
     *
     * <p><b>Deliberately not rate limited.</b> Every caller is on a state change - a phase turning
     * over, a crate landing, a call being made - so there is nothing here that can flood, and a log
     * that quietly drops lines is worse than no log when the thing being chased is a transition that
     * happened once.
     */
    void log(String format, Object... args) {
        if (cfg().debugLog) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Kuudra] " + format, args);
        }
    }

    private static String describe(Vec3 pos) {
        return String.format(Locale.US, "%.0f,%.0f,%.0f", pos.x, pos.y, pos.z);
    }
}
