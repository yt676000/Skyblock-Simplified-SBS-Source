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
 * which of his checks you are heading for; the checks themselves sit on a grid. So the call-out
 * lands on the first grid mark that still leaves enough time to actually swing - anything closer is
 * a mark you cannot make. The whole measurement repeats per cycle, because that is what camping the
 * room is.
 *
 * <p><b>Everything is counted on the server's clock, not on yours.</b> Hypixel schedules in ticks,
 * and a blood room mid-spawn is exactly where a server stops managing twenty of them a second. Wall
 * clock in a room running at eighteen ticks drifts about three seconds over the half minute this
 * measures - enough to bucket the gap into the wrong mark and then fire the call-out early on top of
 * it. So the clock advances by the measured tick rate ({@link ServerStatsTracker}), and falls back
 * to real time whenever the rate is unknown. The arithmetic lives in {@link BloodCampClock}.
 *
 * <p><b>A wrong call is worse than no call</b>, because it costs the kill window it was supposed to
 * find. A gap that is too short means his opening line was never seen (the move line stamped the
 * clock itself); one that is too long means the clock is measuring from something that was not the
 * start of this room. Neither predicts anything, and both say so instead of guessing.
 *
 * <p><b>Paused.</b> The grid, the first mark and the swing window were the numbers measured off
 * Hypixel's behaviour before its 2026-10 patch, which sped up the Watcher's dialogue on every floor
 * and made him send his summons faster. They are presumed wrong until re-measured, so the prediction
 * is switched off in code ({@link #TIMING_VERIFIED}); the clock keeps running and
 * {@link BloodTimingProbe} logs what the new numbers have to be derived from. See
 * {@code docs/features/blood-camp-move-timing.md}.
 */
public final class BloodMoveTimer {

    /**
     * Whether the Watcher's timing constants in {@link BloodCampClock} are known to match the game.
     *
     * <p><b>False since Hypixel's 2026-10 patch</b>, which sped up the Watcher's dialogue on all
     * floors and the spawning of his summons. While false nothing is predicted: no KILL BLOOD flash,
     * no chat line, no ETA on the card ({@link #etaMs()} is -1), and the settings rows read as
     * paused. This is a code constant on purpose: existing configs carry {@code campMove = true}, so a
     * default change would never reach them, and the saved value is left alone so the feature comes
     * back exactly as the player had it.
     *
     * <p>Set back to true only once the grid, the first and last mark and the gap band have been
     * re-derived from the {@code [SBS][BloodTiming]} logs of enough camps per floor type, and the new
     * constants are unit-tested against those logged rooms. The rule is in
     * {@code docs/features/blood-camp-move-timing.md}.
     */
    public static final boolean TIMING_VERIFIED = false;

    /** The reason shown on the paused settings rows. */
    public static final String PAUSED_REASON =
            "Paused — Hypixel changed the Watcher's timing; being re-measured";

    private static final BloodMoveTimer INSTANCE = new BloodMoveTimer();

    /** Red for a "go", as the other dungeon call-outs use it - and its own pitch to tell them apart. */
    private static final int ALERT_COLOR = 0xFFFF5555;
    private static final float ALERT_PITCH = 1.8f;

    /** The pieces every pattern below is built from: who spoke, and which of his lines it was. */
    static final String WATCHER = "(?i)\\[BOSS\\]\\s+The Watcher\\s*:";
    private static final String ANY = ".*";
    static final String CUE = "Let.s see how you can handle this";
    static final String DONE = "That will be enough for now";

    private final BloodCampClock clock = new BloodCampClock();

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
                matcher -> clock.clear(), "blood move: room over");
        // The measurement probe reads the same lines; touching it here is what registers it.
        BloodTimingProbe.getInstance();
    }

    public static BloodMoveTimer getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.BloodSettings cfg() {
        return ConfigManager.getInstance().get().blood;
    }

    /**
     * The clock runs: the blood helper is on and we are in a dungeon. Independent of the call-out
     * switch, so the camp cycle is measured even while nothing is called.
     */
    private static boolean clockOn() {
        return cfg().enabled && DungeonStateManager.getInstance().inDungeon();
    }

    /** A mark is predicted, shown and called: the timing is verified and the player wants the call. */
    private static boolean predicting() {
        return TIMING_VERIFIED && cfg().campMove;
    }

    // ---- read by the HUD ----------------------------------------------------------------------

    /** Milliseconds until the mark, or -1 when nothing is predicted right now (always while paused). */
    public long etaMs() {
        return clock.etaMs(predicting());
    }

    // ---- chat ---------------------------------------------------------------------------------

    /**
     * He spoke: the clock starts over from here, every cycle. Camping is this loop again and again,
     * and a clock that armed once and locked would call the first round and then never again.
     */
    private void onWatcherSpoke() {
        if (clockOn()) {
            clock.start(System.currentTimeMillis());
        }
    }

    /**
     * The reference line. Everything is decided in this one moment: the gap is measured, the mark it
     * points at is worked out, and from here the tick loop only has to wait for it.
     */
    private void onMoveLine() {
        if (!clockOn()) {
            return;
        }
        BloodCampClock.CueResult result = clock.cue(System.currentTimeMillis(), tps(), predicting());
        if (result == BloodCampClock.CueResult.GAP_OUT_OF_RANGE) {
            say("§8[§bSBS§8]§r §7Blood move §8- no call, this cycle's opening line was missed");
        } else if (result == BloodCampClock.CueResult.MARKED) {
            say("§8[§bSBS§8]§r §cBlood move §7in §f"
                    + BloodRoomTracker.seconds((long) ((clock.mark() - clock.gap()) * 1000.0))
                    + " §8(" + (int) clock.mark() + "s mark)");
        }
    }

    // ---- tick ---------------------------------------------------------------------------------

    /** Called every client tick in the Catacombs: advances the clock and fires the call-out. */
    public void onClientTick() {
        BloodTimingProbe.getInstance().onClientTick();
        if (!clock.running()) {
            return; // no room, nothing to count - the common case, and it costs one read
        }
        if (!clockOn()) {
            clock.clear();
            return;
        }
        if (clock.tick(System.currentTimeMillis(), tps(), predicting())) {
            DungeonAlert.getInstance().trigger("KILL BLOOD", ALERT_COLOR, true, ALERT_PITCH);
        }
    }

    /**
     * World change. The tick only runs in the Catacombs now, so leaving one no longer clears the
     * clock by itself - this does, and a cycle spanning a reconnect was not measurable anyway.
     */
    public void onWorldChange() {
        clock.clear();
    }

    private static double tps() {
        return ServerStatsTracker.getInstance().tps();
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
        String state = !TIMING_VERIFIED ? "§6paused: timing being re-measured"
                : cfg().campMove ? "§aon" : "§8off";
        out.add("§bBlood move§7: " + state
                + " §7| clock §f" + (!clock.running() ? "not running"
                        : String.format(Locale.ROOT, "%.1fs", clock.elapsed()))
                + " §7| open→cue §f" + (clock.gap() < 0 ? "-"
                        : String.format(Locale.ROOT, "%.2fs", clock.gap()))
                + " §7| mark §f" + (clock.mark() == 0 ? "none" : (int) clock.mark() + "s")
                + " §7| called §f" + clock.fired()
                + " §7| tick rate §f" + String.format(Locale.ROOT, "%.1f",
                        BloodCampClock.tickFactor(tps()) * BloodCampClock.FULL_TPS));
        return out;
    }
}
