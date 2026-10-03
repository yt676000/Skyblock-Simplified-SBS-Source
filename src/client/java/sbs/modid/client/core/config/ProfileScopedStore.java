/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config;

/**
 * A cache whose data belongs to <b>one Minecraft account + one SkyBlock profile</b> (storages,
 * loadouts, bazaar orders, …) rather than to the global SBS settings. Registered with
 * {@link ProfileContext}; when the player switches profile or account the context flushes the old
 * data to disk and reloads the new profile's, so nothing has to be re-scanned after a switch.
 */
public interface ProfileScopedStore {

    /** Write the current in-memory data to disk NOW (bypassing any save throttle). */
    void flushProfile();

    /** Drop the in-memory data and reload it from the current profile's files. */
    void reloadProfile();
}
