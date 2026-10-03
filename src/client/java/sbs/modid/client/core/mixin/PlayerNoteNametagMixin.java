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
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.social.notes.logic.PlayerNotesStore;
import sbs.modid.client.social.notes.model.NoteTag;

/**
 * Player Notes' optional <b>nametag marker</b>: a coloured glyph in front of the floating name of a
 * player you tagged Trusted or Avoid. Neutral notes are not marked.
 *
 * <p>Same path as the SBS Players badge ({@code SbsBadgeMixin}): the tail of the render-state
 * extraction, where {@code state.nameTag} is the name vanilla is about to draw. The two compose -
 * each prepends to whatever the other left. The glyphs differ in shape as well as colour (a cross
 * for Avoid, a tick for Trusted), so the marker reads without telling red from green.
 */
@Mixin(AvatarRenderer.class)
public abstract class PlayerNoteNametagMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void skyblockSimplified$playerNoteMarker(Avatar entity, AvatarRenderState state,
                                                     float partialTick, CallbackInfo ci) {
        SBSConfig.PlayerNotesSettings cfg = ConfigManager.getInstance().get().playerNotes;
        // Streamer Mode hides it: on a stream the marker would publish your opinion of the player.
        if (!cfg.enabled || !cfg.nametagMarker || state.nameTag == null
                || !(entity instanceof Player player) || !sbs.modid.client.core.player.RealPlayers.isRealPlayer(entity)
                || sbs.modid.client.helper.streamer.logic.StreamerNames.getInstance().active()) {
            return;
        }
        NoteTag tag = PlayerNotesStore.getInstance()
                .markerTag(entity.getUUID(), player.getGameProfile().name());
        if (tag == null || tag == NoteTag.NEUTRAL) {
            return;
        }
        String glyph = tag == NoteTag.AVOID ? "✖ " : "✔ ";
        state.nameTag = Component.literal(glyph).withColor(tag.color() & 0xFFFFFF)
                .append(state.nameTag);
    }
}
