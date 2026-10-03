/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.combat.kuudra.logic.FreshTracker;
import sbs.modid.client.combat.kuudra.logic.KuudraAbilities;
import sbs.modid.client.combat.kuudra.logic.KuudraTracker;
import sbs.modid.client.combat.kuudra.logic.PearlStore;
import sbs.modid.client.combat.kuudra.logic.SupplyTracker;
import sbs.modid.client.combat.kuudra.model.CratePile;
import sbs.modid.client.combat.kuudra.model.KuudraPhase;
import sbs.modid.client.combat.kuudra.model.PearlArea;
import sbs.modid.client.combat.kuudra.model.PearlPoint;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.core.render.WorldRender;

import java.util.List;
import java.util.Locale;

/**
 * Everything this module draws into the world.
 *
 * <p><b>A Kuudra run is a sequence of "be at this exact spot now" problems</b> - the crate that just
 * surfaced, the pile that is not built yet, the pod inside the mouth, the pitch your pearl wants.
 * Each phase gets the two or three markers that matter in it and nothing else: the arena is small,
 * four people are fighting in it, and a screen carrying every marker at once is a screen you stop
 * reading.
 *
 * <p><b>Beams, not boxes, for anything you have to travel to.</b> A box is only visible once you are
 * looking at the thing; a column of light is visible from across the arena and over the walkway
 * walls, which is exactly the distance these markers are read from.
 *
 * <p>Pure view. Nothing is decided here - every position and state comes from the trackers' published
 * snapshots, so what is on screen is what the state machine believes, and a wrong marker is a wrong
 * <i>tracker</i> rather than two places to look.
 */
public final class KuudraHighlight {

    /** How tall a marker beam is drawn. Tall enough to clear the walkways from anywhere in the arena. */
    private static final double BEAM_HEIGHT = 40.0;

    /** Half-width of a beam column. Thin: it is a direction, not an object. */
    private static final double BEAM_HALF = 0.28;

    /** Alpha of a beam's translucent fill, over the solid edges. */
    private static final int BEAM_FILL_ALPHA = 0x30;

    /** The pod positions inside Kuudra's mouth - fixed geometry of the stun sub-arena. */
    private static final Vec3[] PODS = {
            new Vec3(-167.5, 28, -167.5),
            new Vec3(-152.5, 27, -172.5),
            new Vec3(-154.5, 28, -156.5),
    };

    /** How close your pitch has to be to a marker's recorded pitch to read as lined up. */
    private static final double PITCH_TOLERANCE = 1.5;

    /** Build-phase colour ramp, coldest first. Index = percent / 20, capped. */
    private static final int[] BUILD_RAMP = {
            0xFFA80000, 0xFFFF2020, 0xFFFF8700, 0xFF2E8200, 0xFF7CFF6A,
    };

    private static final int COLOR_MISSING = 0xFFFF4040;
    private static final int COLOR_READY = 0xFF7CFF6A;
    private static final int COLOR_POOL = 0xD060EEFF;

    private KuudraHighlight() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.KuudraSettings cfg = ConfigManager.getInstance().get().kuudra;
        KuudraTracker tracker = KuudraTracker.getInstance();
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (!cfg.enabled || player == null || level == null || !tracker.running()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f vp = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;
        KuudraPhase phase = tracker.phase();

        switch (phase) {
            case SUPPLIES -> {
                if (cfg.pileWaypoints) {
                    piles(g, font, vp, camPos, cfg);
                }
                if (cfg.supplyWaypoints) {
                    carriers(g, font, vp, camPos, player, cfg);
                }
            }
            case BUILD -> {
                if (cfg.buildWaypoints) {
                    buildPiles(g, font, vp, camPos);
                }
                if (cfg.freshTimers) {
                    freshTimers(g, font, vp, camPos, level);
                }
            }
            case STUN -> {
                if (cfg.stunWaypoints) {
                    pods(g, font, vp, camPos, cfg);
                }
            }
            default -> {
                // Nothing phase-specific: the always-on markers below still draw.
            }
        }

        if (cfg.pearlWaypoints) {
            pearls(g, font, vp, camPos, player, cfg);
        }
        if (cfg.ichorPoolMarkers) {
            pools(g, vp, camPos);
        }
        if (cfg.kuudraHitbox && phase.bossUp()) {
            hitbox(g, vp, camPos, tracker.boss(), cfg);
        }
    }

    // ------------------------------------------------------------------ phase one

    /**
     * The six drop-off piles.
     *
     * <p>A pile that has had its crate stops being drawn - it is the piles you still owe that are
     * worth a beam, and leaving the finished ones up is what turns the platform into a wall of light
     * halfway through the phase. A pile whose crate has been called missing is drawn in the alert
     * colour with the call on it, because that pile is the one nobody should be waiting at.
     */
    private static void piles(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                              SBSConfig.KuudraSettings cfg) {
        SupplyTracker supplies = SupplyTracker.getInstance();
        int normal = cfg.pileColor.argb();
        for (CratePile pile : CratePile.values()) {
            if (cfg.hideDonePiles && supplies.isCompleted(pile)) {
                continue;
            }
            boolean missing = supplies.isCalledMissing(pile.feeder());
            int color = missing ? COLOR_MISSING : normal;
            beam(g, vp, camPos, pile.position(), color);
            label(g, font, vp, camPos, pile.position().add(0, 2.2, 0),
                    missing ? "§cNO " + pile.displayName().toUpperCase(Locale.ROOT)
                            : pile.displayName(), color);
        }
    }

    /** Live crates: one beam per carrier, wherever it currently is down in the lava. */
    private static void carriers(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                                 LocalPlayer player, SBSConfig.KuudraSettings cfg) {
        int color = cfg.supplyColor.argb();
        for (Vec3 pos : SupplyTracker.getInstance().carriers()) {
            beam(g, vp, camPos, pos, color);
            int distance = (int) player.position().distanceTo(pos);
            label(g, font, vp, camPos, pos.add(0, 3.0, 0), "Supply §7" + distance + "m", color);
        }
    }

    // ------------------------------------------------------------------ phase two

    /**
     * The ballista piles mid-build, each in the colour of how far along it is.
     *
     * <p>The ramp runs red to green because the question the phase asks is "which pile is furthest
     * behind" - the answer has to be readable at a glance from the other side of the platform, and a
     * colour carries that at a distance where the percentage text does not.
     */
    private static void buildPiles(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos) {
        for (SupplyTracker.PileProgress pile : SupplyTracker.getInstance().buildPiles()) {
            int color = BUILD_RAMP[Math.min(BUILD_RAMP.length - 1, Math.max(0, pile.percent() / 20))];
            beam(g, vp, camPos, pile.position(), color);
            label(g, font, vp, camPos, pile.position().add(0, 2.2, 0), pile.percent() + "%", color);
        }
    }

    /** The fresh countdown over each fresh player's head - who is buffed, and for how much longer. */
    private static void freshTimers(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                                    ClientLevel level) {
        FreshTracker fresh = FreshTracker.getInstance();
        for (AbstractClientPlayer other : level.players()) {
            long left = fresh.remaining(other.getGameProfile().name());
            if (left < 0) {
                continue;
            }
            // Green while there is time to start another pile, red once there is only time to finish.
            int color = left > 6_000 ? COLOR_READY : left > 3_000 ? 0xFFFFE000 : 0xFFFF5555;
            label(g, font, vp, camPos, other.position().add(0, other.getBbHeight() + 0.9, 0),
                    String.format(Locale.US, "%.1fs", left / 1000.0), color);
        }
    }

    // ------------------------------------------------------------------ phase three

    /** The three pods inside the mouth. Boxed rather than beamed - it is a small room, not an arena. */
    private static void pods(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                             SBSConfig.KuudraSettings cfg) {
        int color = cfg.stunColor.argb();
        for (Vec3 pod : PODS) {
            WorldRender.boxEdges(g, vp, camPos,
                    pod.x - 0.5, pod.y, pod.z - 0.5, pod.x + 0.5, pod.y + 1.0, pod.z + 0.5, color, 2);
            label(g, font, vp, camPos, pod.add(0, 1.4, 0), "Pod", color);
        }
    }

    // ------------------------------------------------------------------ always on

    /**
     * The player's own pearl markers for wherever they are standing.
     *
     * <p>Only the area you are inside is drawn - a full setup is far more throws than can be aimed at
     * from any one place, and drawing all of them would bury the two that are usable. A marker whose
     * recorded pitch matches where you are actually looking turns green: that is the entire feedback
     * loop, and it is why the pitch is on the label at all.
     */
    private static void pearls(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                               LocalPlayer player, SBSConfig.KuudraSettings cfg) {
        PearlArea area = PearlStore.areaAt(player.position());
        if (area == null) {
            return;
        }
        int missing = 6 - SupplyTracker.getInstance().delivered();
        int fallback = cfg.pearlColor.argb();
        for (PearlPoint point : area.points) {
            if (point.onlyPre != 0 && point.onlyPre != missing) {
                continue;
            }
            if (point.hidePre != 0 && point.hidePre == missing) {
                continue;
            }
            Vec3 at = area.resolve(point, player.position());
            Double pitch = point.pitch();
            boolean lined = point.alert && pitch != null
                    && Math.abs(player.getXRot() - pitch) <= PITCH_TOLERANCE;
            int color = lined ? COLOR_READY : color(point.colorHex, fallback);
            double half = Math.max(0.1, point.size) / 2.0;
            WorldRender.boxEdges(g, vp, camPos,
                    at.x - half, at.y - half, at.z - half,
                    at.x + half, at.y + half, at.z + half, color, lined ? 3 : 2);
            String text = point.label.isEmpty() ? point.note : point.label;
            if (!text.isEmpty()) {
                label(g, font, vp, camPos, at.add(0, half + 0.4, 0),
                        lined ? "§aREADY" : text, color);
            }
        }
    }

    /** Ichor pools a teammate called out: the ring of floor their buff covers. */
    private static void pools(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos) {
        List<KuudraAbilities.Pool> pools = KuudraAbilities.getInstance().pools();
        if (pools.isEmpty()) {
            return;
        }
        for (KuudraAbilities.Pool pool : pools) {
            ring(g, vp, camPos, pool.center(), KuudraAbilities.POOL_RADIUS, COLOR_POOL);
        }
    }

    /** Kuudra himself, boxed on his real hitbox so the shots that miss are visibly misses. */
    private static void hitbox(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos, MagmaCube boss,
                               SBSConfig.KuudraSettings cfg) {
        if (boss == null || !boss.isAlive()) {
            return;
        }
        AABB box = boss.getBoundingBox();
        int color = cfg.bossColor.argb();
        WorldRender.boxEdges(g, vp, camPos,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, 2);
        if (cfg.bossTracer) {
            WorldRender.tracerToBox(g, vp, camPos, box, color, 2);
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    /** A column of light on a spot: a translucent fill inside solid edges, so it reads at any range. */
    private static void beam(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos, Vec3 at, int color) {
        double x0 = at.x - BEAM_HALF;
        double z0 = at.z - BEAM_HALF;
        double x1 = at.x + BEAM_HALF;
        double z1 = at.z + BEAM_HALF;
        WorldRender.fillBox(g, vp, camPos, x0, at.y, z0, x1, at.y + BEAM_HEIGHT, z1,
                (color & 0x00FFFFFF) | (BEAM_FILL_ALPHA << 24));
        WorldRender.boxEdges(g, vp, camPos, x0, at.y, z0, x1, at.y + BEAM_HEIGHT, z1, color, 2);
    }

    /** A flat ring on the floor, drawn as a closed polygon of short segments. */
    private static void ring(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos, Vec3 center,
                             double radius, int color) {
        final int segments = 48;
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        int[] previous = null;
        int[] first = null;
        for (int i = 0; i < segments; i++) {
            double angle = i * 2 * Math.PI / segments;
            Vec3 point = center.add(Math.cos(angle) * radius, 0.05, Math.sin(angle) * radius);
            int[] screen = WorldRender.projectToScreen(vp, camPos, point, gw, gh);
            if (screen != null && previous != null) {
                WorldRender.line(g, previous[0], previous[1], screen[0], screen[1], color, 2);
            }
            if (first == null) {
                first = screen;
            }
            previous = screen;
        }
        if (first != null && previous != null) {
            WorldRender.line(g, previous[0], previous[1], first[0], first[1], color, 2);
        }
    }

    /** One line of text centred over a world point. Skipped silently when it is behind the camera. */
    private static void label(GuiGraphicsExtractor g, Font font, Matrix4f vp, Vec3 camPos,
                              Vec3 at, String text, int color) {
        int[] screen = WorldRender.projectToScreen(vp, camPos, at, g.guiWidth(), g.guiHeight());
        if (screen == null) {
            return;
        }
        g.text(font, Component.literal(text), screen[0] - font.width(text) / 2, screen[1], color, false);
    }

    /** {@code RRGGBB} to opaque ARGB, falling back to the module's configured colour. */
    private static int color(String hex, int fallback) {
        Integer rgb = OverlayColor.parseHex(hex);
        return rgb == null ? fallback : 0xFF000000 | rgb;
    }
}
