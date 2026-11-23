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
 * One Lowball answer, parsed into the text the window shows. Every value comes from the program;
 * this only words it. Every field may be missing or {@code null} - a missing field is a hidden row,
 * never the word "null" and never an exception. Pure (no game classes), so it is unit-tested.
 */
public final class LowballView {

    /** One sell tier row: Fast / Normal / Patient. */
    public record Tier(String label, String price, String time) {
    }

    /** A boxed warning (INFLATED, MAYOR) with its lines. */
    public record Box(String title, List<String> lines, boolean orange) {
    }

    /**
     * One row of the offer table: {@code -10 % | 818m | +82.3m | 17 % | 94 %}. {@code profit} is the
     * program's {@code net_profit}, already after AH tax. Missing cells are {@code "-"}.
     */
    public record OfferRow(String copyText, double discount, String discountText, String price, String profit,
                           boolean loss, String margin, String safety, boolean closest) {
    }

    /**
     * The program's recommended offer. {@code copyText == null} means "no offer recommended" (no
     * market, dupe); {@code why} explains either way.
     */
    public record Recommended(String copyText, double discount, String label, List<String> why) {
    }

    /** Everything about one item. */
    public record Item(String ref, boolean ok, String name, int count, String error,
                       String confidenceText, String confidenceLevel, String confidenceHover,
                       List<Box> boxes, List<String> warnings, List<Tier> tiers, List<String> market,
                       List<OfferRow> offers, Recommended recommended, boolean valueOnly, String fastText,
                       boolean warned, List<Similar> similar, String dyeText) {
    }

    /** One {@code closest[].diff} entry: type, key, counter/cost values and coin weight. */
    public record Diff(String type, String key, double you, double they, double w) {
    }

    /**
     * One similar auction from {@code closest[]}: sold in the last 14 days or live on the AH. Texts
     * are ready to draw; {@code auctionId} is set only for a live row (it opens on click).
     */
    public record Similar(boolean live, String label, boolean sameItem, String priceText, int match,
                          double adj, String adjText, String estText, String auctionId,
                          List<Diff> youHave, List<Diff> theyHave) {
    }

    /** At most this many similar auctions are shown (the server sends up to 8). */
    static final int MAX_SIMILAR = 8;

    private final List<Item> items;
    private final List<String> totalOffers;
    private final String dump;
    private final long dataTimeMs;
    private final String partner;
    private final String source;

    private LowballView(List<Item> items, List<String> totalOffers, String dump, long dataTimeMs,
                        String partner, String source) {
        this.items = items;
        this.totalOffers = totalOffers;
        this.dump = dump;
        this.dataTimeMs = dataTimeMs;
        this.partner = partner;
        this.source = source;
    }

    public List<Item> items() {
        return items;
    }

    /** The summary offers' texts, for "Offer for all"; empty when there is no summary. */
    public List<String> totalOffers() {
        return totalOffers;
    }

    public String partner() {
        return partner;
    }

    public boolean isTrade() {
        return "trade".equals(source);
    }

    /** {@code "data: <dump>, <age> s ago"}, or {@code null} when the answer named no dump/time. */
    public String footer(long nowMs) {
        if (dump == null && dataTimeMs <= 0) {
            return null;
        }
        StringBuilder text = new StringBuilder("data: ");
        text.append(dump != null ? dump : "?");
        if (dataTimeMs > 0) {
            text.append(", ").append(Math.max(0, (nowMs - dataTimeMs) / 1000)).append(" s ago");
        }
        return text.toString();
    }

    public static LowballView parse(JsonObject answer, String source, String partner) {
        List<Item> items = new ArrayList<>();
        if (answer.get("results") instanceof JsonArray results) {
            for (JsonElement e : results) {
                if (e instanceof JsonObject r) {
                    items.add(item(r));
                }
            }
        }
        List<String> totals = new ArrayList<>();
        if (answer.get("summary") instanceof JsonObject summary) {
            for (LowballFormat.Offer offer : LowballFormat.offers(summary.get("offers"))) {
                totals.add(offer.text());
            }
        }
        String dump = LowballFormat.str(answer, "dump");
        long t = 0;
        if (answer.has("t") && answer.get("t").isJsonPrimitive()) {
            double raw = LowballFormat.num(answer, "t");
            t = raw > 1e12 ? (long) raw : (long) (raw * 1000);   // seconds or milliseconds
        }
        return new LowballView(List.copyOf(items), List.copyOf(totals), dump, t, partner, source);
    }

    static Item item(JsonObject r) {
        LowballFormat.Result base = LowballFormat.parse(r);
        int count = (int) LowballFormat.num(r, "count");
        if (!base.ok()) {
            return new Item(base.ref(), false, base.name(), count, base.error(), null, null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(), null, true, null, false, List.of(), null);
        }

        // Confidence badge.
        String confText = null;
        String confLevel = null;
        String confHover = null;
        if (r.get("confidence") instanceof JsonObject c) {
            confLevel = lower(LowballFormat.str(c, "level"));
            if (confLevel != null || c.has("score")) {
                StringBuilder text = new StringBuilder(confLevel != null ? confLevel.toUpperCase(Locale.ROOT) : "?");
                if (has(c, "score")) {
                    text.append(' ').append(Math.round(LowballFormat.num(c, "score") * 100)).append('%');
                }
                confText = text.toString();
            }
            List<String> hover = new ArrayList<>();
            if (has(c, "spread_pct")) {
                hover.add(String.format(Locale.ROOT, "spread %.1f %%", LowballFormat.num(c, "spread_pct")));
            }
            if (has(c, "comps")) {
                hover.add((long) LowballFormat.num(c, "comps") + " comps");
            }
            confHover = hover.isEmpty() ? null : String.join(", ", hover);
        }

        // Boxes, then the remaining warnings.
        List<Box> boxes = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        if (r.get("inflated") instanceof JsonObject inflated && bool(inflated, "warn")) {
            List<String> notes = strings(inflated.get("notes"));
            boxes.add(new Box("INFLATED", notes, true));
            shown.addAll(notes);
        }
        JsonObject mayor = r.get("mayor") instanceof JsonObject m ? m : null;
        if (mayor != null && bool(mayor, "warn")) {
            String current = LowballFormat.str(mayor, "current");
            List<String> lines = new ArrayList<>();
            String note = LowballFormat.str(mayor, "note");
            if (note != null && !note.isBlank()) {
                lines.add(note);
                shown.add(note);
            }
            if (has(mayor, "price_move_pct") && LowballFormat.num(mayor, "price_move_pct") <= -1) {
                lines.add(String.format(Locale.ROOT, "price expected -%.0f %% until sold",
                        Math.abs(LowballFormat.num(mayor, "price_move_pct"))));
            }
            if (bool(mayor, "pet_xp_glut")) {
                lines.add("pets being mass-leveled");
            }
            boxes.add(new Box("MAYOR: " + (current != null ? current : "?"), lines, false));
        }
        List<String> warnings = new ArrayList<>();
        String dyeText = dye(r.get("dye"));
        for (String w : base.warnings()) {
            // The dye warning repeats the dedicated Dye line; drop it once that line is there.
            boolean dyeDup = dyeText != null && w.strip().toLowerCase(Locale.ROOT).startsWith("dye:");
            if (!shown.contains(w) && !w.isBlank() && !dyeDup) {
                warnings.add(w);
            }
        }

        // Sell tiers, in fixed order.
        List<Tier> tiers = new ArrayList<>();
        String fastText = null;
        for (String tier : List.of("fast", "normal", "patient")) {
            JsonObject p = tier(r.get("prices"), tier);
            if (p == null) {
                continue;
            }
            String text = LowballFormat.str(p, "text");
            if (text == null || text.isBlank()) {
                if (!has(p, "price")) {
                    continue;
                }
                text = LowballFormat.coins(LowballFormat.num(p, "price"));
            }
            text = text.replace(',', '.');
            if (tier.equals("fast")) {
                fastText = text;
            }
            tiers.add(new Tier(Character.toUpperCase(tier.charAt(0)) + tier.substring(1), text,
                    has(p, "time_h") ? hours(LowballFormat.num(p, "time_h")) : "?"));
        }
        if (fastText == null && (has(r, "target") || has(r, "target_text"))) {
            fastText = base.targetText();
        }

        // Market line(s).
        List<String> market = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        if (has(r, "lbin")) {
            parts.add("LBIN " + LowballFormat.coins(LowballFormat.num(r, "lbin")));
        }
        if (has(r, "sales_per_day")) {
            parts.add(String.format(Locale.ROOT, "%.1f sales/day", LowballFormat.num(r, "sales_per_day")));
        }
        if (r.get("comps") instanceof JsonObject comps) {
            List<String> c = new ArrayList<>();
            if (has(comps, "n")) {
                c.add((long) LowballFormat.num(comps, "n") + " comps");
            }
            if (has(comps, "identical")) {
                c.add((long) LowballFormat.num(comps, "identical") + " identical");
            }
            if (has(comps, "median")) {
                c.add("median " + LowballFormat.coins(LowballFormat.num(comps, "median")));
            }
            if (!c.isEmpty()) {
                parts.add(String.join(", ", c));
            }
        }
        if (!parts.isEmpty()) {
            market.add(String.join(" · ", parts));
        }
        if (mayor != null) {
            String current = LowballFormat.str(mayor, "current");
            if (current != null && !current.isBlank()) {
                StringBuilder line = new StringBuilder("Mayor ").append(current);
                if (has(mayor, "sell_speed_f")) {
                    double f = LowballFormat.num(mayor, "sell_speed_f");
                    long pct = Math.round(Math.abs(1 - f) * 100);
                    if (f < 1 && pct > 0) {
                        line.append(": sells ").append(pct).append(" % faster");
                    } else if (f > 1 && pct > 0) {
                        line.append(": sells ").append(pct).append(" % slower");
                    }
                }
                market.add(line.toString());
            }
        }

        // Recommended offer, then the table with the row nearest to it marked.
        Recommended recommended = r.get("recommended") instanceof JsonObject rec ? recommended(rec) : null;
        List<OfferRow> offers = new ArrayList<>();
        if (!base.valueOnly() && r.get("offers") instanceof JsonArray array) {
            int closest = -1;
            double best = Double.MAX_VALUE;
            List<JsonObject> raw = new ArrayList<>();
            for (JsonElement e : array) {
                if (e instanceof JsonObject o) {
                    raw.add(o);
                    if (recommended != null && recommended.copyText() != null && has(o, "discount")) {
                        double d = Math.abs(LowballFormat.num(o, "discount") - recommended.discount());
                        if (d < best) {
                            best = d;
                            closest = raw.size() - 1;
                        }
                    }
                }
            }
            for (int k = 0; k < raw.size(); k++) {
                offers.add(offerRow(raw.get(k), k == closest));
            }
        }
        boolean warned = !boxes.isEmpty() || !warnings.isEmpty();
        return new Item(base.ref(), true, base.name(), count, null, confText, confLevel, confHover,
                List.copyOf(boxes), List.copyOf(warnings), List.copyOf(tiers), List.copyOf(market),
                List.copyOf(offers), recommended, base.valueOnly(), fastText, warned, similar(r.get("closest")), dyeText);
    }

    /** {@code closest[]} → rows; missing / malformed (old server) gives an empty list, never an error. */
    static List<Similar> similar(JsonElement closest) {
        if (!(closest instanceof JsonArray array)) {
            return List.of();
        }
        List<Similar> out = new ArrayList<>();
        for (JsonElement e : array) {
            if (out.size() >= MAX_SIMILAR) {
                break;
            }
            if (!(e instanceof JsonObject o)) {
                continue;
            }
            double price = LowballFormat.num(o, "price");
            if (price <= 0) {
                continue;
            }
            boolean live = "live".equals(LowballFormat.str(o, "source"));
            boolean same = !live && bool(o, "same_item");
            String label = live ? "LIVE"
                    : same ? "SOLD (this item!)"
                    : has(o, "age_h") ? "SOLD " + age(LowballFormat.num(o, "age_h")) : "SOLD";
            String priceText = text(o, "price_text", price);
            double adj = LowballFormat.num(o, "adj");
            String adjText = has(o, "adj") ? (adj >= 0 ? "+" : "") + LowballFormat.coins(adj) : null;
            double est = has(o, "est") ? LowballFormat.num(o, "est") : price + adj;
            String estText = has(o, "est_text") || has(o, "est") || has(o, "adj") ? text(o, "est_text", est) : null;
            List<Diff> you = List.of();
            List<Diff> they = List.of();
            if (o.get("diff") instanceof JsonObject diff) {
                you = diffs(diff.get("you_have"));
                they = diffs(diff.get("they_have"));
            }
            out.add(new Similar(live, label, same, priceText, (int) Math.round(LowballFormat.num(o, "match")),
                    adj, adjText, estText, live ? LowballFormat.str(o, "auction_id") : null, you, they));
        }
        return List.copyOf(out);
    }

    private static List<Diff> diffs(JsonElement side) {
        if (!(side instanceof JsonArray array)) {
            return List.of();
        }
        List<Diff> out = new ArrayList<>();
        for (JsonElement e : array) {
            if (e instanceof JsonObject d) {
                out.add(new Diff(LowballFormat.str(d, "t"), LowballFormat.str(d, "k"), LowballFormat.num(d, "you"),
                        LowballFormat.num(d, "they"), LowballFormat.num(d, "w")));
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@code dye} object → {@code "Dye: with 80.2m (~12 h) | without 76.1m (~5 h) | dye applied 4.1m"};
     * {@code null} / absent (undyed item, old server) gives {@code null}.
     */
    static String dye(JsonElement e) {
        if (!(e instanceof JsonObject d)) {
            return null;
        }
        return "Dye: with " + text(d, "with_dye_text", LowballFormat.num(d, "with_dye"))
                + " (~" + hours(d, "time_with_h") + " h)"
                + " | without " + text(d, "without_dye_text", LowballFormat.num(d, "without_dye"))
                + " (~" + hours(d, "time_without_h") + " h)"
                + " | dye applied " + text(d, "applied_text", LowballFormat.num(d, "applied_value"));
    }

    private static String hours(JsonObject o, String key) {
        if (!has(o, key)) {
            return "?";
        }
        double h = LowballFormat.num(o, key);
        return h >= 10 ? String.valueOf(Math.round(h)) : String.format(Locale.ROOT, "%.1f", h);
    }

    /** The server's own text when it sent one, else the same k/m/b format. */
    private static String text(JsonObject o, String key, double fallback) {
        String t = LowballFormat.str(o, key);
        return t != null && !t.isBlank() ? t : LowballFormat.coins(fallback);
    }

    /** {@code 0.4 -> "<1h ago"}, {@code 5.2 -> "5h ago"}, {@code 50 -> "2d ago"}. */
    static String age(double hours) {
        if (hours < 1) {
            return "<1h ago";
        }
        if (hours < 48) {
            return Math.round(Math.floor(hours)) + "h ago";
        }
        return Math.round(Math.floor(hours / 24)) + "d ago";
    }

    static OfferRow offerRow(JsonObject o, boolean closest) {
        double discount = has(o, "discount") ? LowballFormat.num(o, "discount") : Double.NaN;
        String discountText = Double.isNaN(discount) ? "-" : "-" + pct(discount * 100) + " %";
        String profit = "-";
        boolean loss = false;
        if (has(o, "net_profit")) {
            double value = LowballFormat.num(o, "net_profit");
            loss = value < 0;
            profit = (value >= 0 ? "+" : "") + LowballFormat.coinsFine(value);
        }
        String margin = has(o, "net_pct") ? Math.round(LowballFormat.num(o, "net_pct")) + " %" : "-";
        String safety = has(o, "p_success") ? Math.round(LowballFormat.num(o, "p_success") * 100) + " %" : "-";
        return new OfferRow(LowballFormat.offerText(o), discount, discountText, LowballFormat.offerText(o),
                profit, loss, margin, safety, closest);
    }

    /** {@code "Recommended: 818m (-17.5 %) → +139.9m after tax (17 %), safety 94 %"}. */
    static Recommended recommended(JsonObject rec) {
        List<String> why = strings(rec.get("why"));
        if (!has(rec, "discount")) {
            return new Recommended(null, Double.NaN, "No offer recommended", why);
        }
        double discount = LowballFormat.num(rec, "discount");
        String text = LowballFormat.offerText(rec);
        StringBuilder label = new StringBuilder("Recommended: ").append(text)
                .append(" (-").append(pct(discount * 100)).append(" %)");
        if (has(rec, "net_profit")) {
            double profit = LowballFormat.num(rec, "net_profit");
            label.append(" → ").append(profit >= 0 ? "+" : "").append(LowballFormat.coinsFine(profit))
                    .append(" after tax");
            if (has(rec, "net_pct")) {
                label.append(" (").append(Math.round(LowballFormat.num(rec, "net_pct"))).append(" %)");
            }
        }
        if (has(rec, "p_success")) {
            label.append(", safety ").append(Math.round(LowballFormat.num(rec, "p_success") * 100)).append(" %");
        }
        return new Recommended(text, discount, label.toString(), why);
    }

    /** {@code 17.5 -> "17.5"}, {@code 10.0 -> "10"}. */
    static String pct(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded)
                : String.format(Locale.ROOT, "%.1f", rounded);
    }

    /** {@code 1.6 -> "1.6 h"}, {@code 0.8 -> "<1 h"}. */
    static String hours(double h) {
        return h < 1 ? "<1 h" : String.format(Locale.ROOT, "%.1f h", h);
    }

    private static JsonObject tier(JsonElement prices, String tier) {
        if (prices instanceof JsonArray array) {
            for (JsonElement e : array) {
                if (e instanceof JsonObject p && tier.equalsIgnoreCase(LowballFormat.str(p, "tier"))) {
                    return p;
                }
            }
        }
        return null;
    }

    private static boolean has(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return false;
        }
        try {
            e.getAsDouble();
            return true;
        } catch (NumberFormatException notNumber) {
            return !e.getAsString().isBlank();
        }
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e instanceof JsonArray array) {
            for (JsonElement s : array) {
                if (s.isJsonPrimitive() && !s.getAsString().isBlank()) {
                    out.add(s.getAsString());
                }
            }
        }
        return out;
    }

    private static String lower(String s) {
        return s == null || s.isBlank() ? null : s.toLowerCase(Locale.ROOT);
    }
}
