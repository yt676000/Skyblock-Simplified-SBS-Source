/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.inventory.logic.SlotBindings;
import sbs.modid.client.ui.settings.ConfirmClick;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.ArrayList;
import java.util.List;

/**
 * Slot Bindings module (Inventory & Items): tie inventory slots to a hotbar slot and shift-click to
 * exchange them.
 *
 * <p>Self-registered via {@code META-INF/services/sbs.modid.client.core.module.SbsModule}. The work is
 * in {@link SlotBindings} and {@code SlotBindingInputMixin}; both read the config live, so a binding
 * made in game shows up here without a reopen and vice versa.
 */
public final class SlotBindingsModule implements SbsModule {

    /** Static so the armed state survives the settings page rebuilding its rows on every scroll. */
    private static final ConfirmClick CLEAR_CONFIRM = new ConfirmClick();

    /** ServiceLoader needs a public no-arg constructor. */
    public SlotBindingsModule() {
    }

    @Override
    public String id() {
        return "slot_bindings";
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
        return "Slot Bindings";
    }

    @Override
    public String description() {
        return "Tie slots to a hotbar slot - shift-click swaps the items straight over";
    }

    @Override
    public int accentColor() {
        return 0xFF4DD2FF;
    }

    private static SBSConfig.SlotBindingsSettings cfg() {
        return ConfigManager.getInstance().get().slotBindings;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(List.of(
                SettingRow.toggle("Slot Bindings", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Tie an inventory slot to a hotbar slot, then shift-left-click "
                                + "either one to exchange the two items on the spot - the pickaxe "
                                + "in your backpack trades places with whatever is in hotbar 3, "
                                + "without dragging anything. Off here, shift-click is vanilla "
                                + "everywhere. On by default, and it does nothing until you make "
                                + "your first binding."),
                SettingRow.holdKeybind("Bind Key (hold + click two slots)", () -> cfg().bindKey,
                        key -> { cfg().bindKey = key; save(); })
                        .describe("Hold this and left-click a slot, then the slot to pair it with. "
                                + "Right-click a bound slot with the key held to unbind it. "
                                + "Defaults to B. A mouse button works too; the wheel does not, "
                                + "because this one has to be held while you click."),
                SettingRow.label("One side must be a hotbar or offhand slot - that is the swap the game can do in one action"),
                SettingRow.label("Bind a third slot to the same hotbar slot to keep swapping more items through it"),
                SettingRow.label("Locked slots stay locked: a lock is never overridden by a binding"),
                SettingRow.toggle("Only In Your Own Inventory", () -> cfg().onlyPlayerInventory,
                        () -> { cfg().onlyPlayerInventory = !cfg().onlyPlayerInventory; save(); })
                        .describe("On by default: bound slots only swap while your own inventory "
                                + "screen is open. Shift-clicking your slots into a chest, a sack "
                                + "or a shop is how everything gets deposited and sold, and a "
                                + "binding that swapped instead would look like broken shift-click. "
                                + "Turn this off to swap inside those menus as well."),
                SettingRow.toggle("Swap Sound", () -> cfg().swapSound,
                        () -> { cfg().swapSound = !cfg().swapSound; save(); })
                        .describe("A short click when a shift-click swaps two bound slots, so you "
                                + "can tell the swap from a normal shift-click without looking.")));

        int count = SlotBindings.count();
        rows.add(SettingRow.label(count == 0
                ? "§8No bindings yet - hold the bind key and click two slots in game"
                : "§8" + count + (count == 1 ? " binding" : " bindings") + " set"));
        if (count > 0) {
            rows.add(SettingRow.button(
                    "Clear All Bindings" + CLEAR_CONFIRM.state(" §c(click again)", ""),
                    () -> {
                        if (CLEAR_CONFIRM.click()) {
                            SlotBindings.clearAll();
                        }
                    })
                    .describe("Removes every binding. Asks twice - the first click only arms it, "
                            + "and the arming expires on its own after a few seconds."));
        }
        return List.copyOf(rows);
    }
}
