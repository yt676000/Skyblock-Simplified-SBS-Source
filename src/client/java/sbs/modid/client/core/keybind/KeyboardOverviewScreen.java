/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import sbs.modid.client.core.keybind.KeyBindOverview.Bind;
import sbs.modid.client.core.keybind.KeyBindOverview.Status;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * A drawn keyboard and mouse showing which keys carry a bind, and what is on each.
 *
 * <p>Every key with a bind is tinted; a key whose binds need different modifiers is amber, and one
 * where two binds fire on the same press is red. State never rides on colour alone: a key with more
 * than one bind also carries its count, and a conflict a "!". Hovering a key lists its binds below the
 * board; clicking (or pressing the key itself) pins that list. A bind with a known home has a
 * "Open" affordance – clicking the row goes to where it is set.
 *
 * <p>The board shrinks with the viewport: the numpad and then the navigation cluster are dropped
 * before caps get too small to read ({@link KeyboardLayout#fitting}). With nothing selected, the list
 * shows every conflicting bind, which is what most players open this screen to find.
 */
public final class KeyboardOverviewScreen extends Screen {

    /** Smallest cap size at which a short label is still readable. */
    private static final float MIN_UNIT = 12;
    private static final float MAX_UNIT = 26;
    private static final int GAP = 4;
    private static final int SHARED_COLOR = 0xFFE0A14D;
    private static final int BOUND_COLOR = 0xFF3FB4FF;
    /** Pseudo-code for "keys the picture has no cap for". */
    private static final int OTHER = Integer.MIN_VALUE;

    private final Screen parent;

    private KeyBindOverview overview = KeyBindOverview.of(List.of());
    private KeyboardLayout layout = KeyboardLayout.build(KeyboardLayout.Size.FULL);
    private List<Integer> outside = List.of();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int innerW;
    private int dividerY;
    private float unit;
    private int boardX;
    private int boardY;
    private int legendY;
    private int otherX;
    private int otherW;
    private int listTop;
    private int listH;
    private int rowH;

    /** The form factor the player picked, or {@code null} for "whatever fits". Kept for the session. */
    private static KeyboardLayout.Size chosenSize;

    private int activeX;
    private int activeW;
    private int switcherLeft;
    private int switcherRight;
    private int pinned = 0;   // 0 = nothing pinned
    private int scroll;
    private final SciFiScrollbar bar = new SciFiScrollbar();
    /** The rows drawn last frame, for click hit-testing. */
    private final List<Bind> shownRows = new ArrayList<>();

    public KeyboardOverviewScreen(Screen parent) {
        super(Component.literal("Keyboard Overview"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        overview = KeyBindOverview.of(KeyBindSources.collect(this));

        int margin = this.width >= 480 ? SBSTheme.SCREEN_MARGIN : 6;
        panelW = Math.max(1, Math.min(this.width - margin * 2, 760));
        panelH = Math.max(1, Math.min(this.height - margin * 2, 460));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = this.width >= 480 ? SBSTheme.PANEL_PADDING : 6;
        innerX = panelX + pad;
        innerW = Math.max(1, panelW - pad * 2);

        int headerH = font.lineHeight + 10;
        dividerY = panelY + headerH;

        layout = chosenSize == null ? KeyboardLayout.fitting(innerW, MIN_UNIT)
                : KeyboardLayout.build(chosenSize);
        outside = overview.codesOutside(layout.codes());
        int contentTop = dividerY + GAP;
        int contentH = panelY + panelH - pad - contentTop;
        // The board gets at most ~55% of the height, so the bind list always keeps a few rows.
        unit = Math.min(innerW / layout.width(), Math.min(MAX_UNIT, contentH * 0.55f / layout.height()));
        unit = Math.max(4, unit);
        int boardW = Math.round(layout.width() * unit);
        boardX = innerX + (innerW - boardW) / 2;
        boardY = contentTop;
        legendY = boardY + Math.round(layout.height() * unit) + GAP;
        rowH = font.lineHeight + 2;
        listTop = legendY + font.lineHeight + GAP + rowH;   // one row for the list heading
        listH = Math.max(rowH, panelY + panelH - pad - listTop);

        activeW = 0;
        int closeW = Math.min(50, innerW / 4);
        addRenderableOnly(new PanelRenderable());
        addRenderableWidget(new SciFiButton(panelX + panelW - pad - closeW, panelY + 4, closeW,
                headerH - 8, Component.literal("Back"), this::onClose));

        // Size switcher in the header, between the title and Back: the active one is marked with
        // "▸" (not by colour alone), the same convention as the keybind editor's tabs.
        KeyboardLayout.Size[] sizes = KeyboardLayout.Size.values();
        int count = sizes.length + 1;
        int titleW = font.width("Keyboard") + 6;
        int room = innerW - closeW - GAP - titleW;
        int each = Math.min(46, room / count - 2);
        switcherRight = panelX + panelW - pad - closeW - GAP;
        if (each >= font.width("▸ 100%") + 2 || each >= font.width("100%") + 4) {
            int x = switcherRight - count * (each + 2);
            for (int i = count - 1; i >= 0; i--) {
                KeyboardLayout.Size size = i == 0 ? null : sizes[i - 1];
                String name = size == null ? "Auto" : size.label();
                boolean active = size == chosenSize;
                String text = active && font.width("▸ " + name) <= each - 4 ? "▸ " + name : name;
                int bx = x + i * (each + 2);
                if (active) {
                    activeX = bx;
                    activeW = each;
                }
                addRenderableWidget(new SciFiButton(bx, panelY + 4, each, headerH - 8,
                        Component.literal(text), () -> {
                            chosenSize = size;
                            rebuildWidgets();
                        }));
            }
            switcherLeft = x;
        } else {
            switcherLeft = switcherRight;   // too narrow: Auto only, which is the right answer there
        }
    }

    // ------------------------------------------------------------------ input

    private int capAt(double mx, double my) {
        for (KeyboardLayout.Cap cap : layout.caps()) {
            int x = boardX + Math.round(cap.x() * unit);
            int y = boardY + Math.round(cap.y() * unit);
            int w = Math.round(cap.w() * unit);
            int h = Math.round(cap.h() * unit);
            if (mx >= x && mx < x + w && my >= y && my < y + h) {
                return cap.code();
            }
        }
        if (!outside.isEmpty() && my >= legendY && my < legendY + font.lineHeight
                && mx >= otherX && mx < otherX + otherW) {
            return OTHER;
        }
        return 0;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (bar.handleClick(event.x(), event.y(), scroll, value -> scroll = value)) {
            return true;
        }
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        int code = capAt(event.x(), event.y());
        if (code != 0) {
            pinned = pinned == code ? 0 : code;
            scroll = 0;
            return true;
        }
        int row = rowAt(event.x(), event.y());
        if (row >= 0 && row < shownRows.size()) {
            Runnable open = shownRows.get(row).open();
            if (open != null) {
                open.run();
            }
            return true;
        }
        return false;
    }

    private int rowAt(double mx, double my) {
        if (mx < innerX || mx >= innerX + innerW - SciFiScrollbar.WIDTH - 2 || my < listTop) {
            return -1;
        }
        int i = (int) ((my - listTop) / rowH);
        return i < visibleRows() ? i : -1;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            return super.keyPressed(event);
        }
        // Pressing a key shows what is on it – the keyboard path to every cap.
        pinned = event.key();
        scroll = 0;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY) * 2, bar.maxScroll()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return bar.handleDrag(event.y(), value -> scroll = value) || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return bar.release() || super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ model helpers

    private int visibleRows() {
        return Math.max(1, listH / rowH);
    }

    private String keyName(int code) {
        if (code == OTHER) {
            return "Other keys";
        }
        return Keys.displayName(code).getString();
    }

    private List<Bind> bindsFor(int code) {
        if (code == OTHER) {
            List<Bind> out = new ArrayList<>();
            for (int c : outside) {
                out.addAll(overview.on(c));
            }
            return out;
        }
        if (code != 0) {
            return overview.on(code);
        }
        List<Bind> out = new ArrayList<>();
        for (int c : overview.codes()) {
            if (overview.conflicts(c)) {
                out.addAll(overview.on(c));
            }
        }
        return out;
    }

    private static int statusColor(Status status) {
        return switch (status) {
            case CONFLICT -> SBSTheme.WARN;
            case SHARED -> SHARED_COLOR;
            case BOUND -> BOUND_COLOR;
            case FREE -> SBSTheme.TEXT_MUTED;
        };
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ render

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = KeyboardOverviewScreen.this.font;
            g.fill(0, 0, width, height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (dividerY - panelY - font.lineHeight) / 2;
            String title = font.plainSubstrByWidth("Keyboard Overview", Math.max(0, switcherLeft - innerX - GAP));
            g.text(font, Component.literal(title), innerX, titleY, SBSTheme.ACCENT_BRIGHT);
            g.fill(innerX, dividerY, innerX + innerW, dividerY + 1, SBSTheme.ACCENT);
            if (activeW > 0) {
                g.fill(activeX, dividerY - 2, activeX + activeW, dividerY + 1, SBSTheme.ACCENT_BRIGHT);
            }

            int hovered = capAt(mouseX, mouseY);
            drawBoard(g, font, hovered);
            drawLegend(g, font);
            drawList(g, font, pinned != 0 ? pinned : hovered, mouseX, mouseY);
        }

        private void drawBoard(GuiGraphicsExtractor g, Font font, int hovered) {
            for (KeyboardLayout.Cap cap : layout.caps()) {
                int x = boardX + Math.round(cap.x() * unit);
                int y = boardY + Math.round(cap.y() * unit);
                int w = Math.max(2, Math.round((cap.x() + cap.w()) * unit) - Math.round(cap.x() * unit) - 1);
                int h = Math.max(2, Math.round((cap.y() + cap.h()) * unit) - Math.round(cap.y() * unit) - 1);
                Status status = overview.status(cap.code());
                int border = cap.code() == pinned ? SBSTheme.ACCENT_BRIGHT
                        : cap.code() == hovered ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER;
                int fill = status == Status.FREE ? SBSTheme.SEARCH_FILL : withAlpha(statusColor(status), 0x90);
                g.fill(x, y, x + w, y + h, border);
                g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);

                String label = cap.label() != null ? cap.label() : Keys.displayName(cap.code()).getString();
                int count = overview.on(cap.code()).size();
                if (h >= font.lineHeight) {
                    String fitted = font.plainSubstrByWidth(label, Math.max(0, w - 2));
                    int tx = x + (w - font.width(fitted)) / 2;
                    int ty = y + (h - font.lineHeight) / 2 + 1;
                    int textColor = status == Status.FREE ? SBSTheme.TEXT_MUTED : SBSTheme.TEXT;
                    g.text(font, Component.literal(fitted), tx, ty, textColor);
                }
                // Count / conflict marker: state that survives without telling colours apart.
                if (count > 1 && w >= 8 && h >= 8) {
                    String mark = status == Status.CONFLICT ? "!" : String.valueOf(count);
                    g.fill(x + w - 5, y + 1, x + w - 1, y + 5, statusColor(status));
                    if (unit >= 18) {
                        g.text(font, Component.literal(mark), x + w - font.width(mark) - 1, y + 1,
                                SBSTheme.ACCENT_BRIGHT);
                    }
                }
            }
        }

        private void drawLegend(GuiGraphicsExtractor g, Font font) {
            int x = innerX;
            int conflicts = overview.conflictCount();
            String[] texts = {
                    overview.size() + " binds",
                    "Bound",
                    "Different modifiers",
                    "Conflict (" + conflicts + ")"
            };
            int[] colors = {0, BOUND_COLOR, SHARED_COLOR, SBSTheme.WARN};
            int right = innerX + innerW;
            String other = outside.isEmpty() ? "" : "+" + outside.size() + " other keys";
            int otherWidth = other.isEmpty() ? 0 : font.width(other) + 6;
            for (int i = 0; i < texts.length; i++) {
                int swatch = colors[i] == 0 ? 0 : 8;
                int need = swatch + font.width(texts[i]) + 8;
                if (x + need > right - otherWidth) {
                    break;   // drop the rest rather than overlap
                }
                if (swatch > 0) {
                    g.fill(x, legendY + 1, x + 6, legendY + 7, colors[i]);
                }
                g.text(font, Component.literal(texts[i]), x + swatch, legendY, SBSTheme.TEXT_MUTED);
                x += need;
            }
            otherW = 0;
            if (!other.isEmpty() && right - otherWidth >= innerX) {
                otherX = right - otherWidth + 6;
                otherW = otherWidth - 6;
                g.text(font, Component.literal(other), otherX, legendY,
                        pinned == OTHER ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);
            }
        }

        private void drawList(GuiGraphicsExtractor g, Font font, int code, int mouseX, int mouseY) {
            List<Bind> binds = bindsFor(code);
            String heading;
            if (code == 0) {
                heading = binds.isEmpty()
                        ? "No conflicts. Hover, click or press a key to see its binds."
                        : "All conflicts – hover, click or press a key for its binds";
            } else {
                heading = keyName(code) + ": " + (binds.isEmpty() ? "free" : binds.size() + " bind"
                        + (binds.size() == 1 ? "" : "s")) + (pinned != 0 ? "  (click again to unpin)" : "");
            }
            g.text(font, Component.literal(font.plainSubstrByWidth(heading, innerW)), innerX,
                    listTop - rowH, SBSTheme.ACCENT_BRIGHT);

            int visible = visibleRows();
            bar.set(innerX + innerW - SciFiScrollbar.WIDTH, listTop, visible * rowH, binds.size(), visible);
            scroll = Math.max(0, Math.min(scroll, bar.maxScroll()));
            int textW = innerW - SciFiScrollbar.WIDTH - 4;
            shownRows.clear();
            for (int i = 0; i < visible && scroll + i < binds.size(); i++) {
                Bind bind = binds.get(scroll + i);
                shownRows.add(bind);
                int y = listTop + i * rowH;
                boolean hover = mouseX >= innerX && mouseX < innerX + textW && mouseY >= y && mouseY < y + rowH;
                if (hover && bind.open() != null) {
                    g.fill(innerX, y - 1, innerX + textW, y + rowH - 1, SBSTheme.CARD_BG_HOVER);
                }
                // Value-first: the bind's name yields to space last, the origin first.
                String open = bind.open() != null ? "  Open ›" : "";
                int openW = font.width(open);
                String keyPart = code == 0 || code == OTHER
                        ? bind.modifierPrefix() + keyName(bind.code()) + "  " : bind.modifierPrefix();
                String main = keyPart + bind.name();
                String origin = "  " + bind.source().label() + " · " + bind.group();
                int room = Math.max(0, textW - openW - 4);
                String mainFit = font.plainSubstrByWidth(main, room);
                int originRoom = room - font.width(mainFit);
                String originFit = originRoom > font.width("  ...")
                        ? font.plainSubstrByWidth(origin, originRoom) : "";
                int statusColor = statusColor(overview.status(bind.code()));
                g.fill(innerX, y + 1, innerX + 2, y + font.lineHeight - 1, statusColor);
                g.text(font, Component.literal(mainFit), innerX + 4, y, SBSTheme.TEXT);
                g.text(font, Component.literal(originFit), innerX + 4 + font.width(mainFit), y, SBSTheme.TEXT_MUTED);
                if (!open.isEmpty() && openW <= textW) {
                    g.text(font, Component.literal(open), innerX + textW - openW, y,
                            hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);
                }
            }
            bar.render(g, scroll, mouseX, mouseY);
        }
    }
}
