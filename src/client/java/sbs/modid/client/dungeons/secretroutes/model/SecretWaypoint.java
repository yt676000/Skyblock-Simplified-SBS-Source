/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.secretroutes.model;

/**
 * One annotated point of a {@link SecretRoute}, stored <b>room-relative</b> in the room's canonical
 * (NORTH-normalised) frame - the same frame the scanned-room database uses - so it rotates back onto
 * whatever facing the room generated with this run (see {@code RoomRotation}).
 *
 * <p>Every field is a plain public POJO field so Gson round-trips it into {@code secretroutes.txt}
 * with no custom adapter. Optional data is boxed / nullable and only set for the types that need it;
 * a missing field stays {@code null} and is simply not used, so old files keep loading.
 */
public final class SecretWaypoint {

    /** What kind of point this is - drives both the editor flow and the render colour. */
    public enum Type {
        /** A block to stand on. */
        STANDING,
        /** An Aspect-of-the-Void etherwarp: stand + look (yaw/pitch) + the target block warped to. */
        AOTV_WARP,
        /** An ender-pearl throw: stand + a pixel-exact yaw/pitch to aim at. */
        PEARL,
        /** A collectible secret placed by hand on the block under the player's feet. */
        SECRET
    }

    /** Sub-kind of a {@link Type#SECRET} point; {@code null} for the movement types. */
    public enum Secret {
        CHEST, LEVER, BAT, ITEM, WITHER_ESSENCE
    }

    public Type type = Type.STANDING;
    /** Only meaningful for {@link Type#SECRET}; {@code null} otherwise. */
    public Secret subtype;

    /** Order of this point within its route (0-based); the renderer numbers points by it. */
    public int index;
    /** Free-form, editable note rendered above the point when descriptions are on. */
    public String description = "";
    /** A disabled point is kept but neither rendered nor routed through. */
    public boolean enabled = true;

    /** The canonical block position (block under the feet / the secret block). Always present. */
    public int relX;
    public int relY;
    public int relZ;

    /**
     * Pixel-exact canonical stand position for {@link Type#PEARL} / {@link Type#AOTV_WARP}
     * ({@code null} for the others) - the throw/warp only works from the precise spot, so the
     * sub-block position is kept, not just the block.
     */
    public Double preciseX;
    public Double preciseY;
    public Double preciseZ;

    /**
     * Canonical look direction for {@link Type#PEARL} / {@link Type#AOTV_WARP}, stored at <b>full
     * float precision</b> (never rounded) so the aim indicator can be pixel-exact. Yaw is rotated
     * with the room on load; pitch is rotation-invariant.
     */
    public Float yaw;
    public Float pitch;

    /** The etherwarp target block for {@link Type#AOTV_WARP} (canonical), {@code null} otherwise. */
    public Integer targetRelX;
    public Integer targetRelY;
    public Integer targetRelZ;

    public SecretWaypoint() {
    }

    /** Whether this point carries a stored aim (pearl / etherwarp) the crosshair indicator can use. */
    public boolean hasAim() {
        return yaw != null && pitch != null;
    }

    /** A short type label for the editor list and the in-world number tag. */
    public String typeLabel() {
        return switch (type) {
            case STANDING -> "Stand";
            case AOTV_WARP -> "AOTV";
            case PEARL -> "Pearl";
            case SECRET -> subtype != null ? prettySubtype() : "Secret";
        };
    }

    private String prettySubtype() {
        String[] parts = subtype.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
