/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.livid.logic;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the wool in the F5 / M5 boss-room ceiling - the arena's own tell for which Livid is real -
 * and hands it back as chat colours, the same currency the nametags are read in
 * ({@link LividTracker#color(String)}). Whether that colour actually resolves a Livid is
 * {@link LividTracker}'s call; this class only reports what is up there.
 *
 * <p>A fixed world position is unavoidable here - the wool is a block, not an entity - but it is used
 * as an <i>anchor with slack</i> rather than a single hard-coded coordinate: the patch is looked for in
 * a small box around {@link #ANCHOR}, so a ceiling that sits a couple of blocks off still reads. And
 * because the result only counts when its colour matches exactly one Livid in the room, a wrong read
 * (moved room, unloaded chunk, décor wool) resolves nothing and the tracker falls back to its
 * entity-side signals instead of naming the wrong clone.
 */
public final class LividWool {

    /**
     * The wool block in the Livid boss-room ceiling, in world coordinates. The arena is a fixed
     * build, so this is one position rather than a search - and it is the position the community has
     * long read for the same purpose, which is as close to verified as an undocumented coordinate
     * gets.
     */
    private static final BlockPos ANCHOR = new BlockPos(5, 110, 42);

    /** Horizontal slack around the anchor, in blocks. */
    private static final int RADIUS = 4;

    /** Vertical slack around the anchor, in blocks. */
    private static final int BELOW = 2;
    private static final int ABOVE = 2;

    /** Stops the sweep once the patch is clearly found; the real one is only a few blocks. */
    private static final int MAX_HITS = 32;

    /** Wool block → its dye, so a block state can be turned back into a colour. */
    private static final Map<Block, DyeColor> WOOL_DYES = woolDyes();

    private LividWool() {
    }

    /**
     * Every distinct wool colour in the ceiling patch, as chat ARGB, or empty when there is none to
     * read (outside the room, chunk not loaded yet, room moved). More than one entry means the sweep
     * caught more than one colour - the caller decides whether that is still usable.
     */
    public static List<Integer> ceilingColors(ClientLevel level) {
        List<Integer> colors = new ArrayList<>(2);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int hits = 0;
        for (int x = ANCHOR.getX() - RADIUS; x <= ANCHOR.getX() + RADIUS; x++) {
            for (int z = ANCHOR.getZ() - RADIUS; z <= ANCHOR.getZ() + RADIUS; z++) {
                for (int y = ANCHOR.getY() - BELOW; y <= ANCHOR.getY() + ABOVE; y++) {
                    BlockState state = level.getBlockState(cursor.set(x, y, z));
                    DyeColor dye = WOOL_DYES.get(state.getBlock());
                    if (dye == null) {
                        continue;
                    }
                    int argb = LividTracker.chatColor(chatCode(dye));
                    if (argb != 0 && !colors.contains(argb)) {
                        colors.add(argb);
                    }
                    if (++hits >= MAX_HITS) {
                        return colors;
                    }
                }
            }
        }
        return colors;
    }

    /** The sixteen wool blocks, indexed by block so a lookup is one map hit per scanned position. */
    private static Map<Block, DyeColor> woolDyes() {
        Map<Block, DyeColor> dyes = new HashMap<>(16);
        for (DyeColor dye : DyeColor.VALUES) {
            dyes.put(Blocks.WOOL.pick(dye), dye);
        }
        return Map.copyOf(dyes);
    }

    /**
     * The chat colour code a dye reads as on a nametag.
     *
     * <p>Nine of these are the ones the arena actually uses - red, yellow, lime, green, blue, magenta,
     * purple, grey and white, one per Livid - and those are the mapping observed in game, which is
     * worth more than a guess from the dye's own texture: <b>grey wool is §7, not §8</b>, and the
     * yellow one is yellow wool rather than orange. The rest are filled in so an unexpected dye still
     * produces a colour to compare against instead of a hole; brown has no sensible chat colour and
     * returns 0 ("no colour"), which drops the block.
     */
    private static char chatCode(DyeColor dye) {
        return switch (dye) {
            case WHITE -> 'f';
            case YELLOW -> 'e';
            case LIME -> 'a';
            case GREEN -> '2';
            case BLUE -> '9';
            case MAGENTA, PINK -> 'd';
            case PURPLE -> '5';
            case GRAY, LIGHT_GRAY -> '7';
            case RED -> 'c';
            case ORANGE -> '6';
            case LIGHT_BLUE -> 'b';
            case CYAN -> '3';
            case BLACK -> '0';
            case BROWN -> '\0';
        };
    }
}
