/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard;

/**
 * Whether the feature a page describes needs a licence.
 *
 * <p>This exists so the answer is stated rather than discovered. A showcase page for something the
 * player cannot use without paying is fine when it says so, and corrosive when it does not - and
 * either way it stays as skippable as every other page.
 */
public enum Tier {

    /** Works without a licence. Every client-side feature. */
    INCLUDED,

    /** Needs a licence token. The page renders a plain label saying so. */
    REQUIRES_LICENCE
}
