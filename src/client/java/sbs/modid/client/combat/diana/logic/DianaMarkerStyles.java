/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import sbs.modid.client.combat.diana.model.DianaMarker;
import sbs.modid.client.combat.diana.model.MarkerStyle;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.pathfinding.MarkerBox;
import sbs.modid.client.core.pathfinding.MarkerLabelSize;

import java.util.Objects;

/**
 * The look each Diana marker type is drawn with, resolved from the stored style.
 *
 * <p>Resolution is where the guarantees live, so the publisher never has to know them: a missing
 * style or a null enum reads as the default, opacity is clamped to what the slider offers, and
 * through-walls is granted only to a type that already drew through walls.
 */
public final class DianaMarkerStyles {

    /** A marker type's effective look. {@code colorHex} empty means the global waypoint preset. */
    public record Resolved(String colorHex, MarkerBox box, boolean beam, MarkerLabelSize labelSize,
                           boolean showDistance, int opacity, boolean throughWalls) {
    }

    private static final MarkerStyle DEFAULT = new MarkerStyle();

    private DianaMarkerStyles() {
    }

    /** The stored style object for {@code type}, or {@code null} when the config lacks it. */
    public static MarkerStyle stored(SBSConfig.DianaAppearanceSettings appearance, DianaMarker type) {
        if (appearance == null) {
            return null;
        }
        return switch (type) {
            case START_BURROW -> appearance.startBurrow;
            case MOB_BURROW -> appearance.mobBurrow;
            case TREASURE_BURROW -> appearance.treasureBurrow;
            case GUESS -> appearance.guess;
            case RARE_CREATURE -> appearance.rareCreature;
            case SHARED_CREATURE -> appearance.sharedCreature;
        };
    }

    /**
     * The stored style for {@code type}, created in place when missing, for a settings row to edit.
     */
    public static MarkerStyle editable(SBSConfig.DianaSettings cfg, DianaMarker type) {
        if (cfg.appearance == null) {
            cfg.appearance = new SBSConfig.DianaAppearanceSettings();
        }
        SBSConfig.DianaAppearanceSettings a = cfg.appearance;
        MarkerStyle style = stored(a, type);
        if (style != null) {
            return style;
        }
        style = new MarkerStyle();
        switch (type) {
            case START_BURROW -> a.startBurrow = style;
            case MOB_BURROW -> a.mobBurrow = style;
            case TREASURE_BURROW -> a.treasureBurrow = style;
            case GUESS -> a.guess = style;
            case RARE_CREATURE -> a.rareCreature = style;
            case SHARED_CREATURE -> a.sharedCreature = style;
        }
        return style;
    }

    /**
     * The colour {@code type} is drawn in. Burrows and guesses read the colour fields they always
     * had; a creature reads its override, or {@code creatureHex} - that creature's own colour - when
     * the override is empty.
     */
    public static String colorHex(SBSConfig.DianaSettings cfg, DianaMarker type, String creatureHex) {
        SBSConfig.DianaAppearanceSettings a = cfg.appearance;
        return switch (type) {
            case START_BURROW -> nonNull(cfg.startColorHex);
            case MOB_BURROW -> nonNull(cfg.mobColorHex);
            case TREASURE_BURROW -> nonNull(cfg.treasureColorHex);
            case GUESS -> nonNull(cfg.guessColorHex);
            case RARE_CREATURE -> orElse(a == null ? null : a.rareCreatureColorHex, creatureHex);
            case SHARED_CREATURE -> orElse(a == null ? null : a.sharedCreatureColorHex, creatureHex);
        };
    }

    public static Resolved resolve(SBSConfig.DianaSettings cfg, DianaMarker type, String creatureHex) {
        return resolve(type, stored(cfg.appearance, type), colorHex(cfg, type, creatureHex));
    }

    /** The pure half: a stored style (possibly absent or half-filled) to the look it draws. */
    public static Resolved resolve(DianaMarker type, MarkerStyle style, String colorHex) {
        MarkerStyle s = style == null ? DEFAULT : style;
        return new Resolved(
                nonNull(colorHex),
                s.box == null ? DEFAULT.box : s.box,
                s.beam,
                s.labelSize == null ? DEFAULT.labelSize : s.labelSize,
                s.showDistance,
                Math.max(MarkerStyle.MIN_OPACITY, Math.min(100, s.opacity)),
                type.drawsThroughWallsToday() && s.throughWalls);
    }

    /**
     * A number that changes whenever anything a published marker carries changes, so the publisher
     * can tell a restyle from an unchanged tick and republish only then.
     */
    public static int stamp(SBSConfig.DianaSettings cfg) {
        int hash = 1;
        for (DianaMarker type : DianaMarker.values()) {
            hash = 31 * hash + resolve(cfg, type, "").hashCode();
        }
        SBSConfig.DianaAppearanceSettings a = cfg.appearance;
        if (a != null) {
            hash = 31 * hash + Objects.hash(a.rareCreatureColorHex, a.sharedCreatureColorHex);
        }
        return hash;
    }

    private static String nonNull(String hex) {
        return hex == null ? "" : hex;
    }

    private static String orElse(String override, String fallback) {
        return override == null || override.isBlank() ? nonNull(fallback) : override;
    }
}
