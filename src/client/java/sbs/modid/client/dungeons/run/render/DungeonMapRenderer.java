/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Matrix3x2fStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.dev.RoomMapReader;
import sbs.modid.client.core.dev.RoomMapReader.MapSnapshot;
import sbs.modid.client.core.dev.RoomMapReader.MapTile;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.run.logic.DungeonRunRegistry;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.dungeons.run.model.DungeonState;
import sbs.modid.client.dungeons.run.model.DungeonTeamClasses;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * The SBS Dungeon Map HUD: the real dungeon map re-drawn in the SBS style – room tiles in theme colours,
 * multi-slot rooms merged across the full shared edge, thin door connectors, a "?" on unexplored tiles –
 * plus, for every room the player has visited and the database identified, the room <b>name</b> and its
 * live <b>secrets counter</b> ("found/total", "found/?" while the total is unknown). The counter colour
 * follows the map checkmark: white = star mobs cleared, green = all secrets, red = failed.
 *
 * <p>Everything drawn here comes from cached state: {@link DungeonState} refreshes the map snapshot on
 * block events only, and names/counters come from {@link DungeonRunRegistry} which the room tracker
 * fills in the client tick – the per-frame cost is pure drawing.
 */
public final class DungeonMapRenderer {

    // Layout. PANEL_SIZE must match HudElement.DUNGEON_MAP's default bounds.
    private static final int GRID_MAX = 6;
    private static final int CELL = 20;
    private static final int LANE = 5;
    private static final int PAD = 6;
    private static final int CONTENT = GRID_MAX * CELL + (GRID_MAX - 1) * LANE; // 145
    public static final int PANEL_SIZE = CONTENT + PAD * 2;                     // 157

    private static final int CELL_RADIUS = 2;
    private static final float LABEL_SCALE = 0.55f;
    private static final int LABEL_LINE_HEIGHT = 10;

    // SBS-styled room palette (keyed by the Hypixel map colour byte).
    private static final int TILE_NORMAL = 0xFF9A6A38;     // brown
    private static final int TILE_ENTRANCE = 0xFF57D977;   // green
    private static final int TILE_BLOOD = 0xFFE0605F;      // red
    private static final int TILE_PUZZLE = 0xFFB86CE8;     // purple
    private static final int TILE_TRAP = 0xFFE0A14D;       // orange
    private static final int TILE_MINIBOSS = 0xFFE8D060;   // yellow
    private static final int TILE_FAIRY = 0xFFEE7BD8;      // pink
    private static final int TILE_UNEXPLORED = 0xFF39465A; // gray
    private static final int DOOR_COLOR = 0xC06E7B8C;
    /** Wither doors are black on the real map – kept near-black here (visible on the dark panel). */
    private static final int DOOR_WITHER = 0xFF15151C;
    /** The blood door is red on the real map. */
    private static final int DOOR_BLOOD = TILE_BLOOD;

    private DungeonMapRenderer() {
    }

    /**
     * Called every HUD frame; updates the (block-gated) state and draws the map when appropriate.
     *
     * <p><b>The state update runs before every one of the checks below, and must stay there.</b>
     * {@link DungeonState#updateIfMoved()} is the only producer of the map snapshot, and the room
     * locator, the run's cleared/total room counts (the Explore score) and the wither-door boxes all
     * read it. Skipping it because the map is not being drawn would quietly break three features
     * that have nothing to do with this HUD element.
     */
    public static void render(GuiGraphicsExtractor g) {
        DungeonState state = DungeonState.getInstance();
        state.updateIfMoved();

        SBSConfig.DungeonsSettings cfg = ConfigManager.getInstance().get().dungeons;
        if (!cfg.sbsDungeonMap) {
            return;
        }
        if (!state.isInDungeon() || HudLayout.isHidden(HudElement.DUNGEON_MAP)) {
            return;
        }
        // Boss room: the map item is gone, so there is nothing painted to mirror and the panel would
        // be an empty frame. Skipping the draw only - the phase itself is two-signal, see
        // DungeonStateManager#updatePhase.
        if (cfg.hideMapInBoss
                && DungeonStateManager.getInstance().phase() == DungeonEvents.Phase.BOSS) {
            return;
        }

        HudElement.Bounds bounds = HudElement.DUNGEON_MAP.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(bounds.x());
        int y = Math.round(bounds.y());

        HudLayout.begin(g, HudElement.DUNGEON_MAP);
        drawPanel(g, x, y);
        MapSnapshot snapshot = state.snapshot();
        if (snapshot != null) {
            drawMap(g, x + PAD, y + PAD, snapshot);
        }
        HudLayout.end(g);
    }

    private static void drawPanel(GuiGraphicsExtractor g, int x, int y) {
        HudCard.draw(g, x, y, PANEL_SIZE, PANEL_SIZE);
    }

    private static void drawMap(GuiGraphicsExtractor g, int baseX, int baseY, MapSnapshot snapshot) {
        Font font = Minecraft.getInstance().font;

        int cellsX = Math.min(GRID_MAX, snapshot.maxCellX() - snapshot.minCellX() + 1);
        int cellsZ = Math.min(GRID_MAX, snapshot.maxCellZ() - snapshot.minCellZ() + 1);
        int originX = baseX + (CONTENT - span(cellsX)) / 2; // centre the painted area in the panel
        int originY = baseY + (CONTENT - span(cellsZ)) / 2;

        // Pass 1: tiles, same-room bridges (full edge) and door connectors (thin, centred).
        Map<Long, MapTile> byCell = new HashMap<>();
        for (MapTile tile : snapshot.tiles()) {
            byCell.put(tileKey(tile.cellX(), tile.cellZ()), tile);
            int tx = tileX(originX, snapshot, tile.cellX());
            int ty = tileY(originY, snapshot, tile.cellZ());
            if (tx + CELL > baseX + CONTENT + 1 || ty + CELL > baseY + CONTENT + 1) {
                continue; // over 6x6 – never happens on real maps, but never draw outside the panel
            }
            int color = tileColor(tile.colour());
            SciFiRender.roundedRect(g, tx, ty, CELL, CELL, CELL_RADIUS, color);
            if (tile.joinEast()) {
                g.fill(tx + CELL, ty, tx + CELL + LANE, ty + CELL, color);
            }
            if (tile.joinSouth()) {
                g.fill(tx, ty + CELL, tx + CELL, ty + CELL + LANE, color);
            }
            if (tile.doorEast().present()) {
                g.fill(tx + CELL, ty + CELL / 2 - 2, tx + CELL + LANE, ty + CELL / 2 + 2,
                        doorColor(tile.doorEast()));
            }
            if (tile.doorSouth().present()) {
                g.fill(tx + CELL / 2 - 2, ty + CELL, tx + CELL / 2 + 2, ty + CELL + LANE,
                        doorColor(tile.doorSouth()));
            }
            if (tile.unexplored()) {
                g.centeredText(font, "?", tx + CELL / 2, ty + (CELL - font.lineHeight) / 2, SBSTheme.TEXT_MUTED);
            }
        }

        // Pass 2: one label (name + secrets counter) per visited room group. The name comes from the
        // database match the tracker made when the room was entered; rooms whose colour already names
        // them (fairy) are labelled without a match.
        for (RoomGroup group : groupRooms(byCell)) {
            DungeonRunRegistry.RunRoom run = runOf(group);
            String name = run != null ? run.name() : null;
            if (name == null) {
                name = impliedName(group.colour);
                run = null;   // colour-implied name - there is no secret data behind it
            }
            if (name == null) {
                continue;
            }
            int left = tileX(originX, snapshot, group.minCellX);
            int top = tileY(originY, snapshot, group.minCellZ);
            int right = tileX(originX, snapshot, group.maxCellX) + CELL;
            int bottom = tileY(originY, snapshot, group.maxCellZ) + CELL;
            drawLabel(g, font, name, run, group.state, (left + right) / 2, (top + bottom) / 2, right - left);
        }

        // Pass 3: EVERY player marker, read from the map's own decorations (so party members in far,
        // unloaded rooms show too – the world-entity list only had nearby players). Each marker is the
        // player's HEAD, ringed in their DUNGEON CLASS colour (Archer red, Berserk orange, Tank green,
        // Healer purple, Mage blue) resolved by name; YOUR marker adds a white frame so you can always
        // pick yourself out. The head stays upright; a small pointer shows each player's facing.
        List<RoomMapReader.PlayerMarker> markers = RoomMapReader.playerMarkers(snapshot);
        if (!markers.isEmpty()) {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            for (RoomMapReader.PlayerMarker marker : markers) {
                int hx = originX + Math.round((marker.cellX() - snapshot.minCellX()) * (CELL + LANE));
                int hy = originY + Math.round((marker.cellZ() - snapshot.minCellZ()) * (CELL + LANE));
                hx = Math.max(baseX + HEAD / 2, Math.min(baseX + CONTENT - HEAD / 2, hx));
                hy = Math.max(baseY + HEAD / 2, Math.min(baseY + CONTENT - HEAD / 2, hy));
                int ring = DungeonTeamClasses.colorOf(marker.name(),
                        marker.self() ? MARKER_SELF : MARKER_MATE);
                PlayerSkin skin = skinFor(connection, marker.name());
                drawMarker(g, hx, hy, marker.yawDeg(), ring, marker.self(), skin);
            }
        } else {
            int px = originX + Math.round((snapshot.playerCellX() - snapshot.minCellX()) * (CELL + LANE));
            int py = originY + Math.round((snapshot.playerCellZ() - snapshot.minCellZ()) * (CELL + LANE));
            px = Math.max(baseX, Math.min(baseX + CONTENT - 4, px));
            py = Math.max(baseY, Math.min(baseY + CONTENT - 4, py));
            SciFiRender.roundedRect(g, px, py, 4, 4, 1, MARKER_SELF);
        }
    }

    /**
     * A player's skin from the tab list by IGN, tolerating the case differences and roster-derived
     * (lower-cased) names the marker binding produces: the exact lookup first, then a case-insensitive
     * scan of every tab entry. {@code null} when the player is unknown (the marker falls back to a
     * class-coloured square).
     */
    private static PlayerSkin skinFor(ClientPacketListener connection, String name) {
        if (connection == null || name == null || name.isEmpty()) {
            return null;
        }
        PlayerInfo direct = connection.getPlayerInfo(name);
        if (direct != null) {
            return direct.getSkin();
        }
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (info.getProfile() != null && name.equalsIgnoreCase(info.getProfile().name())) {
                return info.getSkin();
            }
        }
        return null;
    }

    /** Pixel size of a player's head on the map. */
    private static final int HEAD = 10;
    /** Fallback ring colours when the class is unknown: local player green, teammates white. */
    private static final int MARKER_SELF = TILE_ENTRANCE;   // green
    private static final int MARKER_MATE = 0xFFFFFFFF;      // white

    /**
     * Draws a player marker: their <b>head</b> (skin face), ringed in their dungeon-class colour, with
     * a small pointer for their facing. The local player ({@code self}) gets an extra white frame so
     * you can always pick yourself out. The head stays upright – only the pointer rotates – because a
     * tilted face is hard to read. Falls back to a class-coloured square while the skin has not loaded.
     */
    private static void drawMarker(GuiGraphicsExtractor g, int cx, int cy, float yawDeg, int ring,
                                   boolean self, PlayerSkin skin) {
        int half = HEAD / 2;
        int left = cx - half;
        int top = cy - half;

        // Facing pointer: a small nose just beyond the ring, rotated to the player's yaw (the map is
        // north-up; the decoration rotation is offset by 180° to match the head convention).
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        pose.rotate((float) Math.toRadians(yawDeg) + (float) Math.PI);
        int reach = half + (self ? 4 : 3);
        g.fill(-2, -reach - 2, 2, -reach, self ? MARKER_MATE : ring);
        pose.popMatrix();

        // White frame (self) → class-colour ring → head, all upright.
        if (self) {
            SciFiRender.roundedRect(g, left - 4, top - 4, HEAD + 8, HEAD + 8, 3, MARKER_MATE);
        }
        SciFiRender.roundedRect(g, left - 2, top - 2, HEAD + 4, HEAD + 4, 2, ring);
        if (skin != null) {
            PlayerFaceExtractor.extractRenderState(g, skin, left, top, HEAD);
        } else {
            SciFiRender.roundedRect(g, left, top, HEAD, HEAD, 1, ring);
        }
    }

    /**
     * The room the registry remembers for a group – tried on <b>every</b> cell of the group, not just
     * its NW one: which segment a room is linked under depends on what the map had revealed when the
     * player entered it, so the NW-only lookup silently lost the name on partially revealed rooms.
     */
    private static DungeonRunRegistry.RunRoom runOf(RoomGroup group) {
        DungeonRunRegistry registry = DungeonRunRegistry.getInstance();
        DungeonRunRegistry.RunRoom named = null;
        for (long cell : group.cells) {
            DungeonRunRegistry.RunRoom run = registry.atMapCell((int) (cell >> 32), (int) cell);
            if (run != null && run.name() != null) {
                return run;   // a named room wins outright
            }
            if (run != null && named == null) {
                named = run;  // visited but not identified yet - keeps the secrets counter alive
            }
        }
        return named;
    }

    /**
     * The name a room's map colour already implies, or {@code null}. Hypixel paints fairy rooms in
     * exactly one pink (map colour byte {@link RoomMapReader#COLOR_FAIRY}, rendered #F27FA5) and uses
     * it for nothing else, so those are labelled without needing a database match at all.
     */
    private static String impliedName(byte colour) {
        return colour == RoomMapReader.COLOR_FAIRY ? "Fairy Room" : null;
    }

    /**
     * Draws the room name plus its "found/total" secrets line, scaled down to fit the room tiles.
     * The counter is drawn only for rooms the run registry knows (a colour-implied name like the
     * fairy room carries no secret data).
     */
    private static void drawLabel(GuiGraphicsExtractor g, Font font, String name,
                                  DungeonRunRegistry.RunRoom run, RoomMapReader.RoomState state,
                                  int centerX, int centerY, int widthPx) {
        List<String> lines = wrap(font, name, (int) (widthPx / LABEL_SCALE));
        String counter = run == null ? null
                : run.secretsFound() + "/" + (run.secretsTotal() >= 0 ? run.secretsTotal() : "?");
        int totalLines = lines.size() + (counter != null ? 1 : 0);
        float textHeight = totalLines * LABEL_LINE_HEIGHT * LABEL_SCALE;

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(centerX, centerY - textHeight / 2f);
        pose.scale(LABEL_SCALE);
        int lineY = 0;
        for (String line : lines) {
            g.centeredText(font, line, 0, lineY, SBSTheme.TEXT);
            lineY += LABEL_LINE_HEIGHT;
        }
        if (counter != null) {
            g.centeredText(font, counter, 0, lineY, counterColor(state));
        }
        pose.popMatrix();
    }

    private static int counterColor(RoomMapReader.RoomState state) {
        return switch (state) {
            case SECRETS_DONE -> SBSTheme.TOGGLE_ON;
            case CLEARED -> SBSTheme.TEXT;
            case FAILED -> SBSTheme.WARN;
            case UNCLEARED -> SBSTheme.TEXT_MUTED;
        };
    }

    /** Greedy word wrap in unscaled font pixels (labels are drawn at {@link #LABEL_SCALE}). */
    private static List<String> wrap(Font font, String name, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : name.split(" ")) {
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

    // ---- room grouping --------------------------------------------------------------------------------

    /** A connected (same-room) group of tiles with its cell bounding box and strongest clear state. */
    private static final class RoomGroup {
        int minCellX = Integer.MAX_VALUE, minCellZ = Integer.MAX_VALUE;
        int maxCellX = Integer.MIN_VALUE, maxCellZ = Integer.MIN_VALUE;
        int nwCellX, nwCellZ;
        RoomMapReader.RoomState state = RoomMapReader.RoomState.UNCLEARED;
        /** Every cell of the group (packed x&lt;&lt;32|z) – the registry is tried on all of them. */
        final List<Long> cells = new ArrayList<>(4);
        /** The group's map colour (all tiles of one room share it) – drives {@link #impliedName}. */
        byte colour;
    }

    /** Groups explored tiles into rooms by following the same-room joins (both directions). */
    private static List<RoomGroup> groupRooms(Map<Long, MapTile> byCell) {
        List<RoomGroup> groups = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        for (MapTile start : byCell.values()) {
            if (start.unexplored() || !visited.add(tileKey(start.cellX(), start.cellZ()))) {
                continue;
            }
            RoomGroup group = new RoomGroup();
            group.colour = start.colour();
            Queue<MapTile> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                MapTile tile = queue.poll();
                group.cells.add(tileKey(tile.cellX(), tile.cellZ()));
                group.minCellX = Math.min(group.minCellX, tile.cellX());
                group.minCellZ = Math.min(group.minCellZ, tile.cellZ());
                group.maxCellX = Math.max(group.maxCellX, tile.cellX());
                group.maxCellZ = Math.max(group.maxCellZ, tile.cellZ());
                if (tile.state().ordinal() > group.state.ordinal()) {
                    group.state = tile.state();
                }
                for (MapTile next : joinedNeighbours(byCell, tile)) {
                    if (visited.add(tileKey(next.cellX(), next.cellZ()))) {
                        queue.add(next);
                    }
                }
            }
            group.nwCellX = group.minCellX;
            group.nwCellZ = group.minCellZ;
            groups.add(group);
        }
        return groups;
    }

    private static List<MapTile> joinedNeighbours(Map<Long, MapTile> byCell, MapTile tile) {
        List<MapTile> result = new ArrayList<>(4);
        if (tile.joinEast()) {
            addIfPresent(result, byCell.get(tileKey(tile.cellX() + 1, tile.cellZ())));
        }
        if (tile.joinSouth()) {
            addIfPresent(result, byCell.get(tileKey(tile.cellX(), tile.cellZ() + 1)));
        }
        MapTile west = byCell.get(tileKey(tile.cellX() - 1, tile.cellZ()));
        if (west != null && west.joinEast()) {
            result.add(west);
        }
        MapTile north = byCell.get(tileKey(tile.cellX(), tile.cellZ() - 1));
        if (north != null && north.joinSouth()) {
            result.add(north);
        }
        return result;
    }

    private static void addIfPresent(List<MapTile> list, MapTile tile) {
        if (tile != null) {
            list.add(tile);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static int span(int cells) {
        return cells * CELL + (cells - 1) * LANE;
    }

    private static int tileX(int originX, MapSnapshot snapshot, int cellX) {
        return originX + (cellX - snapshot.minCellX()) * (CELL + LANE);
    }

    private static int tileY(int originY, MapSnapshot snapshot, int cellZ) {
        return originY + (cellZ - snapshot.minCellZ()) * (CELL + LANE);
    }

    private static long tileKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    /** Connector colour per door kind: black wither door, red blood door, neutral open doorway. */
    private static int doorColor(RoomMapReader.DoorType type) {
        return switch (type) {
            case WITHER -> DOOR_WITHER;
            case BLOOD -> DOOR_BLOOD;
            default -> DOOR_COLOR;
        };
    }

    private static int tileColor(byte colour) {
        return switch (colour) {
            case RoomMapReader.COLOR_ENTRANCE -> TILE_ENTRANCE;
            case RoomMapReader.COLOR_BLOOD -> TILE_BLOOD;
            case RoomMapReader.COLOR_PUZZLE -> TILE_PUZZLE;
            case RoomMapReader.COLOR_TRAP -> TILE_TRAP;
            case RoomMapReader.COLOR_MINIBOSS -> TILE_MINIBOSS;
            case RoomMapReader.COLOR_FAIRY -> TILE_FAIRY;
            case RoomMapReader.COLOR_NORMAL -> TILE_NORMAL;
            case RoomMapReader.COLOR_UNEXPLORED, RoomMapReader.COLOR_QUESTION -> TILE_UNEXPLORED;
            default -> DOOR_COLOR;
        };
    }
}
