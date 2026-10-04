/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.transparency;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Wraps the collector the local player's render state submits into while it fades: every model
 * (body, armour, trims, cape, elytra, worn skull - all of them end in {@code submitModel}) gets its
 * colour alpha scaled, and held items, which carry no render type of their own here, are re-drawn
 * as translucent geometry. The render types themselves are swapped to their translucent twins by
 * the {@code RenderTypes} / {@code Sheets} mixins while {@link OwnPlayerTransparency#scope} is set.
 *
 * <p>One instance per collector order, reused every frame (render thread only), so the wrapper
 * itself allocates nothing while on. Name tags, text and shadows pass through untouched.
 */
public final class TranslucentSubmitCollector implements SubmitNodeCollector {

    private static final TranslucentSubmitCollector ROOT = new TranslucentSubmitCollector();
    private static final java.util.Map<Integer, Ordered> ORDERS = new java.util.HashMap<>();

    /** Reused for every faded item quad; geometry is drawn on the render thread only. */
    private static final QuadInstance QUAD = new QuadInstance();

    private SubmitNodeCollector delegate;
    private int alpha = OwnPlayerTransparency.OPAQUE;

    private TranslucentSubmitCollector() {
    }

    /** The shared wrapper around {@code delegate}, fading by {@code alpha}. */
    public static SubmitNodeCollector wrap(SubmitNodeCollector delegate, int alpha) {
        ROOT.delegate = delegate;
        ROOT.alpha = alpha;
        return ROOT;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        Ordered wrapper = ORDERS.computeIfAbsent(order, k -> new Ordered());
        wrapper.delegate = delegate.order(order);
        wrapper.alpha = alpha;
        return wrapper;
    }

    // ------------------------------------------------------------------ the faded submits

    static <S> void model(OrderedSubmitNodeCollector to, int alpha, Model<? super S> model, S state, PoseStack pose,
                          RenderType renderType, int light, int overlay, int color, TextureAtlasSprite sprite,
                          int outline, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        // The enchant glint is an additive pass that ignores alpha: drawn at full strength over a
        // faded piece it would read as a solid shimmer, so it is left out while faded.
        if (renderType == RenderTypes.armorEntityGlint() || renderType == RenderTypes.entityGlint()) {
            return;
        }
        to.submitModel(model, state, pose, renderType, light, overlay,
                OwnPlayerTransparency.multiplyAlpha(color, alpha), sprite, outline, crumbling);
    }

    /**
     * A held item as translucent geometry: one custom-geometry submit per atlas, the quads' tint
     * applied as vanilla's item renderer does, with the fade in the alpha. The glint is not drawn
     * while faded (it is its own opaque pass).
     */
    static void item(OrderedSubmitNodeCollector to, int alpha, PoseStack pose, int light, int overlay, int[] tints,
                     List<BakedQuad> quads) {
        Identifier atlas = null;
        for (BakedQuad quad : quads) {
            Identifier a = quad.materialInfo().sprite().atlasLocation();
            if (atlas == null || !atlas.equals(a)) {
                atlas = a;
                Identifier current = a;
                to.submitCustomGeometry(pose, RenderTypes.itemTranslucent(current), (p, vc) -> {
                    QuadInstance instance = QUAD;
                    instance.setLightCoords(light);
                    instance.setOverlayCoords(overlay);
                    for (BakedQuad q : quads) {
                        if (!q.materialInfo().sprite().atlasLocation().equals(current)) {
                            continue;
                        }
                        BakedQuad.MaterialInfo info = q.materialInfo();
                        int tint = info.isTinted() && info.tintIndex() >= 0 && info.tintIndex() < tints.length
                                ? tints[info.tintIndex()] : -1;
                        instance.setColor(OwnPlayerTransparency.multiplyAlpha(tint, alpha));
                        vc.putBakedQuad(p, q, instance);
                    }
                });
            }
        }
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType renderType, int light,
                                int overlay, int color, TextureAtlasSprite sprite, int outline,
                                ModelFeatureRenderer.CrumblingOverlay crumbling) {
        model(delegate, alpha, model, state, pose, renderType, light, overlay, color, sprite, outline, crumbling);
    }

    @Override
    public void submitItem(PoseStack pose, ItemDisplayContext context, int light, int overlay, int outline,
                           int[] tints, List<BakedQuad> quads, ItemStackRenderState.FoilType foil) {
        item(delegate, alpha, pose, light, overlay, tints, quads);
    }

    // ------------------------------------------------------------------ passed through

    @Override
    public void submitShadow(PoseStack pose, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        delegate.submitShadow(pose, radius, pieces);
    }

    @Override
    public void submitNameTag(PoseStack pose, Vec3 pos, int y, Component text, boolean seeThrough, int light,
                              CameraRenderState camera) {
        delegate.submitNameTag(pose, pos, y, text, seeThrough, light, camera);
    }

    @Override
    public void submitText(PoseStack pose, float x, float y, FormattedCharSequence text, boolean shadow,
                           Font.DisplayMode mode, int light, int color, int background, int outline) {
        delegate.submitText(pose, x, y, text, shadow, mode, light, color, background, outline);
    }

    @Override
    public void submitFlame(PoseStack pose, EntityRenderState state, Quaternionf rotation) {
        delegate.submitFlame(pose, state, rotation);
    }

    @Override
    public void submitLeash(PoseStack pose, EntityRenderState.LeashState leash) {
        delegate.submitLeash(pose, leash);
    }

    @Override
    public void submitMovingBlock(PoseStack pose, MovingBlockRenderState state, int light) {
        delegate.submitMovingBlock(pose, state, light);
    }

    @Override
    public void submitBlockModel(PoseStack pose, RenderType renderType, List<BlockStateModelPart> parts, int[] tints,
                                 int light, int overlay, int outline) {
        delegate.submitBlockModel(pose, renderType, parts, tints, light, overlay, outline);
    }

    @Override
    public void submitBreakingBlockModel(PoseStack pose, List<BlockStateModelPart> parts, int progress) {
        delegate.submitBreakingBlockModel(pose, parts, progress);
    }

    @Override
    public void submitShapeOutline(PoseStack pose, VoxelShape shape, RenderType renderType, int color, float width,
                                   boolean flag) {
        delegate.submitShapeOutline(pose, shape, renderType, color, width, flag);
    }

    @Override
    public void submitCustomGeometry(PoseStack pose, RenderType renderType, CustomGeometryRenderer renderer) {
        delegate.submitCustomGeometry(pose, renderType, renderer);
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState state) {
        delegate.submitQuadParticleGroup(state);
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean flag) {
        delegate.submitGizmoPrimitives(group, camera, flag);
    }

    @Override
    public <T extends net.minecraft.client.renderer.feature.submit.SubmitNode> void submitCustom(
            net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase<T> phase, T node) {
        delegate.submitCustom(phase, node);
    }

    /** The same fade for one collector order (layers may submit into {@code order(n)}). */
    private static final class Ordered implements OrderedSubmitNodeCollector {

        private OrderedSubmitNodeCollector delegate;
        private int alpha;

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType renderType, int light,
                                    int overlay, int color, TextureAtlasSprite sprite, int outline,
                                    ModelFeatureRenderer.CrumblingOverlay crumbling) {
            model(delegate, alpha, model, state, pose, renderType, light, overlay, color, sprite, outline, crumbling);
        }

        @Override
        public void submitItem(PoseStack pose, ItemDisplayContext context, int light, int overlay, int outline,
                               int[] tints, List<BakedQuad> quads, ItemStackRenderState.FoilType foil) {
            item(delegate, alpha, pose, light, overlay, tints, quads);
        }

        @Override
        public void submitShadow(PoseStack pose, float radius, List<EntityRenderState.ShadowPiece> pieces) {
            delegate.submitShadow(pose, radius, pieces);
        }

        @Override
        public void submitNameTag(PoseStack pose, Vec3 pos, int y, Component text, boolean seeThrough, int light,
                                  CameraRenderState camera) {
            delegate.submitNameTag(pose, pos, y, text, seeThrough, light, camera);
        }

        @Override
        public void submitText(PoseStack pose, float x, float y, FormattedCharSequence text, boolean shadow,
                               Font.DisplayMode mode, int light, int color, int background, int outline) {
            delegate.submitText(pose, x, y, text, shadow, mode, light, color, background, outline);
        }

        @Override
        public void submitFlame(PoseStack pose, EntityRenderState state, Quaternionf rotation) {
            delegate.submitFlame(pose, state, rotation);
        }

        @Override
        public void submitLeash(PoseStack pose, EntityRenderState.LeashState leash) {
            delegate.submitLeash(pose, leash);
        }

        @Override
        public void submitMovingBlock(PoseStack pose, MovingBlockRenderState state, int light) {
            delegate.submitMovingBlock(pose, state, light);
        }

        @Override
        public void submitBlockModel(PoseStack pose, RenderType renderType, List<BlockStateModelPart> parts,
                                     int[] tints, int light, int overlay, int outline) {
            delegate.submitBlockModel(pose, renderType, parts, tints, light, overlay, outline);
        }

        @Override
        public void submitBreakingBlockModel(PoseStack pose, List<BlockStateModelPart> parts, int progress) {
            delegate.submitBreakingBlockModel(pose, parts, progress);
        }

        @Override
        public void submitShapeOutline(PoseStack pose, VoxelShape shape, RenderType renderType, int color,
                                       float width, boolean flag) {
            delegate.submitShapeOutline(pose, shape, renderType, color, width, flag);
        }

        @Override
        public void submitCustomGeometry(PoseStack pose, RenderType renderType, CustomGeometryRenderer renderer) {
            delegate.submitCustomGeometry(pose, renderType, renderer);
        }

        @Override
        public void submitQuadParticleGroup(QuadParticleRenderState state) {
            delegate.submitQuadParticleGroup(state);
        }

        @Override
        public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean flag) {
            delegate.submitGizmoPrimitives(group, camera, flag);
        }

        @Override
        public <T extends net.minecraft.client.renderer.feature.submit.SubmitNode> void submitCustom(
                net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase<T> phase, T node) {
            delegate.submitCustom(phase, node);
        }
    }
}
