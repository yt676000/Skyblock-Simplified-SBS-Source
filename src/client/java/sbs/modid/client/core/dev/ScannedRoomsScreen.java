/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.dungeons.rooms.DungeonRoom;
import sbs.modid.client.dungeons.rooms.DungeonRoomDatabase;
import sbs.modid.client.dungeons.run.logic.DungeonRoomLocator;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Developer screen listing every scanned dungeon room from <b>both</b> sources side by side:
 * the dev export ({@code config/sbs/Development_Stuff/Waypoints.json}, written by the room scanner)
 * and the in-game database ({@code assets/.../dungeons/rooms.json}, bundled in the jar and actually
 * used for matching). A room present in only one of them is exactly what the merge workflow needs to
 * see – a fresh scan not imported yet, or a bundled room whose scan was never kept.
 *
 * <p>The detail pane's centrepiece is the <b>per-cell signature grid</b>: the matcher scores one 32x32
 * cell at a time and needs {@value #WEAK_CELL} stored blocks in a cell to identify the room from it
 * (see {@code DungeonRoomMatcher}), so a cell below that is the reason a room does not light up the
 * moment it is entered. The grid shows each cell's block count, which makes "this room needs a
 * re-scan" a thing you can see instead of guess.
 *
 * <p>Immediate-mode buttons exactly like {@code SecretRoutesScreen}: rects + actions are rebuilt every
 * frame and hit-tested on click.
 */
public final class ScannedRoomsScreen extends Screen {

    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_NUMPAD_ENTER = 335;
    private static final int KEY_BACKSPACE = 259;

    /** Blocks a single cell must store before the matcher will identify the room from that cell alone. */
    private static final int WEAK_CELL = 5;

    /** Largest room on the Hypixel grid – caps the cell grid so a corrupt size cannot draw forever. */
    private static final int MAX_CELLS = 4;

    /** One room name and whichever of the two stored versions exist for it. */
    private record Entry(String name, DungeonRoom config, DungeonRoom game) {

        /** The version shown in the detail pane: the bundled one is live, the export is the draft. */
        DungeonRoom shown() {
            return game != null ? game : config;
        }

        String sources() {
            if (config != null && game != null) {
                return "§aconfig + in-game";
            }
            return config != null ? "§econfig only (not imported)" : "§bin-game only";
        }
    }

    private enum Filter {
        ALL("All"),
        CONFIG("Config"),
        GAME("In-game"),
        WEAK("Weak");

        private final String label;

        Filter(String label) {
            this.label = label;
        }
    }

    private final Screen parent;

    private final List<Entry> entries = new ArrayList<>();
    private Filter filter = Filter.ALL;
    private String search = "";
    private boolean searchFocused;
    private String selected;
    private int scroll;
    private String status = "";

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

    private final List<int[]> buttonRects = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();
    private final int[] searchRect = new int[4];

    public ScannedRoomsScreen(Screen parent) {
        super(Component.literal("Scanned Rooms"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 480, 720);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 300, 480);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = SBSTheme.PANEL_PADDING;
        listX = panelX + pad;
        listW = (panelW - pad * 3) * 2 / 5;
        detailX = listX + listW + pad;
        detailW = panelX + panelW - pad - detailX;
        listTop = panelY + SBSTheme.HEADER_HEIGHT + 8 + (SBSTheme.SEARCH_HEIGHT + 4) * 2;
        listBottom = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT - 6;
        addRenderableOnly(new PanelRenderable());
        reload();
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    /** Re-reads both sources from scratch (the export file may have changed since the screen opened). */
    private void reload() {
        Map<String, DungeonRoom> config = WaypointExporter.loadAllRooms();
        Map<String, DungeonRoom> game = DungeonRoomDatabase.rooms();
        // Case-insensitive ordering, but keys stay distinct: rooms.json really does carry both
        // "Crypts" and "crypts", and collapsing them here would hide one of the two.
        Map<String, Entry> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, DungeonRoom> e : config.entrySet()) {
            merged.put(e.getKey(), new Entry(e.getKey(), e.getValue(), game.get(e.getKey())));
        }
        for (Map.Entry<String, DungeonRoom> e : game.entrySet()) {
            if (!merged.containsKey(e.getKey())) {
                merged.put(e.getKey(), new Entry(e.getKey(), config.get(e.getKey()), e.getValue()));
            }
        }
        entries.clear();
        entries.addAll(merged.values());
        status = config.size() + " in config · " + game.size() + " in-game";
    }

    /** The entries the current filter + search let through. */
    private List<Entry> visible() {
        String needle = search.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (!needle.isEmpty() && !entry.name().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            boolean keep = switch (filter) {
                case CONFIG -> entry.config() != null;
                case GAME -> entry.game() != null;
                case WEAK -> weakCells(entry.shown()) > 0;
                case ALL -> true;
            };
            if (keep) {
                out.add(entry);
            }
        }
        return out;
    }

    private Entry selectedEntry() {
        for (Entry entry : entries) {
            if (entry.name().equals(selected)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * The room's signature block count per 32x32 cell, keyed exactly like the matcher scores them
     * ({@link DungeonRoomLocator#relativeCellKey}).
     */
    private static Map<Long, Integer> cellCounts(DungeonRoom room) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        if (room == null || room.blocks == null) {
            return counts;
        }
        for (DungeonRoom.RoomBlock block : room.blocks) {
            long key = DungeonRoomLocator.relativeCellKey(block.relative_x, block.relative_z);
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    /** How many of the room's occupied cells hold too little signature to identify it on their own. */
    private static int weakCells(DungeonRoom room) {
        int weak = 0;
        for (int count : cellCounts(room).values()) {
            if (count < WEAK_CELL) {
                weak++;
            }
        }
        return weak;
    }

    /**
     * The cell grid to draw: the block extent, widened to the stored {@code room_size} in whichever
     * orientation contains that extent. Old exports do not pin the canonical WxH order, so the size
     * alone cannot say which axis is which – but it is the only thing that knows about a cell holding
     * <b>no</b> blocks at all, which is exactly the cell worth showing.
     */
    private static int[] gridSize(DungeonRoom room, Map<Long, Integer> counts) {
        int extentX = 1;
        int extentZ = 1;
        for (long key : counts.keySet()) {
            extentX = Math.max(extentX, DungeonRoomLocator.cellKeyX(key) + 1);
            extentZ = Math.max(extentZ, DungeonRoomLocator.cellKeyZ(key) + 1);
        }
        int[] size = parseSize(room == null ? null : room.room_size);
        if (size != null) {
            if (size[0] >= extentX && size[1] >= extentZ) {
                extentX = size[0];
                extentZ = size[1];
            } else if (size[1] >= extentX && size[0] >= extentZ) {
                extentX = size[1];
                extentZ = size[0];
            }
        }
        return new int[] {Math.min(MAX_CELLS, extentX), Math.min(MAX_CELLS, extentZ)};
    }

    /** {@code "4x1"} to {@code {4,1}}; {@code null} for L classes and anything unparseable. */
    private static int[] parseSize(String size) {
        if (size == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern.compile("([0-9]+)x([0-9]+)").matcher(size);
        return matcher.find()
                ? new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))}
                : null;
    }

    /** Waypoints by type, e.g. {@code "4 chest, 2 lever"}. */
    private static String waypointSummary(DungeonRoom room) {
        if (room == null || room.waypoints == null || room.waypoints.isEmpty()) {
            return "§8none";
        }
        Map<String, Integer> byType = new TreeMap<>();
        for (DungeonRoom.RoomWaypoint wp : room.waypoints.values()) {
            byType.merge(wp.type == null ? "?" : wp.type, 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : byType.entrySet()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(e.getValue()).append(' ').append(e.getKey());
        }
        return out.toString();
    }

    /** The room rendered in the hand-format {@code rooms.json} uses, ready to paste into the file. */
    private static String toJson(String name, DungeonRoom room) {
        StringBuilder out = new StringBuilder();
        out.append("  \"").append(name).append("\": {\n");
        // Defaults for absent fields, so a copied entry is always valid to paste back into rooms.json
        // ("unknown" is what old exports wrote for an unreadable map, NORTH is the canonical facing).
        out.append("    \"map_color\": \"").append(or(room.map_color, "unknown")).append("\",\n");
        out.append("    \"room_size\": \"").append(or(room.room_size, "")).append("\",\n");
        out.append("    \"scanned_blocks_count\": ")
                .append(room.blocks == null ? 0 : room.blocks.size()).append(",\n");
        out.append("    \"door_facing_direction\": \"")
                .append(or(room.door_facing_direction, "NORTH")).append("\",\n");
        out.append("    \"blocks\": ");
        if (room.blocks == null || room.blocks.isEmpty()) {
            out.append("[],\n");
        } else {
            out.append("[\n");
            for (int i = 0; i < room.blocks.size(); i++) {
                DungeonRoom.RoomBlock b = room.blocks.get(i);
                out.append("      { \"id\": \"").append(b.id).append("\", \"relative_x\": ").append(b.relative_x)
                        .append(", \"relative_y\": ").append(b.relative_y)
                        .append(", \"relative_z\": ").append(b.relative_z).append(" }")
                        .append(i == room.blocks.size() - 1 ? "\n" : ",\n");
            }
            out.append("    ],\n");
        }
        out.append("    \"waypoints\": ");
        if (room.waypoints == null || room.waypoints.isEmpty()) {
            out.append("{}\n");
        } else {
            out.append("{\n");
            int i = 0;
            for (Map.Entry<String, DungeonRoom.RoomWaypoint> e : room.waypoints.entrySet()) {
                DungeonRoom.RoomWaypoint wp = e.getValue();
                out.append("      \"").append(e.getKey()).append("\": { \"type\": \"").append(wp.type)
                        .append("\", \"relative_x\": ").append(wp.relative_x)
                        .append(", \"relative_y\": ").append(wp.relative_y)
                        .append(", \"relative_z\": ").append(wp.relative_z).append(" }")
                        .append(++i == room.waypoints.size() ? "\n" : ",\n");
            }
            out.append("    }\n");
        }
        out.append("  }");
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();
        searchFocused = mx >= searchRect[0] && mx < searchRect[0] + searchRect[2]
                && my >= searchRect[1] && my < searchRect[1] + searchRect[3];
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
            scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (searchFocused) {
            if (key == KEY_ESCAPE || key == KEY_ENTER || key == KEY_NUMPAD_ENTER) {
                searchFocused = false;
            } else if (key == KEY_BACKSPACE && !search.isEmpty()) {
                search = search.substring(0, search.length() - 1);
                scroll = 0;
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
        if (searchFocused && event.isAllowedChatCharacter() && search.length() < 32) {
            search += event.codepointAsString();
            scroll = 0;
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public void onClose() {
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
            Font font = ScannedRoomsScreen.this.font;
            buttonRects.clear();
            buttonActions.clear();

            g.fill(0, 0, ScannedRoomsScreen.this.width, ScannedRoomsScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int pad = SBSTheme.PANEL_PADDING;
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Scanned Rooms  §7" + entries.size() + " total"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + pad, panelY + SBSTheme.HEADER_HEIGHT, panelX + panelW - pad,
                    panelY + SBSTheme.HEADER_HEIGHT + 1, SBSTheme.ACCENT);

            drawFilters(g, font, mouseX, mouseY);
            drawSearch(g, font);
            drawList(g, font, mouseX, mouseY);
            drawDetail(g, font, mouseX, mouseY);

            int by = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
            g.text(font, Component.literal("§8" + status), listX, by + 5, SBSTheme.TEXT_MUTED);
        }

        private void drawFilters(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            int y = panelY + SBSTheme.HEADER_HEIGHT + 8;
            int h = SBSTheme.SEARCH_HEIGHT;
            Filter[] all = Filter.values();
            int w = (listW - (all.length - 1) * 3) / all.length;
            for (int i = 0; i < all.length; i++) {
                Filter option = all[i];
                boolean active = filter == option;
                int x = listX + i * (w + 3);
                boolean hover = hit(mouseX, mouseY, x, y, w, h);
                SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                        active || hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                        active ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
                g.centeredText(font, Component.literal((active ? "§f" : "§7") + option.label),
                        x + w / 2, y + (h - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
                buttonRects.add(new int[] {x, y, w, h});
                buttonActions.add(() -> {
                    filter = option;
                    scroll = 0;
                });
            }
        }

        private void drawSearch(GuiGraphicsExtractor g, Font font) {
            int y = panelY + SBSTheme.HEADER_HEIGHT + 8 + SBSTheme.SEARCH_HEIGHT + 4;
            int h = SBSTheme.SEARCH_HEIGHT;
            searchRect[0] = listX;
            searchRect[1] = y;
            searchRect[2] = listW;
            searchRect[3] = h;
            SciFiRender.roundedRectWithBorder(g, listX, y, listW, h, SBSTheme.CORNER_RADIUS,
                    SBSTheme.SEARCH_FILL, searchFocused ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            String shown = search.isEmpty() && !searchFocused ? "§8search room name..." : search;
            g.text(font, Component.literal(shown), listX + 4, y + (h - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
            if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
                int caretX = listX + 4 + font.width(search);
                g.fill(caretX, y + 2, caretX + 1, y + h - 2, SBSTheme.ACCENT_BRIGHT);
            }
        }

        private void drawList(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            List<Entry> shown = visible();
            int rowH = 22;
            int visibleRows = Math.max(1, (listBottom - listTop) / rowH);
            scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - visibleRows)));
            if (shown.isEmpty()) {
                g.text(font, Component.literal("§8no rooms match"), listX, listTop, SBSTheme.TEXT_MUTED);
                return;
            }
            String active = DungeonRoomTracker.getInstance().activeRoomName();
            int y = listTop;
            for (int i = scroll; i < shown.size() && i < scroll + visibleRows; i++) {
                Entry entry = shown.get(i);
                boolean isSelected = entry.name().equals(selected);
                boolean hover = hit(mouseX, mouseY, listX, y, listW, rowH - 2);
                boolean here = entry.name().equals(active);
                SciFiRender.roundedRectWithBorder(g, listX, y, listW, rowH - 2, SBSTheme.CORNER_RADIUS,
                        hover || isSelected ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                        isSelected ? SBSTheme.ACCENT : here ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
                // Source badges: C = in the dev export, G = in the bundled database.
                String badge = (entry.config() != null ? "§eC" : "§8·") + (entry.game() != null ? "§bG" : "§8·");
                String name = (here ? "§a> " : isSelected ? "§f" : "§7") + entry.name();
                g.text(font, Component.literal(fit(font, name, listW - 26)), listX + 6, y + 3, SBSTheme.TEXT);
                g.text(font, Component.literal(badge), listX + listW - 18, y + 3, SBSTheme.TEXT);

                DungeonRoom room = entry.shown();
                int blocks = room.blocks == null ? 0 : room.blocks.size();
                int weak = weakCells(room);
                String sub = "§8" + (room.room_size == null ? "?" : room.room_size) + " · " + blocks + " blk · "
                        + (room.waypoints == null ? 0 : room.waypoints.size()) + " wp"
                        + (weak > 0 ? " §c" + weak + " weak" : "");
                g.text(font, Component.literal(fit(font, sub, listW - 12)), listX + 6, y + 3 + font.lineHeight,
                        SBSTheme.TEXT_MUTED);
                buttonRects.add(new int[] {listX, y, listW, rowH - 2});
                buttonActions.add(() -> selected = entry.name());
                y += rowH;
            }
            if (shown.size() > visibleRows) {
                g.text(font, Component.literal("§8" + (scroll + 1) + "-"
                                + Math.min(shown.size(), scroll + visibleRows) + "/" + shown.size() + " (scroll)"),
                        listX, listBottom + 1, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawDetail(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            int x = detailX;
            int y = listTop - (SBSTheme.SEARCH_HEIGHT + 4) * 2;
            Entry entry = selectedEntry();
            if (entry == null) {
                g.text(font, Component.literal("§8select a room on the left"), x, y, SBSTheme.TEXT_MUTED);
                g.text(font, Component.literal("§8§oC = in Waypoints.json, G = in the bundled rooms.json"),
                        x, y + font.lineHeight + 2, SBSTheme.TEXT_MUTED);
                return;
            }
            DungeonRoom room = entry.shown();
            int line = font.lineHeight + 2;

            g.text(font, Component.literal("§f" + entry.name()), x, y, SBSTheme.TEXT);
            y += line;
            g.text(font, Component.literal(entry.sources()), x, y, SBSTheme.TEXT_MUTED);
            y += line + 2;

            int blocks = room.blocks == null ? 0 : room.blocks.size();
            g.text(font, Component.literal("§7size §f" + (room.room_size == null ? "?" : room.room_size)
                    + "  §7colour §f" + (room.map_color == null ? "?" : room.map_color)
                    + "  §7facing §f" + (room.door_facing_direction == null ? "?" : room.door_facing_direction)),
                    x, y, SBSTheme.TEXT_MUTED);
            y += line;
            g.text(font, Component.literal("§7blocks §f" + blocks + "  §7waypoints §f"
                    + (room.waypoints == null ? 0 : room.waypoints.size())), x, y, SBSTheme.TEXT_MUTED);
            y += line;
            g.text(font, Component.literal(fit(font, "§7secrets §f" + waypointSummary(room), detailW)),
                    x, y, SBSTheme.TEXT_MUTED);
            y += line + 4;

            y = drawCellGrid(g, font, room, x, y);

            // Both sources present: a disagreement means the export and the bundled entry drifted apart.
            if (entry.config() != null && entry.game() != null) {
                String diff = difference(entry);
                g.text(font, Component.literal(fit(font, diff, detailW)), x, y, SBSTheme.TEXT_MUTED);
                y += line;
            }
            y += 4;

            int h = SBSTheme.SEARCH_HEIGHT;
            int half = (detailW - 4) / 2;
            button(g, font, x, y, half, h, "Copy JSON", mouseX, mouseY, () -> {
                String json = toJson(entry.name(), room);
                Minecraft.getInstance().keyboardHandler.setClipboard(json);
                status = "copied " + json.length() + " chars";
            });
            button(g, font, x + half + 4, y, half, h, "Reload", mouseX, mouseY, () -> {
                reload();
                status = "reloaded from disk";
            });
        }

        /** The per-cell signature grid – one box per 32x32 cell with its stored block count. */
        private int drawCellGrid(GuiGraphicsExtractor g, Font font, DungeonRoom room, int x, int y) {
            Map<Long, Integer> counts = cellCounts(room);
            int[] grid = gridSize(room, counts);
            g.text(font, Component.literal("§7blocks per 32x32 cell"), x, y, SBSTheme.TEXT_MUTED);
            y += font.lineHeight + 2;
            int box = 30;
            int boxH = 18;
            for (int cz = 0; cz < grid[1]; cz++) {
                for (int cx = 0; cx < grid[0]; cx++) {
                    int count = counts.getOrDefault(DungeonRoomLocator.relativeCellKey(cx * 32, cz * 32), 0);
                    int bx = x + cx * (box + 2);
                    int by = y + cz * (boxH + 2);
                    SciFiRender.roundedRectWithBorder(g, bx, by, box, boxH, SBSTheme.CORNER_RADIUS,
                            SBSTheme.CARD_BG, count >= WEAK_CELL ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
                    String text = count == 0 ? "§8–" : (count < WEAK_CELL ? "§c" : "§a") + count;
                    g.centeredText(font, Component.literal(text), bx + box / 2,
                            by + (boxH - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
                }
            }
            y += grid[1] * (boxH + 2) + 2;
            int weak = weakCells(room);
            int empty = grid[0] * grid[1] - counts.size();
            String verdict;
            if (weak == 0 && empty == 0) {
                verdict = "§aevery cell identifies this room on its own";
            } else {
                verdict = "§c" + (weak + empty) + " cell(s) under " + WEAK_CELL
                        + " §7- re-scan for instant detection there";
            }
            g.text(font, Component.literal(fit(font, verdict, detailW)), x, y, SBSTheme.TEXT_MUTED);
            y += font.lineHeight + 2;
            if (parseSize(room.room_size) == null) {
                g.text(font, Component.literal("§8L-shape: one grid cell is not part of the room"),
                        x, y, SBSTheme.TEXT_MUTED);
                y += font.lineHeight + 2;
            }
            return y + 2;
        }

        private String difference(Entry entry) {
            DungeonRoom config = entry.config();
            DungeonRoom game = entry.game();
            int configBlocks = config.blocks == null ? 0 : config.blocks.size();
            int gameBlocks = game.blocks == null ? 0 : game.blocks.size();
            boolean sameSize = String.valueOf(config.room_size).equals(String.valueOf(game.room_size));
            if (sameSize && configBlocks == gameBlocks) {
                return "§8config and in-game agree";
            }
            return "§econfig " + config.room_size + "/" + configBlocks + " blk vs in-game "
                    + game.room_size + "/" + gameBlocks + " blk";
        }

        private void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String label,
                            int mouseX, int mouseY, Runnable action) {
            boolean hover = hit(mouseX, mouseY, x, y, w, h);
            SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                    hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            g.centeredText(font, Component.literal(label), x + w / 2, y + (h - font.lineHeight) / 2 + 1,
                    SBSTheme.TEXT);
            buttonRects.add(new int[] {x, y, w, h});
            buttonActions.add(action);
        }

        private boolean hit(int mouseX, int mouseY, int x, int y, int w, int h) {
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        }
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
    }
}
