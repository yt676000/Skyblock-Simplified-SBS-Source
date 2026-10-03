/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.storage.StorageSearchScreen;

/**
 * Opens the central item search from its configurable in-world hotkey (default {@code O}).
 *
 * <p>Dispatched from {@code CommandKeyMixin}, which only fires on a fresh press with no screen open –
 * the same gate the other SBS hotkeys use, so the key can never fire while typing in chat or
 * browsing another menu.
 *
 * <p>The {@code openKey} setting exists only to open this search, so a bound press always opens it
 * (an unbound key – {@code 0} – does nothing). {@link StorageSearchScreen#open()} then turns on the
 * indexing the search needs, so a press is never a silent no-op regardless of the preview mode.
 */
public final class StorageSearchKeybind {

    private StorageSearchKeybind() {
    }

    /** Called for every fresh in-world key press. */
    public static void onKeyPressed(int keyCode) {
        int bound = ConfigManager.getInstance().get().storageSearch.openKey;
        if (bound == 0 || keyCode != bound) {
            return;
        }
        StorageSearchScreen.open();
    }
}
