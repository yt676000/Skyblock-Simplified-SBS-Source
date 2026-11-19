/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.lowball.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.economy.lowball.logic.Lowball;
import sbs.modid.client.economy.lowball.logic.LowballView;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.window.FloatingWindows;
import sbs.modid.client.ui.window.WindowMemory;
import sbs.modid.client.ui.window.WindowResizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Lowball window (developer mode): the last appraisal, built and handled like the Item Value
 * window - SBS panel, header drag, edge resize, close ✕, geometry remembered through
 * {@link WindowMemory}. It stays open until closed (✕ or Esc), across container changes; with no
 * container open {@link LowballScreen} hosts it.
 *
 * <p>Render path only draws: the rows are built into {@link #lines} when the content, the expanded
 * set or the width changes; a frame reads them and draws. The footer age is the one text that
 * moves, and it is rebuilt at most once a second.
 */
public final class LowballWindow {

    private static final LowballWindow INSTANCE = new LowballWindow();

    private static final int HEADER_H = 16;
    private static final int ROW_H = 11;
    private static final int PAD = 6;
    private static final int MARGIN = 2;
    private static final int MIN_W = 240;
    private static final int MAX_W = 520;
    private static final int MIN_H = 120;
    private static final int MAX_H = 420;

    private static final int RED = 0xFFFF5555;
    private static final int ORANGE = 0xFFFFA040;
    private static final int YELLOW = 0xFFFFD64D;
    private static final int GREEN = 0xFF57D977;
    private static final int BOX_RED = 0x40FF5555;
    private static final int BOX_ORANGE = 0x40FFA040;
    private static final int BUTTON_BG = 0x3057D977;
    private static final int RECOMMENDED_BG = 0x6057D977;

    /** Offer table columns, as fractions of the content width. */
    private static final float[] TABLE_X = {0f, 0.17f, 0.36f, 0.66f, 0.83f};

    /** Marks a row's copy text as a plain copy (a sell price), not an offer to the seller. */
    private static final String PLAIN_COPY = "plain:";

    /**
     * One prepared row: up to three columns (or five with {@code table}), an optional click and an
     * optional box tint.
     */
    private record Line(String a, int colorA, String b, int colorB, String c, int colorC,
                        String d, int colorD, String e, int colorE, boolean table,
                        int indent, String copy, int toggle, int tint, String hover) {

        Line(String a, int colorA, String b, int colorB, String c, int colorC,
             int indent, String copy, int toggle, int tint, String hover) {
            this(a, colorA, b, colorB, c, colorC, null, 0, null, 0, false, indent, copy, toggle, tint, hover);
        }
    }

    private boolean open;
    private LowballView view;
    private final Set<Integer> expanded = new HashSet<>();
    private List<Line> lines = List.of();
    /** Each line's hover, split into tooltip lines when the content is built; {@code null} = none. */
    private List<List<Component>> hovers = List.of();
    private int builtForWidth = -1;
    private String footer;
    private long footerSecond = -1;

    private int posX = Integer.MIN_VALUE;
    private int posY = Integer.MIN_VALUE;
    private int sizeW = 320;
    private int sizeH = 220;
    private int panelW = sizeW;
    private int panelH = sizeH;
    private boolean dragging;
    private double grabDX;
    private double grabDY;
    private int scrollRow;
    private final WindowResizer resizer = new WindowResizer();
    private final WindowMemory memory = new WindowMemory(FloatingWindows.Layer.LOWBALL);

    private LowballWindow() {
    }

    public static LowballWindow getInstance() {
        return INSTANCE;
    }

    public boolean isOpen() {
        return open && view != null && Lowball.active();
    }

    public boolean hasContent() {
        return view != null;
    }

    /** Shows a new appraisal, replacing the old one. */
    public void show(LowballView newView) {
        this.view = newView;
        expanded.clear();
        if (!newView.isTrade() || newView.items().size() == 1) {
            expanded.add(0);   // a single item is shown in full straight away
        }
        scrollRow = 0;
        builtForWidth = -1;
        reopen();
    }

    /** Opens the window again on the last appraisal (no-op without one). */
    public void reopen() {
        if (view == null) {
            return;
        }
        open = true;
        FloatingWindows.raise(FloatingWindows.Layer.LOWBALL);
    }

    public void close() {
        open = false;
        dragging = false;
        resizer.end();
    }

    // ------------------------------------------------------------------
    // Content (built on change, never per frame)
    // ------------------------------------------------------------------

    private void build(Font font) {
        List<Line> out = new ArrayList<>();
        LowballView v = view;
        boolean trade = v.isTrade() && v.items().size() > 1;
        for (int i = 0; i < v.items().size(); i++) {
            LowballView.Item item = v.items().get(i);
            if (trade) {
                String name = (expanded.contains(i) ? "▾ " : "▸ ") + (item.warned() ? "⚠ " : "") + nameOf(item);
                String fast = item.ok() ? orDash(item.fastText()) : "-";
                String offer = !item.ok() ? "-"
                        : item.recommended() != null && item.recommended().copyText() != null
                        ? item.recommended().copyText()
                        : !item.offers().isEmpty() ? item.offers().get(0).copyText() : "-";
                out.add(new Line(name, item.ok() ? (item.warned() ? ORANGE : SBSTheme.TEXT) : SBSTheme.TEXT_MUTED,
                        fast, SBSTheme.TEXT, offer, GREEN, 0, null, i, 0, null));
                if (expanded.contains(i)) {
                    details(out, item, 8, false);
                }
            } else {
                details(out, item, 0, true);
            }
        }
        if (trade && !v.totalOffers().isEmpty()) {
            out.add(new Line("", 0, null, 0, null, 0, 0, null, -1, 0, null));
            out.add(new Line("Offer for all: " + String.join(" – ", v.totalOffers()), SBSTheme.ACCENT_BRIGHT,
                    null, 0, null, 0, 0, v.totalOffers().get(0), -1, BUTTON_BG,
                    "Click: " + Lowball.offerActionText(v.totalOffers().get(0), v.partner())));
        }
        List<List<Component>> tips = new ArrayList<>(out.size());
        for (Line line : out) {
            if (line.hover() == null) {
                tips.add(null);
            } else {
                List<Component> parts = new ArrayList<>();
                for (String part : line.hover().split("\n")) {
                    parts.add(Component.literal(part));
                }
                tips.add(List.copyOf(parts));
            }
        }
        lines = List.copyOf(out);
        hovers = java.util.Collections.unmodifiableList(tips);
        builtForWidth = panelW;
    }

    private void details(List<Line> out, LowballView.Item item, int indent, boolean withHeader) {
        if (withHeader) {
            out.add(new Line(nameOf(item), SBSTheme.ACCENT_BRIGHT, item.confidenceText(),
                    confidenceColor(item.confidenceLevel()), null, 0, indent, null, -1, 0,
                    item.confidenceHover()));
        } else if (item.confidenceText() != null) {
            out.add(new Line("Confidence", SBSTheme.TEXT_MUTED, item.confidenceText(),
                    confidenceColor(item.confidenceLevel()), null, 0, indent, null, -1, 0, item.confidenceHover()));
        }
        if (!item.ok()) {
            out.add(new Line(item.name() + ": " + item.error(), SBSTheme.TEXT_MUTED, null, 0, null, 0,
                    indent, null, -1, 0, null));
            return;
        }
        for (LowballView.Box box : item.boxes()) {
            int tint = box.orange() ? BOX_ORANGE : BOX_RED;
            out.add(new Line(box.title(), box.orange() ? ORANGE : RED, null, 0, null, 0, indent, null, -1, tint, null));
            for (String text : box.lines()) {
                out.add(new Line("  " + text, SBSTheme.TEXT, null, 0, null, 0, indent, null, -1, tint, text));
            }
        }
        for (String warning : item.warnings()) {
            out.add(new Line(warning, RED, null, 0, null, 0, indent, null, -1, 0, warning));
        }
        if (!item.tiers().isEmpty()) {
            out.add(new Line("Sell", SBSTheme.TEXT_MUTED, "Price", SBSTheme.TEXT_MUTED, "Time", SBSTheme.TEXT_MUTED,
                    indent, null, -1, 0, null));
            for (LowballView.Tier tier : item.tiers()) {
                // A sell price is for the AH sign, never a /msg to the seller: always a plain copy.
                out.add(new Line(tier.label(), SBSTheme.TEXT, tier.price(), SBSTheme.ACCENT_BRIGHT, tier.time(),
                        SBSTheme.TEXT_MUTED, indent, PLAIN_COPY + tier.price(), -1, 0, "Click: copy " + tier.price()));
            }
        } else if (item.fastText() != null) {
            out.add(new Line("Value ~" + item.fastText(), SBSTheme.ACCENT_BRIGHT, null, 0, null, 0,
                    indent, null, -1, 0, null));
        }
        for (String market : item.market()) {
            out.add(new Line(market, SBSTheme.TEXT_MUTED, null, 0, null, 0, indent, null, -1, 0, market));
        }
        LowballView.Recommended rec = item.recommended();
        if (rec != null) {
            String why = rec.why().isEmpty() ? null : String.join("\n", rec.why());
            if (rec.copyText() != null) {
                out.add(new Line(rec.label(), GREEN, null, 0, null, 0, indent, rec.copyText(), -1, RECOMMENDED_BG,
                        "Click: " + Lowball.offerActionText(rec.copyText(), view.partner())
                                + (why != null ? "\n" + why : "")));
            } else {
                out.add(new Line(rec.label(), ORANGE, null, 0, null, 0, indent, null, -1, 0, why));
            }
            for (String line : rec.why()) {
                out.add(new Line("  " + line, SBSTheme.TEXT_MUTED, null, 0, null, 0, indent, null, -1, 0, line));
            }
        }
        if (!item.offers().isEmpty()) {
            int h = SBSTheme.TEXT_MUTED;
            out.add(new Line("Offer", h, "Price", h, "Profit after tax", h, "Margin", h, "Safety", h, true,
                    indent, null, -1, 0, "Profit is after AH tax (listing fee + 1 % claim), sold at the Fast price"));
            for (LowballView.OfferRow row : item.offers()) {
                String hover = "Click: " + Lowball.offerActionText(row.copyText(), view.partner())
                        + (row.closest() ? "\nclosest to the recommended offer" : "");
                out.add(new Line((row.closest() ? "▶ " : "") + row.discountText(),
                        row.closest() ? GREEN : SBSTheme.TEXT,
                        row.price(), SBSTheme.ACCENT_BRIGHT,
                        row.profit(), row.loss() ? RED : GREEN,
                        row.margin(), row.loss() ? RED : SBSTheme.TEXT,
                        row.safety(), SBSTheme.TEXT, true,
                        indent, row.copyText(), -1, row.closest() ? BUTTON_BG : 0, hover));
            }
        }
    }

    private static String nameOf(LowballView.Item item) {
        return item.count() > 1 ? item.name() + " x" + item.count() : item.name();
    }

    private static String orDash(String s) {
        return s == null ? "-" : s;
    }

    private static int confidenceColor(String level) {
        if (level == null) {
            return SBSTheme.TEXT_MUTED;
        }
        return switch (level) {
            case "high" -> GREEN;
            case "medium" -> YELLOW;
            case "low" -> RED;
            default -> SBSTheme.TEXT_MUTED;
        };
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    public void render(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!isOpen()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        memory.restore(state -> {
            posX = state.x;
            posY = state.y;
            sizeW = state.width(sizeW);
            sizeH = state.height(sizeH);
        });
        panelW = clamp(Math.min(sizeW, screen.width - MARGIN * 2), Math.min(MIN_W, screen.width - MARGIN * 2), MAX_W);
        panelH = clamp(Math.min(sizeH, screen.height - MARGIN * 2), Math.min(MIN_H, screen.height - MARGIN * 2), MAX_H);
        if (posX == Integer.MIN_VALUE) {
            posX = screen.width - panelW - 8;
            posY = 8;
        }
        posX = clamp(posX, MARGIN, Math.max(MARGIN, screen.width - panelW - MARGIN));
        posY = clamp(posY, MARGIN, Math.max(MARGIN, screen.height - panelH - MARGIN));
        if (builtForWidth != panelW) {
            build(font);
        }
        long second = System.currentTimeMillis() / 1000;
        if (second != footerSecond) {
            footerSecond = second;
            footer = view.footer(System.currentTimeMillis());
        }

        SciFiRender.glow(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, posX + 1, posY + 1, panelW - 2, panelH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int textY = posY + (HEADER_H - font.lineHeight) / 2 + 1;
        g.text(font, Component.literal(view.isTrade() ? "Lowball: trade" : "Lowball"), posX + PAD, textY,
                SBSTheme.ACCENT_BRIGHT);
        g.text(font, Component.literal("x"), closeX() + 3, textY,
                inCloseBox(mouseX, mouseY) ? SBSTheme.WARN : SBSTheme.TEXT_MUTED);
        g.fill(posX + PAD, posY + HEADER_H, posX + panelW - PAD, posY + HEADER_H + 1, SBSTheme.ACCENT_SOFT);

        int top = rowsTop();
        int visible = visibleRows();
        List<Line> list = lines;
        scrollRow = clamp(scrollRow, 0, Math.max(0, list.size() - visible));
        int colB = posX + PAD + (int) ((panelW - PAD * 2) * 0.55);
        int colC = posX + PAD + (int) ((panelW - PAD * 2) * 0.78);
        List<Component> hoverText = null;
        for (int i = 0; i < visible && scrollRow + i < list.size(); i++) {
            Line line = list.get(scrollRow + i);
            int y = top + i * ROW_H;
            int x = posX + PAD + line.indent();
            boolean clickable = line.copy() != null || line.toggle() >= 0;
            boolean hover = mouseX >= posX + PAD && mouseX < posX + panelW - PAD && mouseY >= y - 1 && mouseY < y + ROW_H - 1;
            if (line.tint() != 0) {
                g.fill(x - 2, y - 1, posX + panelW - PAD, y + ROW_H - 1, line.tint());
            }
            if (hover && clickable) {
                SciFiRender.roundedRect(g, x - 2, y - 1, posX + panelW - PAD - x + 2, ROW_H, 2, SBSTheme.CARD_BG_HOVER);
            }
            if (hover && line.hover() != null) {
                hoverText = hovers.get(scrollRow + i);
            }
            if (line.table()) {
                int width = posX + panelW - PAD - x;
                String[] cells = {line.a(), line.b(), line.c(), line.d(), line.e()};
                int[] colors = {line.colorA(), line.colorB(), line.colorC(), line.colorD(), line.colorE()};
                for (int c = 0; c < cells.length; c++) {
                    if (cells[c] != null) {
                        int cx = x + (int) (width * TABLE_X[c]);
                        int next = c + 1 < cells.length ? x + (int) (width * TABLE_X[c + 1]) : posX + panelW - PAD;
                        g.text(font, Component.literal(font.plainSubstrByWidth(cells[c], Math.max(0, next - cx - 3),
                                false)), cx, y, colors[c]);
                    }
                }
                continue;
            }
            int limitA = (line.b() != null ? colB : posX + panelW - PAD) - x - 4;
            g.text(font, Component.literal(font.plainSubstrByWidth(line.a(), Math.max(0, limitA), false)), x, y,
                    line.colorA());
            if (line.b() != null) {
                g.text(font, Component.literal(line.b()), colB, y, line.colorB());
            }
            if (line.c() != null) {
                g.text(font, Component.literal(line.c()), colC, y, line.colorC());
            }
        }
        if (footer != null) {
            g.text(font, Component.literal(font.plainSubstrByWidth(footer, panelW - PAD * 2, false)),
                    posX + PAD, posY + panelH - PAD - font.lineHeight + 1, SBSTheme.TEXT_MUTED);
        }
        resizer.renderGrips(g, posX, posY, panelW, panelH, mouseX, mouseY);
        if (hoverText != null) {
            g.setComponentTooltipForNextFrame(font, hoverText, mouseX, mouseY);
        }
    }

    private int rowsTop() {
        return posY + HEADER_H + 4;
    }

    private int visibleRows() {
        int bottom = posY + panelH - PAD - (footer != null ? ROW_H : 0);
        return Math.max(1, (bottom - rowsTop()) / ROW_H);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    public boolean handleClick(Screen screen, MouseButtonEvent event) {
        if (!isOpen()) {
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
        int index = scrollRow + (int) ((my - rowsTop() + 1) / ROW_H);
        if (my >= rowsTop() - 1 && index >= 0 && index < lines.size() && index - scrollRow < visibleRows()) {
            Line line = lines.get(index);
            if (line.copy() != null && line.copy().startsWith(PLAIN_COPY)) {
                Lowball.copy(line.copy().substring(PLAIN_COPY.length()));
            } else if (line.copy() != null) {
                Lowball.offerClicked(line.copy(), view.partner());
            } else if (line.toggle() >= 0) {
                if (!expanded.remove(line.toggle())) {
                    expanded.add(line.toggle());
                }
                builtForWidth = -1;
            }
        }
        return true;
    }

    public boolean handleDrag(Screen screen, MouseButtonEvent event) {
        if (!isOpen()) {
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
        memory.remember(posX, posY, sizeW, sizeH, false);
        return true;
    }

    public boolean handleScroll(Screen screen, double mouseX, double mouseY, double scrollY) {
        if (!isOpen() || !inPanel(mouseX, mouseY)) {
            return false;
        }
        if (isCtrlDown()) {
            int step = (int) Math.signum(scrollY);
            sizeW = clamp(sizeW + step * 30, MIN_W, MAX_W);
            sizeH = clamp(sizeH + step * 20, MIN_H, MAX_H);
            memory.remember(posX, posY, sizeW, sizeH, false);
            return true;
        }
        scrollRow = Math.max(0, scrollRow - (int) Math.signum(scrollY));
        return true;
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

    private static boolean isCtrlDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
