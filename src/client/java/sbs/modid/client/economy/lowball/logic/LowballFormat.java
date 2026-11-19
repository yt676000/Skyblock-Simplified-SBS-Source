/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.lowball.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure text for the Lowball answer: numbers and the pieces of one result. No game classes, so it is
 * unit-tested. Every number is written with a dot decimal - Hypixel reads "5,96m" as something else,
 * and an offer the player pastes into a /msg must mean what it says.
 */
public final class LowballFormat {

    private LowballFormat() {
    }

    /** {@code 153202 -> "153k"}, {@code 5_960_000 -> "5.96m"}, {@code 950 -> "950"}. Dot decimal. */
    public static String coins(double value) {
        double abs = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        String[] units = {"", "k", "m", "b"};
        int unit = 0;
        while (abs >= 1000 && unit < units.length - 1) {
            abs /= 1000;
            unit++;
        }
        if (unit == 0) {
            return sign + Math.round(abs);
        }
        int decimals = abs >= 100 ? 0 : abs >= 10 ? 1 : 2;
        String text = String.format(Locale.ROOT, "%." + decimals + "f", abs);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return sign + text + units[unit];
    }

    /**
     * Like {@link #coins} but one decimal more for 100+ of a unit: {@code 139_874_501 -> "139.9m"},
     * {@code 82_300_000 -> "82.3m"}. For profits, where 140m vs 139.9m is worth the extra digit.
     */
    public static String coinsFine(double value) {
        double abs = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        String[] units = {"", "k", "m", "b"};
        int unit = 0;
        while (abs >= 1000 && unit < units.length - 1) {
            abs /= 1000;
            unit++;
        }
        if (unit == 0) {
            return sign + Math.round(abs);
        }
        String text = String.format(Locale.ROOT, abs >= 10 ? "%.1f" : "%.2f", abs);
        text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return sign + text + units[unit];
    }

    /** {@code 0.2 -> "-20%"}. */
    public static String discount(double fraction) {
        return "-" + Math.round(fraction * 100) + "%";
    }

    /** The server's text for an offer, or our own formatting when it sent none. */
    public static String offerText(JsonObject offer) {
        String text = str(offer, "offer_text");
        if (text == null || text.isBlank()) {
            text = coins(num(offer, "offer"));
        }
        return text.replace(',', '.');
    }

    /** One offer, parsed. */
    public record Offer(double discount, String text, double netProfit) {
    }

    /** One result, parsed into what the display needs. */
    public record Result(String ref, boolean ok, String name, String error, String targetText,
                         double sellTimeH, double salesPerDay, List<String> warnings, List<Offer> offers,
                         boolean valueOnly, String recommended) {
    }

    public static Result parse(JsonObject r) {
        String ref = str(r, "ref");
        boolean ok = r.has("ok") && r.get("ok").isJsonPrimitive() && r.get("ok").getAsBoolean();
        String name = str(r, "name");
        if (!ok) {
            String error = str(r, "error");
            return new Result(ref, false, name == null ? "?" : name, error == null ? "no value" : error,
                    null, -1, -1, List.of(), List.of(), true, null);
        }
        String target = str(r, "target_text");
        if (target == null || target.isBlank()) {
            target = coins(num(r, "target"));
        }
        List<String> warnings = new ArrayList<>();
        boolean valueOnly = false;
        if (r.get("warnings") instanceof JsonArray array) {
            for (JsonElement w : array) {
                if (w.isJsonPrimitive()) {
                    String text = w.getAsString();
                    warnings.add(text);
                    String upper = text.toUpperCase(Locale.ROOT);
                    if (upper.startsWith("DUPE") || upper.startsWith("NO MARKET")) {
                        valueOnly = true;
                    }
                }
            }
        }
        return new Result(ref, true, name == null ? "?" : name, null, target.replace(',', '.'),
                r.has("sell_time_h") ? num(r, "sell_time_h") : -1,
                r.has("sales_per_day") ? num(r, "sales_per_day") : -1,
                warnings, valueOnly ? List.of() : offers(r.get("offers")), valueOnly,
                r.get("recommended") instanceof JsonObject rec ? LowballView.recommended(rec).label() : null);
    }

    public static List<Offer> offers(JsonElement element) {
        List<Offer> out = new ArrayList<>();
        if (element instanceof JsonArray array) {
            for (JsonElement e : array) {
                if (e instanceof JsonObject o) {
                    out.add(new Offer(num(o, "discount"), offerText(o),
                            o.has("net_profit") ? num(o, "net_profit") : Double.NaN));
                }
            }
        }
        return out;
    }

    /** {@code "Sells ~805.25k (1.6 h, 31/day)"}. */
    public static String sells(Result r) {
        StringBuilder text = new StringBuilder("Sells ~").append(r.targetText());
        List<String> extra = new ArrayList<>();
        if (r.sellTimeH() >= 0) {
            extra.add(String.format(Locale.ROOT, "%.1f h", r.sellTimeH()));
        }
        if (r.salesPerDay() >= 0) {
            extra.add(Math.round(Math.floor(r.salesPerDay())) + "/day");
        }
        if (!extra.isEmpty()) {
            text.append(" (").append(String.join(", ", extra)).append(')');
        }
        return text.toString();
    }

    /** {@code "+153k / +194k"}, or {@code null} when no offer carries a profit. */
    public static String profits(List<Offer> offers) {
        List<String> parts = new ArrayList<>();
        for (Offer offer : offers) {
            if (!Double.isNaN(offer.netProfit())) {
                parts.add((offer.netProfit() >= 0 ? "+" : "") + coins(offer.netProfit()));
            }
        }
        return parts.isEmpty() ? null : String.join(" / ", parts);
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static double num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : 0;
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
