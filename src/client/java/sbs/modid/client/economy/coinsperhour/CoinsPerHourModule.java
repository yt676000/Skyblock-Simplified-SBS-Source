/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.coinsperhour;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.economy.coinsperhour.logic.CoinsPerHourTracker;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Coins per Hour (Economy): what the purse earned per active hour this session, whatever the player
 * is doing, with spending and bank moves kept out of the rate. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}. Read-only.
 */
public final class CoinsPerHourModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public CoinsPerHourModule() {
    }

    @Override
    public String id() {
        return "coins_per_hour";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.ECONOMY;
    }

    @Override
    public String displayName() {
        return "Coins per Hour";
    }

    @Override
    public String description() {
        return "Your purse earnings per active hour this session, with spending and bank moves kept apart";
    }

    @Override
    public int accentColor() {
        return 0xFFFFC94A;
    }

    private static SBSConfig.CoinsPerHourSettings cfg() {
        return ConfigManager.getInstance().get().coinsPerHour;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Coins per Hour", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("A card with what your purse earned per hour of active play this "
                                + "session. Buying things, bank deposits and withdrawals and refunded "
                                + "Bazaar orders are shown separately and never count as earnings. "
                                + "Default: off."),
                SettingRow.intField("Idle After", 1, 60, () -> cfg().idleMinutes,
                        value -> { cfg().idleMinutes = value; save(); }, "min")
                        .describe("With no purse change and no movement for this long, the clock "
                                + "stops until you move again, so a break does not drag the rate "
                                + "down. Default: 3 min."),
                SettingRow.toggle("Show Breakdown", () -> cfg().showBreakdown,
                        () -> { cfg().showBreakdown = !cfg().showBreakdown; save(); })
                        .describe("Lists earned and spent by where it came from: Bazaar, Auction, "
                                + "NPC, Purchases, and Unexplained - purse changes no chat line "
                                + "accounted for, such as coins from mobs. Default: on."),
                SettingRow.toggle("Bazaar Claims Are Earnings", () -> cfg().bazaarClaimsEarn,
                        () -> { cfg().bazaarClaimsEarn = !cfg().bazaarClaimsEarn; save(); })
                        .describe("Counts coins you claim from a filled sell offer as earned. Off, "
                                + "they are shown as moved instead - useful when the items were "
                                + "bought before this session. Default: on."),
                SettingRow.toggle("Auction Claims Are Earnings", () -> cfg().auctionClaimsEarn,
                        () -> { cfg().auctionClaimsEarn = !cfg().auctionClaimsEarn; save(); })
                        .describe("Counts coins you collect from a sold auction as earned. Off, "
                                + "they are shown as moved instead. Default: on."),
                SettingRow.button("Reset Session", () -> CoinsPerHourTracker.getInstance().reset())
                        .describe("Starts the session again from zero. Same as /sbs cph reset. "
                                + "Switching profile resets it on its own."),
                SettingRow.button("Move / Resize Card", () -> net.minecraft.client.Minecraft.getInstance()
                        .setScreenAndShow(new HudEditorScreen(
                                new HudElement[] {HudElement.COINS_PER_HOUR}, "Edit Coins per Hour")))
                        .describe("Opens the editor where you drag the card anywhere on the screen "
                                + "and scale it."));
    }
}
