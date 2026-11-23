/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.trade;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.economy.trade.logic.TradeGuardRuntime;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/** Trade Guard: warns about swapped and bait-and-switched items in the player trade menu. */
public final class TradeGuardModule implements SbsModule {

    public TradeGuardModule() {
    }

    @Override
    public String id() {
        return "trade_guard";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.ECONOMY;
    }

    @Override
    public String displayName() {
        return "Trade Guard";
    }

    @Override
    public String description() {
        return "Warns when a trade partner swaps an item for a look-alike worth less";
    }

    @Override
    public int accentColor() {
        return 0xFFFF5A4D;
    }

    private static SBSConfig.TradeGuardSettings cfg() {
        return ConfigManager.getInstance().get().tradeGuard;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Trade Guard", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Watches the player trade menu. Frames the partner's item in red "
                                + "when it was swapped for a look-alike (Hyperion for Astraea, 5✪ for "
                                + "0✪, a level 100 pet for level 1, fewer coins), and compares what you "
                                + "give with what you get. It never clicks or trades for you."),
                SettingRow.intField("Warn Below Value", 1, 90, () -> cfg().thresholdPct,
                        v -> { cfg().thresholdPct = v; save(); }, "%")
                        .describe("Warn when what you get is worth this many percent less than what "
                                + "you give. Values are the same estimates the item value tooltip "
                                + "uses; items with no known price show \"Value unknown\"."),
                SettingRow.toggle("Warning Sound", () -> cfg().sound,
                        () -> { cfg().sound = !cfg().sound; save(); })
                        .describe("Plays a low note with every Trade Guard chat warning."),
                SettingRow.toggle("Accept Lock", () -> cfg().acceptLock,
                        () -> { cfg().acceptLock = !cfg().acceptLock; save(); })
                        .describe("After a swap, a large value gap or the deal timer ending, your "
                                + "click on the accept button is ignored for a few seconds. This adds to "
                                + "Hypixel's own timer. Shift-click accepts anyway."),
                SettingRow.intField("Lock Length", 1, 10, () -> cfg().lockSeconds,
                        v -> { cfg().lockSeconds = v; save(); }, "s")
                        .describe("How long the accept lock holds."),
                SettingRow.toggle("Warn On Any Change After Confirm", () -> cfg().warnAnyChangeAfterConfirm,
                        () -> { cfg().warnAnyChangeAfterConfirm = !cfg().warnAnyChangeAfterConfirm; save(); })
                        .describe("On: any change to their side after they confirmed (or while a deal "
                                + "timer ran) warns. Off: only items taken away or swapped warn."),
                SettingRow.intField("Remember Partners For", 5, 240, () -> cfg().memoryMinutes,
                        v -> { cfg().memoryMinutes = v; save(); }, "min")
                        .describe("How long the items a partner showed are remembered for the "
                                + "bait-and-switch check. Kept in memory only, never written to disk."),
                SettingRow.toggle("Learn From Chat Item Links", () -> cfg().learnFromChat,
                        () -> { cfg().learnFromChat = !cfg().learnFromChat; save(); })
                        .describe("Items a player links in chat count as shown, so a cheaper "
                                + "look-alike in a later trade with them is flagged."),
                SettingRow.button("Forget Shown Items", () ->
                        TradeGuardRuntime.getInstance().guard().memory().clear())
                        .describe("Clears what partners have shown so far."));
    }
}
