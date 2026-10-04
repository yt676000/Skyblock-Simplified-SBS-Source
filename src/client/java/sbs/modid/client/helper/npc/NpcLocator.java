/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.npc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.RecipeViewerSettings;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.helper.npc.SkyblockNpcs.Npc;
import sbs.modid.client.core.pathfinding.PathfindingManager;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;

import java.util.List;

/**
 * The Recipe Viewer's NPC locator: shift-click an "... (NPC)" entry and a small panel shows where that
 * NPC stands – its scoreboard area and coordinates – and, once you are on its island, hands the
 * coordinates to the existing pathfinding module so it routes you there.
 *
 * <p>Coordinates only mean anything on their own island (every SkyBlock island shares one Minecraft
 * dimension but has its own coordinate space), so the pathfinding waypoint is only published while the
 * live scoreboard area matches the NPC's – exactly the gate {@link sbs.modid.client.helper.quest.logic.QuestWaypoints}
 * uses. Arm it before you travel and it engages the moment you arrive; the waypoint clears itself once
 * you reach the NPC. An NPC missing from {@link SkyblockNpcs} still opens the panel, it just reports
 * the location as not yet catalogued.
 */
public final class NpcLocator {

    private static final NpcLocator INSTANCE = new NpcLocator();

    /** How long the info panel stays up after a shift-click when no pathfinding target is armed. */
    private static final long PANEL_MS = 12_000L;
    /** Within this many blocks of the NPC the route is considered done and clears itself. */
    private static final double ARRIVE_DIST = 3.0;

    /** The clean name shown in the panel (set even for an NPC missing from the table). */
    private String panelName;
    /** The catalogued NPC, or {@code null} when the shift-clicked NPC is not in the seed table. */
    private Npc panelNpc;
    private long shownAt;
    /** The NPC pathfinding is currently routing to (persists across the island travel), or null. */
    private Npc target;

    private NpcLocator() {
    }

    public static NpcLocator getInstance() {
        return INSTANCE;
    }

    /** The NPC whose marker is currently published (transient), or {@code null}. */
    private Npc published;

    /** Whether the one-off cleanup of persisted NPC markers from older versions has run. */
    private boolean legacyPurged;

    private static RecipeViewerSettings cfg() {
        return ConfigManager.getInstance().get().recipeViewer;
    }

    // ------------------------------------------------------------------ intake

    /** Shift-click entry point: opens the panel for {@code displayName} and arms pathfinding to it. */
    public void show(String displayName) {
        panelName = SkyblockNpcs.cleanName(displayName);
        panelNpc = SkyblockNpcs.find(displayName);
        shownAt = System.currentTimeMillis();
        target = panelNpc != null && cfg().npcPathfinding ? panelNpc : null;

        if (panelNpc == null) {
            SBSChat.send(Component.literal(" NPC ").withColor(SBSChat.WHITE)
                    .append(Component.literal(panelName).withColor(SBSChat.PREFIX_COLOR))
                    .append(Component.literal(" is not in the location database yet.").withColor(SBSChat.WHITE)));
            return;
        }
        SBSChat.send(Component.literal(" " + panelName + "  ").withColor(SBSChat.PREFIX_COLOR)
                .append(Component.literal(panelNpc.area() + "  " + coords(panelNpc)).withColor(SBSChat.WHITE))
                .append(Component.literal(onLocation(panelNpc)
                        ? (target != null ? "  – pathfinding" : "")
                        : "  – go to " + panelNpc.island()).withColor(0x57D977)));
    }

    // ------------------------------------------------------------------ tick

    /** Called every client tick: publishes / clears the pathfinding waypoint as you travel and arrive. */
    public void onClientTick() {
        if (!legacyPurged) {
            purgeLegacyWaypoints();
        }
        if (target == null) {
            return;
        }
        if (!cfg().npcPathfinding) {
            target = null;
            clearWaypoint();
            return;
        }
        if (!onLocation(target)) {
            clearWaypoint();   // not on the NPC's island yet – don't route to the wrong place
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player != null && player.position().distanceTo(
                new net.minecraft.world.phys.Vec3(target.x(), target.y(), target.z())) <= ARRIVE_DIST) {
            target = null;     // arrived
            clearWaypoint();
            return;
        }
        ensureWaypoint(target);
    }

    // ------------------------------------------------------------------ render

    /** Draws the info panel (centred, upper third) while it is showing. Safe to call from any pass. */
    public void render(GuiGraphicsExtractor g) {
        if (panelName == null) {
            return;
        }
        if (target == null && System.currentTimeMillis() - shownAt > PANEL_MS) {
            panelName = null;   // auto-hide once the info has been up long enough and nothing is routing
            return;
        }
        Font font = Minecraft.getInstance().font;
        String title = panelName + " (NPC)";
        String line2;
        String line3;
        int line3Color;
        if (panelNpc == null) {
            line2 = "Location not in database yet";
            line3 = "";
            line3Color = SBSTheme.TEXT_MUTED;
        } else {
            line2 = panelNpc.area() + "   " + coords(panelNpc);
            boolean here = onLocation(panelNpc);
            line3 = !cfg().npcPathfinding ? "Pathfinding off"
                    : here ? (target != null ? "Pathfinding to NPC…" : "You are here")
                    : "Travel to " + panelNpc.island();
            line3Color = here ? 0xFF57D977 : SBSTheme.ACCENT_BRIGHT;
        }

        int contentW = Math.max(font.width(title),
                Math.max(font.width(line2), line3.isEmpty() ? 0 : font.width(line3)));
        int padX = 8;
        int padY = 6;
        int lineH = font.lineHeight + 3;
        int w = contentW + padX * 2;
        int h = padY * 2 + lineH * (line3.isEmpty() ? 2 : 3) - 3;
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() / 5;

        SciFiRender.glow(g, x, y, w, h, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, x, y, w, h, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, x + 1, y + 1, w - 2, h - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int ty = y + padY;
        g.centeredText(font, Component.literal(title), x + w / 2, ty, SBSTheme.ACCENT_BRIGHT);
        ty += lineH;
        g.centeredText(font, Component.literal(line2), x + w / 2, ty, SBSTheme.TEXT);
        if (!line3.isEmpty()) {
            ty += lineH;
            g.centeredText(font, Component.literal(line3), x + w / 2, ty, line3Color);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String coords(Npc npc) {
        return "(" + trim(npc.x()) + ", " + trim(npc.y()) + ", " + trim(npc.z()) + ")";
    }

    /** "-34.5" but "69" (drop a trailing ".0"). */
    private static String trim(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    /**
     * Whether the player is on the NPC's island - the "may I route there" gate. Island-level, not
     * zone-level: coordinates are island-scoped, and once you are on the right island walking you to
     * the right zone is the pathfinder's job.
     */
    private static boolean onLocation(Npc npc) {
        return SkyBlockLocation.onIsland(npc.island());
    }

    /**
     * Publishes the marker as a transient waypoint, like every other publisher: it exists only while
     * the path was asked for and the player is on the NPC's island, so it must never outlive the
     * session. (It used to go into the persisted list, where a marker left at quit survived the
     * restart - with {@code target} gone, nothing cleared it.)
     */
    private void ensureWaypoint(Npc npc) {
        if (published == npc && WaypointStore.hasSource(Waypoint.SOURCE_NPC)) {
            return;   // already published
        }
        published = npc;
        WaypointStore.setTransient(Waypoint.SOURCE_NPC, List.of(new Waypoint(npc.name(),
                new BlockPos((int) Math.round(npc.x()), (int) Math.round(npc.y()), (int) Math.round(npc.z())),
                WaypointStore.currentDimension(), Waypoint.SOURCE_NPC)));
    }

    private void clearWaypoint() {
        published = null;
        WaypointStore.clearTransient(Waypoint.SOURCE_NPC);   // invalidates the pathfinder if it removed one
    }

    /** Drops NPC markers older versions wrote into the persisted list - once per session. */
    private void purgeLegacyWaypoints() {
        legacyPurged = true;
        List<Waypoint> all = WaypointStore.all();
        if (!all.isEmpty() && all.removeIf(Waypoint::isNpc)) {
            ConfigManager.getInstance().save();
            PathfindingManager.getInstance().invalidate();
        }
    }
}
