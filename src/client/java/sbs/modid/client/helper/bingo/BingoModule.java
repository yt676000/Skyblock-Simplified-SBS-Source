/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.bingo;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Bingo Card Overlay (Quality of Life): the Bingo card's goals on a HUD card, read from the Bingo Card
 * menu when it is opened.
 *
 * <p>Off by default, and it stays off until the menu has been captured on a real Bingo profile: every
 * menu shape it reads is {@code ESTIMATED} - see {@code docs/features/bingo-card-overlay.md}. The rows
 * say so. Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class BingoModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public BingoModule() {
    }

    @Override
    public String id() {
        return "bingo_card";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public String displayName() {
        return "Bingo Card Overlay";
    }

    @Override
    public String description() {
        return "Your Bingo card's open goals on screen, without opening the menu";
    }

    @Override
    public int accentColor() {
        return 0xFFFF9F43;
    }

    private static SBSConfig.BingoSettings cfg() {
        return ConfigManager.getInstance().get().bingo;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Bingo Card Overlay", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Reads your Bingo card whenever you open the Bingo Card menu (it "
                                + "never clicks anything) and keeps its goals on a card on screen, "
                                + "with how long ago it was read. Only on a Bingo profile, and only "
                                + "for this month's card. Stored per profile, so it is still there "
                                + "after a restart. Not checked on a real Bingo card yet, so it may "
                                + "read nothing until it has been. Default: off."),
                SettingRow.label("Not verified in game yet - open the Bingo Card menu once to fill it"),
                SettingRow.toggle("Show Completed Goals", () -> cfg().showCompleted,
                        () -> { cfg().showCompleted = !cfg().showCompleted; save(); })
                        .describe("Also list the goals you have already finished, struck through, "
                                + "after the open ones. Default: off."),
                SettingRow.toggle("Show Community Goals", () -> cfg().showCommunity,
                        () -> { cfg().showCommunity = !cfg().showCommunity; save(); })
                        .describe("List the community goals too, each with its progress line as the "
                                + "card last showed it. Default: on."),
                SettingRow.button("Move / Resize Card", () -> net.minecraft.client.Minecraft.getInstance()
                                .setScreenAndShow(new HudEditorScreen(
                                        new HudElement[] {HudElement.BINGO_CARD}, "Edit Bingo Card")))
                        .describe("Opens the editor where you drag the Bingo card anywhere on the "
                                + "screen and scale it."));
    }
}
