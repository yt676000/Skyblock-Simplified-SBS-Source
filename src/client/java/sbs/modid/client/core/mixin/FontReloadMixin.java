/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.font.FontManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.render.DigitWidths;

/**
 * Invalidates the {@link DigitWidths} tables once a resource reload has swapped in new font sets.
 * {@code apply} is the reload's apply stage and runs on the client thread.
 */
@Mixin(FontManager.class)
public abstract class FontReloadMixin {

    @Inject(method = "apply", at = @At("TAIL"))
    private void skyblockSimplified$invalidateDigitWidths(@Coerce Object preparation,
                                                         ProfilerFiller profiler, CallbackInfo ci) {
        DigitWidths.onFontReload();
    }
}
