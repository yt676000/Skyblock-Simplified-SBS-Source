/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.itemvalue;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.render.DevNotice;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.itemvalue.ItemValueService.PriceStats;
import sbs.modid.client.economy.itemvalue.ItemValueService.Row;
import sbs.modid.client.economy.pricehistory.logic.PriceBrowser;
import sbs.modid.client.economy.pricehistory.ui.PriceBrowserManager;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The Item Value window: an SBS-styled, <b>movable and resizable</b> table (like the browser
 * windows) listing every priced component of the checked item – columns Now / Day avg / Week avg /
 * Month avg / All-time avg – with the per-column totals pinned at the bottom.
 *
 * <p>Drag the header to move, Ctrl+scroll or drag the bottom-right grip to resize, scroll to move
 * through long component lists. Clicking a row opens that component's page in a new in-game
 * browser window ({@code <site>?token=<licence token>#/item/<ID>}); clicks outside fall through so
 * the container stays usable.
 */
public final class ItemValueOverlay {

    private static final ItemValueOverlay INSTANCE = new ItemValueOverlay();

    private static final int HEADER_H = 16;
    private static final int COL_HEADER_H = 11;
    private static final int ROW_H = 11;
    private static final int PAD = 6;
    private static final int MARGIN = 2;

    private static final int MIN_W = 300;
    private static final int MAX_W = 820;
    private static final int MIN_H = 140;
    private static final int MAX_H = 520;

    private boolean open;
    private String title = "";
    private volatile List<Row> rows = List.of();

    /** No licence token: the Now column is the client's own answer and there is no history to show. */
    private volatile boolean localOnly;

    /** Window position/size, remembered across restarts; {@code MIN_VALUE} = default placement. */
    private int posX = Integer.MIN_VALUE;
    private int posY = Integer.MIN_VALUE;
    private int sizeW = 430;
    private int sizeH = 240;
    private int panelW = sizeW;
    private int panelH = sizeH;

    private boolean dragging;
    private double grabDX;
    private double grabDY;

    /** Shared edge/corner resize mechanics (identical to the Price History window). */
    private final sbs.modid.client.ui.window.WindowResizer resizer = new sbs.modid.client.ui.window.WindowResizer();

    /**
     * Where the player left this window. Only the geometry: which item it was showing is not worth
     * restoring, since the window opens on the item you just checked.
     */
    private final sbs.modid.client.ui.window.WindowMemory memory =
            new sbs.modid.client.ui.window.WindowMemory(
                    sbs.modid.client.ui.window.FloatingWindows.Layer.VALUE);

    private int scrollRow;

    private ItemValueOverlay() {
    }

    public static ItemValueOverlay getInstance() {
        return INSTANCE;
    }

    public boolean isOpen() {
        return open;
    }

    /** Opens (or retargets) the window on a freshly checked item, raising it above the others. */
    public void open(String itemName, List<Row> tableRows, boolean localPricesOnly) {
        this.title = "Value: " + itemName;
        this.rows = tableRows;
        this.localOnly = localPricesOnly;
        this.scrollRow = 0;
        this.open = true;
        sbs.modid.client.ui.window.FloatingWindows.raise(sbs.modid.client.ui.window.FloatingWindows.Layer.VALUE);
    }

    public void close() {
        open = false;
        dragging = false;
        resizer.end();
    }

    // ------------------------------------------------------------------
    // Rendering (OverlayRenderMixin TAIL, above the browser windows)
    // ------------------------------------------------------------------

    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        memory.restore(state -> {
            posX = state.x;
            posY = state.y;
            sizeW = state.width(sizeW);
            sizeH = state.height(sizeH);
        });
        panelW = clamp(Math.min(sizeW, screen.width - MARGIN * 2), MIN_W, MAX_W);
        panelH = clamp(Math.min(sizeH, screen.height - MARGIN * 2), MIN_H, MAX_H);
        if (posX == Integer.MIN_VALUE) {
            posX = (screen.width - panelW) / 2 + 40;
            posY = (screen.height - panelH) / 2 - 20;
        }
        posX = clamp(posX, MARGIN, Math.max(MARGIN, screen.width - panelW - MARGIN));
        posY = clamp(posY, MARGIN, Math.max(MARGIN, screen.height - panelH - MARGIN));

        SciFiRender.glow(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, posX + 1, posY + 1, panelW - 2, panelH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        // Header: title (drag handle) + close ✕.
        int textY = posY + (HEADER_H - font.lineHeight) / 2 + 1;
        String shownTitle = font.plainSubstrByWidth(title, panelW - PAD * 2 - 16, false);
        g.text(font, Component.literal(shownTitle), posX + PAD, textY, SBSTheme.ACCENT_BRIGHT);
        // Two markers share the strip after the title, and both are dropped rather than cut when the
        // window is narrow. "live prices only" says why the history columns read "-" - there is no
        // licence token, so nothing was fetched, and without it the table looks broken rather than
        // deliberately local. The in-development tag says the appraisal itself is unfinished, which
        // holds whether or not a token is set, so it is the one that stays when only one fits.
        int tagX = posX + PAD + font.width(shownTitle) + 6;
        String devTag = DevNotice.TAG;
        if (tagX + font.width(devTag) < closeX() - 4) {
            g.text(font, Component.literal(devTag), tagX, textY, SBSTheme.TEXT_MUTED);
            tagX += font.width(devTag) + 6;
        }
        if (localOnly) {
            String tag = "live prices only";
            if (tagX + font.width(tag) < closeX() - 4) {
                g.text(font, Component.literal(tag), tagX, textY, SBSTheme.TEXT_MUTED);
            }
        }
        boolean closeHover = inCloseBox(mouseX, mouseY);
        g.text(font, Component.literal("x"), closeX() + 3, textY,
                closeHover ? SBSTheme.WARN : SBSTheme.TEXT_MUTED);
        g.fill(posX + PAD, posY + HEADER_H, posX + panelW - PAD, posY + HEADER_H + 1, SBSTheme.ACCENT_SOFT);

        // Column headers.
        int colW = columnWidth();
        int labelW = labelWidth(colW);
        int headerY = posY + HEADER_H + 3;
        String[] headers = {"Now", "Day", "Week", "Month", "All"};
        for (int c = 0; c < 5; c++) {
            String header = headers[c];
            g.text(font, Component.literal(header),
                    columnX(c, colW, labelW) + colW - font.width(header) - 2, headerY, SBSTheme.TEXT_MUTED);
        }

        // Rows (scrolled) + pinned totals.
        List<Row> list = rows;
        int rowsTop = headerY + COL_HEADER_H;
        int totalsY = posY + panelH - PAD - ROW_H;
        int visible = Math.max(1, (totalsY - 3 - rowsTop) / ROW_H);
        int maxScroll = Math.max(0, list.size() - visible);
        scrollRow = clamp(scrollRow, 0, maxScroll);

        for (int i = 0; i < visible && scrollRow + i < list.size(); i++) {
            Row row = list.get(scrollRow + i);
            int rowY = rowsTop + i * ROW_H;
            boolean hover = row.itemId != null && mouseX >= posX + PAD && mouseX < posX + panelW - PAD
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hover) {
                SciFiRender.roundedRect(g, posX + PAD - 2, rowY - 1, panelW - PAD * 2 + 4, ROW_H, 2,
                        SBSTheme.CARD_BG_HOVER);
            }
            String label = row.note != null ? row.label + "  (" + row.note + ")" : row.label;
            g.text(font, Component.literal(font.plainSubstrByWidth(label, labelW - 4, false)),
                    posX + PAD, rowY, hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            drawStats(g, font, row, colW, labelW, rowY);
        }
        if (maxScroll > 0) {
            g.text(font, Component.literal((scrollRow + 1) + "-" + Math.min(list.size(), scrollRow + visible)
                    + "/" + list.size()), closeX() - 34, headerY, SBSTheme.TEXT_MUTED);
        }

        // Totals: per-column sum over every priced row. The Now column falls back to each row's
        // locally computed price, so the one figure most checks are after adds up with or without
        // a licence token; the history columns stay empty rather than under-reporting a partial sum.
        g.fill(posX + PAD, totalsY - 2, posX + panelW - PAD, totalsY - 1, SBSTheme.ACCENT_SOFT);
        double[] totals = new double[5];
        boolean[] any = new boolean[5];
        for (Row row : list) {
            PriceStats stats = row.stats;
            if (stats != null) {
                totals[0] += stats.now();
                totals[1] += stats.day();
                totals[2] += stats.week();
                totals[3] += stats.month();
                totals[4] += stats.all();
                java.util.Arrays.fill(any, true);
            } else if (row.localNow != null) {
                totals[0] += row.localNow;
                any[0] = true;
            }
        }
        g.text(font, Component.literal("Total"), posX + PAD, totalsY, SBSTheme.ACCENT);
        for (int c = 0; c < 5; c++) {
            String text = any[c] ? fmt(totals[c]) : "-";
            g.text(font, Component.literal(text),
                    columnX(c, colW, labelW) + colW - font.width(text) - 2, totalsY, SBSTheme.ACCENT_BRIGHT);
        }

        // Edge/corner resize grips – identical mechanics and look to the Price History window.
        resizer.renderGrips(g, posX, posY, panelW, panelH, mouseX, mouseY);
    }

    private void drawStats(GuiGraphicsExtractor g, Font font, Row row, int colW, int labelW, int rowY) {
        PriceStats stats = row.stats;
        for (int c = 0; c < 5; c++) {
            String text;
            int color;
            if (stats != null) {
                double value = switch (c) {
                    case 0 -> stats.now();
                    case 1 -> stats.day();
                    case 2 -> stats.week();
                    case 3 -> stats.month();
                    default -> stats.all();
                };
                text = fmt(value);
                color = c == 0 ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED;
            } else if (c == 0 && row.localNow != null) {
                // The client's own answer, checked before the "failed" branch below: a history
                // request that never ran (or came back empty) says nothing about today's price.
                text = fmt(row.localNow);
                color = SBSTheme.TEXT;
            } else {
                text = row.failed ? "-" : "...";
                color = SBSTheme.TEXT_MUTED;
            }
            g.text(font, Component.literal(text),
                    columnX(c, colW, labelW) + colW - font.width(text) - 2, rowY, color);
        }
    }

    private int columnWidth() {
        return Math.max(44, Math.min(60, (panelW - PAD * 2 - 130) / 5));
    }

    private int labelWidth(int colW) {
        return panelW - PAD * 2 - colW * 5;
    }

    private int columnX(int column, int colW, int labelW) {
        return posX + PAD + labelW + column * colW;
    }

    private int closeX() {
        return posX + panelW - 14;
    }

    private boolean inCloseBox(double mx, double my) {
        return mx >= closeX() && mx < closeX() + 12 && my >= posY + 2 && my < posY + HEADER_H;
    }

    private boolean inPanel(double mx, double my) {
        return mx >= posX && mx < posX + panelW && my >= posY && my < posY + panelH;
    }

    // ------------------------------------------------------------------
    // Input (ContainerSearchBarMixin, before the browser windows)
    // ------------------------------------------------------------------

    /** Clicks inside the window are always consumed; a row click opens the item in the browser. */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (!inPanel(mx, my)) {
            return false;
        }
        if (event.button() != 0) {
            return true;
        }
        if (inCloseBox(mx, my)) {
            close();
            return true;
        }
        if (resizer.begin(mx, my, posX, posY, panelW, panelH)) {
            return true;
        }
        if (my < posY + HEADER_H) {
            dragging = true;
            grabDX = mx - posX;
            grabDY = my - posY;
            return true;
        }
        Row row = rowAt(my);
        if (row != null && row.itemId != null) {
            openInBrowser(screen, row.itemId);
        }
        return true;
    }

    private Row rowAt(double my) {
        int rowsTop = posY + HEADER_H + 3 + COL_HEADER_H;
        int totalsY = posY + panelH - PAD - ROW_H;
        if (my < rowsTop || my >= totalsY - 3) {
            return null;
        }
        int index = scrollRow + (int) ((my - rowsTop) / ROW_H);
        List<Row> list = rows;
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    /** Opens the component's page: {@code <site>?token=<licence token>#/item/<ID>}. */
    private static void openInBrowser(AbstractContainerScreen<?> screen, String itemId) {
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/?token=<licence token>#/item/<itemId>
// METHOD: none directly - this FORMATS an address and hands it to a browser to open
// PURPOSE: Open the item page for the item under the cursor, already signed in, so the user
//   does not have to paste their licence token into the site by hand.
// DATA SENT: The licence token AS A QUERY PARAMETER, plus the item id in the fragment. The
//   request itself is then made by the embedded Chromium browser, or - when that is
//   unavailable - by the operating system browser through ConfirmLinkScreen, which shows the
//   user the full address and asks before opening it.
// DATA RECEIVED: Nothing here. The page renders in a browser.
// SAFETY DECLARATION: THIS PUTS A CREDENTIAL IN A URL, which is worth stating plainly rather
//   than burying: a token in a query string can reach browser history, a Referer header and
//   the server access log, none of which is true of the Authorization header every other call
//   in this mod uses. It is the licence token, which identifies the purchase and grants
//   nothing on the player game account - no Mojang credential and no session id is involved -
//   and nothing is opened without the user clicking. It should still be moved to a one-time
//   handoff or a POST rather than a query parameter; see docs/features/token-in-url.md.
// ============================================================================
        String token = sbs.modid.client.core.config.LicenceToken.getInstance().get();
        String query = token == null || token.isBlank()
                ? "" : "?token=" + URLEncoder.encode(token.trim(), StandardCharsets.UTF_8);
        String url = PriceBrowser.site() + query + "#/item/" + itemId;
        if (!PriceBrowserManager.getInstance().openNewUrl(url, "Item: " + ItemModifiers.prettify(itemId))) {
            ConfirmLinkScreen.confirmLinkNow(screen, url);
        }
    }

    public boolean handleDrag(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        if (dragging) {
            posX = clamp((int) (event.x() - grabDX), MARGIN, Math.max(MARGIN, screen.width - panelW - MARGIN));
            posY = clamp((int) (event.y() - grabDY), MARGIN, Math.max(MARGIN, screen.height - panelH - MARGIN));
            return true;
        }
        if (resizer.isActive()) {
            int[] rect = resizer.drag(event.x(), event.y(), MIN_W, MAX_W, MIN_H, MAX_H);
            posX = rect[0];
            posY = rect[1];
            sizeW = rect[2];
            sizeH = rect[3];
            return true;
        }
        return false;
    }

    public boolean handleRelease(MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        boolean wasResizing = resizer.end();
        if (!dragging && !wasResizing) {
            return false;
        }
        dragging = false;
        rememberWindow();
        return true;
    }

    /** Persists the window's geometry so it comes back where it was left. */
    private void rememberWindow() {
        memory.remember(posX, posY, sizeW, sizeH, false);
    }

    /** Scroll: Ctrl held resizes the window, otherwise the row list scrolls. Consumed inside. */
    public boolean handleScroll(AbstractContainerScreen<?> screen, double mouseX, double mouseY, double scrollY) {
        if (!open || !inPanel(mouseX, mouseY)) {
            return false;
        }
        if (isCtrlDown()) {
            int step = (int) Math.signum(scrollY);
            sizeW = clamp(sizeW + step * 30, MIN_W, MAX_W);
            sizeH = clamp(sizeH + step * 20, MIN_H, MAX_H);
            rememberWindow();
            return true;
        }
        scrollRow = Math.max(0, scrollRow - (int) Math.signum(scrollY));
        return true;
    }

    private static boolean isCtrlDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Compact coin format: 1.2B / 34.5M / 850K / 123, honouring "Shorten Numbers". */
    private static String fmt(double value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }
}
