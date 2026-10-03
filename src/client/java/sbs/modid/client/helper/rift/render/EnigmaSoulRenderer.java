/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.helper.rift.logic.EnigmaSoulDatabase;
import sbs.modid.client.helper.rift.logic.EnigmaSoulStore;
import sbs.modid.client.helper.rift.logic.EnigmaSoulTracker;
import sbs.modid.client.helper.rift.logic.RiftState;
import sbs.modid.client.helper.rift.model.EnigmaSoul;
import sbs.modid.client.helper.rift.model.SoulState;

import java.util.List;

/**
 * Draws the Enigma Souls of the Rift, in the three states they can be in.
 *
 * <p><b>The instruction is the marker.</b> A coordinate is enough for a Fairy Soul, which is always
 * an orb you right-click; it is close to useless for an Enigma Soul, most of which are a condition
 * rather than a place. So the label carries the soul's name and, for the one nearest the crosshair,
 * how to actually get it - and a soul whose condition the player has said they cannot meet is not
 * drawn at all rather than left standing there forever.
 *
 * <p>Everything projects through {@link WorldRender}: the overlay has no depth buffer, so "through
 * walls" is the natural behaviour and hiding behind terrain is the option that costs a raycast.
 */
public final class EnigmaSoulRenderer {

    /** Souls further than this are drawn without a label, to stop a dense area turning into soup. */
    private static final double LABEL_RANGE = 64.0;

    /** The instruction line is only ever shown for the soul this close to the crosshair. */
    private static final double INSTRUCTION_RANGE = 24.0;

    /** Marker cube half-size, in blocks. */
    private static final double MARKER_HALF = 0.35;

    /** One full breath of an uncollected marker. */
    private static final long PULSE_MS = 1_600L;

    private EnigmaSoulRenderer() {
    }

    private static SBSConfig.EnigmaSoulSettings cfg() {
        return ConfigManager.getInstance().get().enigmaSouls;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.EnigmaSoulSettings cfg = cfg();
        if (!cfg.enabled || !RiftState.getInstance().inRift()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        List<EnigmaSoul> souls = EnigmaSoulDatabase.all();
        if (souls.isEmpty()) {
            return;
        }
        // Decided before anything is projected: the record is per profile and answers empty until
        // the profile is known, which resolves every soul to UNKNOWN and paints the whole Rift as
        // maybes for the first seconds of a session. That is a wrong picture, not an early one, so
        // it is not drawn and then retracted - it waits.
        if (!EnigmaSoulStore.getInstance().ready()) {
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        EnigmaSoulTracker tracker = EnigmaSoulTracker.getInstance();
        double breath = breath();

        // The nearest drawable soul gets the instruction line. Picked in the same pass rather than a
        // separate one, because "nearest" has to respect the same filters as the drawing does - an
        // instruction for a soul that was filtered out would be advice about a marker that is not there.
        EnigmaSoul nearest = null;
        double nearestSqr = INSTRUCTION_RANGE * INSTRUCTION_RANGE;

        for (EnigmaSoul soul : souls) {
            SoulState state = tracker.stateOf(soul);
            if (!visible(cfg, soul, state)) {
                continue;
            }
            Vec3 centre = soul.centre();
            if (!cfg.throughWalls && occluded(minecraft, camPos, centre)) {
                continue;
            }
            double distanceSqr = camPos.distanceToSqr(centre);
            if (state != SoulState.FOUND && distanceSqr < nearestSqr) {
                nearestSqr = distanceSqr;
                nearest = soul;
            }
        }

        for (EnigmaSoul soul : souls) {
            SoulState state = tracker.stateOf(soul);
            if (!visible(cfg, soul, state)) {
                continue;
            }
            Vec3 centre = soul.centre();
            if (!cfg.throughWalls && occluded(minecraft, camPos, centre)) {
                continue;
            }
            int color = colorFor(cfg, state);
            boolean active = soul == nearest;
            drawMarker(g, viewProjection, camPos, centre, color, active,
                    state == SoulState.FOUND ? 0 : breath);

            if (cfg.showTracers && state != SoulState.FOUND) {
                WorldRender.tracer(g, viewProjection, camPos, centre,
                        colorOf(cfg.tracerColor, cfg.tracerColorHex, cfg.tracerOpacity),
                        active ? 2 : 1);
            }

            double distance = camPos.distanceTo(centre);
            if (distance <= LABEL_RANGE || active) {
                drawLabel(g, viewProjection, camPos, font, soul, centre, distance, state, active, cfg);
            }
        }
    }

    /**
     * Whether this soul should be on screen at all.
     *
     * <p>The co-operation filter is the one that matters: a player on their own cannot collect a
     * two-player soul at any point, so leaving it in the field is a marker that can only ever be
     * noise. The unknowns are filterable too, for the opposite reason - a long-established profile
     * has dozens of them and some players would rather see nothing than see maybes.
     */
    private static boolean visible(SBSConfig.EnigmaSoulSettings cfg, EnigmaSoul soul,
                                   SoulState state) {
        if (state == SoulState.FOUND && !cfg.showFound) {
            return false;
        }
        if (state == SoulState.UNKNOWN && !cfg.showUnknown) {
            return false;
        }
        if (cfg.hideMultiplayer && !soul.soloable()) {
            return false;
        }
        return !cfg.hidePurchases
                || soul.requirement != sbs.modid.client.helper.rift.model.SoulRequirement.PURCHASE;
    }

    /** The marker cube. Uncollected ones breathe; found ones sit still and dim. */
    private static void drawMarker(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                   Vec3 centre, int color, boolean active, double breath) {
        double half = MARKER_HALF + (active ? 0.10 * breath + 0.05 : 0.04 * breath);
        WorldRender.boxEdges(g, viewProjection, camPos,
                centre.x - half, centre.y - half, centre.z - half,
                centre.x + half, centre.y + half, centre.z + half,
                color, active ? 3 : 2);
    }

    private static void drawLabel(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                  Font font, EnigmaSoul soul, Vec3 centre, double distance,
                                  SoulState state, boolean active,
                                  SBSConfig.EnigmaSoulSettings cfg) {
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                centre.add(0, 0.8, 0), g.guiWidth(), g.guiHeight());
        if (screen == null) {
            return;
        }
        StringBuilder label = new StringBuilder();
        label.append(switch (state) {
            case FOUND -> "§7";
            case MISSING -> active ? "§b§l" : "§b";
            case UNKNOWN -> active ? "§e§l" : "§e";
        }).append(soul.label());
        if (state == SoulState.UNKNOWN) {
            label.append(" §8?");
        }
        if (cfg.showDistance) {
            label.append(" §7").append((int) Math.round(distance)).append('m');
        }
        if (cfg.showSoulIds) {
            label.append(" §8").append(soul.id);
        }
        g.centeredText(font, Component.literal(label.toString()), screen[0], screen[1], 0xFFFFFFFF);

        // The instruction, on the nearest one only. A second line under forty markers at once would
        // bury the field it exists to clarify - and this is the line that makes the feature work, so
        // it has to be readable where it does appear.
        if (cfg.showInstructions && active && state != SoulState.FOUND) {
            int y = screen[1] + font.lineHeight;
            for (String line : instructionLines(soul)) {
                g.centeredText(font, Component.literal(line), screen[0], y, 0xFFFFFFFF);
                y += font.lineHeight;
            }
        }
    }

    /**
     * The instruction block: what to do, and what it needs before it can be done.
     *
     * <p>The requirement line comes second and only when it adds something the prose does not - the
     * data file's instruction usually names the item itself, and repeating it would cost a line of
     * screen for nothing.
     */
    private static List<String> instructionLines(EnigmaSoul soul) {
        String instruction = soul.instruction == null || soul.instruction.isBlank()
                ? soul.requirement.hint() : soul.instruction;
        String needs = "";
        if (!soul.soloable()) {
            needs = "§c" + soul.minPlayers + " players needed";
        } else if (soul.requiredItem != null && !soul.requiredItem.isBlank()
                && !instruction.toLowerCase(java.util.Locale.ROOT)
                .contains(soul.requiredItem.toLowerCase(java.util.Locale.ROOT))) {
            needs = "§6Needs " + soul.requiredItem;
        }
        return needs.isEmpty() ? List.of("§7" + instruction) : List.of("§7" + instruction, needs);
    }

    private static int colorFor(SBSConfig.EnigmaSoulSettings cfg, SoulState state) {
        return switch (state) {
            case FOUND -> colorOf(cfg.foundColor, cfg.foundColorHex, cfg.foundOpacity);
            case MISSING -> colorOf(cfg.missingColor, cfg.missingColorHex, cfg.missingOpacity);
            case UNKNOWN -> colorOf(cfg.unknownColor, cfg.unknownColorHex, cfg.unknownOpacity);
        };
    }

    /**
     * ARGB from a preset, an optional hex override, and an opacity percentage.
     *
     * <p>Alpha as a separate percentage rather than an eight-digit hex so the presets stay usable and
     * the setting reads as one slider - the same shape the other overlay modules use.
     */
    private static int colorOf(OverlayColor preset, String hex, int opacityPercent) {
        Integer custom = OverlayColor.parseHex(hex);
        int rgb = custom != null ? custom : preset.rgb();
        int alpha = Math.max(0, Math.min(255, Math.round(opacityPercent * 255f / 100f)));
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    /** Whether terrain hides the soul - only asked when the player turned through-walls off. */
    private static boolean occluded(Minecraft minecraft, Vec3 camPos, Vec3 target) {
        HitResult hit = minecraft.level.clip(new ClipContext(camPos, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getLocation().distanceToSqr(camPos) < target.distanceToSqr(camPos) - 1.0;
    }

    /** A 0..1 triangle wave (long modulo first, so it stays exact over a long session). */
    private static double breath() {
        double t = (System.currentTimeMillis() % PULSE_MS) / (double) PULSE_MS;
        return t < 0.5 ? t * 2 : (1 - t) * 2;
    }
}
