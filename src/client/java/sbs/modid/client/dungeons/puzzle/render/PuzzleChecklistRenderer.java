/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;
import sbs.modid.client.core.render.CachedModel;
import sbs.modid.client.dungeons.puzzle.logic.ArchitectDraftReminder;
import sbs.modid.client.dungeons.puzzle.logic.PuzzleTabReader;
import sbs.modid.client.dungeons.puzzle.model.PuzzleEntry;
import sbs.modid.client.dungeons.puzzle.model.PuzzleStatus;
import sbs.modid.client.dungeons.puzzle.model.PuzzleTabSection;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The puzzle checklist strip drawn under the SBS Dungeon Map: this run's puzzles as the tab list
 * reports them, two to a row, each with a state mark.
 *
 * <p>Marks: {@code ✔} solved, {@code ✖} failed, {@code ?} anything the tab did not clearly say.
 * There is no "open" mark yet, on purpose - which glyph the tab uses for an open puzzle has not been
 * captured, and "?" is the honest rendering of "not known to be done".
 *
 * <p>Render path only draws: the rows are built in {@link #MODEL} when the reader publishes a new
 * {@link PuzzleTabSection} (it is immutable and replaced only when the tab changed), so an unchanged
 * section costs one {@code equals} on an identical reference and allocates nothing.
 */
public final class PuzzleChecklistRenderer {

    /** Rows of the strip at most; a run with more puzzles than {@code 2 * MAX_ROWS} shows "+N". */
    public static final int MAX_ROWS = 3;
    private static final float SCALE = 0.6f;
    private static final int LINE = 7;
    private static final int PAD = 3;
    private static final int GAP = 2;

    /** Space reserved under the map for the strip, card included - see {@code HudElement.DUNGEON_MAP}. */
    public static final int RESERVED_HEIGHT = GAP + PAD * 2 + MAX_ROWS * LINE;

    private record Cell(Component text, PuzzleStatus status) {
    }

    private record Model(List<Cell> cells, int rows) {
    }

    private static final Model EMPTY = new Model(List.of(), 0);

    private static final CachedModel<PuzzleTabSection, Model> MODEL =
            new CachedModel<>("puzzleChecklist", section -> build(section, MAX_ROWS));

    /** The same strip one row shorter, for when the failed-room line takes the last row. */
    private static final CachedModel<PuzzleTabSection, Model> MODEL_SHORT =
            new CachedModel<>("puzzleChecklistShort", section -> build(section, MAX_ROWS - 1));

    private PuzzleChecklistRenderer() {
    }

    /**
     * Draws the strip with its top edge {@link #GAP} below {@code y}: the checklist when
     * {@code checklist} is on, and the Architect's Draft reminder line as the last row when it has
     * one. The line takes a checklist row rather than adding one, so the reserved height under the
     * map does not change. Nothing when neither has anything to show.
     */
    public static void render(GuiGraphicsExtractor g, int x, int y, int width, boolean checklist) {
        Component warning = ArchitectDraftReminder.getInstance().cardLine();
        PuzzleTabSection section = PuzzleTabReader.getInstance().section();
        Model model = !checklist ? EMPTY : warning == null ? MODEL.get(section) : MODEL_SHORT.get(section);
        int rows = model.rows() + (warning == null ? 0 : 1);
        if (rows == 0) {
            return;
        }
        int top = y + GAP;
        HudCard.draw(g, x, top, width, PAD * 2 + rows * LINE);
        Font font = Minecraft.getInstance().font;
        int column = (width - PAD * 2) / 2;
        Matrix3x2fStack pose = g.pose();
        List<Cell> cells = model.cells();
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            int cx = x + PAD + (i % 2) * column;
            int cy = top + PAD + (i / 2) * LINE;
            pose.pushMatrix();
            pose.translate(cx, cy);
            pose.scale(SCALE);
            g.text(font, cell.text(), 0, 0, color(cell.status()));
            pose.popMatrix();
        }
        if (warning != null) {
            pose.pushMatrix();
            pose.translate(x + PAD, top + PAD + model.rows() * LINE);
            pose.scale(SCALE);
            g.text(font, warning, 0, 0, SBSTheme.WARN);
            pose.popMatrix();
        }
    }

    private static int color(PuzzleStatus status) {
        return switch (status) {
            case COMPLETE -> SBSTheme.TOGGLE_ON;
            case FAILED -> SBSTheme.WARN;
            case INCOMPLETE -> SBSTheme.TEXT;
            case UNKNOWN -> SBSTheme.TEXT_MUTED;
        };
    }

    private static Model build(PuzzleTabSection section, int maxRows) {
        if (section == null || section.entries().isEmpty()) {
            return EMPTY;
        }
        Font font = Minecraft.getInstance().font;
        // Column width in unscaled font pixels, from the map panel's inner width.
        int room = (int) ((sbs.modid.client.dungeons.run.render.DungeonMapRenderer.PANEL_SIZE - PAD * 2)
                / 2 / SCALE) - 4;
        List<PuzzleEntry> entries = section.entries();
        int capacity = maxRows * 2;
        boolean overflow = entries.size() > capacity;
        int shown = overflow ? capacity - 1 : entries.size();
        List<Cell> cells = new ArrayList<>(shown + 1);
        for (int i = 0; i < shown; i++) {
            PuzzleEntry entry = entries.get(i);
            String text = mark(entry.status()) + " " + entry.displayName();
            cells.add(new Cell(Component.literal(font.plainSubstrByWidth(text, room)), entry.status()));
        }
        if (overflow) {
            cells.add(new Cell(Component.literal("+" + (entries.size() - shown)), PuzzleStatus.UNKNOWN));
        }
        return new Model(List.copyOf(cells), (cells.size() + 1) / 2);
    }

    private static String mark(PuzzleStatus status) {
        return switch (status) {
            case COMPLETE -> "✔";
            case FAILED -> "✖";
            case INCOMPLETE -> "•";
            case UNKNOWN -> "?";
        };
    }
}
