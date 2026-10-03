/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config.share;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.share.ShareCodec.ShareException;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * The front door for clipboard JSON that is <b>not</b> a shared config: pearl setups and mining
 * routes.
 *
 * <h2>Why this exists</h2>
 *
 * {@code docs/CONFIG-SHARING-DESIGN.md} §10 flagged both of those. They accept a paste from anywhere
 * and hand it straight to {@code SBSFiles.GSON} - the parser meant for our own files on our own disk
 * - with no size cap, no depth cap and no element cap. Their blast radius is genuinely small (§5
 * confirms no polymorphic path exists to abuse, and the payloads are coordinates rather than
 * anything sent or executed), which is why it was a follow-up and not an emergency. But "small blast
 * radius" is not "bounded cost", and a hostile blob could still make the client chew memory or throw
 * somewhere unhelpful.
 *
 * <h2>What it does</h2>
 *
 * <ol>
 *   <li><b>Bounds the input before parsing it.</b> Text length, then base64 with a bounded output,
 *     then a streaming walk that counts elements and depth as it goes. A cap enforced after the
 *     work it is capping is decoration.</li>
 *   <li><b>Filters keys against a whitelist</b> while walking, so a key the format did not declare
 *     is dropped before anything binds to it. The tree handed back contains only keys somebody
 *     wrote down, which means a field added to {@code PearlArea} later is not silently
 *     settable from a paste - it has to be added here too, deliberately.</li>
 *   <li><b>Drops rather than rejects</b> an unknown key. These formats are shared between players on
 *     different mod versions, and refusing a whole route because it carries one key from a newer
 *     build would be worse than ignoring it. That is the opposite of the config importer's stance,
 *     and deliberately so: a config decides what the mod does, a route is a list of places.</li>
 * </ol>
 *
 * <p>What it deliberately does <b>not</b> do is replace the formats' own parsers. Those handle
 * several shapes each, for good reasons written down in their own classes; this hands them a tree
 * that is already bounded and already stripped of anything they never asked for.
 */
public final class ClipboardJson {

    /** Text longer than this is refused before base64 or JSON is attempted. */
    public static final int MAX_ENCODED_BYTES = 256 * 1024;

    /**
     * Decoded JSON longer than this is refused.
     *
     * <p><b>Unreachable while {@link #MAX_ENCODED_BYTES} stands where it does</b>, and kept anyway.
     * Base64 shrinks by a third on the way in, so 256 KB of text can only ever become 192 KB of
     * JSON - the text cap refuses everything this one would have. The check costs nothing, and it is
     * what starts protecting the decode the moment somebody raises the text cap; {@code
     * ClipboardJsonTest.theTextCapDominatesTheDecodedCap} fails on the day those two cross, which is
     * the day this needs a test of its own.
     */
    public static final int MAX_DECODED_BYTES = 2 * 1024 * 1024;

    /** Most values (objects, arrays and scalars) a payload may contain. */
    public static final int MAX_ELEMENTS = 20_000;

    /** How deep a payload may nest. A route is 3 deep; a pearl setup 4. */
    public static final int MAX_DEPTH = 16;

    /** Longest string a value may carry. Names and notes, not documents. */
    public static final int MAX_STRING = 256;

    private ClipboardJson() {
    }

    /**
     * The keys a format allows, and whether it nests.
     *
     * @param keys       every object key that may survive, lower-cased
     * @param maxDepth   how deep this format legitimately goes
     * @param maxElements how many values this format legitimately holds
     */
    public record Allowlist(Set<String> keys, int maxDepth, int maxElements) {

        public Allowlist(Set<String> keys) {
            this(keys, MAX_DEPTH, MAX_ELEMENTS);
        }

        public Allowlist {
            keys = keys.stream().map(k -> k.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        boolean allows(String key) {
            return keys.contains(key.toLowerCase(Locale.ROOT));
        }
    }

    /** What a parse dropped, so the caller can say so rather than silently differing. */
    public record Result(JsonElement json, int droppedKeys, int elements) {
    }

    /**
     * Parses {@code text} - plain JSON or base64, with or without a {@code prefix} - into a bounded,
     * key-filtered tree.
     *
     * @param prefix an optional format marker to strip first ({@code "SBSROUTE:"}), or {@code null}
     * @throws ShareException with a message naming which limit was hit
     */
    public static Result parse(String text, String prefix, Allowlist allowlist)
            throws ShareException {
        if (text == null || text.isBlank()) {
            throw new ShareException(ShareCodec.Reason.EMPTY, "The clipboard is empty.");
        }
        String body = text.trim();
        if (body.length() > MAX_ENCODED_BYTES) {
            throw new ShareException(ShareCodec.Reason.ENCODED_TOO_LARGE,
                    "That paste is too large to be one of ours.");
        }
        if (prefix != null && body.regionMatches(true, 0, prefix, 0, prefix.length())) {
            body = body.substring(prefix.length()).trim();
        }
        if (!body.startsWith("[") && !body.startsWith("{")) {
            body = decodeBase64(body);
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_DECODED_BYTES) {
            throw new ShareException(ShareCodec.Reason.DECODED_TOO_LARGE,
                    "That paste unpacks to far more than a setup ever does.");
        }
        return walk(body, allowlist);
    }

    /** Base64 with a bounded result, so a huge blob is refused rather than held. */
    private static String decodeBase64(String body) throws ShareException {
        String compact = body.replaceAll("\\s", "");
        // 4 base64 characters carry 3 bytes; refuse before allocating rather than after.
        if ((long) compact.length() / 4 * 3 > MAX_DECODED_BYTES) {
            throw new ShareException(ShareCodec.Reason.DECODED_TOO_LARGE,
                    "That paste unpacks to far more than a setup ever does.");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(compact);
            return new String(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            throw new ShareException(ShareCodec.Reason.NOT_OURS, "That does not look like one of ours - it "
                    + "may have been damaged when it was copied.");
        }
    }

    /**
     * One streaming pass: counts elements and depth as it goes, rebuilds only allowed keys.
     *
     * <p>The counters are checked <i>during</i> the walk, so a payload that is too big stops being
     * read at the limit rather than being fully materialised and then measured.
     */
    private static Result walk(String json, Allowlist allowlist) throws ShareException {
        int[] elements = {0};
        int[] dropped = {0};
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement root = read(reader, allowlist, 0, elements, dropped);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new ShareException(ShareCodec.Reason.MALFORMED, "That paste has more than one thing in it.");
            }
            return new Result(root, dropped[0], elements[0]);
        } catch (IOException | IllegalStateException | NumberFormatException broken) {
            throw new ShareException(ShareCodec.Reason.MALFORMED,
                    "That paste could not be read - it may be incomplete.");
        } catch (StackOverflowError deep) {
            // Belt and braces: the depth cap below should make this unreachable.
            throw new ShareException(ShareCodec.Reason.TOO_DEEP, "That paste nests far deeper than a setup does.");
        }
    }

    private static JsonElement read(JsonReader reader, Allowlist allowlist, int depth,
                                    int[] elements, int[] dropped)
            throws IOException, ShareException {
        if (depth > allowlist.maxDepth()) {
            throw new ShareException(ShareCodec.Reason.TOO_DEEP, "That paste nests far deeper than a setup does.");
        }
        if (++elements[0] > allowlist.maxElements()) {
            throw new ShareException(ShareCodec.Reason.TOO_MANY_ELEMENTS,
                    "That paste holds far more than a setup ever does.");
        }
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!allowlist.allows(key)) {
                        // Skipped, not descended into: an un-allowed key's value is never built.
                        reader.skipValue();
                        dropped[0]++;
                        continue;
                    }
                    object.add(key, read(reader, allowlist, depth + 1, elements, dropped));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(read(reader, allowlist, depth + 1, elements, dropped));
                }
                reader.endArray();
                return array;
            }
            case STRING -> {
                String value = reader.nextString();
                return new JsonPrimitive(value.length() > MAX_STRING
                        ? value.substring(0, MAX_STRING) : value);
            }
            case NUMBER -> {
                double value = reader.nextDouble();
                // A NaN or an infinity reaching a coordinate is a marker nobody can see and a
                // render that quietly does nothing; refuse it here rather than debug it there.
                if (!Double.isFinite(value)) {
                    throw new ShareException(ShareCodec.Reason.MALFORMED,
                            "That paste contains a number that is not one.");
                }
                return new JsonPrimitive(value);
            }
            case BOOLEAN -> {
                return new JsonPrimitive(reader.nextBoolean());
            }
            case NULL -> {
                reader.nextNull();
                return JsonNull.INSTANCE;
            }
            default -> throw new ShareException(ShareCodec.Reason.MALFORMED, "That paste could not be read.");
        }
    }

    /** Logs what a parse dropped, once per import, so a mismatch is diagnosable. */
    public static void logDropped(String area, Result result) {
        if (result.droppedKeys() > 0) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][{}] ignored {} key(s) this build does not know from a pasted setup",
                    area, result.droppedKeys());
        }
    }
}
