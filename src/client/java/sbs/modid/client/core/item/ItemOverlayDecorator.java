/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.item;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.combat.cooldowns.CooldownOverlay;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.enchants.EnchantBookOverlay;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * The two overlays that belong to an item rather than to the menu it happens to be in: the ability
 * cooldown drain, and the Enchanted Book label.
 *
 * <p><b>Drawn first, so everything else lands on top.</b> These say what the item <i>is</i>; a
 * highlight says what the player should <i>do</i> about it, and when both apply the second is the
 * one that must stay readable. That ordering used to be expressed as {@code priority = 900} on the
 * mixin, which worked but stated it in the one place no reader of either feature would look - and
 * only held for as long as every competing hook stayed at the default priority.
 *
 * <p>Rarity tinting is <b>not</b> here. It draws per slot at the head of {@code extractSlot},
 * underneath the item icon rather than over it, and stays in {@code ItemRarityMixin} where the
 * screen-level decision it caches is made.
 */
public final class ItemOverlayDecorator implements SlotDecorator {

    @Override
    public String id() {
        return "item_overlays";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                         int mouseX, int mouseY) {
        // Both draws gate themselves, but they gate themselves per slot: with all three switches off
        // this still walked every slot of every container, ninety times a frame, to draw nothing.
        // The flags all live on one config section, so asking them once here cannot drift from what
        // the two draws ask - a fourth item overlay added to the loop has to be added here too.
        SBSConfig.ItemOverlaySettings cfg = ConfigManager.getInstance().get().itemOverlay;
        if (!cfg.showItemCooldown && !cfg.bookAbbreviation && !cfg.bookTier) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int slotCount = menu.getItems().size();
        for (int index = 0; index < slotCount; index++) {
            Slot slot = menu.getSlot(index);
            CooldownOverlay.draw(g, slot.getItem(), slot.x, slot.y);
            EnchantBookOverlay.draw(g, slot.getItem(), slot.x, slot.y);
        }
    }
}
