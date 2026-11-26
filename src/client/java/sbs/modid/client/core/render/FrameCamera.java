/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import sbs.modid.client.core.mixin.GameRendererBobAccessor;

/**
 * The camera as the HUD sees it this frame, captured once instead of once per renderer.
 *
 * <p>{@code HudMixin} fills it at the HEAD of {@code Hud.extractRenderState}, which every HUD hook
 * nests under. In 26.2 the camera is extracted <i>before</i> the GUI ({@code GameRenderer.extract}
 * runs {@code extractOptions}, {@code extractCamera} and then {@code gui.extractRenderState}) and
 * nothing moves it while the HUD is extracted, so what is captured here is exactly what each renderer
 * used to read for itself.
 *
 * <p><b>The matrix is the level's, shake included.</b> {@code GameRenderer.renderLevel} draws the world
 * with {@code projection * bob * spin * viewRotation}: {@code bob} is the damage tilt and view
 * bobbing, {@code spin} the nausea / portal warp. {@code Camera.getViewRotationProjectionMatrix} has
 * neither, so boxes projected through it stood still on screen while the world tilted underneath them
 * on every hit - vanilla's F3+B boxes, drawn with the level, never did. The same product is built here
 * from the same render state, through vanilla's own {@code bobHurt} / {@code bobView}
 * ({@link GameRendererBobAccessor}), so the damage-tilt and view-bobbing options apply exactly as they
 * do to the world.
 *
 * <p>The view-projection matrix lives in one reused {@link Matrix4f} and is handed out as a read-only
 * {@link Matrix4fc}: every world-to-screen renderer shares it, so one that changed it would break the
 * rest, and the type makes that a compile error instead.
 *
 * <p>Render thread only, and only meaningful inside the HUD extract. Anything drawn outside it must
 * read the camera itself.
 */
public final class FrameCamera {

    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    /** Scratch for vanilla's bob methods; reset to identity every frame, never allocated per frame. */
    private static final PoseStack BOB = new PoseStack();
    /** Vanilla's spin axis is {@code (0, √2/2, √2/2)}. */
    private static final float SPIN_AXIS = Mth.SQRT_OF_TWO / 2.0F;
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private static Vec3 position = Vec3.ZERO;
    private static int guiWidth;
    private static int guiHeight;

    private FrameCamera() {
    }

    /** Called once per frame from the HUD's HEAD hook. */
    public static void capture(GuiGraphicsExtractor g) {
        Minecraft minecraft = Minecraft.getInstance();
        GameRenderer renderer = minecraft.gameRenderer;
        Camera camera = renderer.mainCamera();
        LocalPlayer player = minecraft.player;
        if (minecraft.level == null || player == null) {
            // No level frame was extracted, so there is no render state to match - plain camera.
            camera.getViewRotationProjectionMatrix(VIEW_PROJECTION);
        } else {
            levelViewProjection(renderer, player, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        }
        position = camera.position();
        guiWidth = g.guiWidth();
        guiHeight = g.guiHeight();
    }

    /** {@code GameRenderer.renderLevel}'s projection, times the view rotation, into {@link #VIEW_PROJECTION}. */
    private static void levelViewProjection(GameRenderer renderer, LocalPlayer player, float partialTick) {
        GameRenderState state = renderer.gameRenderState();
        CameraRenderState cameraState = state.levelRenderState.cameraRenderState;
        OptionsRenderState options = state.optionsRenderState;
        GameRendererBobAccessor bob = (GameRendererBobAccessor) renderer;

        BOB.setIdentity();
        bob.skyblockSimplified$bobHurt(cameraState, BOB);
        if (options.bobView) {
            bob.skyblockSimplified$bobView(cameraState, BOB);
        }
        Matrix4f m = VIEW_PROJECTION.set(cameraState.projectionMatrix).mul(BOB.last().pose());

        float effectScale = options.screenEffectScale;
        float portal = Mth.lerp(partialTick, player.oPortalEffectIntensity, player.portalEffectIntensity);
        float nausea = player.getEffectBlendFactor(MobEffects.NAUSEA, partialTick);
        float spin = Math.max(portal, nausea) * (effectScale * effectScale);
        if (spin > 0.0F) {
            float skew = 5.0F / (spin * spin + 5.0F) - spin * 0.04F;
            skew *= skew;
            float angle = (bob.skyblockSimplified$spinningEffectTime()
                    + partialTick * bob.skyblockSimplified$spinningEffectSpeed()) * DEG_TO_RAD;
            m.rotate(angle, 0.0F, SPIN_AXIS, SPIN_AXIS);
            m.scale(1.0F / skew, 1.0F, 1.0F);
            m.rotate(-angle, 0.0F, SPIN_AXIS, SPIN_AXIS);
        }
        m.mul(cameraState.viewRotationMatrix);
    }

    /** This frame's view-rotation-projection matrix. Shared - read it, never cast it back to change it. */
    public static Matrix4fc viewProjection() {
        return VIEW_PROJECTION;
    }

    /** This frame's camera position. */
    public static Vec3 position() {
        return position;
    }

    public static int guiWidth() {
        return guiWidth;
    }

    public static int guiHeight() {
        return guiHeight;
    }
}
