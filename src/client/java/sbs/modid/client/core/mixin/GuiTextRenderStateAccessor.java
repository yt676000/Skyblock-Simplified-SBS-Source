/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read/write access to the colours of a queued text element, for the HUD opacity pass in
 * {@link GuiHudOpacityMixin}.
 *
 * <p>Unlike the coloured-geometry states, {@code GuiTextRenderState} is a plain class whose font and
 * text are private, so a faded copy cannot be built from the outside – the colour is edited in place
 * instead. Safe because the object is freshly constructed per draw call and its glyphs are only
 * prepared later, at render time.
 */
@Mixin(GuiTextRenderState.class)
public interface GuiTextRenderStateAccessor {

    @Accessor("color")
    int skyblockSimplified$getColor();

    @Mutable
    @Accessor("color")
    void skyblockSimplified$setColor(int color);

    @Accessor("backgroundColor")
    int skyblockSimplified$getBackgroundColor();

    @Mutable
    @Accessor("backgroundColor")
    void skyblockSimplified$setBackgroundColor(int color);
}
