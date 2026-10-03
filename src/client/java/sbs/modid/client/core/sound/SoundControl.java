/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.sound;

import net.minecraft.resources.Identifier;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a sound may play and how loud - the Sound Manager module's brain.
 *
 * <p><b>This runs on the sound engine's hot path</b>, once per sound instance, which on a busy
 * server is a lot of calls per second. So the config's list and map are compiled into a
 * {@link HashSet} and a {@link HashMap} and every query is one hash lookup - no list scan, and
 * <b>no allocation</b>. The compiled form is rebuilt only when the config actually changes, which
 * the settings page signals through {@link #invalidate()}.
 *
 * <p>The tables are keyed by {@link Identifier}, not by its string form, and that is the whole
 * reason they are: {@code Identifier.toString()} concatenates namespace and path on <i>every</i>
 * call (verified in the 26.2 bytecode - there is no cached field), so keying by string would
 * allocate one throwaway {@code String} per sound played. Parsing happens once, here, when the
 * tables are compiled.
 *
 * <p><b>Whitelist mode silences everything not listed</b> - including UI clicks and warning cues.
 * Nothing is implicitly exempt: an exemption list would be this class deciding which sounds a player
 * is not allowed to mute. The settings page makes the consequence explicit and asks for confirmation
 * instead, so a silent game is always something the player chose entry by entry.
 */
public final class SoundControl {

    /** Volume percent meaning "unchanged" - the common case, so it costs no map entry. */
    private static final int UNCHANGED = 100;

    /** Compiled from the config; {@code null} while it needs rebuilding. */
    private static Set<Identifier> listed;
    private static Map<Identifier, Float> volumes;

    /** Whether the compiled state came from an enabled config - saves re-reading the flag. */
    private static boolean active;
    private static boolean whitelist;

    private SoundControl() {
    }

    /** Drops the compiled state; the next query rebuilds it. Called whenever a setting changes. */
    public static synchronized void invalidate() {
        listed = null;
        volumes = null;
    }

    /** Rebuilds the lookup tables from the config if needed. */
    private static synchronized void ensureCompiled() {
        if (listed != null) {
            return;
        }
        SBSConfig.SoundSettings cfg = ConfigManager.getInstance().get().sounds;
        active = cfg.enabled;
        whitelist = cfg.whitelistMode;

        Set<Identifier> compiledListed = new HashSet<>();
        if (cfg.listed != null) {
            for (String id : cfg.listed) {
                // tryParse, not parse: a hand-edited config must not take the sound engine down.
                Identifier parsed = Identifier.tryParse(id);
                if (parsed != null) {
                    compiledListed.add(parsed);
                }
            }
        }
        listed = compiledListed;

        Map<Identifier, Float> compiledVolumes = new HashMap<>();
        if (cfg.volumes != null) {
            for (Map.Entry<String, Integer> entry : cfg.volumes.entrySet()) {
                Integer value = entry.getValue();
                Identifier parsed = Identifier.tryParse(entry.getKey());
                if (parsed != null && value != null && value != UNCHANGED) {
                    // Pre-divided, so the hot path multiplies and never boxes an Integer.
                    compiledVolumes.put(parsed, Math.max(0, Math.min(200, value)) / 100.0f);
                }
            }
        }
        volumes = compiledVolumes;
    }

    /**
     * Whether this sound is allowed to play at all.
     *
     * @param id the sound's registry id, e.g. {@code minecraft:block.note_block.pling}
     */
    public static boolean allowed(Identifier id) {
        ensureCompiled();
        if (!active) {
            return true;
        }
        boolean isListed = listed.contains(id);
        return whitelist ? isListed : !isListed;
    }

    /**
     * The volume multiplier for this sound, as a fraction. {@code 1.0} for anything the player has
     * not given an explicit volume, which is almost everything.
     */
    public static float volumeFactor(Identifier id) {
        ensureCompiled();
        if (!active || volumes.isEmpty()) {
            return 1.0f;
        }
        Float factor = volumes.get(id);
        return factor == null ? 1.0f : factor;
    }

    /** Whether the module is on at all - lets the mixin skip its work entirely while it is off. */
    public static boolean enabled() {
        ensureCompiled();
        return active;
    }
}
