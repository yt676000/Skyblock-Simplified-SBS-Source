/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

import sbs.modid.client.core.api.ApiFailure;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The one place that decides how an {@link ApiFailure} looks. An error ({@link ApiFailure#isError()})
 * is drawn in the theme's warning red; a state the player chose or simply has - no licence, a
 * feature turned off in the privacy settings - is drawn as a muted note, never red.
 */
public final class FailureStyle {

    private FailureStyle() {
    }

    /** ARGB text colour for {@code failure}; muted for {@code null}. */
    public static int colour(ApiFailure failure) {
        return failure != null && failure.isError() ? SBSTheme.WARN : SBSTheme.TEXT_MUTED;
    }

    /**
     * The legacy formatting code for text whose colour lives in the string: {@code §c} for an error,
     * {@code §7} for a note.
     */
    public static String code(ApiFailure failure) {
        return failure != null && failure.isError() ? "§c" : "§7";
    }
}
