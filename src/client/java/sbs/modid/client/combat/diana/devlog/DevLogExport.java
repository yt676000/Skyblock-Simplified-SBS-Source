/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.devlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Turns a capture's {@code .jsonl} into one pretty-printed JSON array, in file order - which is
 * {@code seq} order, because the writer numbers lines as it writes them.
 *
 * <p><b>Streamed.</b> One line is read, parsed and written before the next is read, so an hour-long
 * capture never has to fit in memory.
 *
 * <p><b>Tolerant.</b> The file being converted is often the one a crash cut short, and then its last
 * line is half an object. A line that does not parse as one strict JSON object is skipped and counted
 * - never a reason to lose the rest - and when anything was skipped the array ends with one
 * {@code export_note} element saying how many lines and whether the last one was among them, so the
 * reader of the array knows it is not the whole capture.
 */
public final class DevLogExport {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final TypeAdapter<JsonElement> ELEMENT = GSON.getAdapter(JsonElement.class);

    /** What one conversion did. */
    public record Result(long events, long skipped, boolean lastLineTruncated) {
    }

    private DevLogExport() {
    }

    /**
     * Converts {@code jsonl} into {@code json}, replacing it if it exists.
     *
     * <p>Written to {@code <json>.part} and moved into place at the end, so an export cut off half-way
     * - the game closing, the JVM halting - never leaves a {@code .json} that does not parse. A
     * leftover {@code .part} means the capture still needs converting.
     */
    public static Result export(Path jsonl, Path json) throws IOException {
        Path part = json.resolveSibling(json.getFileName() + ".part");
        Result result;
        try (BufferedReader in = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8);
             Writer out = Files.newBufferedWriter(part, StandardCharsets.UTF_8)) {
            result = export(in, out, jsonl.getFileName().toString());
        }
        try {
            Files.move(part, json, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, json, StandardCopyOption.REPLACE_EXISTING);
        }
        return result;
    }

    /** The conversion itself, over any reader and writer - what the tests drive. */
    public static Result export(Reader jsonl, Writer json, String sourceName) throws IOException {
        BufferedReader in = jsonl instanceof BufferedReader buffered ? buffered : new BufferedReader(jsonl);
        JsonWriter out = new JsonWriter(json);
        out.setIndent("  ");
        out.setHtmlSafe(false);
        out.beginArray();

        long events = 0;
        long skipped = 0;
        long lineNo = 0;
        long lastContentLine = -1;
        long lastBadLine = -1;
        String line;
        while ((line = in.readLine()) != null) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            lastContentLine = lineNo;
            JsonObject event = parseLine(line);
            if (event == null) {
                skipped++;
                lastBadLine = lineNo;
                continue;
            }
            ELEMENT.write(out, event);
            events++;
        }

        boolean lastTruncated = lastBadLine >= 0 && lastBadLine == lastContentLine;
        if (skipped > 0) {
            JsonObject data = new JsonObject();
            data.addProperty("skippedLines", skipped);
            data.addProperty("lastLineTruncated", lastTruncated);
            data.addProperty("source", sourceName);
            JsonObject note = new JsonObject();
            note.addProperty("type", "export_note");
            note.add("data", data);
            ELEMENT.write(out, note);
        }
        out.endArray();
        out.flush();
        return new Result(events, skipped, lastTruncated);
    }

    /** The line as a JSON object, or {@code null} when it is not exactly one strict JSON object. */
    static JsonObject parseLine(String line) {
        try (JsonReader reader = new JsonReader(new StringReader(line))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                return null;
            }
            JsonElement element = ELEMENT.read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                return null;
            }
            return element.getAsJsonObject();
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    /** Where the array for a capture goes: the same name with {@code .json} for {@code .jsonl}. */
    public static Path arrayPathFor(Path jsonl) {
        String name = jsonl.getFileName().toString();
        String base = name.endsWith(".jsonl") ? name.substring(0, name.length() - ".jsonl".length()) : name;
        return jsonl.resolveSibling(base + ".json");
    }
}
