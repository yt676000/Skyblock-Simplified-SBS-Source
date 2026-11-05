/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle;

import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.dungeons.puzzle.logic.PuzzleSettings;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.puzzle.model.SolverStage;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * The Puzzle Solvers card (Dungeons &gt; Puzzle Solvers): the master switch, the map checklist, and
 * one toggle plus one colour per {@link PuzzleType}.
 *
 * <p><b>Every puzzle gets its rows, solver or not.</b> A puzzle with no solver shows its toggle
 * greyed out with the reason, so the card is also the honest list of which rooms are covered - a
 * missing row reads as "forgot to add it", a disabled one as "not built".
 *
 * <p>The master, blaze-order, Quiz and Three Weirdos rows used to live on the Dungeons card. They
 * keep their config fields, and their old option ids are passed as cross-module aliases so a
 * favourite or a jump link to them still lands here.
 *
 * <p>Solvers only highlight. Nothing on this card clicks, aims, moves or sends anything, and there
 * is deliberately no misclick guard for puzzle rooms - see {@code docs/features/dungeon-puzzle-solvers.md}.
 */
public final class PuzzleSolversModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public PuzzleSolversModule() {
    }

    @Override
    public String id() {
        return "puzzle_solvers";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.DUNGEONS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.PUZZLE_SOLVERS;
    }

    @Override
    public String displayName() {
        return "Puzzle Solvers";
    }

    @Override
    public String description() {
        return "Highlights puzzle-room answers, one switch and colour per puzzle; never clicks for you";
    }

    @Override
    public int accentColor() {
        return 0xFFB86CE8;   // the map's puzzle-room purple
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.toggle("Puzzle Solvers", () -> cfg().puzzleSolver,
                        () -> { cfg().puzzleSolver = !cfg().puzzleSolver; save(); })
                .anchor("master", "dungeons:puzzle_solver_blaze")
                .describe("Master switch for every puzzle solver below. Off, nothing is solved or "
                        + "drawn. Solvers only highlight - they never click, aim or move for you, "
                        + "and a puzzle the tab list reports as solved or failed is not drawn."));
        rows.add(SettingRow.toggle("Puzzle Checklist On Map", () -> cfg().puzzleChecklist,
                        () -> { cfg().puzzleChecklist = !cfg().puzzleChecklist; save(); })
                .describe("A strip under the SBS Dungeon Map listing this run's puzzles from the "
                        + "tab list with their state. Works without the solvers. A state the tab "
                        + "did not clearly show is drawn as ?, never as open."));
        addDraftReminder(rows);

        for (PuzzleType type : PuzzleType.values()) {
            addPuzzle(rows, type);
        }
        return rows;
    }

    /**
     * The Architect's First Draft reminder. Works without the solvers: it reads chat and the tab,
     * and only tells the player - it never uses or moves a draft.
     */
    private static void addDraftReminder(List<SettingRow> rows) {
        rows.add(SettingRow.label("§bArchitect's First Draft"));
        rows.add(SettingRow.toggle("Architect's Draft reminder", () -> cfg().architectDraftReminder,
                        () -> { cfg().architectDraftReminder = !cfg().architectDraftReminder; save(); })
                .describe("When a puzzle fails - yours or a teammate's - says which room failed and "
                        + "that an Architect's First Draft resets it, with how many you have in your "
                        + "inventory and sacks. Sacks are counted once you have opened the sack "
                        + "holding your drafts. Only a reminder: you use the draft yourself."));
        rows.add(SettingRow.toggle("Show Draft Price When None", () -> cfg().architectDraftShowPrice,
                        () -> { cfg().architectDraftShowPrice = !cfg().architectDraftShowPrice; save(); })
                .describe("If you have no drafts, the reminder adds what one costs to buy on the "
                        + "Bazaar right now. Shows nothing when the price is not loaded.")
                .disabledWhile(() -> !cfg().architectDraftReminder, "Turn on Architect's Draft reminder first"));
        rows.add(SettingRow.toggle("Failed Room Line Under Map", () -> cfg().architectDraftCardLine,
                        () -> { cfg().architectDraftCardLine = !cfg().architectDraftCardLine; save(); })
                .describe("Keeps a line under the SBS Dungeon Map, for example \"Higher or Lower "
                        + "failed - draft available\", until the room is reset with a draft, solved, "
                        + "or you leave the dungeon.")
                .disabledWhile(() -> !cfg().architectDraftReminder, "Turn on Architect's Draft reminder first"));
        rows.addAll(sbs.modid.client.core.alert.AlertChannelRows.forAlert("architect_draft",
                "a puzzle fails", () -> cfg().architectDraftChannels,
                mask -> { cfg().architectDraftChannels = mask; save(); }));
    }

    private static void addPuzzle(List<SettingRow> rows, PuzzleType type) {
        String name = type.settingsName();
        SolverStage stage = type.stage();
        rows.add(SettingRow.label("§b" + name));

        SettingRow toggle = SettingRow.toggle(name, () -> PuzzleSettings.toggle(type),
                        () -> { PuzzleSettings.setToggle(type, !PuzzleSettings.toggle(type)); save(); })
                .anchor(type.name().toLowerCase(java.util.Locale.ROOT), previousIds(type))
                .describe(describe(type))
                .disabledWhile(() -> !cfg().puzzleSolver || !stage.available(),
                        stage.available() ? "Turn on Puzzle Solvers first" : "No solver for this puzzle yet");
        rows.add(toggle);

        rows.add(SettingRow.color(name + " Colour", () -> PuzzleSettings.colorHex(type),
                        () -> PuzzleSettings.DEFAULT_ARGB,
                        () -> openPicker(type))
                .anchor(type.name().toLowerCase(java.util.Locale.ROOT) + "_colour")
                .describe("The colour " + name + "'s answer is outlined in. Empty is the default "
                        + "green.")
                .disabledWhile(() -> !stage.available(), "No solver for this puzzle yet"));

        if (type == PuzzleType.THREE_WEIRDOS) {
            rows.add(SettingRow.toggle("Three Weirdos: Mark Wrong Chests", () -> cfg().puzzleWeirdosMarkWrong,
                            () -> { cfg().puzzleWeirdosMarkWrong = !cfg().puzzleWeirdosMarkWrong; save(); })
                    .anchor("three_weirdos_mark_wrong")
                    .describe("Also draws a faint red box on the two chests without the reward. "
                            + "Default off."));
        }
        if (type == PuzzleType.ICE_FILL) {
            rows.add(SettingRow.toggle("Ice Fill: One Line", () -> cfg().puzzleIceFillLine,
                            () -> { cfg().puzzleIceFillLine = !cfg().puzzleIceFillLine; save(); })
                    .anchor("ice_fill_line")
                    .describe("On: the rest of the walk is one line in the Ice Fill colour. Off: a "
                            + "flat box per straight run, the next one bright and the rest faint."));
        }
        if (type == PuzzleType.HIGHER_OR_LOWER) {
            rows.add(SettingRow.toggle("Blaze Highest First", () -> cfg().blazeHighestFirst,
                            () -> { cfg().blazeHighestFirst = !cfg().blazeHighestFirst; save(); })
                    .anchor("blaze_highest_first", "dungeons:blaze_highest_first")
                    .describe("Only a fallback now: the order is read from the room (where its "
                            + "chest sits). This decides only when the room cannot be read."));
        }
    }

    /** The option ids these rows had on the Dungeons card, so favourites follow them here. */
    private static String[] previousIds(PuzzleType type) {
        return switch (type) {
            case QUIZ -> new String[] {"dungeons:puzzle_quiz"};
            case THREE_WEIRDOS -> new String[] {"dungeons:puzzle_three_weirdos"};
            default -> new String[0];
        };
    }

    private static String describe(PuzzleType type) {
        return switch (type) {
            case QUIZ -> "Boxes the correct answer to Oruo's questions. A question that is not in "
                    + "the answer set shows nothing rather than a guess - one wrong click fails "
                    + "the room.";
            case HIGHER_OR_LOWER -> "Boxes the blaze to shoot next, read from the blazes' health "
                    + "tags.";
            case CREEPER_BEAMS -> "Testing, default off. Boxes the lit lanterns that belong together, "
                    + "one colour and number per pair, worked out from the beam passing through the "
                    + "creeper. Lanterns it is not sure about stay unmarked.";
            case THREE_WEIRDOS -> "Boxes the chest with the reward once the weirdo who has it has "
                    + "spoken to you. You still open the chest yourself; nothing is drawn until "
                    + "the answer is certain.";
            case TIC_TAC_TOE -> "Boxes the button for your best move, read from the board on the "
                    + "wall. Nothing while a square is still loading. You still click yourself.";
            case ICE_FILL -> "Draws the walk over every ice tile of the floor you are on, ending at "
                    + "the exit; the next straight run is brighter.";
            case BOULDER -> "Marks the boxes to push and which way, in order, worked out from the "
                    + "board as it is now. Re-solves after every push.";
            case ICE_PATH -> "The silverfish puzzle (formerly the separate \"Silverfish\" entry). "
                    + "Draws the fewest hits from where the silverfish rests to the exit, one line per "
                    + "slide; the first is labelled with the direction to push it. Re-plans after "
                    + "every hit.";
            case TELEPORT_MAZE -> "Test version, off by default. Dims pads you have already used "
                    + "and, after a teleport or two, boxes the pad predicted to lead to the centre.";
            default -> "No solver for " + type.displayName() + " exists yet. The puzzle still "
                    + "shows on the map checklist.";
        };
    }

    private static void openPicker(PuzzleType type) {
        net.minecraft.client.gui.screens.Screen previous =
                GuiStateManager.getInstance().getCurrentScreen();
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                new sbs.modid.client.ui.theme.ThemeColorPickerScreen(
                        "Puzzle  •  " + type.displayName(), PuzzleSettings.colorHex(type),
                        value -> {
                            PuzzleSettings.setColorHex(type, value);
                            save();
                        }, previous));
    }
}
