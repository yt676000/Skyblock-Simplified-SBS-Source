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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import sbs.modid.client.combat.diana.devlog.DevLogEvent.Pos;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

/**
 * Every Diana capture event, as a pure function of plain values, and the one place an event becomes
 * a line of JSON.
 *
 * <p>Nothing here touches the game. The hooks in {@link DianaDevLog} read a packet or the world into
 * strings and numbers on the client thread and call one of these; that split is what lets every event
 * type be tested with fixed inputs, and what guarantees no live game object reaches the writer thread.
 *
 * <p>The line shape is fixed: {@code {"seq","t","tick","type","pos","data"}}. The field names inside
 * {@code data} are listed per type in {@code docs/features/diana-log-mode.md}; a reader of a capture
 * depends on them, so they are renamed only together with that table and {@link #LOG_SCHEMA}.
 */
public final class DevLogEvents {

    /** Bumped when a field is renamed or changes meaning. Written into {@code session_start}. */
    public static final int LOG_SCHEMA = 1;

    public static final String SESSION_START = "session_start";
    public static final String SESSION_END = "session_end";
    public static final String PLAYER = "player";
    public static final String SOUND = "sound";
    public static final String SOUND_ENTITY = "sound_entity";
    public static final String PARTICLE = "particle";
    public static final String ENTITY_ADD = "entity_add";
    public static final String ENTITY_DATA = "entity_data";
    public static final String ENTITY_EQUIPMENT = "entity_equipment";
    public static final String ENTITY_REMOVE = "entity_remove";
    public static final String BLOCK_UPDATE = "block_update";
    public static final String CHAT = "chat";
    public static final String ACTIONBAR = "actionbar";
    public static final String TITLE = "title";
    public static final String SUBTITLE = "subtitle";
    public static final String USE_ITEM = "use_item";
    public static final String USE_BLOCK = "use_block";
    public static final String ATTACK_BLOCK = "attack_block";
    public static final String STATE = "state";
    public static final String PACKET_COUNTS = "packet_counts";
    public static final String MARK = "mark";
    public static final String ERROR = "error";
    public static final String STATS = "stats";

    /** What a sound packet's coordinates are worth; see {@code ClientboundSoundPacket}'s constructor. */
    public static final String SOUND_PRECISION = "sound x/y/z are stored as (int)(coordinate * 8), truncated "
            + "toward zero, and read back as int / 8.0F: 1/8-block steps with float rounding. Particle "
            + "positions are full doubles.";

    /** Stamped on every capture, because the file is exactly what the warning describes. */
    public static final String PRIVACY = "Contains chat, nametags and tab lines, which carry other players' "
            + "names. Keep it local; never commit it; replace names with placeholders before using a "
            + "line as a test fixture.";

    /**
     * No HTML escaping: a capture is read by eye, and escaped section signs make it unreadable. Nulls
     * kept: Gson drops null members by default, which would turn {@code "pos":null} into a missing key
     * and give one event type a different set of fields from one line to the next.
     */
    private static final Gson LINE = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    /** When an event was built: wall clock, and client ticks since the capture started. */
    public record Stamp(long t, long tick) {
    }

    /**
     * One value from an entity metadata packet. {@code legacy} is the {@code §} form when the value is
     * text (a name, a display entity's text), else {@code null}.
     */
    public record DataEntry(int index, int serializer, String type, String value, String legacy) {
    }

    /**
     * One slot from an equipment packet. {@code skin} is the SkyBlock skin id from custom data,
     * {@code texture} the head profile's texture URL - the second is what tells two mob heads apart.
     */
    public record EquipmentEntry(String slot, String skyblockId, String item, String name,
                                 boolean hasProfile, String skin, String texture) {
    }

    private DevLogEvents() {
    }

    // ------------------------------------------------------------------
    // The line
    // ------------------------------------------------------------------

    /** The event as one line of JSON, without the newline. {@code seq} is the writer's line number. */
    public static String toJsonLine(long seq, DevLogEvent event) {
        return LINE.toJson(toJson(seq, event));
    }

    /** The event as the JSON object a line holds - for the guard's one-off crash file. */
    public static JsonObject toJson(long seq, DevLogEvent event) {
        JsonObject line = new JsonObject();
        line.addProperty("seq", seq);
        line.addProperty("t", event.t());
        line.addProperty("tick", event.tick());
        line.addProperty("type", event.type());
        line.add("pos", event.pos() == null ? JsonNull.INSTANCE : pos(event.pos()));
        line.add("data", event.data() == null ? new JsonObject() : event.data());
        return line;
    }

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------

    public static DevLogEvent sessionStart(Stamp s, Pos player, String modVersion, String mcVersion,
                                           JsonObject area, boolean dianaEnabled, JsonElement dianaConfig,
                                           String dianaEvent, JsonObject guard, String heldItem, int radius) {
        JsonObject d = new JsonObject();
        d.addProperty("logSchema", LOG_SCHEMA);
        d.addProperty("modVersion", modVersion);
        d.addProperty("mcVersion", mcVersion);
        d.add("area", orEmpty(area));
        d.addProperty("dianaEnabled", dianaEnabled);
        d.add("dianaConfig", dianaConfig == null ? JsonNull.INSTANCE : dianaConfig);
        d.addProperty("dianaEvent", dianaEvent);
        d.add("guard", orEmpty(guard));
        d.addProperty("heldItem", heldItem);
        d.addProperty("radius", radius);
        d.addProperty("soundPrecision", SOUND_PRECISION);
        d.addProperty("privacy", PRIVACY);
        return new DevLogEvent(s.t(), s.tick(), SESSION_START, player, d);
    }

    public static DevLogEvent sessionEnd(Stamp s, String reason, long written, long dropped, long durationMs) {
        JsonObject d = new JsonObject();
        d.addProperty("reason", reason);
        d.addProperty("written", written);
        d.addProperty("dropped", dropped);
        d.addProperty("durationMs", durationMs);
        return new DevLogEvent(s.t(), s.tick(), SESSION_END, null, d);
    }

    public static DevLogEvent stats(Stamp s, long windowMs, Map<String, Double> perSecond, long written,
                                    long dropped, int queued) {
        JsonObject rates = new JsonObject();
        perSecond.forEach((type, rate) -> num(rates, type, rate));
        JsonObject d = new JsonObject();
        d.addProperty("windowMs", windowMs);
        d.add("perSecond", rates);
        d.addProperty("written", written);
        d.addProperty("dropped", dropped);
        d.addProperty("queued", queued);
        return new DevLogEvent(s.t(), s.tick(), STATS, null, d);
    }

    public static DevLogEvent mark(Stamp s, Pos player, String text) {
        JsonObject d = new JsonObject();
        d.addProperty("text", text);
        return new DevLogEvent(s.t(), s.tick(), MARK, player, d);
    }

    /**
     * Something threw. {@code source} is {@code "guard"} when a Diana tracker hook threw and the guard
     * disabled the tracker, {@code "devlog"} when one of this capture's own hooks did.
     */
    public static DevLogEvent error(Stamp s, String source, String entryPoint, String input,
                                    Throwable failure, JsonElement snapshot, JsonArray recent) {
        JsonObject d = new JsonObject();
        d.addProperty("source", source);
        d.addProperty("entryPoint", entryPoint);
        d.addProperty("input", input);
        d.addProperty("exception", failure == null ? null : failure.getClass().getName());
        d.addProperty("message", failure == null ? null : failure.getMessage());
        d.addProperty("stackTrace", stackTrace(failure));
        JsonArray causes = new JsonArray();
        for (Throwable cause = failure == null ? null : failure.getCause();
             cause != null && causes.size() < 16; cause = cause.getCause()) {
            causes.add(cause.getClass().getName() + ": " + cause.getMessage());
        }
        d.add("causes", causes);
        d.add("snapshot", snapshot == null ? JsonNull.INSTANCE : snapshot);
        d.add("recent", recent == null ? new JsonArray() : recent);
        return new DevLogEvent(s.t(), s.tick(), ERROR, null, d);
    }

    /** The full stack trace, causes and suppressed exceptions included, as the JVM prints it. */
    public static String stackTrace(Throwable failure) {
        if (failure == null) {
            return null;
        }
        StringWriter out = new StringWriter(2048);
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    // ------------------------------------------------------------------
    // The player
    // ------------------------------------------------------------------

    public static DevLogEvent player(Stamp s, Pos pos, float yaw, float pitch, boolean onGround, String heldItem) {
        JsonObject d = new JsonObject();
        num(d, "yaw", yaw);
        num(d, "pitch", pitch);
        d.addProperty("onGround", onGround);
        d.addProperty("heldItem", heldItem);
        return new DevLogEvent(s.t(), s.tick(), PLAYER, pos, d);
    }

    public static DevLogEvent useItem(Stamp s, Pos player, String hand, String skyblockId, String name,
                                      float yaw, float pitch, Pos eye, Pos look, Pos camera) {
        JsonObject d = new JsonObject();
        d.addProperty("hand", hand);
        d.addProperty("skyblockId", skyblockId);
        d.addProperty("name", name);
        num(d, "yaw", yaw);
        num(d, "pitch", pitch);
        d.add("eye", posOrNull(eye));
        d.add("look", posOrNull(look));
        d.add("camera", posOrNull(camera));
        return new DevLogEvent(s.t(), s.tick(), USE_ITEM, player, d);
    }

    /**
     * A right click on a block. Carries the same view fields as {@link #useItem}: a spade's ability
     * fired at the ground arrives here, and possibly only here, so its direction must be here too.
     */
    public static DevLogEvent useBlock(Stamp s, Pos block, String hand, String face, Pos hit,
                                       String blockState, String skyblockId, float yaw, float pitch,
                                       Pos eye, Pos look, Pos camera) {
        JsonObject d = new JsonObject();
        d.addProperty("hand", hand);
        d.addProperty("face", face);
        d.add("hit", posOrNull(hit));
        d.addProperty("block", blockState);
        d.addProperty("skyblockId", skyblockId);
        num(d, "yaw", yaw);
        num(d, "pitch", pitch);
        d.add("eye", posOrNull(eye));
        d.add("look", posOrNull(look));
        d.add("camera", posOrNull(camera));
        return new DevLogEvent(s.t(), s.tick(), USE_BLOCK, block, d);
    }

    public static DevLogEvent attackBlock(Stamp s, Pos block, String face, String blockState,
                                          String skyblockId, Pos player, float yaw, float pitch) {
        JsonObject d = new JsonObject();
        d.addProperty("face", face);
        d.addProperty("block", blockState);
        d.addProperty("skyblockId", skyblockId);
        d.add("player", posOrNull(player));
        num(d, "yaw", yaw);
        num(d, "pitch", pitch);
        return new DevLogEvent(s.t(), s.tick(), ATTACK_BLOCK, block, d);
    }

    public static DevLogEvent state(Stamp s, Pos player, JsonObject area, String world, String dianaEvent,
                                    List<String> scoreboard, List<String> tab) {
        JsonObject d = new JsonObject();
        d.add("area", orEmpty(area));
        d.addProperty("world", world);
        d.addProperty("dianaEvent", dianaEvent);
        d.add("scoreboard", strings(scoreboard));
        d.add("tab", strings(tab));
        return new DevLogEvent(s.t(), s.tick(), STATE, player, d);
    }

    // ------------------------------------------------------------------
    // Server packets
    // ------------------------------------------------------------------

    public static DevLogEvent sound(Stamp s, Pos pos, String id, boolean registered, String source,
                                    float volume, float pitch, Integer note, long seed, double distance) {
        JsonObject d = new JsonObject();
        d.addProperty("id", id);
        d.addProperty("registered", registered);
        d.addProperty("source", source);
        num(d, "volume", volume);
        num(d, "pitch", pitch);
        d.addProperty("note", note);
        d.addProperty("seed", seed);
        num(d, "distance", distance);
        return new DevLogEvent(s.t(), s.tick(), SOUND, pos, d);
    }

    public static DevLogEvent soundEntity(Stamp s, Pos pos, String id, boolean registered, int entityId,
                                          String source, float volume, float pitch, Integer note, long seed,
                                          double distance) {
        JsonObject d = new JsonObject();
        d.addProperty("id", id);
        d.addProperty("registered", registered);
        d.addProperty("entityId", entityId);
        d.addProperty("source", source);
        num(d, "volume", volume);
        num(d, "pitch", pitch);
        d.addProperty("note", note);
        d.addProperty("seed", seed);
        num(d, "distance", distance);
        return new DevLogEvent(s.t(), s.tick(), SOUND_ENTITY, pos, d);
    }

    public static DevLogEvent particle(Stamp s, Pos pos, String type, String options, String colour,
                                       float dx, float dy, float dz, float maxSpeed, int count,
                                       boolean overrideLimiter, boolean alwaysShow, double distance) {
        JsonObject d = new JsonObject();
        d.addProperty("type", type);
        d.addProperty("options", options);
        d.addProperty("colour", colour);
        d.add("dist", pos(new Pos(dx, dy, dz)));
        num(d, "maxSpeed", maxSpeed);
        d.addProperty("count", count);
        d.addProperty("overrideLimiter", overrideLimiter);
        d.addProperty("alwaysShow", alwaysShow);
        num(d, "distance", distance);
        return new DevLogEvent(s.t(), s.tick(), PARTICLE, pos, d);
    }

    public static DevLogEvent entityAdd(Stamp s, Pos pos, int id, String uuid, String type, float yaw,
                                        float pitch, float headYaw, int data, double distance) {
        JsonObject d = new JsonObject();
        d.addProperty("id", id);
        d.addProperty("uuid", uuid);
        d.addProperty("type", type);
        num(d, "yaw", yaw);
        num(d, "pitch", pitch);
        num(d, "headYaw", headYaw);
        d.addProperty("data", data);
        num(d, "distance", distance);
        return new DevLogEvent(s.t(), s.tick(), ENTITY_ADD, pos, d);
    }

    public static DevLogEvent entityData(Stamp s, Pos pos, int id, List<DataEntry> values, String customName,
                                         String customNameLegacy, Boolean nameVisible, Boolean invisible) {
        JsonArray list = new JsonArray();
        for (DataEntry value : values) {
            JsonObject v = new JsonObject();
            v.addProperty("index", value.index());
            v.addProperty("serializer", value.serializer());
            v.addProperty("type", value.type());
            v.addProperty("value", value.value());
            if (value.legacy() != null) {
                v.addProperty("legacy", value.legacy());
            }
            list.add(v);
        }
        JsonObject d = new JsonObject();
        d.addProperty("id", id);
        d.add("values", list);
        d.addProperty("customName", customName);
        d.addProperty("customNameLegacy", customNameLegacy);
        d.addProperty("nameVisible", nameVisible);
        d.addProperty("invisible", invisible);
        return new DevLogEvent(s.t(), s.tick(), ENTITY_DATA, pos, d);
    }

    public static DevLogEvent entityEquipment(Stamp s, Pos pos, int id, List<EquipmentEntry> slots) {
        JsonArray list = new JsonArray();
        for (EquipmentEntry slot : slots) {
            JsonObject v = new JsonObject();
            v.addProperty("slot", slot.slot());
            v.addProperty("skyblockId", slot.skyblockId());
            v.addProperty("item", slot.item());
            v.addProperty("name", slot.name());
            v.addProperty("hasProfile", slot.hasProfile());
            v.addProperty("skin", slot.skin());
            v.addProperty("texture", slot.texture());
            list.add(v);
        }
        JsonObject d = new JsonObject();
        d.addProperty("id", id);
        d.add("slots", list);
        return new DevLogEvent(s.t(), s.tick(), ENTITY_EQUIPMENT, pos, d);
    }

    public static DevLogEvent entityRemove(Stamp s, int[] ids, int outOfRange) {
        JsonArray list = new JsonArray();
        for (int id : ids) {
            list.add(id);
        }
        JsonObject d = new JsonObject();
        d.add("ids", list);
        d.addProperty("outOfRange", outOfRange);
        return new DevLogEvent(s.t(), s.tick(), ENTITY_REMOVE, null, d);
    }

    public static DevLogEvent blockUpdate(Stamp s, Pos block, String state, String previous, boolean multi) {
        JsonObject d = new JsonObject();
        d.addProperty("state", state);
        d.addProperty("previous", previous);
        d.addProperty("multi", multi);
        return new DevLogEvent(s.t(), s.tick(), BLOCK_UPDATE, block, d);
    }

    /**
     * A line of text from the server: {@link #CHAT}, {@link #ACTIONBAR}, {@link #TITLE} or
     * {@link #SUBTITLE}. {@code packet} names the packet it came in, because the action bar can arrive
     * as either of two and which one Hypixel uses is one of the questions the capture answers.
     */
    public static DevLogEvent text(Stamp s, String type, String text, String legacy, boolean overlay, String packet) {
        JsonObject d = new JsonObject();
        d.addProperty("text", text);
        d.addProperty("legacy", legacy);
        d.addProperty("overlay", overlay);
        d.addProperty("packet", packet);
        return new DevLogEvent(s.t(), s.tick(), type, null, d);
    }

    public static DevLogEvent packetCounts(Stamp s, Map<String, Long> counts, Map<String, Long> bundled) {
        JsonObject d = new JsonObject();
        d.add("counts", longs(counts));
        d.add("bundled", longs(bundled));
        return new DevLogEvent(s.t(), s.tick(), PACKET_COUNTS, null, d);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * A number, or {@code null} when it is not finite. JSON has no spelling for NaN or infinity; Gson
     * writes them bare when serialising a tree, and the export's strict parser would then drop the
     * whole line.
     */
    public static void num(JsonObject into, String key, double value) {
        if (Double.isFinite(value)) {
            into.addProperty(key, value);
        } else {
            into.add(key, JsonNull.INSTANCE);
        }
    }

    public static JsonObject pos(Pos pos) {
        JsonObject out = new JsonObject();
        num(out, "x", pos.x());
        num(out, "y", pos.y());
        num(out, "z", pos.z());
        return out;
    }

    private static JsonElement posOrNull(Pos pos) {
        return pos == null ? JsonNull.INSTANCE : pos(pos);
    }

    private static JsonObject orEmpty(JsonObject object) {
        return object == null ? new JsonObject() : object;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray out = new JsonArray();
        if (values != null) {
            values.forEach(out::add);
        }
        return out;
    }

    private static JsonObject longs(Map<String, Long> values) {
        JsonObject out = new JsonObject();
        if (values != null) {
            values.forEach(out::addProperty);
        }
        return out;
    }
}
