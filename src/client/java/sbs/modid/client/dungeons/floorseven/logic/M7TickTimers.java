/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.helper.timers.ServerTickClock;
import sbs.modid.client.ui.hud.logic.ServerStatsTracker;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.Locale;

/**
 * M7 Tick Timers: a countdown to the next Storm pad crush in P2 and the next Goldor tick in P3,
 * counted on the server-tick clock ({@link ServerTickClock}) so a slow server does not throw the
 * prediction off. Display only - it never acts.
 *
 * <p><b>Certainty.</b> Both cycles are ESTIMATED: that Storm's pads crush on a fixed tick cycle and
 * that Goldor ticks every {@value #GOLDOR_PERIOD_TICKS} server ticks. Both anchors are UNVERIFIED -
 * each cycle is assumed to start at its phase's first boss line - and the pad period is not known at
 * all, so it is a setting that defaults to 0 (shown as "?"). {@link M7TickProbe} logs what is needed
 * to settle all three. The confirmed "Storm is enraged!" line re-anchors the pad cycle and logs the
 * drift; whether it marks a crush is itself unverified, and the drift column is what will say.
 *
 * <p>The display model is rebuilt once per client tick; the HUD only draws it.
 */
public final class M7TickTimers {

    /** ESTIMATED: Goldor's tick, in server ticks. */
    public static final int GOLDOR_PERIOD_TICKS = 60;

    /** The countdown starts fading towards the "soon" colour this many seconds before a firing. */
    static final double SOON_SECONDS = 1.0;

    private static final M7TickTimers INSTANCE = new M7TickTimers();

    private final TickTimer pad = new TickTimer(0);
    private final TickTimer goldor = new TickTimer(GOLDOR_PERIOD_TICKS);

    private FloorSevenPhaseTimer.Phase phase;
    private boolean gateOpen;

    // Display model, written per tick on the main thread and read by the HUD on the same thread.
    private Component line;
    private int lineColor;
    private int lineWidth;
    private Component centre;
    private int centreColor;

    // Parsed colour cache: re-parsed only when the config string changes.
    private String colorHex;
    private String soonHex;
    private int color = SBSTheme.TEXT;
    private boolean colorCustom;
    private int soon = SBSTheme.WARN;

    private M7TickTimers() {
        ChatPatternRegistry.getInstance().register(M7TickProbe.STORM_ENRAGED, matcher -> onStormEnraged(),
                "m7 tick timers: storm enraged");
    }

    public static M7TickTimers getInstance() {
        return INSTANCE;
    }

    // ---- read by the HUD -------------------------------------------------------------------------

    /** The card line ("Pad: 0.85s"), or {@code null} when nothing is to be shown. */
    public Component line() {
        return line;
    }

    /** Pixel width of {@link #line()}, measured when it was built. */
    public int lineWidth() {
        return lineWidth;
    }

    public int lineColor() {
        return lineColor;
    }

    /** The big centred countdown, or {@code null} outside the last second / when switched off. */
    public Component centre() {
        return centre;
    }

    public int centreColor() {
        return centreColor;
    }

    // ---- tick ----------------------------------------------------------------------------------

    /** Per Catacombs client tick. */
    public void onClientTick() {
        SBSConfig.DungeonsSettings cfg = ConfigManager.getInstance().get().dungeons;
        boolean open = cfg.m7TickTimers && onFloor(cfg);
        if (open != gateOpen) {
            gateOpen = open;
            if (!open) {
                reset();
            }
        }
        if (!open) {
            return;
        }
        double now = ServerTickClock.getInstance().now();
        FloorSevenPhaseTimer.Phase current = FloorSevenPhaseTimer.getInstance().current();
        if (current != phase) {
            enter(current, now);
        }
        pad.setPeriod(cfg.m7PadPeriodTicks);
        refreshColors(cfg);
        build(cfg, now, ServerStatsTracker.getInstance().tps());
        lineWidth = line == null ? 0 : Minecraft.getInstance().font.width(line);
    }

    public void onWorldChange() {
        gateOpen = false;
        reset();
    }

    private void reset() {
        phase = null;
        pad.clear();
        goldor.clear();
        line = null;
        centre = null;
    }

    /** Phase change: the cycle of the phase entered is anchored at its start (UNVERIFIED anchor). */
    private void enter(FloorSevenPhaseTimer.Phase next, double now) {
        phase = next;
        pad.clear();
        goldor.clear();
        if (next == FloorSevenPhaseTimer.Phase.STORM) {
            pad.anchor(now);
            log(now, "pad cycle anchored at Storm phase start (anchor unverified)");
        } else if (next == FloorSevenPhaseTimer.Phase.GOLDOR) {
            goldor.anchor(now);
            log(now, "goldor cycle anchored at Goldor phase start (anchor unverified)");
        }
    }

    private void onStormEnraged() {
        if (!gateOpen || phase != FloorSevenPhaseTimer.Phase.STORM) {
            return;
        }
        double now = ServerTickClock.getInstance().now();
        if (now < 0) {
            return;
        }
        double drift = pad.resync(now);
        log(now, Double.isNaN(drift) ? "storm enraged: pad cycle re-anchored"
                : String.format(Locale.ROOT, "storm enraged: pad cycle re-anchored, drift %.1f ticks", drift));
    }

    private void build(SBSConfig.DungeonsSettings cfg, double now, double tps) {
        TickTimer timer;
        String label;
        if (phase == FloorSevenPhaseTimer.Phase.STORM && cfg.m7TickTimerPad) {
            timer = pad;
            label = "Pad";
        } else if (phase == FloorSevenPhaseTimer.Phase.GOLDOR && cfg.m7TickTimerGoldor) {
            timer = goldor;
            label = "Goldor";
        } else {
            line = null;
            centre = null;
            return;
        }
        double ticks = timer.ticksUntilNext(now);
        if (ticks < 0) {
            line = Component.literal(label + ": ?  §8" + (timer.period() <= 0 ? "period unknown" : "waiting"));
            lineColor = color;
            centre = null;
            return;
        }
        double seconds = TickTimer.seconds(ticks, tps);
        int shade = shade(color, soon, seconds);
        line = Component.literal(String.format(Locale.ROOT, "%s: %.2fs  §8est.", label, seconds));
        lineColor = shade;
        if (cfg.m7TickTimerCentre && seconds <= SOON_SECONDS) {
            centre = Component.literal(String.format(Locale.ROOT, "%.1f", seconds));
            centreColor = shade;
        } else {
            centre = null;
        }
    }

    /** {@code base} until {@link #SOON_SECONDS} before the firing, then a linear blend into {@code soon}. */
    static int shade(int base, int soon, double seconds) {
        if (seconds >= SOON_SECONDS) {
            return base;
        }
        double t = 1.0 - Math.max(0.0, seconds) / SOON_SECONDS;
        int r = mix(base >> 16 & 0xFF, soon >> 16 & 0xFF, t);
        int g = mix(base >> 8 & 0xFF, soon >> 8 & 0xFF, t);
        int b = mix(base & 0xFF, soon & 0xFF, t);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int mix(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    private void refreshColors(SBSConfig.DungeonsSettings cfg) {
        if (!cfg.m7TickTimerColorHex.equals(colorHex)) {
            colorHex = cfg.m7TickTimerColorHex;
            Integer parsed = OverlayColor.parseHex(colorHex);
            colorCustom = parsed != null;
            color = parsed == null ? SBSTheme.TEXT : 0xFF000000 | parsed;
        } else if (!colorCustom) {
            color = SBSTheme.TEXT; // unset follows the theme, which can change at any time
        }
        if (!cfg.m7TickTimerSoonColorHex.equals(soonHex)) {
            soonHex = cfg.m7TickTimerSoonColorHex;
            Integer parsed = OverlayColor.parseHex(soonHex);
            soon = parsed == null ? SBSTheme.WARN : 0xFF000000 | parsed;
        }
    }

    /** M7 always; F7 only with "also on F7". */
    private static boolean onFloor(SBSConfig.DungeonsSettings cfg) {
        DungeonStateManager state = DungeonStateManager.getInstance();
        if (!state.inDungeon() || state.floorNumber() != 7) {
            return false;
        }
        char type = state.floorType();
        return type == 'M' || (type == 'F' && cfg.m7TickTimersOnF7);
    }

    private static void log(double now, String event) {
        SkyblockSimplifiedSBS.LOGGER.info(M7TickProbe.line(now, ServerStatsTracker.getInstance().tps(), event));
    }
}
