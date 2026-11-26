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
 *   <li><b>Bind</b> – with the bind key held, press on one slot and drag to another to tie the two
 *       together; a line follows the cursor meanwhile. Clicking a bound slot with the key held
 *       unbinds it (so does a right click). Clicking an unbound slot still works the older way: it
 *       becomes the pending pick and the next click completes it. Press, drag and release are all
 *       swallowed, so none of it becomes an item pickup or a vanilla drag.</li>
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
                SlotBindings.dragPressed(index);
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

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$slotBindingDrag(MouseButtonEvent event, double dragX, double dragY,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (SlotBindings.dragSlot() >= 0) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$slotBindingRelease(MouseButtonEvent event,
                                                       CallbackInfoReturnable<Boolean> cir) {
        if (event.button() == 0
                && SlotBindings.dragReleased(SlotBindings.inventoryIndexOf(this.hoveredSlot))) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "extractSlots", at = @At("TAIL"))
    private void skyblockSimplified$drawSlotBindings(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                     CallbackInfo ci) {
        if (!SlotBindings.enabled()) {
            return;
        }
        int pending = SlotBindings.pendingSlot();
        int dragFrom = SlotBindings.dragSlot();
        for (Slot slot : getMenu().slots) {
            if (dragFrom >= 0 && SlotBindings.inventoryIndexOf(slot) == dragFrom) {
                // Slots are drawn in GUI-local space; the cursor arrives in screen space. Snap the
                // end to the hovered inventory slot so the line shows exactly what a release binds.
                AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) this;
                int endX = mouseX - bounds.skyblockSimplified$leftPos();
                int endY = mouseY - bounds.skyblockSimplified$topPos();
                if (SlotBindings.inventoryIndexOf(this.hoveredSlot) >= 0) {
                    endX = this.hoveredSlot.x + 8;
                    endY = this.hoveredSlot.y + 8;
                }
                SlotBindingIcon.drawPending(g, slot.x, slot.y);
                SlotBindingIcon.drawLine(g, slot.x + 8, slot.y + 8, endX, endY);
            }
        }
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
