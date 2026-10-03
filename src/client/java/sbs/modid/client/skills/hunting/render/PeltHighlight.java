/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.skills.hunting.logic.PeltTracker;

import java.util.Locale;

/**
 * The pelt hunt's world drawing: the box and tracer on the quest animal, and Trevor's cooldown
 * written under his name.
 *
 * <p>The animal is drawn through walls - the whole point of the feature is finding one that hides,
 * so unlike the see-only highlights this one is a genuine tracker. Pure view over
 * {@link PeltTracker}'s tick cache.
 */
public final class PeltHighlight {

    /** How far from Trevor the cooldown line is worth drawing - it is a "standing at him" readout. */
    private static final double TREVOR_RANGE = 24.0;

    /** Trevor's nametag floats about this far above his feet; the cooldown goes just under it. */
    private static final double NAME_HEIGHT = 2.1;

    private static final int READY_COLOR = 0xFF30E030;
    /** Ready by the assumed countdown only - the same green, dimmed, because it is not confirmed. */
    private static final int MAYBE_READY_COLOR = 0xFF7FA870;
    private static final int WAIT_COLOR = 0xFFFFE000;
    /** Nothing is known yet - muted, so it never competes with a real countdown. */
    private static final int UNKNOWN_COLOR = 0xFFA0A8B4;
    /** A hunt is running. */
    private static final int HUNTING_COLOR = 0xFF6AC7FF;

    /** Marks the line as ours rather than something Hypixel wrote above the NPC. */
    private static final String PREFIX = "[SBS]: ";

    private PeltHighlight() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.PeltSettings cfg = ConfigManager.getInstance().get().pelt;
        Minecraft minecraft = Minecraft.getInstance();
        if (!cfg.enabled || minecraft.player == null || minecraft.level == null) {
            return;
        }
        PeltTracker.Target target = PeltTracker.getInstance().target();
        if (target == null && !cfg.trapperCooldown) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());

        if (cfg.trapperCooldown) {
            drawTrevorCooldown(g, minecraft, viewProjection, camPos);
        }
        if (target == null) {
            return;
        }
        int color = cfg.color.argb();
        AABB box = target.box();
        if (cfg.highlightAnimal) {
            WorldRender.boxEdges(g, viewProjection, camPos,
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, 3);
            label(g, minecraft.font, viewProjection, camPos, box, target, color);
        }
        if (cfg.showTracer) {
            WorldRender.tracerToBox(g, viewProjection, camPos, box, color, 2);
        }
    }

    /**
     * Writes the quest cooldown one line under Trevor's floating name.
     *
     * <p>Placed by projecting a point at his nametag height and stepping down one line in screen
     * space, rather than by projecting a lower world point: the nametag is drawn at a fixed screen
     * size regardless of distance, so a world-space offset would drift away from it as you walk
     * back. One line down in pixels sits under the name at every distance.
     *
     * <p><b>It never claims "Ready" without knowing.</b> The client cannot read Trevor's cooldown;
     * it only learns of one by watching chat, and until then there is no information at all. That
     * used to render as "Ready" - the countdown simply started at zero - so the line was green and
     * wrong from login until the first hunt of the session ended. Now the unknown case says what
     * would actually resolve it, and a countdown taken from the setting rather than from Trevor is
     * marked with a {@code ~} instead of being shown as if it were measured.
     */
    private static void drawTrevorCooldown(GuiGraphicsExtractor g, Minecraft minecraft,
                                           Matrix4f viewProjection, Vec3 camPos) {
        Entity trevor = findTrevor(minecraft, camPos);
        if (trevor == null) {
            return;
        }
        PeltTracker.TrapperStatus status = PeltTracker.getInstance().trapperStatus();
        String text;
        int color;
        switch (status.state()) {
            case HUNTING -> {
                text = "On hunt";
                color = HUNTING_COLOR;
            }
            case COOLING -> {
                String seconds = String.format(Locale.US, "%.0fs",
                        Math.ceil(status.remainingMs() / 1000.0));
                text = status.stated() ? seconds : "~" + seconds;
                color = WAIT_COLOR;
            }
            case READY -> {
                // A countdown Trevor stated running out is knowledge; the configured assumption
                // running out is not - if that number is too short this would be the original bug
                // again, just delayed. So an assumed ready keeps the question mark and a dimmer
                // green, and only his own number earns the confident one.
                text = status.stated() ? "Ready" : "Ready?";
                color = status.stated() ? READY_COLOR : MAYBE_READY_COLOR;
            }
            default -> {
                // No cooldown has been seen start or finish, so nothing can be said about one.
                // Talking to him is what resolves it: he either hands out a hunt or states the
                // remaining time, and either way the readout becomes real from that moment.
                text = "Talk to check";
                color = UNKNOWN_COLOR;
            }
        }

        Vec3 above = trevor.position().add(0, NAME_HEIGHT, 0);
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos, above,
                g.guiWidth(), g.guiHeight());
        if (screen == null) {
            return;
        }
        Font font = minecraft.font;
        g.centeredText(font, Component.literal(PREFIX + text), screen[0],
                screen[1] + font.lineHeight + 1, color);
    }

    /**
     * The nearest entity whose name is Trevor's. Hypixel NPCs are player entities carrying their own
     * display name, but the tag also turns up on a separate stand on some builds - so both are
     * accepted and the nearest wins.
     */
    private static Entity findTrevor(Minecraft minecraft, Vec3 camPos) {
        if (minecraft.level == null) {
            return null;
        }
        Entity best = null;
        double bestDistance = TREVOR_RANGE * TREVOR_RANGE;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            Component name = entity.getCustomName() != null ? entity.getCustomName()
                    : (entity instanceof Player ? entity.getName() : null);
            if (name == null) {
                continue;
            }
            if (!name.getString().replaceAll("§.", "").toLowerCase(Locale.ROOT).contains("trevor")) {
                continue;
            }
            double distance = entity.position().distanceToSqr(camPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    /** The tier and species above the box - and a hint while the animal itself is faded out. */
    private static void label(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                              AABB box, PeltTracker.Target target, int color) {
        String text = target.mob() == null ? target.label() + " (hidden)" : target.label();
        Vec3 top = new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.5, (box.minZ + box.maxZ) / 2);
        int[] screen = WorldRender.projectToScreen(vp, camPos, top, g.guiWidth(), g.guiHeight());
        if (screen != null) {
            g.centeredText(font, Component.literal(text), screen[0], screen[1], color);
        }
    }
}
