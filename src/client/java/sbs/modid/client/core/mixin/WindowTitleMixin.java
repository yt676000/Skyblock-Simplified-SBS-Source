/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.visual.logic.WindowTitleText;

/**
 * The text in the operating system's title bar - "Minecraft[Skyblock Simplified Mod]*26.2" instead
 * of Minecraft's own.
 *
 * <p>Hooked at the <b>return</b> of {@code createTitle} rather than at its head, and that is the
 * whole future-proofing: vanilla builds its title first and hands it over finished, so whatever
 * Mojang decides belongs in there next - a new suffix, a world name, a different separator - arrives
 * as a string this class does not have to understand. The template's {@code {vanilla}} placeholder
 * is exactly that string, so a player who wants Minecraft's title plus a mod tag can have it without
 * anybody reimplementing Minecraft's title.
 *
 * <p>{@code createTitle} is private, which is fine for a mixin and is also why the injection is
 * here: it is the single place the title is composed, and everything that re-titles the window -
 * start-up, joining a world, leaving one - goes through {@code updateTitle} into it. No polling and
 * no second code path that has to be kept in step.
 */
@Mixin(Minecraft.class)
public class WindowTitleMixin {

    @Inject(method = "createTitle()Ljava/lang/String;", at = @At("RETURN"), cancellable = true)
    private void skyblockSimplified$windowTitle(CallbackInfoReturnable<String> cir) {
        String custom = WindowTitleText.resolve(cir.getReturnValue());
        if (custom != null) {
            cir.setReturnValue(custom);
        }
    }
}
