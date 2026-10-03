/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the render-distance option's value range be replaced after construction.
 *
 * <p>Vanilla builds it as {@code new IntRange(2, 32)} inline in the {@code Options} constructor, so
 * the only way to widen the slider without an ordinal-fragile constructor redirect is to swap the
 * whole {@code ValueSet} on that one instance afterwards. Swapping the set is enough: the slider
 * widget reads its bounds from it every time it is drawn.
 */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor<T> {

    @Mutable
    @Accessor("values")
    void skyblockSimplified$setValues(OptionInstance.ValueSet<T> values);

    @Accessor("values")
    OptionInstance.ValueSet<T> skyblockSimplified$values();
}
