/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.hollows.HollowsDetector;
import sbs.modid.client.core.location.hollows.HollowsGeometry;
import sbs.modid.client.core.location.hollows.HollowsRegion;
import sbs.modid.client.core.location.hollows.HollowsStructure;
import sbs.modid.client.helper.map.logic.HollowsMapTracker;
import sbs.modid.client.helper.map.logic.HollowsTarget;
import sbs.modid.client.helper.map.model.HollowsLobbyMap;
import sbs.modid.client.helper.map.model.HollowsMarker;
import sbs.modid.client.helper.map.model.HollowsTrail;
import sbs.modid.client.helper.map.model.KnownStructure;
import sbs.modid.client.helper.map.model.MapViewport;
import sbs.modid.client.helper.map.render.HollowsMapPainter;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.component.SciFiSegmentedSwitch;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The schematic Crystal Hollows map: regions, the trail you walked, structures and your markers for
 * the lobby you are in, with a list of every structure type beside it.
 *
 * <p><b>Schematic, on purpose.</b> Nothing here comes from chunk or block data, and no other player
 * or mob is drawn: the regions are the ESTIMATED table in {@link HollowsGeometry}, the trail is where
 * you stood, the structures are where the zone line named them.
 *
 * <p>Clicking a structure or marker - on the map or in the list - makes it the target: a marker in
 * the world and a direction card on the HUD, never a route ({@link HollowsTarget}). Clicking it again
 * clears it.
 */
public final class HollowsMapScreen extends Screen implements sbs.modid.client.ui.theme.KeyedScreen {

    /** Stable id for per-screen settings (opacity). Never change it once shipped. */
    @Override
    public String screenId() {
        return "crystal_hollows_map";
    }

    private static final int PREFERRED_W = 760;
    private static final int PREFERRED_H = 520;

    /** Below this content width the structure list is left out and the map takes the room. */
    private static final int MIN_WIDTH_FOR_LIST = 260;

    private static final int SWITCH_H = 14;
    private static final int HOVER_SLOP = 5;

    /** Furthest zoom-in, as a multiple of the fit-to-canvas scale. */
    private static final double MAX_ZOOM = 12.0;

    private static final List<String> LAYERS = List.of("Upper", "Magma Fields");
    private static final List<String> SHORT_LAYERS = List.of("Up", "Magma");

    private List<String> layerLabels = LAYERS;

    private final Screen parent;

    private final MapViewport viewport = new MapViewport(HollowsGeometry.MIN, HollowsGeometry.MIN,
            HollowsGeometry.MAX, HollowsGeometry.MAX);
    private boolean viewportPlaced;

    private HollowsTrail.Layer layer;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;

    private int canvasX;
    private int canvasY;
    private int canvasW;
    private int canvasH;

    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private boolean showList;

    private final SciFiScrollbar scrollbar = new SciFiScrollbar();
    private int listScroll;

    /** A drag that began in the canvas pans; a press that never moved is a click on release. */
    private boolean pressInCanvas;
    private boolean dragged;

    /** Things under the cursor this frame. */
    private Item hovered;
    private final List<Item> listRows = new ArrayList<>();
    private final List<int[]> listRects = new ArrayList<>();

    /** One clickable thing on the map or in the list. */
    private record Item(String key, String label, int x, int y, int z, KnownStructure structure,
                        HollowsMarker marker) {

        HollowsTarget.Target target() {
            return new HollowsTarget.Target(key, label, x, y, z);
        }
    }

    public HollowsMapScreen(Screen parent) {
        super(Component.literal("Crystal Hollows Map"));
        this.parent = parent;
    }

    private static SBSConfig.MapSettings cfg() {
        return ConfigManager.getInstance().get().map;
    }

    public static void open() {
        Minecraft.getInstance().setScreenAndShow(new HollowsMapScreen(null));
    }

    @Override
    protected void init() {
        int availableW = Math.max(1, this.width - SBSTheme.SCREEN_MARGIN * 2);
        int availableH = Math.max(1, this.height - SBSTheme.SCREEN_MARGIN * 2);
        panelW = Math.min(availableW, PREFERRED_W);
        panelH = Math.min(availableH, PREFERRED_H);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;

        int contentX = panelX + SBSTheme.PANEL_PADDING;
        int contentW = panelW - SBSTheme.PANEL_PADDING * 2;
        int contentTop = dividerY + SBSTheme.GAP_AFTER_HEADER;
        int footerH = this.font.lineHeight + 4;
        int contentBottom = panelY + panelH - SBSTheme.PANEL_PADDING - footerH;

        showList = contentW >= MIN_WIDTH_FOR_LIST;
        listW = showList ? Math.min(160, Math.max(100, contentW * 32 / 100)) : 0;
        canvasX = contentX;
        canvasY = contentTop + SWITCH_H + 4;
        canvasW = Math.max(1, contentW - (showList ? listW + 6 : 0));
        canvasH = Math.max(1, contentBottom - canvasY);
        listX = canvasX + canvasW + 6;
        listY = contentTop;
        listH = Math.max(1, contentBottom - contentTop);

        viewport.setCanvas(canvasX, canvasY, canvasW, canvasH);
        Player player = Minecraft.getInstance().player;
        if (!viewportPlaced) {
            viewportPlaced = true;
            viewport.setScale(viewport.fitScale());
            if (player != null && HollowsDetector.getInstance().onHollows()) {
                viewport.setCenter(player.getX(), player.getZ());
                viewport.clampCenter();
            }
            layer = player != null && HollowsDetector.getInstance().onHollows()
                    ? HollowsTrail.Layer.of(player.getBlockY()) : HollowsTrail.Layer.UPPER;
        } else {
            viewport.setScale(Math.max(viewport.fitScale(), Math.min(viewport.fitScale() * MAX_ZOOM,
                    viewport.scale())));
        }

        addRenderableOnly(new PanelRenderable());
        // Measured, not assumed: on a canvas too narrow for the full names the switch says less rather
        // than drawing out of its row.
        layerLabels = SciFiSegmentedSwitch.widthFor(LAYERS) <= canvasW ? LAYERS : SHORT_LAYERS;
        addRenderableWidget(new SciFiSegmentedSwitch(canvasX, contentTop, SWITCH_H, layerLabels,
                () -> layer.ordinal(), index -> layer = HollowsTrail.Layer.values()[index]));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();
        if (showList && scrollbar.handleClick(mx, my, listScroll, value -> listScroll = value)) {
            return true;
        }
        for (int i = 0; i < listRects.size(); i++) {
            int[] r = listRects.get(i);
            if (hit(mx, my, r[0], r[1], r[2], r[3])) {
                Item row = listRows.get(i);
                if (row != null) {
                    HollowsTarget.getInstance().toggle(row.target());
                }
                return true;
            }
        }
        if (hit(mx, my, canvasX, canvasY, canvasW, canvasH)) {
            pressInCanvas = true;
            dragged = false;
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (scrollbar.handleDrag(event.y(), value -> listScroll = value)) {
            return true;
        }
        if (pressInCanvas) {
            if (Math.abs(dragX) + Math.abs(dragY) > 0) {
                dragged = true;
            }
            viewport.panBy(dragX, dragY);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (scrollbar.release()) {
            return true;
        }
        if (pressInCanvas) {
            pressInCanvas = false;
            if (!dragged && hovered != null) {
                HollowsTarget.getInstance().toggle(hovered.target());
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (hit(mouseX, mouseY, canvasX, canvasY, canvasW, canvasH)) {
            double fit = viewport.fitScale();
            viewport.zoomAt(scrollY > 0 ? 1.15 : 1 / 1.15, mouseX, mouseY, fit, fit * MAX_ZOOM);
            return true;
        }
        if (showList && hit(mouseX, mouseY, listX, listY, listW, listH)) {
            listScroll = Math.max(0, Math.min(scrollbar.maxScroll(),
                    listScroll - (int) Math.signum(scrollY) * (this.font.lineHeight * 2)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        // After every widget, so the card is never under the layer switch.
        if (hovered != null) {
            drawTooltip(g, this.font, mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------ data

    private static boolean visible(KnownStructure known) {
        return known.confirmed() || cfg().hollowsShareShowUnconfirmed;
    }

    private static Item item(KnownStructure known) {
        return new Item("S:" + known.structure().name(), known.structure().displayName(),
                known.x(), known.y(), known.z(), known, null);
    }

    private static Item item(HollowsMarker marker) {
        return new Item(HollowsMapTracker.markerKey(marker), marker.label(), marker.x(), marker.y(),
                marker.z(), null, marker);
    }

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String distance(Item item) {
        Player player = Minecraft.getInstance().player;
        if (player == null || !HollowsDetector.getInstance().onHollows()) {
            return "";
        }
        double dx = player.getX() - item.x();
        double dy = player.getY() - item.y();
        double dz = player.getZ() - item.z();
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + "m";
    }

    private String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
    }

    // ------------------------------------------------------------------ drawing

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = HollowsMapScreen.this.font;
            hovered = null;
            listRows.clear();
            listRects.clear();

            g.fill(0, 0, width, height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Crystal Hollows Map"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + SBSTheme.PANEL_PADDING, dividerY, panelX + panelW - SBSTheme.PANEL_PADDING,
                    dividerY + 1, SBSTheme.ACCENT);

            HollowsMapTracker tracker = HollowsMapTracker.getInstance();
            HollowsLobbyMap map = tracker.current();
            List<KnownStructure> known = tracker.known();

            drawLobbyLabel(g, font, map);
            drawCanvas(g, font, mouseX, mouseY, map, known);
            if (showList) {
                drawList(g, font, mouseX, mouseY, map, known);
            }
            drawFooter(g, font);
        }

        private void drawLobbyLabel(GuiGraphicsExtractor g, Font font, HollowsLobbyMap map) {
            int switchRight = canvasX + SciFiSegmentedSwitch.widthFor(layerLabels) + 6;
            int right = canvasX + canvasW;
            String text = map == null ? "§8Not on the Crystal Hollows"
                    : "§8Lobby §7" + (map.lobby().isEmpty() ? "unknown yet" : map.lobby());
            int room = right - switchRight;
            if (room > 20) {
                String shown = fit(font, text, room);
                g.text(font, Component.literal(shown), right - font.width(shown),
                        dividerY + SBSTheme.GAP_AFTER_HEADER + (SWITCH_H - font.lineHeight) / 2 + 1, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawCanvas(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, HollowsLobbyMap map,
                                List<KnownStructure> known) {
            SciFiRender.roundedRectWithBorder(g, canvasX, canvasY, canvasW, canvasH, SBSTheme.CORNER_RADIUS,
                    0xF00C1016, SBSTheme.CARD_BORDER);
            viewport.setCanvas(canvasX, canvasY, canvasW, canvasH);
            g.enableScissor(canvasX + 1, canvasY + 1, canvasX + canvasW - 1, canvasY + canvasH - 1);

            HollowsMapPainter.regions(g, viewport, layer, 0x38);
            if (map != null && cfg().hollowsShowTrail) {
                HollowsMapPainter.trail(g, viewport, map.trail(), layer, 0x46);
            }
            HollowsMapPainter.regionLabels(g, font, viewport, layer);

            boolean inCanvas = hit(mouseX, mouseY, canvasX, canvasY, canvasW, canvasH);
            double best = Double.MAX_VALUE;
            double reach = (HollowsMapPainter.ICON / 2.0 + HOVER_SLOP);
            boolean labels = viewport.scale() >= viewport.fitScale() * 1.6;
            List<Item> items = new ArrayList<>();
            for (KnownStructure k : known) {
                if (visible(k)) {
                    items.add(item(k));
                }
            }
            if (map != null && cfg().hollowsShowMarkers) {
                for (HollowsMarker marker : map.markers()) {
                    items.add(item(marker));
                }
            }
            for (Item it : items) {
                double[] p = viewport.toScreen(it.x(), it.z());
                int px = (int) Math.round(p[0]);
                int py = (int) Math.round(p[1]);
                boolean targeted = HollowsTarget.getInstance().is(it.key());
                if (targeted) {
                    SciFiRender.ring(g, px - 7, py - 7, 14, 14, 7, 0xFF57D977);
                }
                if (it.structure() != null) {
                    int rgb = HollowsGeometry.classify(it.x(), it.y(), it.z()).rgb();
                    HollowsMapPainter.structureIcon(g, font, px, py, it.structure().structure().glyph(),
                            rgb, it.structure().confirmed(), HollowsMapPainter.ICON);
                } else {
                    HollowsMapPainter.markerIcon(g, px, py, HollowsMapPainter.MARKER);
                }
                if (labels || targeted) {
                    int color = it.structure() != null && !it.structure().confirmed() ? 0xFF8A9096 : 0xFFD8DEE4;
                    g.centeredText(font, Component.literal(it.label()), px, py + HollowsMapPainter.ICON / 2 + 2, color);
                }
                double dx = mouseX - p[0];
                double dy = mouseY - p[1];
                double d = Math.sqrt(dx * dx + dy * dy);
                if (inCanvas && d <= reach && d < best) {
                    best = d;
                    hovered = it;
                }
            }
            drawPlayer(g);
            if (map == null) {
                g.centeredText(font, Component.literal("§7Not on the Crystal Hollows"), canvasX + canvasW / 2,
                        canvasY + canvasH / 2 - font.lineHeight, SBSTheme.TEXT_MUTED);
                g.centeredText(font, Component.literal("§8Only the lobby you are in is shown"),
                        canvasX + canvasW / 2, canvasY + canvasH / 2 + 2, SBSTheme.TEXT_MUTED);
            }
            g.disableScissor();
        }

        private void drawPlayer(GuiGraphicsExtractor g) {
            Player player = Minecraft.getInstance().player;
            if (player == null || !HollowsDetector.getInstance().onHollows()) {
                return;
            }
            double[] p = viewport.toScreen(player.getX(), player.getZ());
            double[] dir = viewport.screenDirection(player.getYRot());
            HollowsMapPainter.playerArrow(g, p[0], p[1], dir[0], dir[1], 6, 0x57D977);
        }

        /**
         * Every structure type, found or not reported yet, then the markers. Row geometry is computed
         * from the font, and the list scrolls through the shared scrollbar when it does not fit.
         */
        private void drawList(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, HollowsLobbyMap map,
                              List<KnownStructure> known) {
            SciFiRender.roundedRectWithBorder(g, listX, listY, listW, listH, SBSTheme.CORNER_RADIUS,
                    SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
            int rowH = font.lineHeight * 2 + 4;
            int headH = font.lineHeight + 4;
            int markerCount = map == null || !cfg().hollowsShowMarkers ? 0 : map.markers().size();
            int total = headH + HollowsStructure.values().length * rowH
                    + (map == null ? 0 : headH + Math.max(1, markerCount) * (font.lineHeight + 4)) + 4;
            int innerTop = listY + 3;
            int innerH = listH - 6;
            scrollbar.set(listX + listW - SciFiScrollbar.WIDTH - 2, innerTop, innerH, total, innerH);
            listScroll = Math.max(0, Math.min(scrollbar.maxScroll(), listScroll));
            int textW = listW - 10 - SciFiScrollbar.WIDTH;

            g.enableScissor(listX + 1, innerTop, listX + listW - 1, innerTop + innerH);
            int y = innerTop - listScroll;
            g.text(font, Component.literal("§bStructures"), listX + 5, y + 2, SBSTheme.TEXT);
            y += headH;
            for (HollowsStructure structure : HollowsStructure.values()) {
                KnownStructure k = null;
                for (KnownStructure candidate : known) {
                    if (candidate.structure() == structure) {
                        k = candidate;
                    }
                }
                Item row = k != null && visible(k) ? item(k) : null;
                boolean over = row != null && hit(mouseX, mouseY, listX, y, listW - SciFiScrollbar.WIDTH - 2, rowH)
                        && mouseY >= innerTop && mouseY < innerTop + innerH;
                if (over || (row != null && HollowsTarget.getInstance().is(row.key()))) {
                    SciFiRender.roundedRect(g, listX + 2, y, listW - SciFiScrollbar.WIDTH - 6, rowH - 1,
                            SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG_HOVER);
                }
                g.text(font, Component.literal(fit(font, structure.displayName(), textW)), listX + 5, y + 2,
                        row != null ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
                String status;
                if (row == null) {
                    status = "§8not reported yet";
                } else {
                    String where = row.x() + ", " + row.y() + ", " + row.z();
                    String far = distance(row);
                    String trust = k.own() ? "" : k.confirmed() ? " §8shared" : " §8unconfirmed";
                    status = "§a" + where + (far.isEmpty() ? "" : " §7" + far) + trust;
                }
                g.text(font, Component.literal(fit(font, status, textW)), listX + 5, y + 3 + font.lineHeight,
                        SBSTheme.TEXT_MUTED);
                listRows.add(row);
                listRects.add(visibleRect(y, rowH, innerTop, innerH));
                y += rowH;
            }
            if (map != null) {
                g.text(font, Component.literal("§bMarkers §8" + map.markers().size() + "/" + HollowsLobbyMap.MAX_MARKERS),
                        listX + 5, y + 4, SBSTheme.TEXT);
                y += headH;
                int lineH = font.lineHeight + 4;
                if (markerCount == 0) {
                    String hint = cfg().hollowsShowMarkers ? "§8/sbs chmap mark <label>" : "§8hidden in settings";
                    g.text(font, Component.literal(fit(font, hint, textW)), listX + 5, y + 2, SBSTheme.TEXT_MUTED);
                } else {
                    for (HollowsMarker marker : map.markers()) {
                        Item row = item(marker);
                        boolean over = hit(mouseX, mouseY, listX, y, listW - SciFiScrollbar.WIDTH - 2, lineH)
                                && mouseY >= innerTop && mouseY < innerTop + innerH;
                        if (over || HollowsTarget.getInstance().is(row.key())) {
                            SciFiRender.roundedRect(g, listX + 2, y, listW - SciFiScrollbar.WIDTH - 6, lineH - 1,
                                    SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG_HOVER);
                        }
                        String far = distance(row);
                        g.text(font, Component.literal(fit(font, "§e" + marker.label()
                                + (far.isEmpty() ? "" : " §7" + far), textW)), listX + 5, y + 2, SBSTheme.TEXT);
                        listRows.add(row);
                        listRects.add(visibleRect(y, lineH, innerTop, innerH));
                        y += lineH;
                    }
                }
            }
            g.disableScissor();
            scrollbar.render(g, listScroll, mouseX, mouseY);
        }

        /** A row's clickable box, cut to the visible list so a scrolled-away row cannot be clicked. */
        private int[] visibleRect(int y, int h, int innerTop, int innerH) {
            int top = Math.max(y, innerTop);
            int bottom = Math.min(y + h, innerTop + innerH);
            return new int[]{listX, top, listW - SciFiScrollbar.WIDTH - 2, Math.max(0, bottom - top)};
        }

        /** What the scoreboard says beside what the ESTIMATED layout says, then the controls. */
        private void drawFooter(GuiGraphicsExtractor g, Font font) {
            int y = panelY + panelH - SBSTheme.PANEL_PADDING - font.lineHeight + 2;
            int x = panelX + SBSTheme.PANEL_PADDING;
            int w = panelW - SBSTheme.PANEL_PADDING * 2;
            Player player = Minecraft.getInstance().player;
            String left;
            if (player != null && HollowsDetector.getInstance().onHollows()) {
                String zone = HollowsDetector.getInstance().lastZone();
                HollowsRegion predicted = HollowsGeometry.classify(player.getBlockX(), player.getBlockY(),
                        player.getBlockZ());
                left = "§7Scoreboard: §f" + (zone.isEmpty() ? "?" : zone) + "  §7Layout: §f"
                        + predicted.displayName() + " §8(estimated)";
            } else {
                left = "§8Click a structure or marker to target it";
            }
            String right = "§8click target · drag pan · scroll zoom";
            int rightW = font.width(right);
            if (font.width(left) + rightW + 12 <= w) {
                g.text(font, Component.literal(right), x + w - rightW, y, SBSTheme.TEXT_MUTED);
                g.text(font, Component.literal(left), x, y, SBSTheme.TEXT_MUTED);
            } else {
                // Not enough room for both: the zone check is the part worth keeping.
                g.text(font, Component.literal(fit(font, left, w)), x, y, SBSTheme.TEXT_MUTED);
            }
        }
    }

    /** Name, position, distance, how far to trust it, and what a click does. */
    private void drawTooltip(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        Item it = hovered;
        List<String> lines = new ArrayList<>();
        lines.add("§f" + it.label());
        lines.add("§7" + it.x() + ", " + it.y() + ", " + it.z());
        String far = distance(it);
        if (!far.isEmpty()) {
            lines.add("§7" + far + " away");
        }
        if (it.structure() != null) {
            KnownStructure k = it.structure();
            if (k.own()) {
                lines.add("§aFound by you §8(confirmed)");
            } else {
                lines.add("§bShared §8- " + k.confirmations() + " confirmation" + (k.confirmations() == 1 ? "" : "s")
                        + (k.confirmed() ? "" : ", unconfirmed"));
            }
        } else {
            lines.add("§eYour marker §8- only on this PC");
        }
        lines.add(HollowsTarget.getInstance().is(it.key()) ? "§8Click to clear the target" : "§8Click to target");

        int w = 0;
        for (String line : lines) {
            w = Math.max(w, font.width(line));
        }
        w += 10;
        int h = lines.size() * (font.lineHeight + 1) + 7;
        int x = mouseX + 12 + w > panelX + panelW ? mouseX - 12 - w : mouseX + 12;
        int y = Math.max(panelY, Math.min(mouseY, panelY + panelH - h - 2));
        SciFiRender.roundedRect(g, x, y, w, h, SBSTheme.CORNER_RADIUS, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, x + 1, y + 1, w - 2, h - 2, SBSTheme.CORNER_RADIUS - 1,
                0xFF10161E, 0xFF0C1118);
        int ty = y + 4;
        for (String line : lines) {
            g.text(font, Component.literal(line), x + 5, ty, SBSTheme.TEXT);
            ty += font.lineHeight + 1;
        }
    }
}
