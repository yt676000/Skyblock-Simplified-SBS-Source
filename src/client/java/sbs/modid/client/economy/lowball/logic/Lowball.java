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
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.lowball.ui.LowballScreen;
import sbs.modid.client.economy.lowball.ui.LowballWindow;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Lowball (developer mode): appraises SkyBlock items through the local flip program's
 * {@code POST /lowball} and shows its value and offers. The mod prices nothing itself.
 *
 * <p>Read-only by design: it never sends a chat message, never trades and never buys. An offer is
 * copied to the clipboard or, at most, suggested as a {@code /msg} for the player to send.
 *
 * <p>Threads: items are read on the client thread (key press / command), the address is resolved
 * and checked on the SBS worker, the request goes out with {@code HttpClient.sendAsync}, and every
 * result is applied back on the client thread.
 */
public final class Lowball {

    private static final int MAX_ITEMS = 64;
    private static final int SINGLE_TIMEOUT_S = 5;
    private static final int TRADE_TIMEOUT_S = 15;
    public static final int DISPLAY_WINDOW = 0;
    public static final int DISPLAY_TOOLTIP = 1;
    public static final int DISPLAY_CHAT = 2;
    private static final long MIN_INTERVAL_MS = 1000L;
    private static final long CACHE_MS = 60_000L;
    private static final String TOKEN_HEADER = "X-SBS-Token";
    private static final int RED = 0xFF5555;
    private static final int GRAY = 0xAAAAAA;
    private static final int GOLD = 0xFFC94D;
    private static final int GREEN = 0x57D977;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)   // the program is local; a proxy would carry the token off
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .executor(SbsExecutors.worker())
            .build();

    // Client thread only.
    private static long lastRequestAt;
    private static final Map<String, Cached> CACHE = new HashMap<>();
    private static Slot tooltipSlot;
    private static ItemStack tooltipStack;
    private static List<Component> tooltipLines = List.of();

    private record Cached(JsonObject result, long at) {
    }

    private Lowball() {
    }

    public static SBSConfig.LowballSettings cfg() {
        return ConfigManager.getInstance().get().dev.lowball;
    }

    /** Developer mode and the toggle - the only state in which anything here acts or shows. */
    public static boolean active() {
        return sbs.modid.client.core.dev.DevMode.ACTIVE && cfg().enabled; // DEV-ONLY: maintainer's lowball tool
    }

    // ------------------------------------------------------------------
    // Triggers (client thread)
    // ------------------------------------------------------------------

    /**
     * A key press inside a container. Returns whether it was a Lowball key (and is consumed).
     */
    public static boolean onContainerKey(AbstractContainerScreen<?> screen, Slot hovered, int key, boolean shift) {
        if (!active() || key == 0) {
            return false;
        }
        SBSConfig.LowballSettings cfg = cfg();
        boolean shared = cfg.appraiseSlotKey == cfg.appraiseTradeKey;
        boolean tradeKey = key == cfg.appraiseTradeKey && (!shared || shift);
        boolean slotKey = key == cfg.appraiseSlotKey && (!shared || !shift);
        if (tradeKey && cfg.tradeAppraiseAll && isTrade(screen)) {
            appraiseTrade(screen);
            return true;
        }
        if (slotKey && hovered != null && hovered.hasItem()) {
            appraiseSlot(hovered);
            return true;
        }
        return false;
    }

    /** {@code /sbs lowball}: the item in the main hand. */
    public static void appraiseHeld() {
        if (!active()) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        JsonObject item = LowballItems.read(player.getMainHandItem(), "hand");
        if (item == null) {
            say(Component.literal("Lowball: the held item is not a SkyBlock item.").withColor(GRAY));
            return;
        }
        single(item, null, null, "hand");
    }

    /** {@code /sbs lowball last}: reopens the window on the last appraisal. */
    public static void reopenLast() {
        if (!active()) {
            return;
        }
        LowballWindow window = LowballWindow.getInstance();
        if (!window.hasContent()) {
            say(Component.literal("Lowball: nothing appraised yet.").withColor(GRAY));
            return;
        }
        window.reopen();
        showWindowHost();
    }

    /**
     * A clicked offer: copied, with "copied" on the action bar - or, with "Suggest /msg" on and a
     * trade partner known, the /msg is put into the chat box for the player to send. Never sent.
     *
     * <p>Inside a container (the trade window) the chat box cannot open without closing the
     * container, and closing /trade cancels the trade; there the /msg text is copied instead.
     */
    public static void offerClicked(String text, String partner) {
        if (cfg().suggestMsg && partner != null) {
            String msg = "/msg " + partner + " " + text + "?";
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.gui.screen() instanceof AbstractContainerScreen<?>) {
                copy(msg);
            } else {
                minecraft.setScreenAndShow(new net.minecraft.client.gui.screens.ChatScreen(msg, false));
            }
            return;
        }
        copy(text);
    }

    /** What clicking an offer does, for its hover text. */
    public static String offerActionText(String text, String partner) {
        return cfg().suggestMsg && partner != null
                ? "suggest /msg " + partner + " " + text + "?" : "copy " + text;
    }

    /** {@code /sbs lowball copy <text>}: what a clicked offer runs. Copies and says so on the action bar. */
    public static void copy(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.keyboardHandler.setClipboard(text.trim());
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.literal("Lowball: copied " + text.trim()).withColor(GREEN));
        }
    }

    private static void appraiseSlot(Slot slot) {
        JsonObject item = LowballItems.read(slot.getItem(), "slot " + slot.index);
        if (item == null) {
            say(Component.literal("Lowball: not a SkyBlock item.").withColor(GRAY));
            return;
        }
        single(item, slot, slot.getItem(), "slot");
    }

    private static void single(JsonObject item, Slot slot, ItemStack stack, String src) {
        String key = LowballItems.key(item);
        Cached cached = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < CACHE_MS) {
            showSingle(cached.result(), slot, stack, src);
            return;
        }
        if (!rateOk(now)) {
            return;
        }
        JsonArray items = new JsonArray();
        items.add(item);
        send(items, src, null, SINGLE_TIMEOUT_S, answer -> {
            JsonObject result = firstResult(answer);
            if (result == null) {
                say(Component.literal("Lowball: the program sent no result.").withColor(GRAY));
                return;
            }
            JsonObject one = alone(answer, result);
            CACHE.put(key, new Cached(one, System.currentTimeMillis()));
            showSingle(one, slot, stack, src);
        }, false);
    }

    private static void appraiseTrade(AbstractContainerScreen<?> screen) {
        JsonArray items = new JsonArray();
        Map<String, String> keys = new HashMap<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || !slot.hasItem()) {
                continue;
            }
            if (slot.getContainerSlot() % 9 < 5) {
                continue;   // left half and the divider: our own side
            }
            JsonObject item = LowballItems.read(slot.getItem(), "slot " + slot.index);
            if (item != null && items.size() < MAX_ITEMS) {
                items.add(item);
                keys.put("slot " + slot.index, LowballItems.key(item));
            }
        }
        if (items.isEmpty()) {
            say(Component.literal("Lowball: nothing to appraise on the other side.").withColor(GRAY));
            return;
        }
        if (!rateOk(System.currentTimeMillis())) {
            return;
        }
        String partner = tradePartner(screen);
        send(items, "trade", partner, TRADE_TIMEOUT_S, answer -> {
            long now = System.currentTimeMillis();
            if (answer.get("results") instanceof JsonArray results) {
                for (JsonElement e : results) {
                    if (e instanceof JsonObject r && keys.containsKey(LowballFormat.str(r, "ref"))) {
                        CACHE.put(keys.get(LowballFormat.str(r, "ref")), new Cached(alone(answer, r), now));
                    }
                }
            }
            if (cfg().display == DISPLAY_WINDOW) {
                LowballWindow.getInstance().show(LowballView.parse(answer, "trade", partner));
                showWindowHost();
            } else {
                showTrade(answer, partner);
            }
        }, false);
    }

    private static boolean rateOk(long now) {
        if (now - lastRequestAt < MIN_INTERVAL_MS) {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.sendOverlayMessage(Component.literal("Lowball: one request per second").withColor(GRAY));
            }
            return false;
        }
        lastRequestAt = now;
        return true;
    }

    // ------------------------------------------------------------------
    // Request
    // ------------------------------------------------------------------

    private interface Answer {
        void accept(JsonObject body);
    }

    private static void send(JsonArray items, String src, String partner, int timeoutS, Answer onAnswer,
                             boolean isRetry) {
        SBSConfig.LowballSettings cfg = cfg();
        boolean vpn = sbs.modid.client.economy.localflips.logic.FlipSource.isVpn();
        URI base = sbs.modid.client.economy.localflips.logic.FlipSource.base(cfg.url);
        if (base == null && vpn) {
            say(Component.literal("Lowball: " + sbs.modid.client.economy.localflips.logic.FlipSource.NEED_IP)
                    .withColor(RED));
            return;
        }
        if (base == null) {
            say(Component.literal("Lowball: the Server URL is not a valid http address.").withColor(RED));
            return;
        }
        String token = cfg.token == null ? "" : cfg.token.trim();
        if (token.isEmpty()) {
            String shared = ConfigManager.getInstance().get().dev.localFlips.token;
            token = shared == null ? "" : shared.trim();
        }
        JsonObject body = new JsonObject();
        JsonArray discounts = new JsonArray();
        for (int i = 0; i < 4; i++) {
            int pct = discountPct(i);
            if (pct > 0) {
                discounts.add(pct / 100.0);
            }
        }
        if (!discounts.isEmpty()) {
            body.add("discounts", discounts);   // none active: the program uses its own four
        }
        body.add("items", items);
        body.addProperty("src", src);
        if (partner != null) {
            body.addProperty("partner", partner);
        }
        String json = body.toString();
        String finalToken = token;
        Minecraft client = Minecraft.getInstance();

        // DATA SENT: the appraised items' SkyBlock data (custom_data as JSON, name, count, dye colour),
        //   the active offer discounts (up to four), and the player's own token for that program as X-SBS-Token when set.
        //   No Minecraft account data, no SBS licence token.
        // DATA RECEIVED: read-only JSON - values and suggested offers for those items.
        // SAFETY DECLARATION: local or LAN address only - or, in the "vpn" flip source mode, the
        //   Radmin VPN IP the player typed (IPv4 in 26.0.0.0/8 only) (checked after resolving, before connecting);
        //   no proxy, no redirects. Nothing is sent to Hypixel or anywhere else.
        CompletableFuture.supplyAsync(() -> {
                    try {
                        if (!sbs.modid.client.economy.localflips.logic.FlipSource.allowed(
                                InetAddress.getByName(base.getHost()), vpn)) {
                            throw new IllegalStateException("not a local address");
                        }
                    } catch (java.net.UnknownHostException unknown) {
                        throw new CompletionException(unknown);
                    }
                    HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve("/lowball"))
                            .timeout(Duration.ofSeconds(timeoutS))
                            .header("Content-Type", "application/json")
                            .header("User-Agent", "SBS-Lowball")
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
                    if (!finalToken.isEmpty()) {
                        request.header(TOKEN_HEADER, finalToken);
                    }
                    return request.build();
                }, SbsExecutors.worker())
                .thenCompose(request -> HTTP.sendAsync(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))
                .whenComplete((response, error) -> client.execute(() -> {
                    if (error != null) {
                        failed(error);
                        return;
                    }
                    handle(response.statusCode(), response.body(), items, src, partner, timeoutS, onAnswer, isRetry);
                }));
    }

    private static void handle(int status, String text, JsonArray items, String src, String partner,
                               int timeoutS, Answer onAnswer, boolean isRetry) {
        switch (status) {
            case 200 -> {
                JsonObject body;
                try {
                    body = JsonParser.parseString(text).getAsJsonObject();
                } catch (RuntimeException malformed) {
                    say(Component.literal("Lowball: the program sent malformed JSON.").withColor(RED));
                    return;
                }
                onAnswer.accept(body);
            }
            case 503 -> {
                if (isRetry) {
                    say(Component.literal("Lowball: model still loading, try again in a few seconds.").withColor(GRAY));
                    return;
                }
                say(Component.literal("Lowball: model loading, retry in a few seconds.").withColor(GRAY));
                CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS, SbsExecutors.worker())
                        .execute(() -> Minecraft.getInstance().execute(() -> {
                            if (active()) {
                                send(items, src, partner, timeoutS, onAnswer, true);
                            }
                        }));
            }
            case 401 -> say(Component.literal("Lowball: Token wrong or missing (401).").withColor(RED));
            case 400 -> say(Component.literal("Lowball: " + errorText(text)).withColor(RED));
            default -> say(Component.literal("Lowball: the program answered HTTP " + status + ".").withColor(RED));
        }
    }

    private static void failed(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ConnectException) {
            say(Component.literal("Lowball: start the fast mode in the flip overlay.").withColor(RED));
        } else if (cause instanceof java.net.http.HttpTimeoutException) {
            say(Component.literal("Lowball: the program did not answer in time.").withColor(RED));
        } else if (cause instanceof IllegalStateException) {
            say(Component.literal("Lowball: the Server URL is not on this PC or the LAN - refused.").withColor(RED));
        } else {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Lowball] request failed: {}", cause.toString());
            say(Component.literal("Lowball: request failed (" + cause.getClass().getSimpleName() + ").")
                    .withColor(RED));
        }
    }

    private static String errorText(String text) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            String error = LowballFormat.str(o, "error");
            if (error != null) {
                return error;
            }
        } catch (RuntimeException notJson) {
            // fall through to the raw text
        }
        String raw = text == null ? "" : text.strip();
        return raw.length() > 200 ? raw.substring(0, 200) : raw.isEmpty() ? "bad request" : raw;
    }

    private static JsonObject firstResult(JsonObject answer) {
        if (answer.get("results") instanceof JsonArray results && !results.isEmpty()
                && results.get(0) instanceof JsonObject first) {
            return first;
        }
        return null;
    }

    private static int clampPct(int pct) {
        return Math.max(0, Math.min(50, pct));
    }

    private static final int[] DEFAULT_DISCOUNTS = {10, 15, 20, 25};

    /** Offer discount {@code index} (0-3) in percent, 0 = off; tolerant of a short saved array. */
    public static int discountPct(int index) {
        int[] saved = cfg().offerDiscounts;
        return clampPct(saved != null && index < saved.length ? saved[index] : DEFAULT_DISCOUNTS[index]);
    }

    public static void setDiscountPct(int index, int pct) {
        SBSConfig.LowballSettings cfg = cfg();
        if (cfg.offerDiscounts == null || cfg.offerDiscounts.length < 4) {
            int[] grown = DEFAULT_DISCOUNTS.clone();
            if (cfg.offerDiscounts != null) {
                System.arraycopy(cfg.offerDiscounts, 0, grown, 0, cfg.offerDiscounts.length);
            }
            cfg.offerDiscounts = grown;
        }
        cfg.offerDiscounts[index] = clampPct(pct);
    }

    // ------------------------------------------------------------------
    // Display (client thread)
    // ------------------------------------------------------------------

    private static void showSingle(JsonObject answer, Slot slot, ItemStack stack, String src) {
        JsonObject json = firstResult(answer);
        if (json == null) {
            return;
        }
        int display = cfg().display;
        String partner = Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen
                && isTrade(screen) ? tradePartner(screen) : null;
        if (display == DISPLAY_WINDOW) {
            LowballWindow.getInstance().show(LowballView.parse(answer, src, partner));
            showWindowHost();
            return;
        }
        LowballFormat.Result r = LowballFormat.parse(json);
        if (display == DISPLAY_TOOLTIP && slot != null && slot.getItem() == stack) {
            tooltipSlot = slot;
            tooltipStack = stack;
            tooltipLines = tooltipLines(r);
            return;
        }
        for (MutableComponent line : chatLines(r, partner, true)) {
            say(line);
        }
    }

    /** With no screen open the window needs a host; inside a container it draws as a floating window. */
    private static void showWindowHost() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() == null) {
            minecraft.setScreenAndShow(new LowballScreen());
        }
    }

    /** One result with the answer's data stamp ({@code dump}, {@code t}), for the cache. */
    private static JsonObject alone(JsonObject answer, JsonObject result) {
        JsonObject one = new JsonObject();
        JsonArray results = new JsonArray();
        results.add(result);
        one.add("results", results);
        for (String key : List.of("dump", "t")) {
            if (answer.has(key)) {
                one.add(key, answer.get(key));
            }
        }
        return one;
    }

    private static void showTrade(JsonObject answer, String partner) {
        say(Component.literal("Lowball - trade appraisal").withColor(GOLD));
        if (answer.get("results") instanceof JsonArray results) {
            for (JsonElement e : results) {
                if (e instanceof JsonObject r) {
                    for (MutableComponent line : chatLines(LowballFormat.parse(r), partner, false)) {
                        say(line);
                    }
                }
            }
        }
        if (answer.get("summary") instanceof JsonObject summary) {
            List<LowballFormat.Offer> offers = LowballFormat.offers(summary.get("offers"));
            if (!offers.isEmpty()) {
                MutableComponent total = Component.literal("Offer for all: ").withColor(GOLD);
                for (int i = 0; i < offers.size(); i++) {
                    if (i > 0) {
                        total.append(Component.literal(" – ").withColor(GRAY));
                    }
                    total.append(offerLink(offers.get(i), partner));
                }
                say(total);
            }
        }
    }

    private static List<Component> tooltipLines(LowballFormat.Result r) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Lowball ").withColor(GOLD).append(Component.literal(
                sbs.modid.client.economy.localflips.logic.FlipSource.tag()).withColor(GRAY)));
        if (!r.ok()) {
            lines.add(Component.literal(r.name() + ": " + r.error()).withColor(GRAY));
            return List.copyOf(lines);
        }
        for (String warning : r.warnings()) {
            lines.add(Component.literal(warning).withColor(RED));
        }
        lines.add(Component.literal(LowballFormat.sells(r)).withColor(GOLD));
        if (r.recommended() != null) {
            lines.add(Component.literal(r.recommended()).withColor(GREEN));
        }
        if (!r.offers().isEmpty()) {
            StringBuilder offer = new StringBuilder("Offer: ");
            for (int i = 0; i < r.offers().size(); i++) {
                LowballFormat.Offer o = r.offers().get(i);
                offer.append(i > 0 ? " / " : "").append(o.text())
                        .append(" (").append(LowballFormat.discount(o.discount())).append(')');
            }
            lines.add(Component.literal(offer.toString()).withColor(GREEN));
            String profits = LowballFormat.profits(r.offers());
            if (profits != null) {
                lines.add(Component.literal("Profit: " + profits).withColor(GRAY));
            }
        }
        return List.copyOf(lines);
    }

    private static List<MutableComponent> chatLines(LowballFormat.Result r, String partner, boolean single) {
        List<MutableComponent> lines = new ArrayList<>();
        if (!r.ok()) {
            lines.add(Component.literal(r.name() + ": " + r.error()).withColor(GRAY));
            return lines;
        }
        MutableComponent line = Component.literal(single ? "" : r.name() + ": ").withColor(SBSChat.WHITE);
        for (String warning : r.warnings()) {
            line.append(Component.literal(warning + " ").withColor(RED));
        }
        line.append(Component.literal(LowballFormat.sells(r)).withColor(GOLD));
        if (r.recommended() != null) {
            line.append(Component.literal(" · " + r.recommended()).withColor(GREEN));
        }
        if (!r.offers().isEmpty()) {
            line.append(Component.literal(" · Offer: ").withColor(GRAY));
            for (int i = 0; i < r.offers().size(); i++) {
                LowballFormat.Offer o = r.offers().get(i);
                if (i > 0) {
                    line.append(Component.literal(" / ").withColor(GRAY));
                }
                line.append(offerLink(o, partner))
                        .append(Component.literal(" (" + LowballFormat.discount(o.discount()) + ")").withColor(GRAY));
            }
            String profits = LowballFormat.profits(r.offers());
            if (profits != null) {
                line.append(Component.literal(" · Profit: " + profits).withColor(GRAY));
            }
        }
        lines.add(line);
        return lines;
    }

    /** An offer the player can click: copy it, or (when enabled) suggest a /msg - never send it. */
    private static MutableComponent offerLink(LowballFormat.Offer offer, String partner) {
        String text = offer.text();
        boolean suggest = cfg().suggestMsg && partner != null;
        ClickEvent click = suggest
                ? new ClickEvent.SuggestCommand("/msg " + partner + " " + text + "?")
                : new ClickEvent.RunCommand("/sbs lowball copy " + text);
        String hover = suggest ? "Puts /msg " + partner + " " + text + "? into the chat box" : "Copies " + text;
        return Component.literal(text).withColor(GREEN).withStyle(style -> style
                .withUnderlined(true)
                .withClickEvent(click)
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static void say(Component body) {
        SBSChat.send(body);
    }

    // ------------------------------------------------------------------
    // Tooltip (HOT: only reads the prepared lines)
    // ------------------------------------------------------------------

    /** Per frame from the container render: the lines go away once the slot is left. */
    public static void onHover(Slot hovered) {
        if (tooltipSlot != null && hovered != tooltipSlot) {
            tooltipSlot = null;
            tooltipStack = null;
            tooltipLines = List.of();
        }
    }

    /** The tooltip with the Lowball lines appended for the appraised stack; the same list otherwise. */
    public static List<Component> decorate(List<Component> lines, ItemStack stack) {
        if (tooltipStack == null || stack != tooltipStack || lines == null || !active()) {
            return lines;
        }
        List<Component> out = new ArrayList<>(lines.size() + tooltipLines.size());
        out.addAll(lines);
        out.addAll(tooltipLines);
        return out;
    }

    // ------------------------------------------------------------------
    // Trade window
    // ------------------------------------------------------------------

    /** Hypixel's /trade: a chest whose title starts with "You". */
    static boolean isTrade(AbstractContainerScreen<?> screen) {
        return screen.getTitle().getString().startsWith("You");
    }

    /** The other player's name: the last word of "You            Name". */
    static String tradePartner(AbstractContainerScreen<?> screen) {
        String title = screen.getTitle().getString().trim();
        String[] words = title.split("\\s+");
        if (words.length < 2) {
            return null;
        }
        String name = words[words.length - 1];
        return name.matches("[A-Za-z0-9_]{1,16}") && !name.toLowerCase(Locale.ROOT).equals("you") ? name : null;
    }
}
