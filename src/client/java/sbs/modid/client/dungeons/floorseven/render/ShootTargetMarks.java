/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.render;

import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.floorseven.logic.ShootTargetBoard;
import sbs.modid.client.dungeons.floorseven.logic.ShootTargetTracker;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * Draws the Shoot the Target board as depth-tested gizmos (the frame's debug-gizmo scope, the same as
 * Entity Hitboxes and the reward chests), so a mark behind a wall stays behind it.
 *
 * <p>Mark language (shared in intent with the other device helpers being built in parallel - a
 * local helper here, to be consolidated): <b>filled glow</b> = do this (the current target),
 * <b>hollow outline</b> = already done (faint, inset), <b>corner badge</b> = the count
 * ({@code 3/9}). The three differ by shape and fill, not by hue alone, so the board reads for a
 * colour-blind player too; the colours are theme tokens.
 *
 * <p>Nothing is built per frame: boxes, styles and the badge text are rebuilt only when the board's
 * revision or the theme's revision changes. Display only - nothing here aims or shoots.
 */
public final class ShootTargetMarks {

    /** The target box stands a little proud of the block so its fill reads on top of the face. */
    private static final double GROW = 0.02;
    /** Hit marks are inset so they sit inside the block rather than fighting the target's outline. */
    private static final double INSET = 0.2;

    private static int builtBoard = -1;
    private static int builtTheme = -1;
    private static AABB targetBox;
    private static AABB[] hitBoxes = new AABB[0];
    private static Vec3 badgeAt;
    private static String badge;
    private static GizmoStyle targetStyle;
    private static GizmoStyle hitStyle;
    private static TextGizmo.Style badgeStyle;

    private ShootTargetMarks() {
    }

    /** From the TAIL of {@code DebugRenderer.emitGizmos}, once per frame. */
    public static void emit() {
        if (!ConfigManager.getInstance().get().dungeons.shootTarget) {
            return;
        }
        ShootTargetTracker tracker = ShootTargetTracker.getInstance();
        ShootTargetBoard board = tracker.board();
        if (!tracker.armed() || !board.seen() || board.completed()) {
            return;
        }
        if (builtBoard != board.revision() || builtTheme != SBSTheme.revision()) {
            rebuild(board);
        }
        for (AABB box : hitBoxes) {
            Gizmos.cuboid(box, hitStyle);
        }
        if (targetBox != null) {
            Gizmos.cuboid(targetBox, targetStyle);
        }
        if (badgeAt != null) {
            Gizmos.billboardText(badge, badgeAt, badgeStyle);
        }
    }

    private static void rebuild(ShootTargetBoard board) {
        builtBoard = board.revision();
        builtTheme = SBSTheme.revision();
        int accent = SBSTheme.ACCENT;
        targetStyle = GizmoStyle.strokeAndFill(accent, 3.0f, (accent & 0x00FFFFFF) | 0x66000000);
        hitStyle = GizmoStyle.stroke((SBSTheme.TEXT_MUTED & 0x00FFFFFF) | 0x80000000, 1.5f);
        badgeStyle = TextGizmo.Style.forColorAndCentered(SBSTheme.TEXT);

        AABB[] hits = new AABB[board.hitCellCount()];
        int n = 0;
        long target = board.hasTarget() ? board.target() : Long.MIN_VALUE;
        for (int i = 0; i < board.hitCellCount(); i++) {
            long cell = board.hitCell(i);
            if (cell == target) {
                continue; // lit again: the target mark wins
            }
            hits[n++] = new AABB(BlockPos.of(cell)).deflate(INSET);
        }
        hitBoxes = n == hits.length ? hits : java.util.Arrays.copyOf(hits, n);

        BlockPos anchor = board.hasTarget() ? BlockPos.of(target)
                : board.hitCellCount() > 0 ? BlockPos.of(board.hitCell(board.hitCellCount() - 1)) : null;
        targetBox = board.hasTarget() ? new AABB(anchor).inflate(GROW) : null;
        // Corner badge: just off the top corner of the block the eye is already on.
        badgeAt = anchor == null ? null : new Vec3(anchor.getX() + 1.1, anchor.getY() + 1.25, anchor.getZ() + 1.1);
        badge = board.hits() + "/" + ShootTargetBoard.ESTIMATED_HITS;
    }
}
