/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.keybind.KeyBindOverview.Bind;
import sbs.modid.client.core.keybind.KeyBindOverview.Source;
import sbs.modid.client.core.module.ModuleCategory;
import sbs.modid.client.core.module.ModuleManager;
import sbs.modid.client.helper.slothotkey.logic.SlotHotkey;
import sbs.modid.client.helper.slothotkey.logic.SlotHotkeyManager;
import sbs.modid.client.helper.slothotkey.ui.SlotHotkeyEditScreen;
import sbs.modid.client.ui.settings.ConfigNavigator;
import sbs.modid.client.ui.settings.Favorites;
import sbs.modid.client.ui.settings.ModuleSettings;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Reads every live bind into a flat list for {@link KeyBindOverview}.
 *
 * <p>Four sources, all read through their public models and never written:
 * <ul>
 *   <li><b>Controls</b> – every {@code KeyMapping} in {@code Options.keyMappings}, which is where
 *       vanilla <i>and</i> every other mod register theirs. Opens the vanilla Controls screen.</li>
 *   <li><b>SBS settings</b> – every module row built with {@code SettingRow.keybind} /
 *       {@code holdKeybind}, found through {@link SettingRow#boundKey()}. This covers module keys,
 *       the Slot Binding key and the Slot Hotkeys scan key without knowing about any of them – a new
 *       key row shows up here the day it is written. Opens the config on that row.</li>
 *   <li><b>Command keybinds</b> – enabled ones only; a disabled keybind fires nothing.</li>
 *   <li><b>Slot hotkeys</b>.</li>
 * </ul>
 *
 * <p>Built on demand when the overview opens – it lists every module's rows, so it never runs per
 * frame.
 */
public final class KeyBindSources {

    private KeyBindSources() {
    }

    /** Every bind, unbound ones included (the overview drops them). */
    public static List<Bind> collect(Screen returnTo) {
        List<Bind> out = new ArrayList<>();
        minecraft(out, returnTo);
        modules(out);
        commandKeybinds(out);
        slotHotkeys(out);
        return out;
    }

    private static void minecraft(List<Bind> out, Screen returnTo) {
        Minecraft mc = Minecraft.getInstance();
        Options options = mc.options;
        if (options == null) {
            return;
        }
        for (KeyMapping mapping : options.keyMappings) {
            if (mapping.isUnbound()) {
                continue;
            }
            int code = codeOf(mapping);
            if (code == 0) {
                continue;
            }
            String group = mapping.getCategory().label().getString();
            String name = Component.translatable(mapping.getName()).getString();
            out.add(new Bind(code, 0, Source.MINECRAFT, group, name,
                    () -> mc.setScreenAndShow(new KeyBindsScreen(returnTo, mc.options))));
        }
    }

    /** A mapping's key in the {@link Keys} encoding; {@code 0} for a scancode-only bind. */
    static int codeOf(KeyMapping mapping) {
        InputConstants.Key key;
        try {
            key = InputConstants.getKey(mapping.saveString());
        } catch (RuntimeException e) {
            return 0;
        }
        return switch (key.getType()) {
            case KEYSYM -> key.getValue();
            case MOUSE -> Keys.ofMouseButton(key.getValue());
            default -> 0;
        };
    }

    private static void modules(List<Bind> out) {
        for (ModuleCategory category : ModuleManager.getInstance().getCategories()) {
            String moduleId = category.id();
            if (Favorites.PAGE_ID.equals(moduleId)) {
                continue;   // borrows other modules' rows - they would be listed twice
            }
            List<SettingRow> rows;
            try {
                rows = ModuleSettings.rowsFor(moduleId);
            } catch (Throwable t) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Keybind] module '{}' failed to list settings",
                        moduleId, t);
                continue;
            }
            String moduleName = category.displayName().getString();
            for (SettingRow row : rows) {
                IntSupplier key = row.boundKey();
                if (key == null) {
                    continue;
                }
                String optionId = moduleId + ":" + row.id();
                out.add(new Bind(key.getAsInt(), 0, Source.MODULE, moduleName, row.label(),
                        () -> ConfigNavigator.jumpTo(optionId)));
            }
        }
    }

    private static void commandKeybinds(List<Bind> out) {
        for (CommandKeybind keybind : CommandKeybindManager.getInstance().getKeybinds()) {
            if (!keybind.enabled() || !keybind.isBound()) {
                continue;
            }
            String name = keybind.label() == null || keybind.label().isBlank()
                    ? keybind.summary() : keybind.label();
            out.add(new Bind(keybind.keyCode(), keybind.modifiers(), Source.COMMAND_KEYBIND,
                    "Command Keybinds", name,
                    () -> Minecraft.getInstance().setScreenAndShow(new KeybindEditScreen(keybind))));
        }
    }

    private static void slotHotkeys(List<Bind> out) {
        for (SlotHotkey hotkey : SlotHotkeyManager.getInstance().hotkeys()) {
            if (!hotkey.isBound()) {
                continue;
            }
            String menu = hotkey.menuName().isEmpty() ? "Slot Hotkeys" : "In " + hotkey.menuName();
            out.add(new Bind(hotkey.keyCode(), hotkey.modifiers(), Source.SLOT_HOTKEY, menu,
                    hotkey.displayLabel(),
                    () -> Minecraft.getInstance().setScreenAndShow(new SlotHotkeyEditScreen(hotkey))));
        }
    }
}
