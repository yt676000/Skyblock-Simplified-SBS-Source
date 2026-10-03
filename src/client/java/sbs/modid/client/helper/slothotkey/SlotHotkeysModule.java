/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.slothotkey;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.slothotkey.logic.SlotHotkeyManager;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;

import java.util.List;

/**
 * Slot Hotkeys module (Quality of Life): bind a key or mouse button (with an optional modifier) to
 * instantly click a saved menu slot – e.g. open the Pets menu with a command keybind, then select
 * the right pet with one press.
 *
 * <p>Two-step flow: <b>Scan key</b> → press it in a menu and click a slot to remember it; then
 * <b>Edit Hotkeys → Add</b> to give that slot a key/modifier/name. The runtime lives in
 * {@link SlotHotkeyManager}; self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 */
public final class SlotHotkeysModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public SlotHotkeysModule() {
    }

    @Override
    public String id() {
        return "slot_hotkeys";
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
        return "Slot Hotkeys";
    }

    @Override
    public String description() {
        return "Bind a key/mouse button to instantly click a saved menu slot (e.g. pick a pet)";
    }

    @Override
    public int accentColor() {
        return 0xFF4DE0C0;
    }

    private static SBSConfig.SlotHotkeysSettings cfg() {
        return ConfigManager.getInstance().get().slotHotkeys;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.toggle("Slot Hotkeys", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                        .describe("Bind a key to a saved menu slot, then pressing it clicks that "
                                + "slot instantly - e.g. equip a specific pet without scrolling "
                                + "the Pets menu."),

                SettingRow.keybind("Scan Key", () -> cfg().scanKey,
                        key -> { cfg().scanKey = key; save(); })
                        .describe("Press this key while a menu is open, then click the slot you "
                                + "want to save - that slot becomes the target for your next "
                                + "hotkey."),
                SettingRow.label("Click on any slot to save slot for next Hotkey"),

                SettingRow.button("Edit Hotkeys", SlotHotkeysModule::openEditor)
                        .describe("Opens the list of your slot hotkeys: bind the last scanned slot "
                                + "to a key or mouse button (with an optional Shift/Ctrl/Alt), "
                                + "name it, or delete old ones."),
                SettingRow.label("Add binds the scanned slot to a key (+ optional modifier) and name"));
    }

    /** Opens the Edit Hotkeys overlay (settings button lands here). */
    public static void openEditor() {
        if (!cfg().enabled) {
            return;
        }
        net.minecraft.client.Minecraft.getInstance()
                .setScreenAndShow(new sbs.modid.client.helper.slothotkey.ui.SlotHotkeysScreen());
    }
}
