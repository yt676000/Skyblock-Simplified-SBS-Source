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
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The runtime "should this particle be drawn" answer for the Particles module.
 *
 * <p><b>Why the config set is not read directly.</b> This is asked once per particle spawn, which on
 * a busy Hypixel lobby is hundreds of times a frame - a string lookup per call (building the
 * particle's id, lower-casing it, hashing it) would be real work in a hot path for a value that only
 * changes when the player ticks a checkbox. So the persisted ids are compiled once into an identity
 * set of the registry's own {@link ParticleType} objects, and the check becomes a reference hash.
 *
 * <p>The compiled set is rebuilt when {@link #invalidate()} is called (every edit in the selection
 * screen) and, as a safety net, whenever the backing config collection is swapped or changes size -
 * which covers a config profile switch replacing the whole settings object underneath us.
 */
public final class ParticleFilter {

    private static Set<ParticleType<?>> hidden = Collections.emptySet();

    /** The config collection the current compiled set was built from (identity, not contents). */
    private static Set<String> compiledFrom;
    private static int compiledSize = -1;
    private static boolean dirty = true;

    /**
     * Whether the compiled selection hides {@code block} - the registry id of the terrain dust. Kept
     * as its own flag because block-break dust never reaches {@code createParticle}: the level builds
     * those particles itself (see {@code BlockBreakParticleMixin}), so it asks this instead.
     */
    private static boolean blockHidden;

    /** The particle registry id block-break and mining-crack dust is filed under. */
    static final String BLOCK_ID = "minecraft:block";

    private ParticleFilter() {
    }

    private static SBSConfig.ParticleSettings cfg() {
        return ConfigManager.getInstance().get().particles;
    }

    /** Forces a rebuild before the next check - called whenever the selection is edited. */
    public static void invalidate() {
        dirty = true;
    }

    /**
     * Whether this particle type is switched off and must not spawn. Always {@code false} while the
     * module is disabled, so turning it off restores vanilla instantly without clearing anything.
     */
    public static boolean isHidden(ParticleType<?> type) {
        SBSConfig.ParticleSettings settings = cfg();
        if (!settings.enabled || type == null) {
            return false;
        }
        Set<String> ids = settings.hiddenParticles;
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        if (dirty || ids != compiledFrom || ids.size() != compiledSize) {
            rebuild(ids);
        }
        return hidden.contains(type);
    }

    /**
     * Whether the dust from breaking and mining blocks must not be drawn - the same "block" choice as
     * the per-type list, so the existing toggle, presets and saved selections cover it. One flag read
     * after the same staleness check {@link #isHidden} does; no allocation.
     */
    public static boolean blockDustHidden() {
        SBSConfig.ParticleSettings settings = cfg();
        if (!settings.enabled) {
            return false;
        }
        Set<String> ids = settings.hiddenParticles;
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        if (dirty || ids != compiledFrom || ids.size() != compiledSize) {
            rebuild(ids);
        }
        return blockHidden;
    }

    /**
     * Whether a stored selection names the {@code block} particle. Ids are stored either bare
     * ({@code block}, as the presets write them) or namespaced ({@code minecraft:block}); both mean
     * the same registry entry, exactly as {@link Identifier#tryParse} resolves them in {@link #rebuild}.
     */
    static boolean namesBlock(Set<String> ids) {
        if (ids == null) {
            return false;
        }
        for (String id : ids) {
            if (id != null && (BLOCK_ID.equals(id.trim()) || "block".equals(id.trim()))) {
                return true;
            }
        }
        return false;
    }

    /** Resolves the persisted ids back to registry objects. Unknown ids are simply skipped. */
    private static void rebuild(Set<String> ids) {
        Set<ParticleType<?>> compiled =
                Collections.newSetFromMap(new IdentityHashMap<>(Math.max(8, ids.size())));
        for (String id : ids) {
            ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.tryParse(id));
            if (type != null) {
                compiled.add(type);
            }
        }
        hidden = compiled;
        blockHidden = namesBlock(ids);
        compiledFrom = ids;
        compiledSize = ids.size();
        dirty = false;
    }
}
