/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.party;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.social.party.ui.PartyOverlayScreen;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Party Overlay module (Party &amp; Chat): a keybindable panel of party-command buttons - warp,
 * transfer, promote, kick, mute, leave, disband - so the common party actions never have to be typed,
 * plus a favourites list for one-click invites of the players you group up with often. Self-registered
 * via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class PartyOverlayModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public PartyOverlayModule() {
    }

    @Override
    public String id() {
        return "party_overlay";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.PARTY_CHAT;
    }

    @Override
    public String displayName() {
        return "Party Overlay";
    }

    @Override
    public String description() {
        return "A keybindable panel of party-command buttons plus a favourites invite list";
    }

    @Override
    public int accentColor() {
        return 0xFF4DC3E0;
    }

    private static SBSConfig.PartyOverlaySettings cfg() {
        return ConfigManager.getInstance().get().partyOverlay;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /** Opens the overlay (settings button + the in-world keybind both land here). */
    public static void openScreen() {
        if (!cfg().enabled) {
            return;
        }
        Minecraft.getInstance().setScreenAndShow(new PartyOverlayScreen());
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Party Overlay", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A screen full of party buttons - warp, transfer, invite, kick, "
                                + "per member - so the common party commands are one click instead "
                                + "of typing. Keep a favourites list for one-click invites."),
                SettingRow.label("Buttons for warp / transfer / invite, so party commands need no typing"),

                SettingRow.button("Open Party Overlay", PartyOverlayModule::openScreen)
                        .describe("Opens the party button screen."),
                SettingRow.keybind("Open Key", () -> cfg().openKey,
                        key -> { cfg().openKey = key; save(); })
                        .describe("A key that opens the party screen while playing. Click the row, "
                                + "press a key; Esc unbinds."),
                SettingRow.label("Favourites are managed inside the overlay itself"));
    }
}
