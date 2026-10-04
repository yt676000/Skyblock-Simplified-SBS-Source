/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.model;

import sbs.modid.client.core.config.share.Kind;
import sbs.modid.client.core.config.share.Shareable;
import sbs.modid.client.core.pathfinding.MarkerBox;
import sbs.modid.client.core.pathfinding.MarkerLabelSize;

/**
 * The stored look of one Diana marker type. Every initialiser is what the marker drew before the
 * appearance settings existed, so an absent object - any config written by an older build - draws
 * unchanged.
 *
 * <p>Colour is not here: the four burrow and guess colours keep the fields they always had on
 * {@code DianaSettings}, and the two creature overrides sit on {@code DianaAppearanceSettings}.
 * Read through {@code DianaMarkerStyles.resolve}, never directly, so a null enum from a hand-edited
 * file falls back to the default instead of reaching the renderer.
 */
public final class MarkerStyle {

    /** Lowest opacity the slider offers; below it a marker is easier to switch off than to see. */
    public static final int MIN_OPACITY = 10;

    @Shareable(value = Kind.ENUM, enumType = MarkerBox.class)
    public MarkerBox box = MarkerBox.OUTLINE;

    @Shareable(Kind.BOOL)
    public boolean beam = true;

    @Shareable(value = Kind.ENUM, enumType = MarkerLabelSize.class)
    public MarkerLabelSize labelSize = MarkerLabelSize.NORMAL;

    @Shareable(Kind.BOOL)
    public boolean showDistance = true;

    /** Percent, {@link #MIN_OPACITY}-100. */
    @Shareable(value = Kind.INT, min = MIN_OPACITY, max = 100)
    public int opacity = 100;

    /** Only honoured for a type that drew through walls already - see {@link DianaMarker}. */
    @Shareable(Kind.BOOL)
    public boolean throughWalls = true;
}
