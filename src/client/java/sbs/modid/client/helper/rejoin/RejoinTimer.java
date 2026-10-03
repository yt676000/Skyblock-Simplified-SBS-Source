/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rejoin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.RejoinTimerSettings;
import sbs.modid.client.helper.scoreboard.ScoreboardReader;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.Locale;

/**
 * The Rejoin Timer: when Hypixel kicks you off SkyBlock (usually into Limbo), a countdown appears and,
 * when it runs out, tells you it is safe to rejoin – so you wait out Hypixel's brief reconnect window
 * instead of hammering {@code /skyblock} and getting kicked straight back.
 *
 * <p>The kick is recognised from chat ({@link #onChat}) – the "kicked while joining that server" and
 * disconnect/limbo lines – and can also be armed by hand with {@code /sbs rejoin}. The countdown is
 * drawn centred at the top of the screen (it only shows while a kick is being waited out), and the tick
 * ({@link #onClientTick}) fires the "try rejoining now" message, an optional ding and, if enabled, the
 * rejoin command itself. Detection is a best-guess on the exact Hypixel wording, so a throttled
 * {@code [SBS][Rejoin]} log line prints what armed it, and the manual command always works.
 */
public final class RejoinTimer {

    private static final RejoinTimer INSTANCE = new RejoinTimer();

    /** Keep the "try rejoining now" banner up this long after the countdown ends, then auto-hide. */
    private static final long LINGER_MS = 12_000L;

    /** When the current wait started (millis), or 0 when the timer is idle. */
    /** How long a non-SkyBlock sidebar must persist before it counts as "we are in a lobby". */
    private static final long LOBBY_CONFIRM_MS = 3_000L;
    /** How long after a deliberate lobby command the sidebar fallback stays quiet. */
    private static final long VOLUNTARY_GRACE_MS = 15_000L;

    /**
     * How long an announced restart keeps explaining a lobby.
     *
     * <p>It has to span the announcement, its countdown, the move, and however long the player sits
     * in the lobby before going back - so it is generous rather than tight. It costs little to be
     * generous: the flag is cleared the moment SkyBlock is seen again, so in practice it ends when
     * the player rejoins rather than when this elapses. The elapse is only a backstop for a player
     * who is announced at and then never returns.
     *
     * <p><b>Unverified.</b> Five minutes is a guess at how long that gap can be, not a measurement.
     */
    private static final long RESTART_SUPPRESS_MS = 5 * 60_000L;

    private volatile long kickedAt;

    /** Last time the sidebar said SkyBlock; 0 = not seen since the last arm. */
    private volatile long sawSkyblockAt;
    /** When the sidebar first showed a non-SkyBlock title; 0 = it currently does not. */
    private volatile long lobbySinceAt;
    /** When the player last ran a command that moves them off SkyBlock on purpose. */
    private volatile long voluntaryLeaveAt;

    /**
     * When a restart was last announced; 0 = none pending. Cleared on rejoining SkyBlock and on any
     * world change, so it can only ever explain the lobby it was announced for.
     */
    private volatile long restartExpectedAt;

    /** Why the current countdown was armed, for the banner text and the log. */
    private volatile RejoinCause cause = RejoinCause.UNKNOWN;

    /** The level the last tick saw, so a world change or server hop wipes the state. */
    private Object lastLevel;

    private long lastUnmatchedLogAt;
    /** Whether the end-of-countdown message/sound/command has already fired for this wait. */
    private boolean announced;

    private RejoinTimer() {
    }

    public static RejoinTimer getInstance() {
        return INSTANCE;
    }

    private static RejoinTimerSettings cfg() {
        return ConfigManager.getInstance().get().rejoinTimer;
    }

    // ------------------------------------------------------------------ intake

    /**
     * Every chat line. Announcements are the only place the <i>reason</i> is ever visible, so this is
     * where the three cases are separated - by the time the sidebar changes, they all look alike.
     *
     * <p>A restart announcement <b>suppresses</b> rather than arms. That is a reversal of what this
     * did before, and deliberate: the countdown used to start on "server will restart soon", which
     * invited the player to try rejoining a server that is not there. Rejoining is not the answer to
     * a reboot, so the honest response is no banner at all.
     */
    public void onChat(String text) {
        if (!cfg().enabled || text == null) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (isRestartLine(lower)) {
            noteRestartExpected(text.strip());
            return;
        }
        if (active()) {
            return;   // already counting - don't restart on a follow-up line of the same kick
        }
        if (isKickLine(lower)) {
            arm(RejoinCause.KICK, "chat: " + text.strip());
            return;
        }
        if (looksRelevant(lower) && !lower.contains("party") && !lower.contains("guild")
                && System.currentTimeMillis() - lastUnmatchedLogAt > 10_000L) {
            lastUnmatchedLogAt = System.currentTimeMillis();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Rejoin] unmatched candidate line: '{}'", text.strip());
        }
    }

    /**
     * Records that a restart was announced, and cancels any countdown already on screen.
     *
     * <p>Cancelling matters: the announcement often arrives <i>after</i> something else already armed
     * the timer, and a banner telling the player to rejoin a server that is shutting down is the
     * exact failure this change exists to stop.
     */
    private void noteRestartExpected(String line) {
        restartExpectedAt = System.currentTimeMillis();
        if (active()) {
            stop();
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Rejoin] restart announced - suppressing the prompt "
                + "for {}s: '{}'", RESTART_SUPPRESS_MS / 1000, line);
    }

    /** Whether a restart was announced recently enough that a lobby is explained by it. */
    private boolean restartExpected(long now) {
        return restartExpectedAt != 0 && now - restartExpectedAt < RESTART_SUPPRESS_MS;
    }

    /**
     * Called for every command the player sends. A lobby you walked into yourself is not a kick, and
     * with auto-rejoin on, treating it as one would drag you straight back out again - so a short
     * grace window after such a command mutes the sidebar fallback.
     */
    public void onCommandSent(String command) {
        if (command == null) {
            return;
        }
        String verb = command.strip().toLowerCase(Locale.ROOT);
        if (verb.startsWith("/")) {
            verb = verb.substring(1);
        }
        int space = verb.indexOf(' ');
        if (space > 0) {
            verb = verb.substring(0, space);
        }
        // /hub is deliberately NOT here: it moves you inside SkyBlock, so the sidebar keeps saying so.
        if (verb.equals("lobby") || verb.equals("l") || verb.equals("play") || verb.equals("limbo")
                || verb.equals("mainlobby") || verb.equals("hypixel") || verb.equals("skyblock")) {
            voluntaryLeaveAt = System.currentTimeMillis();
        }
    }

    /**
     * Watches the sidebar for the moment SkyBlock turns into a lobby.
     *
     * <p>This is the wording-independent half of the detection: a scheduled reboot, an evacuation and
     * a plain kick all end the same way - the sidebar title stops saying SKYBLOCK and starts naming a
     * lobby ("PROTOTYPE", "HYPIXEL", ...). A missing sidebar is deliberately NOT treated as a lobby,
     * because that is also what the brief gap during any server transfer looks like.
     */
    private void pollScoreboard(long now) {
        Component title = ScoreboardReader.title();
        if (title == null) {
            return;   // between servers - tells us nothing either way
        }
        boolean skyblock = title.getString().toUpperCase(Locale.ROOT).replace(" ", "").contains("SKYBLOCK");
        if (skyblock) {
            sawSkyblockAt = now;
            lobbySinceAt = 0;
            // Back on SkyBlock, so whatever restart was announced is over and done with. Clearing it
            // here is what stops one afternoon's reboot notice muting a genuine kick hours later.
            if (restartExpectedAt != 0) {
                restartExpectedAt = 0;
                SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Rejoin] back on SkyBlock - restart flag cleared");
            }
            return;
        }
        if (active() || sawSkyblockAt == 0) {
            return;   // already counting, or never seen SkyBlock this session - nothing was lost
        }
        if (lobbySinceAt == 0) {
            lobbySinceAt = now;
            return;
        }
        if (now - lobbySinceAt < LOBBY_CONFIRM_MS) {
            return;   // ride out a transient title while the lobby loads
        }
        lobbySinceAt = 0;
        sawSkyblockAt = 0;

        // Everything below is the same observable event with three different causes, and the sidebar
        // says nothing about which. Each check answers "is this explained by something we saw?", and
        // what is left over is UNKNOWN.
        if (now - voluntaryLeaveAt < VOLUNTARY_GRACE_MS) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Rejoin] lobby follows a command the player sent "
                    + "- no prompt");
            return;
        }
        if (restartExpected(now)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Rejoin] lobby follows an announced restart - no "
                    + "prompt, the server is not there to rejoin");
            return;
        }
        // No reason was seen. This is the case the report is about: it is *probably* a kick, and
        // probably is not good enough. A missed prompt costs one command; a wrong one on every
        // restart teaches the player to ignore the banner and costs the feature entirely.
        if (!cfg().promptOnUnknownCause) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Rejoin] in a lobby with no reason seen - no "
                    + "prompt (switch on \"Prompt On Unknown Cause\" to be asked anyway)");
            return;
        }
        arm(RejoinCause.UNKNOWN, "sidebar: left SkyBlock for '" + title.getString().strip() + "'");
    }

    /**
     * Whether a colour-stripped, lower-cased line means you are about to lose, or have just lost, the
     * SkyBlock server.
     *
     * <p>Two families, because they are two different events with the same consequence: being
     * <b>kicked</b> (you are already out) and the server <b>restarting</b> ("This server will restart
     * soon: Scheduled Reboot", "You have 60 seconds to warp out!"), which puts everyone in a lobby a
     * minute later. The old set only knew the first, which is why a scheduled reboot left you in a
     * lobby with no timer running.
     *
     * <p>Matched on shape rather than exact wording - Hypixel rewords these - and anything that looks
     * like one but does not match is logged so the real text can be pinned. Party lines are excluded
     * so leaving a party never starts the timer.
     */
    static boolean isKickLine(String lower) {
        if (lower.contains("party") || lower.contains("guild")) {
            return false;
        }
        return lower.contains("kicked while joining")
                || lower.contains("you were kicked")
                || lower.contains("you have been kicked")
                || lower.contains("a disconnect occurred")
                || (lower.contains("limbo") && lower.contains("you"));
    }

    /**
     * Whether a line announces a scheduled restart or evacuation - the case that must <b>not</b>
     * prompt.
     *
     * <p>These used to sit in {@link #isKickLine} and arm the same countdown, because a reboot and a
     * kick have the same consequence. They do not have the same <i>answer</i>: after a kick the
     * server is still there and rejoining works, after a reboot it is not and rejoining cannot.
     *
     * <p>Matched on shape rather than exact wording, because Hypixel rewords these. <b>Unverified:</b>
     * none of these strings has been read off a live restart by this build - they are inherited from
     * the previous detection, which was itself a best guess. {@link #looksRelevant} logs anything
     * that talks about restarts and matches nothing, which is how the real text gets pinned.
     */
    static boolean isRestartLine(String lower) {
        if (lower.contains("party") || lower.contains("guild")) {
            return false;
        }
        return lower.contains("evacuat")                  // "Server is evacuating..."
                || lower.contains("server will restart")
                || lower.contains("server is restarting")
                || lower.contains("scheduled reboot")
                || lower.contains("seconds to warp out")
                || (lower.contains("restart") && lower.contains("soon"));
    }

    /**
     * A line that talks about restarts or kicks but matched nothing above. Logged (throttled) so an
     * unexpected Hypixel wording can be added instead of silently doing nothing.
     */
    private static boolean looksRelevant(String lower) {
        return lower.contains("restart") || lower.contains("reboot") || lower.contains("kick")
                || lower.contains("evacuat") || lower.contains("limbo") || lower.contains("disconnect");
    }

    /** Arms (or re-arms) the countdown now. Used by {@link #onChat} and the {@code /sbs rejoin} command. */
    public void arm(RejoinCause why, String reason) {
        kickedAt = System.currentTimeMillis();
        cause = why == null ? RejoinCause.UNKNOWN : why;
        announced = false;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Rejoin] armed ({}s, cause {}) by {}",
                cfg().seconds, cause, reason);
        SBSChat.send(Component.literal(" Rejoin timer started – ").withColor(SBSChat.WHITE)
                .append(Component.literal(cfg().seconds + "s").withColor(SBSChat.PREFIX_COLOR)));
    }

    /**
     * The player asking for the timer by hand ({@code /sbs rejoin}).
     *
     * <p>Armed as a {@link RejoinCause#KICK} regardless of what the client did or did not see: the
     * player has looked at their own screen and said this is a kick, and they are a better source
     * than any line-matching in this class.
     */
    public void armManually() {
        restartExpectedAt = 0;
        arm(RejoinCause.KICK, "manual /sbs rejoin");
    }

    /** Stops and hides the timer (manual {@code /sbs rejoin stop}, or after it lingers out). */
    public void stop() {
        kickedAt = 0;
        announced = false;
        cause = RejoinCause.UNKNOWN;
    }

    public boolean active() {
        return kickedAt != 0;
    }

    /** Seconds left on the countdown (may be negative once it has elapsed). */
    private int secondsLeft() {
        long elapsed = System.currentTimeMillis() - kickedAt;
        return (int) Math.ceil((cfg().seconds * 1000L - elapsed) / 1000.0);
    }

    // ------------------------------------------------------------------ tick

    /** Called every client tick: fires the end-of-countdown message/sound/command and auto-hides. */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        // A world change or server hop makes every one of these observations belong to a session
        // that is over. Carrying a restart flag or a half-run countdown across is how a stale
        // suppression eats a real kick later on.
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            resetForNewWorld();
        }
        if (cfg().enabled) {
            pollScoreboard(now);
        }
        if (!active()) {
            return;
        }
        if (!cfg().enabled) {
            stop();
            return;
        }
        long elapsed = now - kickedAt;
        if (!announced && elapsed >= cfg().seconds * 1000L) {
            // Not while a screen is up or the world is still loading: the ding and the chat line
            // arrive somewhere the player is not looking, and a rejoin fired mid-load lands during a
            // transfer. Deferred rather than dropped - the next tick with a clear screen runs it.
            // Minecraft's own screen field is not public in these mappings, which is why
            // GuiTrackingMixin records it - that recording is the only way to ask this question.
            if (sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen() != null
                    || mc.player == null || mc.level == null) {
                return;
            }
            announced = true;
            SBSChat.send(Component.literal(" Try rejoining now!").withColor(0x57D977));
            if (cfg().sound) {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f);
            }
            if (cfg().autoRejoin) {
                sendRejoinOnce(mc);
            }
        }
        if (announced && elapsed >= cfg().seconds * 1000L + LINGER_MS) {
            stop();   // banner has lingered long enough
        }
    }

    /**
     * Sends exactly one {@code /play SKYBLOCK}, once, and never again for this wait.
     *
     * <p><b>Why this is allowed at all</b>: it restores the player to the server they were just
     * thrown off, wins nothing against anybody, and replaces a keystroke that needs no skill or
     * timing - the no-advantage exception in {@code AGENTS.md}, and it is off by default.
     *
     * <p><b>Why it never retries.</b> {@code announced} is set before this runs, so a failure ends
     * here and the player is told rather than looped over. A retry loop is a bot however small each
     * step is, and a rejoin that failed usually failed because Hypixel is not ready - which is
     * precisely the state that a loop would hammer.
     */
    private void sendRejoinOnce(Minecraft mc) {
        if (mc.player == null || mc.player.connection == null) {
            SBSChat.send(Component.literal(" Could not send the rejoin - not connected. Try again "
                    + "with /sbs rejoin.").withColor(0xFFAA55));
            return;
        }
        try {
            mc.player.connection.sendCommand("play SKYBLOCK");
        } catch (Throwable e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Rejoin] rejoin command failed: {}", e.toString());
            SBSChat.send(Component.literal(" The rejoin command did not go through. Run it yourself, "
                    + "or /sbs rejoin to wait again.").withColor(0xFFAA55));
        }
    }

    /** Everything observed belongs to one session on one server; a new world starts over. */
    private void resetForNewWorld() {
        kickedAt = 0;
        announced = false;
        cause = RejoinCause.UNKNOWN;
        sawSkyblockAt = 0;
        lobbySinceAt = 0;
        voluntaryLeaveAt = 0;
        restartExpectedAt = 0;
    }

    // ------------------------------------------------------------------ render

    /** Called from the HUD render hook once per frame; draws the centred countdown / rejoin banner. */
    public void render(GuiGraphicsExtractor g) {
        if (!active() || !cfg().enabled) {
            return;
        }
        int left = secondsLeft();
        String text = left > 0 ? "Rejoin in " + left + "s" : "Try rejoining now!";
        int color = left > 0 ? SBSTheme.ACCENT_BRIGHT : 0xFF57D977;

        Font font = Minecraft.getInstance().font;
        int textW = font.width(text);
        int padX = 8;
        int padY = 5;
        int w = textW + padX * 2;
        int h = font.lineHeight + padY * 2;
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() / 4;

        SciFiRender.roundedRect(g, x - 1, y - 1, w + 2, h + 2, SBSTheme.CORNER_RADIUS + 1,
                SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRect(g, x, y, w, h, SBSTheme.CORNER_RADIUS, SBSTheme.PANEL_FILL_TOP);
        g.centeredText(font, Component.literal(text), x + w / 2, y + padY, color);
    }
}
