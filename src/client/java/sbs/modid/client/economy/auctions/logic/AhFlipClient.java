/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.auctions.ui.AhFlipPopups;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Client for the SBS AH flip alerts ({@code /flips/} on the SBS backend).
 *
 * <p><b>Transport: long-poll</b>, exactly like {@link sbs.modid.client.social.chat.logic.IrcClient}: one
 * background daemon thread holds {@code GET /flips/poll} open for up to ~25s; the server answers
 * the moment a scan finds new flips – every waiting client at the same instant, so nobody gains an
 * edge from poll timing. The user's price range travels as {@code min}/{@code max} query
 * parameters and is filtered server-side (and re-checked here).
 *
 * <p>Each flip becomes one client-side chat line – item, price, estimated value, profit, discount,
 * weekly sales – ending in a clickable <b>[OPEN IN AH]</b> that runs {@code /viewauction <id>}.
 *
 * <p><b>Input handling.</b> Auth is the licence token as a header (never in the URL), over HTTPS.
 * Everything received is validated before use: the auction id must be 32 hex characters before a
 * command is built from it, so a response can only ever produce a {@code /viewauction} for a real
 * id, and display strings are whitelist-sanitized so they carry no formatting codes or click
 * events into chat.
 */
public final class AhFlipClient {

    private static final AhFlipClient INSTANCE = new AhFlipClient();

    /** The flips service on the SBS backend, over HTTPS. */
    private static final String BASE = "https://skyblocksimplified.info";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(32);
    private static final int POLL_HOLD_SECONDS = 25;

    /** The server never sends flips below its own floor; mirror it for the range clamp. */
    private static final long SERVER_MIN_PRICE = 5_000_000L;

    /** Columns the client reads by fixed index (0..14). Rows with more are fine; extras are ignored. */
    private static final int KNOWN_ROW_FIELDS = 15;

    /** Hypixel auction ids are exactly 32 lowercase hex chars – anything else is refused. */
    private static final Pattern AUCTION_ID = Pattern.compile("[0-9a-f]{32}");

    /** Item ids / reforges may only carry these before they are shown (no §-codes, no control chars). */
    private static final Pattern DISPLAY_JUNK = Pattern.compile("[^A-Za-z0-9_:]");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** -1 = fresh session: the server answers with the cursor + the still-fresh flips of the last scan. */
    private volatile long since = -1;
    private volatile boolean authFailureShown;
    private Thread pollThread;
    private Thread soldThread;

    /**
     * How often the sold-watcher polls Hypixel's ended-auctions feed. That feed covers roughly the
     * last minute and refreshes about once a minute, so polling every 30s keeps consecutive windows
     * overlapping - a flip that sells is never missed between two polls.
     */
    private static final long SOLD_POLL_MS = 30_000L;

    /** Auction ids already shown, so a reconnect / re-enable never repeats an alert. */
    private final Set<String> shown = new LinkedHashSet<>();
    private static final int SHOWN_CAP = 512;

    private AhFlipClient() {
    }

    public static AhFlipClient getInstance() {
        return INSTANCE;
    }

    /** Starts the poll + sold-watch loop threads once (both idle cheaply while the toggle is off). */
    public synchronized void start() {
        if (pollThread != null) {
            return;
        }
        pollThread = new Thread(this::pollLoop, "SBS-FlipAlerts-Poll");
        pollThread.setDaemon(true);
        pollThread.start();
        soldThread = new Thread(this::soldLoop, "SBS-FlipAlerts-Sold");
        soldThread.setDaemon(true);
        soldThread.start();
    }

    private static SBSConfig.AhFlipAlertSettings cfg() {
        return ConfigManager.getInstance().get().ahFlips;
    }

    private static String token() {
        String token = sbs.modid.client.core.config.LicenceToken.getInstance().get();
        return token == null ? "" : token.trim();
    }

    private static boolean inWorld() {
        return Minecraft.getInstance().player != null;
    }

    // ------------------------------------------------------------------
    // Long-poll loop
    // ------------------------------------------------------------------

    private void pollLoop() {
        while (true) {
            try {
                if (!cfg().enabled || !inWorld()) {
                    since = -1; // next enable starts fresh (server replays only still-fresh flips)
                    Thread.sleep(2_000);
                    continue;
                }
                // The player's choice decides which producer runs. Picking Local means the poll is
                // never opened - not opened and then discarded - so an unlicensed client stops
                // knocking on a door it has no key to, which is also why the token check moved out
                // of the gate above: without a token the local scan is the whole feature, not a
                // reason to idle.
                if (sbs.modid.client.core.api.RankingSource.useLocal(cfg().flipSource, "Flips")) {
                    since = -1;
                    scanLocal();
                    Thread.sleep(LOCAL_SCAN_MS);
                    continue;
                }
                if (token().isEmpty()) {
                    Thread.sleep(2_000);
                    continue;
                }
                long min = Math.max(cfg().minPrice, SERVER_MIN_PRICE);
                long max = cfg().maxPrice > 0 ? Math.max(cfg().maxPrice, min) : 0;
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/flips/poll
// METHOD: GET (long-poll - the server holds the request open until a flip appears or the
//   timeout given in the query string expires, then the loop re-polls)
// PURPOSE: Push auction-flip alerts without polling every second. Long-poll is used instead
//   of a socket precisely because it is ordinary HTTPS a reviewer can read.
// DATA SENT: Query parameters only - a cursor (?since=), the hold time, and the price window
//   the user configured (?min=, ?max=). No body.
// DATA RECEIVED: Read-only JSON - auction ids, prices and margins. Parsed with Gson.
// SAFETY DECLARATION: Sends the licence token (Authorization: Bearer, X-Api-Token),
//   which identifies the PURCHASE and not the player. No Mojang credentials, no
//   session id, no OS or hardware telemetry. Refused until the user grants
//   ConsentScope.LICENCE_VALIDATION in Licence Token > Privacy & data; every switch there starts
//   off. See PRIVACY.md.
// ============================================================================
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                BASE + "/flips/poll?since=" + since
                                        + "&timeout=" + POLL_HOLD_SECONDS
                                        + "&min=" + min + "&max=" + max))
                        .timeout(POLL_TIMEOUT)
                        .header("Authorization", "Bearer " + token())
                        .GET().build();
                HttpResponse<String> response = sbs.modid.client.core.api.SbsApi.send(
                        ConsentScope.LICENCE_VALIDATION, http, request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 401) {
                    if (!authFailureShown) {
                        authFailureShown = true;
                        show(system("Flip alerts: invalid licence token - check the Licence Token module."));
                    }
                    Thread.sleep(30_000);
                    continue;
                }
                if (response.statusCode() == 423) {
                    if (!authFailureShown) {
                        authFailureShown = true;
                        show(system("Flip alerts: token locked to another connection."));
                    }
                    Thread.sleep(30_000);
                    continue;
                }
                if (response.statusCode() != 200) {
                    Thread.sleep(5_000);
                    continue;
                }
                // Parse leniently: a poll answer is normally {"seq":..,"flips":[..]}, but the
                // server can also return a bare primitive (e.g. a status/error string) or an
                // {"error":..} object. Gson 2.11 would throw coercing a non-object into
                // JsonObject.class, so read a JsonElement first and branch on its actual shape.
                JsonElement parsed = parse(response.body());
                JsonObject body = parsed != null && parsed.isJsonObject()
                        ? parsed.getAsJsonObject() : null;
                if (body == null) {
                    // A primitive body carries the server's out-of-band note; show it once.
                    if (parsed != null && parsed.isJsonPrimitive() && !authFailureShown) {
                        authFailureShown = true;
                        show(system("Flip alerts: " + sanitizeError(parsed.getAsString())));
                    }
                    Thread.sleep(5_000);
                    continue;
                }
                if (body.has("error") && !body.get("error").isJsonNull()) {
                    if (!authFailureShown) {
                        authFailureShown = true;
                        show(system("Flip alerts: " + sanitizeError(body.get("error").getAsString())));
                    }
                    Thread.sleep(10_000);
                    continue;
                }
                authFailureShown = false; // clean response - re-arm the one-shot notices
                readMayor(body); // top-level {"mayor":{available,name,...}} sibling of flips
                long newSince = body.has("seq") && body.get("seq").isJsonPrimitive()
                        ? body.get("seq").getAsLong() : since;
                JsonElement flips = body.get("flips");
                if (flips != null && flips.isJsonArray()) {
                    for (JsonElement element : flips.getAsJsonArray()) {
                        if (!element.isJsonArray()) {
                            continue; // each flip is a positional array; ignore anything else
                        }
                        try {
                            deliver(element.getAsJsonArray(), min, max);
                        } catch (RuntimeException malformed) {
                            // One bad row must not kill the alert stream.
                            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Flips] bad row: {}",
                                    malformed.toString());
                        }
                    }
                }
                since = Math.max(since, newSince);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Flips] poll failed: {}", e.toString());
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Sold-watch loop: remove flips when their auction actually sells/ends
    // ------------------------------------------------------------------

    /**
     * Watches Hypixel's keyless ended-auctions feed and removes any live flip whose auction has sold
     * or ended - the client's own "is it gone?" check, no cloud server involved. This is what
     * replaces the old dismiss-on-click: a flip stays in the list while you look at it and only
     * leaves once it is genuinely off the market. Cheap: skipped entirely when the module is off,
     * the player is out of world, or the feed is empty; otherwise one keyless request per
     * {@link #SOLD_POLL_MS}.
     */
    private void soldLoop() {
        AhEndedAuctionsClient client = new AhEndedAuctionsClient();
        while (true) {
            try {
                if (!cfg().enabled || !inWorld()) {
                    Thread.sleep(5_000);
                    continue;
                }
                List<AhFlipFeed.Flip> flips = AhFlipFeed.getInstance().active();
                if (flips.isEmpty()) {
                    Thread.sleep(SOLD_POLL_MS);
                    continue;
                }
                AhEndedAuctionsClient.Response ended = client.fetch();
                if (ended != null && ended.auctions != null) {
                    Set<String> soldIds = new HashSet<>();
                    for (AhEndedAuctionsClient.Ended auction : ended.auctions) {
                        if (auction != null && auction.auction_id != null) {
                            soldIds.add(normalizeAuctionId(auction.auction_id));
                        }
                    }
                    for (AhFlipFeed.Flip flip : flips) {
                        if (soldIds.contains(flip.auctionId())) {
                            AhFlipFeed.getInstance().dismiss(flip.auctionId());
                            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Flips] flip sold/ended, removed: {}",
                                    flip.displayName());
                        }
                    }
                }
                Thread.sleep(SOLD_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Flips] sold-watch failed: {}", e.toString());
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Ended-feed ids to the same 32-hex lowercase form the flips carry (see {@link #deliver}). */
    private static String normalizeAuctionId(String id) {
        return id.toLowerCase(Locale.ROOT).replace("-", "");
    }

    // ------------------------------------------------------------------
    // Local scan: live auctions only, no server involved
    // ------------------------------------------------------------------

    /**
     * How often the local ranking is re-read. The index behind it is rebuilt by the auction crawl
     * roughly once a minute, so scanning faster would re-examine the same listings; scanning much
     * slower would let a flip expire before it was ever shown.
     */
    private static final long LOCAL_SCAN_MS = 20_000L;

    /**
     * Reads {@link LocalAhFlipEngine} and pushes anything new into the same shared feed the server
     * stream feeds.
     *
     * <p>Deliberately the same {@link AhFlipFeed.Flip} record and the same feed: the popups, the
     * window and the chat line then work identically whichever engine found the flip, and there is
     * one expiry rule, one dismiss list and one sold-watcher rather than two of each. The only thing
     * the outputs need to know is the {@code local} flag, which changes the wording of the market
     * figure - see {@link AhFlipFeed.Flip#volumeLabel()}.
     */
    private void scanLocal() {
        SBSConfig.AhFlipAlertSettings cfg = cfg();
        for (LocalAhFlipEngine.Candidate candidate : LocalAhFlipEngine.compute(cfg)) {
            if (AhFlipFeed.getInstance().isDismissed(candidate.auctionId())) {
                continue;
            }
            synchronized (shown) {
                if (!shown.add(candidate.auctionId())) {
                    continue;
                }
                if (shown.size() > SHOWN_CAP) {
                    var it = shown.iterator();
                    it.next();
                    it.remove();
                }
            }
            AhFlipFeed.Flip flip = new AhFlipFeed.Flip(candidate.auctionId(),
                    sanitizeName(candidate.displayName()), candidate.price(), candidate.resale(),
                    candidate.profit(), candidate.discountPct(), 0, candidate.endAtMs(),
                    candidate.discountPct(), System.currentTimeMillis(), true,
                    candidate.liveListings());
            AhFlipFeed.getInstance().add(flip);
            if (cfg.popupAlerts) {
                AhFlipPopups.getInstance().push(flip);
            }
            if (cfg.chatAlerts) {
                showChatLine(flip);
            }
        }
    }

    /**
     * Hypixel's own item name, made safe to print.
     *
     * <p>It arrives §-coded and is player-influenced (renamed items, pet names), so it goes through
     * the same whitelist the server-supplied names do rather than being trusted for coming from
     * Hypixel - a name is display text either way, and this one reaches chat.
     */
    private static String sanitizeName(String raw) {
        String clean = (raw == null ? "" : raw).replaceAll("§.", "")
                .replaceAll("[^A-Za-z0-9 '+.:✪⚚◆★-]", "").trim();
        if (clean.isEmpty()) {
            return "Unknown Item";
        }
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    // ------------------------------------------------------------------
    // Message building (all server data treated as untrusted)
    // ------------------------------------------------------------------

    /**
     * Row layout (see the server README):
     * {@code [seq, foundTs, auctionId, itemId, price, target, profit, discountPct, salesWeek,
     * endAt, count, stars, recomb, reforge, rating]}.
     *
     * <p>Validated and sanitized here ONCE, then handed to the shared {@link AhFlipFeed}; the
     * outputs (popup cards, Flips window, chat line) only ever see the clean record.
     */
    private void deliver(JsonArray row, long min, long max) {
        // Read the known columns by fixed index (highest is 14 = rating), so any extra fields the
        // server appends past index 14 are simply ignored - the mod never breaks on new columns.
        if (row.size() < KNOWN_ROW_FIELDS) {
            return;
        }
        String auctionId = row.get(2).getAsString().toLowerCase(Locale.ROOT).replace("-", "");
        if (!AUCTION_ID.matcher(auctionId).matches()) {
            return; // never build a command from data that is not exactly an auction id
        }
        long price = row.get(4).getAsLong();
        long target = row.get(5).getAsLong();
        long profit = row.get(6).getAsLong();
        double discount = row.get(7).getAsDouble();
        long salesWeek = row.get(8).getAsLong();
        long endAt = Math.max(0, row.get(9).getAsLong());
        int count = Math.max(1, row.get(10).getAsInt());
        int stars = Math.max(0, Math.min(10, row.get(11).getAsInt()));
        String reforge = row.get(13).isJsonNull() ? "" : row.get(13).getAsString();
        double rating = row.get(14).getAsDouble();
        // The server already filtered by range; re-check locally so a stale poll (range just
        // changed) or a misbehaving response can never alert outside the user's bounds.
        if (price < min || (max > 0 && price > max) || profit <= 0) {
            return;
        }
        if (AhFlipFeed.getInstance().isDismissed(auctionId)) {
            return; // the user closed this one - never resurrect it
        }
        synchronized (shown) {
            if (!shown.add(auctionId)) {
                return;
            }
            if (shown.size() > SHOWN_CAP) {
                var it = shown.iterator();
                it.next();
                it.remove();
            }
        }

        String name = displayName(row.get(3).getAsString(), reforge, stars, count);
        AhFlipFeed.Flip flip = new AhFlipFeed.Flip(auctionId, name, price, target, profit,
                discount, salesWeek, endAt * 1000L, rating, System.currentTimeMillis(), false, 0);
        AhFlipFeed.getInstance().add(flip);
        SBSConfig.AhFlipAlertSettings cfg = cfg();
        if (cfg.popupAlerts) {
            AhFlipPopups.getInstance().push(flip);
        }
        if (cfg.chatAlerts) {
            showChatLine(flip);
        }
    }

    /** The chat output (optional, in addition to the popups): one line with a click-to-open link. */
    private static void showChatLine(AhFlipFeed.Flip flip) {
        MutableComponent open = Component.literal(" [OPEN IN AH]")
                .withColor(SBSChat.PREFIX_COLOR)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/viewauction " + flip.auctionId()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                "Click to open this auction (" + coins(flip.price()) + " coins)"))));
        MutableComponent line = SBSChat.prefix()
                .append(Component.literal(" [FLIP]").withColor(0xFFC94D))
                .append(Component.literal(" " + flip.displayName()).withColor(SBSChat.WHITE))
                .append(Component.literal(" " + coins(flip.price())).withColor(0xFFC94D))
                .append(Component.literal(" → " + coins(flip.target())).withColor(0x8FA6BF))
                .append(Component.literal("  +" + coins(flip.profit())).withColor(0x57D977))
                .append(Component.literal(" (" + Math.round(flip.discountPct()) + "% off, "
                        + flip.volumeLabel() + ")").withColor(0x8FA6BF))
                .append(open);
        show(line);
    }

    /**
     * Opens a flip in game (popup card / Flips window click): leaves the container and runs
     * {@code /viewauction <id>}. The id comes from a {@link AhFlipFeed.Flip}, i.e. it was strictly
     * validated on receipt.
     *
     * <p><b>Opening no longer removes the flip.</b> A click means "let me look at it", not "I bought
     * it" - the old dismiss-on-click hid flips the moment you inspected them, even if you did not buy.
     * A flip now leaves the feed only when it is genuinely gone: sold/bought (detected client-side by
     * {@link #soldLoop} against Hypixel's ended-auctions endpoint), expired, or manually dismissed
     * with the popup card's ✕.
     */
    public static void openAuction(AhFlipFeed.Flip flip) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !AUCTION_ID.matcher(flip.auctionId()).matches()) {
            return;
        }
        minecraft.player.closeContainer();
        minecraft.player.connection.sendCommand("viewauction " + flip.auctionId());
    }

    /** "heroic HYPERION ✪10 x2" -> "Heroic Hyperion ✪10 x2", whitelist-sanitized. */
    private static String displayName(String itemId, String reforge, int stars, int count) {
        StringBuilder out = new StringBuilder();
        String cleanReforge = DISPLAY_JUNK.matcher(reforge == null ? "" : reforge).replaceAll("");
        if (!cleanReforge.isEmpty()) {
            out.append(pretty(cleanReforge)).append(' ');
        }
        String cleanId = DISPLAY_JUNK.matcher(itemId == null ? "" : itemId).replaceAll("");
        out.append(cleanId.isEmpty() ? "Unknown Item" : pretty(cleanId));
        if (stars > 0) {
            out.append(" ✪").append(stars);
        }
        if (count > 1) {
            out.append(" x").append(count);
        }
        return out.length() > 80 ? out.substring(0, 80) : out.toString();
    }

    /** SNAKE_CASE / lowercase id fragments to Title Case words. */
    private static String pretty(String id) {
        String[] parts = id.replace(':', '_').split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /** Compact coin amounts: 8500000 -> "8.5M", 1200000000 -> "1.2B". */
    private static String coins(long value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }

    private static void show(Component line) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(line);
            }
        });
    }

    /**
     * Parses a response body to a JsonElement, or null if it is blank / malformed. Unlike
     * {@code gson.fromJson(body, JsonObject.class)} this never throws on a primitive or array body
     * (Gson 2.11 rejects that coercion) - the caller decides what a non-object body means.
     */
    private static JsonElement parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(body);
        } catch (JsonParseException malformed) {
            return null;
        }
    }

    /**
     * Reads the optional top-level {@code mayor} block ({@code {"available":true,"name":"Paul",
     * "effects":{...}}}) into the shared feed for the window header. Fully defensive: a missing
     * block, {@code available != true}, or a missing/blank name all clear the header - the name is
     * untrusted, so it is whitelist-sanitized before it is ever shown. {@code effects} is ignored.
     */
    private static void readMayor(JsonObject body) {
        try {
            JsonElement element = body.get("mayor");
            if (element == null || !element.isJsonObject()) {
                AhFlipFeed.getInstance().setMayorName(null);
                return;
            }
            JsonObject mayor = element.getAsJsonObject();
            boolean available = mayor.has("available") && mayor.get("available").isJsonPrimitive()
                    && mayor.get("available").getAsBoolean();
            String name = available && mayor.has("name") && mayor.get("name").isJsonPrimitive()
                    ? mayor.get("name").getAsString() : null;
            AhFlipFeed.getInstance().setMayorName(name != null ? sanitizeMayorName(name) : null);
        } catch (RuntimeException malformed) {
            AhFlipFeed.getInstance().setMayorName(null); // never let a bad mayor block break the poll
        }
    }

    /** Mayor name is untrusted display text: keep it short, letters/digits/space only. */
    private static String sanitizeMayorName(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9 ]", "").trim();
        if (clean.isEmpty()) {
            return null;
        }
        return clean.length() > 24 ? clean.substring(0, 24) : clean;
    }

    /** Server-supplied error text is untrusted: strip non-printable chars and clamp the length. */
    private static String sanitizeError(String text) {
        String clean = (text == null ? "" : text).replaceAll("[^\\x20-\\x7E]", "").trim();
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        return clean.isEmpty() ? "server error" : clean;
    }

    private static Component system(String text) {
        return SBSChat.prefix()
                .append(Component.literal(" [FLIP] ").withColor(0xFFC94D))
                .append(Component.literal(text).withColor(0xE0605F));
    }
}
