/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.share.ClipboardJson;
import sbs.modid.client.core.config.share.ShareCodec;
import sbs.modid.client.core.config.SBSConfig.MiningRoute;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Turns whatever a player pasted into a {@link MiningRoute}.
 *
 * <p><b>Why this is not just "decode our own export".</b> Mining routes are shared player to player,
 * pasted out of chat, Discord messages and text files, and hardly any of them started life in this
 * mod. The route strings in circulation come in a handful of shapes, and an importer that only
 * accepted our own {@code SBSROUTE:} blob rejected essentially every route anyone actually had - which
 * is what "import doesn't work" meant. So the parser sniffs the payload instead of demanding one
 * format. Only the <i>data shapes</i> are matched here; the parsing is our own.
 *
 * <p><b>Accepted, in the order they are tried:</b>
 * <ol>
 *   <li>A <b>JSON array of waypoint objects</b> - the plain-text shape most shared mining routes use,
 *       {@code [{"x":12,"y":34,"z":56,"r":0,"g":1,"b":0,"options":{"name":"..."}}, ...]}. Colour and
 *       name are picked up when present.</li>
 *   <li>A <b>JSON object wrapping</b> such a list under any of {@link #POINT_KEYS} - which also covers
 *       our own {@link MiningRoute}, whose {@code points} are {@code [x,y,z]} arrays rather than
 *       objects. Both element shapes are read by {@link #readPoint}.</li>
 *   <li>A <b>base64 blob</b> (with or without our {@code SBSROUTE:} prefix) wrapping either of the
 *       above, <b>optionally gzip-compressed</b> - detected by the gzip magic bytes rather than by
 *       trusting the sender, since both compressed and uncompressed blobs are in circulation.</li>
 *   <li><b>Loose coordinate text</b> as a last resort: {@code "x: 12, y: 34, z: 56"} lines, or bare
 *       {@code "12 34 56"} triples. This is what a route looks like when someone copies it out of a
 *       chat log, and it costs nothing to support.</li>
 * </ol>
 *
 * <p>Every stage is tolerant on purpose: pastes arrive wrapped in quotes or Discord code fences, with
 * newlines injected by line wrapping, and in any of the three base64 alphabets. A paste that survives
 * a round trip through a chat client should still import, so all of that is stripped before parsing
 * rather than being treated as corruption.
 *
 * <p>Returns {@code null} when nothing usable was found - the caller reports that to the player. A
 * rejected paste is logged under {@code [SBS][MiningRoutes]} with its leading characters so a shape we
 * do not handle yet can be identified from a log instead of guessed at.
 */
public final class MiningRouteImport {

    /** Our own export prefix. Accepted with or without it, in any case. */
    private static final String PREFIX = "SBSROUTE:";

    /** Keys a wrapping object may store its waypoint list under. */
    private static final String[] POINT_KEYS = {"points", "waypoints", "locations", "route", "coords"};

    /** Keys a wrapping object may store its display name under. */
    private static final String[] NAME_KEYS = {"name", "label", "title"};

    /** Keys a colour may arrive under, as an {@code RRGGBB} / {@code #RRGGBB} string. */
    private static final String[] COLOR_KEYS = {"colorHex", "color", "colour", "hex"};

    /** A route with more points than this is treated as junk rather than parsed into oblivion. */
    private static final int MAX_POINTS = 10_000;

    /** How much of a rejected paste to log, so an unknown shape can be identified without leaking it all. */
    private static final int LOG_SAMPLE = 120;

    /** "x: 12, y: 34, z: 56" in any separator/casing, the labelled fallback shape. */
    private static final Pattern LABELLED = Pattern.compile(
            "(?i)x\\s*[:=]\\s*(-?\\d+(?:\\.\\d+)?)\\s*[,;\\s]+"
                    + "y\\s*[:=]\\s*(-?\\d+(?:\\.\\d+)?)\\s*[,;\\s]+"
                    + "z\\s*[:=]\\s*(-?\\d+(?:\\.\\d+)?)");

    /** Bare "12 34 56" / "12,34,56" triples - only used when no labelled pair was found. */
    private static final Pattern BARE = Pattern.compile(
            "(-?\\d+(?:\\.\\d+)?)[,;\\s]+(-?\\d+(?:\\.\\d+)?)[,;\\s]+(-?\\d+(?:\\.\\d+)?)");

    private MiningRouteImport() {
    }

    /**
     * Parses a pasted route. Returns {@code null} when the text held no waypoints; the returned route
     * always has a non-blank name, a colour and at least one point.
     */
    public static MiningRoute parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = unwrap(raw);
        if (text.isEmpty()) {
            return null;
        }
        MiningRoute route = fromJson(text);
        if (route == null) {
            route = fromLooseText(text);
        }
        if (route == null || route.points == null || route.points.isEmpty()) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][MiningRoutes] Import rejected; payload began: {}",
                    text.substring(0, Math.min(LOG_SAMPLE, text.length())));
            return null;
        }
        if (route.name == null || route.name.isBlank()) {
            route.name = "Imported";
        }
        if (route.colorHex == null || route.colorHex.isBlank()) {
            route.colorHex = "3FB4FF";
        }
        return route;
    }

    // ------------------------------------------------------------------ unwrapping

    /**
     * Strips the packaging a pasted route arrives in - code fences, quotes, our prefix - and decodes it
     * when what is left is a base64 (optionally gzipped) blob rather than JSON.
     */
    private static String unwrap(String raw) {
        String text = raw.trim();
        text = stripWrapper(text, "```");
        text = stripWrapper(text, "`");
        text = stripWrapper(text, "\"");
        text = stripWrapper(text, "'");
        text = text.trim();
        if (text.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            text = text.substring(PREFIX.length()).trim();
        }
        if (looksLikeJson(text)) {
            return text;
        }
        // A decode is only believed when it produced JSON. The lenient decoders further down silently
        // skip characters outside their alphabet, so they "succeed" on ordinary text too - handed
        // "x: 100, y: 64, z: -200" they return bytes, and accepting those would destroy the very text
        // the loose-coordinate fallback exists to read. Every route blob in circulation wraps JSON, so
        // "did this decode to JSON" is the honest test of whether it was a blob at all.
        String decoded = looksLikeBase64(text) ? decodeBlob(text) : null;
        return decoded != null && looksLikeJson(decoded) ? decoded.trim() : text;
    }

    /**
     * Whether the payload could be base64 at all: nothing but alphabet characters (either alphabet,
     * padded or not) once whitespace is gone, and long enough to be worth decoding.
     */
    private static boolean looksLikeBase64(String text) {
        String compact = text.replaceAll("\\s+", "");
        if (compact.length() < 8) {
            return false;
        }
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            boolean valid = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '-' || c == '_';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    /** Removes a matching leading+trailing wrapper (a quote pair, a Discord code fence). */
    private static String stripWrapper(String text, String wrapper) {
        if (text.length() > wrapper.length() * 2
                && text.startsWith(wrapper) && text.endsWith(wrapper)) {
            return text.substring(wrapper.length(), text.length() - wrapper.length()).trim();
        }
        return text;
    }

    private static boolean looksLikeJson(String text) {
        return text.startsWith("[") || text.startsWith("{");
    }

    /**
     * Decodes a base64 payload, transparently gunzipping it when the decoded bytes carry the gzip magic
     * number. Whitespace is removed first: a blob pasted out of a chat client is routinely line-wrapped,
     * and the strict decoder rejects that outright - which on its own made long routes un-importable.
     */
    private static String decodeBlob(String text) {
        String compact = text.replaceAll("\\s+", "");
        if (compact.isEmpty()) {
            return null;
        }
        byte[] bytes = decodeBase64(compact);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (bytes.length > 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
            bytes = gunzip(bytes);
            if (bytes == null) {
                return null;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Tries the three base64 alphabets in turn - senders use all of them. */
    private static byte[] decodeBase64(String compact) {
        try {
            return Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException ignored) {
            // fall through to the URL-safe alphabet
        }
        try {
            return Base64.getUrlDecoder().decode(compact);
        } catch (IllegalArgumentException ignored) {
            // fall through to the lenient MIME decoder
        }
        try {
            return Base64.getMimeDecoder().decode(compact);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static byte[] gunzip(byte[] bytes) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length * 4);
            in.transferTo(out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ JSON

    /** Parses the JSON shapes; {@code null} when the text is not JSON or holds no waypoints. */
    /**
     * The only keys a pasted route may carry. Everything else is dropped before this class ever sees
     * it - see {@link ClipboardJson}, and {@code docs/CONFIG-SHARING-DESIGN.md} §10 for why a
     * paste is treated as hostile input rather than as our own file.
     *
     * <p>Adding a field to a route means adding it here too. That is the point: a key nobody wrote
     * down cannot arrive, so a future field cannot become settable from a stranger's paste by
     * accident.
     */
    private static final ClipboardJson.Allowlist ALLOWED = new ClipboardJson.Allowlist(
            java.util.Set.of(
                    // The point lists, under every name we accept one.
                    "points", "waypoints", "locations", "route", "coords",
                    // A point.
                    "x", "y", "z",
                    // A route's own metadata.
                    "name", "label", "title", "colorhex", "color", "colour", "hex"),
            8, 60_000);

    private static MiningRoute fromJson(String text) {
        JsonElement root;
        try {
            // Bounded and key-filtered first; the shape handling below is unchanged, it simply no
            // longer runs on whatever was on the clipboard.
            root = ClipboardJson.parse(text, PREFIX, ALLOWED).json();
        } catch (ShareCodec.ShareException refused) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][MiningRoutes] paste refused: {}",
                    refused.getMessage());
            return null;
        } catch (Exception e) {
            return null;
        }
        if (root == null || root.isJsonNull()) {
            return null;
        }
        if (root.isJsonArray()) {
            return fromArray(root.getAsJsonArray(), null);
        }
        if (!root.isJsonObject()) {
            return null;
        }
        JsonObject object = root.getAsJsonObject();
        for (String key : POINT_KEYS) {
            JsonElement list = object.get(key);
            if (list != null && list.isJsonArray()) {
                return fromArray(list.getAsJsonArray(), object);
            }
        }
        // A single waypoint object on its own is still a (one-point) route.
        int[] single = readPoint(object);
        if (single != null) {
            MiningRoute route = new MiningRoute();
            route.points = new ArrayList<>(List.of(single));
            applyMeta(route, object, object);
            return route;
        }
        return null;
    }

    /**
     * Builds a route from a waypoint array. {@code wrapper} is the enclosing object when there was one,
     * and supplies the route's name/colour; otherwise those are taken from the first waypoint, which is
     * where the array-only shapes carry them.
     */
    private static MiningRoute fromArray(JsonArray array, JsonObject wrapper) {
        List<int[]> points = new ArrayList<>();
        JsonObject first = null;
        for (JsonElement element : array) {
            if (points.size() >= MAX_POINTS) {
                break;
            }
            int[] point = readPoint(element);
            if (point == null) {
                continue;
            }
            if (first == null && element.isJsonObject()) {
                first = element.getAsJsonObject();
            }
            points.add(point);
        }
        if (points.isEmpty()) {
            return null;
        }
        MiningRoute route = new MiningRoute();
        route.points = points;
        applyMeta(route, wrapper, first);
        return route;
    }

    /**
     * Reads one waypoint. Handles both element shapes in circulation: an object with {@code x}/{@code y}
     * /{@code z} members, and a bare {@code [x,y,z]} array (which is how our own routes store them).
     */
    private static int[] readPoint(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.size() < 3) {
                return null;
            }
            Integer x = readInt(array.get(0));
            Integer y = readInt(array.get(1));
            Integer z = readInt(array.get(2));
            return x == null || y == null || z == null ? null : new int[]{x, y, z};
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        Integer x = readInt(object.get("x"));
        Integer y = readInt(object.get("y"));
        Integer z = readInt(object.get("z"));
        return x == null || y == null || z == null ? null : new int[]{x, y, z};
    }

    /** A coordinate as a block position - shared routes store them as doubles as often as ints. */
    private static Integer readInt(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return (int) Math.floor(element.getAsDouble());
        } catch (Exception e) {
            return null;
        }
    }

    /** Fills the route's name and colour from whichever of the two objects carries them. */
    private static void applyMeta(MiningRoute route, JsonObject wrapper, JsonObject firstPoint) {
        String name = readName(wrapper);
        if (name == null) {
            name = readName(firstPoint);
        }
        if (name == null && firstPoint != null && firstPoint.has("options")
                && firstPoint.get("options").isJsonObject()) {
            name = readName(firstPoint.getAsJsonObject("options"));
        }
        route.name = name;

        String color = readColor(wrapper);
        if (color == null) {
            color = readColor(firstPoint);
        }
        route.colorHex = color;
    }

    private static String readName(JsonObject object) {
        if (object == null) {
            return null;
        }
        for (String key : NAME_KEYS) {
            JsonElement element = object.get(key);
            if (element != null && element.isJsonPrimitive()) {
                String value = element.getAsString().trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * A colour as {@code RRGGBB}. Accepts a hex string under any of {@link #COLOR_KEYS}, or separate
     * {@code r}/{@code g}/{@code b} members - which appear both as 0-1 floats and as 0-255 ints, told
     * apart by whether every channel is within 0-1.
     */
    private static String readColor(JsonObject object) {
        if (object == null) {
            return null;
        }
        for (String key : COLOR_KEYS) {
            JsonElement element = object.get(key);
            if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString().trim().replace("#", "");
                if (value.matches("(?i)[0-9a-f]{6}")) {
                    return value.toUpperCase(Locale.ROOT);
                }
            }
        }
        Double r = readDouble(object.get("r"));
        Double g = readDouble(object.get("g"));
        Double b = readDouble(object.get("b"));
        if (r == null || g == null || b == null) {
            return null;
        }
        boolean unitScale = r <= 1.0 && g <= 1.0 && b <= 1.0;
        int red = channel(r, unitScale);
        int green = channel(g, unitScale);
        int blue = channel(b, unitScale);
        return String.format(Locale.ROOT, "%02X%02X%02X", red, green, blue);
    }

    private static int channel(double value, boolean unitScale) {
        double scaled = unitScale ? value * 255.0 : value;
        return Math.max(0, Math.min(255, (int) Math.round(scaled)));
    }

    private static Double readDouble(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsDouble();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ loose text

    /**
     * The last resort: scrape coordinates out of free text. Labelled triples win outright; only when
     * none were found are bare number triples considered, because those match far too eagerly to be
     * allowed to compete with a real match.
     */
    private static MiningRoute fromLooseText(String text) {
        List<int[]> points = new ArrayList<>();
        Matcher labelled = LABELLED.matcher(text);
        while (labelled.find() && points.size() < MAX_POINTS) {
            points.add(triple(labelled));
        }
        if (points.isEmpty()) {
            Matcher bare = BARE.matcher(text);
            while (bare.find() && points.size() < MAX_POINTS) {
                points.add(triple(bare));
            }
        }
        if (points.isEmpty()) {
            return null;
        }
        MiningRoute route = new MiningRoute();
        route.points = points;
        // Loose text carries no name, and the field default ("Route") is also what a brand-new route is
        // called - leaving it would make an import indistinguishable from an empty route in the list.
        // Cleared so the single default in parse() names it.
        route.name = null;
        return route;
    }

    private static int[] triple(Matcher matcher) {
        return new int[]{
                (int) Math.floor(Double.parseDouble(matcher.group(1))),
                (int) Math.floor(Double.parseDouble(matcher.group(2))),
                (int) Math.floor(Double.parseDouble(matcher.group(3)))};
    }
}
