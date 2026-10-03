/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.economy.auctions.logic.AhFlipClient;
import sbs.modid.client.economy.auctions.logic.AhFlipFeed;
import sbs.modid.client.ui.window.FloatingWindows;
import sbs.modid.client.ui.window.WindowMemory;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.DevNotice;
import sbs.modid.client.ui.render.LocalRankingNotice;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.render.SourceSwitch;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The AH Flips window (AH Flip Alerts module): a movable, resizable SBS table that opens
 * automatically over the <b>player inventory</b> and Hypixel's <b>Auction House</b> GUIs while the
 * module's "Flips Window" toggle is on – the exact window mechanics of the Bazaar
 * {@link sbs.modid.client.economy.bazaar.ui.BestFlipsOverlay} (drag, edge-resize, Ctrl+Scroll zoom, scroll,
 * minimize bar, {@link FloatingWindows} z-order).
 *
 * <p>Unlike the Bazaar window it fetches nothing itself: it lists the live flips from the shared
 * {@link AhFlipFeed}, which {@link AhFlipClient} keeps filled while the module is enabled – window,
 * popups and chat always show the same flips. Clicking a flip's rows opens its auction in game
 * ({@code /viewauction}, like the Similar Auctions rows).
 */
public final class AhFlipsOverlay {

    private static final AhFlipsOverlay INSTANCE = new AhFlipsOverlay();

    private static final int HEADER_H = 16;
    private static final int ROW_H = 11;
    private static final int PAD = 6;
    private static final int MARGIN = 2;
    private static final int MIN_W = 260;
    private static final int MAX_W = 700;
    private static final int MIN_H = 120;
    private static final int MAX_H = 520;

    /** A rendered row; {@code flip} is set on both rows of an entry (click = open auction). */
    private record Line(String left, String right, int leftColor, int rightColor,
                        AhFlipFeed.Flip flip) {
    }

    private boolean open;
    /** Minimized to a small reopen bar (never closed) – remembered across restarts. */
    private boolean minimized;
    private int minBarX;
    private int minBarY;
    private int minBarW;
    private int minBarH;

    private int posX = Integer.MIN_VALUE;
    private int posY = Integer.MIN_VALUE;
    private int sizeW = 340;
    private int sizeH = 300;
    private int panelW = sizeW;
    private int panelH = sizeH;

    private boolean dragging;
    private double grabDX;
    private double grabDY;
    private final sbs.modid.client.ui.window.WindowResizer resizer = new sbs.modid.client.ui.window.WindowResizer();
    private int scrollRow;
    /** Top Y of the first list row, saved each frame so a click maps back to the row under it. */
    private int rowsTop;

    /** The list's scrollbar - the mod's shared one, so it drags like every other SBS list. */
    private final SciFiScrollbar bar = new SciFiScrollbar();

    /** Strip reserved on the right of every row for the scrollbar - see the render pass. */
    private static final int BAR_GUTTER = SciFiScrollbar.WIDTH + 3;

    /** Where the player left this window: position, size and whether it was collapsed. */
    private final WindowMemory memory = new WindowMemory(FloatingWindows.Layer.AH_FLIPS);

    private AhFlipsOverlay() {
    }

    public static AhFlipsOverlay getInstance() {
        return INSTANCE;
    }

    private static sbs.modid.client.core.config.SBSConfig.AhFlipAlertSettings cfg() {
        return sbs.modid.client.core.config.ConfigManager.getInstance().get().ahFlips;
    }

    /** Collapses the window to the small reopen bar (does NOT close it). */
    public void minimize() {
        minimized = true;
        dragging = false;
        resizer.end();
        rememberWindow();
    }

    /** Persists position, size and collapsed state so the window comes back where it was left. */
    private void rememberWindow() {
        memory.remember(posX, posY, sizeW, sizeH, minimized);
    }

    // ------------------------------------------------------------------
    // Lifecycle: auto-open over the inventory + auction screens
    // ------------------------------------------------------------------

    /** True for every Hypixel Auction House GUI ("Auction House", "Auctions Browser", ...). */
    private static boolean isAuctionGui(String title) {
        return title != null && title.trim().toLowerCase(Locale.ROOT).contains("auction");
    }

    /** Tracks the screen state and auto-opens/-closes; called every frame from the render pass. */
    private void updateFor(AbstractContainerScreen<?> screen) {
        var cfg = sbs.modid.client.core.config.ConfigManager.getInstance().get().ahFlips;
        String title = screen.getTitle() != null ? screen.getTitle().getString() : "";
        boolean visible = cfg.enabled && cfg.windowOverlay
                && (screen instanceof InventoryScreen || isAuctionGui(title));
        if (!visible) {
            open = false;
            return; // minimized state persists across re-entries (session), never closes
        }
        if (!open) {
            open = true;
            scrollRow = 0;
            FloatingWindows.raise(FloatingWindows.Layer.AH_FLIPS);
        }
    }

    // ------------------------------------------------------------------
    // Line building (straight from the shared feed – no own fetch)
    // ------------------------------------------------------------------

    private List<Line> currentLines() {
        List<AhFlipFeed.Flip> flips = AhFlipFeed.getInstance().active();
        List<Line> built = new ArrayList<>();
        built.add(new Line("click a flip to open it · rating = profit x discount x sales/wk",
                "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED, null));
        int rank = 0;
        for (AhFlipFeed.Flip flip : flips) {
            rank++;
            built.add(new Line("#" + rank + " " + flip.displayName(),
                    "+" + fmt(flip.profit()),
                    rank <= 3 ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT,
                    SBSTheme.TOGGLE_ON, flip));
            String detail = "   " + fmt(flip.price()) + " → " + fmt(flip.target())
                    + " · " + Math.round(flip.discountPct()) + "%"
                    + " · " + flip.volumeLabel()
                    + " · " + ago(flip.receivedMs());
            built.add(new Line(detail, "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED, flip));
        }
        if (rank == 0) {
            built.add(new Line("No live flips right now - they arrive with the next AH scan",
                    "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED, null));
        }
        return built;
    }

    // ------------------------------------------------------------------
    // Rendering (FloatingWindows z-order pass)
    // ------------------------------------------------------------------

    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        updateFor(screen);
        if (!open) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        memory.restore(state -> {
            posX = state.x;
            posY = state.y;
            sizeW = state.width(sizeW);
            sizeH = state.height(sizeH);
            minimized = state.minimized;
        });
        if (posX == Integer.MIN_VALUE) {
            posX = MARGIN + 4;
            posY = (screen.height - sizeH) / 2;
        }
        if (minimized) {
            drawMinimizedBar(screen, g, font, mouseX, mouseY);
            return;
        }
        panelW = clamp(Math.min(sizeW, screen.width - MARGIN * 2), MIN_W, MAX_W);
        panelH = clamp(Math.min(sizeH, screen.height - MARGIN * 2), MIN_H, MAX_H);
        posX = clamp(posX, MARGIN, Math.max(MARGIN, screen.width - panelW - MARGIN));
        posY = clamp(posY, MARGIN, Math.max(MARGIN, screen.height - panelH - MARGIN));

        SciFiRender.glow(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, posX + 1, posY + 1, panelW - 2, panelH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int textY = posY + (HEADER_H - font.lineHeight) / 2 + 1;
        // Everything on this line competes for the strip left of the scroll counter, so each piece is
        // measured against what the one before it took: the in-development tag is dropped first, then
        // the mayor. A window dragged narrow loses them in that order rather than overlapping.
        int headerRight = minimizeGlyphX() - 44;
        String title = DevNotice.tagged(font, "AH Flips", headerRight - (posX + PAD));
        g.text(font, Component.literal(title), posX + PAD, textY, SBSTheme.ACCENT_BRIGHT);
        // Active mayor, dezent right after the title (only when the server sent one).
        String mayor = AhFlipFeed.getInstance().mayorName();
        if (mayor != null) {
            String text = "· Mayor: " + mayor;
            int mayorX = posX + PAD + font.width(title) + 6;
            if (mayorX + font.width(text) <= headerRight) {
                g.text(font, Component.literal(text), mayorX, textY, SBSTheme.TEXT_MUTED);
            }
        }
        boolean minHover = inMinimizeBox(mouseX, mouseY);
        g.text(font, Component.literal("-"), minimizeGlyphX() + 4, textY,
                minHover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED);
        g.fill(posX + PAD, posY + HEADER_H, posX + panelW - PAD, posY + HEADER_H + 1, SBSTheme.ACCENT_SOFT);

        // Server / Local on its own strip under the header, then the pinned notice while the local
        // ranking is what is on screen. Both are outside the scroll region: a warning that scrolls
        // away is one most readers see once and then forget while reading the numbers.
        boolean preferLocal = cfg().flipSource.preferLocal;
        int switchY = posY + HEADER_H + 3;
        SourceSwitch.draw(g, font, posX + PAD, switchY, preferLocal, mouseX, mouseY);
        if (mouseX >= posX + PAD && mouseX < posX + PAD + SourceSwitch.width(font)
                && mouseY >= switchY && mouseY < switchY + SourceSwitch.HEIGHT) {
            List<Component> tip = new ArrayList<>();
            for (String line : SourceSwitch.tooltip(preferLocal)) {
                tip.add(Component.literal(line));
            }
            g.setTooltipForNextFrame(font, tip, java.util.Optional.empty(), mouseX, mouseY,
                    SBSTheme.tooltipStyle());
        }
        int noticeTop = switchY + SourceSwitch.HEIGHT + 2;
        int noticeWidth = Math.max(1, panelW - PAD * 2);
        int noticeHeight = preferLocal
                ? LocalRankingNotice.draw(g, font, posX + PAD, noticeTop, noticeWidth, null) : 0;

        List<Line> list = currentLines();
        // Measured off what was actually drawn, never assumed: the notice wraps to two lines on a
        // window dragged narrow, and a constant would paint the first flip over it.
        int rowsTop = noticeTop + noticeHeight;
        this.rowsTop = rowsTop;
        int rowsBottom = posY + panelH - PAD;
        int visible = Math.max(1, (rowsBottom - rowsTop) / ROW_H);
        int maxScroll = Math.max(0, list.size() - visible);
        scrollRow = clamp(scrollRow, 0, maxScroll);
        if (maxScroll > 0) {
            g.text(font, Component.literal((scrollRow + 1) + "-" + Math.min(list.size(), scrollRow + visible)
                    + "/" + list.size()), minimizeGlyphX() - 40, textY, SBSTheme.TEXT_MUTED);
        }

        Line hovered = lineAt(mouseY);
        for (int i = 0; i < visible && scrollRow + i < list.size(); i++) {
            Line line = list.get(scrollRow + i);
            int rowY = rowsTop + i * ROW_H;
            // Hover highlight spans both rows of the hovered flip (they open the same auction).
            if (line.flip() != null && hovered != null && hovered.flip() == line.flip()
                    && mouseX >= posX && mouseX < posX + panelW) {
                g.fill(posX + 2, rowY - 1, posX + panelW - 2, rowY + ROW_H - 1, SBSTheme.CARD_BG_HOVER);
            }
            String right = line.right();
            int rightW = right.isEmpty() ? 0 : font.width(right) + 2;
            g.text(font, Component.literal(font.plainSubstrByWidth(line.left(),
                            panelW - PAD * 2 - BAR_GUTTER - rightW - 4, false)),
                    posX + PAD, rowY, line.leftColor());
            if (!right.isEmpty()) {
                g.text(font, Component.literal(right),
                        posX + panelW - PAD - BAR_GUTTER - font.width(right), rowY, line.rightColor());
            }
        }

        // The strip is reserved above whether or not a bar lands in it, so the rows do not re-flow
        // as the list crosses the length at which one appears.
        bar.set(posX + panelW - PAD - SciFiScrollbar.WIDTH, rowsTop, rowsBottom - rowsTop,
                list.size(), visible);
        bar.render(g, scrollRow, mouseX, mouseY);

        resizer.renderGrips(g, posX, posY, panelW, panelH, mouseX, mouseY);
    }

    /** The collapsed state: a small "AH Flips" bar with a restore glyph; click anywhere to reopen. */
    private void drawMinimizedBar(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                                  Font font, int mouseX, int mouseY) {
        minBarW = font.width("AH Flips") + 26;
        minBarH = HEADER_H;
        minBarX = clamp(posX, MARGIN, Math.max(MARGIN, screen.width - minBarW - MARGIN));
        minBarY = clamp(posY, MARGIN, Math.max(MARGIN, screen.height - minBarH - MARGIN));
        boolean hover = mouseX >= minBarX && mouseX < minBarX + minBarW
                && mouseY >= minBarY && mouseY < minBarY + minBarH;
        SciFiRender.roundedRectWithBorder(g, minBarX, minBarY, minBarW, minBarH, SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        int textY = minBarY + (minBarH - font.lineHeight) / 2 + 1;
        g.text(font, Component.literal("AH Flips"), minBarX + 5, textY, SBSTheme.ACCENT_BRIGHT);
        int gx = minBarX + minBarW - 12;
        int gy = minBarY + 4;
        int gw = 8;
        int gh = minBarH - 8;
        g.fill(gx, gy, gx + gw, gy + 2, SBSTheme.ACCENT);
        g.outline(gx, gy, gw, gh, hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);
    }

    // ------------------------------------------------------------------
    // Input (z-order dispatched from ContainerSearchBarMixin)
    // ------------------------------------------------------------------

    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (minimized) {
            if (event.button() == 0 && mx >= minBarX && mx < minBarX + minBarW
                    && my >= minBarY && my < minBarY + minBarH) {
                minimized = false;
                rememberWindow();
                FloatingWindows.raise(FloatingWindows.Layer.AH_FLIPS);
                return true;
            }
            return false;
        }
        if (!inPanel(mx, my)) {
            return false;
        }
        if (inMinimizeBox(mx, my) && event.button() == 0) {
            minimize();
            return true;
        }
        if (event.button() == 0 && resizer.begin(mx, my, posX, posY, panelW, panelH)) {
            return true;
        }
        if (my < posY + HEADER_H && event.button() == 0) {
            dragging = true;
            grabDX = mx - posX;
            grabDY = my - posY;
            return true;
        }
        // The source switch, before the rows below it.
        if (event.button() == 0) {
            Font font = Minecraft.getInstance().font;
            Boolean picked = SourceSwitch.hit(font, posX + PAD, posY + HEADER_H + 3, mx, my);
            if (picked != null) {
                sbs.modid.client.core.api.RankingSource.choose(cfg().flipSource, picked);
                return true;
            }
        }
        // Before the rows: a click that reached both would open an auction as well as scroll.
        if (event.button() == 0 && bar.handleClick(mx, my, scrollRow, value -> scrollRow = value)) {
            return true;
        }
        Line line = lineAt(my);
        if (line != null && line.flip() != null && event.button() == 0) {
            AhFlipClient.openAuction(line.flip());
        }
        return true;
    }

    private Line lineAt(double my) {
        if (my < rowsTop || my >= posY + panelH - PAD) {
            return null;
        }
        int index = scrollRow + (int) ((my - rowsTop) / ROW_H);
        List<Line> list = currentLines();
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    public boolean handleDrag(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!open || minimized) {
            return false;
        }
        if (bar.handleDrag(event.y(), value -> scrollRow = value)) {
            return true;
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
        if (bar.release()) {
            return true;
        }
        boolean wasResizing = resizer.end();
        if (!dragging && !wasResizing) {
            return false;
        }
        dragging = false;
        rememberWindow();
        return true;
    }

    public boolean handleScroll(AbstractContainerScreen<?> screen, double mouseX, double mouseY, double scrollY) {
        if (!open || minimized || !inPanel(mouseX, mouseY)) {
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

    // ------------------------------------------------------------------
    // Geometry / formatting helpers
    // ------------------------------------------------------------------

    private int minimizeGlyphX() {
        return posX + panelW - 14;
    }

    private boolean inMinimizeBox(double mx, double my) {
        return mx >= minimizeGlyphX() && mx < minimizeGlyphX() + 12 && my >= posY + 2 && my < posY + HEADER_H;
    }

    private boolean inPanel(double mx, double my) {
        return mx >= posX && mx < posX + panelW && my >= posY && my < posY + panelH;
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

    /** "just now" / "3m ago" / "12m ago" – how fresh the flip is. */
    private static String ago(long thenMs) {
        long s = Math.max(0, (System.currentTimeMillis() - thenMs) / 1000);
        return s < 60 ? "just now" : (s / 60) + "m ago";
    }
}
