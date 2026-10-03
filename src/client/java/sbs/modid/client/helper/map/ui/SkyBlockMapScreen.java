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
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.map.logic.HollowsTracker;
import sbs.modid.client.helper.map.logic.MapDatabase;
import sbs.modid.client.helper.map.logic.MapNavigation;
import sbs.modid.client.helper.map.model.IslandMap;
import sbs.modid.client.helper.map.model.MapCategory;
import sbs.modid.client.helper.map.model.MapLocation;
import sbs.modid.client.helper.map.model.MapWarp;
import sbs.modid.client.helper.warp.WarpAvailability;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The SkyBlock map: an island's places laid out where they actually are, and a click to be taken to
 * one.
 *
 * <p>Not a terrain minimap – there is no chunk data here at all. It draws the curated catalogue from
 * {@link MapDatabase} projected onto the island's own coordinate rectangle, which is the thing a
 * player actually wants from a SkyBlock map: where the Bank is relative to the Bazaar, and how to get
 * to either.
 *
 * <p><b>The island you are on is selected on open</b>, because that is the map you nearly always
 * want; the sidebar switches to any other. Clicking a place hands it to {@link MapNavigation} and
 * closes the screen - the warp lands on a loading screen and this window would only be in the way.
 */
public final class SkyBlockMapScreen extends Screen implements sbs.modid.client.ui.theme.KeyedScreen {

    /** Stable id for per-screen settings (opacity). Never change it once shipped. */
    @Override
    public String screenId() {
        return "skyblock_map";
    }


    private static final int KEY_ESCAPE = 256;
    private static final int KEY_BACKSPACE = 259;

    private static final int SIDEBAR_W = 108;
    private static final int ROW_H = 13;
    private static final int SEARCH_H = 14;

    /** Marker size in pixels, and how close the cursor must be to count as hovering one. */
    private static final int MARKER = 9;
    private static final int HOVER_SLOP = 6;

    private static final int MIN_ZOOM = 40;
    private static final int MAX_ZOOM = 600;

    private final Screen parent;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;

    /** The map canvas rectangle. */
    private int canvasX;
    private int canvasY;
    private int canvasW;
    private int canvasH;

    private IslandMap selected;

    /** Pan offset in pixels, applied on top of the fitted projection. */
    private double panX;
    private double panY;

    private String search = "";
    private boolean searchFocused;

    /** Sidebar scroll, in rows. */
    private int islandScroll;

    /** Per-frame hit testing, rebuilt every render (the pattern the warp menu uses). */
    private final List<int[]> markerRects = new ArrayList<>();
    private final List<MapLocation> markerLocations = new ArrayList<>();
    private final List<int[]> islandRects = new ArrayList<>();
    private final List<IslandMap> islandTargets = new ArrayList<>();

    /** The place under the cursor this frame, for the tooltip and the click. */
    private MapLocation hovered;

    public SkyBlockMapScreen(Screen parent) {
        super(Component.literal("SkyBlock Map"));
        this.parent = parent;
    }

    private static SBSConfig.MapSettings cfg() {
        return ConfigManager.getInstance().get().map;
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 460, 820);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 300, 560);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;

        int contentTop = dividerY + SBSTheme.GAP_AFTER_HEADER;
        canvasX = panelX + SBSTheme.PANEL_PADDING + SIDEBAR_W + 6;
        canvasY = contentTop + SEARCH_H + 5;
        canvasW = panelX + panelW - SBSTheme.PANEL_PADDING - canvasX;
        canvasH = panelY + panelH - SBSTheme.PANEL_PADDING - 14 - canvasY;

        if (selected == null) {
            selected = MapDatabase.current();
            if (selected == null && !MapDatabase.maps().isEmpty()) {
                selected = MapDatabase.maps().getFirst();
            }
        }
        addRenderableOnly(new PanelRenderable());
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();

        searchFocused = hit(mx, my, canvasX, canvasY - SEARCH_H - 5, canvasW, SEARCH_H);

        for (int i = 0; i < islandRects.size(); i++) {
            int[] r = islandRects.get(i);
            if (hit(mx, my, r[0], r[1], r[2], r[3])) {
                select(islandTargets.get(i));
                return true;
            }
        }
        if (hovered != null && selected != null && inCanvas(mx, my)) {
            MapLocation target = hovered;
            IslandMap map = selected;
            // Close first: the warp puts a loading screen up behind this window anyway.
            Minecraft.getInstance().setScreenAndShow(parent);
            MapNavigation.getInstance().travelTo(map, target);
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (inCanvas(event.x(), event.y())) {
            panX += dragX;
            panY += dragY;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (inCanvas(mouseX, mouseY)) {
            SBSConfig.MapSettings cfg = cfg();
            int before = cfg.zoom;
            cfg.zoom = clamp((int) Math.round(cfg.zoom * (scrollY > 0 ? 1.15 : 1 / 1.15)),
                    MIN_ZOOM, MAX_ZOOM);
            if (cfg.zoom != before) {
                // Zoom about the cursor rather than the canvas centre, so the place you are looking
                // at stays under the pointer instead of sliding away as you zoom in.
                double factor = cfg.zoom / (double) before;
                double cx = canvasX + canvasW / 2.0;
                double cy = canvasY + canvasH / 2.0;
                panX = (panX + cx - mouseX) * factor + mouseX - cx;
                panY = (panY + cy - mouseY) * factor + mouseY - cy;
                ConfigManager.getInstance().save();
            }
            return true;
        }
        if (hit(mouseX, mouseY, panelX + SBSTheme.PANEL_PADDING, canvasY - SEARCH_H - 5,
                SIDEBAR_W, canvasH + SEARCH_H + 5)) {
            int rows = MapDatabase.maps().size();
            int visible = Math.max(1, (canvasH + SEARCH_H) / ROW_H);
            islandScroll = clamp(islandScroll - (int) Math.signum(scrollY), 0,
                    Math.max(0, rows - visible));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (searchFocused) {
            if (key == KEY_ESCAPE) {
                if (search.isEmpty()) {
                    searchFocused = false;
                } else {
                    search = "";
                }
                return true;
            }
            if (key == KEY_BACKSPACE) {
                if (!search.isEmpty()) {
                    search = search.substring(0, search.length() - 1);
                }
                return true;
            }
            if (event.isPaste()) {
                search += Minecraft.getInstance().keyboardHandler.getClipboard();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (searchFocused && event.isAllowedChatCharacter() && search.length() < 40) {
            search += event.codepointAsString();
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void select(IslandMap map) {
        if (map == selected) {
            return;
        }
        selected = map;
        panX = 0;
        panY = 0;
    }

    // ------------------------------------------------------------------ projection

    /** Pixels per block for the current island and zoom, fitted to the canvas. */
    private double scale() {
        IslandMap.Bounds bounds = mapBounds();
        double fit = Math.min(canvasW / (double) bounds.width(), canvasH / (double) bounds.depth());
        return fit * Math.max(MIN_ZOOM, cfg().zoom) / 100.0;
    }

    /** World X/Z to screen pixels. North (-Z) is up, which is how every SkyBlock map is drawn. */
    private int[] project(double worldX, double worldZ) {
        IslandMap.Bounds bounds = mapBounds();
        double scale = scale();
        double centreX = (bounds.minX + bounds.maxX) / 2.0;
        double centreZ = (bounds.minZ + bounds.maxZ) / 2.0;
        return new int[]{
                (int) Math.round(canvasX + canvasW / 2.0 + (worldX - centreX) * scale + panX),
                (int) Math.round(canvasY + canvasH / 2.0 + (worldZ - centreZ) * scale + panY)};
    }

    // ------------------------------------------------------------------ helpers

    private boolean inCanvas(double mx, double my) {
        return hit(mx, my, canvasX, canvasY, canvasW, canvasH);
    }

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * The places to draw for the selected island: its own catalogue, plus anything a runtime layer
     * knows about right now.
     *
     * <p>Only the Crystal Hollows have such a layer, and they have to: the Hollows are regenerated
     * every few hours, so their file ships an empty {@code locations} list and the places come from
     * {@link HollowsTracker} instead - what you have walked into during this lobby. They arrive as
     * ordinary {@link MapLocation}s, so hovering, the tooltip, the search box and click-to-waypoint
     * all work on them without this screen growing a second drawing path to keep in step.
     */
    private List<MapLocation> places() {
        if (selected == null) {
            return List.of();
        }
        if (!HollowsTracker.ISLAND.equals(selected.island)) {
            return selected.locations;
        }
        List<MapLocation> runtime = HollowsTracker.getInstance().locations(selected);
        if (runtime.isEmpty()) {
            return selected.locations;
        }
        List<MapLocation> all = new ArrayList<>(selected.locations.size() + runtime.size());
        all.addAll(selected.locations);
        all.addAll(runtime);
        return all;
    }

    /**
     * The box to draw in. The Hollows' is whatever this lobby has been seen to cover, because their
     * file cannot carry a meaningful one - see {@link HollowsTracker#bounds()}.
     */
    private IslandMap.Bounds mapBounds() {
        if (selected != null && HollowsTracker.ISLAND.equals(selected.island)
                && SkyBlockLocation.onIsland(HollowsTracker.ISLAND)) {
            return HollowsTracker.getInstance().bounds();
        }
        return selected.bounds();
    }

    /** Whether a place passes the search box (blank query passes everything). */
    private boolean matches(MapLocation location) {
        if (search.isBlank()) {
            return true;
        }
        String needle = search.trim().toLowerCase(Locale.ROOT);
        return location.name.toLowerCase(Locale.ROOT).contains(needle)
                || location.categoryOrDefault().displayName().toLowerCase(Locale.ROOT).contains(needle)
                || (location.area != null && location.area.toLowerCase(Locale.ROOT).contains(needle));
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = SkyBlockMapScreen.this.font;
            markerRects.clear();
            markerLocations.clear();
            islandRects.clear();
            islandTargets.clear();
            hovered = null;

            g.fill(0, 0, SkyBlockMapScreen.this.width, SkyBlockMapScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("SkyBlock Map"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + SBSTheme.PANEL_PADDING, dividerY, panelX + panelW - SBSTheme.PANEL_PADDING,
                    dividerY + 1, SBSTheme.ACCENT);

            if (MapDatabase.maps().isEmpty()) {
                g.centeredText(font, Component.literal("§cNo island maps are bundled with this build"),
                        panelX + panelW / 2, panelY + panelH / 2, SBSTheme.TEXT);
                return;
            }

            drawSidebar(g, font, mouseX, mouseY);
            drawSearch(g, font);
            if (selected != null) {
                drawCanvas(g, font, mouseX, mouseY);
            }
            drawFooter(g, font);
            if (hovered != null) {
                drawTooltip(g, font, mouseX, mouseY);
            }
        }

        private void drawSidebar(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            int x = panelX + SBSTheme.PANEL_PADDING;
            int y = canvasY - SEARCH_H - 5;
            int bottom = canvasY + canvasH;
            List<IslandMap> maps = MapDatabase.maps();

            g.enableScissor(x, y, x + SIDEBAR_W, bottom);
            int rowY = y - islandScroll * ROW_H;
            for (IslandMap map : maps) {
                if (rowY + ROW_H >= y && rowY <= bottom) {
                    boolean active = map == selected;
                    boolean here = SkyBlockLocation.onIsland(map.island);
                    boolean hoveredRow = hit(mouseX, mouseY, x, rowY, SIDEBAR_W, ROW_H);
                    if (active || hoveredRow) {
                        SciFiRender.roundedRect(g, x, rowY, SIDEBAR_W, ROW_H, SBSTheme.CORNER_RADIUS,
                                active ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
                    }
                    // A dot marks the island you are standing on - the map you most likely want.
                    String label = (here ? "§a• " : "  ") + map.displayName();
                    g.text(font, Component.literal(trim(font, label, SIDEBAR_W - 6)), x + 3, rowY + 3,
                            active ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
                    islandRects.add(new int[]{x, rowY, SIDEBAR_W, ROW_H});
                    islandTargets.add(map);
                }
                rowY += ROW_H;
            }
            g.disableScissor();
        }

        private void drawSearch(GuiGraphicsExtractor g, Font font) {
            int x = canvasX;
            int y = canvasY - SEARCH_H - 5;
            SciFiRender.roundedRectWithBorder(g, x, y, canvasW, SEARCH_H, SBSTheme.CORNER_RADIUS,
                    SBSTheme.CARD_BG, searchFocused ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
            String shown = search.isEmpty() && !searchFocused
                    ? "§8Search this island…"
                    : search + (searchFocused ? "§7_" : "");
            g.text(font, Component.literal(shown), x + 5, y + (SEARCH_H - font.lineHeight) / 2 + 1,
                    SBSTheme.TEXT);
        }

        private void drawCanvas(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            SciFiRender.roundedRectWithBorder(g, canvasX, canvasY, canvasW, canvasH,
                    SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
            g.enableScissor(canvasX + 1, canvasY + 1, canvasX + canvasW - 1, canvasY + canvasH - 1);

            drawGrid(g);
            if (cfg().showWarps) {
                drawWarps(g, font);
            }
            drawPlaces(g, font, mouseX, mouseY);
            drawPlayer(g, font);

            if (places().isEmpty()) {
                drawEmptyNote(g, font);
            }
            g.disableScissor();
        }

        /**
         * What an island with no catalogued places says instead of showing a blank grid.
         *
         * <p>Its warps still work from the tooltip and the travel command still runs - the map just
         * has nothing to point at yet, and saying so (with where to add them) is better than looking
         * broken.
         */
        private void drawEmptyNote(GuiGraphicsExtractor g, Font font) {
            String headline = "§7No places catalogued for " + selected.displayName() + " yet";
            g.centeredText(font, Component.literal(headline), canvasX + canvasW / 2,
                    canvasY + canvasH / 2 - font.lineHeight, SBSTheme.TEXT_MUTED);
            String note = selected.note == null || selected.note.isBlank()
                    ? "Its warps still work - see the island list"
                    : selected.note;
            int maxWidth = canvasW - 24;
            int y = canvasY + canvasH / 2 + 2;
            for (String line : wrap(font, note, maxWidth)) {
                g.centeredText(font, Component.literal("§8" + line), canvasX + canvasW / 2, y,
                        SBSTheme.TEXT_MUTED);
                y += font.lineHeight + 1;
            }
        }

        /** Greedy word wrap; never splits a word. */
        private List<String> wrap(Font font, String text, int maxWidth) {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && font.width(candidate) > maxWidth) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            return lines;
        }

        /** A faint 50-block grid, so distances on the map read as distances. */
        private void drawGrid(GuiGraphicsExtractor g) {
            IslandMap.Bounds bounds = selected.bounds();
            int step = 50;
            double scale = scale();
            if (scale * step < 12) {
                step = 200;   // zoomed out far enough that a 50-block grid would be a solid block
            }
            int startX = Math.floorDiv(bounds.minX, step) * step;
            for (int worldX = startX; worldX <= bounds.maxX; worldX += step) {
                int sx = project(worldX, 0)[0];
                if (sx > canvasX && sx < canvasX + canvasW) {
                    g.fill(sx, canvasY + 1, sx + 1, canvasY + canvasH - 1, SBSTheme.CARD_BORDER);
                }
            }
            int startZ = Math.floorDiv(bounds.minZ, step) * step;
            for (int worldZ = startZ; worldZ <= bounds.maxZ; worldZ += step) {
                int sy = project(0, worldZ)[1];
                if (sy > canvasY && sy < canvasY + canvasH) {
                    g.fill(canvasX + 1, sy, canvasX + canvasW - 1, sy + 1, SBSTheme.CARD_BORDER);
                }
            }
        }

        /** Warp arrival points, drawn as hollow diamonds so they read differently from places. */
        private void drawWarps(GuiGraphicsExtractor g, Font font) {
            for (MapWarp warp : selected.warps) {
                if (!warp.hasPosition()) {
                    continue;
                }
                int[] p = project(warp.x, warp.z);
                boolean locked = !WarpAvailability.usable(warp.command);
                int rgb = locked ? 0x8A6060 : MapCategory.WARP.rgb();
                int size = MARKER - 2;
                SciFiRender.roundedRect(g, p[0] - size / 2, p[1] - size / 2, size, size,
                        size / 2, 0x60000000 | rgb);
                SciFiRender.roundedRect(g, p[0] - size / 2 + 1, p[1] - size / 2 + 1, size - 2, size - 2,
                        (size - 2) / 2, 0xFF000000 | (locked ? 0x2A1E1E : 0x10202A));
                if (scale() > 0.25) {
                    g.text(font, Component.literal((locked ? "§8" : "§b") + warp.displayLabel()),
                            p[0] + size, p[1] - font.lineHeight / 2, SBSTheme.TEXT_MUTED);
                }
            }
        }

        private void drawPlaces(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            double bestDistance = Double.MAX_VALUE;
            for (MapLocation location : places()) {
                if (!matches(location)) {
                    continue;
                }
                int[] p = project(location.x, location.z);
                if (p[0] < canvasX - MARKER || p[0] > canvasX + canvasW + MARKER
                        || p[1] < canvasY - MARKER || p[1] > canvasY + canvasH + MARKER) {
                    continue;   // off-canvas: not drawn, and not clickable
                }
                MapCategory category = location.categoryOrDefault();
                int rgb = category.rgb();

                SciFiRender.roundedRect(g, p[0] - MARKER / 2 - 1, p[1] - MARKER / 2 - 1,
                        MARKER + 2, MARKER + 2, (MARKER + 2) / 2, 0x50000000 | rgb);
                SciFiRender.roundedRect(g, p[0] - MARKER / 2, p[1] - MARKER / 2, MARKER, MARKER,
                        MARKER / 2, 0xFF000000 | rgb);
                g.centeredText(font, Component.literal(category.glyph()), p[0] + 1,
                        p[1] - font.lineHeight / 2 + 1, 0xFF101418);

                markerRects.add(new int[]{p[0] - MARKER / 2, p[1] - MARKER / 2, MARKER, MARKER});
                markerLocations.add(location);

                // Nearest marker to the cursor wins, so overlapping places in a dense town are all
                // reachable instead of whichever happened to be drawn last.
                double dx = mouseX - p[0];
                double dy = mouseY - p[1];
                double distance = dx * dx + dy * dy;
                double reach = (MARKER / 2.0 + HOVER_SLOP) * (MARKER / 2.0 + HOVER_SLOP);
                if (distance <= reach && distance < bestDistance && inCanvas(mouseX, mouseY)) {
                    bestDistance = distance;
                    hovered = location;
                }

                boolean labelled = scale() > 0.55 || !search.isBlank();
                if (labelled && location != hovered) {
                    g.centeredText(font, Component.literal("§7" + location.name), p[0],
                            p[1] + MARKER / 2 + 2, SBSTheme.TEXT_MUTED);
                }
            }
        }

        /** Where the player is, when they are on the island this map shows. */
        private void drawPlayer(GuiGraphicsExtractor g, Font font) {
            Player player = Minecraft.getInstance().player;
            if (player == null || selected == null || !SkyBlockLocation.onIsland(selected.island)) {
                return;
            }
            int[] p = project(player.getX(), player.getZ());
            SciFiRender.roundedRect(g, p[0] - 4, p[1] - 4, 8, 8, 4, 0x7057D977);
            SciFiRender.roundedRect(g, p[0] - 2, p[1] - 2, 4, 4, 2, 0xFF57D977);
            g.centeredText(font, Component.literal("§aYou"), p[0], p[1] - font.lineHeight - 3,
                    0xFF57D977);
        }

        private void drawFooter(GuiGraphicsExtractor g, Font font) {
            int y = panelY + panelH - SBSTheme.PANEL_PADDING - font.lineHeight + 2;
            MapNavigation navigation = MapNavigation.getInstance();
            String left = navigation.active()
                    ? navigation.statusLine() + " §8(click a place to change target)"
                    : "§8Click a place to travel there  ·  drag to pan  ·  scroll to zoom";
            g.text(font, Component.literal(left), panelX + SBSTheme.PANEL_PADDING, y, SBSTheme.TEXT_MUTED);

            String right = "§8" + cfg().zoom + "%";
            g.text(font, Component.literal(right),
                    panelX + panelW - SBSTheme.PANEL_PADDING - font.width(right), y, SBSTheme.TEXT_MUTED);
        }

        /**
         * The hover card: what the place is, where it is, and - the part that matters - how you would
         * get there, including whether the warp is one this profile has been refused before.
         */
        private void drawTooltip(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            List<String> lines = new ArrayList<>();
            lines.add("§f" + hovered.name);
            lines.add("§8" + hovered.categoryOrDefault().displayName()
                    + (hovered.area == null || hovered.area.isBlank() ? "" : "  ·  " + hovered.area));
            if (hovered.note != null && !hovered.note.isBlank()) {
                lines.add("§7" + hovered.note);
            }
            lines.add("§8(" + hovered.x + ", " + hovered.y + ", " + hovered.z + ")");

            Player player = Minecraft.getInstance().player;
            boolean here = SkyBlockLocation.onIsland(selected.island);
            if (player != null && here) {
                double dx = player.getX() - hovered.x;
                double dz = player.getZ() - hovered.z;
                lines.add("§7" + (int) Math.round(Math.sqrt(dx * dx + dz * dz)) + "m away");
            } else {
                lines.add("§7On " + selected.displayName());
            }

            List<MapWarp> candidates = selected.warpsNearest(hovered);
            MapWarp warp = candidates.isEmpty() ? null : candidates.getFirst();
            if (!here && selected.travel != null && !selected.travel.isBlank()) {
                lines.add("§bTravel: §f" + selected.travel
                        + (warp == null ? "" : " §8then §f" + warp.command));
            } else if (warp != null) {
                boolean locked = !WarpAvailability.usable(warp.command);
                lines.add((locked ? "§cLocked: §f" : "§bNearest warp: §f") + warp.command);
            }
            lines.add(cfg().autoWarp ? "§8Click to warp + mark" : "§8Click to mark (auto-warp off)");

            int w = 0;
            for (String line : lines) {
                w = Math.max(w, font.width(line));
            }
            int h = lines.size() * (font.lineHeight + 1) + 7;
            w += 10;
            // Keep the card on screen: flip it to the other side of the cursor near the edges.
            int x = mouseX + 12 + w > panelX + panelW ? mouseX - 12 - w : mouseX + 12;
            int y = Math.min(mouseY, panelY + panelH - h - 2);

            SciFiRender.roundedRect(g, x, y, w, h, SBSTheme.CORNER_RADIUS, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, x + 1, y + 1, w - 2, h - 2, SBSTheme.CORNER_RADIUS - 1,
                    SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int ty = y + 4;
            for (String line : lines) {
                g.text(font, Component.literal(line), x + 5, ty, SBSTheme.TEXT);
                ty += font.lineHeight + 1;
            }
        }

        private String trim(Font font, String text, int maxWidth) {
            if (font.width(text) <= maxWidth) {
                return text;
            }
            return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
        }
    }
}
