/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net.validation;

import java.util.regex.Pattern;

import static sbs.modid.client.core.net.validation.Schema.any;
import static sbs.modid.client.core.net.validation.Schema.array;
import static sbs.modid.client.core.net.validation.Schema.bool;
import static sbs.modid.client.core.net.validation.Schema.enumOf;
import static sbs.modid.client.core.net.validation.Schema.integer;
import static sbs.modid.client.core.net.validation.Schema.map;
import static sbs.modid.client.core.net.validation.Schema.number;
import static sbs.modid.client.core.net.validation.Schema.object;
import static sbs.modid.client.core.net.validation.Schema.relayed;
import static sbs.modid.client.core.net.validation.Schema.string;

/**
 * Every message type id and its schema. One entry per row of the inventory in
 * {@code docs/features/comms-hardening.md}; ids are stable and never renamed.
 *
 * <p>{@code sbs.*} types are SBS-defined: fixed field sets, unknown members rejected.
 * {@code relay.*} types are third-party documents: only the members this client reads are declared.
 * {@code out.*} types are checked before a request or frame is sent.
 */
public final class MessageTypes {

    // SBS server, inbound
    public static final String ERROR = "sbs.error";
    public static final String SOCKET_TICKET = "sbs.socket.ticket";
    public static final String SESSION_CHALLENGE = "sbs.session.challenge";
    public static final String SESSION_VERIFY = "sbs.session.verify";
    public static final String FREE_ACCESS_CHALLENGE = "sbs.free_token.challenge";
    public static final String FREE_ACCESS_CLAIM = "sbs.free_token.claim";
    public static final String AH_FLIPS_POLL = "sbs.ah.flips.poll";
    public static final String APPRAISE = "sbs.appraise";
    public static final String BAZAAR_FLIPS = "sbs.bazaar.flips";
    public static final String FORGE_FLIPS = "sbs.forge.flips";
    public static final String MAYOR = "sbs.mayor";
    public static final String PRICE_ITEM = "sbs.price.item";
    public static final String PRICE_ITEMS = "sbs.price.items";
    public static final String ENCHANTS = "sbs.enchants";
    public static final String DATASET = "sbs.dataset";
    public static final String HEALTH = "sbs.health";
    public static final String CARRY_META = "sbs.carry.meta";
    public static final String CARRY_TICKETS = "sbs.carry.tickets";
    public static final String CARRY_TICKET = "sbs.carry.ticket";
    public static final String CARRY_OK = "sbs.carry.ok";
    public static final String CARRY_CHAT_ACK = "sbs.carry.chat.ack";
    public static final String CARRY_POLL = "sbs.carry.poll";
    public static final String CARRY_ADMIN_CARRIERS = "sbs.carry.admin.carriers";
    public static final String CARRY_ADMIN_SET = "sbs.carry.admin.set";
    public static final String PARTY_LIST = "sbs.party.list";
    public static final String PARTY_SINGLE = "sbs.party.single";
    public static final String PARTY_OK = "sbs.party.ok";
    public static final String PARTY_CHAT_ACK = "sbs.party.chat.ack";
    public static final String PARTY_POLL = "sbs.party.poll";
    public static final String IRC_POLL = "sbs.irc.poll";
    public static final String IRC_SEND_ACK = "sbs.irc.send.ack";
    public static final String PRESENCE_ACK = "sbs.presence.ack";
    public static final String PRIVACY_ACK = "sbs.privacy.ack";
    public static final String HOLLOWS_SNAPSHOT = "sbs.ws.hollows.snapshot";
    public static final String HOLLOWS_UPDATE = "sbs.ws.hollows.update";
    public static final String HOLLOWS_ERROR = "sbs.ws.hollows.error";

    // Local listener (ApiServer), inbound from tooling on the same machine
    public static final String LOCAL_HIGHLIGHT = "local.highlight";

    // Relayed third-party documents, inbound
    public static final String HYPIXEL_BAZAAR = "relay.hypixel.bazaar";
    public static final String HYPIXEL_AUCTIONS = "relay.hypixel.auctions";
    public static final String HYPIXEL_AUCTIONS_ENDED = "relay.hypixel.auctions_ended";
    public static final String HYPIXEL_ITEMS = "relay.hypixel.items";
    public static final String HYPIXEL_COLLECTIONS = "relay.hypixel.collections";
    public static final String MOJANG_PROFILE = "relay.mojang.profile";
    /** Player Viewer relays: raw Hypixel bodies; the viewer validates per section on top. */
    public static final String PV_PROFILES = "relay.pv.profiles";
    public static final String PV_PROFILE = "relay.pv.profile";
    public static final String PV_MUSEUM = "relay.pv.museum";
    public static final String PV_STATUS = "relay.pv.status";
    public static final String REPO_ITEM = "relay.repo.item";
    public static final String REPO_PET_ITEM = "relay.repo.pet_item";
    public static final String REPO_ARCHIVE = "relay.repo.archive";

    // Outbound
    public static final String OUT_PRIVACY_CONSENT = "out.privacy.consent";
    public static final String OUT_PRIVACY_DELETE = "out.privacy.delete";
    public static final String OUT_SOCKET_TICKET = "out.socket.ticket";
    public static final String OUT_SESSION_VERIFY = "out.session.verify";
    public static final String OUT_FREE_ACCESS_CLAIM = "out.free_token.claim";
    public static final String OUT_APPRAISE = "out.appraise";
    public static final String OUT_PRESENCE = "out.presence.sync";
    public static final String OUT_CARRY_CREATE = "out.carry.create";
    public static final String OUT_CARRY_TICKET_ID = "out.carry.ticket_id";
    public static final String OUT_CARRY_CHAT = "out.carry.chat";
    public static final String OUT_CARRY_SET_CARRIER = "out.carry.admin.set";
    public static final String OUT_CARRY_REMOVE_CARRIER = "out.carry.admin.remove";
    public static final String OUT_PARTY_CREATE = "out.party.create";
    public static final String OUT_PARTY_MEMBER = "out.party.member";
    public static final String OUT_PARTY_KICK = "out.party.kick";
    public static final String OUT_PARTY_CHAT = "out.party.chat";
    public static final String OUT_IRC_SEND = "out.irc.send";
    public static final String OUT_HOLLOWS_SUBSCRIBE = "out.ws.hollows.subscribe";
    public static final String OUT_HOLLOWS_REPORT = "out.ws.hollows.report";
    public static final String OUT_HOLLOWS_UNSUBSCRIBE = "out.ws.hollows.unsubscribe";

    /** Display names as the SBS server accepts them (system lines may carry an empty one). */
    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{0,16}$");
    static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{32}$|^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$");
    static final Pattern ITEM_ID = Pattern.compile("^[A-Za-z0-9_:;.\\-+]{0,96}$");
    static final Pattern COLOR = Pattern.compile("^#?[0-9a-fA-F]{6}$");
    static final Pattern LOBBY = Pattern.compile("^mini[0-9]{1,5}[A-Z]{1,3}$");

    private static final long BIG = 1_000_000_000_000_000L;
    private static final double NUM = 1e15;

    private MessageTypes() {
    }

    private static Schema text(int max) {
        return string(max);
    }

    private static Schema id(int max) {
        return string(max, Schema.ID);
    }

    private static Schema num() {
        return number(-NUM, NUM);
    }

    private static Schema count() {
        return integer(0, BIG);
    }

    private static Schema epoch() {
        return number(0, 4.2e12);
    }

    private static Schema numbers(int max) {
        return array(num(), max);
    }

    private static Schema name() {
        return string(0, 16, NAME);
    }

    /** A small open object whose values are scalars or short arrays (requirement entries). */
    private static Schema smallMap(int entries) {
        Schema scalar = new Schema() {
            @Override
            void check(com.google.gson.JsonElement value, String path) throws ValidationException {
                if (value == null || value.isJsonNull()) {
                    return;
                }
                if (value.isJsonPrimitive()) {
                    if (value.getAsJsonPrimitive().isString()) {
                        text(256).check(value, path);
                    } else if (value.getAsJsonPrimitive().isNumber()) {
                        num().check(value, path);
                    }
                    return;
                }
                if (value.isJsonArray()) {
                    array(text(256).nullable(), 64).check(value, path);
                    return;
                }
                throw new ValidationException(null, ValidationException.Kind.WRONG_TYPE, path);
            }
        };
        return map(Schema.ID, 64, scalar, entries);
    }

    static void registerAll() {
        JsonLimits sbs = JsonLimits.DEFAULT;
        JsonLimits relay = JsonLimits.RELAY;

        // ------------------------------------------------------------ shared
        MessageSchemas.register(ERROR, object()
                .required("error", text(200))
                .optional("req_detail", array(smallMap(16), 50))
                .optional("ok", bool()), sbs);
        // Verified session (SessionIdentity): fixed fields, unknown members rejected.
        MessageSchemas.register(SESSION_CHALLENGE, object()
                .required("serverId", string(40, 40, Pattern.compile("^[0-9a-f]{40}$"))), sbs);
        MessageSchemas.register(SESSION_VERIFY, object()
                .required("session", string(69, 69, Pattern.compile("^sbss_[0-9a-f]{64}$")))
                .required("uuid", string(32, 32, Pattern.compile("^[0-9a-f]{32}$")))
                .required("name", string(3, 16, NAME))
                .required("expiresAt", integer(0, 32_503_680_000L)), sbs);
        // Automatic PlayerViewer access (FreeAccess): fixed fields, unknown members rejected.
        MessageSchemas.register(FREE_ACCESS_CHALLENGE, object()
                .required("serverId", string(40, 40, Pattern.compile("^[0-9a-f]{40}$")))
                .required("expires_in", integer(1, 3600)), sbs);
        MessageSchemas.register(FREE_ACCESS_CLAIM, object()
                .required("token", string(72, 72, Pattern.compile("^sbsfree_[0-9a-f]{64}$")))
                .required("uuid", string(32, 32, Pattern.compile("^[0-9a-f]{32}$")))
                .required("expiresAt", integer(0, 32_503_680_000L)), sbs);
        MessageSchemas.register(SOCKET_TICKET, object()
                .required("ticket", string(16, 1024, Pattern.compile("^[A-Za-z0-9._~+/=-]+$"))), sbs);

        // ------------------------------------------------------------ auctions
        Schema mayorSummary = map(Schema.ID, 64, any(), 64);
        Schema flipRow = new Schema() {
            @Override
            void check(com.google.gson.JsonElement value, String path) throws ValidationException {
                array(any(), 32).check(value, path);
                var row = value.getAsJsonArray();
                if (row.size() < 15) {
                    throw new ValidationException(null, ValidationException.Kind.MISSING_FIELD, path);
                }
                string(32, 36, UUID).check(row.get(2), path + "[2]");
                string(1, 96, ITEM_ID).check(row.get(3), path + "[3]");
                for (int i : new int[] {4, 5, 6, 8, 9}) {
                    num().check(row.get(i), path + "[" + i + "]");
                }
                num().check(row.get(7), path + "[7]");
                number(0, 1_000_000).check(row.get(10), path + "[10]");
                number(0, 100).check(row.get(11), path + "[11]");
                string(64, ITEM_ID).nullable().check(row.get(13), path + "[13]");
                num().check(row.get(14), path + "[14]");
            }
        };
        MessageSchemas.register(AH_FLIPS_POLL, object()
                .optional("seq", count())
                .optional("flips", array(flipRow, 2_000))
                .optional("mayor", mayorSummary), sbs);
        Schema lastSale = relayed().optional("ts", epoch()).optional("unit", num());
        Schema soldIn = relayed().optional("median", num()).optional("avg", num()).optional("n", count());
        Schema tier = relayed()
                .optional("n", count()).optional("median", num()).optional("avg", num())
                .optional("min", num()).optional("max", num()).optional("last", lastSale)
                .optional("oldest_ts", epoch()).optional("sold_in", soldIn);
        Schema mayorEffect = relayed()
                .optional("available", bool()).optional("mayor_name", text(64))
                .optional("election_year", integer(0, 100_000))
                .optional("effect_pct", num()).optional("current_median", num()).optional("baseline_median", num())
                .optional("n_current", count()).optional("n_baseline", count())
                .optional("note", text(512)).optional("reason", text(256));
        MessageSchemas.register(APPRAISE, object()
                .optional("item_id", string(96, ITEM_ID))
                .optional("variant_key", text(512))
                .optional("attrs", any())
                .optional("value", num())
                .optional("value_basis", text(128))
                .optional("value_n", count())
                .optional("value_max_distance", num())
                .optional("extras_value", num())
                .optional("value_factor", num())
                .optional("price_spike", relayed().optional("factor", num()).optional("recent", num())
                        .optional("baseline", num()).optional("recent_hours", count())
                        .optional("baseline_days", count()))
                .optional("mayor", mayorEffect)
                .optional("lbin", numbers(8))
                .optional("tiers", relayed().optional("exact", tier).optional("similar", tier).optional("base", tier))
                .optional("recent", array(numbers(16), 5_000))
                .optional("live_cheapest", array(array(any(), 16), 500))
                .optional("closest", array(any(), 500))
                .optional("results", array(any(), 200)), sbs);

        // ------------------------------------------------------------ bazaar / forge / mayor
        MessageSchemas.register(BAZAAR_FLIPS, object()
                .optional("g", count())
                .optional("data_ts", epoch())
                .required("flips", array(object()
                        .optional("type", id(32)).optional("item_id", string(96, ITEM_ID))
                        .optional("buy", num()).optional("sell", num()).optional("profit", num())
                        .optional("margin_pct", num()).optional("volume_week", num())
                        .optional("fill_s", num()).optional("sell_s", num()).optional("score", num())
                        .optional("craft_from", string(96, ITEM_ID)).optional("craft_count", count()), 5_000)),
                sbs);
        MessageSchemas.register(FORGE_FLIPS, object()
                .optional("data_ts", epoch())
                .optional("weights", object().optional("now", num()).optional("day", num()).optional("week", num()))
                .required("flips", array(object()
                        .optional("item_id", string(96, ITEM_ID)).optional("name", text(128))
                        .optional("buy", num()).optional("sell", num()).optional("profit", num())
                        .optional("margin_pct", num()).optional("duration_s", num()).optional("count", count())
                        .optional("per_hour", num()).optional("per_hour_day", num()).optional("per_hour_week", num())
                        .optional("rating", num()).optional("score", num()).optional("volume_week", num())
                        .optional("hotm", text(64)).optional("basis", text(64)).optional("inputs", any()), 5_000)),
                sbs);
        Schema perks = array(any(), 32);
        MessageSchemas.register(MAYOR, object()
                .optional("active", object()
                        .optional("name", text(64)).optional("key", id(64)).optional("year", integer(0, 100_000))
                        .optional("since", epoch()).optional("perks", perks).optional("minister", any())
                        .optional("mayor_name", text(64)).optional("election_year", integer(0, 100_000))
                        .optional("minister_name", text(64)).optional("minister_perks", perks))
                .optional("election", object()
                        .optional("year", integer(0, 100_000)).optional("updated_ts", epoch())
                        .optional("candidates", array(object()
                                .optional("name", text(64)).optional("key", id(64))
                                .optional("votes", count()).optional("perks", perks), 16)))
                .optional("item", string(96, ITEM_ID))
                .optional("current_effect", mayorEffect)
                .optional("history", map(Pattern.compile("^[A-Za-z0-9_ .'-]*$"), 64, relayed()
                        .optional("available", bool()).optional("reason", text(256))
                        .optional("n_terms_recorded", count()), 64)), sbs);

        // ------------------------------------------------------------ price history / enchants
        MessageSchemas.register(PRICE_ITEM, object()
                .required("t", enumOf("bz", "ah"))
                .optional("i", string(96, ITEM_ID))
                .optional("g", count())
                .optional("h", array(numbers(16), 50_000))
                .optional("s", array(numbers(16), 50_000))
                .optional("lb", numbers(4)), sbs.withMaxBytes(8 * JsonLimits.MIB));
        MessageSchemas.register(PRICE_ITEMS, object()
                .required("items", array(array(string(96, ITEM_ID), 2), 50_000)),
                new JsonLimits(4 * JsonLimits.MIB, 8, 50_000, 64, 256, 250_000));
        MessageSchemas.register(ENCHANTS, object()
                .optional("i", string(96, ITEM_ID))
                .required("enchants", map(Pattern.compile("^[A-Za-z0-9_]+$"), 64, integer(0, 1_000), 1_000)), sbs);

        // Curated datasets (VersionedDataStore): each document type checks its own content with
        // valid() after the schemaVersion gate; here the structure and size are bounded.
        MessageSchemas.register(DATASET, relayed()
                .optional("schemaVersion", integer(0, 1_000_000))
                .optional("dataVersion", integer(0, Integer.MAX_VALUE)), sbs.withMaxBytes(4 * JsonLimits.MIB));
        MessageSchemas.register(HEALTH, object().optional("ok", bool()).optional("ts", num()), sbs);

        // ------------------------------------------------------------ carry
        Schema ticket = object()
                .required("id", id(64)).required("category", id(64)).required("tier", text(64))
                .required("note", text(200)).required("status", id(32)).required("creator_name", name())
                .optional("carrier_ign", name()).required("created_at", epoch()).optional("claimed_at", epoch())
                .required("mine", bool()).required("claimed_by_me", bool()).required("chat_seq", count());
        Schema roles = map(Schema.ID, 64, array(text(64), 64), 64);
        MessageSchemas.register(CARRY_META, object()
                .required("categories", map(Schema.ID, 64, object()
                        .required("label", text(64)).required("tiers", array(text(64), 64)), 64))
                .required("disclaimer", text(1_000))
                .required("owner", bool())
                .optional("carrier", object().required("ign", name()).required("roles", roles)), sbs);
        MessageSchemas.register(CARRY_TICKETS, object().required("tickets", array(ticket, 500)), sbs);
        MessageSchemas.register(CARRY_TICKET, object().optional("ok", bool()).required("ticket", ticket), sbs);
        MessageSchemas.register(CARRY_OK, object().required("ok", bool()), sbs);
        MessageSchemas.register(CARRY_CHAT_ACK, object().required("ok", bool()).optional("seq", count()), sbs);
        MessageSchemas.register(CARRY_POLL, object()
                .required("ticket", ticket)
                .required("messages", array(object()
                        .required("n", count()).required("at", epoch())
                        .required("from", enumOf("USER", "CARRIER", "OWNER"))
                        .required("tag", text(32)).required("verified", bool()).required("msg", text(300))
                        .optional("name", name()), 500)), sbs);
        MessageSchemas.register(CARRY_ADMIN_CARRIERS, object()
                .required("carriers", array(object()
                        .required("ign", name()).required("roles", roles).optional("added_at", epoch()), 1_000)), sbs);
        MessageSchemas.register(CARRY_ADMIN_SET, object()
                .required("ok", bool())
                .required("carrier", object().required("ign", name()).required("roles", roles)), sbs);

        // ------------------------------------------------------------ party finder
        Schema member = object().required("uuid", string(36, UUID)).required("name", name());
        Schema party = object()
                .required("id", id(64)).required("type", id(32)).required("size", integer(0, 100))
                .required("note", text(256)).optional("location", text(128)).optional("mob", text(128))
                .optional("mode", text(128)).required("leader", member)
                .required("requirements", array(smallMap(16), 50))
                .required("members", array(member, 100)).required("member_count", integer(0, 100))
                .required("updated", epoch()).required("chat_seq", count())
                .optional("you_meet", bool()).optional("req_detail", array(smallMap(16), 50));
        MessageSchemas.register(PARTY_LIST, object().required("parties", array(party, 500)), sbs);
        MessageSchemas.register(PARTY_SINGLE, object().optional("ok", bool()).required("party", party), sbs);
        MessageSchemas.register(PARTY_OK, object().required("ok", bool()), sbs);
        MessageSchemas.register(PARTY_CHAT_ACK, object().required("ok", bool()).optional("seq", count()), sbs);
        Schema partyLine = new Schema() {
            @Override
            void check(com.google.gson.JsonElement value, String path) throws ValidationException {
                array(any(), 4).check(value, path);
                var row = value.getAsJsonArray();
                if (row.size() != 4) {
                    throw new ValidationException(null, ValidationException.Kind.WRONG_TYPE, path);
                }
                count().check(row.get(0), path + "[0]");
                epoch().check(row.get(1), path + "[1]");
                name().check(row.get(2), path + "[2]");
                text(256).check(row.get(3), path + "[3]");
            }
        };
        MessageSchemas.register(PARTY_POLL, object()
                .optional("party", party.nullable())
                .required("messages", array(partyLine, 500)), sbs);

        // ------------------------------------------------------------ irc
        Schema ircLine = new Schema() {
            @Override
            void check(com.google.gson.JsonElement value, String path) throws ValidationException {
                array(any(), 6).check(value, path);
                var row = value.getAsJsonArray();
                if (row.size() < 4) {
                    throw new ValidationException(null, ValidationException.Kind.MISSING_FIELD, path);
                }
                count().check(row.get(0), path + "[0]");
                epoch().check(row.get(1), path + "[1]");
                name().check(row.get(2), path + "[2]");
                text(256).check(row.get(3), path + "[3]");
                if (row.size() > 4) {
                    text(32).nullable().check(row.get(4), path + "[4]");
                }
                if (row.size() > 5) {
                    Schema.string(0, 7, Pattern.compile("^(#?[0-9a-fA-F]{6})?$")).nullable().check(row.get(5), path + "[5]");
                }
            }
        };
        MessageSchemas.register(IRC_POLL, object()
                .optional("seq", count())
                .required("messages", array(ircLine, 500)), sbs);

        // Acknowledgements whose body is not read; registered for their size cap and schema.
        MessageSchemas.register(IRC_SEND_ACK, object().optional("ok", bool()).optional("seq", count())
                .optional("error", text(200)), sbs);
        MessageSchemas.register(PRESENCE_ACK, object().optional("sbs", array(string(36, UUID), 200))
                .optional("online", count()), sbs);
        MessageSchemas.register(PRIVACY_ACK, object().optional("ok", bool()).optional("error", text(200)), sbs);

        // ------------------------------------------------------------ hollows socket
        Schema point = array(integer(-1_000_000, 1_000_000), 3);
        Schema structureFields = object()
                .required("v", integer(1, 1)).required("type", enumOf("update"))
                .required("lobby", string(16, LOBBY))
                .required("structure", id(64))
                .required("x", integer(-1_000_000, 1_000_000)).required("y", integer(-1_000, 1_000))
                .required("z", integer(-1_000_000, 1_000_000))
                .required("bbox", object().required("min", point).required("max", point))
                .required("confirmations", integer(1, 100_000)).required("firstSeen", integer(0, 4_102_444_800_000L));
        Schema shared = object()
                .required("structure", id(64))
                .required("x", integer(-1_000_000, 1_000_000)).required("y", integer(-1_000, 1_000))
                .required("z", integer(-1_000_000, 1_000_000))
                .required("bbox", object().required("min", point).required("max", point))
                .required("confirmations", integer(1, 100_000)).required("firstSeen", integer(0, 4_102_444_800_000L));
        JsonLimits socket = new JsonLimits(64 * 1024, 8, 64, 32, 1_024, 2_000);
        MessageSchemas.register(HOLLOWS_SNAPSHOT, object()
                .required("v", integer(1, 1)).required("type", enumOf("snapshot"))
                .required("lobby", string(16, LOBBY))
                .required("structures", array(shared, 20)), socket);
        MessageSchemas.register(HOLLOWS_UPDATE, structureFields, socket);
        MessageSchemas.register(HOLLOWS_ERROR, object()
                .required("v", integer(1, 1)).required("type", enumOf("error"))
                .required("code", text(32)), socket);

        // ------------------------------------------------------------ relayed: Hypixel
        Schema summary = array(relayed().optional("pricePerUnit", num()).optional("orders", count())
                .optional("amount", count()), 1_000);
        MessageSchemas.register(HYPIXEL_BAZAAR, relayed()
                .required("success", bool())
                .optional("lastUpdated", epoch())
                .required("products", map(Pattern.compile("^[A-Za-z0-9_:;.\\-+]+$"), 128, relayed()
                        .optional("sell_summary", summary).optional("buy_summary", summary)
                        .optional("quick_status", relayed()
                                .optional("buyPrice", num()).optional("sellPrice", num())
                                .optional("buyVolume", count()).optional("sellVolume", count())
                                .optional("buyMovingWeek", count()).optional("sellMovingWeek", count())
                                .optional("buyOrders", count()).optional("sellOrders", count())), 20_000)),
                relay);
        MessageSchemas.register(HYPIXEL_AUCTIONS, relayed()
                .required("success", bool())
                .optional("page", integer(0, 100_000)).optional("totalPages", integer(0, 100_000))
                .required("auctions", array(relayed()
                        .optional("bin", bool()).optional("starting_bid", count())
                        .optional("item_name", text(256)).optional("uuid", string(36, UUID)).optional("end", epoch()),
                        5_000)), relay);
        MessageSchemas.register(HYPIXEL_AUCTIONS_ENDED, relayed()
                .required("success", bool())
                .required("auctions", array(relayed()
                        .optional("auction_id", string(36, UUID)).optional("seller", string(36, UUID))
                        .optional("buyer", string(36, UUID)).optional("price", count()).optional("bin", bool()),
                        50_000)), relay);
        MessageSchemas.register(HYPIXEL_ITEMS, relayed()
                .required("success", bool())
                .required("items", array(relayed()
                        .optional("id", string(1, 96, ITEM_ID)).optional("name", text(256))
                        .optional("material", id(96)).optional("durability", integer(0, 65_535))
                        .optional("tier", id(32)).optional("category", id(64)).optional("skin", any())
                        .optional("item_model", text(128)).optional("color", text(32)).optional("glowing", bool())
                        .optional("npc_sell_price", num()).optional("museum", bool())
                        .optional("museum_data", any()).optional("gemstone_slots", any()), 50_000)), relay);
        MessageSchemas.register(HYPIXEL_COLLECTIONS, relayed()
                .optional("collections", map(Schema.ID, 64, relayed()
                        .optional("items", map(Schema.ID, 96, relayed()
                                .optional("name", text(128))
                                .optional("tiers", array(relayed().optional("amountRequired", count()), 64)), 500)),
                        64)), relay);

        // ------------------------------------------------------------ relayed: Mojang / item repo
        // Player Viewer: only size and structure here (outer layer). The viewer's own parser reads the
        // fields it uses and validates them per section (inner layer), so no members are declared.
        // Strings allow the base64 inventory blobs (at most 1 MiB compressed -> ~1.4 MB text).
        JsonLimits pvLarge = new JsonLimits(8 * JsonLimits.MIB, 64, 50_000, 50_000, 1_500_000, 4_000_000);
        MessageSchemas.register(PV_PROFILES, relayed(), pvLarge);
        MessageSchemas.register(PV_PROFILE, relayed(), pvLarge);
        MessageSchemas.register(PV_MUSEUM, relayed(), pvLarge);
        MessageSchemas.register(PV_STATUS, relayed(), new JsonLimits(64 * 1024, 16, 256, 256, 4_096, 10_000));
        MessageSchemas.register(MOJANG_PROFILE, relayed().optional("name", string(0, 16, NAME)),
                new JsonLimits(256 * 1024, 16, 256, 256, 65_536, 10_000));
        MessageSchemas.register(REPO_ITEM, relayed().optional("lore", array(text(1_024), 256)),
                new JsonLimits(2 * JsonLimits.MIB, 32, 10_000, 10_000, 262_144, 100_000));
        // Read with a pattern over the raw text, not as JSON; registered for its size cap.
        MessageSchemas.register(REPO_PET_ITEM, any(), new JsonLimits(2 * JsonLimits.MIB, 32, 10_000, 10_000,
                262_144, 100_000));
        // A zip archive read as a stream; registered for its size cap.
        MessageSchemas.register(REPO_ARCHIVE, any(), new JsonLimits(256 * JsonLimits.MIB, 64, 1, 1, 1, 1));

        MessageSchemas.register(LOCAL_HIGHLIGHT, object().required("slots", array(integer(0, 1_000), 200)),
                new JsonLimits(64 * 1024, 4, 200, 4, 64, 500));

        // ------------------------------------------------------------ outbound
        MessageSchemas.register(OUT_PRIVACY_CONSENT, object()
                .required("accountId", text(128)).required("scopeId", id(64)).required("granted", bool())
                .required("timestamp", epoch()).required("disclosureVersion", any()).required("source", id(64))
                .optional("policyVersion", any()), sbs);
        MessageSchemas.register(OUT_PRIVACY_DELETE, object().required("scopeId", id(64)), sbs);
        MessageSchemas.register(OUT_SESSION_VERIFY, object()
                .required("username", string(3, 16, NAME))
                .required("serverId", string(40, 40, Pattern.compile("^[0-9a-f]{40}$"))), sbs);
        MessageSchemas.register(OUT_FREE_ACCESS_CLAIM, object()
                .required("username", string(3, 16, NAME))
                .required("serverId", string(40, 40, Pattern.compile("^[0-9a-f]{40}$"))), sbs);
        MessageSchemas.register(OUT_SOCKET_TICKET, object()
                .required("v", integer(1, 1)).required("scope", id(64)), sbs);
        MessageSchemas.register(OUT_APPRAISE, object()
                .required("item_id", string(1, 96, ITEM_ID)).required("attributes", any()), sbs);
        MessageSchemas.register(OUT_PRESENCE, object().required("uuid", string(36, UUID)), sbs);
        MessageSchemas.register(OUT_CARRY_CREATE, object()
                .required("category", id(64)).required("tier", text(64)).required("note", text(200)), sbs);
        MessageSchemas.register(OUT_CARRY_TICKET_ID, object().required("ticket_id", id(64)), sbs);
        MessageSchemas.register(OUT_CARRY_CHAT, object()
                .required("ticket_id", id(64)).required("msg", text(300)), sbs);
        MessageSchemas.register(OUT_CARRY_SET_CARRIER, object()
                .required("token", text(256)).required("ign", string(1, 16, NAME)).required("category", id(64))
                .required("tiers", any()), sbs);
        MessageSchemas.register(OUT_CARRY_REMOVE_CARRIER, object().required("ign", string(1, 16, NAME)), sbs);
        // Party, carry and IRC identify the sender by the X-SBS-Session header; a self-declared
        // name or uuid in the body is rejected by these fixed-field schemas.
        MessageSchemas.register(OUT_PARTY_CREATE, object()
                .optional("type", id(32)).optional("size", integer(0, 100)).optional("note", text(256))
                .optional("location", text(128)).optional("mob", text(128)).optional("mode", text(128))
                .optional("requirements", array(smallMap(16), 50)), sbs);
        MessageSchemas.register(OUT_PARTY_MEMBER, object()
                .required("party_id", id(64)), sbs);
        MessageSchemas.register(OUT_PARTY_KICK, object()
                .required("party_id", id(64)).required("target", string(36, UUID)), sbs);
        MessageSchemas.register(OUT_PARTY_CHAT, object()
                .required("party_id", id(64)).required("msg", text(256)), sbs);
        MessageSchemas.register(OUT_IRC_SEND, object().required("msg", text(256)), sbs);
        MessageSchemas.register(OUT_HOLLOWS_SUBSCRIBE, object()
                .required("v", integer(1, 1)).required("type", enumOf("subscribe"))
                .required("lobby", string(16, LOBBY)).required("island", enumOf("crystal_hollows"))
                .required("mod", string(1, 40, Pattern.compile("^[0-9A-Za-z.+-]+$"))), socket);
        MessageSchemas.register(OUT_HOLLOWS_REPORT, object()
                .required("v", integer(1, 1)).required("type", enumOf("report"))
                .required("lobby", string(16, LOBBY)).required("structure", id(64))
                .required("x", integer(-1_000_000, 1_000_000)).required("y", integer(-1_000, 1_000))
                .required("z", integer(-1_000_000, 1_000_000))
                .required("bbox", object().required("min", point).required("max", point))
                .required("samples", integer(1, 1_000_000)), socket);
        MessageSchemas.register(OUT_HOLLOWS_UNSUBSCRIBE, object()
                .required("v", integer(1, 1)).required("type", enumOf("unsubscribe"))
                .required("lobby", string(16, LOBBY)), socket);
    }
}
