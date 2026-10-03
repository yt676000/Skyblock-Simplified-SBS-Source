/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.carry;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Carry Tickets module (Quality of Life): open a carry ticket (Dungeons F0-M7, Slayer Eman/Blaze
 * T1-T4, Kuudra Basic-Infernal), chat with your carrier, and - as a verified carrier - claim
 * tickets matching your server-assigned role. The OWNER manages carriers and sees everything.
 *
 * <p>Identity: carrier tags in the ticket chat are assigned by the server, not by this client.
 * Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class CarryModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public CarryModule() {
    }

    @Override
    public String id() {
        return "carry_tickets";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.PARTY_CHAT;
    }

    @Override
    public String displayName() {
        return "Carry Tickets";
    }

    @Override
    public String description() {
        return "Open carry tickets (Dungeons, Slayers, Kuudra) with verified carriers and per-ticket chat";
    }

    @Override
    public int accentColor() {
        return 0xFFE0A14D;
    }

    private static SBSConfig.CarrySettings cfg() {
        return ConfigManager.getInstance().get().carry;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /** Opens the ticket screen (settings button + the in-world keybind both land here). */
    public static void openScreen() {
        if (!cfg().enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreenAndShow(new sbs.modid.client.combat.carry.ui.CarryScreen(null));
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Carry Tickets", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("The carry-ticket system: open a ticket for the carry you want "
                                + "(Dungeons, Slayer or Kuudra), chat with your carrier in it, and "
                                + "carriers claim tickets that match their verified role. Needs "
                                + "your SBS licence token.")
                        .licenced(),
                SettingRow.label("Carry tickets with verified carriers - needs a licence token"),

                SettingRow.button("Open Carry Tickets", CarryModule::openScreen)
                        .describe("Opens the carry-ticket screen where you create, browse and chat "
                                + "in tickets."),
                SettingRow.keybind("Open Key", () -> cfg().openKey,
                        key -> { cfg().openKey = key; save(); })
                        .describe("A keyboard key that opens the carry-ticket screen directly while "
                                + "playing. Click the row, then press the key you want; Esc "
                                + "removes the binding."),
                SettingRow.label("Carrier tags are verified by the SBS server, never by the mod"));
    }
}
