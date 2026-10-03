/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.particles;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Every particle type the game knows, as a searchable list for the Particles module.
 *
 * <p>The list is read from the particle <b>registry</b> rather than hand-written, so it is complete
 * by construction and picks up whatever a game update adds or renames - a hard-coded list would
 * quietly stop covering new particles and would still offer ones that no longer exist.
 *
 * <p>Built once on first use and cached: the registry is fixed after startup, and the settings
 * screen re-filters this list on every keystroke.
 */
public final class ParticleCatalog {

    /** One particle type: the registry object, its id, and the name shown in the list. */
    public record ParticleEntry(ParticleType<?> type, String id, String name) {
    }

    private static List<ParticleEntry> all;

    private ParticleCatalog() {
    }

    /** Every particle type, sorted by display name. */
    public static List<ParticleEntry> all() {
        if (all == null) {
            List<ParticleEntry> list = new ArrayList<>(256);
            for (ParticleType<?> type : BuiltInRegistries.PARTICLE_TYPE) {
                Identifier key = BuiltInRegistries.PARTICLE_TYPE.getKey(type);
                if (key != null) {
                    list.add(new ParticleEntry(type, key.toString(), prettyName(key)));
                }
            }
            list.sort(Comparator.comparing(ParticleEntry::name));
            all = List.copyOf(list);
        }
        return all;
    }

    /**
     * The entries matching {@code query}, which is matched against both the display name and the
     * full id - so "minecraft:flame", "flame" and "Flame" all find the same row. A blank query
     * returns everything.
     */
    public static List<ParticleEntry> search(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return all();
        }
        List<ParticleEntry> out = new ArrayList<>();
        for (ParticleEntry entry : all()) {
            if (entry.name().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.id().toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** "minecraft:angry_villager" → "Angry Villager"; a modded namespace is kept as a prefix. */
    private static String prettyName(Identifier key) {
        StringBuilder out = new StringBuilder(key.getPath().length());
        boolean upper = true;
        for (char c : key.getPath().toCharArray()) {
            if (c == '_') {
                out.append(' ');
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return "minecraft".equals(key.getNamespace())
                ? out.toString() : key.getNamespace() + ": " + out;
    }
}
