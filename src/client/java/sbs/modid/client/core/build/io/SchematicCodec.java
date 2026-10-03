/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The schematic file format - ours, versioned, and the same bytes whether they sit in a
 * {@code .sbsbp} file or inside an {@code SBSBP:} share code.
 *
 * <pre>
 * gzip(
 *   "SBSBP"            5 bytes, magic
 *   version            u8 ({@link #VERSION})
 *   header             int length + UTF-8 JSON   (name, created, tags, folder, favourite,
 *                                                 source, origin, extras, size, blocks)
 *   width height length  3 ints
 *   palette            int count + count x (int length + UTF-8 state string), [0] = air
 *   cells              u8 bits + int longCount + longCount longs ({@link PackedIndices})
 *   block entities     int count + count x (int cellIndex + int length + UTF-8 SNBT)
 * )
 * </pre>
 *
 * <p><b>The header is first so a folder lists cheaply</b>: {@link #readSummary} inflates a few
 * hundred bytes per file and stops, which is what lets the Quick Paste grid show sizes and block
 * counts for a large library without loading any cells.
 *
 * <p><b>Every length is checked before it is trusted.</b> Files come from disk and share codes from
 * the clipboard - anything a player pastes is untrusted input. Inflation is bounded while it runs
 * ({@link #MAX_INFLATED_BYTES}), each string length and count is capped before the buffer for it is
 * allocated, and the decoded cells are validated against the palette ({@link Schematic#fromRaw}).
 * Nothing here can name a class; the header is parsed by Gson into a tree, never bound.
 */
public final class SchematicCodec {

    /** Written into every file; a reader refuses a higher one rather than guess at new fields. */
    public static final int VERSION = 1;

    private static final byte[] MAGIC = "SBSBP".getBytes(StandardCharsets.US_ASCII);

    /** Inflated-size ceiling: a full 256-cube at 16 bits per cell is 32 MiB. */
    public static final long MAX_INFLATED_BYTES = 64L * 1024 * 1024;

    static final int MAX_HEADER_BYTES = 64 * 1024;
    static final int MAX_STATE_BYTES = 1024;
    static final int MAX_BLOCK_ENTITY_BYTES = 256 * 1024;
    static final int MAX_BLOCK_ENTITIES = 65_536;

    private SchematicCodec() {
    }

    /** Why a file or code was refused; the message is written for the player. */
    public static final class FormatException extends IOException {
        public FormatException(String message) {
            super(message);
        }
    }

    /**
     * What a library listing needs without the cells.
     *
     * @param blocks non-air block count, as written by the encoder
     */
    public record Summary(SchematicHeader header, int width, int height, int length, int blocks,
                          int version) {

        public String sizeLabel() {
            return width + "×" + height + "×" + length;
        }
    }

    // ---------------------------------------------------------------- encode

    public static byte[] encode(Schematic schematic) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.write(MAGIC);
            out.writeByte(VERSION);
            writeString(out, headerJson(schematic).toString());
            out.writeInt(schematic.width());
            out.writeInt(schematic.height());
            out.writeInt(schematic.length());
            List<String> palette = schematic.palette();
            out.writeInt(palette.size());
            for (String state : palette) {
                writeString(out, state);
            }
            int bits = PackedIndices.bitsFor(palette.size());
            long[] packed = PackedIndices.pack(schematic.cellsCopy(), bits);
            out.writeByte(bits);
            out.writeInt(packed.length);
            for (long value : packed) {
                out.writeLong(value);
            }
            Map<Integer, String> entities = schematic.blockEntities();
            out.writeInt(entities.size());
            for (Map.Entry<Integer, String> entry : entities.entrySet()) {
                out.writeInt(entry.getKey());
                writeString(out, entry.getValue());
            }
        } catch (IOException impossible) {
            // A ByteArrayOutputStream does not throw; reaching this is a bug, not a disk problem.
            throw new IllegalStateException("in-memory encode failed", impossible);
        }
        return bytes.toByteArray();
    }

    private static JsonObject headerJson(Schematic schematic) {
        SchematicHeader header = schematic.header();
        JsonObject json = new JsonObject();
        json.addProperty("format", VERSION);
        json.addProperty("name", header.name());
        json.addProperty("created", header.createdAt());
        JsonArray tags = new JsonArray();
        header.tags().forEach(tags::add);
        json.add("tags", tags);
        json.addProperty("folder", header.folder());
        json.addProperty("favourite", header.favourite());
        json.addProperty("source", header.source().name().toLowerCase(java.util.Locale.ROOT));
        int[] origin = header.origin();
        if (origin != null) {
            JsonArray array = new JsonArray();
            for (int value : origin) {
                array.add(value);
            }
            json.add("origin", array);
        }
        JsonObject extras = new JsonObject();
        header.extras().forEach(extras::addProperty);
        json.add("extras", extras);
        JsonArray size = new JsonArray();
        size.add(schematic.width());
        size.add(schematic.height());
        size.add(schematic.length());
        json.add("size", size);
        json.addProperty("blocks", schematic.nonAirCount());
        return json;
    }

    // ---------------------------------------------------------------- decode

    public static Schematic decode(byte[] data) throws FormatException {
        return decode(new ByteArrayInputStream(data));
    }

    /** Reads a whole schematic. The stream is consumed but not closed. */
    public static Schematic decode(InputStream raw) throws FormatException {
        try {
            DataInputStream in = open(raw);
            Summary summary = readHead(in);
            int width = in.readInt();
            int height = in.readInt();
            int length = in.readInt();
            long volume = (long) width * height * length;
            if (width <= 0 || height <= 0 || length <= 0 || volume > Schematic.MAX_VOLUME) {
                throw new FormatException("the build's size is invalid (" + width + "x" + height + "x" + length + ")");
            }
            int paletteSize = in.readInt();
            if (paletteSize < 1 || paletteSize > Schematic.MAX_PALETTE) {
                throw new FormatException("the block list is damaged (" + paletteSize + " entries)");
            }
            List<String> palette = new ArrayList<>(paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                palette.add(readString(in, MAX_STATE_BYTES));
            }
            int bits = in.readUnsignedByte();
            if (bits != PackedIndices.bitsFor(paletteSize)) {
                throw new FormatException("the block data is damaged (bit width " + bits + ")");
            }
            int longCount = in.readInt();
            if (longCount != PackedIndices.longsFor((int) volume, bits)) {
                throw new FormatException("the block data is damaged (length " + longCount + ")");
            }
            long[] packed = new long[longCount];
            for (int i = 0; i < longCount; i++) {
                packed[i] = in.readLong();
            }
            char[] cells = PackedIndices.unpack(packed, (int) volume, bits);
            int entityCount = in.readInt();
            if (entityCount < 0 || entityCount > MAX_BLOCK_ENTITIES) {
                throw new FormatException("the block entity list is damaged (" + entityCount + ")");
            }
            Map<Integer, String> entities = new HashMap<>();
            for (int i = 0; i < entityCount; i++) {
                int cell = in.readInt();
                String snbt = readString(in, MAX_BLOCK_ENTITY_BYTES);
                if (cell >= 0 && cell < volume) {
                    entities.put(cell, snbt);
                }
            }
            return Schematic.fromRaw(width, height, length, palette, cells, entities, summary.header());
        } catch (FormatException refused) {
            throw refused;
        } catch (EOFException truncated) {
            throw new FormatException("the file ends early - it is incomplete or damaged");
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new FormatException("the build data is invalid: " + invalid.getMessage());
        } catch (IOException io) {
            throw new FormatException("it could not be read: " + io.getMessage());
        }
    }

    /** Reads only the header - a few hundred bytes - for listings. */
    public static Summary readSummary(InputStream raw) throws FormatException {
        try {
            return readHead(open(raw));
        } catch (FormatException refused) {
            throw refused;
        } catch (EOFException truncated) {
            throw new FormatException("the file ends early - it is incomplete or damaged");
        } catch (IOException io) {
            throw new FormatException("it could not be read: " + io.getMessage());
        }
    }

    private static DataInputStream open(InputStream raw) throws IOException {
        InputStream gzip;
        try {
            gzip = new GZIPInputStream(raw);
        } catch (java.util.zip.ZipException notGzip) {
            throw new FormatException("that is not an SBS build file");
        }
        return new DataInputStream(new BoundedInputStream(gzip, MAX_INFLATED_BYTES));
    }

    private static Summary readHead(DataInputStream in) throws IOException {
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new FormatException("that is not an SBS build file");
        }
        int version = in.readUnsignedByte();
        if (version > VERSION) {
            throw new FormatException("it was saved by a newer SBS (format " + version
                    + ", this one reads up to " + VERSION + ") - update the mod to open it");
        }
        if (version < 1) {
            throw new FormatException("that is not an SBS build file (format " + version + ")");
        }
        String json = readString(in, MAX_HEADER_BYTES);
        JsonObject header;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new FormatException("the file's header is damaged");
            }
            header = parsed.getAsJsonObject();
        } catch (RuntimeException badJson) {
            throw new FormatException("the file's header is damaged");
        }
        try {
            return parseHeader(header, version);
        } catch (RuntimeException wrongTypes) {
            // A number where a string belongs, or the reverse: Gson throws, and the player gets a
            // sentence rather than a stack trace.
            throw new FormatException("the file's header is damaged");
        }
    }

    private static Summary parseHeader(JsonObject json, int version) {
        List<String> tags = new ArrayList<>();
        if (json.has("tags") && json.get("tags").isJsonArray()) {
            for (JsonElement tag : json.getAsJsonArray("tags")) {
                if (tag.isJsonPrimitive()) {
                    tags.add(tag.getAsString());
                }
            }
        }
        int[] origin = null;
        if (json.has("origin") && json.get("origin").isJsonArray()) {
            JsonArray array = json.getAsJsonArray("origin");
            if (array.size() == 3) {
                origin = new int[] {array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
            }
        }
        Map<String, Integer> extras = new LinkedHashMap<>();
        if (json.has("extras") && json.get("extras").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("extras").entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                    extras.put(entry.getKey(), entry.getValue().getAsInt());
                }
            }
        }
        SchematicHeader header = new SchematicHeader(
                string(json, "name"),
                json.has("created") ? json.get("created").getAsLong() : 0L,
                tags,
                string(json, "folder"),
                json.has("favourite") && json.get("favourite").getAsBoolean(),
                SchematicHeader.Source.parse(string(json, "source")),
                origin,
                extras);
        int width = 0;
        int height = 0;
        int length = 0;
        if (json.has("size") && json.get("size").isJsonArray() && json.getAsJsonArray("size").size() == 3) {
            JsonArray size = json.getAsJsonArray("size");
            width = size.get(0).getAsInt();
            height = size.get(1).getAsInt();
            length = size.get(2).getAsInt();
        }
        int blocks = json.has("blocks") ? json.get("blocks").getAsInt() : 0;
        return new Summary(header, width, height, length, blocks, version);
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    // ---------------------------------------------------------------- strings

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int max) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > max) {
            throw new FormatException("the file is damaged (a text field of " + length + " bytes)");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Fails the read once more than {@code limit} bytes have come out of the inflater. */
    private static final class BoundedInputStream extends FilterInputStream {

        private final long limit;
        private long read;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int len) throws IOException {
            int n = super.read(buffer, offset, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(long n) throws FormatException {
            read += n;
            if (read > limit) {
                throw new FormatException("it unpacks to more than " + (limit / (1024 * 1024))
                        + " MB - too large to be a build");
            }
        }
    }
}
