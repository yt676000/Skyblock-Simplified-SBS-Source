/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.particles;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.particles.logic.ParticlePresets;
import sbs.modid.client.helper.particles.model.ParticlePreset;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Particles module (Visuals): switch individual particle types off. Opens a searchable list of
 * every particle the game has, each with a checkbox - the point being that a few particle types
 * (other players' potion swirls, enchantment glyphs, explosion clouds) are all that stand between
 * you and seeing what you are fighting, and turning off exactly those beats turning off all of them.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class ParticlesModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public ParticlesModule() {
    }

    @Override
    public String id() {
        return "particles";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.VISUALS;
    }

    @Override
    public String displayName() {
        return "Particles";
    }

    @Override
    public String description() {
        return "Turn individual particle types off - searchable list with a checkbox per particle";
    }

    @Override
    public int accentColor() {
        return 0xFFB050FF;
    }

    private static SBSConfig.ParticleSettings cfg() {
        return ConfigManager.getInstance().get().particles;
    }

    private static void save() {
        ConfigManager.getInstance().save();
        ParticleFilter.invalidate();
    }

    /** The count line under the button, so the page says what is off without opening the list. */
    private static String summary() {
        int total = ParticleCatalog.all().size();
        int off = cfg().hiddenParticles == null ? 0 : cfg().hiddenParticles.size();
        return off == 0
                ? "All " + total + " particles enabled"
                : off + " of " + total + " particles turned off";
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Particles", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Master switch for the per-particle list below. Off restores every "
                                + "particle to vanilla instantly without clearing your selection, so "
                                + "you can compare with and without in one click."),
                SettingRow.label("Off restores all particles without losing your selection"),
                SettingRow.enumOptions("Preset", () -> cfg().preset,
                        ParticlePresets::apply, v -> v.displayName())
                        .describe("A ready-made selection, applied to the list below. All is "
                                + "vanilla; Reduced drops ambient scenery (leaves, drips, spores, "
                                + "smoke, rain, block dust); Combat clears what sits between you "
                                + "and the boss (potion swirls, enchant glyphs, hit sparks, "
                                + "explosions); Minimal keeps only what you act on (bobber, hit "
                                + "markers, drops, portals) and hides everything else, including "
                                + "particles a future game version adds; None switches all of them "
                                + "off. Picking a preset parks your own ticked list and Custom "
                                + "hands it straight back, so trying one out costs you nothing - "
                                + "and editing any single particle below flips you to Custom."),
                SettingRow.label(cfg().preset.description()),
                SettingRow.button("Select Particles...",
                        () -> Minecraft.getInstance().setScreenAndShow(new ParticleSelectionScreen(
                                sbs.modid.client.core.api.GuiStateManager.getInstance()
                                        .getCurrentScreen())))
                        .describe("Opens the searchable list of every particle type. A ticked box "
                                + "means the particle renders; untick one to switch it off. The "
                                + "Enable/Disable All buttons apply to whatever the search shows, so "
                                + "typing \"dust\" then Disable All switches off every dust particle."),
                SettingRow.label(summary()),
                SettingRow.button("Enable All Particles",
                        () -> ParticlePresets.apply(ParticlePreset.ALL))
                        .describe("Clears the whole selection - every particle type renders again. "
                                + "The same as picking the All preset, so your hand-made list is "
                                + "parked rather than lost: Custom brings it back."));
    }
}
