/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.profit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.dungeons.profit.logic.DungeonProfitTracker;
import sbs.modid.client.dungeons.profit.ui.DungeonProfitScreen;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Dungeon Profit Tracker (Dungeons): profit per run, per session and per floor - chest loot,
 * essence and rare drops, less chest prices, Kismet Feathers, Dungeon Potions and the player's own
 * per-run cost.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p>Display only: it reads chat and the chest menus the player opens, and never clicks, opens or
 * sends anything. Only the player's own loot and run numbers are stored.
 */
public final class DungeonProfitModule implements SbsModule {

    private static final List<String> SIDES = List.of("Instant-sell", "Sell offer");
    private static final List<String> POTION_SIDES = List.of("Market", "Purchase price");
    /** "" is stored for all floors; shown as the first option. */
    public static final String ALL_FLOORS = "All floors";
    public static final List<String> FLOORS;

    static {
        List<String> floors = new ArrayList<>();
        floors.add(ALL_FLOORS);
        floors.add("E");
        for (int i = 1; i <= 7; i++) {
            floors.add("F" + i);
        }
        for (int i = 1; i <= 7; i++) {
            floors.add("M" + i);
        }
        FLOORS = List.copyOf(floors);
    }

    /** ServiceLoader needs a public no-arg constructor. */
    public DungeonProfitModule() {
    }

    @Override
    public String id() {
        return "dungeon_profit";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.DUNGEONS;
    }

    @Override
    public String displayName() {
        return "Dungeon Profit Tracker";
    }

    @Override
    public String description() {
        return "Profit per run, per session and per floor, incl. chest costs, Kismet and potions";
    }

    @Override
    public int accentColor() {
        return 0xFFC9A227;
    }

    private static SBSConfig.DungeonProfitSettings cfg() {
        return ConfigManager.getInstance().get().dungeonProfit;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Dungeon Profit Tracker", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Adds up what each Catacombs run earned - the chests you opened, essence "
                                + "you found and rare drops - less what the chests cost to open, any "
                                + "Kismet Feathers used and the Dungeon Potions you drank before the "
                                + "start. Chests claimed later from Croesus are added to "
                                + "their run. Reads chat and the chest menus you open; sends nothing. "
                                + "On by default."),
                SettingRow.button("Open Run History", () -> open(new DungeonProfitScreen(current())))
                        .describe("Every stored run with its profit, a summary per floor, and the session "
                                + "reset. Also /sbs dungeonprofit."),

                SettingRow.label("— Card —"),
                SettingRow.toggle("Dungeon Profit Card", () -> cfg().card,
                        () -> { cfg().card = !cfg().card; save(); })
                        .describe("Last run, session total, profit per hour, runs and the top drop. "
                                + "Shown in the Dungeon Hub only, not inside a run. On by default."),
                SettingRow.toggle("Show Card Everywhere", () -> cfg().showEverywhere,
                        () -> { cfg().showEverywhere = !cfg().showEverywhere; save(); })
                        .describe("Also shows the card on every other island. Off by default."),
                SettingRow.toggle("Show Costs on Card", () -> cfg().cardCosts,
                        () -> { cfg().cardCosts = !cfg().cardCosts; save(); })
                        .describe("Adds a line with what the open or last run cost: chests, Kismet "
                                + "Feathers, potions and your extra cost. On by default."),
                SettingRow.button("Move / Resize Card", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.DUNGEON_PROFIT}, "Edit Dungeon Profit Card")))
                        .describe("Opens the editor where you drag the card and scale it."),
                SettingRow.options("Floor Filter", () -> FLOORS,
                        () -> cfg().floorFilter.isEmpty() ? ALL_FLOORS : cfg().floorFilter,
                        value -> { cfg().floorFilter = ALL_FLOORS.equals(value) ? "" : value; save(); })
                        .describe("Which floor the card's session figures and the history screen show. "
                                + "Default: All floors."),

                SettingRow.label("— Counting —"),
                SettingRow.toggle("Count Shared Wither Essence", () -> cfg().countEveryoneEssence,
                        () -> { cfg().countEveryoneEssence = !cfg().countEveryoneEssence; save(); })
                        .describe("When anyone in the party finds a Wither Essence, everyone gains one: "
                                + "count that one for you too. Essence you find yourself is always "
                                + "counted. On by default."),
                SettingRow.segmented("Bazaar Price", SIDES, () -> cfg().priceSide,
                        value -> { cfg().priceSide = value; save(); })
                        .describe("Instant-sell values Bazaar loot at what selling now pays; Sell offer at "
                                + "the lowest sell offer. Other items use lowest BIN. A run keeps the "
                                + "prices it was booked at. Items without a price are never counted as "
                                + "zero - the total gets a trailing +. Default: Instant-sell."),
                SettingRow.segmented("Potion Cost", POTION_SIDES, () -> cfg().potionCostSide,
                        value -> { cfg().potionCostSide = value; save(); })
                        .describe("Dungeon Potions you drink before a run starts are taken off that run. "
                                + "Market values one at its Bazaar price (on the Bazaar Price side); "
                                + "Purchase price uses what you paid for it this session, when you bought "
                                + "it. Read from your inventory - a potion drunk straight from the Potion "
                                + "Bag is not seen. Default: Market."),
                SettingRow.intField("Extra Cost Per Run", 0, 100_000_000, () -> cfg().userCostPerRun,
                        value -> { cfg().userCostPerRun = value; save(); }, " coins")
                        .describe("Your own cost per run - keys, a carry - taken off every run that "
                                + "finishes from now on. Dungeon Potions and Kismet Feathers are counted "
                                + "on their own: leave them out of this, or they count twice. Default: 0."),

                SettingRow.label("— Data —"),
                SettingRow.button("Reset Session", () -> DungeonProfitTracker.getInstance().resetSession())
                        .describe("Starts the session total from zero. Stored runs and the all-time "
                                + "figures are kept."),
                SettingRow.button("Reset History & Totals", DungeonProfitModule::confirmReset)
                        .describe("Deletes this profile's stored runs and all-time totals. Asks first."));
    }

    private static Screen current() {
        return sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
    }

    /** History and totals; asks first. Returns to {@code back} either way. */
    public static void confirmReset() {
        Screen back = current();
        open(new ConfirmScreen(yes -> {
            if (yes) {
                DungeonProfitTracker.getInstance().resetAll();
            }
            Minecraft.getInstance().setScreenAndShow(back);
        }, Component.literal("Reset dungeon profit history?"),
                Component.literal("Every stored run and the all-time totals of this profile are deleted. "
                        + "This cannot be undone.")));
    }

    private static void open(Screen screen) {
        Minecraft.getInstance().setScreenAndShow(screen);
    }
}
