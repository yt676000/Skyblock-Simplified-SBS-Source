/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.components.AbstractSliderButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the protected normalised {@code value} (0..1) of a slider so the Minecraft Overlay reskin
 * can draw the SBS handle at the correct position.
 */
@Mixin(AbstractSliderButton.class)
public interface AbstractSliderButtonAccessor {

    @Accessor("value")
    double skyblockSimplified$value();
}
