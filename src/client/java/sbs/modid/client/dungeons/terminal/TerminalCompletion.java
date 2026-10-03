/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.terminal;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.command.SBSCommands;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.floorseven.logic.TerminalTracker;

import java.util.Locale;

/**
 * What happens the moment <b>you</b> finish a terminal: how long it took, whether that beat your
 * best, and - only if asked for - one line in party chat.
 *
 * <p>The clock starts when a terminal the solver recognises opens and stops on your own activation
 * line, which is the server's own confirmation that the thing is done. Nothing here watches the board
 * for a win condition: a terminal is finished when Hypixel says it is, and that message is already
 * being parsed by {@link TerminalTracker} for the progress card.
 *
 * <p><b>The timer is not cleared when the GUI closes.</b> Finishing a terminal <i>is</i> what closes
 * it, and the chat line lands a moment later - clearing on close would throw the measurement away
 * exactly when it is about to be used. It is consumed once, expires by itself after
 * {@value #STALE_MS} ms, and is replaced when a different terminal opens.
 *
 * <p><b>On the party line.</b> It is off until switched on, it only ever fires for the local player's
 * own activation, it goes to party chat and nowhere else, and the text is the player's own from the
 * settings page - never anything another player typed. The one thing it borrows from the server is
 * the counter in brackets, which everyone in the party already saw.
 */
public final class TerminalCompletion {

    private static final TerminalCompletion INSTANCE = new TerminalCompletion();

    /** A measurement older than this belongs to a terminal somebody else finished, not to this one. */
    private static final long STALE_MS = 120_000L;

    /** Hypixel disconnects a client whose chat packet runs long; the party line is capped well under. */
    private static final int MAX_MESSAGE = 200;

    private Object openScreen;
    private TerminalType openType = TerminalType.NONE;
    private long openedAtMs;

    private TerminalCompletion() {
    }

    public static TerminalCompletion getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /**
     * Called every tick a recognised terminal is on screen; starts the clock on the tick the screen
     * itself changes. Keyed on screen identity rather than on the type, so two ordered terminals in a
     * row are two measurements and not one very slow one.
     */
    public void onTerminalVisible(Object screen, TerminalType type) {
        if (screen == openScreen) {
            return;
        }
        openScreen = screen;
        openType = type;
        openedAtMs = System.currentTimeMillis();
    }

    /**
     * The local player's own activation line. {@code kind} is what Hypixel counted; the bracketed
     * counter is passed through untouched.
     */
    public void onOwnActivation(TerminalTracker.Kind kind, int done, int total) {
        long elapsed = consumeElapsed(kind);
        TerminalType solved = openType;
        if (elapsed > 0) {
            openScreen = null;
            openType = TerminalType.NONE;
            openedAtMs = 0;
        }
        if (cfg().terminalTimes && elapsed > 0) {
            report(solved, elapsed);
        }
        if (cfg().terminalAnnounce) {
            announce(kind, done, total, elapsed);
        }
    }

    /**
     * How long the thing just activated took, or {@code 0} when there is no honest number.
     *
     * <p>Only a {@link TerminalTracker.Kind#TERMINAL} is ever timed: a device and a lever are clicked
     * in the world with no GUI to open, so there is nothing to start a clock on and any figure would
     * be the age of whatever terminal happened to be opened last.
     */
    private long consumeElapsed(TerminalTracker.Kind kind) {
        if (kind != TerminalTracker.Kind.TERMINAL || openedAtMs == 0) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - openedAtMs;
        return elapsed > 0 && elapsed < STALE_MS ? elapsed : 0;
    }

    /** The local line: what this one took, against the best this profile has managed. */
    private void report(TerminalType type, long elapsed) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        long best = best(type);
        boolean record = best <= 0 || elapsed < best;
        if (record) {
            cfg().terminalLivePb.put(type.name(), elapsed);
            ConfigManager.getInstance().save();
        }
        String line = "§b[SBS] §f" + label(type) + " §7in §f" + time(elapsed)
                + (record ? " §a(new best)" : " §7(best " + time(best) + "§7)");
        player.sendSystemMessage(Component.literal(line));
    }

    /** The party line, built from the player's own template. */
    private void announce(TerminalTracker.Kind kind, int done, int total, long elapsed) {
        String template = cfg().terminalAnnounceText;
        if (template == null || template.isBlank()) {
            return;
        }
        String message = template
                .replace("{kind}", kind.name().toLowerCase(Locale.ROOT))
                .replace("{done}", String.valueOf(done))
                .replace("{total}", String.valueOf(total))
                .replace("{time}", elapsed > 0 ? time(elapsed) : "");
        message = clean(tidy(message));
        if (message.isEmpty()) {
            return;
        }
        SBSCommands.run("/pc " + sbs.modid.client.core.util.ChatTag.tag(message));
    }

    /**
     * Tidies up after a placeholder that resolved to nothing.
     *
     * <p>A device and a lever carry no time, and the default template puts the time in brackets - so
     * without this the party would read "device 4/7 done ()". The brackets belonged to the value, not
     * to the sentence, so they go with it.
     */
    private static String tidy(String text) {
        return text.replaceAll("\\(\\s*\\)", "")
                .replaceAll("\\[\\s*\\]", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    /**
     * The template reduced to plain printable ASCII and capped.
     *
     * <p>The text is the player's own, so this is not about trusting it - it is that a {@code §} or a
     * stray control character in a chat packet is the sort of thing a server refuses outright, and
     * being disconnected mid-Necron for a cosmetic message would be an absurd way to lose a run.
     */
    private static String clean(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_MESSAGE));
        for (int i = 0; i < text.length() && out.length() < MAX_MESSAGE; i++) {
            char c = text.charAt(i);
            if (c >= ' ' && c <= '~' && c != '§' && c != '&') {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    private static long best(TerminalType type) {
        Long stored = cfg().terminalLivePb.get(type.name());
        return stored == null ? 0 : stored;
    }

    /** "4.2s", or "1m 04s" once a terminal has taken long enough for seconds alone to read badly. */
    private static String time(long ms) {
        if (ms >= 60_000L) {
            return String.format(Locale.ROOT, "%dm %02ds", ms / 60_000L, ms % 60_000L / 1000L);
        }
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    /** The terminal's name in the local line; the generic word when it was one we do not classify. */
    private static String label(TerminalType type) {
        return switch (type) {
            case ORDER -> "Order terminal";
            case COLOR -> "Colour terminal";
            case STARTS_WITH -> "Starts-with terminal";
            case PANES -> "Panes terminal";
            case RUBIX -> "Rubix terminal";
            case MELODY -> "Melody terminal";
            case ITEM_NAME, NONE -> "Terminal";
        };
    }
}
