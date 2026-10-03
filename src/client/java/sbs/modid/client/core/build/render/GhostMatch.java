/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.render;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

import java.util.Arrays;

/**
 * The parts of "does the world match the hologram" that are more than {@code getBlock() ==}: fluid
 * levels and head skins. Kept free of {@code BlockState} so it is unit-testable without a registry.
 */
public final class GhostMatch {

    /** Highest {@code level} a liquid block has before it counts as falling (8 and up). */
    private static final int FALLING = 8;

    private GhostMatch() {
    }

    /**
     * Height of a fluid ghost for a liquid block's {@code level} property: a source (0) and a falling
     * column (8+) fill the block, flowing levels 1-7 step down by ninths the way vanilla's amount does.
     */
    public static float fluidHeight(int level) {
        if (level <= 0 || level >= FALLING) {
            return 1.0f;
        }
        return (FALLING - level) / 9.0f;
    }

    /**
     * Whether two liquid levels count as the same fluid. Exact: a source is not a flowing block, and
     * one flowing step is not another - a plot rebuilt with the wrong water levels floods differently.
     */
    public static boolean sameFluidLevel(int wanted, int actual) {
        return wanted == actual;
    }

    /**
     * Whether a head in the world wears the hologram's skin. A hologram cell without a skin - copied
     * before block entities were captured, or a vanilla mob skull - has nothing to compare and always
     * matches; otherwise the skins must be the same, and a head with no data at all is a wrong one.
     */
    public static boolean sameSkin(String wantedSnbt, String actualSnbt) {
        String wanted = skinKey(wantedSnbt);
        if (wanted == null) {
            return true;
        }
        return wanted.equals(skinKey(actualSnbt));
    }

    /**
     * What identifies a head's skin in its block-entity SNBT: the texture property when there is one
     * (Hypixel heads are all texture, with random or absent ids), else the profile id, else the name.
     * {@code null} when the data holds no profile.
     */
    public static String skinKey(String snbt) {
        if (snbt == null || snbt.isEmpty()) {
            return null;
        }
        CompoundTag tag;
        try {
            tag = TagParser.parseCompoundFully(snbt);
        } catch (Exception unreadable) {
            return null;
        }
        Tag profile = tag.get("profile");
        if (profile == null) {
            return null;
        }
        if (profile instanceof CompoundTag compound) {
            String texture = texture(compound.get("properties"));
            if (texture != null) {
                return "texture:" + texture;
            }
            if (compound.getIntArray("id").isPresent()) {
                return "id:" + Arrays.toString(compound.getIntArray("id").get());
            }
            return compound.getString("name").map(name -> "name:" + name).orElse(null);
        }
        return profile.asString().map(name -> "name:" + name).orElse(null);
    }

    private static String texture(Tag properties) {
        if (properties instanceof ListTag list) {
            for (Tag entry : list) {
                if (entry instanceof CompoundTag property
                        && "textures".equals(property.getString("name").orElse(null))) {
                    return property.getString("value").orElse(null);
                }
            }
        } else if (properties instanceof CompoundTag map) {
            // The older map form: {textures:[{value:"..."}]}
            if (map.get("textures") instanceof ListTag values && !values.isEmpty()
                    && values.get(0) instanceof CompoundTag first) {
                return first.getString("value").orElse(null);
            }
        }
        return null;
    }
}
