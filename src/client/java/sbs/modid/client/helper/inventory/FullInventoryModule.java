/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory;

import sbs.modid.client.core.alert.AlertChannelRows;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.inventory.logic.FreeSlotWatcher;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Full Inventory Warning (Inventory and Items, Slots and Protection): tells the player they have
 * run out of room before the next drop is the one that does not fit.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}; every
 * option writes to {@link SBSConfig.FullInventorySettings} and is read live, so changes apply
 * without a restart.
 *
 * <p><b>Its own card rather than rows on a neighbour.</b> Eleven rows - a threshold, a cooldown, a
 * HUD element and the five alert channels - hung off Slot Bindings or Accessory Bag would sit
 * under a name that does not describe them. It is in the Slots and Protection subgroup because it
 * is about the state of the player's slots, alongside the slot lock and drop protection that
 * already live in this package.
 *
 * <p><b>Read-only.</b> It counts empty slots and it says something. It never moves, drops, sells or
 * stashes anything to make room - deciding what to throw away is the player's call, and a mod that
 * made it for them would be playing the game.
 */
public final class FullInventoryModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public FullInventoryModule() {
    }

    @Override
    public String id() {
        return "full_inventory";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.INVENTORY_ITEMS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.SLOTS;
    }

    @Override
    public String displayName() {
        return "Full Inventory Warning";
    }

    @Override
    public String description() {
        return "Tells you when your inventory has run out of room, before the next drop is lost";
    }

    @Override
    public int accentColor() {
        return 0xFFFF9B54;
    }

    private static SBSConfig.FullInventorySettings cfg() {
        return ConfigManager.getInstance().get().fullInventory;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(16);

        rows.add(SettingRow.toggle("Full Inventory Warning", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Watches how many empty slots are left in your inventory and warns you "
                        + "when you are about to run out. Your armor and offhand are never counted "
                        + "- only the hotbar and the twenty-seven slots above it, which is where "
                        + "loot actually lands. Default: on.")
                .anchor("full_inventory_enabled"));
        rows.add(SettingRow.label("§8Counts empty slots only, so the SkyBlock Menu item is never one of"));
        rows.add(SettingRow.label("§8them - with it in your hotbar the most you can have free is 35."));

        // ---------------------------------------------------------------- when it fires
        rows.add(SettingRow.intField("Warn At", 0, 35,
                        () -> cfg().threshold,
                        value -> { cfg().threshold = value; save(); }, " free")
                .describe("How many free slots are left when the warning goes off. 0 means only "
                        + "when you are completely full; 3 gives you a few slots of warning while "
                        + "there is still time to do something about it. A typed field rather than "
                        + "a slider because the exact number is the whole setting. Default: 0.")
                .anchor("full_inventory_threshold"));
        rows.add(SettingRow.rangeSlider("Warn Again After", 5, 300,
                        () -> cfg().cooldownSeconds,
                        value -> { cfg().cooldownSeconds = value; save(); }, "s")
                .describe("The shortest gap between two warnings. You are only warned again after "
                        + "you have actually freed a slot and filled up once more - this is what "
                        + "stops that becoming a stream of them while you are clearing a vein. "
                        + "Default: 30s.")
                .anchor("full_inventory_cooldown"));
        rows.add(SettingRow.toggle("Off In Dungeons", () -> cfg().offInDungeons,
                        () -> { cfg().offInDungeons = !cfg().offInDungeons; save(); })
                .describe("Stays quiet inside the Catacombs. A dungeon inventory fills and empties "
                        + "constantly by design and a warning there is noise during the one part of "
                        + "the game where you can least afford to read it. Default: on.")
                .anchor("full_inventory_off_in_dungeons"));

        // ---------------------------------------------------------------- how it tells you
        rows.addAll(AlertChannelRows.forAlert("full_inventory", "your inventory is full",
                () -> cfg().notifyChannels,
                value -> { cfg().notifyChannels = value; save(); }));

        rows.add(SettingRow.toggle("Show The Counter", () -> cfg().showHud,
                        () -> { cfg().showHud = !cfg().showHud; save(); })
                .describe("A small movable chip reading \"Free slots: N\". It appears only once you "
                        + "are at or under the number above and disappears again as soon as you are "
                        + "not, so it is not one more thing on screen the rest of the time. "
                        + "Default: off.")
                .anchor("full_inventory_show_hud"));
        rows.add(SettingRow.button("Move / Resize Counter", () -> open(new HudEditorScreen(
                        new HudElement[] {HudElement.FREE_SLOTS}, "Edit Free Slots Counter")))
                .describe("Opens the editor where you drag the chip anywhere on the screen and "
                        + "scale it. Its box is there to grab even while the chip itself is "
                        + "hidden, so you can place it without filling your inventory first."));

        rows.add(SettingRow.label("§8Status: " + status()));
        return rows;
    }

    /** What the watcher last saw, so the page can be checked without filling an inventory. */
    private static String status() {
        int free = FreeSlotWatcher.getInstance().freeSlots();
        if (!cfg().enabled) {
            return "§7switched off";
        }
        return free < 0 ? "§7nothing read yet" : "§f" + free + "§7 free slots at the last check";
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
