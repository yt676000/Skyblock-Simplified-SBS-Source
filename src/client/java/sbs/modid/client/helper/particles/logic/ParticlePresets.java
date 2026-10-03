/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.particles.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.particles.ParticleCatalog;
import sbs.modid.client.helper.particles.ParticleFilter;
import sbs.modid.client.helper.particles.model.ParticlePreset;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Turns a {@link ParticlePreset} into the hidden-id set the renderer actually reads, and keeps the
 * player's hand-made selection safe while they try presets out.
 *
 * <p><b>The parking rule.</b> {@code hiddenParticles} stays the single source of truth for
 * rendering - nothing else is consulted per frame. Applying a preset overwrites it, so before
 * leaving {@link ParticlePreset#CUSTOM} the current set is copied into {@code customHidden} and
 * handed straight back when Custom is picked again. Without that, trying "Minimal" once would
 * silently destroy a selection someone spent ten minutes ticking.
 *
 * <p>That snapshot also covers configs written before presets existed: they load as Custom with a
 * populated {@code hiddenParticles} and an empty {@code customHidden}, and the first preset switch
 * parks what they had rather than an empty set.
 *
 * <p><b>Any hand edit means Custom.</b> {@link #markCustom()} is called from every toggle in the
 * selection screen, because a preset label over a set that no longer matches it is a lie - and the
 * edit itself becomes the new parked selection.
 */
public final class ParticlePresets {

    private static final String VANILLA_NAMESPACE = "minecraft:";

    private ParticlePresets() {
    }

    private static SBSConfig.ParticleSettings cfg() {
        return ConfigManager.getInstance().get().particles;
    }

    private static void persist() {
        ConfigManager.getInstance().save();
        ParticleFilter.invalidate();
    }

    /**
     * Switches to {@code preset} and rebuilds the hidden set from it, parking the current selection
     * first if we are leaving Custom.
     */
    public static void apply(ParticlePreset preset) {
        SBSConfig.ParticleSettings settings = cfg();
        if (settings.preset == ParticlePreset.CUSTOM) {
            settings.customHidden = new LinkedHashSet<>(safe(settings.hiddenParticles));
        }
        settings.preset = preset;
        settings.hiddenParticles = resolve(preset, settings.customHidden);
        persist();
    }

    /** Advances to the next preset in the cycle. */
    public static void cycle() {
        apply(cfg().preset.next());
    }

    /**
     * Records that the selection was edited by hand: the preset becomes Custom and the edited set
     * becomes the parked one. Callers save afterwards, so this does not persist on its own.
     */
    public static void markCustom() {
        SBSConfig.ParticleSettings settings = cfg();
        settings.preset = ParticlePreset.CUSTOM;
        settings.customHidden = new LinkedHashSet<>(safe(settings.hiddenParticles));
    }

    /** The hidden ids a preset means, resolved against the particle types the registry has now. */
    public static Set<String> resolve(ParticlePreset preset, Set<String> parked) {
        Set<String> hidden = new LinkedHashSet<>();
        switch (preset.rule()) {
            case MANUAL -> hidden.addAll(safe(parked));
            case SHOW_EVERYTHING -> {
                // nothing hidden
            }
            case HIDE_EVERYTHING -> {
                for (ParticleCatalog.ParticleEntry entry : ParticleCatalog.all()) {
                    hidden.add(entry.id());
                }
            }
            case HIDE_LISTED -> {
                Set<String> named = qualify(preset.paths());
                for (ParticleCatalog.ParticleEntry entry : ParticleCatalog.all()) {
                    if (named.contains(entry.id())) {
                        hidden.add(entry.id());
                    }
                }
            }
            case KEEP_LISTED -> {
                Set<String> named = qualify(preset.paths());
                for (ParticleCatalog.ParticleEntry entry : ParticleCatalog.all()) {
                    if (!named.contains(entry.id())) {
                        hidden.add(entry.id());
                    }
                }
            }
        }
        return hidden;
    }

    /**
     * Resolving through the catalog rather than trusting the preset's list means the counter under
     * the list can never claim more particles are off than the game actually has - an id a future
     * version drops simply stops matching.
     */
    private static Set<String> qualify(Set<String> paths) {
        Set<String> out = new LinkedHashSet<>(paths.size());
        for (String path : paths) {
            out.add(path.indexOf(':') < 0 ? VANILLA_NAMESPACE + path : path);
        }
        return out;
    }

    private static Set<String> safe(Set<String> set) {
        return set == null ? Set.of() : set;
    }
}
