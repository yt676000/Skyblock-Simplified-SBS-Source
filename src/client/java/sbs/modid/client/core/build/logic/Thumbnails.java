/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.logic;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.build.io.ThumbnailRaster;
import sbs.modid.client.core.build.model.Schematic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Thumbnails for the build library: drawing one ({@link ThumbnailRaster}, coloured from each block's
 * map colour) and writing it as a PNG beside its build, and loading the PNGs back as textures for the
 * Quick Paste grid.
 *
 * <p>Writing runs on the library's background thread; loading and releasing textures only on the
 * render thread, where the texture manager lives.
 */
public final class Thumbnails {

    /** The colour a block with no map colour (glass, air-like blocks) is drawn in. */
    private static final int NO_MAP_COLOUR = 0xB8C4CC;

    private static final Map<String, Loaded> TEXTURES = new HashMap<>();

    /** A thumbnail registered as a texture, and the file stamp it was loaded from. */
    public record Loaded(Identifier id, long modified) {
    }

    private Thumbnails() {
    }

    /** One RGB per palette entry, from each block's map colour. */
    public static int[] colours(List<String> palette) {
        int[] out = new int[palette.size()];
        BlockState[] states = BlockStates.resolve(palette);
        for (int i = 1; i < out.length; i++) {
            MapColor colour = states[i].getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            out[i] = colour == MapColor.NONE || colour.col == 0 ? NO_MAP_COLOUR : colour.col;
        }
        return out;
    }

    /** Draws {@code schematic} and writes it to {@code target} as a PNG. */
    public static void write(Schematic schematic, Path target) throws IOException {
        int[] pixels = ThumbnailRaster.render(schematic, colours(schematic.palette()));
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (NativeImage image = new NativeImage(ThumbnailRaster.SIZE, ThumbnailRaster.SIZE, false)) {
            for (int y = 0; y < ThumbnailRaster.SIZE; y++) {
                for (int x = 0; x < ThumbnailRaster.SIZE; x++) {
                    image.setPixel(x, y, pixels[y * ThumbnailRaster.SIZE + x]);
                }
            }
            Files.createDirectories(target.getParent());
            image.writeToFile(temp);
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * The texture for a build's thumbnail, loaded on first use and reloaded when the file changed;
     * {@code null} when there is no PNG (the grid draws a placeholder). Render thread only.
     */
    public static Identifier texture(String slug, Path png) {
        // Served from memory once looked up - including "there is none" - so the grid does not stat
        // a file per card per frame. forget() and releaseAll() are how a changed file is seen again.
        Loaded loaded = TEXTURES.get(slug);
        if (loaded != null) {
            return loaded.id();
        }
        long modified;
        try {
            if (!Files.isRegularFile(png)) {
                TEXTURES.put(slug, new Loaded(null, 0L));
                return null;
            }
            modified = Files.getLastModifiedTime(png).toMillis();
        } catch (IOException unreadable) {
            TEXTURES.put(slug, new Loaded(null, 0L));
            return null;
        }
        Identifier id = Identifier.fromNamespaceAndPath("sbs", "build_thumb/" + sanitise(slug));
        try (InputStream in = Files.newInputStream(png)) {
            NativeImage image = NativeImage.read(in);
            Minecraft.getInstance().getTextureManager().release(id);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
            TEXTURES.put(slug, new Loaded(id, modified));
            return id;
        } catch (IOException | RuntimeException unreadable) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Build] Thumbnail {} could not be loaded: {}", png, unreadable.getMessage());
            TEXTURES.put(slug, new Loaded(null, modified));
            return null;
        }
    }

    /** The id of the Build Library's large preview texture, re-uploaded for every view. */
    public static final Identifier PREVIEW = Identifier.fromNamespaceAndPath("sbs", "build_preview");

    /** Uploads {@code SIZE x SIZE} ARGB pixels as the preview texture. Render thread only. */
    public static Identifier uploadPreview(int[] pixels) {
        NativeImage image = new NativeImage(ThumbnailRaster.SIZE, ThumbnailRaster.SIZE, false);
        for (int y = 0; y < ThumbnailRaster.SIZE; y++) {
            for (int x = 0; x < ThumbnailRaster.SIZE; x++) {
                image.setPixel(x, y, pixels[y * ThumbnailRaster.SIZE + x]);
            }
        }
        Minecraft.getInstance().getTextureManager().release(PREVIEW);
        Minecraft.getInstance().getTextureManager().register(PREVIEW, new DynamicTexture(PREVIEW::toString, image));
        return PREVIEW;
    }

    /** Frees the preview texture. Render thread only. */
    public static void releasePreview() {
        Minecraft.getInstance().getTextureManager().release(PREVIEW);
    }

    /** Forgets one thumbnail so the next draw looks at its file again. Render thread only. */
    public static void forget(String slug) {
        Loaded loaded = TEXTURES.remove(slug);
        if (loaded != null && loaded.id() != null) {
            Minecraft.getInstance().getTextureManager().release(loaded.id());
        }
    }

    /** Resource paths allow a-z, 0-9, _ . - / only; slugs already are that, this guards imports. */
    private static String sanitise(String slug) {
        return slug.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    /** Frees every loaded thumbnail texture - when the Quick Paste grid closes. Render thread only. */
    public static void releaseAll() {
        for (Loaded loaded : TEXTURES.values()) {
            if (loaded.id() != null) {
                Minecraft.getInstance().getTextureManager().release(loaded.id());
            }
        }
        TEXTURES.clear();
    }
}
