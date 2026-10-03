/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.model;

/**
 * One pearl aim marker: a box hanging in the air that you throw an Ender Pearl at to land somewhere
 * specific, plus the pitch to throw it at.
 *
 * <p><b>Why a box in the sky and not a marker on the landing spot.</b> A pearl throw in Kuudra is
 * ballistic and long - you are not aiming at the platform you want, you are aiming at a point of empty
 * air whose arc ends there. The useful thing to draw is therefore the aim point, and the useful thing
 * to write on it is the pitch, because two throws from the same block to the same aim point land in
 * different places if you are looking up more steeply.
 *
 * <p><b>{@link #standX}/{@link #standY}/{@link #standZ} is the block the throw was recorded from.</b>
 * It is optional, and it is what makes a marker forgiving: standing one block off the recorded spot
 * moves the whole throw by one block, so the aim point is shifted by however far you are off it (see
 * {@link PearlArea#invertNorthSouth}). Without a stand block the marker is simply static.
 *
 * <p>Plain mutable fields with a no-arg constructor: these are read and written straight by Gson.
 */
public final class PearlPoint {

    /** Free-text note for the player's own benefit ("double pearl to square"). Never parsed. */
    public String note = "";

    /** The aim point. */
    public double x;
    public double y;
    public double z;

    /** The block this throw was recorded from, or {@code null} on all three for a static marker. */
    public Double standX;
    public Double standY;
    public Double standZ;

    /** Edge length of the drawn box. Small for a precise throw, large for a forgiving one. */
    public double size = 0.8;

    /** {@code RRGGBB}. Empty falls back to the module's configured pearl colour. */
    public String colorHex = "";

    /**
     * What is written on the marker - by convention the pitch to look at. When it parses as a number
     * and {@link #alert} is on, it is also compared against where you are actually looking.
     */
    public String label = "";

    /** Whether to call out when your pitch matches {@link #label}. Pointless without a numeric label. */
    public boolean alert = true;

    /** Show only while this many supplies are still missing ({@code 0} = always). */
    public int onlyPre;

    /** Hide while this many supplies are still missing ({@code 0} = never hide). */
    public int hidePre;

    /** Gson needs a no-arg constructor. */
    public PearlPoint() {
    }

    /** Whether a stand block was recorded, i.e. whether this marker moves with the player. */
    public boolean hasStand() {
        return standX != null && standY != null && standZ != null;
    }

    /** The numeric pitch on the label, or {@code null} when the label is not a number. */
    public Double pitch() {
        String text = label.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
