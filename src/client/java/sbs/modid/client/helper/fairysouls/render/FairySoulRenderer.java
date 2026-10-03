/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.fairysouls.render;

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
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.helper.fairysouls.logic.FairySoulDatabase;
import sbs.modid.client.helper.fairysouls.logic.FairySoulRouting;
import sbs.modid.client.helper.fairysouls.logic.FairySoulStore;
import sbs.modid.client.helper.fairysouls.model.FairySoul;

import java.util.List;

/**
 * Draws the Fairy Souls of the island you are standing on.
 *
 * <p>Its own renderer rather than the generic waypoint one because these need things that one has no
 * concept of: dozens of markers at once, a visible difference between collected and uncollected, and
 * an independent colour for each state. The routed soul is emphasised here (a larger, breathing box
 * and a bold label) rather than by the generic waypoint renderer, which is deliberately kept out of
 * the way - the shared renderer still draws the <i>route line</i> to it, so the target reads
 * differently from the rest of the field without any marker being drawn twice.
 *
 * <p>Everything projects through {@link WorldRender}: the overlay has no depth buffer, so "through
 * walls" is the natural behaviour and hiding behind terrain is the option that costs a raycast.
 */
public final class FairySoulRenderer {

    /**
     * Souls the client's own scanner catalogued, as opposed to the curated file's: a fixed cyan,
     * never the player's uncollected colour, so the two cannot be mistaken for each other.
     */
    private static final int LEARNED_COLOR = 0xCC55DDFF;

    /** Souls further than this are drawn without a label, to stop a dense island turning into soup. */
    private static final double LABEL_RANGE = 64.0;

    /** Marker cube half-size, in blocks. */
    private static final double MARKER_HALF = 0.35;

    /** One full breath of the uncollected marker. */
    private static final long PULSE_MS = 1_600L;

    private FairySoulRenderer() {
    }

    private static SBSConfig.FairySoulSettings cfg() {
        return ConfigManager.getInstance().get().fairySouls;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.FairySoulSettings cfg = cfg();
        if (!cfg.enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        String island = SkyBlockLocation.island();
        List<FairySoul> souls = FairySoulDatabase.forIsland(island);
        if (souls.isEmpty()) {
            return;
        }

        // Everything below this point is decided before a single marker is projected. The collection
        // record is per profile and answers empty until the profile is known, so drawing during that
        // window shows every soul as uncollected - which is the pink flash on join, and is a wrong
        // picture rather than merely an early one. An island already finished cannot contribute a
        // marker at all when collected souls are hidden, so it stops here too rather than after
        // eighty per-soul tests and a raycast apiece.
        FairySoulStore store = FairySoulStore.getInstance();
        if (!store.ready()) {
            return;
        }
        boolean islandDone = store.isIslandDone(island);
        if (islandDone && !cfg.showCollected) {
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        FairySoul routed = FairySoulRouting.getInstance().currentTarget();
        double breath = breath();

        for (FairySoul soul : souls) {
            // islandDone is hoisted out of the loop - it is one answer for the whole island, and
            // asking it per soul re-read the record eighty times a frame for nothing.
            boolean collected = islandDone || store.isCollected(soul.id, island);
            if (collected && !cfg.showCollected) {
                continue;
            }
            Vec3 centre = soul.centre();
            // The raycast is the most expensive thing here, so it goes last of the three tests.
            if (!cfg.throughWalls && occluded(minecraft, camPos, centre)) {
                continue;
            }
            boolean active = routed != null && routed.id.equals(soul.id);
            int color = collected ? colorOf(cfg.collectedColor, cfg.collectedColorHex,
                    cfg.collectedOpacity)
                    : soul.learned ? LEARNED_COLOR
                    : colorOf(cfg.uncollectedColor, cfg.uncollectedColorHex, cfg.uncollectedOpacity);

            drawMarker(g, viewProjection, camPos, centre, color, active, collected ? 0 : breath);

            if (cfg.showTracers && !collected) {
                WorldRender.tracer(g, viewProjection, camPos, centre,
                        colorOf(cfg.tracerColor, cfg.tracerColorHex, cfg.tracerOpacity),
                        active ? 2 : 1);
            }

            double distance = camPos.distanceTo(centre);
            if (distance <= LABEL_RANGE || active) {
                drawLabel(g, viewProjection, camPos, font, soul, centre, distance, collected, active,
                        cfg);
            }
        }
    }

    /** The marker cube. The uncollected ones breathe; collected ones sit still and dim. */
    private static void drawMarker(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                   Vec3 centre, int color, boolean active, double breath) {
        double half = MARKER_HALF + (active ? 0.10 * breath + 0.05 : 0.04 * breath);
        WorldRender.boxEdges(g, viewProjection, camPos,
                centre.x - half, centre.y - half, centre.z - half,
                centre.x + half, centre.y + half, centre.z + half,
                color, active ? 3 : 2);
    }

    private static void drawLabel(GuiGraphicsExtractor g, Matrix4f viewProjection, Vec3 camPos,
                                  Font font, FairySoul soul, Vec3 centre, double distance,
                                  boolean collected, boolean active,
                                  SBSConfig.FairySoulSettings cfg) {
        int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                centre.add(0, 0.8, 0), g.guiWidth(), g.guiHeight());
        if (screen == null) {
            return;
        }
        StringBuilder label = new StringBuilder();
        // A learned soul says so in its name: if the scanner ever learns a wrong skin again, the
        // player sees "unconfirmed" on the decoration instead of a confident "Fairy Soul".
        String name = soul.learned ? "Fairy Soul? (seen, unconfirmed)" : "Fairy Soul";
        String colour = soul.learned ? "§b" : "§d";
        label.append(collected ? "§7" + name : active ? colour + "§l" + name : colour + name);
        if (cfg.showDistance) {
            label.append(" §7").append((int) Math.round(distance)).append('m');
        }
        if (cfg.showSoulIds) {
            label.append(" §8").append(soul.id);
        }
        g.centeredText(font, Component.literal(label.toString()), screen[0], screen[1], 0xFFFFFFFF);

        // Where the soul actually is, but only ever on the routed one: a second line under fifty
        // markers at once would bury the field it is meant to clarify.
        if (cfg.showHints && active && !collected) {
            String hint = hintFor(soul);
            if (!hint.isEmpty()) {
                g.centeredText(font, Component.literal(hint),
                        screen[0], screen[1] + font.lineHeight, 0xFFFFFFFF);
            }
        }
    }

    /**
     * The routed soul's second line: its zone, plus a warning when it cannot be walked to.
     *
     * <p>The data file's own {@code note} wins when it has one - it is the specific answer ("behind
     * the painting") where the zone is only the general one. The bundled file carries no notes; the
     * field is there for the backend's copy to fill.
     */
    private static String hintFor(FairySoul soul) {
        StringBuilder hint = new StringBuilder();
        if (soul.note != null && !soul.note.isBlank()) {
            hint.append("§7").append(soul.note);
        } else if (soul.area != null && !soul.area.isBlank()) {
            hint.append("§7").append(soul.area);
        }
        if (!soul.walkable) {
            hint.append(hint.isEmpty() ? "" : " ").append("§c(needs a teleport)");
        }
        return hint.toString();
    }

    /**
     * ARGB from a preset, an optional hex override, and an opacity percentage.
     *
     * <p>Alpha is a separate percentage rather than an eight-digit hex so the existing colour presets
     * stay usable and the setting reads as one slider - the same shape the other overlay modules use.
     */
    private static int colorOf(sbs.modid.client.core.render.OverlayColor preset, String hex,
                               int opacityPercent) {
        Integer custom = sbs.modid.client.core.render.OverlayColor.parseHex(hex);
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
