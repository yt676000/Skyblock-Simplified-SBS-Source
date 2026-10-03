/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.module;

import sbs.modid.SkyblockSimplifiedSBS;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Finds every {@link SbsModule} on the classpath, so the menu lists modules nobody had to register.
 *
 * <p>Discovery is {@link ServiceLoader} against the mod's own classloader – Fabric loads the mod on
 * Knot, so {@code SbsModule.class.getClassLoader()} is the one that can see the implementations.
 *
 * <p>Loaded once and cached: {@code ServiceLoader} instantiates on every iteration, and the sidebar
 * asks for the module list on every frame it renders.
 *
 * <p>A module that throws while loading is skipped with a log line rather than taking the whole
 * menu down with it — one broken feature branch should not make the mod unusable for the other
 * developer.
 */
public final class ModuleRegistry {

    private static Map<String, SbsModule> modules;

    private ModuleRegistry() {
    }

    /** Every discovered module, by id, in discovery order. */
    public static synchronized Map<String, SbsModule> all() {
        if (modules == null) {
            modules = load();
        }
        return modules;
    }

    /** The discovered module with this id, or {@code null}. */
    public static SbsModule byId(String id) {
        return all().get(id);
    }

    /** Forgets the cache; only useful for tests. */
    public static synchronized void reload() {
        modules = null;
    }

    private static Map<String, SbsModule> load() {
        Map<String, SbsModule> found = new LinkedHashMap<>();
        List<String> broken = new ArrayList<>();
        try {
            ServiceLoader<SbsModule> loader =
                    ServiceLoader.load(SbsModule.class, SbsModule.class.getClassLoader());
            for (SbsModule module : loader) {
                try {
                    String id = module.id();
                    if (id == null || id.isBlank()) {
                        broken.add(module.getClass().getName() + " (no id)");
                        continue;
                    }
                    SbsModule previous = found.put(id, module);
                    if (previous != null) {
                        // Two modules claiming one id would silently shadow each other - say so.
                        SkyblockSimplifiedSBS.LOGGER.warn(
                                "[SBS][Modules] duplicate module id '{}': {} replaced {}",
                                id, module.getClass().getName(), previous.getClass().getName());
                    }
                } catch (Throwable t) {
                    broken.add(module.getClass().getName() + " (" + t + ")");
                }
            }
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Modules] module discovery failed", t);
        }
        if (!broken.isEmpty()) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Modules] skipped broken modules: {}", broken);
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Modules] discovered {} self-registered module(s): {}",
                found.size(), found.keySet());
        return found;
    }
}
