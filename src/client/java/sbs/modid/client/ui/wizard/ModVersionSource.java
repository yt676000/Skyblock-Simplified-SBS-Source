/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard;

import net.fabricmc.loader.api.FabricLoader;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.ui.wizard.model.ModVersion;

import java.util.Optional;

/**
 * The running mod's version, when it has one.
 *
 * <p>Split from {@link ModVersion} so the parsing stays testable without a loader, and empty is a
 * real answer rather than a failure: {@code fabric.mod.json} currently declares
 * {@code "version": "PreAlpha"}, so on this build there is no version to compare against and the
 * showcase correctly selects nothing. Onboarding is unaffected - it does not depend on a version.
 */
public final class ModVersionSource {

    private static Optional<ModVersion> cached;

    private ModVersionSource() {
    }

    /** The running version, or empty when the mod does not declare a parseable one. */
    public static synchronized Optional<ModVersion> current() {
        if (cached == null) {
            cached = read();
        }
        return cached;
    }

    private static Optional<ModVersion> read() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(SkyblockSimplifiedSBS.MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .flatMap(ModVersion::parse);
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Wizard] Could not read the mod version: {}",
                    t.toString());
            return Optional.empty();
        }
    }
}
