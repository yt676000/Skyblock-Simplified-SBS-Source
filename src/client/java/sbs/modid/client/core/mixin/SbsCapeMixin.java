/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.cape.CapeModule;

/**
 * The Wardrobe module's <b>SBS cape</b>: swaps the cape texture into the player's render state.
 *
 * <p>Runs at the tail of the render-state extraction, the same hook and the same target as
 * {@link SbsBadgeMixin}, because that is where the state vanilla is about to draw from is finished
 * and still writable.
 *
 * <p><b>Two fields have to change, and the second is the one that is easy to miss.</b>
 * {@code CapeLayer.submit} refuses to draw in three places: when the player is invisible or
 * {@code showCape} is false, when {@code skin.cape()} is null, and when the chest slot carries
 * something with a wings layer. Setting the texture alone therefore draws nothing for most players:
 * {@code showCape} is a copy of {@code Avatar.isModelPartShown(PlayerModelPart.CAPE)}, which reads
 * the <i>server-synched</i> skin-customisation byte - not a local option a mod could set - and it is
 * false for anyone who has capes switched off or whose server never set the bit. So the state's
 * {@code showCape} is forced true alongside the texture.
 *
 * <p><b>The elytra is left alone deliberately.</b> {@code WingsLayer.getPlayerElytraTexture} falls
 * back to the cape texture whenever the skin has no elytra of its own and {@code showCape} is true,
 * so forcing the cape onto a player who is wearing an elytra would silently re-skin their wings with
 * it. Nothing is gained by doing it anyway - {@code CapeLayer} would not draw the cape under a wings
 * item regardless - so a worn elytra makes this stand down entirely.
 *
 * <p>Only the client's own player is touched. Every other player renders exactly as the server
 * described them, which is also why nobody else ever sees this cape.
 */
@Mixin(AvatarRenderer.class)
public abstract class SbsCapeMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void skyblockSimplified$sbsCape(Avatar entity, AvatarRenderState state, float partialTick,
                                            CallbackInfo ci) {
        if (!ConfigManager.getInstance().get().wardrobe.sbsCape || state.skin == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !entity.getUUID().equals(minecraft.player.getUUID())) {
            return;
        }
        // A wings item in the chest slot means the cape is not drawn anyway, and forcing it here
        // would repaint the elytra with the cape texture through WingsLayer's fallback.
        if (state.chestEquipment != null && state.chestEquipment.is(Items.ELYTRA)) {
            return;
        }
        PlayerSkin skin = state.skin;
        state.skin = new PlayerSkin(skin.body(),
                new ClientAsset.ResourceTexture(CapeModule.TEXTURE, CapeModule.TEXTURE),
                skin.elytra(), skin.model(), skin.secure());
        state.showCape = true;
    }
}
