/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.secretroutes.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.dungeons.secretroutes.model.SecretRoute;
import sbs.modid.client.dungeons.secretroutes.logic.SecretRouteStore;
import sbs.modid.client.dungeons.secretroutes.logic.SecretRoutesManager;
import sbs.modid.client.dungeons.secretroutes.model.SecretWaypoint;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.skills.mining.ui.MiningRoutesScreen;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Secret Routes editor: pick/create a route for the detected room, run the walk + breaker scans,
 * place the four waypoint types, edit each point's description, and import/export routes. Everything
 * is gated on a recognised room - with none the screen says so and offers no actions (recording
 * writes room-relative data keyed by the room name, which does not exist without a match).
 *
 * <p>Immediate-mode buttons/fields exactly like {@link MiningRoutesScreen}: rects + actions are
 * rebuilt each frame and hit-tested on click.
 */
public final class SecretRoutesScreen extends Screen {

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
    private final Field descField = new Field(80, "description");
    private final Field importField = new Field(20000, "paste route JSON here");
    private SecretWaypoint editing;
    private int wpScroll;
    private String status = "";

    private final List<int[]> buttonRects = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();

    public SecretRoutesScreen(Screen parent) {
        super(Component.literal("Secret Routes"));
        this.parent = parent;
    }

    private static SecretRoutesManager mgr() {
        return SecretRoutesManager.getInstance();
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 460, 680);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 280, 460);
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
        syncName();
    }

    private void syncName() {
        SecretRoute route = mgr().selectedRoute();
        nameField.value = route != null ? route.name : "";
    }

    private List<Field> fields() {
        return List.of(nameField, descField, importField);
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            wpScroll = Math.max(0, wpScroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

    /** Writes the name field back onto the selected route, and the desc field onto the edited point. */
    private void commitFields() {
        SecretRoute route = mgr().selectedRoute();
        if (route != null && !nameField.value.isBlank()) {
            route.name = nameField.value.trim();
            SecretRouteStore.save();
        }
        if (editing != null) {
            mgr().setDescription(editing, descField.value);
        }
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
            Font font = SecretRoutesScreen.this.font;
            buttonRects.clear();
            buttonActions.clear();

            g.fill(0, 0, SecretRoutesScreen.this.width, SecretRoutesScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            String room = mgr().boundRoomName();
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            String title = room != null ? "Secret Routes  §7" + room : "Secret Routes  §cNo room detected";
            g.centeredText(font, Component.literal(title), panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, panelY + SBSTheme.HEADER_HEIGHT, panelX + panelW - pad,
                    panelY + SBSTheme.HEADER_HEIGHT + 1, SBSTheme.ACCENT);

            if (room == null) {
                g.centeredText(font, Component.literal("§7Stand in a recognised room to record routes."),
                        panelX + panelW / 2, panelY + panelH / 2 - 4, SBSTheme.TEXT_MUTED);
                g.centeredText(font, Component.literal("§8Routes are keyed by the room name and rotate with the run."),
                        panelX + panelW / 2, panelY + panelH / 2 + 8, SBSTheme.TEXT_MUTED);
                return;
            }

            drawRouteList(g, font, mouseX, mouseY);
            drawDetail(g, font, mouseX, mouseY);

            int by = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
            button(g, font, listX, by, listW, SBSTheme.SEARCH_HEIGHT, "§a+ New Route", mouseX, mouseY, () -> {
                mgr().createRoute("Route " + (mgr().routesForCurrentRoom().size() + 1));
                syncName();
            });
            if (!status.isEmpty()) {
                g.text(font, Component.literal("§7" + status), detailX, by + 5, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawRouteList(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            List<SecretRoute> routes = mgr().routesForCurrentRoom();
            int sel = SecretRoutesManager.cfg().selectedRoute;
            int rowH = 22;
            int y = listTop;
            for (int i = 0; i < routes.size() && y + rowH <= listBottom; i++) {
                SecretRoute route = routes.get(i);
                boolean selected = i == sel;
                boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + rowH - 2;
                SciFiRender.roundedRectWithBorder(g, listX, y, listW, rowH - 2, SBSTheme.CORNER_RADIUS,
                        hover || selected ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                        selected ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
                g.text(font, Component.literal((selected ? "§f" : "§7") + route.name), listX + 6, y + 3, SBSTheme.TEXT);
                g.text(font, Component.literal("§8" + route.waypoints.size() + " pts · "
                        + route.breakerBlocks.size() + " breaker"), listX + 6, y + 3 + font.lineHeight, SBSTheme.TEXT_MUTED);
                int index = i;
                buttonRects.add(new int[]{listX, y, listW - 20, rowH - 2});
                buttonActions.add(() -> {
                    mgr().selectRoute(index);
                    editing = null;
                    wpScroll = 0;
                    syncName();
                });
                button(g, font, listX + listW - 19, y + 2, 18, rowH - 6, "§cx", mouseX, mouseY, () -> {
                    if (index == sel) {
                        mgr().deleteSelectedRoute();
                        editing = null;
                        syncName();
                    }
                });
                y += rowH;
            }
            if (routes.isEmpty()) {
                g.text(font, Component.literal("§8no routes yet — New Route"), listX, listTop, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawDetail(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            SecretRoute route = mgr().selectedRoute();
            int x = detailX;
            int y = listTop;
            int h = SBSTheme.SEARCH_HEIGHT;
            if (route == null) {
                g.text(font, Component.literal("§8select or create a route"), x, y, SBSTheme.TEXT_MUTED);
                return;
            }
            // Name.
            drawField(g, font, nameField, x, y, detailW, h);
            y += h + 4;

            // Scan row.
            int third = (detailW - 8) / 3;
            boolean scanning = mgr().isScanning();
            button(g, font, x, y, third, h, scanning ? "§cStop Scan" : "§aStart Scan", mouseX, mouseY, () -> {
                if (mgr().isScanning()) {
                    mgr().stopScan();
                } else {
                    mgr().startScan();
                }
            });
            boolean breaker = mgr().isBreakerScanning();
            button(g, font, x + third + 4, y, third, h, breaker ? "§cBreaker: ON" : "Breaker: OFF", mouseX, mouseY,
                    () -> mgr().setBreakerScan(!mgr().isBreakerScanning()));
            button(g, font, x + (third + 4) * 2, y, third, h, "Subtype: " + prettySubtype(), mouseX, mouseY,
                    mgr()::cycleSubtype);
            y += h + 4;

            // Add row.
            int quarter = (detailW - 12) / 4;
            button(g, font, x, y, quarter, h, "+ Stand", mouseX, mouseY, () -> {
                mgr().addStanding();
                status = "point added";
            });
            button(g, font, x + (quarter + 4), y, quarter, h, "Scan Item", mouseX, mouseY, () -> {
                mgr().scanItemsAtFeet();
                status = "secret added";
            });
            button(g, font, x + (quarter + 4) * 2, y, quarter, h, "+ AOTV", mouseX, mouseY, () -> {
                mgr().addAotv();
                status = "AOTV added";
            });
            button(g, font, x + (quarter + 4) * 3, y, quarter, h, "+ Pearl", mouseX, mouseY, () -> {
                mgr().addPearl();
                status = "pearl added";
            });
            y += h + 6;

            // Description editor (for the point being edited).
            if (editing != null) {
                g.text(font, Component.literal("§7Description for #" + (editing.index + 1)), x, y, SBSTheme.TEXT_MUTED);
                y += font.lineHeight + 1;
                drawField(g, font, descField, x, y, detailW - 46, h);
                button(g, font, x + detailW - 42, y, 42, h, "§aOK", mouseX, mouseY, () -> {
                    mgr().setDescription(editing, descField.value);
                    editing = null;
                });
                y += h + 6;
            }

            // Waypoint list (scrollable).
            int wpTop = y;
            int wpBottom = panelY + panelH - pad() - SBSTheme.SEARCH_HEIGHT - h - 10;
            drawWaypointList(g, font, route, x, wpTop, wpBottom, mouseX, mouseY);

            // Export / import at the very bottom of the detail column.
            int iy = wpBottom + 4;
            drawField(g, font, importField, x, iy, detailW - 130, h);
            button(g, font, x + detailW - 126, iy, 60, h, "§aImport", mouseX, mouseY, () -> {
                String source = importField.value.isBlank()
                        ? Minecraft.getInstance().keyboardHandler.getClipboard() : importField.value;
                int n = SecretRouteStore.importJson(source);
                status = n >= 0 ? "imported " + n : "§cimport failed";
                importField.value = "";
                syncName();
            });
            button(g, font, x + detailW - 62, iy, 62, h, "Export", mouseX, mouseY, () -> {
                String json = SecretRouteStore.exportRoom(mgr().boundRoomName());
                if (json != null) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(json);
                    status = "copied " + json.length() + " chars";
                } else {
                    status = "§cnothing to export";
                }
            });
        }

        private void drawWaypointList(GuiGraphicsExtractor g, Font font, SecretRoute route,
                                      int x, int top, int bottom, int mouseX, int mouseY) {
            List<SecretWaypoint> points = route.waypoints;
            int rowH = 12;
            int visible = Math.max(1, (bottom - top) / rowH);
            wpScroll = Math.max(0, Math.min(wpScroll, Math.max(0, points.size() - visible)));
            if (points.isEmpty()) {
                g.text(font, Component.literal("§8no points — stand in the room and use the add buttons"),
                        x, top, SBSTheme.TEXT_MUTED);
                return;
            }
            int y = top;
            for (int i = wpScroll; i < points.size() && i < wpScroll + visible; i++) {
                SecretWaypoint wp = points.get(i);
                String desc = wp.description == null || wp.description.isBlank() ? "" : " §8" + wp.description;
                String label = (wp.enabled ? "§f" : "§8") + "#" + (wp.index + 1) + " §7" + wp.typeLabel() + desc;
                String fitted = fit(font, label, detailW - 54);
                g.text(font, Component.literal(fitted), x, y + 2, SBSTheme.TEXT);
                // Edit / enable / delete micro-buttons hard right.
                button(g, font, x + detailW - 52, y, 16, rowH - 1, "§bE", mouseX, mouseY, () -> {
                    editing = wp;
                    descField.value = wp.description == null ? "" : wp.description;
                    descField.focused = true;
                });
                button(g, font, x + detailW - 34, y, 16, rowH - 1, wp.enabled ? "§a◉" : "§8○", mouseX, mouseY,
                        () -> mgr().toggleWaypoint(wp));
                button(g, font, x + detailW - 16, y, 16, rowH - 1, "§cx", mouseX, mouseY, () -> {
                    mgr().deleteWaypoint(wp);
                    if (editing == wp) {
                        editing = null;
                    }
                });
                y += rowH;
            }
            if (points.size() > visible) {
                g.text(font, Component.literal("§8" + (wpScroll + 1) + "-"
                                + Math.min(points.size(), wpScroll + visible) + "/" + points.size() + " (scroll)"),
                        x, bottom - font.lineHeight, SBSTheme.TEXT_MUTED);
            }
        }
    }

    private static int pad() {
        return SBSTheme.PANEL_PADDING;
    }

    private String prettySubtype() {
        SecretWaypoint.Secret subtype = mgr().currentSubtype();
        String[] parts = subtype.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
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
        String fitted = shown;
        while (font.width(fitted) > w - 8 && fitted.length() > 1) {
            fitted = fitted.substring(1);
        }
        g.text(font, Component.literal(fitted), x + 4, y + (h - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
        if (field.focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caretX = x + 4 + font.width(fitted);
            g.fill(caretX, y + 2, caretX + 1, y + h - 2, SBSTheme.ACCENT_BRIGHT);
        }
    }
}
