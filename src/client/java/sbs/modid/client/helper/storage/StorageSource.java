/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

import sbs.modid.client.core.config.SBSConfig;

/**
 * One place items can live, as the search shows it: a stable id, the label the player reads
 * ("Ender Chest 3", "Chest 12", "Mining Sack"), its kind, and – where one exists – the command that
 * opens it.
 *
 * @param id           stable key for this exact storage (dedupes re-captures of the same container)
 * @param displayName  what the search prints as the location
 * @param kind         which family it belongs to, for filtering and sorting
 * @param openCommand  command that opens it ({@code null} when it cannot be opened on demand)
 */
public record StorageSource(String id, String displayName, Kind kind, String openCommand) {

    /**
     * The storage families the index understands. Adding a new one means adding a constant here,
     * a title rule in {@link StorageIndex}, and a toggle in
     * {@link SBSConfig.StorageSearchSettings#enabledFor} – nothing in the UI changes.
     */
    public enum Kind {
        INVENTORY("Inventory"),
        ENDER_CHEST("Ender Chest"),
        BACKPACK("Backpacks"),
        CHEST("Island Chests"),
        MUSEUM("Museum"),
        SACKS("Sacks"),
        VAULT("Personal Vault"),
        OTHER("Other");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** Whether this storage can be opened by a double-click. */
    public boolean canOpen() {
        return openCommand != null && !openCommand.isBlank();
    }
}
