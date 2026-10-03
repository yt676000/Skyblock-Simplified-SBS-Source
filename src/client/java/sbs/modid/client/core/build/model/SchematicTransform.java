/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rotation and mirroring of a whole {@link Schematic} - the cells move, and each palette entry is
 * turned the same way so stairs keep facing the wall they were built against.
 *
 * <p>Positions are pure maths here. What a state becomes when turned (an east-facing stair after a
 * quarter turn) is the game's knowledge, so it is asked of a {@link StateMapper}: in game
 * {@code BlockStates.MAPPER} (backed by {@code BlockState.rotate / mirror}), in tests an identity or a
 * fake. The mapper is called once per palette entry, never per cell.
 *
 * <p>Conventions, matching the game's: a quarter turn is <b>clockwise seen from above</b> (north goes
 * to east), and {@link Axis#X} mirrors east/west ({@code x -> W-1-x}), {@link Axis#Z} north/south,
 * {@link Axis#Y} upside down.
 */
public final class SchematicTransform {

    /** A mirror plane, named by the axis whose coordinate is negated. */
    public enum Axis {
        X, Y, Z;

        /** {@code x}, {@code y} or {@code z} (case-insensitive), else {@code null}. */
        public static Axis parse(String value) {
            if (value == null) {
                return null;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "x", "ew", "eastwest", "east-west" -> X;
                case "y", "ud", "updown", "up-down", "vertical" -> Y;
                case "z", "ns", "northsouth", "north-south" -> Z;
                default -> null;
            };
        }
    }

    /** Turns one block-state string. Implementations must be pure functions of their inputs. */
    public interface StateMapper {

        /** {@code state} after {@code quarterTurns} (1-3) clockwise quarter turns about Y. */
        String rotate(String state, int quarterTurns);

        /** {@code state} mirrored across {@code axis}. */
        String mirror(String state, Axis axis);

        /** Leaves every state as it is - positions still move. */
        StateMapper IDENTITY = new StateMapper() {
            @Override
            public String rotate(String state, int quarterTurns) {
                return state;
            }

            @Override
            public String mirror(String state, Axis axis) {
                return axis == Axis.Y ? VerticalFlip.flip(state) : state;
            }
        };
    }

    private SchematicTransform() {
    }

    /** Normalises any number of degrees to 0-3 clockwise quarter turns; {@code -1} if not a multiple of 90. */
    public static int quarterTurns(int degrees) {
        if (degrees % 90 != 0) {
            return -1;
        }
        return Math.floorMod(degrees / 90, 4);
    }

    /**
     * {@code source} turned {@code quarterTurns} times clockwise about Y. Width and length swap on an
     * odd count. The origin, if any, is dropped - a turned build no longer sits where it was copied.
     */
    public static Schematic rotate(Schematic source, int quarterTurns, StateMapper mapper) {
        int turns = Math.floorMod(quarterTurns, 4);
        if (turns == 0) {
            return source;
        }
        int w = source.width();
        int h = source.height();
        int l = source.length();
        int newW = (turns % 2 == 0) ? w : l;
        int newL = (turns % 2 == 0) ? l : w;
        List<String> palette = new ArrayList<>(source.palette().size());
        for (String state : source.palette()) {
            palette.add(state.equals(Schematic.AIR) ? state : mapper.rotate(state, turns));
        }
        return remap(source, newW, h, newL, palette, (x, y, z) -> {
            int nx;
            int nz;
            switch (turns) {
                case 1 -> {       // (x, z) -> (L-1-z, x)
                    nx = l - 1 - z;
                    nz = x;
                }
                case 2 -> {
                    nx = w - 1 - x;
                    nz = l - 1 - z;
                }
                default -> {      // three turns: (x, z) -> (z, W-1-x)
                    nx = z;
                    nz = w - 1 - x;
                }
            }
            return nx + newW * (nz + newL * y);
        });
    }

    /** {@code source} mirrored across {@code axis}. Size is unchanged. */
    public static Schematic flip(Schematic source, Axis axis, StateMapper mapper) {
        int w = source.width();
        int h = source.height();
        int l = source.length();
        List<String> palette = new ArrayList<>(source.palette().size());
        for (String state : source.palette()) {
            palette.add(state.equals(Schematic.AIR) ? state : mapper.mirror(state, axis));
        }
        return remap(source, w, h, l, palette, (x, y, z) -> switch (axis) {
            case X -> (w - 1 - x) + w * (z + l * y);
            case Y -> x + w * (z + l * (h - 1 - y));
            case Z -> x + w * ((l - 1 - z) + l * y);
        });
    }

    /** Where a cell goes: the new cell index for old {@code (x, y, z)}. */
    private interface CellMap {
        int to(int x, int y, int z);
    }

    /**
     * Moves every cell through {@code map} under a transformed palette. Two old entries may turn into
     * the same new string (a symmetric block), so the palette is re-interned rather than kept
     * index-for-index - otherwise equal states would count as different blocks everywhere after.
     */
    private static Schematic remap(Schematic source, int newW, int newH, int newL,
                                   List<String> mappedPalette, CellMap map) {
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        int[] remapIndex = new int[mappedPalette.size()];
        for (int i = 0; i < mappedPalette.size(); i++) {
            String state = i == 0 ? Schematic.AIR : mappedPalette.get(i);
            Integer existing = index.get(state);
            if (existing == null) {
                existing = palette.size();
                palette.add(state);
                index.put(state, existing);
            }
            remapIndex[i] = existing;
        }
        char[] cells = new char[newW * newH * newL];
        Map<Integer, String> entities = new HashMap<>();
        int w = source.width();
        int h = source.height();
        int l = source.length();
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    int from = x + w * (z + l * y);
                    int to = map.to(x, y, z);
                    cells[to] = (char) remapIndex[source.paletteAt(from)];
                    String entity = source.blockEntities().get(from);
                    if (entity != null) {
                        entities.put(to, entity);
                    }
                }
            }
        }
        return Schematic.fromRaw(newW, newH, newL, palette, cells, entities,
                source.header().withOrigin(null));
    }

    /**
     * Upside-down mirroring of a state string, which the game has no {@code Mirror} for.
     *
     * <p>Swaps the property values that mean "top" and "bottom": stair and trapdoor {@code half},
     * slab {@code type}, {@code facing} and {@code vertical_direction} up/down, and button/lever
     * {@code face} floor/ceiling. Everything else is left alone - a flipped door stays a door on its
     * hinges, which is the least surprising wrong answer for a block with no upside-down form.
     */
    public static final class VerticalFlip {

        private VerticalFlip() {
        }

        public static String flip(String state) {
            int open = state.indexOf('[');
            if (open < 0 || !state.endsWith("]")) {
                return state;
            }
            String[] properties = state.substring(open + 1, state.length() - 1).split(",");
            StringBuilder out = new StringBuilder(state.length()).append(state, 0, open + 1);
            for (int i = 0; i < properties.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(flipProperty(properties[i]));
            }
            return out.append(']').toString();
        }

        private static String flipProperty(String property) {
            int eq = property.indexOf('=');
            if (eq < 0) {
                return property;
            }
            String key = property.substring(0, eq);
            String value = property.substring(eq + 1);
            String flipped = switch (key) {
                case "half", "type" -> swap(value, "top", "bottom");
                case "facing", "vertical_direction" -> swap(value, "up", "down");
                case "face" -> swap(value, "floor", "ceiling");
                default -> value;
            };
            return key + "=" + flipped;
        }

        private static String swap(String value, String a, String b) {
            if (value.equals(a)) {
                return b;
            }
            if (value.equals(b)) {
                return a;
            }
            return value;
        }
    }
}
