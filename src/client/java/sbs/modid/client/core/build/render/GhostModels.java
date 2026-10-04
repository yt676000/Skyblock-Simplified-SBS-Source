/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Transformation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.build.logic.BlockStates;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Hologram blocks drawn as their real, translucent models in the world, so you see <i>which</i>
 * block goes where rather than a coloured box.
 *
 * <p>26.2 renders the level by collecting "submit nodes" and drawing them per pass later, so this
 * hooks {@link LevelRenderEvents#COLLECT_SUBMITS} and pushes one custom-geometry node holding every
 * ghost - the only world-space render path in the mod; everything else projects through
 * {@code WorldRender}. The node uses {@link Sheets#translucentBlockItemSheet()} - the block atlas
 * with blending, per-upload quad sorting and per-vertex normals - which is what vanilla's block-model
 * feature renderer draws through, so {@code putBakedQuad} lands in the format it expects. Quads are
 * emitted by hand rather than through {@code submitBlockModel}, because that hard-codes a fully
 * opaque tint and the point here is a see-through ghost.
 *
 * <p>Model parts are looked up per block state and cached, keyed on the baked model set so a resource
 * reload drops the stale quads.
 */
public final class GhostModels {

    /** Block-state models, resolved once per state. Cleared whenever the baked model set is swapped. */
    private static final Map<BlockState, List<BlockStateModelPart>> PART_CACHE = new HashMap<>();

    /** Tint sources (grass, leaves, sugar cane, ...) per state, alongside {@link #PART_CACHE}. */
    private static final Map<BlockState, Tints> TINT_CACHE = new HashMap<>();

    /**
     * Per-ghost tint arrays for biome-tinted states, reused pass after pass so a field of sugar-cane
     * ghosts does not allocate an array per ghost per frame. A slot is overwritten by the next pass
     * only after the previous one was drawn, and with the same colour anyway.
     */
    private static int[][] tintPool = new int[64][];

    /** Fixed model seed, so a state with random variants always ghosts as the same variant. */
    private static final long MODEL_SEED = 42L;

    /** Lowest usable model opacity in percent; below it the pipeline's alpha cutout eats the ghost. */
    public static final int MIN_OPACITY = GhostTint.MIN_OPACITY;

    /** {@code Direction.values()} clones its array on every call; the quad loop runs per ghost. */
    private static final Direction[] DIRECTIONS = Direction.values();

    /** The model set the caches were built from; a different instance means a resource reload. */
    private static BlockStateModelSet cachedModelSet;

    /** Longest gap after which the level-render hook counts as "not firing" (missing Fabric event). */
    private static final long HOOK_ALIVE_NANOS = 500_000_000L;

    /** Render time of the last {@link #onCollectSubmits} call, so the HUD can tell the hook is live. */
    private static volatile long lastHookNanos;

    /** Whether the hook has ever fired; {@link #lastHookNanos} is meaningless until it has. */
    private static volatile boolean hookFired;

    /** One line the first time the level hook fires, so a silent ghost can be told from a dead hook. */
    private static boolean loggedHook;

    /** One line the first time ghost geometry is actually submitted, with what it holds. */
    private static boolean loggedSubmit;

    /**
     * Probe lines already written: one per distinct fluid state and per head render type, so a single
     * paste logs what each was drawn with. Keys are canonical game objects, never built strings, so
     * the check allocates nothing per frame. {@link #armProbe} clears it.
     */
    private static final Set<Object> LOGGED_PROBES = new HashSet<>();

    /**
     * Cells (packed {@link BlockPos#asLong}) the last level pass actually submitted geometry for. The
     * HUD pass reads it to decide where the flat fill can be dropped: a ghost the model pass did not
     * draw keeps its fill, whatever its block is. Cleared at the start of every pass; reused, not
     * reallocated, because it is filled every frame.
     */
    private static final LongOpenHashSet DRAWN = new LongOpenHashSet();

    /** One ghost resolved to drawable geometry, in camera-relative space. */
    private record Piece(float x, float y, float z, float scale, int light,
                         List<BlockStateModelPart> parts, int[] tints) {
    }

    /**
     * A state's tint sources, resolved once: {@code plain} holds each source's {@code color(state)},
     * and bit {@code i} of {@code biomeMask} is set when source {@code i} answers differently in the
     * world (grass, foliage, sugar cane, water) and so is asked at the ghost's cell every frame.
     */
    private record Tints(int[] plain, List<BlockTintSource> sources, int biomeMask) {
    }

    /** One fluid box: camera-relative corner, height in blocks, tinted colour with alpha, still sprite. */
    private record FluidPiece(float x, float y, float z, float scale, float height, int light, int color,
                              TextureAtlasSprite sprite) {
    }

    /** Skull models by type; {@code Optional.empty()} for a type vanilla has no model for. */
    private static final Map<SkullBlock.Type, Optional<SkullModelBase>> SKULL_MODELS = new HashMap<>();

    /** Head profiles parsed from cell SNBT, which is otherwise re-read every frame. */
    private static final Map<String, Optional<ResolvableProfile>> PROFILE_CACHE = new HashMap<>();

    /** The entity model set {@link #SKULL_MODELS} was baked from. */
    private static EntityModelSet cachedEntityModels;

    private GhostModels() {
    }

    /** Subscribes the level-render hook. Called once from the client initializer. */
    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(GhostModels::onCollectSubmits);
    }

    private static void onCollectSubmits(LevelRenderContext context) {
        // Recorded before any early-out: it proves the Fabric event is actually wired at runtime,
        // which is what hookAlive() reports to the HUD fallback - independent of whether we draw.
        lastHookNanos = System.nanoTime();
        hookFired = true;
        DRAWN.clear();
        if (!loggedHook) {
            loggedHook = true;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Blueprint] level render hook is live");
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        GhostCollector.Frame frame = GhostCollector.collect();
        if (frame.isEmpty()) {
            return;
        }
        GhostStyle style = frame.style();
        if (!style.models()) {
            if (LOGGED_PROBES.add("models-off")) {
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Blueprint] ghost models are off for {} - outlines and flat fill only",
                        frame.hologram().owner().name());
            }
            return;
        }

        refreshCaches(minecraft);
        // Floored at MIN_OPACITY: the translucent block pipeline discards anything under alpha 0.1,
        // so a lower setting would not fade the ghost, it would delete it.
        int alpha = GhostTint.modelAlpha(style.modelOpacity());
        Vec3 camera = context.levelState().cameraRenderState.pos;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<Piece> pieces = new ArrayList<>();
        int pooled = 0;
        List<FluidPiece> fluids = new ArrayList<>();
        int heads = 0;
        for (GhostCollector.Ghost ghost : frame.ghosts()) {
            if (!showsModel(ghost.status(), style)) {
                continue;
            }
            BlockState state = ghost.wanted();
            pos.set(ghost.x(), ghost.y(), ghost.z());
            int light = LightCoordsUtil.getLightCoords(level, pos);
            // A wrong block already fills the space, so its ghost is blown up a hair to sit around
            // the real one instead of z-fighting with its faces.
            float scale = ghost.status() == GhostCollector.Status.WRONG ? 1.01f : 1.0f;
            float rx = (float) (ghost.x() - camera.x);
            float ry = (float) (ghost.y() - camera.y);
            float rz = (float) (ghost.z() - camera.z);
            if (state.getBlock() instanceof LiquidBlock) {
                if (addFluid(fluids, minecraft, level, pos, state, rx, ry, rz, scale, light, alpha,
                        GhostMatch.fluidHeight(state.getValue(LiquidBlock.LEVEL)), false)) {
                    DRAWN.add(pos.asLong());
                }
                continue;
            }
            if (state.getBlock() instanceof AbstractSkullBlock skull) {
                if (submitHead(context, minecraft, skull, state, ghost.entity(), rx, ry, rz, scale, light, alpha)) {
                    heads++;
                    DRAWN.add(pos.asLong());
                }
                continue;
            }
            if (state.getRenderShape() != RenderShape.MODEL) {
                continue; // chests, signs, banners, ...: the HUD pass keeps their flat fill instead
            }
            List<BlockStateModelPart> parts = parts(state);
            if (parts.isEmpty()) {
                continue;
            }
            Vec3 offset = state.getOffset(pos);
            Tints tints = tints(state);
            int[] colors = tints.plain();
            if (tints.biomeMask() != 0) {
                colors = pooledTints(pooled++, colors.length);
                tintInWorld(tints, state, level, pos, colors);
            }
            pieces.add(new Piece(rx + (float) offset.x, ry + (float) offset.y, rz + (float) offset.z,
                    scale, light, parts, colors));
            DRAWN.add(pos.asLong());
            if (!state.getFluidState().isEmpty()) {
                // Waterlogged: the block's own model, plus a faint wash of the water it holds.
                addFluid(fluids, minecraft, level, pos, state, rx, ry, rz, scale, light,
                        GhostTint.washAlpha(alpha), 1.0f, true);
            }
        }
        if (pieces.isEmpty() && fluids.isEmpty()) {
            return;
        }

        int baseColor = ARGB.white(alpha);
        if (!loggedSubmit) {
            loggedSubmit = true;
            // At MAX_RENDER only the nearest ghosts are kept, so a fluid or head farther out is not in
            // this frame at all - "capped" says the counts are not the whole build in radius.
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Blueprint] submitting {} ghost models, {} fluids, {} heads (of {} ghosts{}), alpha {}",
                    pieces.size(), fluids.size(), heads, frame.ghosts().size(),
                    frame.ghosts().size() >= GhostCollector.MAX_RENDER ? ", capped" : "", alpha);
        }
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(),
                Sheets.translucentBlockItemSheet(),
                (pose, buffer) -> {
                    draw(pose, buffer, pieces, baseColor);
                    drawFluids(pose, buffer, fluids);
                });
    }

    /**
     * Whether the last level pass drew geometry for the cell at {@code (x, y, z)} - a block model, a
     * fluid box or a head. The HUD pass drops its flat fill only for these: anything the model pass
     * skipped (chests, signs, banners, a head without a model, models switched off) keeps the fill
     * rather than being left as an empty outline. False while the hook is not firing, since the set
     * is then stale.
     */
    public static boolean drewModel(int x, int y, int z) {
        return hookAlive() && DRAWN.contains(BlockPos.asLong(x, y, z));
    }

    /**
     * Re-arms the one-shot diagnostics: the submit summary and one line per distinct fluid state and
     * head type, written the next time each is drawn.
     */
    public static void armProbe() {
        loggedSubmit = false;
        LOGGED_PROBES.clear();
    }

    /**
     * Queues a translucent fluid box: its still texture, tinted with the biome's water colour at the
     * cell (lava has no tint source and is orange already).
     *
     * <p>The tint is read with {@code colorInWorld}: the water source's plain {@code color(state)}
     * answers -1 (white), and water's still texture is grey, so that drew a grey box.
     *
     * @return whether a box was queued
     */
    private static boolean addFluid(List<FluidPiece> fluids, Minecraft minecraft, ClientLevel level, BlockPos pos,
                                    BlockState state, float x, float y, float z, float scale, int light,
                                    int alpha, float height, boolean wash) {
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            return false;
        }
        FluidModel model = minecraft.getModelManager().getFluidStateModelSet().get(fluid);
        BlockTintSource tint = model.tintSource();
        int tintArgb = tint == null ? -1 : tint.colorInWorld(fluid.createLegacyBlock(), level, pos);
        int color = GhostTint.fluidColor(tint != null, tintArgb, alpha);
        TextureAtlasSprite sprite = model.stillMaterial().sprite();
        fluids.add(new FluidPiece(x, y, z, scale, height, light, color, sprite));
        // Keyed on canonical instances: the block state for a liquid, its fluid state for a wash.
        if (LOGGED_PROBES.add(wash ? fluid : state)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Blueprint] fluid {}{} rgb={} alpha={} sprite={} height={}",
                    BlockStates.serialize(state), wash ? " (waterlogged wash)" : "",
                    String.format("%06X", color & 0xFFFFFF), color >>> 24, sprite.contents().name(), height);
        }
        return true;
    }

    /**
     * Submits a head through vanilla's own skull model with the cell's skin, translucent. A player
     * head without a readable profile gets the default skin; vanilla mob skulls draw through their
     * cutout render type, which ignores the ghost alpha, so those show solid.
     */
    private static boolean submitHead(LevelRenderContext context, Minecraft minecraft, AbstractSkullBlock skull,
                                      BlockState state, String entity, float x, float y, float z,
                                      float scale, int light, int alpha) {
        SkullBlock.Type type = skull.getType();
        SkullModelBase model = skullModel(minecraft, type);
        ResolvableProfile profile = type == SkullBlock.Types.PLAYER ? profile(entity) : null;
        if (model == null) {
            if (LOGGED_PROBES.add(type)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Blueprint] head {} profile={} renderType=none submitted=false",
                        type.getSerializedName(), profile != null ? "yes" : "no");
            }
            return false;
        }
        RenderType renderType;
        if (profile != null) {
            renderType = minecraft.playerSkinRenderCache().getOrDefault(profile).renderType();
        } else if (type == SkullBlock.Types.PLAYER) {
            renderType = SkullBlockRenderer.getPlayerSkinRenderType(DefaultPlayerSkin.getDefaultTexture());
        } else {
            renderType = SkullBlockRenderer.getSkullRenderType(type, null);
        }
        Transformation transformation = state.getBlock() instanceof WallSkullBlock
                ? SkullBlockRenderer.TRANSFORMATIONS.wallTransformation(state.getValue(WallSkullBlock.FACING))
                : SkullBlockRenderer.TRANSFORMATIONS.freeTransformations(state.getValue(SkullBlock.ROTATION));
        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        if (scale != 1.0f) {
            poseStack.translate(0.5f, 0.5f, 0.5f);
            poseStack.scale(scale, scale, scale);
            poseStack.translate(-0.5f, -0.5f, -0.5f);
        }
        poseStack.mulPose(transformation);
        context.submitNodeCollector().submitModel(model, new SkullModelBase.State(), poseStack, renderType,
                light, OverlayTexture.NO_OVERLAY, ARGB.white(alpha), null, 0, null);
        poseStack.popPose();
        // Render types are memoised per texture, so this logs once per distinct skin.
        if (LOGGED_PROBES.add(renderType)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Blueprint] head {} profile={} renderType={} submitted=true",
                    type.getSerializedName(), profile != null ? "yes" : "no", renderTypeName(renderType));
        }
        return true;
    }

    /** {@code entity_translucent} out of {@code RenderType[entity_translucent:...]}; the name is not exposed. */
    private static String renderTypeName(RenderType renderType) {
        String text = renderType.toString();
        int open = text.indexOf('[');
        int colon = text.indexOf(':', open + 1);
        return open >= 0 && colon > open ? text.substring(open + 1, colon) : text;
    }

    private static SkullModelBase skullModel(Minecraft minecraft, SkullBlock.Type type) {
        return SKULL_MODELS.computeIfAbsent(type,
                key -> Optional.ofNullable(SkullBlockRenderer.createModel(minecraft.getEntityModels(), key)))
                .orElse(null);
    }

    /** The head's profile from its captured block entity, or null when the cell carries none. */
    private static ResolvableProfile profile(String snbt) {
        if (snbt == null) {
            return null;
        }
        return PROFILE_CACHE.computeIfAbsent(snbt, key -> {
            try {
                Tag profile = TagParser.parseCompoundFully(key).get("profile");
                return profile == null ? Optional.empty()
                        : ResolvableProfile.CODEC.parse(NbtOps.INSTANCE, profile).result();
            } catch (Exception unreadable) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Blueprint] head data unreadable, default skin: {}",
                        unreadable.getMessage());
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Emits each fluid box - top, bottom and four sides - at its level's height. */
    private static void drawFluids(PoseStack.Pose base, VertexConsumer buffer, List<FluidPiece> fluids) {
        PoseStack.Pose pose = new PoseStack().last();
        for (int i = fluids.size() - 1; i >= 0; i--) {
            FluidPiece piece = fluids.get(i);
            pose.set(base);
            pose.translate(piece.x(), piece.y(), piece.z());
            if (piece.scale() != 1.0f) {
                pose.translate(0.5f, 0.5f, 0.5f);
                pose.scale(piece.scale(), piece.scale(), piece.scale());
                pose.translate(-0.5f, -0.5f, -0.5f);
            }
            float h = piece.height();
            TextureAtlasSprite s = piece.sprite();
            float u0 = s.getU(0f);
            float u1 = s.getU(1f);
            float v0 = s.getV(0f);
            float v1 = s.getV(1f);
            float vh = s.getV(1f - h);
            int c = piece.color();
            int l = piece.light();
            // up
            face(buffer, pose, c, l, 0, 1, 0, 0, h, 0, u0, v0, 0, h, 1, u0, v1, 1, h, 1, u1, v1, 1, h, 0, u1, v0);
            // down
            face(buffer, pose, c, l, 0, -1, 0, 0, 0, 1, u0, v1, 0, 0, 0, u0, v0, 1, 0, 0, u1, v0, 1, 0, 1, u1, v1);
            // north (z = 0)
            face(buffer, pose, c, l, 0, 0, -1, 1, h, 0, u0, vh, 1, 0, 0, u0, v1, 0, 0, 0, u1, v1, 0, h, 0, u1, vh);
            // south (z = 1)
            face(buffer, pose, c, l, 0, 0, 1, 0, h, 1, u0, vh, 0, 0, 1, u0, v1, 1, 0, 1, u1, v1, 1, h, 1, u1, vh);
            // west (x = 0)
            face(buffer, pose, c, l, -1, 0, 0, 0, h, 0, u0, vh, 0, 0, 0, u0, v1, 0, 0, 1, u1, v1, 0, h, 1, u1, vh);
            // east (x = 1)
            face(buffer, pose, c, l, 1, 0, 0, 1, h, 1, u0, vh, 1, 0, 1, u0, v1, 1, 0, 0, u1, v1, 1, h, 0, u1, vh);
        }
    }

    private static void face(VertexConsumer buffer, PoseStack.Pose pose, int color, int light,
                             float nx, float ny, float nz,
                             float x0, float y0, float z0, float ua, float va,
                             float x1, float y1, float z1, float ub, float vb,
                             float x2, float y2, float z2, float uc, float vc,
                             float x3, float y3, float z3, float ud, float vd) {
        vertex(buffer, pose, color, light, nx, ny, nz, x0, y0, z0, ua, va);
        vertex(buffer, pose, color, light, nx, ny, nz, x1, y1, z1, ub, vb);
        vertex(buffer, pose, color, light, nx, ny, nz, x2, y2, z2, uc, vc);
        vertex(buffer, pose, color, light, nx, ny, nz, x3, y3, z3, ud, vd);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, int color, int light,
                               float nx, float ny, float nz, float x, float y, float z, float u, float v) {
        buffer.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, nx, ny, nz);
    }

    /**
     * Whether a status gets a real block model. A correct block is already standing there, and a
     * removed one is going away, so neither does; a wrong one only when asked for, since its ghost has
     * to share the space with the mistake.
     */
    public static boolean showsModel(GhostCollector.Status status, GhostStyle style) {
        if (!style.models()) {
            return false;
        }
        return switch (status) {
            case MISSING -> true;
            case WRONG -> style.modelsOnWrong();
            case CORRECT, REMOVE -> false;
        };
    }

    /**
     * Whether the level-render hook has fired recently. When it has not - e.g. the Fabric level-render
     * event is unavailable on this build - no model can be drawn, so the HUD keeps its flat fill rather
     * than suppress it for a model that never appears.
     *
     * <p>The {@link #hookFired} flag carries "never fired", because a {@link Long#MIN_VALUE} seed makes
     * {@code now - lastHookNanos} overflow into a huge negative age: the hook would then read as alive
     * before it had ever run, and the flat-fill fallback would stand down for a model nothing draws.
     */
    public static boolean hookAlive() {
        return hookFired && System.nanoTime() - lastHookNanos < HOOK_ALIVE_NANOS;
    }

    /** Emits every piece's quads into the shared translucent buffer, farthest ghost first. */
    private static void draw(PoseStack.Pose base, VertexConsumer buffer, List<Piece> pieces,
                             int baseColor) {
        PoseStack.Pose pose = new PoseStack().last();
        QuadInstance quad = new QuadInstance();
        quad.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        for (int i = pieces.size() - 1; i >= 0; i--) {
            Piece piece = pieces.get(i);
            pose.set(base);
            pose.translate(piece.x(), piece.y(), piece.z());
            if (piece.scale() != 1.0f) {
                // Scale about the block centre so the shell stays concentric with the real block.
                pose.translate(0.5f, 0.5f, 0.5f);
                pose.scale(piece.scale(), piece.scale(), piece.scale());
                pose.translate(-0.5f, -0.5f, -0.5f);
            }
            quad.setLightCoords(piece.light());
            for (BlockStateModelPart part : piece.parts()) {
                for (Direction direction : DIRECTIONS) {
                    putQuads(pose, buffer, quad, part.getQuads(direction), baseColor, piece.tints());
                }
                putQuads(pose, buffer, quad, part.getQuads(null), baseColor, piece.tints());
            }
        }
    }

    private static void putQuads(PoseStack.Pose pose, VertexConsumer buffer, QuadInstance instance,
                                 List<BakedQuad> quads, int baseColor, int[] tints) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            instance.setColor(tintIndex != -1 && tintIndex < tints.length
                    ? ARGB.multiply(baseColor, tints[tintIndex])
                    : baseColor);
            buffer.putBakedQuad(pose, quad, instance);
        }
    }

    private static List<BlockStateModelPart> parts(BlockState state) {
        return PART_CACHE.computeIfAbsent(state, key -> {
            BlockStateModel model = Minecraft.getInstance().getModelManager()
                    .getBlockStateModelSet().get(key);
            List<BlockStateModelPart> collected = new ArrayList<>();
            model.collectParts(RandomSource.create(MODEL_SEED), collected);
            return List.copyOf(collected);
        });
    }

    /** A state's tint sources with their plain colours, and which of them read the world. */
    private static Tints tints(BlockState state) {
        return TINT_CACHE.computeIfAbsent(state, key -> {
            List<BlockTintSource> sources = Minecraft.getInstance().getBlockColors().getTintSources(key);
            int[] plain = new int[sources.size()];
            int biomeMask = 0;
            for (int i = 0; i < plain.length; i++) {
                plain[i] = sources.get(i).color(key);
                if (i < Integer.SIZE && readsWorld(sources.get(i))) {
                    biomeMask |= 1 << i;
                }
            }
            return new Tints(plain, sources, biomeMask);
        });
    }

    /**
     * Fills {@code out} with each source's tint at {@code pos}: world-reading sources through
     * {@code colorInWorld} (the client level caches biome colours per chunk, so once warm this is an
     * array read), the rest from their cached plain colour. In 26.2 the plain {@code color(state)} of
     * sugar cane is -1 (white) and of grass and leaves a fixed default - never the biome's colour.
     */
    private static void tintInWorld(Tints tints, BlockState state, ClientLevel level, BlockPos pos, int[] out) {
        int[] plain = tints.plain();
        for (int i = 0; i < out.length; i++) {
            boolean inWorld = i < Integer.SIZE && (tints.biomeMask() & 1 << i) != 0;
            out[i] = inWorld
                    ? GhostTint.blockColor(tints.sources().get(i).colorInWorld(state, level, pos), plain[i])
                    : plain[i];
        }
    }

    /** Pool slot {@code index} as an array of exactly {@code length}: the quad loop bounds on it. */
    private static int[] pooledTints(int index, int length) {
        if (index >= tintPool.length) {
            tintPool = Arrays.copyOf(tintPool, tintPool.length * 2);
        }
        int[] slot = tintPool[index];
        if (slot == null || slot.length != length) {
            slot = new int[length];
            tintPool[index] = slot;
        }
        return slot;
    }

    /**
     * Whether a tint source overrides {@code colorInWorld}, i.e. its colour depends on where the block
     * stands. Vanilla's biome sources are anonymous classes that override it; constant and state-only
     * ones (redstone, stems) inherit the default, which returns {@code color(state)}. Asked once per
     * state. Unreadable counts as world-reading: one tint-cache lookup too many beats a white ghost.
     *
     * <p>{@code relevantProperties()} does not answer this - it names the state properties a colour
     * depends on, and the biome sources report none.
     */
    private static boolean readsWorld(BlockTintSource source) {
        try {
            return !source.getClass()
                    .getMethod("colorInWorld", BlockState.class, BlockAndTintGetter.class, BlockPos.class)
                    .getDeclaringClass().isInterface();
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return true;
        }
    }

    /** Drops the cached quads when the models were re-baked (resource reload, resource pack swap). */
    private static void refreshCaches(Minecraft minecraft) {
        BlockStateModelSet modelSet = minecraft.getModelManager().getBlockStateModelSet();
        if (modelSet != cachedModelSet) {
            cachedModelSet = modelSet;
            PART_CACHE.clear();
            TINT_CACHE.clear();
        }
        EntityModelSet entityModels = minecraft.getEntityModels();
        if (entityModels != cachedEntityModels) {
            cachedEntityModels = entityModels;
            SKULL_MODELS.clear();
        }
        if (PROFILE_CACHE.size() > 512) {
            PROFILE_CACHE.clear();
        }
    }
}
