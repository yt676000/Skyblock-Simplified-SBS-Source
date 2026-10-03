/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.sacks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.sacks.logic.SackContents;
import sbs.modid.client.helper.sacks.logic.SackPricing;
import sbs.modid.client.helper.sacks.model.SackPriceMode;
import sbs.modid.client.helper.sacks.model.SackSort;
import sbs.modid.client.helper.storage.StorageIndex;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Everything in the sack you just opened, with a value you can switch between Sell Offer,
 * Insta-Sell and NPC.
 *
 * <h2>A panel beside the menu, not a replacement for it</h2>
 * The Pets and Loadouts overlays cover their menus whole and map clicks back onto the real slots,
 * because in those menus every click is "pick this one". A sack is not like that - you withdraw by
 * clicking its slots, with every modifier Hypixel supports - so covering it would mean
 * re-implementing all of that to stay usable. This sits beside the menu and <b>only swallows clicks
 * that land on itself</b>; the sack underneath behaves exactly as it always did.
 *
 * <h2>Where it goes when there is no room</h2>
 * At 1280x720 on GUI scale 4 the viewport is 320x180 and the menu is most of it. There is no side
 * panel to be had, so instead of hanging off the edge or painting over the grid the panel collapses
 * to a single compact card - the total, and the few rows worth the most - pinned above the menu.
 * The full panel is only chosen once it has been measured to fit.
 *
 * <h2>Nothing unpriced is ever a zero</h2>
 * An item the chosen market does not price shows a dash, and the total carries a {@code +} to say
 * it is a floor rather than a figure - the same contract {@code ItemAppraisal} keeps for the
 * container-value card. A cold Bazaar snapshot says "prices loading", which is a different
 * statement from "worth nothing" and has to stay one.
 */
public final class SackOverlay {

    private static final SackOverlay INSTANCE = new SackOverlay();

    /** How often the sack is re-read. Its contents change when you withdraw, not per frame. */
    private static final long SCAN_MS = 150;

    private static final int PAD = 6;
    private static final int ROW_H = 18;
    private static final int GAP = 6;
    private static final int PREFERRED_W = 190;
    private static final int MIN_PANEL_W = 130;
    /** Rows on the collapsed card, where the whole point is that it is small. */
    private static final int COMPACT_ROWS = 3;

    /** One priced row, ready to draw. */
    private record Row(SackContents.Row item, long unit, long value, boolean priced) {
    }

    private long lastScan;
    private int lastContainerId = -1;
    private SackPriceMode lastMode;
    private SackSort lastSort;
    private boolean lastHideEmpty;

    private List<Row> rows = List.of();
    private long total;
    private int unpriced;
    private boolean pricesCold;

    private int scroll;
    /** The one scrollbar in this repo - a hand-drawn bar looks draggable and is not. */
    private final SciFiScrollbar bar = new SciFiScrollbar();

    // Hit rectangles, written by the renderer and read by the click handler so the two can never
    // disagree about where a control is.
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int switchY;
    private int[] switchX = new int[SackPriceMode.values().length + 1];
    private int sortY;
    private int[] sortX = new int[SackSort.values().length + 1];
    private boolean compact;

    private SackOverlay() {
    }

    public static SackOverlay getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().skyblockMenu.sackOverlay;
    }

    private static SackPriceMode mode() {
        SackPriceMode mode = ConfigManager.getInstance().get().skyblockMenu.sackPriceMode;
        return mode == null ? SackPriceMode.INSTA_SELL : mode;
    }

    private static SackSort sort() {
        SackSort sort = ConfigManager.getInstance().get().skyblockMenu.sackSort;
        return sort == null ? SackSort.VALUE : sort;
    }

    private static boolean hideEmpty() {
        return ConfigManager.getInstance().get().skyblockMenu.sackHideEmpty;
    }

    private static String title(AbstractContainerScreen<?> screen) {
        return PlainText.strip(screen.getTitle().getString()).trim();
    }

    /**
     * Whether the panel is up. The sack check is {@link StorageIndex#isSackTitle} - the same one the
     * storage index uses - minus the Sack of Sacks, which carries the word but is a menu of sacks
     * rather than of items and has nothing for this panel to total.
     */
    public boolean isActive(AbstractContainerScreen<?> screen) {
        if (!enabled() || screen == null) {
            return false;
        }
        String title = title(screen);
        return StorageIndex.isSackTitle(title)
                && !title.toLowerCase(Locale.ROOT).contains("sack of sacks");
    }

    /** Whether the generic container-value card should stand down: its count would be wrong here. */
    public boolean suppressesContainerValue(AbstractContainerScreen<?> screen) {
        return isActive(screen);
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    private void refresh(AbstractContainerScreen<?> screen) {
        long now = System.currentTimeMillis();
        int containerId = screen.getMenu().containerId;
        SackPriceMode mode = mode();
        SackSort sort = sort();
        boolean hide = hideEmpty();
        // A different menu, or a control the player just moved, re-reads at once: showing the last
        // sack's rows for a sixth of a second is the kind of wrong that looks like a broken feature.
        boolean settled = containerId == lastContainerId && mode == lastMode && sort == lastSort
                && hide == lastHideEmpty;
        if (settled && now - lastScan < SCAN_MS) {
            return;
        }
        if (containerId != lastContainerId) {
            scroll = 0;
        }
        lastScan = now;
        lastContainerId = containerId;
        lastMode = mode;
        lastSort = sort;
        lastHideEmpty = hide;

        pricesCold = mode != SackPriceMode.NPC && !SackPricing.bazaarReady();

        List<Row> built = new ArrayList<>();
        long sum = 0;
        int misses = 0;
        for (SackContents.Row item : SackContents.read(screen)) {
            // An amount we could not read is not an empty row - hiding it would hide exactly the
            // items a mis-parsed sack is failing on. Only a confirmed zero is hidden.
            if (hide && item.known() && item.stored() == 0) {
                continue;
            }
            SackPricing.UnitPrice price = SackPricing.unit(item.id(), mode);
            long amount = item.known() ? item.stored() : 0;
            long value = price.priced() ? price.coins() * amount : 0;
            if (price.priced()) {
                sum += value;
            } else {
                misses++;
            }
            built.add(new Row(item, price.coins(), value, price.priced()));
        }
        built.sort(comparator(sort));
        rows = built;
        total = sum;
        unpriced = misses;
    }

    private static Comparator<Row> comparator(SackSort sort) {
        return switch (sort) {
            case VALUE -> Comparator.comparingLong(Row::value).reversed();
            case AMOUNT -> Comparator.comparingLong((Row r) -> r.item().stored()).reversed();
            case NAME -> Comparator.comparing(r -> r.item().name(), String.CASE_INSENSITIVE_ORDER);
        };
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Drawn from {@code OverlayRenderMixin.renderTopMost}, above the menu and its own overlays. */
    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        if (!isActive(screen)) {
            return;
        }
        refresh(screen);
        Font font = Minecraft.getInstance().font;
        layout(screen, font);
        if (compact) {
            drawCompact(g, font, mouseX, mouseY);
        } else {
            drawPanel(g, font, mouseX, mouseY);
        }
    }

    /**
     * Where the panel goes, measured against the real viewport.
     *
     * <p>The room to the right of the menu is the ceiling, never a floor: if what is left after the
     * margins cannot hold {@link #MIN_PANEL_W}, there is no side panel and the compact card is used
     * instead. Sizing to a minimum is the standing bug this file is not allowed to reproduce.
     */
    private void layout(AbstractContainerScreen<?> screen, Font font) {
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        int menuLeft = bounds.skyblockSimplified$leftPos();
        int menuRight = menuLeft + bounds.skyblockSimplified$imageWidth();
        int menuTop = bounds.skyblockSimplified$topPos();
        int menuHeight = bounds.skyblockSimplified$imageHeight();

        int rightRoom = screen.width - menuRight - GAP * 2;
        int leftRoom = menuLeft - GAP * 2;
        boolean onRight = rightRoom >= leftRoom;
        int room = Math.max(rightRoom, leftRoom);

        compact = room < MIN_PANEL_W;
        if (compact) {
            panelW = Math.min(Math.max(1, screen.width - GAP * 2), PREFERRED_W);
            panelX = (screen.width - panelW) / 2;
            panelH = headerHeight(font) + COMPACT_ROWS * ROW_H + PAD * 2;
            // Above the menu when there is sky for it, otherwise pinned to the top of the viewport.
            panelY = menuTop - panelH - GAP >= 0 ? menuTop - panelH - GAP : GAP;
            return;
        }
        panelW = Math.min(room, PREFERRED_W);
        panelX = onRight ? menuRight + GAP : menuLeft - GAP - panelW;
        panelY = menuTop;
        panelH = Math.min(menuHeight, screen.height - menuTop - GAP);
    }

    /** Header block height, from the strings actually drawn rather than a constant. */
    private int headerHeight(Font font) {
        return PAD + font.lineHeight + 3          // title
                + font.lineHeight + 4              // total
                + SBSTheme.SEARCH_HEIGHT + 3       // price switch
                + SBSTheme.SEARCH_HEIGHT + 4;      // sort switch
    }

    private void drawPanel(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        SciFiRender.roundedRectWithBorder(g, panelX, panelY, panelW, panelH,
                SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        int y = drawHeader(g, font, mouseX, mouseY, panelW);
        syncBar(y);
        int listTop = y;
        int listBottom = panelY + panelH - PAD;
        int visible = Math.max(0, (listBottom - listTop) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, bar.maxScroll()));

        g.enableScissor(panelX, listTop, panelX + panelW, listBottom);
        for (int i = 0; i < visible && i + scroll < rows.size(); i++) {
            drawRow(g, font, rows.get(i + scroll), listTop + i * ROW_H, panelW);
        }
        g.disableScissor();
        bar.render(g, scroll, mouseX, mouseY);
    }

    /**
     * Points the scrollbar at the list box. Called from the renderer and again before every input
     * check, so a click is always tested against the track that was last drawn.
     */
    private void syncBar(int listTop) {
        int listBottom = panelY + panelH - PAD;
        bar.set(panelX + panelW - PAD - SciFiScrollbar.WIDTH, listTop, listBottom - listTop,
                rows.size(), Math.max(1, (listBottom - listTop) / ROW_H));
    }

    /** The no-room card: the total and the few rows worth the most, and nothing else. */
    private void drawCompact(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        SciFiRender.roundedRectWithBorder(g, panelX, panelY, panelW, panelH,
                SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        int y = drawHeader(g, font, mouseX, mouseY, panelW);
        for (int i = 0; i < COMPACT_ROWS && i < rows.size(); i++) {
            drawRow(g, font, rows.get(i), y + i * ROW_H, panelW);
        }
    }

    /** Title, total, the price switch and the sort switch. Returns the y the list starts at. */
    private int drawHeader(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, int width) {
        int x = panelX + PAD;
        int inner = width - PAD * 2;
        int y = panelY + PAD;

        g.text(font, Component.literal("Sack"), x, y, SBSTheme.ACCENT_BRIGHT);
        String count = rows.size() + (rows.size() == 1 ? " item" : " items");
        int countW = font.width(count);
        // Measured, not assumed: the count is only drawn when it actually fits beside the title.
        if (countW <= inner - font.width("Sack") - 6) {
            g.text(font, Component.literal(count), panelX + width - PAD - countW, y,
                    SBSTheme.TEXT_MUTED);
        }
        y += font.lineHeight + 3;

        String totalText = pricesCold ? "§8prices loading"
                : "§6" + NumberDisplay.format(total) + (unpriced > 0 ? "§8+" : "");
        g.text(font, Component.literal(totalText),
                x, y, SBSTheme.TEXT);
        y += font.lineHeight + 4;

        y = drawSwitch(g, font, x, y, inner, SackPriceMode.labels(), mode().ordinal(), switchX,
                mouseX, mouseY, true);
        y = drawSwitch(g, font, x, y, inner, SackSort.labels(), sort().ordinal(), sortX,
                mouseX, mouseY, false);
        return y;
    }

    /**
     * A segmented switch drawn by hand: every option visible, any of them one click away.
     *
     * <p>{@code SciFiSegmentedSwitch} is an {@code AbstractWidget} and this panel is painted over a
     * container screen, which has no widget tree to add one to - so the widget cannot be used here,
     * but the rule it exists to enforce (never a click-through above two options) is what is being
     * kept, and it is kept. The segment rectangles are stored so the click handler hit-tests the
     * boxes that were actually drawn.
     */
    private int drawSwitch(GuiGraphicsExtractor g, Font font, int x, int y, int width,
                           List<String> options, int selected, int[] slots,
                           int mouseX, int mouseY, boolean priceRow) {
        int h = SBSTheme.SEARCH_HEIGHT;
        int n = options.size();
        for (int i = 0; i < n; i++) {
            int sx = x + width * i / n;
            int ex = x + width * (i + 1) / n;
            slots[i] = sx;
            boolean on = i == selected;
            boolean hover = mouseX >= sx && mouseX < ex && mouseY >= y && mouseY < y + h;
            SciFiRender.roundedRectWithBorder(g, sx, y, ex - sx - 1, h, 2,
                    on ? SBSTheme.ACCENT : hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    on ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            String label = font.plainSubstrByWidth(options.get(i), ex - sx - 5, false);
            g.centeredText(font, Component.literal(label), (sx + ex) / 2 - 1,
                    y + (h - font.lineHeight) / 2 + 1, on ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
        }
        slots[n] = x + width;
        if (priceRow) {
            switchY = y;
        } else {
            sortY = y;
        }
        return y + h + 3;
    }

    private void drawRow(GuiGraphicsExtractor g, Font font, Row row, int y, int width) {
        int x = panelX + PAD;
        g.item(row.item().icon(), x, y);

        String value = !row.priced() ? "§8–"
                : "§6" + NumberDisplay.format(row.value());
        int valueW = font.width(PlainText.strip(value));
        int nameX = x + 18;
        int nameRoom = width - PAD * 2 - 18 - valueW - 6;

        String amount = row.item().known() ? NumberDisplay.format(row.item().stored()) + "x " : "";
        String name = font.plainSubstrByWidth(amount + row.item().name(), Math.max(8, nameRoom),
                false);
        g.text(font, Component.literal(name), nameX, y + (ROW_H - font.lineHeight) / 2,
                row.item().known() ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
        g.text(font, Component.literal(value), panelX + width - PAD - valueW,
                y + (ROW_H - font.lineHeight) / 2, SBSTheme.TEXT);
    }

    // ------------------------------------------------------------------
    // Input - only what lands on the panel
    // ------------------------------------------------------------------

    /** True only for clicks inside the panel: the sack underneath keeps every other click. */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!isActive(screen) || !inPanel(event.x(), event.y())) {
            return false;
        }
        int mx = (int) event.x();
        int my = (int) event.y();
        if (event.button() == 0 && bar.handleClick(mx, my, scroll, value -> scroll = value)) {
            return true;
        }
        var cfg = ConfigManager.getInstance().get().skyblockMenu;
        int picked = segmentAt(mx, my, switchY, switchX, SackPriceMode.values().length);
        if (picked >= 0) {
            cfg.sackPriceMode = SackPriceMode.values()[picked];
            ConfigManager.getInstance().save();
            return true;
        }
        picked = segmentAt(mx, my, sortY, sortX, SackSort.values().length);
        if (picked >= 0) {
            cfg.sackSort = SackSort.values()[picked];
            ConfigManager.getInstance().save();
            return true;
        }
        return true;   // anywhere else on the panel: swallowed, so no slot behind it is clicked
    }

    private int segmentAt(int mx, int my, int rowY, int[] slots, int count) {
        if (rowY <= 0 || my < rowY || my >= rowY + SBSTheme.SEARCH_HEIGHT) {
            return -1;
        }
        for (int i = 0; i < count; i++) {
            if (mx >= slots[i] && mx < slots[i + 1]) {
                return i;
            }
        }
        return -1;
    }

    /** Scrolls the list; only over the panel, so the sack's own scroll still works. */
    public boolean handleScroll(AbstractContainerScreen<?> screen, double mouseX, double mouseY,
                                double scrollY) {
        if (!isActive(screen) || compact || !inPanel(mouseX, mouseY)) {
            return false;
        }
        if (bar.needed() && scrollY != 0) {
            scroll = Math.max(0, Math.min(bar.maxScroll(), scroll - (int) Math.signum(scrollY)));
        }
        return true;
    }

    /** Thumb drag. Without this the bar would be exactly the lie the check exists to prevent. */
    public boolean handleDrag(AbstractContainerScreen<?> screen, double mouseY) {
        return isActive(screen) && bar.handleDrag(mouseY, value -> scroll = value);
    }

    /** Ends a thumb drag. */
    public boolean handleRelease(AbstractContainerScreen<?> screen) {
        return isActive(screen) && bar.release();
    }

    private boolean inPanel(double mx, double my) {
        return mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + panelH;
    }
}
