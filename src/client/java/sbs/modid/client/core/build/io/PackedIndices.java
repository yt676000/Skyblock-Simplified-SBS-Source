/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.io;

/**
 * Packs small unsigned integers into {@code long}s at a fixed bit width.
 *
 * <p>An entry never straddles two longs: each long holds {@code 64 / bits} entries and the spare high
 * bits stay zero. That wastes a little (at 5 bits, 4 of 64) but makes decoding a shift and a mask with
 * no carry logic - the part of a file format that is cheapest to get subtly wrong.
 */
public final class PackedIndices {

    private PackedIndices() {
    }

    /** Bits needed to store every index below {@code paletteSize}; at least 1. */
    public static int bitsFor(int paletteSize) {
        int max = Math.max(1, paletteSize - 1);
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(max));
    }

    /** How many longs {@code count} entries of {@code bits} bits take. */
    public static int longsFor(int count, int bits) {
        int perLong = 64 / bits;
        return (count + perLong - 1) / perLong;
    }

    public static long[] pack(char[] values, int bits) {
        if (bits < 1 || bits > 16) {
            throw new IllegalArgumentException("bits " + bits);
        }
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        long[] out = new long[longsFor(values.length, bits)];
        for (int i = 0; i < values.length; i++) {
            long value = values[i] & mask;
            if (value != values[i]) {
                throw new IllegalArgumentException("value " + (int) values[i] + " needs more than " + bits + " bits");
            }
            out[i / perLong] |= value << ((i % perLong) * bits);
        }
        return out;
    }

    public static char[] unpack(long[] packed, int count, int bits) {
        if (bits < 1 || bits > 16) {
            throw new IllegalArgumentException("bits " + bits);
        }
        int perLong = 64 / bits;
        if (packed.length < longsFor(count, bits)) {
            throw new IllegalArgumentException("packed data too short for " + count + " entries");
        }
        long mask = (1L << bits) - 1;
        char[] out = new char[count];
        for (int i = 0; i < count; i++) {
            out[i] = (char) ((packed[i / perLong] >>> ((i % perLong) * bits)) & mask);
        }
        return out;
    }
}
