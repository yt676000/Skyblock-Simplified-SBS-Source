/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.chat;

import net.minecraft.network.chat.Component;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.perf.Perf;
import sbs.modid.client.core.util.PlainText;

import java.util.List;
import java.util.Locale;

/**
 * The one place a received chat line fans out to the features that read it.
 *
 * <p>The line is flattened and stripped <b>once</b>; each {@link Listener} then gets it only if its
 * island {@link Gate} is open and its {@link Prefilter} - a handful of literals taken from that
 * parser's own patterns - finds one of them. Regex only ever runs inside a listener whose prefilter
 * hit. The listeners and their order live in {@link ChatListeners}.
 *
 * <p><b>Why a prefilter can never lose a line.</b> The parsers do not agree on what a colour code is:
 * some strip {@code §} plus a code character ({@link PlainText}), some {@code §} plus <i>any</i>
 * character, some match the raw text. All of them agree whenever the stripped line has no {@code §}
 * left in it, so a literal is looked for in the raw line <i>and</i> the stripped one - the raw form
 * covers a literal whose first letter a code before it would eat ({@code §Oink!} strips to
 * {@code ink!}). A line whose stripped form still holds a {@code §} (a malformed code) skips every
 * prefilter and reaches every listener, exactly as before this class existed.
 *
 * <p>Client thread only, like every chat hook. Steady state allocates nothing beyond the strip
 * itself: the {@link Line} is reused (a fresh one only for a line added from inside a listener).
 */
public final class ChatDispatch {

    /** Where a listener may run, decided once per line. */
    public interface Gate {
        boolean open(Line line);
    }

    /** A cheap test on the line that must pass before the listener is called. */
    public interface Prefilter {
        boolean test(Line line);
    }

    /** The parser call itself. */
    public interface Handler {
        void onChat(String plain, String raw, Component message);
    }

    /** One registered parser. */
    public record Listener(String name, Gate gate, Prefilter prefilter, Handler handler) {
    }

    /** Every line, everywhere: for parsers that do work on lines matching none of their patterns. */
    public static final Gate ANY_ISLAND = line -> true;
    /** No prefilter; documented per listener in {@link ChatListeners}. */
    public static final Prefilter EVERY_LINE = line -> true;

    /** Island gates, each read at most once per line. */
    public static final Gate CRYSTAL_HOLLOWS = Line::crystalHollows;

    /** One chat line, as every prefilter sees it. */
    public static final class Line {
        String raw = "";
        String plain = "";
        boolean bypass;
        private byte hollows;   // 0 unknown, 1 yes, 2 no

        void set(String raw) {
            this.raw = raw;
            this.plain = PlainText.strip(raw);
            this.bypass = plain.indexOf('§') >= 0;
            this.hollows = 0;
        }

        public String raw() {
            return raw;
        }

        public String plain() {
            return plain;
        }

        /** Whether {@code literal} occurs in the line (case-sensitive). */
        public boolean has(String literal) {
            return bypass || raw.contains(literal) || (plain != raw && plain.contains(literal));
        }

        /**
         * Whether {@code literal} occurs ignoring case. Per-character case folding, which accepts
         * everything {@code toLowerCase(Locale.ROOT).contains} and an ASCII {@code (?i)} accept.
         */
        public boolean hasIgnoreCase(String literal) {
            return bypass || containsIgnoreCase(raw, literal)
                    || (plain != raw && containsIgnoreCase(plain, literal));
        }

        public boolean crystalHollows() {
            if (hollows == 0) {
                hollows = SkyBlockLocation.onIsland(
                        sbs.modid.client.helper.map.logic.HollowsTracker.ISLAND) ? (byte) 1 : (byte) 2;
            }
            return hollows == 1;
        }
    }

    // ------------------------------------------------------------------ prefilter factories

    /** Passes when the line contains any of {@code literals}, case-sensitive. */
    public static Prefilter any(String... literals) {
        return line -> {
            for (String l : literals) {
                if (line.has(l)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** Passes when the line contains any of {@code literals}, ignoring case. */
    public static Prefilter anyIgnoreCase(String... literals) {
        String[] lower = new String[literals.length];
        for (int i = 0; i < literals.length; i++) {
            lower[i] = literals[i].toLowerCase(Locale.ROOT);
        }
        return line -> {
            for (String l : lower) {
                if (line.hasIgnoreCase(l)) {
                    return true;
                }
            }
            return false;
        };
    }

    public static Prefilter or(Prefilter a, Prefilter b) {
        return line -> a.test(line) || b.test(line);
    }

    static boolean containsIgnoreCase(String hay, String needle) {
        int n = needle.length();
        int last = hay.length() - n;
        for (int i = 0; i <= last; i++) {
            if (hay.regionMatches(true, i, needle, 0, n)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ dispatch

    private static List<Listener> listeners;
    private static final Line SHARED = new Line();
    private static int depth;

    // Counters for the Perf overlay; cumulative, rates taken in summary().
    private static long lines;
    private static long invoked;
    private static long rejected;
    private static long bypassed;
    private static long shownAt;
    private static long shownLines;
    private static long shownInvoked;
    private static long shownRejected;
    private static String shown = "-";

    private ChatDispatch() {
    }

    private static Line current = SHARED;

    /** The line being dispatched, for a listener that prefilters parsers of its own (Diana). */
    static Line current() {
        return current;
    }

    static List<Listener> listeners() {
        if (listeners == null) {
            listeners = ChatListeners.build();
        }
        return listeners;
    }

    /** One received chat line, from the chat mixin. */
    public static void dispatch(Component message) {
        if (message == null) {
            return;
        }
        try (Perf.Section s = Perf.tick("chat")) {
            String raw = message.getString();
            Line line = depth == 0 ? SHARED : new Line();
            line.set(raw);
            depth++;
            try {
                run(listeners(), line, message);
            } finally {
                depth--;
            }
        }
    }

    /** The loop itself, separate so tests can drive it with their own listeners. */
    static void run(List<Listener> list, Line line, Component message) {
        Line outer = current;
        current = line;
        try {
            runAll(list, line, message);
        } finally {
            current = outer;
        }
    }

    private static void runAll(List<Listener> list, Line line, Component message) {
        lines++;
        if (line.bypass) {
            bypassed++;
        }
        for (int i = 0, n = list.size(); i < n; i++) {
            Listener l = list.get(i);
            if (!l.gate().open(line) || !l.prefilter().test(line)) {
                rejected++;
                continue;
            }
            invoked++;
            l.handler().onChat(line.plain, line.raw, message);
        }
    }

    /** "lines/s, listeners per line, rejects/s" since the last call (the overlay rebuilds on a timer). */
    public static String summary() {
        long now = System.currentTimeMillis();
        if (shownAt != 0 && now - shownAt >= 1000) {
            double sec = (now - shownAt) / 1000.0;
            long dl = lines - shownLines;
            shown = String.format(Locale.ROOT, "%.1f lines/s, %.1f / %d listeners per line, %.0f rejects/s, %d bypassed",
                    dl / sec, dl == 0 ? 0.0 : (invoked - shownInvoked) / (double) dl, listeners().size(),
                    (rejected - shownRejected) / sec, bypassed);
        }
        if (shownAt == 0 || now - shownAt >= 1000) {
            shownAt = now;
            shownLines = lines;
            shownInvoked = invoked;
            shownRejected = rejected;
        }
        return shown;
    }
}
