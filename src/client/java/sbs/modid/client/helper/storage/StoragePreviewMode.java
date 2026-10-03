/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

/**
 * How the Skyblock Menu module surfaces stored items.
 *
 * <ul>
 *   <li>{@link #OFF} – nothing; no preview and no indexing.</li>
 *   <li>{@link #PREVIEW} – the classic hover preview of Ender Chest pages and Backpacks.</li>
 *   <li>{@link #FULL_UI} – the preview <i>plus</i> the central item search: every storage the
 *       client has seen is indexed and searchable from one screen.</li>
 * </ul>
 *
 * <p>{@link #FULL_UI} is a superset of {@link #PREVIEW}, so raising the setting only ever adds
 * behaviour – nothing that worked at a lower mode stops working at a higher one.
 */
public enum StoragePreviewMode {

    OFF("Off"),
    PREVIEW("Preview"),
    FULL_UI("Full UI");

    private final String displayName;

    StoragePreviewMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public StoragePreviewMode next() {
        StoragePreviewMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** Whether the hover preview should be drawn (true for both {@link #PREVIEW} and {@link #FULL_UI}). */
    public boolean showsPreview() {
        return this != OFF;
    }

    /** Whether storages should be indexed for the central search. */
    public boolean indexes() {
        return this == FULL_UI;
    }
}
