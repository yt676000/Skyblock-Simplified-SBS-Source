/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.module;

import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * A module that registers itself.
 *
 * <p><b>Why this exists.</b> Adding a module used to mean editing three shared files – a
 * {@code register(...)} in {@link ModuleManager}, a {@code case} block in {@code ModuleSettings},
 * and a settings class in {@code SBSConfig}. Three files that every feature branch touches is three
 * merge conflicts per branch, and the {@code case} blocks in particular are 10-30 lines each, which
 * git cannot auto-merge.
 *
 * <p>A module implementing this interface is discovered automatically: it declares its own identity
 * <i>and</i> its own settings rows, in its own package. Adding one is a new file plus a single line
 * in {@code META-INF/services/sbs.modid.client.core.module.SbsModule} — and a one-line conflict is one
 * anybody can resolve.
 *
 * <p><b>How to add a module</b>
 * <ol>
 *   <li>Write a class implementing this interface, in your feature's package.</li>
 *   <li>Add its fully-qualified name to
 *       {@code src/client/resources/META-INF/services/sbs.modid.client.core.module.SbsModule}.</li>
 *   <li>If it needs persisted settings, put the settings class in your package too and add the one
 *       field to {@code SBSConfig} (Gson needs the field to exist there).</li>
 * </ol>
 * Nothing else. The sidebar, the search and the settings panel all pick it up on their own.
 *
 * <p>Implementations must be stateless and have a public no-arg constructor — {@link java.util.ServiceLoader}
 * instantiates them.
 */
public interface SbsModule {

    /** Stable id, used as the config key and by the settings panel. Never rename it. */
    String id();

    /** Which sidebar group it belongs to. */
    ModuleGroup group();

    /**
     * Which family inside {@link #group()} it is listed under, or {@code null} for the group's
     * trailing "General" bucket - see {@link ModuleSubgroup}. Must be a subgroup of this module's own
     * group; one belonging to another group is ignored with a log line.
     *
     * <p>Most groups have no subgroups and every module in them keeps this default. Declaring one in
     * such a group is what turns its sub-headers on, so do it only where the group really mixes
     * families.
     */
    default ModuleSubgroup subgroup() {
        return null;
    }

    /** Name shown on the module card. */
    String displayName();

    /** One-line description shown under the name (and matched by the search). */
    String description();

    /** Accent colour of the card, ARGB. Stay in the muted SBS palette. */
    default int accentColor() {
        return 0xFF3FB4FF;
    }

    /** The rows of this module's settings page. Empty for a module with nothing to configure. */
    default List<SettingRow> settings() {
        return List.of();
    }

    /**
     * Whether the module should be listed right now. Used by the Developer card, which only exists
     * while dev mode is on; almost every module wants the default.
     */
    default boolean visible() {
        return true;
    }
}
