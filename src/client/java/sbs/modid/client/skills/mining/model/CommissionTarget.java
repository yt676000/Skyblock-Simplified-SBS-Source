/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.model;

import net.minecraft.core.BlockPos;

/**
 * Where one commission is done, as far as it could be worked out.
 *
 * <p><b>Three kinds, and they are deliberately not blurred together.</b> A commission naming a place
 * ("Rampart's Quarry Mithril") has one answer that is right for its whole life, and it is a table
 * lookup. A commission naming a mob or a material has no fixed answer at all - the right destination
 * is whichever instance is nearest <i>now</i>, and it changes as the player walks and as the world is
 * mined out. Treating the second as the first is how a route ends up pointing at the spot where the
 * ore used to be.
 *
 * <p><b>{@link Kind#UNRESOLVED} is a real answer, not a failure to produce one.</b> It carries the
 * commission text that could not be placed, so the HUD can say so, the command can list it and the
 * log can name it for the vocabulary to be extended. Nothing anywhere is allowed to turn it into a
 * plausible guess - the island's warp point, the nearest known area - because a waypoint the player
 * follows for a minute and a half to the wrong place costs more than no waypoint at all.
 *
 * @param kind   how the destination was arrived at
 * @param label  what to write on the marker and in the list - the commission's own name
 * @param token  the area, mob or material word the parse matched, for diagnostics and re-scans
 * @param pos    the destination, or {@code null} for {@link Kind#UNRESOLVED} and for a live target
 *               whose scan has not found anything yet
 * @param warpId the map file's warp id for this location when it names one, so a destination in an
 *               unloaded area can offer the warp that gets near it; empty otherwise
 */
public record CommissionTarget(Kind kind, String label, String token, BlockPos pos, String warpId) {

    public enum Kind {
        /** A named place with fixed coordinates from the island map data. */
        AREA,
        /** A mob named by the commission; the destination is the nearest one alive right now. */
        MOB,
        /** A material named by the commission; the destination is the nearest such block right now. */
        MATERIAL,
        /** Nothing in the vocabulary matched. Reported as unknown, never guessed at. */
        UNRESOLVED
    }

    /** An unresolved target for {@code label} - the honest answer when the parse finds nothing. */
    public static CommissionTarget unresolved(String label) {
        return new CommissionTarget(Kind.UNRESOLVED, label, "", null, "");
    }

    /** Whether this target moves as the player and the world do, and so needs re-scanning. */
    public boolean isLive() {
        return kind == Kind.MOB || kind == Kind.MATERIAL;
    }

    /** Whether there is somewhere to send the player right now. */
    public boolean hasPosition() {
        return pos != null;
    }

    /** The same target with a freshly scanned position. */
    public CommissionTarget at(BlockPos found) {
        return new CommissionTarget(kind, label, token, found, warpId);
    }

    /** One line for the command listing and the log. */
    public String describe() {
        return switch (kind) {
            case AREA -> label + " -> " + token + (pos == null ? "" : " " + posText());
            case MOB -> label + " -> nearest " + token + (pos == null ? " (none nearby)" : " " + posText());
            case MATERIAL -> label + " -> nearest " + token
                    + (pos == null ? " (no block known for it yet)" : " " + posText());
            case UNRESOLVED -> label + " -> unknown"
                    + (token.isEmpty() ? "" : " (nothing matched: " + token + ")");
        };
    }

    private String posText() {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
