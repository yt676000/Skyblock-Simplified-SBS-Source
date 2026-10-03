/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.economy.auctions.logic.AppraiseApi;
import sbs.modid.client.economy.auctions.logic.PlayerNameCache;
import sbs.modid.client.ui.window.FloatingWindows;
import sbs.modid.client.economy.prices.LiveAuctionIndex;
import sbs.modid.client.ui.render.LocalRankingNotice;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.render.SourceSwitch;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.economy.pricehistory.logic.PriceLookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Similar Auctions window: a movable, resizable SBS table (same window mechanics as the Item
 * Value overlay, part of the {@link FloatingWindows} z-order) fed by the cloud appraise API.
 * Shows, for the hovered item's exact variant:
 * <ul>
 *   <li>the estimated sell value + lowest BIN,</li>
 *   <li><b>live</b> identical/similar auctions – click opens the auction in-game
 *       ({@code /viewauction <id>}), right-click opens the seller's AH ({@code /ah <name>});
 *       seller UUIDs resolve to names asynchronously via {@link PlayerNameCache},</li>
 *   <li>the <b>sale history</b> per match tier (identical / similar / base) with median/avg/range
 *       and the median time-to-sell,</li>
 *   <li>the most recent individual sales.</li>
 * </ul>
 * All matching happens server-side; this class only renders the response.
 */
public final class SimilarAuctionsOverlay {

    private static final SimilarAuctionsOverlay INSTANCE = new SimilarAuctionsOverlay();

    private static final int HEADER_H = 16;
    private static final int ROW_H = 11;
    private static final int PAD = 6;
    private static final int MARGIN = 2;
    private static final int MIN_W = 300;
    private static final int MAX_W = 820;
    private static final int MIN_H = 140;
    private static final int MAX_H = 520;
    private static final int MAX_LIVE_ROWS = 10;
    private static final int MAX_RECENT_ROWS = 12;

    /** Below this panel width the wider million texts ("1523M") stop fitting comfortably next to
     *  the seller/diff columns, so {@link #fmt} collapses billion values to "1.5B". */
    private static final int COMPACT_PRICES_W = 400;
    /** Width-aware price mode, set from the current panel width each frame before lines build
     *  (static because {@link #fmt} is used from static line-building helpers; single render
     *  thread). */
    private static boolean compactPrices;

    /** One live auction from {@code live_cheapest} / a live {@code closest} entry.
     *  {@code match} is -1 when the server did not send one; {@code adj} (signed coin delta of
     *  YOUR item vs this listing) only arrives on closest entries. */
    private record LiveAuction(String id, double unit, int count, String sellerUuid, long endAt, int match,
                               Double adj) {
    }

    /** A closest sale-comparison header, composed per frame so the async seller name pops in.
     *  {@code ts} is the sale time; 0 renders no "sold ... ago" (never epoch-0). */
    private record SaleRef(String sellerUuid, int match, int count, long ts, Double adj) {
    }

    /** One rendered table line; {@code auction} non-null makes it clickable, {@code sale}
     *  non-null composes a closest-sale header at draw time. */
    private record Line(String left, String right, int leftColor, int rightColor, LiveAuction auction,
                        SaleRef sale) {
        Line(String left, String right, int leftColor, int rightColor, LiveAuction auction) {
            this(left, right, leftColor, rightColor, auction, null);
        }
    }

    private boolean open;
    private String title = "";

    /** Response state (written from the API thread, read while rendering). */
    private volatile AppraiseApi.Response data;
    private volatile List<LiveAuction> liveAuctions = List.of();

    /** True while the window is showing live listings worked out here instead of an appraisal. */
    private volatile boolean localMode;

    /** Top Y of the first row, saved each frame so a click maps back to the row under it. */
    private int rowsTop;

    /** The last thing asked about, so flipping the source switch can re-answer it in place. */
    private String lastItemId;
    private JsonObject lastAttributes;
    private String lastName;
    private volatile String error;
    private volatile boolean loading;
    private int requestId;

    /** Lines cache, rebuilt when the data reference changes (auction names resolve at draw time). */
    private List<Line> lines = List.of();
    private Object builtFor;

    private int posX = Integer.MIN_VALUE;
    private int posY = Integer.MIN_VALUE;
    private int sizeW = 460;
    private int sizeH = 260;
    private int panelW = sizeW;
    private int panelH = sizeH;

    private boolean dragging;
    private double grabDX;
    private double grabDY;

    /** Shared edge/corner resize mechanics (identical to the Price History window). */
    private final sbs.modid.client.ui.window.WindowResizer resizer = new sbs.modid.client.ui.window.WindowResizer();

    /**
     * Where the player left this window. Only the geometry: which auction it was listing is not
     * worth restoring, since the window opens on the item you just looked at.
     */
    private final sbs.modid.client.ui.window.WindowMemory memory =
            new sbs.modid.client.ui.window.WindowMemory(
                    sbs.modid.client.ui.window.FloatingWindows.Layer.AUCTIONS);

    private int scrollRow;

    private SimilarAuctionsOverlay() {
    }

    public static SimilarAuctionsOverlay getInstance() {
        return INSTANCE;
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
        dragging = false;
        resizer.end();
    }

    // ------------------------------------------------------------------
    // Opening / API round trip
    // ------------------------------------------------------------------

    /** Matches the pet level in a hover name like {@code "[Lvl 87] Ender Dragon"}. */
    private static final java.util.regex.Pattern PET_LEVEL_IN_NAME =
            java.util.regex.Pattern.compile("\\[Lvl (\\d+)\\]");

    /** Appraises a hovered live stack: its ExtraAttributes go to the server 1:1. */
    public void openFor(ItemStack stack) {
        List<String> candidates = PriceLookup.candidatesFor(stack);
        if (candidates.isEmpty()) {
            return;
        }
        String name = stack.getHoverName().getString()
                .replaceAll(String.valueOf((char) 0x00A7) + ".", "").trim();
        JsonObject attributes = AppraiseApi.attributesJson(SkyblockItem.extraAttributes(stack));
        // Pets: the level lives only in the display name – the server can otherwise
        // just derive it from petInfo.exp, and the shown name is the exact truth.
        if (candidates.get(0).startsWith("PET_")) {
            java.util.regex.Matcher lvl = PET_LEVEL_IN_NAME.matcher(name);
            if (lvl.find()) {
                attributes.addProperty("petLevel", Integer.parseInt(lvl.group(1)));
            }
        }
        request(candidates.get(0), attributes, name);
    }

    /** Appraises a catalogue-only item (Recipe Viewer panel – no NBT, base variant). */
    public void openPlain(List<String> candidates, String name) {
        if (candidates != null && !candidates.isEmpty()) {
            request(candidates.get(0), new JsonObject(), name);
        }
    }

    private void request(String itemId, JsonObject attributes, String displayName) {
        title = "Auctions: " + displayName;
        data = null;
        error = null;
        loading = true;
        scrollRow = 0;
        open = true;
        lastItemId = itemId;
        lastAttributes = attributes;
        lastName = displayName;
        FloatingWindows.raise(FloatingWindows.Layer.AUCTIONS);
        int id = ++requestId;
        // The local view answers a smaller question and answers it instantly, so it does not go
        // through the request-id / callback machinery at all - there is nothing in flight to race.
        if (sbs.modid.client.core.api.RankingSource.useLocal(cfg().similarSource, "Similar")) {
            localMode = true;
            liveAuctions = localListings(displayName);
            loading = false;
            return;
        }
        localMode = false;
        AppraiseApi.getInstance().appraise(itemId, attributes, (result, apiError) -> {
            if (id != requestId) {
                return; // a newer check replaced this one
            }
            if (result != null) {
                liveAuctions = parseLive(result);
                data = result;
            } else {
                error = apiError;
            }
            loading = false;
        });
    }

    private static sbs.modid.client.core.config.SBSConfig.AhFlipAlertSettings cfg() {
        return sbs.modid.client.core.config.ConfigManager.getInstance().get().ahFlips;
    }

    /**
     * Re-asks the current question against whichever source is selected now. Used by the switch, so
     * flipping it answers in place instead of making the player close and re-hover the item.
     */
    private void reopenCurrent() {
        if (lastItemId != null) {
            request(lastItemId, lastAttributes == null ? new JsonObject() : lastAttributes, lastName);
        }
    }

    /**
     * The cheapest live listings of an item, straight out of the index the auction crawl builds.
     *
     * <p>Keyed on the <b>display name</b>, exactly as {@link sbs.modid.client.economy.prices.LbinCache}
     * keys the lowest-BIN map: the auction payload item name is what both sides normalise, so a
     * hovered stack and a listing meet on the same string. Keying on the SkyBlock id instead would
     * silently miss every custom-named and variant item.
     *
     * <p>This is the whole of the local view: what is on sale, for how much, ending when. No value,
     * no match percentage, no comparison of this item's modifiers against a listing's - none of that
     * can be had from live listings alone, and a number invented to fill the column would be worse
     * than the empty column.
     */
    private static List<LiveAuction> localListings(String displayName) {
        LiveAuctionIndex.Entry entry =
                LiveAuctionIndex.getInstance().get(SkyblockItem.normalizeName(displayName));
        if (entry == null) {
            return List.of();
        }
        List<LiveAuction> live = new ArrayList<>();
        for (LiveAuctionIndex.Listing listing : entry.cheapest()) {
            if (live.size() >= MAX_LIVE_ROWS) {
                break;
            }
            // endAt is SECONDS everywhere in this window; the index stores millis.
            live.add(new LiveAuction(listing.auctionId(), listing.price(), 1, null,
                    listing.endAtMs() / 1000L, -1, null));
        }
        return live;
    }

    /** The local view's rows: the listings, and a plain statement of what is missing from them. */
    private List<Line> localLines() {
        List<Line> built = new ArrayList<>();
        if (!LiveAuctionIndex.getInstance().ready()) {
            built.add(muted("Scanning the auction house - live listings appear shortly."));
            return built;
        }
        List<LiveAuction> live = liveAuctions;
        LiveAuctionIndex.Entry entry =
                LiveAuctionIndex.getInstance().get(SkyblockItem.normalizeName(lastName));
        String scope = entry != null && entry.totalListings() > live.size()
                ? " of " + entry.totalListings() : "";
        built.add(header("Live Auctions" + (live.isEmpty() ? "" : " (" + live.size() + scope + ")")));
        if (live.isEmpty()) {
            built.add(muted("Nothing of this name is listed right now."));
        } else {
            for (LiveAuction auction : live) {
                built.add(new Line(null, fmt(auction.unit()), SBSTheme.TEXT,
                        SBSTheme.ACCENT_BRIGHT, auction));
            }
            built.add(muted("Click: open auction"));
        }
        built.add(muted(""));
        built.add(muted("Listings, not a valuation: no sale history, and no comparison"));
        built.add(muted("of this item's own stars, books or gems against them."));
        return built;
    }

    private static List<LiveAuction> parseLive(AppraiseApi.Response response) {
        List<LiveAuction> live = new ArrayList<>();
        JsonArray cheapest = response.live_cheapest;
        if (cheapest == null) {
            return live;
        }
        for (int i = 0; i < cheapest.size() && live.size() < MAX_LIVE_ROWS; i++) {
            try {
                JsonArray row = cheapest.get(i).getAsJsonArray();
                if (row.size() >= 5) {
                    // Optional 7th element: value match 0..100 ([5] is the coin distance).
                    int match = row.size() >= 7 && row.get(6).isJsonPrimitive()
                            ? (int) Math.round(row.get(6).getAsDouble()) : -1;
                    live.add(new LiveAuction(row.get(0).getAsString(), row.get(1).getAsDouble(),
                            row.get(2).getAsInt(), row.get(3).getAsString(), row.get(4).getAsLong(),
                            match, null));
                }
            } catch (Exception ignored) {
                // one malformed row must not kill the whole window
            }
        }
        return live;
    }

    // ------------------------------------------------------------------
    // Line building
    // ------------------------------------------------------------------

    private List<Line> currentLines() {
        if (localMode) {
            return localLines();
        }
        AppraiseApi.Response response = data;
        if (loading) {
            return List.of(muted("Loading..."));
        }
        if (response == null) {
            return List.of(new Line(error != null ? error : "No data", "",
                    SBSTheme.WARN, SBSTheme.WARN, null));
        }
        if (builtFor != response) {
            lines = buildLines(response, liveAuctions);
            builtFor = response;
        }
        return lines;
    }

    private static List<Line> buildLines(AppraiseApi.Response r, List<LiveAuction> live) {
        List<Line> built = new ArrayList<>();

        String valueText = r.value != null && r.value > 0 ? fmt(r.value) : "-";
        String valueSuffix = r.value_n != null && r.value_n > 0 ? " (" + r.value_n + " sales)" : "";
        built.add(new Line("Est. sell value · " + basisLabel(r.value_basis) + valueSuffix, valueText,
                SBSTheme.TEXT, SBSTheme.ACCENT_BRIGHT, null));
        addSpikeWarning(built, r.price_spike);
        addMayorLine(built, r.mayor);
        if (r.extras_value != null && r.extras_value > 0) {
            built.add(new Line("Extras value (all modifiers)", fmt(r.extras_value),
                    SBSTheme.TEXT, SBSTheme.TEXT, null));
        }
        String lbinText = r.lbin != null && r.lbin.length > 0 && r.lbin[0] > 0
                ? fmt(r.lbin[0]) + (r.lbin.length > 1 && r.lbin[1] > 0
                        ? " · " + (int) r.lbin[1] + " offers" : "")
                : "-";
        built.add(new Line("Lowest BIN (item)", lbinText, SBSTheme.TEXT, SBSTheme.TEXT, null));

        built.add(header("Live Auctions" + (live.isEmpty() ? "" : " (" + live.size() + ")")));
        if (live.isEmpty()) {
            built.add(muted("No live auctions for this variant"));
        } else {
            for (LiveAuction auction : live) {
                built.add(new Line(null, fmt(auction.unit()),
                        auction.match() >= 0 ? matchColor(auction.match()) : SBSTheme.TEXT,
                        SBSTheme.ACCENT_BRIGHT, auction));
            }
            built.add(muted("Click: open auction · Right-Click: /ah <seller>"));
        }

        addClosest(built, r.closest);

        built.add(header("Sale History"));
        boolean anyTier = false;
        if (r.tiers != null) {
            anyTier |= addTier(built, "Identical", r.tiers.exact);
            anyTier |= addTier(built, "Similar", r.tiers.similar);
            anyTier |= addTier(built, "Base item", r.tiers.base);
        }
        if (!anyTier) {
            built.add(muted("No sale history for this variant"));
        }

        if (r.recent != null && r.recent.length > 0) {
            built.add(header("Recent Sales"));
            for (int i = 0; i < r.recent.length && i < MAX_RECENT_ROWS; i++) {
                double[] sale = r.recent[i];
                if (sale.length < 4) {
                    continue;
                }
                String left = "x" + (int) sale[2] + " · " + (sale[3] > 0 ? "BIN" : "Auction")
                        + " · " + ago((long) sale[0]);
                built.add(new Line(left, fmt(sale[1]), SBSTheme.TEXT_MUTED, SBSTheme.TEXT, null));
            }
        }
        return built;
    }

    /**
     * The unusual-price-increase warning right under the estimated value: the server sends
     * {@code price_spike} only when the item's median sale price jumped by at least the configured
     * factor within the last day vs the week before – prices in such a phase are unreliable
     * (manipulation, hype, patch fallout), so the estimate deserves a visible caveat.
     * Renders like: {@code ! Price spike: 2.0x in 24h vs 7d (102m -> 202m)}.
     */
    private static void addSpikeWarning(List<Line> built, AppraiseApi.PriceSpike spike) {
        if (spike == null || spike.factor == null || spike.factor <= 1) {
            return;
        }
        StringBuilder sb = new StringBuilder("! Price spike: ").append(trim(spike.factor)).append('x');
        if (spike.recent_hours != null && spike.baseline_days != null) {
            sb.append(" in ").append(spike.recent_hours).append("h vs ")
                    .append(spike.baseline_days).append('d');
        }
        if (spike.baseline != null && spike.recent != null
                && spike.baseline > 0 && spike.recent > 0) {
            sb.append(" (").append(fmt(spike.baseline)).append(" -> ").append(fmt(spike.recent)).append(')');
        }
        built.add(new Line(sb.toString(), "", SBSTheme.WARN, SBSTheme.WARN, null));
    }

    /**
     * The mayor price-awareness line, right under the estimate: the current SkyBlock mayor can
     * deflate or inflate a whole item's market for their term. Shown ONLY when the server sent
     * {@code available == true} - a missing block or {@code available == false} (still gathering
     * data, the normal early case) renders nothing, no placeholder line.
     *
     * <p>The effect sign decides framing and colour:
     * <ul>
     *   <li><b>Below normal</b> (deflation) → a muted, informational line: a cheap market is
     *       opportunity, not a warning.</li>
     *   <li><b>Above normal</b> (inflation) → a red {@code "! "} warning (same style as the price
     *       spike line): the high price is artificial, so a lowball buyer must factor it out.</li>
     * </ul>
     * The server's {@code note} is the authored summary and is preferred as the text (sanitized,
     * untrusted like all API strings), matching this window's English UI; without one the line is
     * composed from {@code effect_pct} and {@code mayor_name}.
     */
    private static void addMayorLine(List<Line> built, AppraiseApi.Mayor mayor) {
        if (mayor == null || !Boolean.TRUE.equals(mayor.available)) {
            return; // no block, or "not enough data yet" - the normal case, shown as nothing
        }
        double effect = mayor.effect_pct != null ? mayor.effect_pct : 0;
        boolean inflated = effect > 0;
        String note = mayor.note != null ? sanitizeNote(mayor.note) : "";
        String who = mayor.mayor_name != null && !mayor.mayor_name.isBlank()
                ? " under Mayor " + sanitizeMayor(mayor.mayor_name) : "";
        String text;
        if (!note.isEmpty()) {
            text = (inflated ? "! " : "") + note;
        } else if (inflated) {
            text = "! ~+" + trim(effect) + "% above normal" + who + " - price inflated, factor it out";
        } else if (effect < 0) {
            text = "~" + trim(effect) + "% below normal" + who + " - prices deflated";
        } else {
            return; // available but no effect and no note - nothing meaningful to show
        }
        int color = inflated ? SBSTheme.WARN : SBSTheme.TEXT_MUTED;
        built.add(new Line(text, "", color, color, null));
    }

    /** Mayor name from the API is untrusted display text: keep it short and printable only. */
    private static String sanitizeMayor(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9 ]", "").trim();
        return clean.length() > 24 ? clean.substring(0, 24) : clean;
    }

    /** The server's mayor note is untrusted: strip control/§ chars and clamp to a single tidy line. */
    private static String sanitizeNote(String note) {
        String clean = note.replaceAll("[^\\x20-\\x7E]", "").trim();
        return clean.length() > 90 ? clean.substring(0, 90) : clean;
    }

    /** Adds one history tier (two lines); returns true when the tier had data. */
    private static boolean addTier(List<Line> built, String label, AppraiseApi.Tier tier) {
        if (tier == null || tier.n <= 0) {
            return false;
        }
        String soldIn = tier.sold_in != null && tier.sold_in.median != null
                ? " · sells in ~" + duration(tier.sold_in.median.longValue()) : "";
        built.add(new Line(label + " · " + tier.n + " sales" + soldIn,
                tier.median != null ? fmt(tier.median) : "-",
                SBSTheme.TEXT, SBSTheme.ACCENT_BRIGHT, null));
        StringBuilder detail = new StringBuilder("   avg ").append(fmtOr(tier.avg))
                .append(" · min ").append(fmtOr(tier.min)).append(" · max ").append(fmtOr(tier.max));
        if (tier.last != null && tier.last.unit > 0) {
            detail.append(" · last ").append(fmt(tier.last.unit)).append(' ').append(ago(tier.last.ts));
        }
        built.add(muted(detail.toString()));
        return true;
    }

    // ------------------------------------------------------------------
    // Closest matches: comparison items with value match % and per-difference weights
    // ------------------------------------------------------------------

    private static final int MAX_CLOSEST = 5;
    private static final int MAX_DIFFS_PER_SIDE = 5;
    private static final int MATCH_YELLOW = 0xFFFFD64D;
    private static final int DIFF_THEY = 0xFFE0A14D;

    private static void addClosest(List<Line> built, JsonArray closest) {
        if (closest == null || closest.size() == 0) {
            return;
        }
        built.add(header("Closest Matches"));
        int shown = 0;
        for (int i = 0; i < closest.size() && shown < MAX_CLOSEST; i++) {
            try {
                JsonObject entry = closest.get(i).getAsJsonObject();
                double price = num(entry, "price");
                if (price <= 0) {
                    continue;
                }
                int match = entry.has("match") && entry.get("match").isJsonPrimitive()
                        ? (int) Math.round(entry.get("match").getAsDouble()) : -1;
                int count = (int) Math.max(1, num(entry, "count"));
                long endAt = (long) num(entry, "end_at");
                boolean liveEntry = "live".equals(str(entry, "source"));
                String auctionId = str(entry, "auction_id");
                String seller = str(entry, "seller");
                Double adj = optNum(entry, "adj");
                Double est = optNum(entry, "est");
                if (est == null && adj != null) {
                    est = price + adj;
                }
                long ts = (long) num(entry, "ts");

                LiveAuction auction = liveEntry && auctionId != null && seller != null
                        ? new LiveAuction(auctionId, price, count, seller, endAt, match, adj) : null;
                int color = match >= 0 ? matchColor(match) : SBSTheme.TEXT;
                if (auction != null) {
                    built.add(new Line(null, fmt(price), color, SBSTheme.ACCENT_BRIGHT, auction));
                } else {
                    long soldTs = ts > 0 ? ts : endAt; // older responses only carry end_at
                    built.add(new Line(null, fmt(price), color, SBSTheme.TEXT, null,
                            new SaleRef(seller, match, count, soldTs, adj)));
                }
                if (est != null && est > 0) {
                    // The estimate is for YOUR hovered item (this listing's price corrected by the
                    // value differences), with the direction spelled out explicitly.
                    String direction = adj == null || Math.abs(adj) < 1
                            ? "same value as this one"
                            : adj > 0
                                    ? "yours is worth " + fmt(adj) + " more than this one"
                                    : "yours is worth " + fmt(-adj) + " less than this one";
                    built.add(muted("   → your item est " + fmt(est) + " (" + direction + ")"));
                }
                JsonObject diff = entry.has("diff") && entry.get("diff").isJsonObject()
                        ? entry.getAsJsonObject("diff") : null;
                if (diff != null) {
                    // "+" = the listing has this on top of your item, "−" = your item is ahead.
                    double[] hidden = new double[2]; // [count, adj-signed coin sum] of unrendered diffs
                    addDiffSide(built, diff, "they_have", "+", true, DIFF_THEY, -1, hidden);
                    addDiffSide(built, diff, "you_have", "-", false, SBSTheme.TOGGLE_ON, 1, hidden);
                    if (hidden[0] > 0) {
                        // rendered diff weights + this remainder add up to exactly adj
                        built.add(muted("      +" + (int) hidden[0] + " more differences (Est: "
                                + signed(hidden[1]) + ")"));
                    }
                }
                shown++;
            } catch (Exception ignored) {
                // one malformed entry must not kill the whole window
            }
        }
    }

    /** Renders up to {@link #MAX_DIFFS_PER_SIDE} entries of one diff side; everything NOT rendered
     *  goes into {@code hidden} as [count, coin sum], the sum signed in adj terms via
     *  {@code adjSign} (you_have = +w, they_have = −w). */
    private static void addDiffSide(List<Line> built, JsonObject diff, String key, String sign,
                                    boolean theyPrimary, int color, int adjSign, double[] hidden) {
        if (!diff.has(key) || !diff.get(key).isJsonArray()) {
            return;
        }
        JsonArray side = diff.getAsJsonArray(key);
        int shown = 0;
        for (int i = 0; i < side.size(); i++) {
            JsonObject o;
            try {
                o = side.get(i).getAsJsonObject();
            } catch (Exception malformed) {
                continue;
            }
            String text = shown < MAX_DIFFS_PER_SIDE ? diffTextSafe(o, theyPrimary) : null;
            if (text != null) {
                built.add(new Line("   " + sign + " " + text, "", color, color, null));
                shown++;
            } else {
                hidden[0]++;
                hidden[1] += adjSign * num(o, "w");
            }
        }
    }

    private static String diffTextSafe(JsonObject o, boolean theyPrimary) {
        try {
            return diffText(o, theyPrimary);
        } catch (Exception e) {
            return null;
        }
    }

    /** "+ Smite 7 (vs 6) · ~40m" from {@code {t:"ench",k:"smite",you:6,they:7,w:40000000}}. */
    private static String diffText(JsonObject o, boolean theyPrimary) {
        String type = str(o, "t");
        String k = str(o, "k");
        String label = switch (type == null ? "" : type) {
            // Enchant labels go through the shared actual-vs-shown mapping
            // ("ultimate_reiterate" -> "Duplex", "dragon_hunter" -> "Gravity").
            case "ench" -> k != null
                    ? sbs.modid.client.helper.enchants.EnchantNames.displayName(k) : pretty(type);
            case "attr", "rune", "scroll" -> k != null ? pretty(k) : pretty(type);
            case "gem" -> "Gem" + (k != null ? " " + pretty(k) : "");
            case "mod" -> k != null ? k : "Modifier";
            case "stars" -> "Stars";
            case "recomb" -> "Recombobulated";
            case "hpb" -> "Potato Books";
            case "reforge" -> "Reforge";
            case "tier" -> "Rarity Tier";
            default -> k != null ? pretty(k) : (type != null ? pretty(type) : null);
        };
        if (label == null) {
            return null;
        }
        String primary = valueText(o, theyPrimary ? "they" : "you");
        String other = valueText(o, theyPrimary ? "you" : "they");
        StringBuilder sb = new StringBuilder(label);
        if (primary != null) {
            sb.append(' ').append(primary);
        }
        if (other != null) {
            sb.append(" (vs ").append(other).append(')');
        }
        double weight = num(o, "w");
        if (weight > 0) {
            sb.append(" · ~").append(fmt(weight));
        }
        return sb.toString();
    }

    /** A diff entry's you/they value as display text ({@code null} for absent / 0 / empty). */
    private static String valueText(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) {
            return null;
        }
        var primitive = o.getAsJsonPrimitive(key);
        if (primitive.isNumber()) {
            long value = Math.round(primitive.getAsDouble());
            return value == 0 ? null : String.valueOf(value);
        }
        String text = primitive.getAsString();
        return text.isBlank() ? null : pretty(text);
    }

    /** Match badge text per the display recommendation (≥95 "Match", 80–94 "Similar", else %). */
    private static String matchBadge(int match) {
        if (match < 0) {
            return "";
        }
        if (match >= 95) {
            return "Match " + match + "% · ";
        }
        if (match >= 80) {
            return "Similar " + match + "% · ";
        }
        return match + "% · ";
    }

    private static int matchColor(int match) {
        if (match >= 95) {
            return SBSTheme.TOGGLE_ON;
        }
        if (match >= 80) {
            return MATCH_YELLOW;
        }
        return SBSTheme.TEXT_MUTED;
    }

    /** "+386M" / "-12.5M" – coin amount with its sign always rendered. */
    private static String signed(double value) {
        return (value >= 0 ? "+" : "-") + fmt(Math.abs(value));
    }

    /** Optional numeric field: {@code null} when absent/non-numeric ({@link #num} returns 0). */
    private static Double optNum(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() && o.getAsJsonPrimitive(key).isNumber()
                    ? o.get(key).getAsDouble() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static double num(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String str(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * NBT ids whose in-game display name differs from the id ("dragon_hunter" was renamed to
     * "Gravity" in-game but keeps its old id in item NBT / the price API). Without this mapping
     * players think the server invents enchants they've never seen. Extend as Hypixel renames more.
     */
    private static final java.util.Map<String, String> ID_RENAMES = java.util.Map.of(
            "dragon_hunter", "Gravity");

    /** "ultimate_wise" / "WITHER_SHIELD_SCROLL" → "Ultimate Wise" / "Wither Shield Scroll". */
    private static String pretty(String id) {
        String renamed = ID_RENAMES.get(id.toLowerCase(Locale.ROOT));
        if (renamed != null) {
            return renamed;
        }
        return prettyWords(id);
    }

    private static String prettyWords(String id) {
        String[] words = id.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static Line header(String text) {
        return new Line(text, "", SBSTheme.ACCENT, SBSTheme.ACCENT, null);
    }

    private static Line muted(String text) {
        return new Line(text, "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED, null);
    }

    private static String basisLabel(String basis) {
        if (basis == null || basis.isBlank()) {
            return "no match";
        }
        return switch (basis) {
            case "exact" -> "identical";
            case "similar" -> "similar";
            case "base" -> "base item";
            case "weighted" -> "weighted";
            default -> basis;
        };
    }

    // ------------------------------------------------------------------
    // Rendering (FloatingWindows z-order pass)
    // ------------------------------------------------------------------

    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        memory.restore(state -> {
            posX = state.x;
            posY = state.y;
            sizeW = state.width(sizeW);
            sizeH = state.height(sizeH);
        });
        panelW = clamp(Math.min(sizeW, screen.width - MARGIN * 2), MIN_W, MAX_W);
        panelH = clamp(Math.min(sizeH, screen.height - MARGIN * 2), MIN_H, MAX_H);
        if (posX == Integer.MIN_VALUE) {
            posX = (screen.width - panelW) / 2 - 60;
            posY = (screen.height - panelH) / 2 - 10;
        }
        posX = clamp(posX, MARGIN, Math.max(MARGIN, screen.width - panelW - MARGIN));
        posY = clamp(posY, MARGIN, Math.max(MARGIN, screen.height - panelH - MARGIN));

        // Price texts depend on the available width (millions vs "b") – when resizing or a high
        // GUI scale crosses the threshold, rebuild the cached lines in the other mode.
        boolean compact = panelW < COMPACT_PRICES_W;
        if (compact != compactPrices) {
            compactPrices = compact;
            builtFor = null;
        }

        SciFiRender.glow(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, posX + 1, posY + 1, panelW - 2, panelH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int textY = posY + (HEADER_H - font.lineHeight) / 2 + 1;
        g.text(font, Component.literal(font.plainSubstrByWidth(title, panelW - PAD * 2 - 16, false)),
                posX + PAD, textY, SBSTheme.ACCENT_BRIGHT);
        boolean closeHover = inCloseBox(mouseX, mouseY);
        g.text(font, Component.literal("x"), closeX() + 3, textY,
                closeHover ? SBSTheme.WARN : SBSTheme.TEXT_MUTED);
        g.fill(posX + PAD, posY + HEADER_H, posX + panelW - PAD, posY + HEADER_H + 1, SBSTheme.ACCENT_SOFT);

        // Server / Local on its own strip under the header, then the pinned notice while the local
        // view is what is on screen. Both sit outside the scroll region so neither can scroll away.
        boolean preferLocal = cfg().similarSource.preferLocal;
        int switchY = posY + HEADER_H + 3;
        SourceSwitch.draw(g, font, posX + PAD, switchY, preferLocal, mouseX, mouseY);
        if (mouseX >= posX + PAD && mouseX < posX + PAD + SourceSwitch.width(font)
                && mouseY >= switchY && mouseY < switchY + SourceSwitch.HEIGHT) {
            List<Component> tip = new ArrayList<>();
            for (String line : SourceSwitch.tooltip(preferLocal)) {
                tip.add(Component.literal(line));
            }
            g.setTooltipForNextFrame(font, tip, java.util.Optional.empty(), mouseX, mouseY,
                    SBSTheme.tooltipStyle());
        }
        int noticeTop = switchY + SourceSwitch.HEIGHT + 2;
        int noticeHeight = localMode
                ? LocalRankingNotice.draw(g, font, posX + PAD, noticeTop,
                        Math.max(1, panelW - PAD * 2), null)
                : 0;

        List<Line> list = currentLines();
        // Measured off what was actually drawn, never assumed: the notice wraps to two lines on a
        // window dragged narrow, and a constant would paint the first row over it.
        int rowsTop = noticeTop + noticeHeight;
        this.rowsTop = rowsTop;
        int rowsBottom = posY + panelH - PAD;
        int visible = Math.max(1, (rowsBottom - rowsTop) / ROW_H);
        int maxScroll = Math.max(0, list.size() - visible);
        scrollRow = clamp(scrollRow, 0, maxScroll);
        if (maxScroll > 0) {
            g.text(font, Component.literal((scrollRow + 1) + "-" + Math.min(list.size(), scrollRow + visible)
                    + "/" + list.size()), closeX() - 40, textY, SBSTheme.TEXT_MUTED);
        }

        for (int i = 0; i < visible && scrollRow + i < list.size(); i++) {
            Line line = list.get(scrollRow + i);
            int rowY = rowsTop + i * ROW_H;
            boolean hover = line.auction() != null && mouseX >= posX + PAD && mouseX < posX + panelW - PAD
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hover) {
                SciFiRender.roundedRect(g, posX + PAD - 2, rowY - 1, panelW - PAD * 2 + 4, ROW_H, 2,
                        SBSTheme.CARD_BG_HOVER);
            }
            String left = line.left() != null ? line.left()
                    : line.auction() != null ? auctionText(line.auction()) : saleText(line.sale());
            String right = line.right();
            int rightW = right.isEmpty() ? 0 : font.width(right) + 2;
            g.text(font, Component.literal(font.plainSubstrByWidth(left, panelW - PAD * 2 - rightW - 4, false)),
                    posX + PAD, rowY, hover ? SBSTheme.ACCENT_BRIGHT : line.leftColor());
            if (!right.isEmpty()) {
                g.text(font, Component.literal(right), posX + panelW - PAD - font.width(right), rowY,
                        line.rightColor());
            }
        }

        // Edge/corner resize grips – identical mechanics and look to the Price History window.
        resizer.renderGrips(g, posX, posY, panelW, panelH, mouseX, mouseY);
    }

    /** A live row's text, composed per frame so the async seller name pops in when resolved.
     *  Closest entries carry {@code adj}: "Similar 80% · IAmGarb · +386m · x1 · ends in 2h". */
    private static String auctionText(LiveAuction auction) {
        long remaining = auction.endAt() - System.currentTimeMillis() / 1000L;
        String ends = remaining > 0 ? "ends in " + duration(remaining) : "ended";
        // A locally listed row carries no seller uuid. Rendering the usual "..." placeholder there
        // would read as a name still resolving, and it would never resolve.
        if (auction.sellerUuid() == null || auction.sellerUuid().isEmpty()) {
            return "x" + auction.count() + " · " + ends;
        }
        String seller = PlayerNameCache.getInstance().name(auction.sellerUuid());
        return matchBadge(auction.match())
                + (seller != null ? seller : "...")
                + (auction.adj() != null ? " · " + signed(auction.adj()) : "")
                + " · x" + auction.count() + " · " + ends;
    }

    /** A closest sale header: "Similar 80% · IAmGarb · +386m · x1 · sold 3h ago"; the sold-ago
     *  segment only renders with a real sale timestamp. */
    private static String saleText(SaleRef sale) {
        StringBuilder sb = new StringBuilder(matchBadge(sale.match()));
        if (sale.sellerUuid() != null && !sale.sellerUuid().isEmpty()) {
            String seller = PlayerNameCache.getInstance().name(sale.sellerUuid());
            sb.append(seller != null ? seller : "...").append(" · ");
        }
        if (sale.adj() != null) {
            sb.append(signed(sale.adj())).append(" · ");
        }
        sb.append('x').append(sale.count());
        if (sale.ts() > 0) {
            sb.append(" · sold ").append(ago(sale.ts()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Input (z-order dispatched from ContainerSearchBarMixin)
    // ------------------------------------------------------------------

    /** Clicks inside the window are consumed; a live-auction row click opens it in-game. */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (!inPanel(mx, my)) {
            return false;
        }
        if (inCloseBox(mx, my) && event.button() == 0) {
            close();
            return true;
        }
        if (event.button() == 0 && resizer.begin(mx, my, posX, posY, panelW, panelH)) {
            return true;
        }
        if (event.button() == 0) {
            Boolean picked = SourceSwitch.hit(Minecraft.getInstance().font, posX + PAD,
                    posY + HEADER_H + 3, mx, my);
            if (picked != null) {
                sbs.modid.client.core.api.RankingSource.choose(cfg().similarSource, picked);
                reopenCurrent();
                return true;
            }
        }
        if (my < posY + HEADER_H && event.button() == 0) {
            dragging = true;
            grabDX = mx - posX;
            grabDY = my - posY;
            return true;
        }
        Line line = lineAt(my);
        if (line != null && line.auction() != null) {
            openAuction(line.auction(), event.button());
        }
        return true;
    }

    /**
     * Leaves the container and opens the clicked auction: left = {@code /viewauction <id>}
     * (directly on the listing), right = {@code /ah <seller>} (once the name is resolved).
     */
    private void openAuction(LiveAuction auction, int button) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        String command;
        if (button == 1) {
            String seller = PlayerNameCache.getInstance().name(auction.sellerUuid());
            if (seller == null) {
                return; // name still resolving – next click
            }
            command = "ah " + seller;
        } else {
            command = "viewauction " + auction.id();
        }
        minecraft.player.closeContainer();
        minecraft.player.connection.sendCommand(command);
    }

    private Line lineAt(double my) {
        if (my < rowsTop || my >= posY + panelH - PAD) {
            return null;
        }
        int index = scrollRow + (int) ((my - rowsTop) / ROW_H);
        List<Line> list = currentLines();
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    public boolean handleDrag(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        if (dragging) {
            posX = clamp((int) (event.x() - grabDX), MARGIN, Math.max(MARGIN, screen.width - panelW - MARGIN));
            posY = clamp((int) (event.y() - grabDY), MARGIN, Math.max(MARGIN, screen.height - panelH - MARGIN));
            return true;
        }
        if (resizer.isActive()) {
            int[] rect = resizer.drag(event.x(), event.y(), MIN_W, MAX_W, MIN_H, MAX_H);
            posX = rect[0];
            posY = rect[1];
            sizeW = rect[2];
            sizeH = rect[3];
            return true;
        }
        return false;
    }

    public boolean handleRelease(MouseButtonEvent event) {
        if (!open) {
            return false;
        }
        boolean wasResizing = resizer.end();
        if (!dragging && !wasResizing) {
            return false;
        }
        dragging = false;
        rememberWindow();
        return true;
    }

    /** Persists the window's geometry so it comes back where it was left. */
    private void rememberWindow() {
        memory.remember(posX, posY, sizeW, sizeH, false);
    }

    /** Scroll: Ctrl held resizes the window, otherwise the line list scrolls. Consumed inside. */
    public boolean handleScroll(AbstractContainerScreen<?> screen, double mouseX, double mouseY, double scrollY) {
        if (!open || !inPanel(mouseX, mouseY)) {
            return false;
        }
        if (isCtrlDown()) {
            int step = (int) Math.signum(scrollY);
            sizeW = clamp(sizeW + step * 30, MIN_W, MAX_W);
            sizeH = clamp(sizeH + step * 20, MIN_H, MAX_H);
            rememberWindow();
            return true;
        }
        scrollRow = Math.max(0, scrollRow - (int) Math.signum(scrollY));
        return true;
    }

    // ------------------------------------------------------------------
    // Geometry / formatting helpers
    // ------------------------------------------------------------------

    private int closeX() {
        return posX + panelW - 14;
    }

    private boolean inCloseBox(double mx, double my) {
        return mx >= closeX() && mx < closeX() + 12 && my >= posY + 2 && my < posY + HEADER_H;
    }

    private boolean inPanel(double mx, double my) {
        return mx >= posX && mx < posX + panelW && my >= posY && my < posY + panelH;
    }

    private static boolean isCtrlDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String fmtOr(Double value) {
        return value != null && value > 0 ? fmt(value) : "-";
    }

    /**
     * Coin format: 850K / 34.5M / 123 – and width-aware in the billion range: with enough panel
     * width billions stay in millions ("1523M", more precise for comparing), collapsing to "1.5B"
     * only when the value is an exact round billion ("2B"), the window is too narrow for the wider
     * text ({@link #compactPrices}, e.g. high GUI scale), or the amount is absurdly large.
     */
    private static String fmt(double value) {
        if (!sbs.modid.client.core.util.NumberDisplay.enabled()) {
            return sbs.modid.client.core.util.NumberDisplay.grouped(value);
        }
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000) {
            boolean exactBillion = Math.round(abs) % 1_000_000_000L == 0;
            if (exactBillion) {
                return Math.round(value / 1_000_000_000) + "B";
            }
            if (compactPrices || abs >= 100_000_000_000L) {
                return trim(value / 1_000_000_000) + "B";
            }
            return Math.round(value / 1_000_000) + "M";
        }
        if (abs >= 1_000_000) {
            return trim(value / 1_000_000) + "M";
        }
        if (abs >= 1_000) {
            return trim(value / 1_000) + "K";
        }
        return String.valueOf(Math.round(value));
    }

    private static String trim(double value) {
        return Math.abs(value) >= 100
                ? String.valueOf(Math.round(value))
                : String.format(Locale.ROOT, "%.1f", value);
    }

    /** "45s" / "12m" / "3h 05m" / "2d 4h". */
    private static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        if (seconds < 86400) {
            return String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
        }
        return String.format(Locale.ROOT, "%dd %dh", seconds / 86400, (seconds % 86400) / 3600);
    }

    private static String ago(long tsSeconds) {
        long delta = Math.max(0, System.currentTimeMillis() / 1000L - tsSeconds);
        return duration(delta) + " ago";
    }
}
