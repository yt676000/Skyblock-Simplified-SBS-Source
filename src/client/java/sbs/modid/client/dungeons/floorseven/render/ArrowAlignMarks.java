/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.render;

import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.dungeons.floorseven.logic.ArrowAlignSolver;
import sbs.modid.client.dungeons.floorseven.logic.ArrowAlignTracker;
import sbs.modid.client.dungeons.floorseven.model.ArrowAlignGrid;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * Draws Arrow Align's per-frame click counts on the device, through the debug-gizmo scope so the
 * marks are depth-tested like the frames themselves.
 *
 * <p>Mark language (shared P3 convention, built in parallel elsewhere - this is a local helper to
 * fold into the shared one): <b>filled glow</b> on a frame = turn this one; <b>corner badge</b> =
 * how many clicks; a <b>tick glyph</b> = already right. Done vs to-do differs by shape (tick vs
 * number + fill), not only by colour, so it reads without colour vision.
 *
 * <p>All geometry is rebuilt when the tracker's grid revision or the theme changes - never per frame.
 * The per-frame pass only hands the cached objects to {@link Gizmos}.
 */
public final class ArrowAlignMarks {

    /** Glyphs for 0..7 clicks; 0 is the tick. Constant strings, so a frame draws no new text. */
    private static final String[] LABELS = {"✔", "1", "2", "3", "4", "5", "6", "7"};
    private static final String UNKNOWN = "unknown layout - recording";
    private static final String AMBIGUOUS = "ambiguous layout - recording";

    /** Half the marked square on a frame face, in blocks (a frame is 12/16 across). */
    private static final double HALF = 0.36;
    /** How far in front of the wall the marks sit: frame depth (1/16) plus a hair against z-fight. */
    private static final double FACE_OFFSET = 0.0625 + 0.02;
    private static final double DRAW_DISTANCE_SQ = 24 * 24;

    private static int builtRevision = -1;
    private static int builtTheme = -1;
    private static int count;
    private static Vec3[][] corners = new Vec3[0][];
    private static Vec3[] badge = new Vec3[0];
    private static int[] clicks = new int[0];
    private static Vec3 banner;
    private static String bannerText;
    private static GizmoStyle todoStyle;
    private static TextGizmo.Style todoText;
    private static TextGizmo.Style doneText;
    private static TextGizmo.Style bannerStyle;

    private ArrowAlignMarks() {
    }

    /** From the TAIL of {@code DebugRenderer.emitGizmos} ({@code EntityHitboxesMixin}). */
    public static void emit(double camX, double camY, double camZ) {
        ArrowAlignTracker tracker = ArrowAlignTracker.getInstance();
        if (tracker.placement() == null || !tracker.enabled()) {
            return;
        }
        rebuildIfStale(tracker);
        if (banner != null) {
            if (banner.distanceToSqr(camX, camY, camZ) <= DRAW_DISTANCE_SQ) {
                Gizmos.billboardText(bannerText, banner, bannerStyle);
            }
            return;
        }
        for (int i = 0; i < count; i++) {
            int n = clicks[i];
            if (n > 0) {
                Vec3[] c = corners[i];
                Gizmos.rect(c[0], c[1], c[2], c[3], todoStyle);
                Gizmos.billboardText(LABELS[n], badge[i], todoText);
            } else {
                Gizmos.billboardText(LABELS[0], badge[i], doneText);
            }
        }
    }

    private static void rebuildIfStale(ArrowAlignTracker tracker) {
        int theme = SBSTheme.revision();
        if (tracker.revision() == builtRevision && theme == builtTheme) {
            return;
        }
        builtRevision = tracker.revision();
        builtTheme = theme;
        int accent = SBSTheme.ACCENT;
        int done = SBSTheme.TOGGLE_ON;
        todoStyle = GizmoStyle.strokeAndFill(accent, 2.0f, (accent & 0x00FFFFFF) | 0x60000000);
        todoText = TextGizmo.Style.forColorAndCentered(SBSTheme.TEXT);
        doneText = TextGizmo.Style.forColorAndCentered(done);
        bannerStyle = TextGizmo.Style.forColorAndCentered(SBSTheme.TEXT_MUTED);

        ArrowAlignGrid.Placement placement = tracker.placement();
        ArrowAlignSolver.Result result = tracker.result();
        ArrowAlignGrid grid = placement.grid();
        int nx = placement.nx();
        int nz = placement.nz();
        // Right-hand vector of a viewer facing the wall; see ArrowAlignGrid.fromFrames.
        int rx = nz;
        int rz = -nx;

        int arrows = grid.arrowCount();
        corners = new Vec3[arrows][];
        badge = new Vec3[arrows];
        clicks = new int[arrows];
        count = 0;
        double sumX = 0;
        double sumZ = 0;
        double topY = Double.NEGATIVE_INFINITY;
        for (ArrowAlignGrid.Frame frame : placement.frames()) {
            sumX += frame.x() + 0.5;
            sumZ += frame.z() + 0.5;
            topY = Math.max(topY, frame.y() + 1.0);
        }
        int frames = placement.frames().size();
        if (result.status() != ArrowAlignSolver.Status.SOLVED) {
            banner = new Vec3(sumX / frames - nx * 0.5, topY + 0.5, sumZ / frames - nz * 0.5);
            bannerText = result.status() == ArrowAlignSolver.Status.AMBIGUOUS ? AMBIGUOUS : UNKNOWN;
            return;
        }
        banner = null;
        int[] frameAt = placement.frameAt();
        for (int cell = 0; cell < frameAt.length; cell++) {
            int n = result.clicksAt(cell);
            if (frameAt[cell] < 0 || n < 0) {
                continue;
            }
            ArrowAlignGrid.Frame frame = placement.frames().get(frameAt[cell]);
            // Face centre: the frame's block centre pushed back to the wall, then out by the frame depth.
            double cx = frame.x() + 0.5 - nx * (0.5 - FACE_OFFSET);
            double cy = frame.y() + 0.5;
            double cz = frame.z() + 0.5 - nz * (0.5 - FACE_OFFSET);
            corners[count] = new Vec3[]{
                    new Vec3(cx - rx * HALF, cy - HALF, cz - rz * HALF),
                    new Vec3(cx + rx * HALF, cy - HALF, cz + rz * HALF),
                    new Vec3(cx + rx * HALF, cy + HALF, cz + rz * HALF),
                    new Vec3(cx - rx * HALF, cy + HALF, cz - rz * HALF)};
            // Done frames carry the tick in the middle; counts sit in the top-right corner badge.
            double off = n == 0 ? 0 : HALF * 0.7;
            badge[count] = new Vec3(cx + rx * off + nx * 0.02, cy + off - 0.1, cz + rz * off + nz * 0.02);
            clicks[count] = Math.min(n, LABELS.length - 1);
            count++;
        }
    }
}
