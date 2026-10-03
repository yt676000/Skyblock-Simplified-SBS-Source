/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.edit.model;

/**
 * How much of a HUD element its single opacity applied to, before the opacity was split into one
 * value per part.
 *
 * <p><b>Legacy only.</b> Nothing sets this any more: an element now carries a body, a frame and a
 * text/icon opacity independently, which covers every combination this enum could name and the many
 * it could not (a 30% plate under a fully solid readout, say). It survives purely so
 * {@link HudTransform#materialize()} can read a layout file written before the split and turn it
 * into the three values that mean the same thing. Deleting a constant here would silently reset
 * whatever opacity a player had configured under it, so the names are as fixed as any other id.
 *
 * @deprecated read on load and discarded – see {@link HudTransform#materialize()}.
 */
@Deprecated
public enum HudOpacityScope {

    /** Only the panel body. Frame, text and icons stayed fully solid. */
    BACKGROUND,

    /** The panel body and its frame / glow. Text and icons stayed fully solid. */
    BACKGROUND_OUTLINE,

    /** Everything the element draws, text and icons included. */
    ALL
}
