/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import sbs.modid.client.helper.etherwarp.EtherWarpSensitivity;
import sbs.modid.client.skills.farming.logic.FarmingSensitivity;

/**
 * The one place the mod scales mouse sensitivity: the Farming module's
 * reduced-sensitivity-while-farming feature and the Ether Warp module's
 * reduced-sensitivity-while-aiming feature.
 *
 * <p>{@code MouseHandler.turnPlayer} starts by reading {@code options.sensitivity().get()} and
 * feeding it through the usual {@code (x * 0.6 + 0.2)^3 * 8} curve. Redirecting that first
 * {@code get()} (ordinal 0 – the sensitivity one; the later reads in the method are other options)
 * hands each feature the chance to scale the value for this frame only.
 *
 * <p>Both features share this single injection point, so they are composed here rather than each
 * owning a mixin of their own – two redirects on one call site would conflict. In practice only one
 * can be active at a time (each requires its own item in the main hand), but multiplying is the
 * right answer if that ever stops being true: two reductions compose to a stronger one.
 *
 * <p>Nothing is written back to the options, so the player's saved sensitivity is never modified and
 * cannot be left reduced by a crash or a disconnect – the normal value simply returns the moment the
 * feature stops applying.
 */
@Mixin(MouseHandler.class)
public abstract class MouseSensitivityMixin {

    @Redirect(method = "turnPlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;",
                    ordinal = 0))
    private Object skyblockSimplified$reduceSensitivity(OptionInstance<?> instance) {
        Object value = instance.get();
        if (value instanceof Double sensitivity) {
            return EtherWarpSensitivity.apply(FarmingSensitivity.apply(sensitivity));
        }
        return value;
    }
}
