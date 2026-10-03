/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.logic;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicTransform;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one bridge between a schematic's block-state strings and the game's {@link BlockState}s.
 *
 * <p>Strings are the command syntax the game itself prints ({@link BlockStateParser#serialize}), so
 * a palette reads like a {@code /setblock} argument and survives game updates that renumber states.
 * Parsing is cached per string - a palette is resolved once per hologram, never per frame.
 *
 * <p>A string the game does not know (a block from a newer version, a typo in an imported file)
 * resolves to air with one log line per string, so a build with one unknown block still loads and
 * shows the rest rather than failing whole.
 */
public final class BlockStates {

    private static final Map<String, BlockState> PARSED = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** Turns palette strings with the game's own rotate / mirror rules. */
    public static final SchematicTransform.StateMapper MAPPER = new SchematicTransform.StateMapper() {
        @Override
        public String rotate(String state, int quarterTurns) {
            BlockState parsed = parse(state);
            if (parsed.isAir() && !state.equals(Schematic.AIR)) {
                return state;   // unknown here: keep the string rather than lose it to air
            }
            Rotation rotation = switch (Math.floorMod(quarterTurns, 4)) {
                case 1 -> Rotation.CLOCKWISE_90;
                case 2 -> Rotation.CLOCKWISE_180;
                case 3 -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };
            return serialize(parsed.rotate(rotation));
        }

        @Override
        public String mirror(String state, SchematicTransform.Axis axis) {
            if (axis == SchematicTransform.Axis.Y) {
                return SchematicTransform.VerticalFlip.flip(state);
            }
            BlockState parsed = parse(state);
            if (parsed.isAir() && !state.equals(Schematic.AIR)) {
                return state;
            }
            // FRONT_BACK negates X (east <-> west), LEFT_RIGHT negates Z - checked against the
            // 26.2 bytecode of Mirror.mirror(Direction), 2026-09-25.
            Mirror mirror = axis == SchematicTransform.Axis.X ? Mirror.FRONT_BACK : Mirror.LEFT_RIGHT;
            return serialize(parsed.mirror(mirror));
        }
    };

    private BlockStates() {
    }

    /** The command-syntax string of a state: {@code minecraft:oak_stairs[facing=north,...]}. */
    public static String serialize(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    /** The state a string names, or air (logged once) when the game does not know it. */
    public static BlockState parse(String state) {
        BlockState cached = PARSED.get(state);
        if (cached != null) {
            return cached;
        }
        BlockState parsed = tryParse(state);
        if (parsed == null) {
            if (WARNED.add(state)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Build] Unknown block state '{}' - shown as air", state);
            }
            parsed = Blocks.AIR.defaultBlockState();
        }
        PARSED.put(state, parsed);
        return parsed;
    }

    /** The state a string names, or {@code null} - for command arguments, where a typo is an error. */
    public static BlockState tryParse(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state.trim(), false).blockState();
        } catch (CommandSyntaxException | RuntimeException invalid) {
            return null;
        }
    }

    /** Resolves a whole palette, index for index. */
    public static BlockState[] resolve(List<String> palette) {
        BlockState[] out = new BlockState[palette.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = i == 0 ? Blocks.AIR.defaultBlockState() : parse(palette.get(i));
        }
        return out;
    }
}
