/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import sbs.modid.client.dungeons.bossbar.logic.BossHealthModel;

/**
 * Boss Bar Health: swaps the name vanilla draws over each boss bar for the prepared one with the
 * health appended. Wrapping {@code getName()} rather than the text call keeps vanilla's own
 * centring - it measures the width of whatever this returns.
 *
 * <p>Per frame per bar: a map lookup and a reference compare, no allocation. The component itself
 * is built on the tick by {@link BossHealthModel}.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/LerpingBossEvent;getName()"
                    + "Lnet/minecraft/network/chat/Component;"))
    private Component skyblockSimplified$bossBarHealth(LerpingBossEvent event, Operation<Component> original) {
        Component name = original.call(event);
        return BossHealthModel.getInstance().displayName(event.getId(), name);
    }
}
