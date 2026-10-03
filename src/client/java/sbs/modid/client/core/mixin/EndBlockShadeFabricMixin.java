/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.helper.visual.logic.EndVisuals;

/**
 * The End block effects on the pipeline that actually meshes chunks when Fabric API is installed.
 *
 * <p>Fabric API's rendering module replaces vanilla's block meshing wholesale: its own mixin turns
 * {@code SectionCompiler}'s call to {@code ModelBlockRenderer.tesselateBlock} into a call to the
 * Fabric renderer, so {@code putQuadWithTint} - and with it {@link EndBlockShadeMixin} - never runs
 * for terrain. Its counterpart funnel is {@code AltModelBlockRendererImpl.transform}: the renderer
 * registers itself as the quad transform every emitted block quad passes through, and by the time it
 * returns {@code true} the quad has been lit, ambient-occluded and tinted - the same "last editable
 * moment" the vanilla hook catches. The renderer keeps the block being meshed in fields, which is
 * where this mixin reads it from.
 *
 * <p>{@link Pseudo} plus {@code require = 0} because the target is another mod's implementation
 * class, not a mapped Minecraft name: without Fabric API's renderer module the class does not exist,
 * and a future rename must degrade to "effect off" (the {@code [SBS][End]} mesh counters make that
 * visible immediately) rather than crash the game. Exactly one of the two hooks is ever live - this
 * one whenever the Fabric renderer is present, the vanilla one otherwise - so nothing double-shades.
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl",
        remap = false)
public abstract class EndBlockShadeFabricMixin {

    /** The block whose quads are currently being emitted, set for the whole of its tesselation. */
    @Shadow
    private BlockState blockState;

    @Shadow
    private BlockAndTintGetter level;

    @Shadow
    private BlockPos pos;

    /** RETURN rather than TAIL: the culled-quad exits return {@code false} and must stay untouched. */
    @Inject(method = "transform", at = @At("RETURN"), require = 0)
    private void skyblockSimplified$endShading(MutableQuadView quad,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && this.blockState != null) {
            // The precedence chain, identical in EndBlockShadeMixin: the location-gated effects get
            // first refusal and each says whether it claimed the quad, then Dark Mode darkens
            // whatever is left. That is what makes The End and The Mist overrides - on their island
            // their own look wins, everywhere else the world-wide setting applies. A future effect
            // of this kind joins the chain here, above the fallback.
            boolean claimed = EndVisuals.shade(this.blockState, this.level, this.pos, quad);
            if (!claimed) {
                claimed = sbs.modid.client.helper.visual.logic.MistVisuals
                        .shade(this.blockState, this.level, this.pos, quad);
            }
            if (!claimed) {
                sbs.modid.client.helper.visual.logic.DarkMode
                        .shade(this.blockState, this.level, this.pos, quad);
            }
        }
    }
}
