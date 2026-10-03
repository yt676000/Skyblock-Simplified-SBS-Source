/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.vector;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import sbs.modid.SkyblockSimplifiedSBS;

/**
 * A piece of vector artwork that lives on the GPU as a texture baked at the exact size it is drawn.
 *
 * <p>The {@link Painter} draws into a {@link VectorCanvas} in viewBox units and knows nothing about
 * pixels; this class works out the device resolution (GUI units times the GUI scale), rasterizes
 * once, and from then on the draw call is a single textured quad. The bake repeats only when the
 * target size changes – a window resize or a GUI-scale change – or when the theme recolours the
 * art, which is why {@link #draw} takes a {@code themeStamp}: it is compared against the stamp the
 * current texture was baked with.
 *
 * <p>Everything here is client-thread only, and safe to keep in a {@code static final} field: the
 * texture is created lazily on the first draw, long after the render system is up.
 */
public final class VectorImage {

    /** Draws the artwork in viewBox units. Called once per bake, never per frame. */
    @FunctionalInterface
    public interface Painter {
        void paint(VectorCanvas canvas);
    }

    /** Upper bound on a baked texture's edge, so an absurd window size cannot allocate wildly. */
    private static final int MAX_EDGE = 4096;

    private final Identifier textureId;
    private final float viewWidth;
    private final float viewHeight;
    private final Painter painter;

    private DynamicTexture texture;
    private int bakedWidth;
    private int bakedHeight;
    private int bakedStamp;

    /**
     * @param name       unique texture path, lowercase; it becomes {@code <modid>:vector/<name>}
     * @param viewWidth  viewBox width the painter draws in
     * @param viewHeight viewBox height the painter draws in
     */
    public VectorImage(String name, float viewWidth, float viewHeight, Painter painter) {
        this.textureId = SkyblockSimplifiedSBS.id("vector/" + name);
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        this.painter = painter;
    }

    /** Aspect ratio of the viewBox – what the caller needs to size the destination rectangle. */
    public float aspect() {
        return viewWidth / viewHeight;
    }

    /**
     * Draws the artwork into {@code w × h} GUI units at {@code (x, y)}.
     *
     * @param tint       ARGB multiplied over the baked pixels; {@code 0xFFFFFFFF} draws it as baked,
     *                   a lower alpha fades it (the title screen's fade-in uses this)
     * @param themeStamp any value that changes when the artwork's colors change
     */
    public void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int tint, int themeStamp) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        int deviceW = Math.min(MAX_EDGE, w * scale);
        int deviceH = Math.min(MAX_EDGE, h * scale);
        if (texture == null || bakedWidth != deviceW || bakedHeight != deviceH || bakedStamp != themeStamp) {
            bake(deviceW, deviceH, themeStamp);
        }
        if (texture == null) {
            return;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, textureId, x, y, 0F, 0F,
                w, h, bakedWidth, bakedHeight, bakedWidth, bakedHeight, tint);
    }

    /** Drops the texture, e.g. when the feature is switched off. It re-bakes on the next draw. */
    public void discard() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            texture = null;
            bakedWidth = 0;
            bakedHeight = 0;
        }
    }

    private void bake(int deviceW, int deviceH, int themeStamp) {
        VectorCanvas canvas = new VectorCanvas(deviceW, deviceH);
        // viewBox -> device: uniform-per-axis scale, so the caller's aspect choice is what decides
        // whether the art is distorted, not this class.
        canvas.scale(deviceW / viewWidth, deviceH / viewHeight);
        try {
            painter.paint(canvas);
        } catch (RuntimeException ex) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] vector art '{}' failed to rasterize", textureId, ex);
            return;
        }

        NativeImage image = new NativeImage(deviceW, deviceH, false);
        int[] pixels = canvas.pixels();
        for (int y = 0; y < deviceH; y++) {
            int row = y * deviceW;
            for (int x = 0; x < deviceW; x++) {
                image.setPixel(x, y, pixels[row + x]);
            }
        }

        TextureManager textures = Minecraft.getInstance().getTextureManager();
        textures.release(textureId); // Closes the previous texture; the id is reused every bake.
        texture = new DynamicTexture(textureId::toString, image);
        textures.register(textureId, texture);
        bakedWidth = deviceW;
        bakedHeight = deviceH;
        bakedStamp = themeStamp;
    }
}
