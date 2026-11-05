/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The per-puzzle toggle and colour, looked up by {@link PuzzleType}.
 *
 * <p>The config keeps one plain field per puzzle - {@code @Shareable}, named, and read back exactly
 * like every other setting - and this is the one place that maps a type onto its two fields. The
 * settings card, the coordinator and the blaze solver all go through it, so adding a puzzle is one
 * {@link #bind} line and not a switch in three files.
 *
 * <p><b>Colours are parsed on change, not per frame.</b> {@link #argb} keeps the last hex string it
 * saw per puzzle and only re-parses when the config holds a different string, so the render path
 * pays a reference comparison.
 */
public final class PuzzleSettings {

    /**
     * The answer colour both shipped solvers drew before colours were configurable - kept as every
     * puzzle's default so nothing looks different until the player picks a colour.
     */
    public static final int DEFAULT_ARGB = 0xFF55FF55;

    private record Binding(Predicate<SBSConfig.DungeonsSettings> enabled,
                           BiConsumer<SBSConfig.DungeonsSettings, Boolean> setEnabled,
                           Function<SBSConfig.DungeonsSettings, String> hex,
                           BiConsumer<SBSConfig.DungeonsSettings, String> setHex) {
    }

    private static final Map<PuzzleType, Binding> BINDINGS = new EnumMap<>(PuzzleType.class);

    static {
        bind(PuzzleType.CREEPER_BEAMS, c -> c.puzzleCreeperBeams, (c, v) -> c.puzzleCreeperBeams = v,
                c -> c.puzzleCreeperBeamsColorHex, (c, v) -> c.puzzleCreeperBeamsColorHex = v);
        bind(PuzzleType.THREE_WEIRDOS, c -> c.puzzleThreeWeirdos, (c, v) -> c.puzzleThreeWeirdos = v,
                c -> c.puzzleThreeWeirdosColorHex, (c, v) -> c.puzzleThreeWeirdosColorHex = v);
        bind(PuzzleType.TIC_TAC_TOE, c -> c.puzzleTicTacToe, (c, v) -> c.puzzleTicTacToe = v,
                c -> c.puzzleTicTacToeColorHex, (c, v) -> c.puzzleTicTacToeColorHex = v);
        bind(PuzzleType.WATER_BOARD, c -> c.puzzleWaterBoard, (c, v) -> c.puzzleWaterBoard = v,
                c -> c.puzzleWaterBoardColorHex, (c, v) -> c.puzzleWaterBoardColorHex = v);
        bind(PuzzleType.TELEPORT_MAZE, c -> c.puzzleTeleportMaze, (c, v) -> c.puzzleTeleportMaze = v,
                c -> c.puzzleTeleportMazeColorHex, (c, v) -> c.puzzleTeleportMazeColorHex = v);
        bind(PuzzleType.HIGHER_OR_LOWER, c -> c.puzzleHigherOrLower, (c, v) -> c.puzzleHigherOrLower = v,
                c -> c.puzzleHigherOrLowerColorHex, (c, v) -> c.puzzleHigherOrLowerColorHex = v);
        bind(PuzzleType.BOULDER, c -> c.puzzleBoulder, (c, v) -> c.puzzleBoulder = v,
                c -> c.puzzleBoulderColorHex, (c, v) -> c.puzzleBoulderColorHex = v);
        bind(PuzzleType.ICE_FILL, c -> c.puzzleIceFill, (c, v) -> c.puzzleIceFill = v,
                c -> c.puzzleIceFillColorHex, (c, v) -> c.puzzleIceFillColorHex = v);
        bind(PuzzleType.ICE_PATH, c -> c.puzzleIcePath, (c, v) -> c.puzzleIcePath = v,
                c -> c.puzzleIcePathColorHex, (c, v) -> c.puzzleIcePathColorHex = v);
        bind(PuzzleType.QUIZ, c -> c.puzzleQuiz, (c, v) -> c.puzzleQuiz = v,
                c -> c.puzzleQuizColorHex, (c, v) -> c.puzzleQuizColorHex = v);
    }

    // Parse cache for argb(), indexed by ordinal. Client thread only (tick and render).
    private static final String[] SEEN_HEX = new String[PuzzleType.values().length];
    private static final int[] SEEN_ARGB = new int[PuzzleType.values().length];

    static {
        java.util.Arrays.fill(SEEN_ARGB, DEFAULT_ARGB);   // a null field reads as the default
    }

    private PuzzleSettings() {
    }

    private static void bind(PuzzleType type, Predicate<SBSConfig.DungeonsSettings> enabled,
                             BiConsumer<SBSConfig.DungeonsSettings, Boolean> setEnabled,
                             Function<SBSConfig.DungeonsSettings, String> hex,
                             BiConsumer<SBSConfig.DungeonsSettings, String> setHex) {
        BINDINGS.put(type, new Binding(enabled, setEnabled, hex, setHex));
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** Whether every puzzle type has its two fields bound. Asserted by a test. */
    static boolean complete() {
        return BINDINGS.size() == PuzzleType.values().length;
    }

    /** The bound toggle read from a given settings object, for tests that need no ConfigManager. */
    static boolean toggleOf(PuzzleType type, SBSConfig.DungeonsSettings settings) {
        return BINDINGS.get(type).enabled().test(settings);
    }

    /** The bound colour field read from a given settings object, for the same tests. */
    static String hexOf(PuzzleType type, SBSConfig.DungeonsSettings settings) {
        return BINDINGS.get(type).hex().apply(settings);
    }

    /** This puzzle's own toggle, regardless of the master switch. */
    public static boolean toggle(PuzzleType type) {
        return BINDINGS.get(type).enabled().test(cfg());
    }

    public static void setToggle(PuzzleType type, boolean on) {
        BINDINGS.get(type).setEnabled().accept(cfg(), on);
    }

    /**
     * Whether this puzzle's solver may run: master on, its own toggle on, and a solver exists. A
     * stale {@code true} left in the config for a puzzle with no solver does nothing.
     */
    public static boolean enabled(PuzzleType type) {
        SBSConfig.DungeonsSettings dungeons = cfg();
        return dungeons.puzzleSolver && type.stage().available()
                && BINDINGS.get(type).enabled().test(dungeons);
    }

    /** The stored colour text: {@code RRGGBB}, or empty for the default. */
    public static String colorHex(PuzzleType type) {
        String hex = BINDINGS.get(type).hex().apply(cfg());
        return hex == null ? "" : hex;
    }

    public static void setColorHex(PuzzleType type, String hex) {
        BINDINGS.get(type).setHex().accept(cfg(), hex == null ? "" : hex);
    }

    /**
     * Moves the old Silverfish toggle and colour onto Ice Path once, then clears them, because the
     * two were one room (see {@link PuzzleType}). A Silverfish switch that was on turns Ice Path on;
     * a Silverfish colour is taken only when Ice Path has none of its own. Returns whether anything
     * changed. Called by {@code ConfigManager} on load.
     */
    @SuppressWarnings("deprecation")
    public static boolean migrateSilverfish(SBSConfig.DungeonsSettings settings) {
        if (settings == null) {
            return false;
        }
        boolean changed = false;
        if (settings.puzzleSilverfish) {
            settings.puzzleIcePath = true;
            settings.puzzleSilverfish = false;
            changed = true;
        }
        String hex = settings.puzzleSilverfishColorHex;
        if (hex != null && !hex.isEmpty()) {
            if (settings.puzzleIcePathColorHex == null || settings.puzzleIcePathColorHex.isEmpty()) {
                settings.puzzleIcePathColorHex = hex;
            }
            settings.puzzleSilverfishColorHex = "";
            changed = true;
        }
        return changed;
    }

    /**
     * The coordinator's per-room diagnostic, after the room name: the puzzle (or {@code none}), its
     * stage, both switches, and whether a solver therefore runs. Kept here so a test can check it
     * without loading the coordinator.
     */
    static String describeRoom(PuzzleType type, boolean master, boolean toggle, boolean registered,
                               String borders) {
        if (type == null) {
            return "none borders=" + borders;
        }
        boolean runs = master && toggle && type.stage().available() && registered;
        return type + " stage=" + type.stage() + " master=" + (master ? "on" : "off")
                + " toggle=" + (toggle ? "on" : "off") + (registered ? "" : " solver=missing")
                + " borders=" + borders + " -> " + (runs ? "runs" : "idle");
    }

    /** The colour this puzzle's answer is drawn in, opaque ARGB. Cheap on an unchanged config. */
    public static int argb(PuzzleType type) {
        String hex = BINDINGS.get(type).hex().apply(cfg());
        int i = type.ordinal();
        if (hex != SEEN_HEX[i]) {   // reference test on purpose: a new string means a new value
            Integer rgb = OverlayColor.parseHex(hex);
            SEEN_ARGB[i] = rgb == null ? DEFAULT_ARGB : 0xFF000000 | rgb;
            SEEN_HEX[i] = hex;
        }
        return SEEN_ARGB[i];
    }
}
