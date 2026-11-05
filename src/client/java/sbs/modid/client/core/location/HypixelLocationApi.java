/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.location;

import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sbs.modid.SkyblockSimplifiedSBS;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Hypixel's Mod API location event, spoken directly - no ModAPI dependency.
 *
 * <p><b>The handshake</b> (HypixelDev/ModAPI, {@code HypixelModAPI}, {@code ServerboundRegisterPacket},
 * {@code ClientboundLocationPacket}): the server sends {@code hypixel:hello}; the client answers once
 * with {@code hypixel:register} naming the events it wants ({@code hyevent:location}, version 1); from
 * then on the server sends {@code hyevent:location} on every server change. Without the register
 * packet the event is never sent at all.
 *
 * <p>Wire formats. Every clientbound Mod API packet starts with a success {@code boolean}; {@code false}
 * is followed by a VarInt error id and nothing else. Then:
 * <ul>
 *   <li>{@code hypixel:register} (serverbound): VarInt version (1), VarInt count, then per entry a
 *       String event id and a VarInt event version.</li>
 *   <li>{@code hyevent:location} v1: VarInt version, String serverName, then four optional Strings
 *       (a {@code boolean} present flag, then the String): serverType, lobbyName, mode, map.</li>
 * </ul>
 *
 * <p>COLD path: one packet per server switch. The handlers run on the client thread (Fabric's
 * default for these receivers); the result is still published through a {@code volatile} snapshot
 * because {@link SkyBlockLocation} may be read from anywhere.
 */
public final class HypixelLocationApi {

    /** The event this client subscribes to, and the version of its wire format we parse. */
    static final String LOCATION_EVENT = "hyevent:location";
    static final int LOCATION_VERSION = 1;
    static final int REGISTER_VERSION = 1;

    /** What the API names SkyBlock in {@code serverType}. */
    static final String SKYBLOCK = "SKYBLOCK";

    /**
     * {@code mode} → island, spelled the way the tab list's {@code Area:} row spells it, so a caller
     * comparing {@link SkyBlockLocation#island()} sees the same string whichever source answered.
     * Modes that do not name one island are deliberately absent ({@code foraging_2} is Galatea, a
     * region whose islands only the tab list tells apart) - they fall through to the tab list.
     */
    private static final Map<String, String> ISLAND_BY_MODE = Map.ofEntries(
            Map.entry("dynamic", "Private Island"),
            Map.entry("garden", "Garden"),
            Map.entry("hub", "Hub"),
            Map.entry("farming_1", "The Farming Islands"),
            Map.entry("foraging_1", "The Park"),
            Map.entry("combat_1", "Spider's Den"),
            Map.entry("combat_3", "The End"),
            Map.entry("crimson_isle", "Crimson Isle"),
            Map.entry("mining_1", "Gold Mine"),
            Map.entry("mining_2", "Deep Caverns"),
            Map.entry("mining_3", "Dwarven Mines"),
            Map.entry("crystal_hollows", "Crystal Hollows"),
            Map.entry("mineshaft", "Mineshaft"),
            Map.entry("dungeon_hub", "Dungeon Hub"),
            Map.entry("dungeon", "The Catacombs"),
            Map.entry("kuudra", "Kuudra"),
            Map.entry("rift", "The Rift"),
            Map.entry("winter", "Jerry's Workshop"),
            Map.entry("dark_auction", "Dark Auction"),
            Map.entry("fishing_1", "Backwater Bayou"));

    /** One received location event. Strings are never null; an absent optional is {@code ""}. */
    public record Location(String server, String serverType, String lobby, String mode, String map) {
        public static final Location NONE = new Location("", "", "", "", "");

        public boolean onSkyBlock() {
            return SKYBLOCK.equalsIgnoreCase(serverType);
        }
    }

    private static volatile Location current = Location.NONE;

    private HypixelLocationApi() {
    }

    /** The last location event of this connection, or {@link Location#NONE}. */
    public static Location current() {
        return current;
    }

    /**
     * The island the event names, or {@code ""} when it names none we can spell exactly (not on
     * SkyBlock, an unknown or region-wide mode). Pure - the part the tests pin down.
     */
    static String islandOf(Location location) {
        if (location == null || !location.onSkyBlock()) {
            return "";
        }
        String island = ISLAND_BY_MODE.get(location.mode().toLowerCase(Locale.ROOT));
        return island == null ? "" : island;
    }

    // ------------------------------------------------------------------ payloads

    /** {@code hypixel:hello}: only its arrival matters, so the body is skipped. */
    record Hello() implements CustomPacketPayload {
        static final Type<Hello> TYPE = new Type<>(Identifier.fromNamespaceAndPath("hypixel", "hello"));
        static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of(
                (buf, hello) -> { },
                buf -> {
                    buf.skipBytes(buf.readableBytes());
                    return new Hello();
                });

        @Override
        public Type<Hello> type() {
            return TYPE;
        }
    }

    /** {@code hyevent:location}; {@code location} is null for an error reply or an unknown version. */
    record LocationEvent(Location location) implements CustomPacketPayload {
        static final Type<LocationEvent> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("hyevent", "location"));
        static final StreamCodec<FriendlyByteBuf, LocationEvent> CODEC =
                StreamCodec.of(HypixelLocationApi::writeLocation, HypixelLocationApi::readLocation);

        @Override
        public Type<LocationEvent> type() {
            return TYPE;
        }
    }

    /** {@code hypixel:register}, subscribing to {@link #LOCATION_EVENT} and nothing else. */
    record Register() implements CustomPacketPayload {
        static final Type<Register> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("hypixel", "register"));
        static final StreamCodec<FriendlyByteBuf, Register> CODEC = StreamCodec.of(
                (buf, register) -> {
                    buf.writeVarInt(REGISTER_VERSION);
                    buf.writeVarInt(1);
                    buf.writeUtf(LOCATION_EVENT);
                    buf.writeVarInt(LOCATION_VERSION);
                },
                buf -> {
                    buf.skipBytes(buf.readableBytes());
                    return new Register();
                });

        @Override
        public Type<Register> type() {
            return TYPE;
        }
    }

    // ============================================================================
    // [MODERATOR AUDIT / NETWORK DISCLOSURE]
    // KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
    // ENDPOINT: the connected Minecraft server (Hypixel), plugin channels hypixel:hello (in),
    //   hypixel:register (out), hyevent:location (in). No HTTP; no connection is opened.
    // METHOD: Minecraft custom payload packets on the existing game connection
    // PURPOSE: Ask Hypixel's official Mod API which server/island the player is on, so island
    //   detection works when the sidebar or tab list is hidden
    // DATA SENT: hypixel:register - a version number and the event id "hyevent:location" with its
    //   version. Nothing about the player, the account or the machine
    // DATA RECEIVED: server name, server type, lobby name, mode and map strings. Parsed by hand from
    //   the packet buffer (no deserialization), kept in memory only, never stored or forwarded
    // SAFETY DECLARATION: Sent only to the server the player is already connected to, and only after
    //   that server announced the Mod API with hypixel:hello. No credentials, session id, uuid or
    //   telemetry.
    // ============================================================================
    static LocationEvent readLocation(FriendlyByteBuf buf) {
        try {
            if (!buf.readBoolean()) {
                return new LocationEvent(null);
            }
            if (buf.readVarInt() != LOCATION_VERSION) {
                return new LocationEvent(null);
            }
            String server = buf.readUtf();
            String serverType = readOptional(buf);
            String lobby = readOptional(buf);
            String mode = readOptional(buf);
            String map = readOptional(buf);
            return new LocationEvent(new Location(server, serverType, lobby, mode, map));
        } finally {
            // An error reply or a future version leaves bytes we did not read; vanilla rejects a
            // payload that is not consumed in full.
            buf.skipBytes(buf.readableBytes());
        }
    }

    private static String readOptional(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUtf() : "";
    }

    /** The exact inverse of {@link #readLocation}; used by the tests to build captured packets. */
    static void writeLocation(FriendlyByteBuf buf, LocationEvent event) {
        Location l = event.location();
        buf.writeBoolean(l != null);
        if (l == null) {
            buf.writeVarInt(0);
            return;
        }
        buf.writeVarInt(LOCATION_VERSION);
        buf.writeUtf(l.server());
        writeOptional(buf, l.serverType());
        writeOptional(buf, l.lobby());
        writeOptional(buf, l.mode());
        writeOptional(buf, l.map());
    }

    private static void writeOptional(FriendlyByteBuf buf, String value) {
        buf.writeBoolean(!value.isEmpty());
        if (!value.isEmpty()) {
            buf.writeUtf(value);
        }
    }

    // ------------------------------------------------------------------ wiring

    /** Registers the payload types and receivers. Once, from the client initializer. */
    public static void init() {
        // The Hypixel Mod API mod (shipped alongside Skyblocker and others) owns these channels: it
        // registers the same payload ids, and Fabric refuses a second registration, which crashes the
        // client initializer. Stand down; SkyBlockLocation falls back to the scoreboard.
        if (FabricLoader.getInstance().isModLoaded("hypixel-mod-api")) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Location] hypixel-mod-api present, leaving the location handshake to it");
            return;
        }
        // The hello may arrive while (re)configuring or in play; listen in both phases.
        PayloadTypeRegistry.clientboundConfiguration().register(Hello.TYPE, Hello.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Hello.TYPE, Hello.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(Register.TYPE, Register.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Register.TYPE, Register.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LocationEvent.TYPE, LocationEvent.CODEC);

        ClientConfigurationNetworking.registerGlobalReceiver(Hello.TYPE, (hello, context) ->
                context.responseSender().sendPacket(new Register()));
        ClientPlayNetworking.registerGlobalReceiver(Hello.TYPE, (hello, context) ->
                context.responseSender().sendPacket(new Register()));
        ClientPlayNetworking.registerGlobalReceiver(LocationEvent.TYPE, (event, context) ->
                accept(event.location()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> current = Location.NONE);
    }

    private static void accept(Location location) {
        if (location == null || Objects.equals(location, current)) {
            return;
        }
        current = location;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Location] api server={} type={} mode={} map={}",
                location.server(), location.serverType(), location.mode(), location.map());
    }
}
