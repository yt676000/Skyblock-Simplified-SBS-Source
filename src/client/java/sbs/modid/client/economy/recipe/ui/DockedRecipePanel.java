/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.recipe.logic.MarketSearch;
import sbs.modid.client.economy.recipe.logic.RecipePricing;
import sbs.modid.client.economy.recipe.logic.RecipeRegistry;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.SbsRecipe;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A recipe shown <b>inside</b> the Recipe Viewer side panel (or its window), so it stays beside the
 * inventory or crafting table while the player crafts instead of replacing the container screen.
 *
 * <p>Opened by a plain click on a panel item; the state is a singleton, so the same recipe is still
 * there when the next container opens. Shows the 3x3 grid, the result, and the price table
 * ({@link RecipePricing}): every row with its insta-buy and buy-order price.
 *
 * <p>Layout is measured against the panel it gets. At 1280x720 on GUI scale 4 the room beside the
 * inventory is about 60 px wide, so below {@link #WIDE} each price row takes two lines (amount and
 * name, then the two prices) and the result goes under the grid; wider panels get one line per row
 * with a name column. Rows that do not fit scroll with the wheel.
 *
 * <p>Input: click an ingredient icon to open its recipe ({@code <} steps back), Middle Click any icon
 * or row for {@code /bz} / {@code /ahsearch}, click any amount to copy it, {@code Full} opens the
 * full-screen viewer, {@code x} closes the recipe back to the item grid.
 */
final class DockedRecipePanel {

    private static final int CELL = 18;
    /** Panel width from which the price table gets a name column and the result sits beside the grid. */
    private static final int WIDE = 150;
    private static final long COPIED_FLASH_MS = 1200L;

    private record State(ItemRef item, boolean usages, List<SbsRecipe> recipes, int index) {
    }

    private record Hit(int x0, int y0, int x1, int y1, Runnable action) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private ItemRef item;
    private boolean usages;
    private List<SbsRecipe> recipes = List.of();
    private int index;
    private final Deque<State> history = new ArrayDeque<>();
    private int scroll;

    /** Rebuilt every frame: click boxes, and the item under the mouse (tooltip / Middle Click). */
    private final List<Hit> hits = new ArrayList<>();
    private ItemRef hovered;

    private String copiedText;
    private long copiedAt;
    private int copiedX;
    private int copiedY;

    boolean isOpen() {
        return item != null;
    }

    void open(ItemRef ref, boolean showUsages) {
        if (item != null) {
            history.push(new State(item, usages, recipes, index));
        }
        show(ref, showUsages);
    }

    private void show(ItemRef ref, boolean showUsages) {
        this.item = ref;
        this.usages = showUsages;
        RecipeRegistry registry = RecipeRegistry.getInstance();
        this.recipes = showUsages ? registry.usagesFor(ref.lookupId()) : registry.recipesFor(ref.lookupId());
        this.index = 0;
        this.scroll = 0;
    }

    void close() {
        item = null;
        history.clear();
        hits.clear();
        hovered = null;
    }

    private void back() {
        if (history.isEmpty()) {
            close();
            return;
        }
        State s = history.pop();
        item = s.item();
        usages = s.usages();
        recipes = s.recipes();
        index = s.index();
        scroll = 0;
    }

    /** The item under the mouse in the last rendered frame, or {@code null}. */
    ItemRef hovered() {
        return hovered;
    }

    // ------------------------------------------------------------------ render

    void render(GuiGraphicsExtractor g, int[] b, int mouseX, int mouseY) {
        hits.clear();
        hovered = null;
        if (item == null || b[2] < CELL * 3 || b[3] < CELL * 2) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int x = b[0];
        int right = b[0] + b[2];
        int bottom = b[1] + b[3];
        int y = b[1];
        int lh = font.lineHeight;

        // Header: "<" back, the item name (yields), "Full" and "x" on the right.
        int closeX = right - font.width("x");
        int fullX = closeX - 4 - font.width("Full");
        button(g, font, "<", x, y, mouseX, mouseY, this::back);
        button(g, font, "Full", fullX, y, mouseX, mouseY, this::openFullScreen);
        button(g, font, "x", closeX, y, mouseX, mouseY, this::close);
        int nameX = x + font.width("<") + 4;
        String title = (usages ? "Uses: " : "") + item.name;
        g.text(font, Component.literal(font.plainSubstrByWidth(title, Math.max(0, fullX - 4 - nameX))),
                nameX, y, SBSTheme.ACCENT_BRIGHT);
        y += lh + 3;

        SbsRecipe recipe = recipes.isEmpty() ? null : recipes.get(Math.min(index, recipes.size() - 1));
        if (recipes.size() > 1) {
            String counter = (index + 1) + "/" + recipes.size();
            int cw = font.width(counter);
            int cx = x + (b[2] - cw) / 2;
            button(g, font, "<", cx - 10, y, mouseX, mouseY,
                    () -> { index = (index - 1 + recipes.size()) % recipes.size(); scroll = 0; });
            g.text(font, Component.literal(counter), cx, y, SBSTheme.TEXT_MUTED);
            button(g, font, ">", cx + cw + 4, y, mouseX, mouseY,
                    () -> { index = (index + 1) % recipes.size(); scroll = 0; });
            y += lh + 3;
        }
        if (recipe == null) {
            g.textWithWordWrap(font, Component.literal(usages ? "Used in no known recipe." : "No crafting recipe known."),
                    x, y, b[2], SBSTheme.TEXT_MUTED);
            drawCopied(g, font);
            return;
        }

        boolean wide = b[2] >= WIDE;
        for (int i = 0; i < 9; i++) {
            int cx = x + (i % 3) * CELL;
            int cy = y + (i / 3) * CELL;
            SciFiRender.roundedRectWithBorder(g, cx, cy, CELL - 1, CELL - 1,
                    SBSTheme.SLOT_CORNER, SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
            ItemRef cell = recipe.grid != null ? recipe.grid[i] : null;
            if (cell != null) {
                icon(g, font, cell, cx, cy, mouseX, mouseY, () -> open(cell, false));
            }
        }
        int resX;
        int resY;
        if (b[2] >= CELL * 3 + 14 + CELL) {
            g.text(font, Component.literal(">"), x + CELL * 3 + 4, y + CELL + 5, SBSTheme.ACCENT);
            resX = x + CELL * 3 + 12;
            resY = y + CELL;
            y += CELL * 3 + 3;
        } else {
            resX = x + CELL;
            resY = y + CELL * 3 + 2;
            y = resY + CELL + 3;
        }
        if (recipe.result != null) {
            SciFiRender.roundedRectWithBorder(g, resX, resY, CELL - 1, CELL - 1,
                    SBSTheme.SLOT_CORNER, SBSTheme.CARD_BG, SBSTheme.ACCENT);
            ItemRef result = recipe.result;
            icon(g, font, result, resX, resY, mouseX, mouseY,
                    () -> { if (usages) { open(result, false); } });
        }

        drawPrices(g, font, recipe, x, y, right, bottom, wide, mouseX, mouseY);
        drawCopied(g, font);
    }

    private void drawPrices(GuiGraphicsExtractor g, Font font, SbsRecipe recipe, int x, int top, int right,
                            int bottom, boolean wide, int mouseX, int mouseY) {
        RecipePricing.Table table = RecipePricing.of(recipe);
        List<RecipePricing.Line> lines = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        lines.add(new RecipePricing.Line(null, 0, table.totalInsta(), table.totalOrder(), true));
        labels.add(table.complete() ? "Total" : "Total*");
        if (table.result() != null) {
            lines.add(table.result());
            labels.add("= " + table.result().item().name);
        }
        for (RecipePricing.Line l : table.ingredients()) {
            lines.add(l);
            labels.add(l.item().name);
        }

        int lh = font.lineHeight;
        int rowH = wide ? lh + 2 : lh * 2 + 3;
        int colW = Math.max(font.width("Buy order"), font.width("00.0M")) + 4;
        int orderRight = right;
        int instaRight = wide ? right - colW : right - Math.max(0, (right - x) / 2) - 2;
        // Header: which column is which, measured to the width there is.
        String instaHead = wide ? "Insta-buy" : "Insta";
        String orderHead = wide ? "Buy order" : "Order";
        text(g, font, instaHead, instaRight - font.width(instaHead), top, SBSTheme.TEXT_MUTED);
        text(g, font, orderHead, orderRight - font.width(orderHead), top, SBSTheme.TEXT_MUTED);
        int y = top + lh + 2;
        int fit = Math.max(1, (bottom - y) / rowH);
        scroll = Math.max(0, Math.min(scroll, lines.size() - fit));
        if (lines.size() > fit && !wide) {
            // no spare header width in a narrow panel - the position rides on the left of the header
            text(g, font, (scroll + 1) + "/" + lines.size(), x, top, SBSTheme.TEXT_MUTED);
        } else if (lines.size() > fit) {
            text(g, font, "Item " + (scroll + 1) + "/" + lines.size(), x, top, SBSTheme.TEXT_MUTED);
        } else if (wide) {
            text(g, font, "Item", x, top, SBSTheme.TEXT_MUTED);
        }

        for (int i = scroll; i < lines.size() && y + rowH <= bottom + 2; i++) {
            RecipePricing.Line line = lines.get(i);
            String label = labels.get(i);
            ItemRef rowItem = line.item();
            boolean total = rowItem == null;
            if (rowItem != null && mouseX >= x && mouseX < right && mouseY >= y - 1 && mouseY < y + rowH - 1) {
                hovered = rowItem;
                g.fill(x - 1, y - 1, right + 1, y + rowH - 1, SBSTheme.CARD_BG);
            }
            int colour = total ? SBSTheme.ACCENT_BRIGHT : (i == 1 && table.result() != null ? SBSTheme.HUD_MANA : SBSTheme.TEXT);
            int tx = x;
            if (!total) {
                String amount = line.count() + "x";
                copyable(g, font, amount, String.valueOf(line.count()), tx, y, SBSTheme.ACCENT, mouseX, mouseY);
                tx += font.width(amount) + 3;
            }
            int nameRight = wide ? instaRight - colW - 2 : right;
            text(g, font, font.plainSubstrByWidth(label, Math.max(0, nameRight - tx)), tx, y, colour);
            int priceY = wide ? y : y + lh + 1;
            price(g, font, line.insta(), instaRight, priceY, mouseX, mouseY);
            price(g, font, line.order(), orderRight, priceY, mouseX, mouseY);
            y += rowH;
        }
    }

    private void price(GuiGraphicsExtractor g, Font font, Long value, int right, int y, int mouseX, int mouseY) {
        if (value == null) {
            text(g, font, "?", right - font.width("?"), y, SBSTheme.TEXT_MUTED);
            return;
        }
        String shown = NumberDisplay.shorten(value);
        copyable(g, font, shown, String.valueOf(value), right - font.width(shown), y, SBSTheme.TEXT, mouseX, mouseY);
    }

    private void copyable(GuiGraphicsExtractor g, Font font, String shown, String copy, int x, int y, int colour,
                          int mouseX, int mouseY) {
        int w = font.width(shown);
        Hit hit = new Hit(x - 1, y - 1, x + w + 1, y + font.lineHeight, () -> copy(copy, x + w / 2, y));
        text(g, font, shown, x, y, hit.contains(mouseX, mouseY) ? SBSTheme.ACCENT_BRIGHT : colour);
        hits.add(hit);
    }

    private void copy(String text, int x, int y) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        copiedText = text;
        copiedAt = System.currentTimeMillis();
        copiedX = x;
        copiedY = y;
    }

    private void drawCopied(GuiGraphicsExtractor g, Font font) {
        if (copiedText == null || System.currentTimeMillis() - copiedAt >= COPIED_FLASH_MS) {
            return;
        }
        String msg = "Copied " + copiedText;
        int w = font.width(msg);
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int x = Math.max(2, Math.min(copiedX - w / 2, screenW - w - 2));
        g.fill(x - 2, copiedY - 12, x + w + 2, copiedY - 1, SBSTheme.CARD_BG);
        g.text(font, Component.literal(msg), x, copiedY - 10, SBSTheme.TOGGLE_ON);
    }

    private void icon(GuiGraphicsExtractor g, Font font, ItemRef ref, int x, int y, int mouseX, int mouseY,
                      Runnable onClick) {
        g.item(ref.displayStack(), x, y);
        g.itemDecorations(font, ref.displayStack(), x, y);
        Hit hit = new Hit(x, y, x + CELL - 1, y + CELL - 1, onClick);
        if (hit.contains(mouseX, mouseY)) {
            hovered = ref;
        }
        hits.add(hit);
    }

    private void button(GuiGraphicsExtractor g, Font font, String label, int x, int y, int mouseX, int mouseY,
                        Runnable action) {
        int w = font.width(label);
        Hit hit = new Hit(x - 2, y - 2, x + w + 2, y + font.lineHeight + 1, action);
        text(g, font, label, x, y, hit.contains(mouseX, mouseY) ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);
        hits.add(hit);
    }

    private static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int colour) {
        g.text(font, Component.literal(s), x, y, colour);
    }

    private void openFullScreen() {
        ItemRef target = item;
        boolean showUsages = usages;
        Minecraft.getInstance().setScreenAndShow(new RecipeViewerScreen(target, showUsages));
    }

    // ------------------------------------------------------------------ input

    /** A click inside the panel; true when it hit something (the caller swallows panel clicks anyway). */
    boolean click(MouseButtonEvent event) {
        if (item == null) {
            return false;
        }
        if (event.button() == 2) {
            return hovered != null && MarketSearch.run(hovered);
        }
        if (event.button() != 0) {
            return false;
        }
        for (Hit hit : List.copyOf(hits)) {
            if (hit.contains(event.x(), event.y())) {
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    void scroll(double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY)); // clamped on render
    }
}
