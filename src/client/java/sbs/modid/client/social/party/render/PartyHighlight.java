/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.party.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.PartyHighlightSettings;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.social.party.logic.PartyTracker;

/**
 * Draws a full through-wall highlight – a coloured box plus the name – around every {@link PartyTracker}
 * member currently rendered on the island. Projection through the HUD pipeline ignores depth, so the
 * box shows through terrain, which is the point on Hypixel where party members have no native marker.
 *
 * <p><b>In a dungeon</b> the highlight recolours per member by their dungeon class (from the sidebar's
 * {@code "[A] Name"} lines via {@link sbs.modid.client.dungeons.run.model.DungeonTeamClasses}: Archer red,
 * Berserk orange, Tank green, Healer purple, Mage blue) – and the sidebar also acts as the roster,
 * so the dungeon highlight works even when no party chat was ever seen.
 */
public final class PartyHighlight {

    /** A teammate under the low-health threshold. */
    private static final int LOW_HEALTH_COLOR = 0xFFFF2020;


    private static final int[][] EDGES = {
            {0, 1}, {1, 3}, {3, 2}, {2, 0},
            {4, 5}, {5, 7}, {7, 6}, {6, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7},
    };

    private PartyHighlight() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        PartyHighlightSettings cfg = ConfigManager.getInstance().get().partyHighlight;
        Minecraft minecraft = Minecraft.getInstance();
        if (!cfg.enabled || minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (hiddenInThisBossRoom(cfg)) {
            return;
        }
        // Roster: the party chat, OR – in a dungeon – the sidebar's class lines (always present).
        boolean dungeonRoster = sbs.modid.client.dungeons.run.model.DungeonTeamClasses.hasClasses();
        if (!PartyTracker.getInstance().hasParty() && !dungeonRoster) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;
        int color = cfg.color.argb();
        PartyTracker tracker = PartyTracker.getInstance();

        boolean sawMember = false;
        for (AbstractClientPlayer other : minecraft.level.players()) {
            if (other == minecraft.player || !other.isAlive()) {
                continue;
            }
            String name = other.getGameProfile().name();
            boolean member = tracker.isMember(name);
            char dungeonClass = sbs.modid.client.dungeons.run.model.DungeonTeamClasses.classOf(name);
            if (!member && dungeonClass == 0) {
                continue;
            }
            sawMember |= member;
            // In a dungeon each member wears their class colour; elsewhere the configured one.
            int memberColor = sbs.modid.client.dungeons.run.model.DungeonTeamClasses.colorOf(name, color);
            // Teammate low health: red while their sidebar health is under the threshold.
            if (sbs.modid.client.dungeons.teamhealth.logic.TeamHealthTracker.getInstance().tintLow(name)) {
                memberColor = LOW_HEALTH_COLOR;
            }
            AABB box = other.getBoundingBox();
            if (cfg.showBox) {
                drawEdges(g, viewProjection, camPos, box, memberColor);
            }
            if (cfg.showTracer) {
                WorldRender.tracerToBox(g, viewProjection, camPos, box, memberColor, 2);
            }
            if (cfg.showNametag) {
                drawLabel(g, font, viewProjection, camPos, box, name, memberColor);
            }
        }
        if (sawMember) {
            // Seeing a member confirms the party is still live, so the roster does not expire.
            tracker.noteSeen();
        }
    }

    /**
     * Whether the party highlight is switched off for the boss room we are standing in
     * ({@link PartyHighlightSettings#hiddenBossFloors}). Only ever true inside a dungeon whose phase the
     * state manager has resolved to {@code BOSS} – outside a boss fight the setting does nothing,
     * so a floor ticked here still shows the boxes for the whole run up to the boss door.
     */
    private static boolean hiddenInThisBossRoom(PartyHighlightSettings cfg) {
        if (cfg.hiddenBossFloors.isEmpty()) {
            return false;
        }
        var state = sbs.modid.client.dungeons.run.logic.DungeonStateManager.getInstance();
        return state.inDungeon()
                && state.phase() == sbs.modid.client.dungeons.events.DungeonEvents.Phase.BOSS
                && cfg.hiddenBossFloors.contains(state.floorNumber());
    }

    private static void drawEdges(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos, AABB box, int color) {
        Vec3[] corners = {
                new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.minY, box.minZ),
                new Vec3(box.minX, box.minY, box.maxZ), new Vec3(box.maxX, box.minY, box.maxZ),
                new Vec3(box.minX, box.maxY, box.minZ), new Vec3(box.maxX, box.maxY, box.minZ),
                new Vec3(box.minX, box.maxY, box.maxZ), new Vec3(box.maxX, box.maxY, box.maxZ),
        };
        // Clipped to the near plane instead of dropped when a corner goes behind the camera, so a
        // box you are standing inside stays whole as you turn.
        for (int[] edge : EDGES) {
            WorldRender.line3d(g, vp, camPos, corners[edge[0]], corners[edge[1]], color, 2);
        }
    }

    private static void drawLabel(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                                  AABB box, String label, int color) {
        Vec3 top = new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.5, (box.minZ + box.maxZ) / 2);
        int[] screen = WorldRender.projectToScreen(vp, camPos, top, g.guiWidth(), g.guiHeight());
        if (screen != null) {
            g.text(font, Component.literal(label), screen[0] - font.width(label) / 2, screen[1], color);
        }
    }
}
