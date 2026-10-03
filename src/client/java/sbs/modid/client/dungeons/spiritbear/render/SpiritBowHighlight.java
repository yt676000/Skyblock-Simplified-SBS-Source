/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.spiritbear.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.spiritbear.logic.SpiritBearTracker;

/**
 * The Spirit Bow on the floor: a beam, a box and a tracer on the item the dead bear left behind.
 *
 * <p><b>The beam is the part that matters.</b> A dropped item is a quarter of a block of dark
 * geometry lying on a dark arena floor, in a fight with a boss, a bear's corpse and everybody's
 * particles on top of it - a box around something that small is only findable once you already know
 * where to look. The column of light goes up out of the mess and can be read from the far wall, which
 * is where whoever is fetching the bow usually is. The box and the label take over at close range,
 * where the beam is behind you.
 *
 * <p>Drawn through walls and through mobs on purpose: Thorn cannot be finished until somebody is
 * holding this, so the seconds spent looking for it are the most expensive seconds in the fight. Pure
 * view over {@link SpiritBearTracker}'s tick cache.
 */
public final class SpiritBowHighlight {

    /** Light purple - the colour the drop's own name is printed in. */
    private static final int COLOR_BOW = 0xFFE87CFF;

    /** The same purple at low alpha, so the box reads as a solid thing rather than a wireframe. */
    private static final int FILL_BOW = 0x40E87CFF;

    /** How far up the beam goes. Tall enough to clear the arena's walls from across the room. */
    private static final double BEAM_HEIGHT = 20.0;

    /** The item's own hitbox is a quarter block; the box is grown to something you can aim at. */
    private static final double BOX_INFLATE = 0.3;

    private SpiritBowHighlight() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().spiritBear;
        Minecraft minecraft = Minecraft.getInstance();
        if (!cfg.enabled || !cfg.highlightBow || minecraft.player == null || minecraft.level == null) {
            return;
        }
        ItemEntity bow = SpiritBearTracker.getInstance().bow();
        if (bow == null || !bow.isAlive()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        AABB box = bow.getBoundingBox().inflate(BOX_INFLATE);
        Vec3 foot = new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
        WorldRender.line3d(g, viewProjection, camPos, foot, foot.add(0, BEAM_HEIGHT, 0), COLOR_BOW, 3);
        WorldRender.fillBox(g, viewProjection, camPos,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, FILL_BOW);
        WorldRender.boxEdges(g, viewProjection, camPos,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, COLOR_BOW, 3);
        WorldRender.tracerToBox(g, viewProjection, camPos, box, COLOR_BOW, 3);

        // The distance is on the label because the answer being looked for is "can I get there first",
        // and a beam alone cannot say how far away its base is.
        String text = "Spirit Bow " + Math.round(minecraft.player.distanceTo(bow)) + "m";
        Vec3 top = new Vec3(foot.x, box.maxY + 0.5, foot.z);
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos, top,
                g.guiWidth(), g.guiHeight());
        if (screen != null) {
            g.centeredText(font, Component.literal(text), screen[0], screen[1], COLOR_BOW);
        }
    }
}
