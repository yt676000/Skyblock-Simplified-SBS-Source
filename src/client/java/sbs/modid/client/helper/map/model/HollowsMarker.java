/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.model;

/**
 * A spot the player marked by hand on the Crystal Hollows map ({@code /sbs chmap mark <label>}).
 *
 * <p><b>Local only.</b> A marker is the player's own note and is never sent anywhere - not to
 * Structure Sharing, not to the backend. {@code HollowsMarkerPrivacyTest} fails the build if any
 * class carrying a network disclosure block refers to this type.
 */
public record HollowsMarker(String label, int x, int y, int z, long createdAt) {
}
