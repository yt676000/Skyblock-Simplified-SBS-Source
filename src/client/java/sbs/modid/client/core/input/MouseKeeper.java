/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.input;

import sbs.modid.client.core.config.ConfigManager;

/**
 * State behind "Keep Mouse Position": the cursor position on the menu the player was last looking
 * at, tracked continuously while a menu is open, plus the teleport guard that puts the cursor back
 * when a menu transition centres it.
 *
 * <p><b>Why a guard instead of a nicer release.</b> The centring happens inside the un-grab: on
 * Windows, leaving the grabbed cursor mode makes GLFW place the real cursor wherever it decides to
 * restore to, and the position the game passes into the release is only applied to the grabbed-mode
 * virtual cursor – it never reaches the screen. So no matter what coordinates {@code releaseMouse}
 * is fed, the OS cursor can still come out centred. The only approach that holds is to watch what
 * actually arrives: for a short window after every menu open, a reported position that is EXACTLY
 * the window centre while the kept position is somewhere else cannot be the player's hand – it is
 * the transition's teleport, and it is snapped back where the cursor belongs.
 *
 * <p>The guard also refuses to learn the teleport: the old version tracked every move into the kept
 * position, so the first centring overwrote the kept position with the centre and every later
 * restore faithfully "restored" the cursor to the middle of the screen. A guarded centre report
 * never reaches {@link #track}.
 */
public final class MouseKeeper {

    /** How long after a menu open the centre teleport is still swallowed. */
    private static final long GUARD_MS = 500L;
    /** How close (px) to the exact centre a report has to be to count as the teleport. */
    private static final double CENTER_TOLERANCE = 2.0;
    /**
     * The guard stands down when the kept position is itself this close to the centre – there a
     * teleport and the player's own cursor cannot be told apart, and there is also nothing to fix.
     */
    private static final double KEEP_MIN_DISTANCE = 25.0;

    /**
     * How far from the last real position a single report may land before it is read as a teleport
     * rather than as a hand, in pixels.
     *
     * <p><b>This is the rule that was missing.</b> The guard originally only recognised a teleport
     * that arrived <i>exactly</i> on the window centre — while this class's own note says GLFW
     * "place[s] the real cursor wherever it decides to restore to". Those two statements cannot both
     * be acted on: a restore to anywhere other than the centre passed the guard untouched and the
     * cursor stayed flung, which is precisely the intermittent recurrence. Destination was the wrong
     * thing to match on; <b>discontinuity</b> is the property a teleport actually has.
     *
     * <p>160 px is chosen to sit far above a hand and far below a fling. {@link #track} updates the
     * kept position on every non-grabbed move, so consecutive reports are single mouse events apart —
     * at a 125 Hz polling rate this threshold means 20,000 px/s, which no flick reaches, while the
     * teleport it is meant to catch crosses most of the screen. Unverified in game: the miss report
     * in {@code KeepMousePositionMixin} is what would show it firing on a real hand.
     */
    private static final double JUMP_DISTANCE = 160.0;

    private static double keptX = -1;
    private static double keptY = -1;
    private static long guardUntil;
    private static double guardCx;
    private static double guardCy;

    /**
     * Whether the armed window ever corrected anything. False while a guard is open and unfired,
     * which is the state {@link #pendingMiss()} turns into the one log line worth having.
     */
    private static boolean guardFired = true;

    private MouseKeeper() {
    }

    static boolean enabled() {
        return ConfigManager.getInstance().get().convenience.keepMousePosition;
    }

    /**
     * Records the real cursor position, called for every raw move. Ignored only while the mouse is
     * grabbed (mouselook / gameplay – there is no menu cursor to keep); when it is not grabbed a menu
     * is open, so this is always the last place the cursor sat on a menu.
     *
     * <p>It deliberately does NOT gate on {@code GuiStateManager} having a screen: Hypixel's
     * server-opened container menus (backpacks, storages) don't always go through the
     * {@code setScreenAndShow} hook that feeds {@code GuiStateManager}, so that gate silently dropped
     * every move on those menus – the kept position never updated and the cursor kept centring on the
     * next backpack. The grabbed flag alone is the authoritative "is a menu cursor showing" signal.
     */
    public static void track(double x, double y, boolean grabbed) {
        if (grabbed || !enabled()) {
            return;
        }
        keptX = x;
        keptY = y;
    }

    /** Whether there is a kept position worth restoring to (and the feature is on). */
    public static boolean hasKept() {
        return enabled() && keptX > 0 && keptY > 0;
    }

    public static double keptX() {
        return keptX;
    }

    public static double keptY() {
        return keptY;
    }

    /**
     * Arms the teleport guard – called on every menu open, with the window centre the transition
     * would teleport to. Re-arming while already armed just extends the window, which is exactly
     * right for Hypixel's rapid close-open bursts.
     */
    public static void armGuard(double centerX, double centerY) {
        if (!hasKept()) {
            return;
        }
        double dx = keptX - centerX;
        double dy = keptY - centerY;
        if (dx * dx + dy * dy < KEEP_MIN_DISTANCE * KEEP_MIN_DISTANCE) {
            return;
        }
        guardCx = centerX;
        guardCy = centerY;
        guardUntil = System.currentTimeMillis() + GUARD_MS;
        guardFired = false;
    }

    /** Where the transition was expected to teleport to, for the miss report. */
    public static double guardCenterX() {
        return guardCx;
    }

    public static double guardCenterY() {
        return guardCy;
    }

    /** Whether the teleport guard is armed right now – the cheap pre-check for the per-frame read. */
    public static boolean guarding() {
        return guardUntil != 0 && System.currentTimeMillis() < guardUntil && hasKept();
    }

    /**
     * Disarms the guard. Called the moment a snap-back happens.
     *
     * <p><b>The correction is one-shot on purpose, and this is the fix for the cursor "spinning".</b>
     * The guard used to stay armed for its whole window and correct on every frame the cursor was
     * near the centre. But the centre of the screen is an ordinary place for a hand to be - it is
     * where the menu is - so once the player moved back towards the middle they were thrown across
     * the screen again, and again, for as long as the window lasted. Hypixel re-opens a container on
     * every click, and each open re-armed the guard, so during fast clicking the window effectively
     * never closed and the cursor was flung on and off the centre repeatedly.
     *
     * <p>The transition teleports exactly once per menu open. Correcting exactly once per menu open
     * is therefore both sufficient and the only version that cannot fight the player: after the snap
     * there is nothing left to undo, and everything that follows is the person moving the mouse.
     */
    public static void consumeGuard() {
        guardUntil = 0;
        guardFired = true;
    }

    /**
     * Why this reported position is the transition's teleport, or {@code null} when it is the
     * player's own hand.
     *
     * <p>Two signatures, because a teleport has two tells and only one of them was being used:
     *
     * <ul>
     *   <li>{@code "centre"} – it landed on the window centre to the pixel. A real hand passes NEAR
     *       the centre with ordinary in-between positions; only a programmatic set lands on it
     *       exactly.</li>
     *   <li>{@code "jump"} – it landed {@value #JUMP_DISTANCE} px or more from where the cursor
     *       genuinely was a moment ago. This is the one that catches a restore to somewhere other
     *       than the centre, which is the documented GLFW behaviour and was previously unhandled.</li>
     * </ul>
     *
     * <p>A string rather than a boolean so the miss report and the fired log can say <i>which</i>
     * tell caught it. If the jump rule ever fires on a real hand, that line is how it gets found.
     */
    public static String teleportReason(double x, double y) {
        if (!guarding()) {
            return null;
        }
        if (Math.abs(x - guardCx) <= CENTER_TOLERANCE && Math.abs(y - guardCy) <= CENTER_TOLERANCE) {
            return "centre";
        }
        double dx = x - keptX;
        double dy = y - keptY;
        return dx * dx + dy * dy >= JUMP_DISTANCE * JUMP_DISTANCE ? "jump" : null;
    }

    /**
     * True exactly once per guard window that closed <b>without correcting anything</b>.
     *
     * <p>This is the diagnostic that was missing. The previous logging only recorded the successful
     * snap-back, at {@code debug} — so a session where the cursor was flung produced no line at all,
     * because the failure is the absence of a snap-back and an absent event cannot log itself. A
     * window that opens and closes unfired is the exact footprint of the bug, and it is rare enough
     * to report at {@code info} without drowning a log that sees a menu open per click.
     */
    public static boolean pendingMiss() {
        if (guardUntil == 0 || guardFired || System.currentTimeMillis() < guardUntil) {
            return false;
        }
        guardUntil = 0;
        return true;
    }
}
