/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.warp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.command.SBSCommands;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.helper.warp.WarpCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * The custom warp menu: one box per island, laid out in a grid.
 *
 * <p>Clicking a box's <b>title</b> warps to the island itself; clicking a line inside the box warps
 * to that spot on the island. Every click goes through {@link SBSCommands#run(String)} – the same
 * path a typed command takes, so command shortcuts apply here too.
 *
 * <p>The destinations live in {@link WarpCatalog}, not here: this class only lays them out, so the
 * list can grow without touching the drawing.
 */
public final class WarpMenuScreen extends Screen {

    private static final int BOX_GAP = 6;
    private static final int BOX_PAD = 5;
    private static final int TITLE_H = 13;
    private static final int ROW_H = 11;
    private static final int MIN_BOX_W = 96;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int gridTop;

    /** Per-frame hit boxes: {x, y, w, h} -> the command to run. */
    private final List<int[]> hitRects = new ArrayList<>();
    private final List<String> hitCommands = new ArrayList<>();

    private int columns;
    private int boxWidth;

    /** Vertical scroll offset in pixels, and the max it may reach (set each frame from the layout). */
    private int scrollOffset;
    private int maxScroll;

    public WarpMenuScreen() {
        super(Component.literal("Warp Menu"));
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 420, 700);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 260, 460);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        innerX = panelX + SBSTheme.PANEL_PADDING;
        contentWidth = panelW - SBSTheme.PANEL_PADDING * 2;
        gridTop = dividerY + SBSTheme.GAP_AFTER_HEADER;

        // Column count is driven by the widest box the catalog actually needs, so every label
        // renders in full. Squeezing in one more column would only buy ellipses.
        int needed = widestBox();
        columns = Math.max(1, Math.min((contentWidth + BOX_GAP) / (needed + BOX_GAP),
                WarpCatalog.ISLANDS.size()));
        boxWidth = (contentWidth - (columns - 1) * BOX_GAP) / columns;

        addRenderableOnly(new PanelRenderable());
    }

    /**
     * The width the widest box needs to show its longest line untruncated: island titles and
     * "• label   note" rows including the bullet and the gap.
     *
     * <p>An island's own <b>note</b> is deliberately excluded – the Portal Hub's sentence is ~280px
     * and would force every box in the grid that wide. Notes wrap instead ({@link #wrap}).
     */
    private int widestBox() {
        int widest = MIN_BOX_W;
        for (WarpCatalog.Island island : WarpCatalog.ISLANDS) {
            widest = Math.max(widest, this.font.width(island.name()) + BOX_PAD * 2);
            for (WarpCatalog.Warp warp : island.warps()) {
                int row = this.font.width("• " + warp.label()) + BOX_PAD * 2;
                if (!warp.note().isEmpty()) {
                    row += this.font.width(warp.note()) + 6;
                }
                widest = Math.max(widest, row);
            }
        }
        return widest;
    }

    /** Greedy word wrap to {@code maxWidth}; never splits a word, never returns empty. */
    private List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && this.font.width(candidate) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines.isEmpty() ? List.of(text) : lines;
    }

    /** Height of an island's box: title, its wrapped note, then one row per sub-warp. */
    private int boxHeight(WarpCatalog.Island island) {
        int h = BOX_PAD + TITLE_H + island.warps().size() * ROW_H + BOX_PAD;
        if (!island.note().isEmpty()) {
            h += wrap(island.note(), boxWidth - BOX_PAD * 2).size() * ROW_H;
        }
        return h;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        // Only accept clicks inside the scrollable grid viewport, so a hit rect belonging to a box
        // that is clipped away above/below cannot be triggered through the header or panel edge.
        int gridBottom = panelY + panelH - SBSTheme.PANEL_PADDING;
        if (event.y() < gridTop || event.y() >= gridBottom) {
            return false;
        }
        for (int i = 0; i < hitRects.size(); i++) {
            int[] r = hitRects.get(i);
            if (event.x() >= r[0] && event.x() < r[0] + r[2]
                    && event.y() >= r[1] && event.y() < r[1] + r[3]) {
                String command = hitCommands.get(i);
                // Close first: the warp is a server action, and leaving the menu open over a
                // loading island would just be in the way.
                Minecraft.getInstance().setScreenAndShow(null);
                SBSCommands.run(command);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll <= 0 || scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        // One notch = one row-ish; negative scrollY (wheel down) moves the content up.
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(scrollY) * 24));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = WarpMenuScreen.this.font;
            hitRects.clear();
            hitCommands.clear();

            g.fill(0, 0, WarpMenuScreen.this.width, WarpMenuScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Warp Menu"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + SBSTheme.PANEL_PADDING, dividerY, panelX + panelW - SBSTheme.PANEL_PADDING,
                    dividerY + 1, SBSTheme.ACCENT);

            drawGrid(g, mouseX, mouseY);
        }

        /**
         * Lays the boxes out column by column, each column tracking its own y – islands have
         * different heights, so a fixed row grid would leave big holes under the short ones.
         *
         * <p>The grid scrolls: boxes are packed in content space, then drawn shifted by
         * {@link #scrollOffset} inside a scissor-clipped viewport. Previously a box that ran past the
         * panel was silently dropped (and so was unreachable) – with scrolling every warp stays
         * visible and clickable no matter how long the catalog grows.
         */
        private void drawGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            int bottom = panelY + panelH - SBSTheme.PANEL_PADDING;
            int viewportH = bottom - gridTop;

            // Pack boxes into the shortest column first, in content space (pre-scroll).
            int[] columnY = new int[columns];
            java.util.Arrays.fill(columnY, gridTop);
            List<int[]> layout = new ArrayList<>();   // {islandIndex, x, contentY, h}
            for (int idx = 0; idx < WarpCatalog.ISLANDS.size(); idx++) {
                int col = 0;
                for (int i = 1; i < columns; i++) {
                    if (columnY[i] < columnY[col]) {
                        col = i;
                    }
                }
                int h = boxHeight(WarpCatalog.ISLANDS.get(idx));
                layout.add(new int[]{idx, innerX + col * (boxWidth + BOX_GAP), columnY[col], h});
                columnY[col] += h + BOX_GAP;
            }

            int contentBottom = gridTop;
            for (int y : columnY) {
                contentBottom = Math.max(contentBottom, y - BOX_GAP);
            }
            int contentH = contentBottom - gridTop;
            maxScroll = Math.max(0, contentH - viewportH);
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

            // Clip to the grid so scrolled boxes never bleed over the header or the panel edge.
            g.enableScissor(panelX, gridTop, panelX + panelW, bottom);
            for (int[] box : layout) {
                int screenY = box[2] - scrollOffset;
                if (screenY + box[3] < gridTop || screenY > bottom) {
                    continue;   // fully outside the viewport – nothing to draw or click
                }
                drawBox(g, WarpCatalog.ISLANDS.get(box[0]), box[1], screenY, box[3], mouseX, mouseY);
            }
            g.disableScissor();

            if (maxScroll > 0) {
                int trackX = panelX + panelW - 4;
                float ratio = viewportH / (float) contentH;
                int thumbH = Math.max(16, (int) (viewportH * ratio));
                int thumbY = gridTop + (int) ((viewportH - thumbH) * (scrollOffset / (float) maxScroll));
                g.fill(trackX, gridTop, trackX + 2, gridTop + viewportH, SBSTheme.CARD_BORDER);
                g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, SBSTheme.ACCENT);
            }
        }

        private void drawBox(GuiGraphicsExtractor g, WarpCatalog.Island island,
                             int x, int y, int h, int mouseX, int mouseY) {
            var font = WarpMenuScreen.this.font;
            SciFiRender.roundedRectWithBorder(g, x, y, boxWidth, h, SBSTheme.CORNER_RADIUS,
                    SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);

            // Title = the island's own warp.
            int titleY = y + BOX_PAD;
            boolean titleHovered = hit(mouseX, mouseY, x + 2, titleY - 1, boxWidth - 4, TITLE_H);
            if (titleHovered) {
                SciFiRender.roundedRect(g, x + 2, titleY - 1, boxWidth - 4, TITLE_H,
                        SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG_HOVER);
            }
            g.text(font, Component.literal((titleHovered ? "§b" : "§f")
                            + trim(island.name(), boxWidth - 10)),
                    x + BOX_PAD, titleY + 1, SBSTheme.ACCENT_BRIGHT);
            register(x + 2, titleY - 1, boxWidth - 4, TITLE_H, island.command());
            g.fill(x + BOX_PAD, titleY + TITLE_H - 2, x + boxWidth - BOX_PAD, titleY + TITLE_H - 1,
                    SBSTheme.ACCENT_SOFT);

            int rowY = titleY + TITLE_H;
            for (String line : wrap(island.note(), boxWidth - BOX_PAD * 2)) {
                if (island.note().isEmpty()) {
                    break;
                }
                g.text(font, Component.literal("§8" + line), x + BOX_PAD, rowY, SBSTheme.TEXT_MUTED);
                rowY += ROW_H;
            }
            for (WarpCatalog.Warp warp : island.warps()) {
                boolean hovered = hit(mouseX, mouseY, x + 2, rowY - 1, boxWidth - 4, ROW_H);
                if (hovered) {
                    SciFiRender.roundedRect(g, x + 2, rowY - 1, boxWidth - 4, ROW_H,
                            SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG_HOVER);
                }
                // The note reads muted and right-aligned; the label is trimmed against it.
                String note = warp.note();
                int noteW = note.isEmpty() ? 0 : font.width(note) + 4;
                if (!note.isEmpty()) {
                    g.text(font, Component.literal("§8" + note),
                            x + boxWidth - BOX_PAD - font.width(note), rowY, SBSTheme.TEXT_MUTED);
                }
                g.text(font, Component.literal((hovered ? "§b› " : "§7• ")
                                + trim(warp.label(), boxWidth - 12 - noteW)),
                        x + BOX_PAD, rowY, SBSTheme.TEXT);
                register(x + 2, rowY - 1, boxWidth - 4, ROW_H, warp.command());
                rowY += ROW_H;
            }
        }

        private boolean hit(int mouseX, int mouseY, int x, int y, int w, int h) {
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        }

        private void register(int x, int y, int w, int h, String command) {
            hitRects.add(new int[]{x, y, w, h});
            hitCommands.add(command);
        }

        private String trim(String text, int maxWidth) {
            var font = WarpMenuScreen.this.font;
            if (font.width(text) <= maxWidth) {
                return text;
            }
            return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
        }
    }
}
