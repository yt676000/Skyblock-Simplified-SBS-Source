/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.customskin.command;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.customskin.ui.CustomSkinScreen;

/**
 * The Custom Skin open hotkey, dispatched from {@code CommandKeyMixin} on a fresh key press while
 * in-world with no screen open (unbound by default).
 *
 * <p>The picker always targets the item you are <b>holding</b> – the selected hotbar slot – so the
 * key is a no-op with an empty hand, with a chat line saying why rather than opening an empty
 * screen.
 */
public final class CustomSkinKeybinds {

    private CustomSkinKeybinds() {
    }

    public static void onKeyPressed(int keyCode) {
        SBSConfig.CustomSkinSettings cfg = ConfigManager.getInstance().get().customSkin;
        if (!cfg.enabled || cfg.openKey == 0 || keyCode != cfg.openKey) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        if (held.isEmpty()) {
            minecraft.player.sendSystemMessage(
                    Component.literal("§8[§bSBS§8]§r §7Hold an item to give it a custom skin."));
            return;
        }
        minecraft.setScreenAndShow(new CustomSkinScreen(held));
    }
}
