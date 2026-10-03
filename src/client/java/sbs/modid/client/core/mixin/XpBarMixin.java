/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.contextualbar.ExperienceBar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.render.SBSHudRenderer;
import sbs.modid.client.ui.hud.model.XpBarMode;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

/**
 * The GUI module's <b>SBS XP bar</b>: replaces the vanilla experience bar (a
 * {@code ContextualBar} since 26.x, rendered in two passes – background sprite and progress
 * sprite) with the SBS rounded bar, exactly like the health and mana bar replacements in
 * {@link HudMixin}. The green level number above the bar is drawn separately by the HUD and stays
 * vanilla – on Hypixel it carries the skill level.
 *
 * <p>The SBS bar is its own GUI-editor element ({@link HudElement#SBS_XP_BAR}): movable, scalable
 * and hideable like the other SBS bars.
 */
@Mixin(ExperienceBar.class)
public abstract class XpBarMixin {

    private static XpBarMode sbs$mode() {
        return ConfigManager.getInstance().get().hypixelGui.xpBar;
    }

    /** First pass (background sprite): draw the whole SBS bar instead, then cancel vanilla. */
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$sbsXpBar(GuiGraphicsExtractor g, DeltaTracker deltaTracker,
                                             CallbackInfo ci) {
        XpBarMode mode = sbs$mode();
        if (!mode.rendersBar()) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (!HudLayout.isHidden(HudElement.SBS_XP_BAR)) {
            HudLayout.begin(g, HudElement.SBS_XP_BAR);
            SBSHudRenderer.renderXpBar(g, player, mode.outlined());
            HudLayout.end(g);
        }
        ci.cancel();
    }

    /** Second pass (progress sprite): already covered by the SBS bar – suppress it. */
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void skyblockSimplified$suppressProgress(GuiGraphicsExtractor g, DeltaTracker deltaTracker,
                                                     CallbackInfo ci) {
        if (sbs$mode().rendersBar()) {
            ci.cancel();
        }
    }
}
