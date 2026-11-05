/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudraprofit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.kuudraprofit.logic.KuudraProfitTracker;
import sbs.modid.client.combat.kuudraprofit.ui.KuudraProfitScreen;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Kuudra Profit Tracker (Combat): profit per run, per session and per tier - chest loot less the key
 * the paid chest used. Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p>Display only: it reads chat and never clicks, opens or sends anything. Only the player's own
 * loot and run numbers are stored.
 */
public final class KuudraProfitModule implements SbsModule {

    private static final List<String> SIDES = List.of("Instant-sell", "Sell offer");
    private static final List<String> TIERS = List.of("All tiers", "T1", "T2", "T3", "T4", "T5");

    /** ServiceLoader needs a public no-arg constructor. */
    public KuudraProfitModule() {
    }

    @Override
    public String id() {
        return "kuudra_profit";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.COMBAT;
    }

    @Override
    public String displayName() {
        return "Kuudra Profit Tracker";
    }

    @Override
    public String description() {
        return "Profit per Kuudra run, per session and per tier: chest loot minus the key";
    }

    @Override
    public int accentColor() {
        return 0xFFE0662B;
    }

    private static SBSConfig.KuudraProfitSettings cfg() {
        return ConfigManager.getInstance().get().kuudraProfit;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Kuudra Profit Tracker", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Adds up what each Kuudra run earned - everything in the chests you "
                                + "claimed, essence included - less the key the paid chest used. A lost run "
                                + "counts as a run with no profit. Reads chat; sends nothing. On by default."),
                SettingRow.button("Open Run History", () -> open(new KuudraProfitScreen(current())))
                        .describe("Every stored run with its profit, a summary per tier, and the session "
                                + "reset. Also /sbs kuudraprofit."),

                SettingRow.label("— Card —"),
                SettingRow.toggle("Kuudra Profit Card", () -> cfg().card,
                        () -> { cfg().card = !cfg().card; save(); })
                        .describe("Last run, session total, profit per run and per hour, runs and the top "
                                + "drop. Shown in Kuudra's Hollow and on the Crimson Isle. On by default."),
                SettingRow.toggle("Show Card Everywhere", () -> cfg().showEverywhere,
                        () -> { cfg().showEverywhere = !cfg().showEverywhere; save(); })
                        .describe("Also shows the card on every other island. Off by default."),
                SettingRow.button("Move / Resize Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.KUUDRA_PROFIT}, "Edit Kuudra Profit Card")))
                        .describe("Opens the editor where you drag the card and scale it."),
                SettingRow.options("Tier Filter", () -> TIERS,
                        () -> TIERS.get(Math.max(0, Math.min(5, cfg().tierFilter))),
                        value -> { cfg().tierFilter = Math.max(0, TIERS.indexOf(value)); save(); })
                        .describe("Which tier the card's session figures and the history screen show. "
                                + "Default: All tiers."),

                SettingRow.label("— Counting —"),
                SettingRow.segmented("Bazaar Price", SIDES, () -> cfg().priceSide,
                        value -> { cfg().priceSide = value; save(); })
                        .describe("Instant-sell values Bazaar loot at what selling now pays; Sell offer at "
                                + "the lowest sell offer. Other items use lowest BIN. A run keeps the "
                                + "prices it was booked at. Items without a price are never counted as "
                                + "zero - the total gets a trailing +. Default: Instant-sell."),
                SettingRow.toggle("Key At Purchase Price", () -> cfg().keyAtPurchasePrice,
                        () -> { cfg().keyAtPurchasePrice = !cfg().keyAtPurchasePrice; save(); })
                        .describe("On: a paid chest costs what its key cost when you bought it (the price "
                                + "when the purchase message showed). Off: what a key costs today. A key "
                                + "bought before the tracker saw it always uses today's price. On by default."),

                SettingRow.label("— Data —"),
                SettingRow.button("Reset Session", () -> KuudraProfitTracker.getInstance().resetSession())
                        .describe("Starts the session total from zero. Stored runs and the all-time "
                                + "figures are kept."),
                SettingRow.button("Reset History & Totals", KuudraProfitModule::confirmReset)
                        .describe("Deletes this profile's stored Kuudra runs, totals and remembered keys. "
                                + "Asks first."));
    }

    private static Screen current() {
        return sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
    }

    /** History and totals; asks first. Returns to the screen it was opened from either way. */
    public static void confirmReset() {
        Screen back = current();
        open(new ConfirmScreen(yes -> {
            if (yes) {
                KuudraProfitTracker.getInstance().resetAll();
            }
            Minecraft.getInstance().setScreenAndShow(back);
        }, Component.literal("Reset Kuudra profit history?"),
                Component.literal("Every stored Kuudra run and the all-time totals of this profile are deleted. "
                        + "This cannot be undone.")));
    }

    private static void open(Screen screen) {
        Minecraft.getInstance().setScreenAndShow(screen);
    }
}
