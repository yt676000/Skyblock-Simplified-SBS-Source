/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.inventory.logic.SlotBindings;
import sbs.modid.client.helper.inventory.render.SlotBindingIcon;

/**
 * The Slot Bindings feature's three touch points with the container GUI: making a binding, using
 * one, and showing it.
 *
 * <ul>
 *   <li><b>Bind</b> – the bind key held + left click picks a slot, the next click ties the two
 *       together; right click with the key held unbinds. The click is swallowed either way, so it
 *       never also becomes an item pickup.</li>
 *   <li><b>Swap</b> – shift + left click on a bound slot exchanges it with its anchor and swallows
 *       the vanilla quick-move that click would otherwise be. Everything else about the click is
 *       vanilla, and a slot that is not bound never reaches this branch.</li>
 *   <li><b>Render</b> – the frame and number over each bound slot, at the tail of
 *       {@code extractSlots} so they sit above the item but below the cursor item and tooltips.</li>
 * </ul>
 *
 * <p>The swap is issued through the screen's own {@code slotClicked}, which is the same call vanilla
 * makes when you press 1-9 over a slot: <b>one</b> container input, no synthesized clicks. If either
 * side is locked, {@code SlotLockGuardMixin} vetoes that input like any other — the lock wins.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class SlotBindingInputMixin {

    @Shadow
    public abstract AbstractContainerMenu getMenu();

    @Shadow
    protected Slot hoveredSlot;

    @Shadow
    protected abstract void slotClicked(Slot slot, int slotId, int buttonNum, ContainerInput input);

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$slotBindingClick(MouseButtonEvent event, boolean doubled,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (!SlotBindings.enabled()) {
            return;
        }
        int index = SlotBindings.inventoryIndexOf(this.hoveredSlot);
        if (SlotBindings.bindKeyDown() && (event.button() == 0 || event.button() == 1)) {
            if (index < 0) {
                return;   // not a player-inventory slot - nothing bindable here
            }
            if (event.button() == 1) {
                SlotBindings.unbind(index);
            } else {
                SlotBindings.clicked(index);
            }
            cir.setReturnValue(true);
            return;
        }
        if (event.button() != 0 || !event.hasShiftDown() || this.hoveredSlot == null) {
            return;
        }
        SlotBindings.Swap swap = SlotBindings.resolve(getMenu(), this.hoveredSlot);
        if (swap == null) {
            return;   // not bound (or an item is on the cursor): vanilla shift-click, untouched
        }
        // The member slot is the one named, whichever end was clicked: SWAP always exchanges a slot
        // with a hotbar index, so the hotbar side travels as the button.
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Stacking] SlotBindingInputMixin rewrote QUICK_MOVE slot={} into SWAP slot={} button={}",
                this.hoveredSlot.index, swap.menuSlotId(), swap.hotbarIndex());
        slotClicked(getMenu().getSlot(swap.menuSlotId()), swap.menuSlotId(), swap.hotbarIndex(),
                ContainerInput.SWAP);
        SlotBindings.swapped();
        cir.setReturnValue(true);
    }

    @Inject(method = "extractSlots", at = @At("TAIL"))
    private void skyblockSimplified$drawSlotBindings(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                     CallbackInfo ci) {
        if (!SlotBindings.enabled()) {
            return;
        }
        int pending = SlotBindings.pendingSlot();
        for (Slot slot : getMenu().slots) {
            int index = SlotBindings.inventoryIndexOf(slot);
            if (index < 0) {
                continue;
            }
            if (index == pending) {
                SlotBindingIcon.drawPending(g, slot.x, slot.y);
                continue;
            }
            int number = SlotBindings.groupNumber(index);
            if (number > 0) {
                SlotBindingIcon.draw(g, slot.x, slot.y, number, SlotBindings.isAnchor(index));
            }
        }
    }
}
