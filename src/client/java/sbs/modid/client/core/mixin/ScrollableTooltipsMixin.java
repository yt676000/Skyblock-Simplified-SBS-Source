/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import sbs.modid.client.helper.tooltip.ScrollableTooltips;

import java.util.List;

/**
 * Feeds every tooltip through the {@link ScrollableTooltips} module right before it is laid out
 * and drawn.
 *
 * <p>In this version tooltips are deferred: {@code setTooltipForNextFrame(...)} only records the
 * lines, and the single method {@code GuiGraphicsExtractor#tooltip(Font, List, int, int,
 * ClientTooltipPositioner, Identifier)} does the actual positioning and drawing once per frame for
 * the active tooltip. That makes it the one clean choke point that already has the fully-built
 * {@link ClientTooltipComponent} line list (text lines <i>and</i> image lines such as the enchant
 * glint bar), so measuring the real height and swapping in a scrolled page is trivial here.
 *
 * <p>{@link ModifyVariable} rewrites the {@code components} argument in place: the module returns
 * the same list untouched when it is off or the tooltip already fits (so vanilla rendering is
 * completely unaffected), and only overflowing lore is replaced by a fitted, scrollable page.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class ScrollableTooltipsMixin {

    @ModifyVariable(method = "tooltip", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private List<ClientTooltipComponent> skyblockSimplified$scrollTooltip(List<ClientTooltipComponent> components) {
        return ScrollableTooltips.getInstance().apply(Minecraft.getInstance().font, components);
    }
}
