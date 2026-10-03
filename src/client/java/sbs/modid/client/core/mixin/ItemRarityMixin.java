/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.Rarity;
import sbs.modid.client.helper.rarity.RarityOverlay;
import sbs.modid.client.helper.rarity.RarityOverlayMode;

/**
 * The rarity tint, drawn UNDER the item of every container slot.
 *
 * <p><b>The tint is drawn per slot, at the HEAD of {@code extractSlot}</b> – vanilla's own per-slot
 * render, which paints that slot's background and then its item. That anchor is what makes the
 * overlay survive: everything that reskins a container (the SBS panel with its <b>opaque</b> slot
 * cells, the Abiphone phone skin) paints from the HEAD of {@code extractSlots}, the enclosing loop's
 * method, so it is structurally finished before the first {@code extractSlot} runs. Drawing here can
 * therefore never be buried, no matter how mixin priorities happen to order the HEAD handlers of
 * {@code extractSlots} against each other – which is exactly what did bury it: the tint went down
 * first and the theme's cells were painted straight over it, so rarity was invisible in every
 * reskinned menu (Accessory Bag, chests, the player inventory).
 *
 * <p>The screen-level decision (feature on, screen allowed) is made once per frame at the HEAD of
 * {@code extractSlots} and cached, so the per-slot hook stays a field read instead of re-reading the
 * config and re-stringifying the title 90 times a frame.
 *
 * <p>The cooldown drain and the Enchanted Book labels used to ride along at the TAIL of the same
 * method, ordered under the highlighters by this class's {@code priority = 900}. They are now
 * {@code ItemOverlayDecorator} and say the same thing with an explicit draw order. The priority
 * stays because it still orders this class's own {@code extractSlot} tint against the container
 * reskin, which is what it was for first.
 *
 * <p>Suppressed only over the <b>chest</b> slots of the Bazaar orders menus, where the order-status
 * highlight owns the slot coloring – the player's own inventory below keeps its tint there, and every
 * other Bazaar screen keeps it everywhere.
 */
@Mixin(value = AbstractContainerScreen.class, priority = 900)
public abstract class ItemRarityMixin {

    private static final int ITEM_SIZE = 16;

    /**
     * The mode this frame's slots are tinted with, decided in {@code extractSlots} and read by every
     * {@code extractSlot} that follows. {@code null} means "nothing to draw" – also the value a slot
     * render that somehow bypasses {@code extractSlots} sees, which is the safe way round.
     */
    @Unique
    private RarityOverlayMode skyblockSimplified$rarityMode;

    /**
     * True while a Bazaar orders menu is open: the order-status highlight owns the chest slots there,
     * so only the player's own inventory rows below are tinted.
     */
    @Unique
    private boolean skyblockSimplified$playerSlotsOnly;

    @Inject(method = "extractSlots", at = @At("HEAD"))
    private void skyblockSimplified$decideRarity(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
        skyblockSimplified$rarityMode = null;
        skyblockSimplified$playerSlotsOnly = false;
        RarityOverlayMode mode = ConfigManager.getInstance().get().itemOverlay.rarityMode;
        if (mode == RarityOverlayMode.OFF) {
            return;
        }
        // Rarity shows everywhere (own inventory and the rest of the Bazaar included) EXCEPT the chest
        // slots of the Bazaar orders menus ("Bazaar Orders" / "Co-op Bazaar Orders"), where the
        // order-status highlight owns the slot coloring and a second tint underneath just muddies it.
        // The player's own inventory in that screen carries no order highlight, so it keeps its tint.
        Screen self = (Screen) (Object) this;
        String title = self.getTitle() != null ? self.getTitle().getString() : "";
        skyblockSimplified$playerSlotsOnly = BazaarOrderTracker.isOrdersMenu(title);
        skyblockSimplified$rarityMode = mode;
    }

    /** One slot's rarity tint, after any container reskin and before vanilla draws the item. */
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void skyblockSimplified$rarityOverlay(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY,
                                                 CallbackInfo ci) {
        RarityOverlayMode mode = skyblockSimplified$rarityMode;
        if (mode == null) {
            return;
        }
        if (skyblockSimplified$playerSlotsOnly && !(slot.container instanceof Inventory)) {
            return;
        }
        Rarity rarity = Rarity.detect(slot.getItem());
        if (rarity != null) {
            RarityOverlay.draw(g, slot.x, slot.y, ITEM_SIZE, rarity, mode);
        }
    }

}
