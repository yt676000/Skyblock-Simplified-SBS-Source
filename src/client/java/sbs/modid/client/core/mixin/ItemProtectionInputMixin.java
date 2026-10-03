/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.keybind.Keys;
import sbs.modid.client.helper.itemprotection.logic.ItemProtection;

/**
 * The marking keybind: pressing it while hovering a slot in any container screen toggles protection
 * for that slot's item and swallows the input, so a mouse-bound key never also picks the item up.
 *
 * <p>Bound through {@code core/keybind/Keys}, which packs keys, mouse buttons and the wheel into one
 * int - hence two injections for one bind. The wheel is deliberately not offered: a marking action
 * that fires on a scroll would be triggered by scrolling a paged menu.
 *
 * <p>{@code priority = 800}, ahead of the slot-hotkey mixin at 900, so a slot armed for hotkey
 * scanning cannot swallow the marking press first. Drawing the marker is
 * {@code ProtectedItemDecorator}; enforcing it is {@code ItemProtectionGuardMixin}. This class is
 * only the toggle.
 */
@Mixin(value = AbstractContainerScreen.class, priority = 800)
public abstract class ItemProtectionInputMixin {

    @Shadow
    protected Slot hoveredSlot;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$toggleProtectionKey(KeyEvent event,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (skyblockSimplified$handles(event.key()) && skyblockSimplified$toggle()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$toggleProtectionMouse(MouseButtonEvent event, boolean doubled,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (skyblockSimplified$handles(Keys.ofMouseButton(event.button())) && skyblockSimplified$toggle()) {
            cir.setReturnValue(true);
        }
    }

    /** Whether this input is the configured marking bind. Unbound ({@code 0}) never matches. */
    private static boolean skyblockSimplified$handles(int code) {
        int bound = ConfigManager.getInstance().get().itemProtection.toggleKey;
        return bound != 0 && bound == code;
    }

    /**
     * Toggles the hovered slot's item. A press over no slot is still consumed - the key is bound to
     * this feature, and letting it fall through to the container would make it do something else
     * entirely depending on where the cursor happened to be.
     */
    private boolean skyblockSimplified$toggle() {
        if (!ConfigManager.getInstance().get().itemProtection.enabled) {
            return false;
        }
        Slot slot = this.hoveredSlot;
        return ItemProtection.toggle(slot == null ? net.minecraft.world.item.ItemStack.EMPTY : slot.getItem());
    }
}
