/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.visual.logic.EndVisuals;

/**
 * The End block effects (glowing purple/pink blocks, darkened end stone), applied while a chunk is
 * meshed - on the vanilla pipeline.
 *
 * <p><b>This hook is dormant whenever Fabric API is installed</b>: its rendering module reroutes
 * {@code SectionCompiler}'s meshing away from {@code ModelBlockRenderer.tesselateBlock}, so nothing
 * ever reaches {@code putQuadWithTint} and {@link EndBlockShadeFabricMixin} catches the quads on the
 * replacement pipeline instead. Exactly one of the two is live per launch. This one stays for the
 * setups where the vanilla path does run, and because it documents the vanilla-side seam.
 *
 * <p>{@code putQuadWithTint} is the one place every vanilla-meshed block quad passes on its way into
 * a section buffer, and it runs after the lighter has filled {@code quadInstance} with the quad's
 * colours and light coordinates but before they are written out - so injecting at its head is the
 * last moment at which either can still be changed. It is also where vanilla itself applies the
 * block tint, which is the same kind of edit: {@link EndVisuals} multiplies a colour on and raises
 * the block light, exactly as a tinted block or a light source would.
 *
 * <p>Chunks mesh on worker threads. {@link EndVisuals#shade} is written for that - it reads one
 * volatile snapshot the client thread publishes and touches nothing else - and returns on a single
 * branch while the feature is off, so the hot path of every player not using it stays untouched.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class EndBlockShadeMixin {

    /** The quad being emitted: its per-vertex colours and light, still editable at this point. */
    @Shadow
    @Final
    private QuadInstance quadInstance;

    @Inject(method = "putQuadWithTint", at = @At("HEAD"))
    private void skyblockSimplified$endShading(BlockQuadOutput output, float x, float y, float z,
                                               BlockAndTintGetter level, BlockState state, BlockPos pos,
                                               BakedQuad quad, CallbackInfo ci) {
        // The precedence chain, identical in EndBlockShadeFabricMixin: the location-gated effects get
        // first refusal and each says whether it claimed the quad, then Dark Mode darkens whatever is
        // left. That is what makes The End and The Mist overrides - on their island their own look
        // wins, everywhere else the world-wide setting applies. A future effect of this kind joins
        // the chain here, above the fallback.
        boolean claimed = EndVisuals.shade(state, level, pos, this.quadInstance);
        if (!claimed) {
            claimed = sbs.modid.client.helper.visual.logic.MistVisuals
                    .shade(state, level, pos, this.quadInstance);
        }
        if (!claimed) {
            sbs.modid.client.helper.visual.logic.DarkMode.shade(state, level, pos, this.quadInstance);
        }
    }
}
