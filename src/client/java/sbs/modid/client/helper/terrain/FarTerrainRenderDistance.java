/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.terrain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.mixin.OptionInstanceAccessor;

/**
 * Raises Minecraft's own render-distance slider to {@value FarTerrainManager#MAX_RADIUS} so far
 * terrain can actually be drawn.
 *
 * <p><b>Why the vanilla slider and not a second one.</b> The renderer sizes everything - the section
 * grid, the occlusion walk, the fog - from that one number. A private "SBS render distance" would
 * have to fight it every frame; widening the real option means the whole renderer simply agrees.
 *
 * <p><b>What the extra range costs.</b> The section grid is allocated as
 * {@code (2r+1)² × heightInSections} slots, so it grows with the square of the distance: 32 is about
 * a hundred thousand slots, 128 about 1.6 million (a ~13 MB reference array; the sections themselves
 * are only created where terrain exists, which on a SkyBlock island is a small fraction). It is
 * affordable on a strong machine and punishing on a weak one, which is exactly why it is opt-in and
 * why the module's Performance mode exists.
 *
 * <p>The widening is applied once, lazily, and only while the module is on - a fresh install, or the
 * module switched off, leaves the vanilla option untouched at its stock maximum.
 */
public final class FarTerrainRenderDistance {

    /** Vanilla's own ceiling; up to here the terrain is real server-streamed chunks. */
    public static final int VANILLA_MAX = 32;

    private static boolean widened;
    private static int stockMax = VANILLA_MAX;

    /** The ceiling currently applied to the slider, so a change of ceiling is noticed. */
    private static int appliedMax;

    /** Whether the value saved by this mod has been put back yet this launch. */
    private static boolean restored;

    /**
     * The last value seen on the option, so a change made by the <i>player</i> is told apart from
     * one this class just made. Without it, restoring a value that has to be clamped (the stored
     * 128 with Uncapped now off) would immediately record the clamped number over the real
     * preference and lose it for good.
     */
    private static int lastKnown = -1;

    private FarTerrainRenderDistance() {
    }

    /**
     * Widens the slider if the module is on, restores the stock range if it is off, and keeps this
     * mod's own copy of the value in step. Cheap enough to call every tick: it only touches the
     * option when something actually differs.
     */
    public static void sync(boolean moduleEnabled) {
        OptionInstance<Integer> option = Minecraft.getInstance().options.renderDistance();
        // Tracked by the wanted ceiling, not just on/off: toggling Uncapped changes the ceiling
        // while the module stays on, and a boolean guard would never notice.
        int wantedMax = sbs.modid.client.core.config.ConfigManager.getInstance()
                .get().farTerrain.uncapped
                ? FarTerrainManager.MAX_RADIUS : FarTerrainManager.MAX_LIVE_RADIUS;
        if (moduleEnabled != widened || (moduleEnabled && wantedMax != appliedMax)) {
            applyRange(option, moduleEnabled, wantedMax);
        }
        if (moduleEnabled && widened) {
            restoreOnce(option, wantedMax);
        }
        // Unconditional, and that is the point: this row IS Minecraft's render distance, so moving
        // either slider has to move the other and both have to persist. Gating the mirror on the
        // module being on meant that with it off - the default - the two silently drifted apart, and
        // switching the module on then restored the stale stored number over the player's real one.
        mirrorPlayerChange(option);
    }

    /**
     * Puts back the render distance this mod recorded, then watches for the player changing it.
     *
     * <p>This exists because Minecraft throws the value away. {@code options.txt} is parsed in the
     * {@code Options} constructor - before any mod runs, so before the slider has been widened - and
     * {@code OptionInstance.set} does not clamp a value outside its range, it substitutes the
     * option's <i>default</i>. A 64-chunk distance therefore comes back as vanilla's 12, not as 32,
     * and the setting silently un-does itself on every launch. The only durable copy is our own.
     */
    private static void restoreOnce(OptionInstance<Integer> option, int max) {
        if (restored) {
            return;
        }
        restored = true;
        SBSConfig.FarTerrainSettings settings = settings();
        if (settings.renderDistance > 0) {
            int wanted = Math.min(settings.renderDistance, max);
            if (wanted != option.get()) {
                option.set(wanted);
                Minecraft.getInstance().options.save();
            }
        }
        lastKnown = option.get();
    }

    /**
     * Copies a change the player made on <b>Minecraft's</b> slider into this mod's saved value, so
     * the two never disagree and neither has to know about the other.
     *
     * <p><b>When the live option can be believed.</b> Only while the slider can actually represent
     * the value we hold. With the module off the slider tops out at vanilla's ceiling, so if the
     * stored preference is above it the number showing is a substitute Minecraft picked - either the
     * clamp {@link #applyRange} just applied, or the option's <i>default</i>, which is what
     * {@code OptionInstance.set} silently uses for an out-of-range value read from {@code
     * options.txt}. Recording either would quietly destroy a 64- or 128-chunk preference the moment
     * the module was switched off, or on the next launch. A stored value the slider can hold is a
     * real choice and is mirrored.
     */
    private static void mirrorPlayerChange(OptionInstance<Integer> option) {
        SBSConfig.FarTerrainSettings settings = settings();
        if (!optionIsBelievable(widened, settings.renderDistance, stockMax)) {
            return;
        }
        int now = option.get();
        if (now == lastKnown) {
            return;
        }
        lastKnown = now;
        if (settings.renderDistance == now) {
            return;   // already agreed - no config write, so this stays free on the tick path
        }
        settings.renderDistance = now;
        sbs.modid.client.core.config.ConfigManager.getInstance().save();
    }

    /**
     * Records a value the player set on <b>this mod's</b> row, and puts it on Minecraft's option.
     *
     * <p>The other direction of the same contract. An explicit move of our slider is always the
     * player's choice - it needs none of the "can the option be believed" reasoning above, because
     * nobody substituted anything - so it is written to both sides and saved to both files here
     * rather than waiting for the next tick to notice.
     */
    public static void setFromPlayer(int value) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.options.renderDistance().set(value);
        minecraft.options.save();
        lastKnown = minecraft.options.renderDistance().get();
        settings().renderDistance = lastKnown;
        sbs.modid.client.core.config.ConfigManager.getInstance().save();
    }

    /**
     * Whether the number currently on Minecraft's option is the player's choice, or a substitute.
     *
     * <p>Pulled out as a pure function because it is the whole safety of the mirror and it has no
     * symptom when it is wrong: recording a substitute writes a smaller number over a real
     * preference, and the preference is gone with nothing logged and nothing to compare against.
     *
     * @param widened   whether the slider has been widened past vanilla's ceiling
     * @param stored    the preference this mod has saved, or {@code 0} when it has none
     * @param stockMax  vanilla's own ceiling
     */
    static boolean optionIsBelievable(boolean widened, int stored, int stockMax) {
        // Widened: the slider spans the whole range, so whatever it reads is what was chosen.
        // Not widened: only a stored value the slider could actually hold can be trusted - above the
        // ceiling, Minecraft is showing its own substitute for a value it refused to load.
        return widened || stored <= stockMax;
    }

    private static SBSConfig.FarTerrainSettings settings() {
        return sbs.modid.client.core.config.ConfigManager.getInstance().get().farTerrain;
    }

    @SuppressWarnings("unchecked")
    private static void applyRange(OptionInstance<Integer> option, boolean moduleEnabled,
                                   int wantedMax) {
        OptionInstanceAccessor<Integer> access =
                (OptionInstanceAccessor<Integer>) (Object) option;
        appliedMax = moduleEnabled ? wantedMax : 0;
        if (moduleEnabled) {
            if (access.skyblockSimplified$values() instanceof OptionInstance.IntRange range) {
                if (!widened) {
                    // Only the FIRST widen sees vanilla's own ceiling. Re-widening (the Uncapped
                    // toggle) would otherwise record 64 as "stock" and never restore 32.
                    stockMax = range.maxInclusive();
                }
                // Widened to the LIVE ceiling, not the memory slider's 128: past that the renderer
                // cannot keep up and a server transfer stalls tearing it all down again. Uncapped
                // mode is the player explicitly accepting that, so it gets the full range.
                access.skyblockSimplified$setValues(
                        new OptionInstance.IntRange(range.minInclusive(), wantedMax,
                                range.applyValueImmediately()));
                widened = true;
            }
            return;
        }
        if (access.skyblockSimplified$values() instanceof OptionInstance.IntRange range) {
            access.skyblockSimplified$setValues(
                    new OptionInstance.IntRange(range.minInclusive(), stockMax,
                            range.applyValueImmediately()));
        }
        // A value set past the vanilla ceiling has to come back down with the range, or the option
        // holds a number its own slider can no longer represent.
        if (option.get() > stockMax) {
            option.set(stockMax);
        }
        widened = false;
        // Switching the module off clamps the value, and that clamp must never be mistaken for the
        // player choosing 32 - otherwise turning the module off and on again silently costs them the
        // distance they had. Forgetting what we last saw makes the next enable restore, not record.
        restored = false;
        lastKnown = -1;
    }

    /**
     * The live render distance, however far the slider now goes.
     *
     * <p>Falls back to the vanilla default with no client: the settings rows are also built by
     * offline tooling, and one label reading this is not worth failing a whole module's index for.
     */
    public static int current() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? VANILLA_MAX : minecraft.options.renderDistance().get();
    }

    /** How much of the current distance is real server terrain rather than remembered terrain. */
    public static int realPortion() {
        return Math.min(current(), VANILLA_MAX);
    }
}
