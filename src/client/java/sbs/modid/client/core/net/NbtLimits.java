/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net;

import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import sbs.modid.client.core.net.validation.ValidationException;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Decodes base64 gzip NBT from a remote document (inventory blobs) under fixed limits.
 *
 * <p>The base64 text is checked before decoding, the decompressed size is counted while the
 * gzip stream is read (so the limit holds however small the compressed input is), nesting depth is
 * enforced by {@link NbtAccounter} during the read, and tag count, list length and string length
 * are checked on the result before it is returned. Any limit aborts the decode of that blob with a
 * {@link ValidationException} of kind {@code NBT_LIMIT}; the caller marks that one section
 * unavailable and carries on with the others.
 */
public final class NbtLimits {

    public static final int MAX_COMPRESSED = 1024 * 1024;
    public static final int MAX_DECOMPRESSED = 16 * 1024 * 1024;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_TAGS = 100_000;
    public static final int MAX_LIST = 10_000;
    public static final int MAX_STRING = 32_768;

    private static final String TYPE = "nbt";

    private NbtLimits() {
    }

    /** Decodes {@code base64(gzip(nbt))} into a compound. */
    public static CompoundTag decodeBase64Gzip(String base64) throws ValidationException {
        if (base64 == null || base64.isEmpty()) {
            throw fail("");
        }
        // Base64 expands by 4/3; anything longer cannot decode to an accepted size.
        if (base64.length() > (MAX_COMPRESSED / 3 + 1) * 4 + 4) {
            throw fail("compressed");
        }
        byte[] compressed;
        try {
            compressed = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException malformed) {
            throw new ValidationException(TYPE, ValidationException.Kind.MALFORMED, "base64");
        }
        return decodeGzip(compressed);
    }

    /** Decodes {@code gzip(nbt)} bytes into a compound. */
    public static CompoundTag decodeGzip(byte[] compressed) throws ValidationException {
        if (compressed == null || compressed.length > MAX_COMPRESSED) {
            throw fail("compressed");
        }
        CompoundTag root;
        try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             InputStream limited = new LimitedInputStream(gzip, MAX_DECOMPRESSED);
             DataInputStream data = new DataInputStream(new BufferedInputStream(limited))) {
            root = NbtIo.read(data, new NbtAccounter(MAX_DECOMPRESSED * 4L, MAX_DEPTH));
        } catch (IOException | RuntimeException failure) {
            throw classify(failure);
        }
        checkTree(root);
        return root;
    }

    /** Checks tag count, list and array length and string length of an already decoded tree. */
    public static void checkTree(Tag root) throws ValidationException {
        int[] count = {0};
        walk(root, 0, count);
    }

    private static void walk(Tag tag, int depth, int[] count) throws ValidationException {
        if (++count[0] > MAX_TAGS) {
            throw fail("tags");
        }
        if (depth > MAX_DEPTH) {
            throw fail("depth");
        }
        if (tag instanceof CompoundTag compound) {
            for (Map.Entry<String, Tag> entry : compound.entrySet()) {
                if (entry.getKey().length() > MAX_STRING) {
                    throw fail("string");
                }
                walk(entry.getValue(), depth + 1, count);
            }
        } else if (tag instanceof ListTag list) {
            if (list.size() > MAX_LIST) {
                throw fail("list");
            }
            for (int i = 0; i < list.size(); i++) {
                walk(list.get(i), depth + 1, count);
            }
        } else if (tag instanceof CollectionTag array) {
            if (array.size() > MAX_LIST) {
                throw fail("list");
            }
        } else if (tag instanceof StringTag string && string.value().length() > MAX_STRING) {
            throw fail("string");
        }
    }

    /** NbtIo may wrap the cause in its own exception, so the whole cause chain is inspected. */
    private static ValidationException classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof LimitExceededException) {
                return fail("decompressed");
            }
            if (t instanceof NbtAccounterException) {
                return fail("depth");
            }
        }
        return new ValidationException(TYPE, ValidationException.Kind.MALFORMED, "");
    }

    private static ValidationException fail(String what) {
        return new ValidationException(TYPE, ValidationException.Kind.NBT_LIMIT, what);
    }

    /** Thrown by {@link LimitedInputStream} when more than its limit is read. */
    public static final class LimitExceededException extends IOException {
        public LimitExceededException() {
            super("stream limit exceeded");
        }
    }

    /** Counts bytes as they are read and fails as soon as the count passes the limit. */
    public static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private final long limit;
        private long count;

        public LimitedInputStream(InputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0 && ++count > limit) {
                throw new LimitExceededException();
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                count += n;
                if (count > limit) {
                    throw new LimitExceededException();
                }
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
