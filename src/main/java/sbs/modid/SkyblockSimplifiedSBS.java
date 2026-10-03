/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common mod initializer for Skyblock Simplified (SBS).
 *
 * <p>Skyblock Simplified is a client-side utility mod, so the bulk of the logic
 * lives in {@code sbs.modid.client.SkyblockSimplifiedSBSClient}. This common
 * initializer only holds shared constants and helpers.
 */
public class SkyblockSimplifiedSBS implements ModInitializer {

    public static final String MOD_ID = "skyblock-simplified-sbs";

    /** Shared logger – named after the mod id so log lines are easy to attribute. */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[SBS] Common initializer loaded.");
    }

    /** Creates an {@link Identifier} within this mod's namespace. */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
