/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.render.OverlayColor;

/**
 * What {@link PathRenderer} derives from one waypoint and would otherwise rebuild every frame: the
 * label text, the sub-label and its parsed colour, and the occlusion ray's answer.
 *
 * <p>Every entry is keyed on what it was built from and rebuilt when that changes, so a publisher
 * that edits a field in place (a ticking sub-label, a renamed marker) is picked up on the next frame
 * without telling anyone. Keys compare by reference where the input is a string, which is exact for
 * "was this field reassigned" and costs nothing.
 *
 * <p>Render-thread only, hung off the waypoint as a transient field so it never reaches the config.
 */
final class MarkerRenderCache {

    /**
     * How long an occlusion answer stands while the camera holds still, in client ticks. Moving the
     * camera further than {@link #OCCLUSION_MOVE_SQ} re-casts at once, so this only bounds how late a
     * block placed or broken in the line of sight is noticed.
     */
    private static final long OCCLUSION_TICKS = 4;
    private static final double OCCLUSION_MOVE_SQ = 0.5 * 0.5;

    // Label line, keyed by everything that goes into the string.
    private Component label;
    private String labelName;
    private int labelDistance = Integer.MIN_VALUE;
    private int labelState = -1;

    // Sub-label line.
    private Component sub;
    private String subText;

    // Sub-label colour; null rgb means "use the marker's colour".
    private String subColorHex;
    private Integer subRgb;

    // Occlusion.
    private boolean occluded;
    private long occlusionTick = Long.MIN_VALUE;
    private double camX, camY, camZ;
    private int targetX, targetY, targetZ;

    /** Route-state suffix on the routed marker's label. */
    static final int STATE_PLAIN = 0;
    static final int STATE_NO_ROUTE = 1;
    static final int STATE_PARTIAL = 2;

    static MarkerRenderCache of(Waypoint waypoint) {
        MarkerRenderCache cache = waypoint.renderCache;
        if (cache == null) {
            cache = new MarkerRenderCache();
            waypoint.renderCache = cache;
        }
        return cache;
    }

    /**
     * The label: name, then the rounded distance when the waypoint shows it, then the route state.
     * {@code distance} is the already-rounded metre count, so the text changes only when the digits do.
     */
    Component label(Waypoint waypoint, int distance, int state) {
        int keyDistance = waypoint.showDistance ? distance : Integer.MIN_VALUE;
        if (label == null || labelName != waypoint.name || labelDistance != keyDistance || labelState != state) {
            String text = waypoint.showDistance ? waypoint.name + " §7" + distance + "m" : waypoint.name;
            if (state == STATE_NO_ROUTE) {
                text += " §c(no route to that island known)";
            } else if (state == STATE_PARTIAL) {
                text += " §c(partial)";
            }
            label = Component.literal(text);
            labelName = waypoint.name;
            labelDistance = keyDistance;
            labelState = state;
        }
        return label;
    }

    /** The sub-label line as a component, rebuilt only when the publisher replaced the string. */
    Component sub(String text) {
        if (sub == null || subText != text) {
            sub = Component.literal(text);
            subText = text;
        }
        return sub;
    }

    /** The sub-label's own colour, or {@code fallback} when it has none - parsed once per value. */
    int subRgb(String hex, int fallback) {
        if (subColorHex != hex) {
            subRgb = OverlayColor.parseHex(hex);
            subColorHex = hex;
        }
        return subRgb == null ? fallback : subRgb;
    }

    /**
     * Whether terrain hides {@code target}, re-casting only when the answer may have changed: every
     * {@link #OCCLUSION_TICKS} ticks, when the camera moved more than half a block, or when the
     * marker itself moved. Between those the last answer stands.
     */
    boolean occluded(Vec3 camPos, Vec3 target, long tick) {
        int tx = (int) Math.floor(target.x);
        int ty = (int) Math.floor(target.y);
        int tz = (int) Math.floor(target.z);
        double dx = camPos.x - camX;
        double dy = camPos.y - camY;
        double dz = camPos.z - camZ;
        if (Math.abs(tick - occlusionTick) >= OCCLUSION_TICKS
                || dx * dx + dy * dy + dz * dz > OCCLUSION_MOVE_SQ
                || tx != targetX || ty != targetY || tz != targetZ) {
            occluded = PathRenderer.occluded(camPos, target);
            occlusionTick = tick;
            camX = camPos.x;
            camY = camPos.y;
            camZ = camPos.z;
            targetX = tx;
            targetY = ty;
            targetZ = tz;
        }
        return occluded;
    }
}
