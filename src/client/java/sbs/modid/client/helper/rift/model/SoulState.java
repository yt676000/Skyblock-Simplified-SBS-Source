/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.model;

/**
 * What is known about one soul's collection state.
 *
 * <p><b>Three states, not two</b>, and the third one is the point. Hypixel's collection line gives an
 * authoritative <i>count</i> ("You have found 31/52 Souls"), and the client can only witness the
 * souls it was running for. When the count says 31 and the local record names 12, the other 19 are
 * not missing - they are <b>found by somebody the client was not watching</b>, and drawing them as
 * missing sends the player back to nineteen souls they already have.
 *
 * <p>Modelling that gap as {@link #UNKNOWN} rather than as {@link #MISSING} is what makes the feature
 * honest on an established profile. The field starts almost entirely unknown and converges as the
 * player collects, and every unknown that gets collected turns into a found - so the unknowns only
 * ever shrink.
 */
public enum SoulState {

    /** On record as collected - witnessed by this client, or named by the API. */
    FOUND,

    /**
     * Genuinely not collected. Only ever concluded when the record is <b>complete</b>: the number of
     * souls on record equals the number Hypixel says have been found, so there is nothing unaccounted
     * for and anything not on record really is still out there.
     */
    MISSING,

    /**
     * Not on record, but the count says more have been found than the record names - so this could be
     * either. Drawn differently from both, never as missing.
     */
    UNKNOWN
}
