/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.social.presence.SbsPresence;

/**
 * The SBS Players module's <b>nametag badge</b>: prepends the SBS icon to the floating name of any
 * player who is also running the mod.
 *
 * <p>Runs at the tail of the player's render-state extraction, where {@code state.nameTag} already
 * holds the name vanilla will draw. If the player is a known SBS user (from {@link SbsPresence}) and
 * a name is showing, the tag is rebuilt with the badge glyph in front – so it rides along with the
 * name at exactly the right place, in first and third person alike, with no separate render pass.
 */
@Mixin(AvatarRenderer.class)
public abstract class SbsBadgeMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void skyblockSimplified$sbsBadge(Avatar entity, AvatarRenderState state, float partialTick,
                                             CallbackInfo ci) {
        if (sbs.modid.client.helper.loadouts.PreviewEntities.isPreview(entity)) {
            return;   // GUI preview model: never badged, noted or rewritten
        }
        if (!ConfigManager.getInstance().get().sbsPlayers.enabled || state.nameTag == null) {
            return;
        }
        if (SbsPresence.getInstance().isSbsUser(entity.getUUID())) {
            state.nameTag = SbsPresence.badge(state.nameTag);
        }
    }
}
