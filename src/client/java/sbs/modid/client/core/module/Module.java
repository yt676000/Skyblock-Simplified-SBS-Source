/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.module;

import net.minecraft.network.chat.Component;
import sbs.modid.client.core.module.Searchable;

import java.util.List;

/**
 * Foundation interface for a single feature module (e.g. "Auto-Sell", "Dungeon Map").
 *
 * <p>No concrete modules exist yet – this only establishes the contract every
 * future module will follow so that {@link ModuleCategory} and the search system
 * can treat them uniformly. A typical future module will extend a small abstract
 * base class implementing this interface plus enable/disable persistence.
 */
public interface Module extends Searchable {

    /** Stable, unique identifier (lowercase, no spaces). Used for config keys. */
    String id();

    /** Localized display name shown in the GUI. */
    Component displayName();

    /** Short description shown in tooltips / detail views. */
    Component description();

    /** The category this module belongs to. */
    String categoryId();

    /** Whether the module is currently active. Defaults to off. */
    default boolean isEnabled() {
        return false;
    }

    @Override
    default List<String> searchTerms() {
        return List.of(id(), displayName().getString(), description().getString());
    }
}
