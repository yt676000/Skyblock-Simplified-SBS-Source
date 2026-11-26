/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One flip as the local flip program reports it, validated and sanitised once on receipt.
 *
 * <p>The program omits fields it has no value for, so every optional number has a sentinel:
 * {@link #NONE} for whole numbers and {@link Double#NaN} for fractions. Text fields are display
 * text only: colour codes and control characters are removed and the length is clamped.
 *
 * <p>The command that opens the auction is never taken from the payload. The program does send a
 * ready {@code viewauction} string, but a data source must not be able to name a command (root
 * {@code AGENTS.md}, "Data files describe data, never behaviour"), so {@link #command()} is built
 * from the 32-hex {@link #auction} id alone, which {@link #parse} validates.
 */
public record LocalFlip(
        Kind kind,
        String name,
        String itemId,
        String dropClass,
        int count,
        long price,
        long maxBid,
        double endsMin,
        long profitNow,
        long target,
        long netProfit,
        double netPct,
        double pSuccess,
        double sellTimeH,
        long profitPerDay,
        long priceNormal,
        double timeNormalH,
        long pricePatient,
        double timePatientH,
        double salesPerDay,
        double ageMin,
        String seller,
        String note,
        String auction,
        double foundAt,
        double start,
        double latencyS,
        double botP,
        boolean auctionOnly,
        double ageRatio) {

    /** "Not sent" for whole-number fields. Every real value the program sends is far above it. */
    public static final long NONE = Long.MIN_VALUE;

    /** An auction id: 32 lowercase hex characters, no dashes, after normalisation. */
    private static final Pattern AUCTION_ID = Pattern.compile("[0-9a-f]{32}");

    /** Colour codes and anything that is not printable text. */
    private static final Pattern COLOUR = Pattern.compile("§.");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\p{Cf}]");

    private static final int NAME_MAX = 80;
    private static final int NOTE_MAX = 600;
    private static final int SHORT_MAX = 40;

    /** What kind of offer this is; drives the badge and which fields mean anything. */
    public enum Kind {
        /** Just listed and time-critical; arrives over the stream. */
        SOFORT("SOFORT"),
        /** Buy-it-now from the flip list. */
        BIN("BIN"),
        /** A bid auction: {@code price} is the current bid, {@code maxBid} the most worth bidding. */
        GEBOT("Gebot"),
        /** A kind this build does not know; shown, never filtered on. */
        OTHER("?");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static Kind of(String raw) {
            if (raw == null) {
                return OTHER;
            }
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "SOFORT" -> SOFORT;
                case "BIN" -> BIN;
                case "GEBOT" -> GEBOT;
                default -> OTHER;
            };
        }
    }

    /**
     * Reads one flip object. Returns {@code null} when the object has no valid auction id - an entry
     * that cannot be opened, deduplicated or marked gone is not an entry at all. Any other field that
     * is missing or of the wrong type falls back to its sentinel instead of failing the whole flip.
     */
    public static LocalFlip parse(JsonObject o) {
        if (o == null) {
            return null;
        }
        String auction = normaliseAuction(string(o, "auction"));
        if (auction == null) {
            return null;
        }
        String name = clean(string(o, "name"), NAME_MAX);
        return new LocalFlip(
                Kind.of(string(o, "kind")),
                name.isEmpty() ? "Unknown Item" : name,
                clean(string(o, "item_id"), SHORT_MAX),
                clean(string(o, "drop_class"), SHORT_MAX),
                (int) Math.max(1, Math.min(Integer.MAX_VALUE, whole(o, "count", 1))),
                whole(o, "price", NONE),
                whole(o, "max_bid", NONE),
                fraction(o, "ends_min"),
                whole(o, "profit_now", NONE),
                whole(o, "target", NONE),
                whole(o, "net_profit", NONE),
                fraction(o, "net_pct"),
                fraction(o, "p_success"),
                fraction(o, "sell_time_h"),
                whole(o, "profit_per_day", NONE),
                whole(o, "price_normal", NONE),
                fraction(o, "time_normal_h"),
                whole(o, "price_patient", NONE),
                fraction(o, "time_patient_h"),
                fraction(o, "sales_per_day"),
                fraction(o, "age_min"),
                clean(string(o, "seller"), SHORT_MAX),
                clean(string(o, "note"), NOTE_MAX),
                auction,
                fraction(o, "found_at"),
                fraction(o, "start"),
                fraction(o, "latency_s"),
                fraction(o, "bot_p"),
                flag(o, "auction_only"),
                fraction(o, "age_ratio"));
    }

    /**
     * Lower-cases and strips dashes; {@code null} unless the result is exactly 32 hex characters.
     * The {@code weg} event uses this too, so a dashed and an undashed id name the same auction.
     */
    public static String normaliseAuction(String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT).replace("-", "");
        return AUCTION_ID.matcher(id).matches() ? id : null;
    }

    /** The command that opens this auction, without the leading slash: {@code viewauction <uuid>}. */
    public String command() {
        return "viewauction " + dashed(auction);
    }

    /** {@code 32hex} to the dashed 8-4-4-4-12 form Hypixel prints and accepts. */
    static String dashed(String id) {
        return id.substring(0, 8) + '-' + id.substring(8, 12) + '-' + id.substring(12, 16) + '-'
                + id.substring(16, 20) + '-' + id.substring(20);
    }

    /** Safety as a whole percentage, or {@code -1} when the program sent none (bids never do). */
    public int safetyPct() {
        return Double.isNaN(pSuccess) ? -1 : (int) Math.round(Math.max(0, Math.min(1, pSuccess)) * 100);
    }

    public boolean has(long value) {
        return value != NONE;
    }

    /** The name with the stack size when the program did not already prefix it ("13x Item"). */
    public String displayName() {
        if (count > 1 && !name.startsWith(count + "x ")) {
            return count + "x " + name;
        }
        return name;
    }

    // ------------------------------------------------------------------
    // Field readers: tolerant of absence and of the wrong JSON type
    // ------------------------------------------------------------------

    /** True only for a JSON {@code true}; anything else (absent, wrong type) is false. */
    private static boolean flag(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    /** Bot share 0-100, or -1 when the server sent no {@code bot_p}. */
    public int botPct() {
        return Double.isNaN(botP) ? -1 : (int) Math.round(Math.max(0, Math.min(1, botP)) * 100);
    }

    /** {@code bot_p >= 0.65}: the server's "BOT PREY" threshold. */
    public boolean botPrey() {
        return !Double.isNaN(botP) && botP >= 0.65;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        return e.getAsString();
    }

    private static long whole(JsonObject o, String key, long fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        double value = e.getAsDouble();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.round(value);
    }

    private static double fraction(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return Double.NaN;
        }
        double value = e.getAsDouble();
        return Double.isInfinite(value) ? Double.NaN : value;
    }

    /** Display text: no colour codes, no control characters, trimmed and length-clamped. */
    static String clean(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String text = CONTROL.matcher(COLOUR.matcher(raw).replaceAll("")).replaceAll("").trim();
        return text.length() > max ? text.substring(0, max) : text;
    }
}
