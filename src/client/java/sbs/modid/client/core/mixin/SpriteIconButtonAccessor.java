/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.WidgetSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to a sprite icon button's icon so the SBS theme can redraw the real icon inside its
 * own rounded card (cancelling vanilla rendering would otherwise lose it).
 */
@Mixin(SpriteIconButton.class)
public interface SpriteIconButtonAccessor {

    @Accessor("sprite")
    WidgetSprites skyblockSimplified$sprite();

    @Accessor("spriteWidth")
    int skyblockSimplified$spriteWidth();

    @Accessor("spriteHeight")
    int skyblockSimplified$spriteHeight();
}
