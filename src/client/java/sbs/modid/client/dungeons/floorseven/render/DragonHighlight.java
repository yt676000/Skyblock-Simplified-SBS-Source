/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.floorseven.logic.DragonTracker;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The statue boxes of the M7 dragon phase: the piece of room a dragon has to die in for its statue
 * to come down. One box per dragon, in that dragon's colour.
 *
 * <p><b>The box answers one question, so it answers it loudly.</b> While the dragon is outside its
 * box the outline is thin - information, not instruction. The moment the dragon is inside, the box
 * thickens and fills: that is the window in which damage counts, and it wants to be visible from
 * whatever corner of the arena you are fighting in, without reading a label.
 *
 * <p>Statues whose dragon is not up are drawn faint, so the arena still shows where the other four
 * are while you are dragging this one home. Positions come from {@link DragonTracker} - learned from
 * where each dragon came up, correctable by hand with {@code /sbs dragons <colour>}.
 */
public final class DragonHighlight {

    /** Live box: the dragon is in it. Thick, filled - "hit it now". */
    private static final int WEIGHT_IN_ZONE = 4;
    /** Its dragon is up, but elsewhere. */
    private static final int WEIGHT_WAITING = 2;
    /** No dragon of that colour right now. */
    private static final int WEIGHT_IDLE = 1;

    /** Alpha of the fill while the dragon stands in its box. */
    private static final int FILL_ALPHA = 0x33000000;
    /** Alpha the outline of an idle statue is faded to. */
    private static final int IDLE_ALPHA = 0x66000000;

    private static final double LABEL_HEIGHT = 1.0;

    private DragonHighlight() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ConfigManager.getInstance().get().dungeons.m7DragonBoxes
                || minecraft.player == null || minecraft.level == null
                || !DragonTracker.inBossRoom()) {
            return;
        }
        DragonTracker tracker = DragonTracker.getInstance();
        List<DragonTracker.Dragon> dragons = tracker.dragons();
        if (dragons.isEmpty() && !DragonTracker.inDragonPhase()) {
            return; // the four phases before the dragons have no use for five boxes
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        // The living dragons first: each draws the box it has to be brought back to.
        Map<DragonTracker.Kind, Boolean> drawn = new EnumMap<>(DragonTracker.Kind.class);
        for (DragonTracker.Dragon dragon : dragons) {
            drawn.put(dragon.kind(), Boolean.TRUE);
            String label = dragon.kind().label() + (dragon.inZone() ? " §akill here"
                    : " §7" + Math.round(dragon.statueDistance()) + "m");
            box(g, font, viewProjection, camPos, dragon.statue(), dragon.kind().color(),
                    dragon.inZone() ? WEIGHT_IN_ZONE : WEIGHT_WAITING, dragon.inZone(), label);
        }
        // Then the statues nobody is fighting: faint, so the arena stays readable.
        for (DragonTracker.Kind kind : DragonTracker.Kind.values()) {
            if (kind == DragonTracker.Kind.UNKNOWN || drawn.containsKey(kind)) {
                continue;
            }
            Vec3 statue = tracker.statueOf(kind);
            if (statue != null) {
                box(g, font, viewProjection, camPos, statue, fade(kind.color()), WEIGHT_IDLE, false,
                        "§7" + kind.label());
            }
        }
    }

    private static void box(GuiGraphicsExtractor g, Font font, Matrix4f viewProjection, Vec3 camPos,
                            Vec3 statue, int color, int weight, boolean filled, String label) {
        double radius = DragonTracker.zoneRadius();
        double x0 = statue.x - radius;
        double y0 = statue.y - radius;
        double z0 = statue.z - radius;
        double x1 = statue.x + radius;
        double y1 = statue.y + radius;
        double z1 = statue.z + radius;
        if (filled) {
            WorldRender.fillBox(g, viewProjection, camPos, x0, y0, z0, x1, y1, z1,
                    (color & 0x00FFFFFF) | FILL_ALPHA);
        }
        WorldRender.boxEdges(g, viewProjection, camPos, x0, y0, z0, x1, y1, z1, color, weight);

        int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                new Vec3(statue.x, y1 + LABEL_HEIGHT, statue.z), g.guiWidth(), g.guiHeight());
        if (screen != null) {
            g.centeredText(font, Component.literal(label), screen[0], screen[1], color);
        }
    }

    /** The same colour at the alpha an idle statue is drawn with. */
    private static int fade(int color) {
        return (color & 0x00FFFFFF) | IDLE_ALPHA;
    }
}
