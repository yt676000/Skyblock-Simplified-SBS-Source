/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.terminal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.terminal.TerminalSolutions.Hint;
import sbs.modid.client.ui.render.ActionMarks;
import sbs.modid.client.ui.render.MarkKind;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.MarkPalette;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.Map;

/**
 * Draws the open F7 / M7 terminal as one large SBS board over the vanilla chest, and forwards a
 * click on a cell to the real slot behind it.
 *
 * <p>The puzzle, the board state and the solution are all unchanged - this is the same
 * {@link TerminalSolver} answer at a size that can be read while a Necron is hitting you. Hypixel's
 * terminal is a 9-wide chest whose slots are 16 pixels across; at the moment the phase actually
 * matters you are reading those 16 pixels through a boss fight, and that is the whole reason this
 * exists.
 *
 * <p><b>It never invents a click.</b> A cell click becomes exactly the click the player made, on the
 * slot that cell stands for, through the same {@code PICKUP} primitive a normal slot click uses -
 * button and all, so a right click stays a right click (which the rubix terminal needs). The misclick
 * guard is asked first via {@link TerminalSolver#blocks}, so the board can never be the loophole that
 * lets through what the guard refuses on the vanilla slots.
 *
 * <p>Every click inside the screen is swallowed while the board is up. The board covers the menu
 * completely, so a click that fell through would land on whatever vanilla slot happens to sit under
 * the cursor - a slot the player cannot see and did not aim at.
 *
 * <p><b>Cells are painted, not blitted.</b> A pane or wool of a known dye becomes a filled cell in
 * that colour, because at this size a colour block reads faster and more certainly than a scaled-up
 * 16px texture; everything else (the item terminals' swords, dyes and heads) draws its real icon,
 * since there the item's identity <i>is</i> the puzzle. Anything already selected is dimmed and
 * ticked rather than removed, so the board keeps the shape the player memorised.
 *
 * <p>Marks, ticks and the melody column band are the shared mark language ({@link ActionMarks});
 * every colour on the board is a theme token or derived from one ({@link MarkPalette}).
 */
public final class TerminalBoardOverlay {

    private static final TerminalBoardOverlay INSTANCE = new TerminalBoardOverlay();

    /** Chest width; every terminal board is a multiple of this. */
    private static final int COLUMNS = 9;

    /** Cell edge at 100%, in pixels - roughly twice a vanilla slot. */
    private static final int BASE_CELL = 34;

    /** Smallest cell the board shrinks to before it lets the viewport clip it. */
    private static final int MIN_CELL = 14;

    private static final int GAP = 3;
    private static final int PAD = 10;
    /** Space between the title line and the instruction line. */
    private static final int LINE_GAP = 3;

    private static final String COMPLETE = "Terminal complete";

    /** Geometry of the last frame, which is what {@link #slotAt} hit-tests against. */
    private int boardX;
    private int boardY;
    private int headerH;
    private int cell;
    private int stride;
    private int rows;

    /** "4/10", rebuilt only when the packed progress changes. */
    private long progressKey = -1L;
    private String progressText = "";

    private TerminalBoardOverlay() {
    }

    public static TerminalBoardOverlay getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /**
     * Whether the board is covering this screen right now - i.e. whether input and tooltips belong
     * to us rather than to the vanilla menu.
     *
     * <p>Deliberately does not require a non-empty solution: a terminal the solver recognises but
     * cannot currently answer still gets the big board, because dropping back to the vanilla chest
     * mid-terminal would be far more disorienting than a board without marks.
     */
    public boolean isActive(AbstractContainerScreen<?> screen) {
        if (screen == null || !cfg().terminalSolver || !cfg().terminalCustomGui) {
            return false;
        }
        TerminalSolver solver = TerminalSolver.getInstance();
        return solver.type() != TerminalType.NONE && solver.isTracking(screen)
                && boardRows(screen.getMenu()) > 0;
    }

    /** True while the cursor is over the board, so the vanilla hovered-slot tooltip stays hidden. */
    public boolean coversForTooltip(AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
        return isActive(screen);
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /**
     * The board: a centred SBS card with the terminal's name and progress, a one-line instruction,
     * and the grid. Sized from the viewport - the configured scale is the preferred cell size, and
     * the card shrinks below it rather than hang off a small screen at a large GUI scale.
     */
    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        if (!isActive(screen)) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        TerminalSolver solver = TerminalSolver.getInstance();
        TerminalType type = solver.type();
        Font font = Minecraft.getInstance().font;
        MarkPalette palette = ActionMarks.palette();

        rows = boardRows(menu);
        headerH = PAD + font.lineHeight + LINE_GAP + font.lineHeight + PAD;
        int availW = Math.max(1, g.guiWidth() - SBSTheme.SCREEN_MARGIN);
        int availH = Math.max(1, g.guiHeight() - SBSTheme.SCREEN_MARGIN);
        int preferred = BASE_CELL * clamp(cfg().terminalGuiScale, 60, 200) / 100;
        int fitW = (availW - PAD * 2 + GAP) / COLUMNS - GAP;
        int fitH = (availH - headerH - PAD + GAP) / rows - GAP;
        cell = Math.max(MIN_CELL, Math.min(preferred, Math.min(fitW, fitH)));
        stride = cell + GAP;

        int gridW = COLUMNS * stride - GAP;
        int gridH = rows * stride - GAP;
        int boardW = gridW + PAD * 2;
        int boardH = headerH + gridH + PAD;
        boardX = (g.guiWidth() - boardW) / 2;
        boardY = (g.guiHeight() - boardH) / 2;

        g.fill(0, 0, g.guiWidth(), g.guiHeight(), palette.backdrop);
        SciFiRender.glow(g, boardX, boardY, boardW, boardH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, boardX, boardY, boardW, boardH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, boardX + 1, boardY + 1, boardW - 2, boardH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        long progress = solver.progress();
        boolean complete = TerminalMarks.complete(progress);
        drawHeader(g, font, type, progress, boardW);

        Map<Integer, Hint> hints = TerminalSolver.getInstance().hints();
        int gridTop = boardY + headerH;
        int gridLeft = boardX + PAD;
        int column = solver.melodyColumn();
        if (type == TerminalType.MELODY && column >= 0 && column < COLUMNS) {
            ActionMarks.band(g, gridLeft + column * stride - 1, gridTop - 1, cell + 2, gridH + 2);
        }
        for (int index = 0; index < rows * COLUMNS; index++) {
            int x = gridLeft + (index % COLUMNS) * stride;
            int y = gridTop + (index / COLUMNS) * stride;
            drawCell(g, font, palette, type, TerminalSolutions.item(menu, index),
                    complete ? null : hints.get(index), x, y);
        }
        if (complete) {
            drawComplete(g, font, palette, gridLeft, gridTop, gridW, gridH);
        }
    }

    /** Title left, progress right, instruction under both - each measured against the card. */
    private void drawHeader(GuiGraphicsExtractor g, Font font, TerminalType type, long progress, int boardW) {
        int inner = boardW - PAD * 2;
        int x = boardX + PAD;
        int y = boardY + PAD;
        String count = progressText(progress);
        int countW = count.isEmpty() ? 0 : font.width(count);
        if (countW > 0) {
            g.text(font, Component.literal(count), x + inner - countW, y,
                    TerminalMarks.complete(progress) ? ActionMarks.edgeColor(MarkKind.DO) : SBSTheme.ACCENT, false);
        }
        int titleRoom = Math.max(0, inner - (countW > 0 ? countW + 8 : 0));
        g.text(font, Component.literal(fit(font, TerminalMarks.nameOf(type), titleRoom)), x, y,
                SBSTheme.ACCENT_BRIGHT, false);
        g.text(font, Component.literal(fit(font, TerminalMarks.instructionOf(type), inner)), x,
                y + font.lineHeight + LINE_GAP, SBSTheme.TEXT_MUTED, false);
    }

    private String progressText(long progress) {
        if (progress != progressKey) {
            progressKey = progress;
            int total = TerminalMarks.total(progress);
            progressText = total <= 0 ? "" : TerminalMarks.done(progress) + "/" + total;
        }
        return progressText;
    }

    /** Ellipsises {@code text} to {@code width}; the strings here are constants, so this rarely cuts. */
    private static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        int dots = font.width("...");
        return width <= dots ? "" : font.plainSubstrByWidth(text, width - dots) + "...";
    }

    /** The finished board: every cell veiled, and the word for it in the middle. */
    private static void drawComplete(GuiGraphicsExtractor g, Font font, MarkPalette palette,
                                     int x, int y, int w, int h) {
        SciFiRender.roundedRectRaw(g, x, y, w, h, SBSTheme.PANEL_CORNER, palette.doneVeil);
        int textW = font.width(COMPLETE) + 16;
        int pillH = font.lineHeight + 8;
        int px = x + (w - textW) / 2;
        int py = y + (h - pillH) / 2;
        SciFiRender.roundedRectRaw(g, px - 1, py - 1, textW + 2, pillH + 2, 5, palette.doEdge);
        SciFiRender.roundedRectRaw(g, px, py, textW, pillH, 4, palette.badgeFill);
        g.centeredText(font, Component.literal(COMPLETE), x + w / 2, py + 4, palette.doEdge);
    }

    /**
     * One cell: its background, its content, whether it is already done, and the solver's mark.
     *
     * <p>The mark is drawn <b>over</b> the content on purpose - a mark behind a full-bleed colour
     * cell would be invisible, and the mark is the one thing on this board that must never be missed.
     */
    private void drawCell(GuiGraphicsExtractor g, Font font, MarkPalette palette, TerminalType type,
                          ItemStack stack, Hint hint, int x, int y) {
        int radius = Math.max(2, cell / 6);
        if (stack.isEmpty()) {
            SciFiRender.roundedRectRaw(g, x, y, cell, cell, radius, palette.cellEmpty);
            return;
        }
        int dye = dyeColor(stack);
        if (dye != 0) {
            SciFiRender.roundedRectRaw(g, x, y, cell, cell, radius, 0xFF000000 | dye);
        } else {
            SciFiRender.roundedRectRaw(g, x, y, cell, cell, radius, palette.cellItem);
            drawItem(g, stack, x, y);
        }
        if (stack.hasFoil()) {
            ActionMarks.done(g, font, x, y, cell, cell);
        }
        if (hint != null) {
            MarkKind kind = TerminalMarks.kindOf(hint);
            ActionMarks.cell(g, x, y, cell, kind);
            ActionMarks.badge(g, font, x, y, cell, cell, TerminalMarks.badgeOf(type, hint), kind);
        }
        if (type == TerminalType.ORDER && dye != 0) {
            // The number IS the item here (it is the stack count), and nothing else on the cell
            // carries it once the pane is drawn as a colour block. Drawn after the mark, shadowed,
            // so the due number is never hidden by its own fill.
            g.centeredText(font, Component.literal(String.valueOf(stack.getCount())), x + cell / 2,
                    y + (cell - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
        }
    }

    /** The item's icon, centred and scaled to the cell (its own 16px art, enlarged). */
    private void drawItem(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        float scale = cell / 16f * 0.75f;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x + cell / 2f, y + cell / 2f);
        pose.scale(scale);
        g.item(stack, -8, -8);
        pose.popMatrix();
    }

    /**
     * The dye colour to paint a cell in, or {@code 0} for an item that is not a coloured block.
     *
     * <p>Only blocks whose colour is the point are painted: a pane, wool, clay or terracotta. A dye
     * <i>item</i> is deliberately excluded even though its name carries a colour - on the item
     * terminals it is one of the things to pick out, and it has to look like itself.
     */
    private static int dyeColor(ItemStack stack) {
        String path = TerminalSolutions.idPath(stack);
        boolean colouredBlock = path.contains("glass_pane") || path.contains("_wool")
                || path.contains("terracotta") || path.contains("_concrete") || path.contains("stained_glass");
        if (!colouredBlock) {
            return 0;
        }
        String colour = TerminalSolutions.colourOf(stack);
        DyeColor dye = colour == null ? null : DyeColor.byName(colour, null);
        return dye == null ? 0 : dye.getTextureDiffuseColor() & 0xFFFFFF;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Forwards a click on a cell to the slot it stands for, and swallows everything else.
     *
     * <p>Returning {@code true} unconditionally is the point: while the board is up the vanilla menu
     * is not visible, so no click may reach it by accident.
     */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!isActive(screen)) {
            return false;
        }
        int slot = slotAt(event.x(), event.y());
        if (slot >= 0 && !TerminalSolver.getInstance().blocks(slot)) {
            clickSlot(screen, slot, event.button());
        }
        return true;
    }

    /** The board slot under a screen position, or {@code -1} when the cursor is off the grid. */
    private int slotAt(double mouseX, double mouseY) {
        if (cell <= 0 || rows <= 0) {
            return -1;
        }
        int gridLeft = boardX + PAD;
        int gridTop = boardY + headerH;
        int column = (int) Math.floor((mouseX - gridLeft) / stride);
        int row = (int) Math.floor((mouseY - gridTop) / stride);
        if (column < 0 || column >= COLUMNS || row < 0 || row >= rows) {
            return -1;
        }
        // Inside the cell rather than in the gap after it: a click in the 3px seam belongs to
        // neither neighbour, and guessing one of them is how a terminal gets a click it never earned.
        if ((mouseX - gridLeft) % stride > cell || (mouseY - gridTop) % stride > cell) {
            return -1;
        }
        return row * COLUMNS + column;
    }

    /** The player's own click, on the slot their cell stands for - same primitive, same button. */
    private static void clickSlot(AbstractContainerScreen<?> screen, int slot, int button) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null || minecraft.player == null) {
            return;
        }
        minecraft.gameMode.handleContainerInput(screen.getMenu().containerId, slot, button,
                ContainerInput.PICKUP, minecraft.player);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Rows the terminal itself occupies, or 0 when the menu is not a whole number of chest rows. */
    private static int boardRows(AbstractContainerMenu menu) {
        int upper = Math.max(0, menu.getItems().size() - 36);
        return upper > 0 && upper % COLUMNS == 0 ? upper / COLUMNS : 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
