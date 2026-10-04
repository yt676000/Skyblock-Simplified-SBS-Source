/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.transparency;

/**
 * The fade carried on an entity render state ({@code EntityRenderStateMixin}): the state has no
 * entity identity at submit time, so the decision made during extraction travels with it.
 * {@link OwnPlayerTransparency#OPAQUE} = untouched.
 */
public interface OwnPlayerAlpha {

    int sbs$ownAlpha();

    void sbs$setOwnAlpha(int alpha);
}
