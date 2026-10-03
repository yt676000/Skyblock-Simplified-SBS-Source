/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.blood.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.events.DungeonAlert;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.ui.hud.logic.ServerStatsTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The blood-camp move call-out: when to have the room dead so the Watcher skips the rest of his
 * dialogue and opens up early.
 *
 * <p><b>What it is for.</b> Camping blood means running the room over and over for what it drops,
 * and in that loop the fight is not the slow part - the Watcher talking is. He checks the room on a
 * schedule, and a room that is already empty when he checks makes him cut the talking short. Killing
 * the mobs a second later means waiting for the next check, which is the difference between a camp
 * that flows and one that stands around. This is a timing hint and nothing else: it does not fight,
 * it does not tell you where the mobs are - {@link BloodRoomTracker} does that - and the two are
 * happy to run together or apart.
 *
 * <p><b>The schedule is read off his own two lines.</b> Anything he says starts the clock, and
 * {@code "Let's see how you can handle this."} is the reference point, because that line is fixed
 * relative to his checks while the one that opened the cycle varies. The gap between the two says
 * which of his checks you are heading for; the checks themselves sit on a three-second grid, the
 * first useful one a good twenty seconds in. So the call-out lands on the first grid mark that still
 * leaves enough time to actually swing - anything closer is a mark you cannot make. The whole
 * measurement repeats per cycle, because that is what camping the room is.
 *
 * <p><b>Everything is counted on the server's clock, not on yours.</b> Hypixel schedules in ticks,
 * and a blood room mid-spawn is exactly where a server stops managing twenty of them a second. Wall
 * clock in a room running at eighteen ticks drifts about three seconds over the half minute this
 * measures - enough to bucket the gap into the wrong mark and then fire the call-out early on top of
 * it. So the clock here advances by the measured tick rate ({@link ServerStatsTracker}), which
 * cancels both halves of that at once, and falls back to real time whenever the rate is unknown.
 *
 * <p><b>A wrong call is worse than no call</b>, because it costs the kill window it was supposed to
 * find. A gap that is too short means his opening line was never seen (the move line stamped the
 * clock itself); one that is too long means the clock is measuring from something that was not the
 * start of this room. Neither predicts anything, and both say so instead of guessing.
 *
 * <p>The grid, the twenty-four-second floor and the swing window are the numbers the community has
 * measured off Hypixel's own behaviour - there is no second version of them to have. The
 * implementation here is our own.
 */
public final class BloodMoveTimer {

    private static final BloodMoveTimer INSTANCE = new BloodMoveTimer();

    /** The Watcher's checks sit on a three-second grid, counted from his opening line. */
    private static final double GRID_SECONDS = 3.0;

    /** The earliest check worth aiming at - the room is not killable before it. */
    private static final double FIRST_MARK_SECONDS = 24.0;

    /** The last one that still skips anything; past it he has said his piece anyway. */
    private static final double LAST_MARK_SECONDS = 36.0;

    /** How much room a mark needs to be worth calling: any less and you cannot finish the mobs. */
    private static final double LEAD_SECONDS = 2.0;

    /** The flash lands a touch early, so the last hit falls on the mark instead of just behind it. */
    private static final double ALERT_LEAD_SECONDS = 0.15;

    /** Shorter than this and he was still talking when he cued - that is not a cycle to measure. */
    private static final double MIN_GAP_SECONDS = 1.0;

    /** Longer than this and the clock is not measuring this room's start. */
    private static final double MAX_GAP_SECONDS = 34.0;

    /** A clock this old belongs to a room that ended without saying so. */
    private static final double STALE_SECONDS = 90.0;

    /** Tick rates outside this band are a freeze or a world change, not a room running slow. */
    private static final double MIN_TPS = 5.0;
    private static final double FULL_TPS = 20.0;

    /** Red for a "go", as the other dungeon call-outs use it - and its own pitch to tell them apart. */
    private static final int ALERT_COLOR = 0xFFFF5555;
    private static final float ALERT_PITCH = 1.8f;

    /** The pieces every pattern below is built from: who spoke, and which of his lines it was. */
    private static final String WATCHER = "(?i)\\[BOSS\\]\\s+The Watcher\\s*:";
    private static final String ANY = ".*";
    private static final String CUE = "Let.s see how you can handle this";
    private static final String DONE = "That will be enough for now";

    /** When the Watcher's opening line landed; 0 while no clock is running. */
    private volatile long openedAt;

    /** Server-seconds since that line - see the class note on why these are not wall-clock seconds. */
    private volatile double elapsed;

    /** Wall clock of the last step, i.e. what {@link #advance()} last converted. */
    private long lastStep;

    /** The grid mark to call out at, in server-seconds since the opening line; 0 while none is due. */
    private volatile double mark;

    /** The measured opening→move gap that produced that mark, or -1 while nothing was measured. */
    private volatile double gap = -1;

    private volatile boolean fired;

    private BloodMoveTimer() {
        // The clock starts on the Watcher opening his mouth, whatever comes out of it. He has a
        // fistful of different openers, a different one again between waves, and Hypixel is free to
        // add another tomorrow; what matters is only that he spoke, so matching him rather than his
        // lines cannot go stale. His two structural lines are the exceptions: one ends a cycle and
        // one ends the room, and either re-stamping the clock would measure a gap of zero.
        ChatPatternRegistry.getInstance().register(
                WATCHER + "(?!" + ANY + "(?:" + CUE + "|" + DONE + "))",
                matcher -> onWatcherSpoke(), "blood move: watcher opens");
        // The reference point. Tied to the Watcher's own line rather than floating: these are words
        // a player can simply type, and a call-out that anyone in the party can fake by talking is
        // worse than one that occasionally misses. The apostrophe is matched loosely because it
        // arrives as a different glyph depending on where the line was written.
        ChatPatternRegistry.getInstance().register(
                WATCHER + ANY + CUE,
                matcher -> onMoveLine(), "blood move: watcher's cue");
        // The room is over - the same line the tracker ends on, read here independently so neither
        // class has to tell the other anything.
        ChatPatternRegistry.getInstance().register(
                WATCHER + ANY + DONE,
                matcher -> clear(), "blood move: room over");
    }

    public static BloodMoveTimer getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.BloodSettings cfg() {
        return ConfigManager.getInstance().get().blood;
    }

    /** The feature is on, and we are somewhere it means something. */
    private static boolean enabled() {
        return cfg().enabled && cfg().campMove && DungeonStateManager.getInstance().inDungeon();
    }

    // ---- read by the HUD ----------------------------------------------------------------------

    /** Milliseconds until the mark, or -1 when nothing is predicted right now. */
    public long etaMs() {
        double due = mark;
        if (due == 0 || openedAt == 0) {
            return -1;
        }
        double left = due - elapsed;
        return left <= 0 ? -1 : (long) (left * 1000.0);
    }

    // ---- chat ---------------------------------------------------------------------------------

    /**
     * He spoke: the clock starts over from here.
     *
     * <p><b>Every cycle, not just the first.</b> Camping the room means going round this loop again
     * and again - he talks, he sends mobs, they die, he talks again - and each round has its own
     * schedule to measure. A clock that armed once and locked would call the first round of the run
     * and then quietly never again, which for a camping feature is the same as not having one.
     *
     * <p>The exception is a call that is already standing: while one is waiting to fire, nothing he
     * says moves it. That call was measured off a gap that really happened, and a stray taunt in the
     * middle of the fight must not throw it away seconds before it comes due.
     */
    private void onWatcherSpoke() {
        if (!enabled() || (mark != 0 && !fired)) {
            return;
        }
        openedAt = System.currentTimeMillis();
        lastStep = openedAt;
        elapsed = 0;
        mark = 0;
        gap = -1;
        fired = false;
    }

    /**
     * The reference line. Everything is decided in this one moment: the gap is measured, the mark it
     * points at is worked out, and from here the tick loop only has to wait for it.
     */
    private void onMoveLine() {
        if (!enabled() || openedAt == 0) {
            return;
        }
        // The line arrives between two ticks, so the clock is caught up to this instant rather than
        // to the last tick - a fifty-millisecond error is a third of the whole call-out lead.
        advance();
        double measured = elapsed;
        gap = measured;
        if (measured < MIN_GAP_SECONDS || measured >= MAX_GAP_SECONDS) {
            mark = 0;
            say("§8[§bSBS§8]§r §7Blood move §8- no call, this cycle's opening line was missed");
            return;
        }
        mark = markAfter(measured);
        fired = false;
        say("§8[§bSBS§8]§r §cBlood move §7in §f"
                + BloodRoomTracker.seconds((long) ((mark - measured) * 1000.0))
                + " §8(" + (int) mark + "s mark)");
    }

    /**
     * The mark to aim at: the first point of his grid that is still far enough away to finish the
     * room before it, never earlier than his first check and never past his last.
     */
    private static double markAfter(double measured) {
        double earliest = measured + LEAD_SECONDS;
        double next = (Math.floor(earliest / GRID_SECONDS) + 1) * GRID_SECONDS;
        return Math.min(LAST_MARK_SECONDS, Math.max(FIRST_MARK_SECONDS, next));
    }

    // ---- tick ---------------------------------------------------------------------------------

    /** Called every client tick: advances the clock and fires the call-out when the mark comes up. */
    public void onClientTick() {
        if (openedAt == 0) {
            return; // no room, nothing to count - the common case, and it costs one read
        }
        if (!enabled()) {
            clear();
            return;
        }
        advance();
        if (elapsed > STALE_SECONDS) {
            clear(); // a room that ended without its closing line must not time the next one
            return;
        }
        if (mark == 0 || fired || elapsed < mark - ALERT_LEAD_SECONDS) {
            return;
        }
        fired = true;
        DungeonAlert.getInstance().trigger("KILL BLOOD", ALERT_COLOR, true, ALERT_PITCH);
    }

    /**
     * Moves the clock up to now, converting real time into server time as it goes. The tick rate is
     * sampled every step rather than once, because a room does not lag evenly: it stalls while the
     * mobs come in and recovers afterwards, and only the stalling part should stretch the countdown.
     */
    private void advance() {
        long now = System.currentTimeMillis();
        long stepMs = now - lastStep;
        lastStep = now;
        if (stepMs <= 0) {
            return;
        }
        elapsed += stepMs / 1000.0 * tickFactor();
    }

    /** Server seconds per real second: 1.0 whenever there is no trustworthy reading to use. */
    private static double tickFactor() {
        double tps = ServerStatsTracker.getInstance().tps();
        return tps < MIN_TPS || tps > FULL_TPS ? 1.0 : tps / FULL_TPS;
    }

    private void clear() {
        openedAt = 0;
        elapsed = 0;
        mark = 0;
        gap = -1;
        fired = false;
    }

    private static void say(String message) {
        if (!cfg().campMoveChat) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    /**
     * What {@code /sbsdev blood} prints for this half of the helper. A call-out that did not come is
     * otherwise indistinguishable from a feature that is switched off, and the gap it measured is the
     * one number that says which of the two happened.
     */
    public List<String> debugLines() {
        List<String> out = new ArrayList<>();
        out.add("§bBlood move§7: " + (cfg().campMove ? "§aon" : "§8off")
                + " §7| clock §f" + (openedAt == 0 ? "not running"
                        : String.format(Locale.US, "%.1fs", elapsed))
                + " §7| open→cue §f" + (gap < 0 ? "-" : String.format(Locale.US, "%.2fs", gap))
                + " §7| mark §f" + (mark == 0 ? "none" : (int) mark + "s")
                + " §7| called §f" + fired
                + " §7| tick rate §f" + String.format(Locale.US, "%.1f", tickFactor() * FULL_TPS));
        return out;
    }
}
