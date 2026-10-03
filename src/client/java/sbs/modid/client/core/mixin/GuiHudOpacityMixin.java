/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.ui.hud.edit.logic.HudOpacity;

/**
 * The one place HUD element opacity is applied: every GUI element drawn this frame is queued into
 * {@link GuiRenderState}, so fading whatever is submitted while {@link HudOpacity} is active fades
 * exactly the element the GUI editor wrapped – vanilla hearts and hotbar included – without a single
 * overlay having to know about opacity.
 *
 * <p>Costs nothing while no element is faded: {@link HudOpacity#active()} is a static float compare,
 * and the argument is handed straight back unchanged.
 */
@Mixin(GuiRenderState.class)
public class GuiHudOpacityMixin {

    /** Coloured rectangles, blits and tiled blits – i.e. everything except text, glyphs and items. */
    @ModifyVariable(method = "addGuiElement", at = @At("HEAD"), argsOnly = true)
    private GuiElementRenderState skyblockSimplified$fadeElement(GuiElementRenderState state) {
        return HudOpacity.fadeElement(state);
    }

    /** The blit-only fast path (sprites recorded straight into the current layer). */
    @ModifyVariable(method = "addBlitToCurrentLayer", at = @At("HEAD"), argsOnly = true)
    private BlitRenderState skyblockSimplified$fadeBlit(BlitRenderState state) {
        return HudOpacity.fadeBlit(state);
    }

    /**
     * Text is edited in place rather than replaced – see {@link GuiTextRenderStateAccessor}. Both the
     * text colour and its backdrop fade, so a faded label keeps its contrast instead of turning into
     * ghost text on a solid box.
     */
    @Inject(method = "addText", at = @At("HEAD"))
    private void skyblockSimplified$fadeText(GuiTextRenderState state, CallbackInfo ci) {
        if (!HudOpacity.active() || state == null || !HudOpacity.fadesText()) {
            return;
        }
        // Via Object: the target class is final, so javac rejects the direct cast to the interface
        // mixin adds to it at runtime.
        GuiTextRenderStateAccessor access = (GuiTextRenderStateAccessor) (Object) state;
        access.skyblockSimplified$setColor(HudOpacity.fadeTextColor(access.skyblockSimplified$getColor()));
        access.skyblockSimplified$setBackgroundColor(
                HudOpacity.fadeTextColor(access.skyblockSimplified$getBackgroundColor()));
    }

    /**
     * Item icons cannot be faded – {@link GuiItemRenderState} has no colour to rewrite – so the only
     * opacity they can honour is the last one: at a content dial of zero the submission is dropped,
     * and the item is simply never drawn.
     *
     * <p>Dropping a submission is not free of consequence, which is why nothing else does it:
     * {@code GuiRenderState} layers each element against the bounds of the one before, so an element
     * that never arrives is an element the next one is not stacked above. Harmless here – the item
     * being skipped is invisible either way, so nothing that was drawn over it needed to be – and
     * unavailable anywhere else, since a faded element must still take part in that layering.
     */
    @Inject(method = "addItem", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$hideItem(GuiItemRenderState state, CallbackInfo ci) {
        if (HudOpacity.hidesContent()) {
            ci.cancel();
        }
    }
}
