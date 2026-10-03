/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.terminal;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.dungeons.terminal.TerminalSolutions.Hint;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The terminal solver's test bench: runs {@link TerminalSolverTest} and shows every case with its
 * verdict, plus the board it ran on with the solver's own marks drawn over it - so a failure can be
 * looked at instead of guessed at.
 *
 * <p>The board view is the point. Green outlines are what the solver said to click (with its label,
 * "-1" meaning a right click), and a <b>red outline is a slot the case expected and the solver
 * missed</b>. Melody's cases are the ones worth opening: they show the layout the solver assumes,
 * which is the thing to hold a real terminal up against.
 *
 * <p>Nothing here touches a dungeon, a server or the player's inventory - the boards are chest menus
 * built in memory and thrown away when the screen closes.
 */
public final class TerminalTestScreen extends Screen {

    private static final int KEY_ESCAPE = 256;

    private static final int PANEL_W = 452;
    private static final int PANEL_H = 250;
    private static final int LIST_W = 176;
    private static final int ROW_H = 13;
    private static final int CELL = 18;
    private static final int COLUMNS = 9;

    private static final int MISSED = 0xFFFF5555;

    private final List<TerminalSolverTest.Result> results = new ArrayList<>();
    private final List<int[]> buttonRects = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();

    private int selected;
    private int panelX;
    private int panelY;

    public TerminalTestScreen() {
        super(Component.literal("Terminal Solver Test"));
    }

    @Override
    protected void init() {
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;
        addRenderableOnly(new PanelRenderable());
        if (results.isEmpty()) {
            run();
        }
    }

    private void run() {
        results.clear();
        results.addAll(TerminalSolverTest.runAll());
        selected = 0;
        for (int i = 0; i < results.size(); i++) {
            if (!results.get(i).passed()) {
                selected = i; // open on the first failure: that is what you came to look at
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();
        for (int i = 0; i < buttonRects.size(); i++) {
            int[] rect = buttonRects.get(i);
            if (mx >= rect[0] && mx < rect[0] + rect[2] && my >= rect[1] && my < rect[1] + rect[3]) {
                buttonActions.get(i).run();
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = TerminalTestScreen.this.font;
            buttonRects.clear();
            buttonActions.clear();

            g.fill(0, 0, TerminalTestScreen.this.width, TerminalTestScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, PANEL_W, PANEL_H, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, PANEL_W, PANEL_H, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, PANEL_W - 2, PANEL_H - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            String passed = TerminalSolverTest.summary(results);
            boolean allGreen = !results.isEmpty() && passed.startsWith(results.size() + "/");
            g.text(font, Component.literal("§bTerminal Solver Test §7· " + (allGreen ? "§a" : "§c")
                    + passed + " §7passed"), panelX + 10, panelY + 8, SBSTheme.ACCENT_BRIGHT);

            if (results.isEmpty()) {
                g.text(font, Component.literal("§7Join a world first - the boards borrow its inventory."),
                        panelX + 10, panelY + 26, SBSTheme.TEXT_MUTED);
            } else {
                drawList(g, font, mouseX, mouseY);
                drawBoard(g, font);
            }

            int by = panelY + PANEL_H - 22;
            button(g, font, panelX + 10, by, 110, 16, "§aRun again", mouseX, mouseY, TerminalTestScreen.this::run);
            button(g, font, panelX + PANEL_W - 90, by, 80, 16, "Close", mouseX, mouseY,
                    TerminalTestScreen.this::onClose);
        }

        /** The case list: verdict, name, and the selected row highlighted. */
        private void drawList(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            int x = panelX + 10;
            int y = panelY + 26;
            for (int i = 0; i < results.size(); i++) {
                TerminalSolverTest.Result result = results.get(i);
                boolean hover = mouseX >= x && mouseX < x + LIST_W && mouseY >= y && mouseY < y + ROW_H;
                if (i == selected || hover) {
                    SciFiRender.roundedRect(g, x - 2, y - 2, LIST_W, ROW_H,
                            3, i == selected ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
                }
                String mark = result.passed() ? "§a✔" : "§c✘";
                g.text(font, Component.literal(mark + " §f" + trim(font, result.name(), LIST_W - 20)),
                        x, y, SBSTheme.TEXT);
                int row = i;
                buttonRects.add(new int[]{x - 2, y - 2, LIST_W, ROW_H});
                buttonActions.add(() -> selected = row);
                y += ROW_H;
            }
        }

        /** The selected case: its board with the solver's marks, and the detail line under it. */
        private void drawBoard(GuiGraphicsExtractor g, Font font) {
            TerminalSolverTest.Result result = results.get(Math.min(selected, results.size() - 1));
            int x = panelX + LIST_W + 18;
            int y = panelY + 26;
            int width = PANEL_W - LIST_W - 28;

            g.text(font, Component.literal((result.passed() ? "§a" : "§c")
                            + trim(font, result.name(), width)), x, y, SBSTheme.TEXT);
            y += 12;
            for (String line : wrap(font, result.detail(), width)) {
                g.text(font, Component.literal("§7" + line), x, y, SBSTheme.TEXT_MUTED);
                y += 10;
            }
            if (!result.hasBoard()) {
                return;
            }
            y += 4;
            int rows = Math.max(1, result.upper() / COLUMNS);
            for (int slot = 0; slot < result.upper(); slot++) {
                int sx = x + (slot % COLUMNS) * CELL;
                int sy = y + (slot / COLUMNS) * CELL;
                SciFiRender.roundedRect(g, sx, sy, CELL - 2, CELL - 2, 2, SBSTheme.CARD_BG);
                ItemStack stack = result.menu().getSlot(slot).getItem();
                if (stack != null && !stack.isEmpty()) {
                    g.item(stack, sx, sy);
                }
            }
            for (Map.Entry<Integer, Hint> entry : result.hints().entrySet()) {
                mark(g, font, x, y, entry.getKey(), entry.getValue().color(), entry.getValue().label());
            }
            for (Integer slot : result.expected().keySet()) {
                if (!result.hints().containsKey(slot)) {
                    mark(g, font, x, y, slot, MISSED, "?");   // expected, but the solver said nothing
                }
            }
            g.text(font, Component.literal("§8green = solver · red = missed"),
                    x, y + rows * CELL + 2, SBSTheme.TEXT_MUTED);
        }

        private void mark(GuiGraphicsExtractor g, Font font, int originX, int originY, int slot,
                          int color, String label) {
            int sx = originX + (slot % COLUMNS) * CELL;
            int sy = originY + (slot / COLUMNS) * CELL;
            TerminalSolver.outline(g, sx, sy, color);
            if (label != null && !label.isEmpty()) {
                g.text(font, Component.literal(label), sx + 15 - font.width(label), sy + 8, color);
            }
        }
    }

    private void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String label,
                        int mouseX, int mouseY, Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        g.centeredText(font, Component.literal(label), x + w / 2, y + (h - font.lineHeight) / 2 + 1,
                SBSTheme.TEXT);
        buttonRects.add(new int[]{x, y, w, h});
        buttonActions.add(action);
    }

    private static String trim(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && font.width(cut + "…") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    /** Breaks the detail line on spaces so a long list of wrong slots stays readable. */
    private static List<String> wrap(Font font, String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate) > width && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
            if (lines.size() >= 3) {
                break;
            }
        }
        if (!line.isEmpty() && lines.size() < 3) {
            lines.add(line.toString());
        }
        return lines;
    }
}
