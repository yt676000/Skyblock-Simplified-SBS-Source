/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * One feature's drawing over the slots of an open container screen.
 *
 * <p>Registered through {@code META-INF/services}, like {@code SbsModule} and {@code WizardPage} and
 * for the same reason: a new decoration is a class plus a line, touching neither the mixin nor any
 * other feature's decorator.
 *
 * <p><b>Gate yourself.</b> {@link #decorate} is called for <i>every</i> container screen on every
 * frame - the player's inventory, a villager, someone else's menu. Deciding "is this my screen, and
 * is my feature even on" is the decorator's own first job; the registry deliberately knows nothing
 * about which screens matter to whom.
 *
 * <p><b>Then say so cheaply, in {@link #tier}.</b> The gate above still costs the frame the work of
 * running it, and on a busy menu a dozen of them run per frame to answer "no". {@code tier} is the
 * same answer given from the cached {@link MenuFrame} instead of from the screen, so the frame can
 * skip the pass entirely while it is over budget - see {@link MenuRenderPriority}.
 *
 * <p><b>Draw in slot-relative space.</b> By the time {@code extractSlots} runs, vanilla has already
 * pushed the pose and translated it to the GUI origin - {@code extractSlot} reads {@code slot.x} and
 * {@code slot.y} straight off the slot, and so must you. Adding {@code leftPos}/{@code topPos} again
 * lands the decoration a whole GUI's width off to the side. {@link SlotDecorations#box} exists so
 * that geometry is written once rather than re-derived per feature, which is how one of the five
 * hooks this replaced ended up drawing at double offset.
 */
public interface SlotDecorator {

    /** Stable identifier, for logs and for the duplicate check. Never display text. */
    String id();

    /**
     * Draw order, ascending - lower numbers are drawn first and therefore end up behind. Fills and
     * tints belong low, icons high, so an icon is never hidden under a later feature's wash.
     *
     * <p>The five hooks this replaced were ordered by whatever sequence the mixin processor happened
     * to apply them in, which is to say not ordered at all.
     */
    int order();

    /**
     * Whether this decoration is about the menu that is open, answered from the frame's cached
     * title rather than by re-reading the screen.
     *
     * <p>The default is {@link RenderTier#RELEVANT}: a decorator that says nothing keeps running
     * every frame, exactly as it did before this method existed. Override it only where the answer
     * is cheap and certain - and derive it from the <i>same</i> test {@link #decorate} gates on,
     * never from a second copy of it. Two tests that disagree is a feature that stops drawing on a
     * screen it was written for, with nothing in the log to say why.
     *
     * @param frame the open menu, with its title already stripped and lowercased
     */
    default RenderTier tier(MenuFrame frame) {
        return RenderTier.RELEVANT;
    }

    /**
     * Draws over the open screen's slots. Called at the tail of {@code extractSlots}, so items are
     * already drawn and the cursor item and tooltips are not yet - decorations sit between.
     *
     * @param screen the open container screen, already cast for you
     * @param g      the extractor, positioned in slot-relative space
     * @param mouseX cursor x in <b>screen</b> space, as vanilla passes it - not slot-relative
     * @param mouseY cursor y in <b>screen</b> space
     */
    void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY);
}
