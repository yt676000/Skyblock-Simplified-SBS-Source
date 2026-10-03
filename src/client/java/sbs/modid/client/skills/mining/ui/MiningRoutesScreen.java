/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningRoute;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.skills.mining.logic.MiningRoutesManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages Mining Routes: the route list (select / show / delete), the selected route's name, colour,
 * loop and waypoint actions, and the clipboard import/export that lets routes be shared.
 */
public final class MiningRoutesScreen extends Screen {

    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_NUMPAD_ENTER = 335;
    private static final int KEY_BACKSPACE = 259;

    private static final class Field {
        String value = "";
        boolean focused;
        int x;
        int y;
        int w;
        int h;
        final int max;
        final String hint;

        Field(int max, String hint) {
            this.max = max;
            this.hint = hint;
        }

        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final Screen parent;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listW;
    private int detailX;
    private int detailW;
    private int listTop;
    private int listBottom;

    private final Field nameField = new Field(24, "route name");
    private final Field colorField = new Field(6, "RRGGBB");
    /**
     * Shared routes are routinely tens of thousands of characters - a few hundred waypoints as plain
     * JSON. The old 4000 cap silently truncated those mid-paste, so the import could only ever fail on
     * exactly the routes people most wanted to import.
     */
    private final Field importField = new Field(200_000, "paste a route here (or just copy it)");
    private String status = "";
    private int lastSelected = -1;

    private final List<int[]> buttonRects = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();

    public MiningRoutesScreen(Screen parent) {
        super(Component.literal("Mining Routes"));
        this.parent = parent;
    }

    private static MiningRoutesManager mgr() {
        return MiningRoutesManager.getInstance();
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 420, 620);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 260, 440);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = SBSTheme.PANEL_PADDING;
        listX = panelX + pad;
        listW = (panelW - pad * 3) * 2 / 5;
        detailX = listX + listW + pad;
        detailW = panelX + panelW - pad - detailX;
        listTop = panelY + SBSTheme.HEADER_HEIGHT + 8;
        listBottom = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT - 8;
        addRenderableOnly(new PanelRenderable());
        syncFieldsToSelected();
    }

    /** Load the selected route's name/colour into the edit fields (once, on selection change). */
    private void syncFieldsToSelected() {
        List<MiningRoute> routes = mgr().routes();
        int sel = MiningRoutesManager.cfg().selectedRoute;
        if (sel >= 0 && sel < routes.size()) {
            nameField.value = routes.get(sel).name;
            colorField.value = routes.get(sel).colorHex;
        }
        lastSelected = sel;
    }

    private MiningRoute selectedRoute() {
        List<MiningRoute> routes = mgr().routes();
        int sel = MiningRoutesManager.cfg().selectedRoute;
        return sel >= 0 && sel < routes.size() ? routes.get(sel) : null;
    }

    private List<Field> fields() {
        return List.of(nameField, colorField, importField);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();
        for (Field f : fields()) {
            f.focused = f.hit(mx, my);
        }
        for (int i = 0; i < buttonRects.size(); i++) {
            int[] r = buttonRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                buttonActions.get(i).run();
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        for (Field f : fields()) {
            if (!f.focused) {
                continue;
            }
            if (key == KEY_ESCAPE || key == KEY_ENTER || key == KEY_NUMPAD_ENTER) {
                f.focused = false;
                commitFields();
            } else if (key == KEY_BACKSPACE && !f.value.isEmpty()) {
                f.value = f.value.substring(0, f.value.length() - 1);
            } else if (event.isPaste()) {
                String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                if (clip != null) {
                    f.value += clip.trim();
                    if (f.value.length() > f.max) {
                        f.value = f.value.substring(0, f.max);
                    }
                }
            }
            return true;
        }
        if (key == KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        for (Field f : fields()) {
            if (f.focused) {
                if (event.isAllowedChatCharacter() && f.value.length() < f.max) {
                    f.value += event.codepointAsString();
                }
                return true;
            }
        }
        return super.charTyped(event);
    }

    /** Writes the name/colour fields back onto the selected route. */
    private void commitFields() {
        MiningRoute route = selectedRoute();
        if (route == null) {
            return;
        }
        if (!nameField.value.isBlank()) {
            route.name = nameField.value.trim();
        }
        if (colorField.value.matches("(?i)[0-9a-f]{6}")) {
            route.colorHex = colorField.value;
        }
        ConfigManager.getInstance().save();
    }

    @Override
    public void onClose() {
        commitFields();
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = MiningRoutesScreen.this.font;
            buttonRects.clear();
            buttonActions.clear();

            g.fill(0, 0, MiningRoutesScreen.this.width, MiningRoutesScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Mining Routes"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, panelY + SBSTheme.HEADER_HEIGHT, panelX + panelW - pad,
                    panelY + SBSTheme.HEADER_HEIGHT + 1, SBSTheme.ACCENT);

            drawRouteList(g, font, mouseX, mouseY);
            drawDetail(g, font, mouseX, mouseY);

            // Bottom row.
            int by = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
            button(g, font, listX, by, 90, SBSTheme.SEARCH_HEIGHT, "§aNew Route", mouseX, mouseY, () -> {
                mgr().createRoute("Route " + (mgr().routes().size() + 1));
                syncFieldsToSelected();
            });
            if (!status.isEmpty()) {
                g.text(font, Component.literal("§7" + status), listX + 96, by + 5, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawRouteList(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            List<MiningRoute> routes = mgr().routes();
            int sel = MiningRoutesManager.cfg().selectedRoute;
            int rowH = 22;
            int y = listTop;
            for (int i = 0; i < routes.size() && y + rowH <= listBottom; i++) {
                MiningRoute route = routes.get(i);
                boolean selected = i == sel;
                boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + rowH - 2;
                SciFiRender.roundedRectWithBorder(g, listX, y, listW, rowH - 2, SBSTheme.CORNER_RADIUS,
                        hover || selected ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                        selected ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
                int dot = MiningRoutesManager.routeColor(route.colorHex);
                g.fill(listX + 4, y + rowH / 2 - 4, listX + 11, y + rowH / 2 + 3, dot);
                g.text(font, Component.literal((selected ? "§f" : "§7") + route.name), listX + 15, y + 3,
                        SBSTheme.TEXT);
                g.text(font, Component.literal("§8" + route.points.size() + " pts"), listX + 15,
                        y + 3 + font.lineHeight, SBSTheme.TEXT_MUTED);
                int index = i;
                // Row = select.
                buttonRects.add(new int[]{listX, y, listW - 40, rowH - 2});
                buttonActions.add(() -> {
                    MiningRoutesManager.cfg().selectedRoute = index;
                    ConfigManager.getInstance().save();
                    syncFieldsToSelected();
                });
                // Visible toggle + delete.
                button(g, font, listX + listW - 38, y + 2, 18, rowH - 6, route.visible ? "§a◉" : "§8○",
                        mouseX, mouseY, () -> {
                            route.visible = !route.visible;
                            ConfigManager.getInstance().save();
                        });
                button(g, font, listX + listW - 19, y + 2, 18, rowH - 6, "§cx", mouseX, mouseY, () -> {
                    mgr().deleteRoute(index);
                    syncFieldsToSelected();
                });
                y += rowH;
            }
            if (routes.isEmpty()) {
                g.text(font, Component.literal("§8no routes - New Route below"), listX, listTop,
                        SBSTheme.TEXT_MUTED);
            }
        }

        private void drawDetail(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            MiningRoute route = selectedRoute();
            int x = detailX;
            int y = listTop;
            if (route == null) {
                g.text(font, Component.literal("§8select or create a route"), x, y, SBSTheme.TEXT_MUTED);
                return;
            }
            int h = SBSTheme.SEARCH_HEIGHT;
            g.text(font, Component.literal("§7Name"), x, y, SBSTheme.TEXT_MUTED);
            y += font.lineHeight + 1;
            drawField(g, font, nameField, x, y, detailW, h);
            y += h + 4;
            g.text(font, Component.literal("§7Colour (RRGGBB)"), x, y, SBSTheme.TEXT_MUTED);
            y += font.lineHeight + 1;
            drawField(g, font, colorField, x, y, 80, h);
            button(g, font, x + 86, y, detailW - 86, h, route.loop ? "§aLoop: ON" : "Loop: OFF",
                    mouseX, mouseY, () -> {
                        route.loop = !route.loop;
                        ConfigManager.getInstance().save();
                    });
            y += h + 6;

            // Waypoint actions.
            button(g, font, x, y, (detailW - 8) / 3, h, "§a+ Waypoint", mouseX, mouseY, () -> {
                mgr().addWaypointAtPlayer();
                status = route.points.size() + " points";
            });
            button(g, font, x + (detailW - 8) / 3 + 4, y, (detailW - 8) / 3, h, "Undo", mouseX, mouseY, () -> {
                mgr().undoLastWaypoint();
                status = route.points.size() + " points";
            });
            button(g, font, x + ((detailW - 8) / 3 + 4) * 2, y, (detailW - 8) / 3, h, "§cClear", mouseX, mouseY, () -> {
                mgr().clearSelected();
                status = "cleared";
            });
            y += h + 6;

            // Export / import.
            button(g, font, x, y, detailW, h, "Export → Clipboard", mouseX, mouseY, () -> {
                String code = mgr().exportRoute(MiningRoutesManager.cfg().selectedRoute);
                Minecraft.getInstance().keyboardHandler.setClipboard(code);
                status = "copied " + code.length() + " chars";
            });
            y += h + 6;
            g.text(font, Component.literal("§7Import - copy a route, then press Import"), x, y, SBSTheme.TEXT_MUTED);
            y += font.lineHeight + 1;
            drawField(g, font, importField, x, y, detailW - 66, h);
            button(g, font, x + detailW - 62, y, 62, h, "§aImport", mouseX, mouseY, () -> {
                String source = importField.value.isBlank()
                        ? Minecraft.getInstance().keyboardHandler.getClipboard() : importField.value;
                String name = mgr().importRoute(source);
                status = name != null
                        ? "imported \"" + name + "\" ("
                                + mgr().routes().get(MiningRoutesManager.cfg().selectedRoute).points.size()
                                + " points)"
                        : "§cno waypoints found in that text";
                importField.value = "";
                syncFieldsToSelected();
            });
        }
    }

    private void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String label,
                        int mouseX, int mouseY, Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        g.centeredText(font, Component.literal(label), x + w / 2, y + (h - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
        buttonRects.add(new int[]{x, y, w, h});
        buttonActions.add(action);
    }

    private void drawField(GuiGraphicsExtractor g, Font font, Field field, int x, int y, int w, int h) {
        field.x = x;
        field.y = y;
        field.w = w;
        field.h = h;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                field.focused ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        String shown = field.value.isEmpty() && !field.focused ? "§8" + field.hint : field.value;
        // Only the tail of a long value is ever visible, so clamp to a tail that cannot be narrower
        // than the box before measuring: no glyph is under a pixel wide, so `limit` characters always
        // over-fill it. Measuring the whole value here instead - one full-width call per character
        // dropped - made pasting a real route stall the screen at a few thousand characters.
        int limit = w - 8;
        String fitted = shown.length() > limit + 1 ? shown.substring(shown.length() - limit - 1) : shown;
        while (font.width(fitted) > limit && fitted.length() > 1) {
            fitted = fitted.substring(1);
        }
        g.text(font, Component.literal(fitted), x + 4, y + (h - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
        if (field.focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caretX = x + 4 + font.width(fitted);
            g.fill(caretX, y + 2, caretX + 1, y + h - 2, SBSTheme.ACCENT_BRIGHT);
        }
    }
}
