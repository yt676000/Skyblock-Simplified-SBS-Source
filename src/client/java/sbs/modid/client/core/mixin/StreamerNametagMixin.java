/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.streamer.logic.StreamerNames;

/**
 * Streamer Mode on <b>floating names in the world</b> - the one place a name is shown that the GUI
 * text hook cannot reach, because a nametag is drawn in world space rather than submitted as GUI
 * text.
 *
 * <p>Hooked on {@link EntityRenderer} rather than on the player renderer, and that is the point:
 * every entity gets its tag from here, so this covers Hypixel's armour-stand holograms as well as
 * players. Those holograms routinely have somebody's name written into them - a kill feed, a shop
 * sign, an auction label - and a feature that hid the name over a player's head but left it on the
 * hologram beside them would be worse than none, because it would look like it was working.
 *
 * <p>Injected at the tail of {@code extractRenderState}, where {@code nameTag} and {@code scoreText}
 * are already whatever vanilla decided to draw. Subclasses reach this through {@code super}, so the
 * redaction happens before any of them add anything of their own - which is why the SBS badge still
 * rides in front of a name this has emptied.
 */
@Mixin(EntityRenderer.class)
public abstract class StreamerNametagMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
            at = @At("TAIL"))
    private void skyblockSimplified$redactNameTag(Entity entity, EntityRenderState state,
                                                  float partialTick, CallbackInfo ci) {
        if (sbs.modid.client.helper.loadouts.PreviewEntities.isPreview(entity)) {
            return;   // GUI preview model: never badged, noted or rewritten
        }
        StreamerNames streamer = StreamerNames.getInstance();
        if (!streamer.active()) {
            return;
        }
        if (state.nameTag != null) {
            state.nameTag = streamer.apply(state.nameTag);
        }
        // The score line under a tag is server-controlled text too, and on Hypixel it is where a
        // scoreboard objective's per-player value lands.
        if (state.scoreText != null) {
            state.scoreText = streamer.apply(state.scoreText);
        }
    }
}
