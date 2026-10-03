/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.particles.model;

import java.util.Set;

/**
 * The ready-made particle selections offered by the Particles module.
 *
 * <p><b>Why presets at all.</b> The per-type list is complete but long - a hundred and twenty-odd
 * rows - and nobody wants to tick their way through it to answer "make Kuudra readable". Each preset
 * is one such answer, and the list stays there for the fine-tuning afterwards.
 *
 * <p><b>Why a rule and not a stored id set per preset.</b> A preset says <i>what it means</i>
 * ({@link Rule#KEEP_LISTED} = "only these carry information, drop the rest") and is resolved against
 * the live registry at apply time. That way a game update's new particles land on the side the
 * preset intends - hidden under Minimal, visible under Reduced - instead of quietly defaulting to
 * whatever a frozen list happened to omit. Same reasoning as
 * {@link sbs.modid.client.helper.particles.ParticleCatalog}, which reads the registry rather than a
 * hand-written list.
 *
 * <p>The id lists were checked against the Minecraft 26.2 particle registry; ids that a future
 * version removes are skipped rather than failing, so a stale entry costs nothing.
 *
 * <p>The idea of exposing every particle type as individually switchable comes from Sodium Extra;
 * the grouping below is our own and is chosen for what actually obscures a SkyBlock fight.
 */
public enum ParticlePreset {

    /** The player's own ticked list - presets never overwrite it, they park it and hand it back. */
    CUSTOM("Custom", "Your own selection from the list below", Rule.MANUAL, Set.of()),

    /** Vanilla: nothing hidden. */
    ALL("All", "Every particle renders, exactly like vanilla", Rule.SHOW_EVERYTHING, Set.of()),

    /**
     * The decorative, high-count ambient particles - leaves, drips, spores, smoke, rain, block
     * break dust. All scenery: none of it tells you anything, and it is where the particle budget
     * goes on a busy island.
     */
    REDUCED("Reduced", "Drops ambient scenery: leaves, drips, spores, smoke, rain, block dust",
            Rule.HIDE_LISTED, Set.of(
                    "ash", "white_ash", "crimson_spore", "warped_spore", "spore_blossom_air",
                    "falling_spore_blossom", "cherry_leaves", "pale_oak_leaves", "tinted_leaves",
                    "firefly", "mycelium", "composter", "infested", "snowflake", "sneeze",
                    "egg_crack", "wax_on", "wax_off", "scrape",
                    "dripping_water", "dripping_lava", "dripping_honey", "dripping_obsidian_tear",
                    "dripping_dripstone_water", "dripping_dripstone_lava",
                    "falling_water", "falling_lava", "falling_honey", "falling_nectar",
                    "falling_obsidian_tear", "falling_dripstone_water", "falling_dripstone_lava",
                    "landing_honey", "landing_lava", "landing_obsidian_tear",
                    "underwater", "bubble_column_up", "current_down", "nautilus", "dolphin",
                    "campfire_cosy_smoke", "campfire_signal_smoke", "white_smoke", "smoke",
                    "large_smoke", "cloud", "rain", "splash",
                    "block", "block_crumble", "falling_dust", "dust_plume",
                    "sculk_charge", "sculk_charge_pop", "sculk_soul")),

    /**
     * The ones that sit between you and the boss: other players' potion swirls, enchantment glyphs,
     * hit sparks, explosion clouds, firework and totem flashes. Ambient scenery is left alone -
     * this preset is about seeing the fight, not about frame rate.
     */
    COMBAT("Combat", "Clears the fight: potion swirls, enchant glyphs, hit sparks, explosions",
            Rule.HIDE_LISTED, Set.of(
                    "entity_effect", "effect", "instant_effect", "enchant", "enchanted_hit",
                    "crit", "damage_indicator", "sweep_attack", "explosion", "explosion_emitter",
                    "dragon_breath", "flash", "firework", "totem_of_undying", "elder_guardian",
                    "sonic_boom", "witch", "angry_villager", "squid_ink", "glow_squid_ink",
                    "poof", "large_smoke", "cloud")),

    /**
     * The inverse rule: everything off <i>except</i> the handful that carry information you act on -
     * your bobber, hit feedback, drops, warp portals. Anything the game adds later is hidden by
     * default, which is what "minimal" should mean.
     */
    MINIMAL("Minimal", "Only what you act on: bobber, hit markers, drops, hearts, portals",
            Rule.KEEP_LISTED, Set.of(
                    "fishing", "crit", "enchanted_hit", "damage_indicator", "happy_villager",
                    "heart", "note", "item", "portal", "flame", "totem_of_undying")),

    /** Every particle type off. */
    NONE("None", "No particles at all", Rule.HIDE_EVERYTHING, Set.of());

    /** How a preset's id list is turned into the hidden set. */
    public enum Rule {
        /** Hand the player their own parked selection back. */
        MANUAL,
        /** Hide nothing. */
        SHOW_EVERYTHING,
        /** Hide every type the registry knows. */
        HIDE_EVERYTHING,
        /** Hide exactly the listed ids. */
        HIDE_LISTED,
        /** Hide everything except the listed ids. */
        KEEP_LISTED
    }

    private final String displayName;
    private final String description;
    private final Rule rule;
    private final Set<String> paths;

    ParticlePreset(String displayName, String description, Rule rule, Set<String> paths) {
        this.displayName = displayName;
        this.description = description;
        this.rule = rule;
        this.paths = paths;
    }

    public String displayName() {
        return displayName;
    }

    /** The one-line explanation shown under the cycle row. */
    public String description() {
        return description;
    }

    public Rule rule() {
        return rule;
    }

    /**
     * The vanilla particle paths this preset names, without the {@code minecraft:} namespace - it is
     * added when resolving so the lists above stay readable.
     */
    public Set<String> paths() {
        return paths;
    }

    public ParticlePreset next() {
        ParticlePreset[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
