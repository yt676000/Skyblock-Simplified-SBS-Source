/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.garden.logic.PestTracker;
import sbs.modid.client.skills.garden.model.GardenPlotCatalog;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.Set;

/**
 * The plot grid itself: cells, pest flashing, the three command buttons and the status line, drawn
 * into whatever rectangle the caller gives it.
 *
 * <p>Split out of the screen so the standalone hotkey screen and the floating inventory window are
 * literally the same grid rather than two implementations that drift apart. Everything sizes itself
 * from the rectangle, so the window can be resized freely and the screen can be generous.
 */
public final class GardenPlotsGrid {

    private static final int CELL_GAP = 3;
    private static final int CELL_MIN = 12;
    private static final int CELL_MAX = 26;
    private static final int BUTTON_H = 14;
    private static final int BUTTON_GAP = 4;
    /** One full flash cycle of an infested plot. */
    private static final long FLASH_PERIOD_MS = 900L;
    /** The Garden's fixed 5×5 arrangement - the layout itself lives in {@link GardenPlotCatalog}. */
    private static final int SIZE = GardenPlotCatalog.GRID_SIZE;

    private GardenPlotsGrid() {
    }

    private static SBSConfig.GardenPlotsSettings cfg() {
        return ConfigManager.getInstance().get().gardenPlots;
    }

    // ------------------------------------------------------------------ layout

    /** Cell edge for the grid area, so the whole grid fits the rectangle it was handed. */
    private static int cellSize(int w, int gridH) {
        int byWidth = (w - (SIZE - 1) * CELL_GAP) / SIZE;
        int byHeight = (gridH - (SIZE - 1) * CELL_GAP) / SIZE;
        return Math.max(CELL_MIN, Math.min(CELL_MAX, Math.min(byWidth, byHeight)));
    }

    /** Height the buttons and status line take off the bottom. */
    private static int footerH(Font font, boolean withButtons) {
        return (withButtons ? BUTTON_H + BUTTON_GAP : 0) + font.lineHeight + 2;
    }

    // ------------------------------------------------------------------ drawing

    public static void draw(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                            int mouseX, int mouseY, boolean withButtons) {
        int gridH = h - footerH(font, withButtons);
        int cell = cellSize(w, gridH);
        int gridW = SIZE * cell + (SIZE - 1) * CELL_GAP;
        int gx = x + (w - gridW) / 2;

        GardenPlotCatalog catalog = GardenPlotCatalog.getInstance();
        PestTracker pests = PestTracker.getInstance();
        // The flash toggle only silences the flashing - the status line below always tells the
        // truth, so switching the flash off cannot make the grid CLAIM "No pests" while six plots
        // are infested.
        Set<Integer> reallyInfested = pests.infestedPlots();
        Set<Integer> infested = cfg().flashInfested ? reallyInfested : Set.of();

        // The map as the world lies: Hypixel's fixed numbering, the Garden house in the middle.
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                int cx = gx + col * (cell + CELL_GAP);
                int cy = y + row * (cell + CELL_GAP);
                int number = GardenPlotCatalog.numberAtGrid(row, col);
                if (number == GardenPlotCatalog.HOUSE) {
                    drawHouseCell(g, font, cx, cy, cell, mouseX, mouseY);
                } else {
                    drawCell(g, font, cx, cy, cell, number, catalog.icon(number),
                            infested.contains(number), mouseX, mouseY);
                }
            }
        }

        int bottom = y + h;
        if (withButtons) {
            int by = bottom - font.lineHeight - 2 - BUTTON_H;
            int bw = (w - BUTTON_GAP * 2) / 3;
            drawButton(g, font, x, by, bw, "Desk", mouseX, mouseY);
            drawButton(g, font, x + bw + BUTTON_GAP, by, bw, "Set Spawn", mouseX, mouseY);
            drawButton(g, font, x + (bw + BUTTON_GAP) * 2, by, bw, "Go To Spawn", mouseX, mouseY);
        }
        drawStatus(g, font, x, bottom - font.lineHeight, w, catalog, reallyInfested, pests);
    }

    /** The bed says "home": the middle cell is the Garden house, and clicking it warps to the Barn. */
    private static final ItemStack HOUSE_ICON =
            new ItemStack(net.minecraft.world.item.Items.BED.red());

    /**
     * The centre cell's teleport target. Hypixel names it rather than numbering it, which is why it
     * is a word here and a plot number everywhere else on this grid.
     */
    private static final String BARN_PLOT = "barn";

    /**
     * The centre cell: the Garden house with the NPCs - no plot, no number, never infested.
     * Clicking it teleports to the Barn.
     */
    private static void drawHouseCell(GuiGraphicsExtractor g, Font font, int cx, int cy, int cell,
                                      int mouseX, int mouseY) {
        boolean hovered = mouseX >= cx && mouseX < cx + cell && mouseY >= cy && mouseY < cy + cell;
        SciFiRender.roundedRectWithBorder(g, cx, cy, cell, cell, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);
        if (cell >= 16) {
            g.item(HOUSE_ICON, cx + (cell - 16) / 2, cy + (cell - 16) / 2);
        } else {
            String label = "H";
            g.text(font, Component.literal(label), cx + (cell - font.width(label)) / 2,
                    cy + (cell - font.lineHeight) / 2, SBSTheme.ACCENT_BRIGHT);
        }
    }

    private static void drawCell(GuiGraphicsExtractor g, Font font, int cx, int cy, int cell,
                                 int number, ItemStack icon, boolean infested,
                                 int mouseX, int mouseY) {
        boolean hovered = mouseX >= cx && mouseX < cx + cell && mouseY >= cy && mouseY < cy + cell;
        int border = SBSTheme.CARD_BORDER;
        if (infested) {
            // Pulse rather than blink on/off: a hard blink is easy to miss between two frames, and
            // the plot has to stay readable in the dark half of the cycle.
            long phase = System.currentTimeMillis() % FLASH_PERIOD_MS;
            double wave = (Math.sin(phase / (double) FLASH_PERIOD_MS * Math.PI * 2) + 1) / 2;
            border = ((int) (0x60 + wave * 0x9F) << 24) | 0x00FF4040;
        } else if (hovered) {
            border = SBSTheme.ACCENT_BRIGHT;
        }
        SciFiRender.roundedRectWithBorder(g, cx, cy, cell, cell, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, border);

        if (icon != null && !icon.isEmpty()) {
            g.item(icon, cx + (cell - 16) / 2, cy + (cell - 16) / 2);
        } else {
            String label = String.valueOf(number);
            g.text(font, Component.literal(label), cx + (cell - font.width(label)) / 2,
                    cy + (cell - font.lineHeight) / 2, SBSTheme.TEXT_MUTED);
        }
    }

    private static void drawButton(GuiGraphicsExtractor g, Font font, int bx, int by, int bw,
                                   String label, int mouseX, int mouseY) {
        boolean hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + BUTTON_H;
        SciFiRender.roundedRectWithBorder(g, bx, by, bw, BUTTON_H, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        String text = fit(font, label, bw - 4);
        g.text(font, Component.literal(text), bx + (bw - font.width(text)) / 2,
                by + (BUTTON_H - font.lineHeight) / 2 + 1,
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
    }

    /**
     * The one status line. Infestation first - it is the live information. "No pests" is only ever
     * said while the Pests widget is actually being read; a widget that is missing or unparsed says
     * SO instead, because a confident "No pests" in that state is a lie that points the player away
     * from the real problem (and exactly the report that got this line rewritten).
     */
    private static void drawStatus(GuiGraphicsExtractor g, Font font, int x, int y, int w,
                                   GardenPlotCatalog catalog, Set<Integer> infested,
                                   PestTracker pests) {
        String note;
        int color;
        if (!infested.isEmpty()) {
            note = infested.size() == 1 ? "1 plot infested" : infested.size() + " plots infested";
            color = 0xFFFF6060;
        } else if (!pests.onGarden()) {
            note = "Pests widget not seen - check /widgets";
            color = SBSTheme.TEXT_MUTED;
        } else if (!catalog.hasIcons()) {
            note = "No pests · open Configure Plots for icons";
            color = SBSTheme.TEXT_MUTED;
        } else {
            note = "No pests";
            color = SBSTheme.TEXT_MUTED;
        }
        note = fit(font, note, w);
        g.text(font, Component.literal(note), x + (w - font.width(note)) / 2, y, color);
    }

    /** Trims text to the width available, so a narrow window degrades instead of overflowing. */
    private static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String out = text;
        while (out.length() > 1 && font.width(out + "…") > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }

    // ------------------------------------------------------------------ input

    /** Handles a click inside the rectangle. Returns {@code true} when it hit something. */
    public static boolean click(int x, int y, int w, int h, double mx, double my) {
        Font font = Minecraft.getInstance().font;
        int bottom = y + h;
        int by = bottom - font.lineHeight - 2 - BUTTON_H;
        if (my >= by && my < by + BUTTON_H) {
            int bw = (w - BUTTON_GAP * 2) / 3;
            var cfg = cfg();
            if (mx >= x && mx < x + bw) {
                return run(cfg.deskCommand);
            }
            if (mx >= x + bw + BUTTON_GAP && mx < x + bw * 2 + BUTTON_GAP) {
                return run(cfg.setSpawnCommand);
            }
            if (mx >= x + (bw + BUTTON_GAP) * 2 && mx < x + (bw + BUTTON_GAP) * 2 + bw) {
                return run(cfg.gardenSpawnCommand);
            }
            return false;
        }
        Integer number = plotAt(x, y, w, h, mx, my, font);
        if (number == null) {
            return false;
        }
        if (number == GardenPlotCatalog.HOUSE) {
            // The centre cell teleports to the Barn, not to spawn. The two are not the same place,
            // and the Barn is the one worth a cell on this grid - it is where the Mousemat and the
            // rest of the plot's fixtures are. Spawn keeps its own dedicated footer button above,
            // so gardenSpawnCommand is still reachable and still configurable.
            return run(cfg().plotTeleportCommand + " " + BARN_PLOT);
        }
        return run(cfg().plotTeleportCommand + " " + number);
    }

    /** The plot number of the clicked cell, {@link GardenPlotCatalog#HOUSE} for the middle one. */
    private static Integer plotAt(int x, int y, int w, int h, double mx, double my, Font font) {
        int cell = cellSize(w, h - footerH(font, true));
        int gridW = SIZE * cell + (SIZE - 1) * CELL_GAP;
        int gx = x + (w - gridW) / 2;

        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                if (hits(gx, y, cell, col, row, mx, my)) {
                    return GardenPlotCatalog.numberAtGrid(row, col);
                }
            }
        }
        return null;
    }

    private static boolean hits(int gx, int gy, int cell, int col, int row, double mx, double my) {
        int cx = gx + col * (cell + CELL_GAP);
        int cy = gy + row * (cell + CELL_GAP);
        return mx >= cx && mx < cx + cell && my >= cy && my < cy + cell;
    }

    /**
     * Closes whatever screen is open, then sends the command. Hypixel will not warp you while a menu
     * is up, so a teleport that left the inventory open would silently do nothing.
     *
     * <p>Public because this is <b>the</b> Garden teleport path: the grid's clicks and the
     * infested-plot hotkey both go through it, so the screen-closing rule above is stated once and
     * cannot be forgotten by the next caller.
     */
    public static boolean run(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.setScreenAndShow(null);
        if (mc.player != null) {
            mc.player.connection.sendCommand(trimmed.startsWith("/") ? trimmed.substring(1) : trimmed);
        }
        return true;
    }
}
