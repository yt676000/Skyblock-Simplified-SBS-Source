/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.itemprotection.logic.ProtectedItems;
import sbs.modid.client.helper.itemprotection.model.IndicatorStyle;
import sbs.modid.client.helper.itemprotection.model.ProtectionMode;
import sbs.modid.client.helper.itemprotection.ui.ProtectedItemsScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Item Protection module (Inventory &amp; Items): mark an item, and an accidental click can no
 * longer drop, sell, salvage, sack or feed it to a menu.
 *
 * <p><b>Ships off.</b> Most of the menu titles the guard classifies on have not been seen in game by
 * this build - see {@code DestructiveScreens}, where every pattern is marked verified or not - so
 * the player switches this on knowing it is new. An unrecognised menu asks rather than allows, which
 * is what makes a wrong pattern cost a click instead of an item.
 *
 * <p>The runtime lives in {@code logic/ItemProtection}; the list in {@code logic/ProtectedItems}.
 * Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class ItemProtectionModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public ItemProtectionModule() {
    }

    @Override
    public String id() {
        return "item_protection";
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
        return "Item Protection";
    }

    @Override
    public String description() {
        return "Mark items so they cannot be dropped, sold, salvaged or consumed by accident";
    }

    @Override
    public int accentColor() {
        return 0xFFFFC83F;
    }

    private static SBSConfig.ItemProtectionSettings cfg() {
        return ConfigManager.getInstance().get().itemProtection;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();

        rows.add(SettingRow.toggle("Item Protection", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .anchor("item_protection_enabled")
                .describe("Marked items refuse to be dropped, sold, salvaged, put into a sack or "
                        + "consumed by a menu - the click is cancelled on your client before "
                        + "anything is sent. Off by default because the menus it recognises have "
                        + "not all been confirmed in game yet; a menu SBS does not recognise asks "
                        + "you to confirm rather than letting the item go."));

        rows.add(SettingRow.keybind("Mark Key", () -> cfg().toggleKey,
                        key -> { cfg().toggleKey = key; save(); })
                .anchor("item_protection_key")
                .describe("Press this while hovering a slot in any inventory to protect or "
                        + "unprotect that item. Unbound by default so it cannot collide with "
                        + "anything you already use. An item with its own id (most gear) is "
                        + "protected on its own; a stackable one protects every stack of that "
                        + "kind."));

        rows.add(SettingRow.button("Manage Protected Items", ItemProtectionModule::openManager)
                .anchor("item_protection_manage")
                .describe("The list of everything you have marked, including items you are not "
                        + "carrying right now. Also reachable with /sbs protect."));
        rows.add(SettingRow.label("§8Now: " + status()));

        rows.add(SettingRow.segmented("Mode",
                        List.of(ProtectionMode.HARD_BLOCK.displayName(), ProtectionMode.CONFIRM.displayName()),
                        () -> cfg().mode == ProtectionMode.CONFIRM ? 1 : 0,
                        index -> {
                            cfg().mode = index == 1 ? ProtectionMode.CONFIRM : ProtectionMode.HARD_BLOCK;
                            save();
                        })
                .anchor("item_protection_mode")
                .describe("Hard Block refuses every time - unprotect the item to act on it. Confirm "
                        + "refuses once and lets the same action through if you repeat it inside "
                        + "the window. Listing on the auction house and any menu SBS does not "
                        + "recognise always confirm, whichever mode this is on."));

        rows.add(SettingRow.rangeSlider("Confirm Window", 1, 15, () -> cfg().confirmSeconds,
                        value -> { cfg().confirmSeconds = value; save(); }, "s")
                .anchor("item_protection_confirm_seconds")
                .describe("How long you have to repeat a refused action to confirm it. Default: 5s."));

        rows.add(SettingRow.toggle("Deny Sound", () -> cfg().denySound,
                        () -> { cfg().denySound = !cfg().denySound; save(); })
                .anchor("item_protection_sound")
                .describe("A short note when an action on a protected item is refused."));

        rows.add(SettingRow.label("§8Indicator"));

        rows.add(SettingRow.toggle("Show Indicator", () -> cfg().indicator,
                        () -> { cfg().indicator = !cfg().indicator; save(); })
                .anchor("item_protection_indicator")
                .describe("Mark protected slots in every inventory, so you can see what is "
                        + "protected without hovering it."));

        rows.add(SettingRow.segmented("Indicator Style",
                        List.of(IndicatorStyle.BORDER.displayName(), IndicatorStyle.ICON.displayName(),
                                IndicatorStyle.BOTH.displayName()),
                        () -> style().ordinal(),
                        index -> {
                            IndicatorStyle[] values = IndicatorStyle.values();
                            cfg().indicatorStyle = values[Math.floorMod(index, values.length)];
                            save();
                        })
                .anchor("item_protection_indicator_style")
                .disabledWhile(() -> !cfg().indicator)
                .describe("A frame around the slot, a small shield in its top-left corner, or both. "
                        + "The shield sits opposite the stack count and the slot-lock padlock, so "
                        + "the three never cover each other."));

        rows.add(SettingRow.color("Indicator Colour", () -> cfg().indicatorColorHex,
                        () -> 0xFF000000 | SBSConfig.ItemProtectionSettings.DEFAULT_INDICATOR_COLOR,
                        ItemProtectionModule::openColorPicker)
                .anchor("item_protection_indicator_color")
                .disabledWhile(() -> !cfg().indicator)
                .describe("Colour of the frame and the shield. Amber by default, which no other "
                        + "slot decoration in the mod uses."));

        rows.add(SettingRow.toggle("Tooltip Line", () -> cfg().tooltipLine,
                        () -> { cfg().tooltipLine = !cfg().tooltipLine; save(); })
                .anchor("item_protection_tooltip")
                .describe("Adds a line to a protected item's tooltip saying whether this one item "
                        + "or the whole item type is protected."));

        rows.add(SettingRow.label("§8What is guarded"));

        rows.add(SettingRow.toggle("Dropping", () -> cfg().blockDrop,
                        () -> { cfg().blockDrop = !cfg().blockDrop; save(); })
                .anchor("item_protection_block_drop")
                .describe("The drop key over a slot or while holding the item, dropping a whole "
                        + "stack, and dragging the item outside the window. Never applies in "
                        + "dungeons, where that key is your class ability and not a drop at all."));

        rows.add(SettingRow.toggle("Selling", () -> cfg().blockSell,
                        () -> { cfg().blockSell = !cfg().blockSell; save(); })
                .anchor("item_protection_block_sell")
                .describe("NPC sell menus, the Bazaar's sell-instantly slots, sell confirmations "
                        + "and \"sell all\" buttons."));

        rows.add(SettingRow.toggle("Salvaging", () -> cfg().blockSalvage,
                        () -> { cfg().blockSalvage = !cfg().blockSalvage; save(); })
                .anchor("item_protection_block_salvage")
                .describe("Reforge-anvil salvage, dungeon salvage and the bulk variant."));

        rows.add(SettingRow.toggle("Sacks & Deletors", () -> cfg().blockSack,
                        () -> { cfg().blockSack = !cfg().blockSack; save(); })
                .anchor("item_protection_block_sack")
                .describe("Sacks, the Sack of Sacks and the Personal Deletor - menus that swallow "
                        + "an item rather than storing it as itself."));

        rows.add(SettingRow.toggle("Auction Listings", () -> cfg().blockAuction,
                        () -> { cfg().blockAuction = !cfg().blockAuction; save(); })
                .anchor("item_protection_block_auction")
                .describe("Creating an auction. Always asks rather than refusing, whatever Mode is "
                        + "set to: people list protected items on purpose."));

        rows.add(SettingRow.toggle("Consuming Menus", () -> cfg().blockConsume,
                        () -> { cfg().blockConsume = !cfg().blockConsume; save(); })
                .anchor("item_protection_block_consume")
                .describe("The forge, Kat, attribute fusion, the anvil, craft menus and museum "
                        + "donation - anything that takes the item and does not give it back."));

        rows.add(SettingRow.toggle("Ask In Unknown Menus", () -> cfg().confirmUnknownMenus,
                        () -> { cfg().confirmUnknownMenus = !cfg().confirmUnknownMenus; save(); })
                .anchor("item_protection_confirm_unknown")
                .describe("When a protected item would leave your inventory in a menu SBS does not "
                        + "recognise as safe, ask first. This is what covers menus Hypixel has "
                        + "reworded or added since this build - turning it off is the one setting "
                        + "here that can lose an item."));

        rows.add(SettingRow.toggle("Click Log", () -> cfg().debugLog,
                        () -> { cfg().debugLog = !cfg().debugLog; save(); })
                .anchor("item_protection_debug_log")
                .describe("Writes one line per inventory click to your log, saying how SBS read the "
                        + "menu and what it decided. Switch this on if protection does not fire "
                        + "where you expect it to - the line says whether the menu was recognised, "
                        + "whether the slot was looked at, and whether the item's identity "
                        + "resolved. Off by default; it is noisy while it is on."));

        return rows;
    }

    private static IndicatorStyle style() {
        IndicatorStyle style = cfg().indicatorStyle;
        return style == null ? IndicatorStyle.BOTH : style;
    }

    /** "3 items, 1 type protected" - the live count under the manage button. */
    private static String status() {
        ProtectedItems store = ProtectedItems.getInstance();
        int items = store.uniqueCount();
        int types = store.typeCount();
        if (items == 0 && types == 0) {
            return "nothing protected yet";
        }
        return items + (items == 1 ? " item" : " items") + ", "
                + types + (types == 1 ? " type" : " types") + " protected";
    }

    /** Opens the management list (settings button and /sbs protect both land here). */
    public static void openManager() {
        net.minecraft.client.Minecraft.getInstance()
                .setScreenAndShow(new ProtectedItemsScreen());
    }

    private static void openColorPicker() {
        var state = sbs.modid.client.core.api.GuiStateManager.getInstance();
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                new sbs.modid.client.ui.theme.ThemeColorPickerScreen(
                        "Item Protection", cfg().indicatorColorHex,
                        value -> {
                            cfg().indicatorColorHex = value;
                            save();
                        },
                        state.getCurrentScreen()));
    }
}
