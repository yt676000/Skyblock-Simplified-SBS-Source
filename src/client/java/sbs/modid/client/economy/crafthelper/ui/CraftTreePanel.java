/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.crafthelper.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.economy.crafthelper.logic.CraftHelper;
import sbs.modid.client.economy.crafthelper.logic.CraftPins;
import sbs.modid.client.economy.crafthelper.model.CraftNode;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.SbsRecipe;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * One Craft Helper tree on screen: a header (icon, name, {@code - n +}, an action on the right), the
 * rows with their tree lines, a draggable scrollbar, and the recipe list a row opens when its item has
 * several recipes. The HUD card and the Recipe Viewer's Tree view each own one, in their own
 * coordinate space - the HUD maps the mouse through its transform before calling in.
 *
 * <p>Everything a frame draws comes from {@link CraftHelper.Model}, built on change; this class only
 * positions and draws it. The header's few widths are measured once per model version.
 *
 * <p>The recipe list follows the popup checklist in {@code ui/AGENTS.md}: drawn after the rows, offered
 * every click first, opaque, and the host suppresses its own hover tooltips while it is open.
 */
public final class CraftTreePanel {

    public static final int HEADER = 18;
    private static final int PAD = 4;
    private static final int GUTTER = SciFiScrollbar.WIDTH + 3;

    private static final Component MINUS = Component.literal("−");
    private static final Component PLUS = Component.literal("+");
    private static final Component CLOSE = Component.literal("✖");
    private static final Component NO_RECIPE = Component.literal("No recipe known - a base material.");
    private static final Component LOADING = Component.literal("Recipe data not loaded yet.");

    /** What the right end of the header does. */
    public enum Action {
        /** HUD: closes the panel. */
        CLOSE,
        /** Viewer: puts this tree on the HUD, or takes it off. */
        HUD_TOGGLE
    }

    private final Action action;
    private final SciFiScrollbar bar = new SciFiScrollbar();
    private int scroll;

    // Layout of the last frame, in the caller's coordinates.
    private int x;
    private int y;
    private int w;
    private int h;
    private int rowsTop;
    private int lineH;
    private int visibleRows;
    private CraftHelper.Model model;

    // Header layout, measured once per model version.
    private long laidOut = -1;
    private Component quantityText;
    private int minusX;
    private int quantityX;
    private int plusX;
    private int actionX;
    private int actionW;
    private int minusW;
    private int plusW;
    private Component actionText;
    private boolean actionOn;

    // Recipe list popup.
    private CraftNode menuNode;
    private List<Component> menuLines = List.of();
    private int menuX;
    private int menuY;
    private int menuW;

    public CraftTreePanel(Action action) {
        this.action = action;
    }

    /** Whether the recipe list is open; the host suppresses its tooltips while it is. */
    public boolean popupOpen() {
        return menuNode != null;
    }

    /** Back to the top and the list closed - on a new item. */
    public void reset() {
        scroll = 0;
        menuNode = null;
    }

    /** Height the panel wants for {@code rows} visible rows. */
    public static int heightFor(int rows) {
        return HEADER + PAD + rows * (Minecraft.getInstance().font.lineHeight + 2) + PAD;
    }

    // ------------------------------------------------------------------ render

    /**
     * Draws {@code model} into the box. {@code interactive} is false on the bare HUD, where there is
     * no cursor: hover states and the popup are skipped, the bar is only an indicator.
     */
    public void render(GuiGraphicsExtractor g, CraftHelper.Model model, int x, int y, int w, int h,
                       int mouseX, int mouseY, boolean interactive) {
        Font font = Minecraft.getInstance().font;
        if (this.model == null || model == null || !this.model.id().equals(model.id())) {
            reset();
        }
        this.model = model;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.lineH = font.lineHeight + 2;
        this.rowsTop = y + HEADER + PAD;
        this.visibleRows = Math.max(1, (y + h - PAD - rowsTop) / lineH);
        if (model == null) {
            return;
        }
        layoutHeader(font, model);
        drawHeader(g, font, model, mouseX, mouseY, interactive);

        List<CraftHelper.RenderRow> rows = model.rows();
        int maxScroll = Math.max(0, rows.size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int barX = x + w - PAD - SciFiScrollbar.WIDTH;
        bar.set(barX, rowsTop, visibleRows * lineH, rows.size(), visibleRows);

        if (rows.size() == 1 && model.root().children().isEmpty()) {
            g.text(font, model.root().recipeCount() == 0 ? NO_RECIPE : LOADING, x + PAD,
                    rowsTop + lineH, SBSTheme.TEXT_MUTED);
        }
        int left = x + PAD;
        int right = x + w - PAD - GUTTER;
        g.enableScissor(left, rowsTop, right, rowsTop + visibleRows * lineH);
        int end = Math.min(rows.size(), scroll + visibleRows);
        for (int i = scroll; i < end; i++) {
            CraftHelper.RenderRow row = rows.get(i);
            int ry = rowsTop + (i - scroll) * lineH;
            if (interactive && menuNode == null && mouseX >= left && mouseX < right
                    && mouseY >= ry && mouseY < ry + lineH) {
                g.fill(left, ry, right, ry + lineH, SBSTheme.CARD_BG_HOVER);
            }
            int ty = ry + 1;
            g.text(font, row.connectors(), left, ty, SBSTheme.TEXT_MUTED);
            g.text(font, row.marker(), left + row.markerX(), ty, SBSTheme.ACCENT);
            g.text(font, row.mark(), left + row.markX(), ty, row.markColor());
            g.text(font, row.counts(), left + row.countsX(), ty, row.countsColor());
            g.text(font, row.name(), left + row.nameX(), ty, row.nameColor());
            if (row.recipeTag() != null) {
                g.text(font, row.recipeTag(), left + row.recipeTagX(), ty,
                        row.row().node().recipeCount() > 1 ? SBSTheme.ACCENT : SBSTheme.TEXT_MUTED);
            }
        }
        g.disableScissor();
        bar.render(g, scroll, interactive ? mouseX : Integer.MIN_VALUE, interactive ? mouseY : Integer.MIN_VALUE);

        if (interactive && menuNode != null) {
            drawMenu(g, font, mouseX, mouseY);
        }
    }

    private void layoutHeader(Font font, CraftHelper.Model model) {
        boolean on = model.id().equals(CraftPins.getInstance().shown());
        if (laidOut == model.version() && actionOn == on) {
            return;
        }
        laidOut = model.version();
        actionOn = on;
        actionText = action == Action.CLOSE ? CLOSE
                : Component.literal(on ? "On HUD ✔" : "Show on HUD");
        actionW = font.width(actionText);
        quantityText = Component.literal(Integer.toString(model.quantity()));
        // Right to left, relative to the panel's right edge: action, gap, +, quantity, -.
        minusW = font.width(MINUS);
        plusW = font.width(PLUS);
        actionX = actionW + PAD;
        plusX = actionX + 10 + plusW;
        quantityX = plusX + 4 + font.width(quantityText);
        minusX = quantityX + 4 + minusW;
    }

    private void drawHeader(GuiGraphicsExtractor g, Font font, CraftHelper.Model model, int mouseX, int mouseY,
                            boolean interactive) {
        g.item(model.icon().displayStack(), x + PAD, y + 1);
        int ty = y + (HEADER - font.lineHeight) / 2 + 1;
        int right = x + w;
        int titleRight = right - minusX - 6;
        g.enableScissor(x + PAD + 20, y, titleRight, y + HEADER);
        g.text(font, model.title(), x + PAD + 20, ty, model.titleColor());
        g.disableScissor();
        drawControl(g, font, MINUS, minusW, right - minusX, ty, mouseX, mouseY, interactive, SBSTheme.TEXT);
        g.text(font, quantityText, right - quantityX, ty, SBSTheme.TEXT);
        drawControl(g, font, PLUS, plusW, right - plusX, ty, mouseX, mouseY, interactive, SBSTheme.TEXT);
        drawControl(g, font, actionText, actionW, right - actionX, ty, mouseX, mouseY, interactive,
                action == Action.CLOSE ? CraftHelper.MISSING : (actionOn ? CraftHelper.DONE : SBSTheme.ACCENT));
        g.fill(x + PAD, y + HEADER, x + w - PAD, y + HEADER + 1, SBSTheme.CARD_BORDER);
    }

    private static void drawControl(GuiGraphicsExtractor g, Font font, Component text, int textW, int cx, int ty,
                                    int mouseX, int mouseY, boolean interactive, int color) {
        boolean hover = interactive && mouseX >= cx - 2 && mouseX < cx + textW + 2
                && mouseY >= ty - 3 && mouseY < ty + font.lineHeight + 2;
        g.text(font, text, cx, ty, hover ? SBSTheme.ACCENT_BRIGHT : color);
    }

    private void drawMenu(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int menuH = menuLines.size() * lineH + 4;
        SciFiRender.roundedRectWithBorder(g, menuX, menuY, menuW, menuH, SBSTheme.CORNER_RADIUS,
                SBSTheme.CARD_BG | 0xFF000000, SBSTheme.ACCENT);
        int current = menuNode.recipeIndex();
        for (int i = 0; i < menuLines.size(); i++) {
            int ly = menuY + 2 + i * lineH;
            boolean hover = mouseX >= menuX && mouseX < menuX + menuW && mouseY >= ly && mouseY < ly + lineH;
            if (hover) {
                g.fill(menuX + 1, ly, menuX + menuW - 1, ly + lineH, SBSTheme.CARD_BG_HOVER | 0xFF000000);
            }
            g.text(font, menuLines.get(i), menuX + 4, ly + 1, i == current ? CraftHelper.DONE : SBSTheme.TEXT);
        }
    }

    // ------------------------------------------------------------------ input

    /** A click in the panel's coordinates. Returns whether it was consumed. */
    public boolean click(double mx, double my, int button) {
        if (model == null) {
            return false;
        }
        if (menuNode != null) {
            pickFromMenu(mx, my);
            return true;    // a click outside the open list closes it and does nothing else
        }
        if (mx < x || mx >= x + w || my < y || my >= y + h) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        if (bar.handleClick(mx, my, scroll, value -> scroll = value)) {
            return true;
        }
        if (my < y + HEADER + 1) {
            headerClick(mx);
            return true;
        }
        int index = scroll + (int) ((my - rowsTop) / lineH);
        if (my >= rowsTop && index >= 0 && index < model.rows().size() && index < scroll + visibleRows) {
            rowClick(model.rows().get(index), mx - (x + PAD), rowsTop + (index - scroll) * lineH);
        }
        return true;
    }

    private void headerClick(double mx) {
        int right = x + w;
        String id = model.id();
        if (hit(mx, right - actionX, actionW)) {
            if (action == Action.CLOSE || actionOn) {
                CraftPins.getInstance().show(null);
            } else {
                CraftPins.getInstance().show(id);
            }
        } else if (hit(mx, right - plusX, plusW)) {
            ensurePinned(id);
            CraftPins.getInstance().addQuantity(id, 1);
        } else if (hit(mx, right - minusX, minusW)) {
            CraftPins.getInstance().addQuantity(id, -1);
        }
    }

    private static void ensurePinned(String id) {
        if (!CraftPins.getInstance().isPinned(id)) {
            CraftPins.getInstance().togglePin(id);
        }
    }

    private static boolean hit(double mx, int left, int width) {
        return mx >= left - 3 && mx < left + width + 3;
    }

    private void rowClick(CraftHelper.RenderRow row, double localX, int rowY) {
        CraftNode node = row.row().node();
        if (row.recipeTag() != null && node.recipeCount() > 1 && localX >= row.recipeTagX()) {
            openMenu(node, rowY);
            return;
        }
        if (row.row().expandable()) {
            CraftHelper.getInstance().toggle(model.id(), node.path());
        }
    }

    private void openMenu(CraftNode node, int rowY) {
        Font font = Minecraft.getInstance().font;
        List<SbsRecipe> recipes = CraftHelper.getInstance().candidates(node.id());
        List<Component> lines = new ArrayList<>(recipes.size());
        int width = 0;
        for (int i = 0; i < recipes.size(); i++) {
            StringBuilder text = new StringBuilder().append(i + 1).append(": ");
            List<ItemRef> ingredients = recipes.get(i).ingredients;
            for (int j = 0; j < ingredients.size(); j++) {
                ItemRef ingredient = ingredients.get(j);
                text.append(j == 0 ? "" : ", ").append(ingredient.count).append("x ").append(ingredient.name);
            }
            String line = font.plainSubstrByWidth(text.toString(), Math.max(60, w - 12));
            lines.add(Component.literal(line));
            width = Math.max(width, font.width(line));
        }
        if (lines.isEmpty()) {
            return;
        }
        menuNode = node;
        menuLines = List.copyOf(lines);
        menuW = Math.min(width + 8, w - 4);
        menuX = x + w - menuW - 2;
        int menuH = lines.size() * lineH + 4;
        menuY = rowY + lineH;
        if (menuY + menuH > y + h) {
            menuY = Math.max(y, rowY - menuH);
        }
    }

    private void pickFromMenu(double mx, double my) {
        if (mx >= menuX && mx < menuX + menuW && my >= menuY + 2) {
            int index = (int) ((my - menuY - 2) / lineH);
            if (index >= 0 && index < menuLines.size()) {
                CraftPins.getInstance().chooseRecipe(menuNode.id(), index);
            }
        }
        menuNode = null;
    }

    /** Wheel over the panel scrolls it. */
    public boolean scroll(double mx, double my, double amount) {
        if (model == null || mx < x || mx >= x + w || my < y || my >= y + h) {
            return false;
        }
        if (menuNode == null) {
            int max = Math.max(0, model.rows().size() - visibleRows);
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(amount) * 3));
        }
        return true;
    }

    public boolean drag(double my) {
        return bar.handleDrag(my, value -> scroll = value);
    }

    public boolean release() {
        return bar.release();
    }

    /** Escape closes the recipe list first; returns whether it did. */
    public boolean escape() {
        if (menuNode == null) {
            return false;
        }
        menuNode = null;
        return true;
    }
}
