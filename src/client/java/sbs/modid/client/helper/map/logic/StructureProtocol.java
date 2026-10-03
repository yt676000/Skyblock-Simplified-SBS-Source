/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import sbs.modid.client.core.location.hollows.HollowsGeometry;
import sbs.modid.client.core.location.hollows.HollowsStructure;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Crystal Hollows Structure Sharing wire format, version 1: what the client sends, what it
 * accepts, and the checks both directions share. The contract is
 * {@code docs/HOLLOWS-STRUCTURE-SHARING-PROTOCOL.md}; this class is the client half of it.
 *
 * <p><b>No free text anywhere.</b> Every field is an id from a fixed set (message type, structure,
 * error code), a lobby id matching {@link #validLobby}, or a number. There is no name, no message
 * and no label for a server or another player to put words in front of the player with.
 *
 * <p><b>Decoding never throws.</b> {@link #decode} answers {@code null} for anything it does not
 * accept: not JSON, the wrong version, an unknown type, a lobby id of the wrong shape, an oversized
 * list. Inside a snapshot, one bad entry (an unknown structure, an implausible position) is dropped
 * and the rest are kept. Unknown fields are ignored, so a newer server can add fields without
 * breaking this client.
 */
public final class StructureProtocol {

    /** The protocol version this build speaks. */
    public static final int VERSION = 1;

    /** The island id on the wire. */
    public static final String ISLAND = "crystal_hollows";

    /** The most structures one lobby may hold; a longer snapshot is refused whole. */
    public static final int MAX_STRUCTURES = 20;

    /** Upper bound on a confirmation count; anything larger is not a count. */
    public static final int MAX_CONFIRMATIONS = 100_000;

    /** Upper bound on the samples behind one report. */
    public static final int MAX_SAMPLES = 1_000_000;

    /**
     * A Crystal Hollows lobby id. CONFIRMED shape: every Hypixel location packet carrying
     * {@code map='Crystal Hollows'} in the evidence logs names a {@code mini<digits><capitals>}
     * server ({@code mini10CA}, {@code mini1006P}, {@code mini141DK}), and the tab row
     * {@code Server: mini24CD} uses the same id. One spare character is allowed on each part.
     */
    private static final Pattern LOBBY = Pattern.compile("^mini[0-9]{1,5}[A-Z]{1,3}$");

    /** What a mod version may look like on the wire. */
    private static final Pattern MOD_VERSION = Pattern.compile("^[0-9A-Za-z.+-]{1,40}$");

    /** The latest epoch-millisecond value accepted for {@code firstSeen} (2100-01-01). */
    private static final long MAX_EPOCH_MS = 4_102_444_800_000L;

    private StructureProtocol() {
    }

    // ------------------------------------------------------------------ shared checks

    /** Whether {@code lobby} is a Crystal Hollows lobby id. */
    public static boolean validLobby(String lobby) {
        return lobby != null && LOBBY.matcher(lobby).matches();
    }

    /**
     * Whether a structure position is one this protocol accepts, sent or received: the centroid
     * inside the Hollows and in the structure's quadrant, the box well-formed, inside the Hollows,
     * containing the centroid, and no wider than {@link HollowsGeometry#MAX_BOX_EXTENT}.
     */
    public static boolean plausible(HollowsStructure structure, int x, int y, int z,
                                    StructureSampler.Box box) {
        if (structure == null || box == null || !structure.plausibleAt(x, y, z)) {
            return false;
        }
        if (box.minX() > box.maxX() || box.minY() > box.maxY() || box.minZ() > box.maxZ()) {
            return false;
        }
        if (!HollowsGeometry.inBounds(box.minX(), box.minY(), box.minZ())
                || !HollowsGeometry.inBounds(box.maxX(), box.maxY(), box.maxZ())) {
            return false;
        }
        return box.contains(x, y, z) && box.maxExtent() <= HollowsGeometry.MAX_BOX_EXTENT;
    }

    // ------------------------------------------------------------------ client to server

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: wss://skyblocksimplified.info/ws/hollows (sent by StructureShareClient over SbsSocket)
// METHOD: WebSocket text frames, JSON
// PURPOSE: The three messages Crystal Hollows Structure Sharing sends.
// DATA SENT: subscribe {v, type, lobby, island, mod}; report {v, type, lobby, structure, x, y, z,
//   bbox {min [x,y,z], max [x,y,z]}, samples}; unsubscribe {v, type, lobby}. lobby is the
//   Hypixel server id (mini24CD), structure a fixed id, mod the mod version. Built with Gson.
// DATA RECEIVED: Nothing on this path.
// SAFETY DECLARATION: No player name, no UUID, no chat, no free text of any kind. The lobby id
//   is disclosed under ConsentScope.HOLLOWS_STRUCTURES, which starts off. A report is built only
//   from the player's own position while their own sidebar named the structure.
// ============================================================================
    /** {@code subscribe}, or {@code null} for a lobby id of the wrong shape. */
    public static String subscribe(String lobby, String modVersion) {
        if (!validLobby(lobby)) {
            return null;
        }
        JsonObject out = envelope("subscribe");
        out.addProperty("lobby", lobby);
        out.addProperty("island", ISLAND);
        out.addProperty("mod", modVersion != null && MOD_VERSION.matcher(modVersion).matches()
                ? modVersion : "unknown");
        return out.toString();
    }

    /** {@code report}, or {@code null} when the lobby or the position would be refused. */
    public static String report(String lobby, StructureSampler.Report report) {
        if (!validLobby(lobby) || report == null
                || !plausible(report.structure(), report.x(), report.y(), report.z(), report.box())) {
            return null;
        }
        JsonObject out = envelope("report");
        out.addProperty("lobby", lobby);
        writePosition(out, report.structure(), report.x(), report.y(), report.z(), report.box());
        out.addProperty("samples", Math.max(1, Math.min(MAX_SAMPLES, report.samples())));
        return out.toString();
    }

    /** {@code unsubscribe}, or {@code null} for a lobby id of the wrong shape. */
    public static String unsubscribe(String lobby) {
        if (!validLobby(lobby)) {
            return null;
        }
        JsonObject out = envelope("unsubscribe");
        out.addProperty("lobby", lobby);
        return out.toString();
    }

    private static JsonObject envelope(String type) {
        JsonObject out = new JsonObject();
        out.addProperty("v", VERSION);
        out.addProperty("type", type);
        return out;
    }

    private static void writePosition(JsonObject out, HollowsStructure structure, int x, int y, int z,
                                      StructureSampler.Box box) {
        out.addProperty("structure", structure.wireId());
        out.addProperty("x", x);
        out.addProperty("y", y);
        out.addProperty("z", z);
        JsonObject bbox = new JsonObject();
        bbox.add("min", triple(box.minX(), box.minY(), box.minZ()));
        bbox.add("max", triple(box.maxX(), box.maxY(), box.maxZ()));
        out.add("bbox", bbox);
    }

    private static JsonArray triple(int a, int b, int c) {
        JsonArray array = new JsonArray();
        array.add(a);
        array.add(b);
        array.add(c);
        return array;
    }

    /**
     * {@code snapshot} or {@code update} in the server's shape, for tests and the mock server.
     * The client never sends these.
     */
    static JsonObject sharedJson(Shared shared) {
        JsonObject out = new JsonObject();
        writePosition(out, shared.structure(), shared.x(), shared.y(), shared.z(), shared.box());
        out.addProperty("confirmations", shared.confirmations());
        out.addProperty("firstSeen", shared.firstSeen());
        return out;
    }

    // ------------------------------------------------------------------ server to client

    /** One structure as the server knows it for a lobby. */
    public record Shared(HollowsStructure structure, int x, int y, int z, StructureSampler.Box box,
                         int confirmations, long firstSeen) {
    }

    /** A decoded, validated server message. */
    public sealed interface Message permits Snapshot, Update, ServerError {
    }

    /** Everything known in a lobby, sent after {@code subscribe}. Replaces what the client held. */
    public record Snapshot(String lobby, List<Shared> structures) implements Message {
    }

    /** One structure changed: new, moved, or more confirmations. */
    public record Update(String lobby, Shared structure) implements Message {
    }

    /** The server refused something. */
    public record ServerError(ErrorCode code) implements Message {
    }

    /** The server's error codes. Anything else reads as {@link #UNKNOWN}. */
    public enum ErrorCode {
        BAD_REQUEST("bad_request"),
        RATE_LIMITED("rate_limited"),
        UNAUTHORIZED("unauthorized"),
        UNSUPPORTED_VERSION("unsupported_version"),
        INVALID_LOBBY("invalid_lobby"),
        UNKNOWN("unknown");

        private final String wireId;

        ErrorCode(String wireId) {
            this.wireId = wireId;
        }

        public String wireId() {
            return wireId;
        }

        static ErrorCode of(String id) {
            for (ErrorCode code : values()) {
                if (code.wireId.equals(id)) {
                    return code;
                }
            }
            return UNKNOWN;
        }
    }

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: wss://skyblocksimplified.info/ws/hollows (received by StructureShareClient)
// METHOD: WebSocket text frames, JSON
// PURPOSE: Parse the server's snapshot / update / error messages for Crystal Hollows Structure
//   Sharing.
// DATA SENT: Nothing on this path.
// DATA RECEIVED: Structure ids, positions, boxes, confirmation counts and first-seen times for
//   one lobby; error codes. Parsed with Gson's tree API into the records above and validated
//   field by field. Nothing can name a class, a command or text to display.
// SAFETY DECLARATION: Read-only display data. Reporter identity is never part of the format,
//   so nothing about another player is received.
// ============================================================================
    /** A server message, or {@code null} for anything this build does not accept. Never throws. */
    public static Message decode(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject message = root.getAsJsonObject();
            Long version = integer(message, "v");
            if (version == null || version != VERSION) {
                return null;
            }
            String type = string(message, "type");
            if (type == null) {
                return null;
            }
            return switch (type) {
                case "snapshot" -> snapshot(message);
                case "update" -> update(message);
                case "error" -> {
                    String code = string(message, "code");
                    yield new ServerError(ErrorCode.of(code == null ? "" : code.toLowerCase(Locale.ROOT)));
                }
                default -> null;
            };
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static Snapshot snapshot(JsonObject message) {
        String lobby = string(message, "lobby");
        if (!validLobby(lobby) || !message.has("structures") || !message.get("structures").isJsonArray()) {
            return null;
        }
        JsonArray rows = message.getAsJsonArray("structures");
        if (rows.size() > MAX_STRUCTURES) {
            return null;
        }
        List<Shared> out = new ArrayList<>();
        Set<HollowsStructure> seen = EnumSet.noneOf(HollowsStructure.class);
        for (JsonElement row : rows) {
            Shared shared = row.isJsonObject() ? shared(row.getAsJsonObject()) : null;
            if (shared != null && seen.add(shared.structure())) {
                out.add(shared);
            }
        }
        return new Snapshot(lobby, List.copyOf(out));
    }

    private static Update update(JsonObject message) {
        String lobby = string(message, "lobby");
        if (!validLobby(lobby)) {
            return null;
        }
        Shared shared = shared(message);
        return shared == null ? null : new Update(lobby, shared);
    }

    /** One structure entry, or {@code null} when any part of it is refused. */
    private static Shared shared(JsonObject row) {
        HollowsStructure structure = HollowsStructure.byWireId(string(row, "structure"));
        Long x = integer(row, "x");
        Long y = integer(row, "y");
        Long z = integer(row, "z");
        Long confirmations = integer(row, "confirmations");
        Long firstSeen = integer(row, "firstSeen");
        StructureSampler.Box box = box(row);
        if (structure == null || x == null || y == null || z == null || box == null
                || confirmations == null || firstSeen == null) {
            return null;
        }
        if (confirmations < 1 || confirmations > MAX_CONFIRMATIONS || firstSeen < 0 || firstSeen > MAX_EPOCH_MS) {
            return null;
        }
        int ix = x.intValue();
        int iy = y.intValue();
        int iz = z.intValue();
        if (!plausible(structure, ix, iy, iz, box)) {
            return null;
        }
        return new Shared(structure, ix, iy, iz, box, confirmations.intValue(), firstSeen);
    }

    private static StructureSampler.Box box(JsonObject row) {
        if (!row.has("bbox") || !row.get("bbox").isJsonObject()) {
            return null;
        }
        JsonObject bbox = row.getAsJsonObject("bbox");
        int[] min = triple(bbox, "min");
        int[] max = triple(bbox, "max");
        if (min == null || max == null) {
            return null;
        }
        return new StructureSampler.Box(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    private static int[] triple(JsonObject parent, String name) {
        if (!parent.has(name) || !parent.get(name).isJsonArray()) {
            return null;
        }
        JsonArray array = parent.getAsJsonArray(name);
        if (array.size() != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            Long value = integer(array.get(i));
            if (value == null) {
                return null;
            }
            out[i] = value.intValue();
        }
        return out;
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    private static Long integer(JsonObject object, String name) {
        return integer(object.get(name));
    }

    /**
     * A whole number within a sane range, or {@code null}. A fractional or out-of-range number is
     * refused rather than rounded: it is not something this format ever sends.
     */
    private static Long integer(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            return null;
        }
        double value = primitive.getAsDouble();
        if (Double.isNaN(value) || value != Math.rint(value) || Math.abs(value) > MAX_EPOCH_MS) {
            return null;
        }
        return (long) value;
    }
}
