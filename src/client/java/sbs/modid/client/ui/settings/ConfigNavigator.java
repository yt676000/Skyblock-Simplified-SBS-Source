/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings;

import net.minecraft.client.Minecraft;
import sbs.modid.client.ui.screen.SBSMainScreen;

/**
 * "Take me to that setting" – the one way anything in the mod navigates to a config option.
 *
 * <p>Three features need it: the favourites list jumping to an option's real home, the HUD editor
 * landing on the settings of the element under the cursor, and the overlay inspector opening the
 * option behind a HUD element. Each of them wants the same three things to happen – open the right
 * module, scroll the option into view, mark it so the eye finds it – and each of them getting that
 * slightly differently is how they drift apart.
 *
 * <p>The request is handed over as a pending target rather than pushed into a live screen: the
 * caller usually has no screen open at all, and the config screen resolves its own layout in
 * {@code init()}, which is the only moment the scroll position can be worked out.
 */
public final class ConfigNavigator {

    /** How long a jumped-to row stays marked. Long enough to find, short enough not to nag. */
    public static final long HIGHLIGHT_MS = 2500;

    private static String pendingModuleId;
    private static String pendingOptionId;
    private static int pendingRowIndex = -1;

    private ConfigNavigator() {
    }

    /**
     * Opens the config on the option with this id, scrolled to it and briefly highlighted.
     *
     * <p>Returns {@code false} when nothing answers to the id – a removed feature, or a rename with
     * no alias behind it – in which case nothing is opened and the caller can say so instead of
     * dropping the player on an unrelated page.
     */
    public static boolean jumpTo(String optionId) {
        OptionIndex.Entry entry = OptionIndex.byId(optionId);
        if (entry == null) {
            return false;
        }
        pendingModuleId = entry.moduleId();
        pendingOptionId = entry.optionId();
        pendingRowIndex = entry.rowIndex();
        Minecraft.getInstance().setScreenAndShow(new SBSMainScreen());
        return true;
    }

    /**
     * Opens the config on a whole module, with no row singled out – for callers that know the
     * feature but not the individual option.
     */
    public static void jumpToModule(String moduleId) {
        pendingModuleId = moduleId;
        pendingOptionId = null;
        pendingRowIndex = -1;
        Minecraft.getInstance().setScreenAndShow(new SBSMainScreen());
    }

    /** Sets the target for a config screen that is about to be built by the caller itself. */
    public static void prepare(String moduleId, String optionId, int rowIndex) {
        pendingModuleId = moduleId;
        pendingOptionId = optionId;
        pendingRowIndex = rowIndex;
    }

    /** The module the next config screen should open on, or {@code null}. Not consumed by reading. */
    public static String pendingModuleId() {
        return pendingModuleId;
    }

    public static String pendingOptionId() {
        return pendingOptionId;
    }

    public static int pendingRowIndex() {
        return pendingRowIndex;
    }

    /**
     * Clears the target once a screen has acted on it, so reopening the config later lands where the
     * player left it rather than replaying an old jump.
     */
    public static void consume() {
        pendingModuleId = null;
        pendingOptionId = null;
        pendingRowIndex = -1;
    }
}
