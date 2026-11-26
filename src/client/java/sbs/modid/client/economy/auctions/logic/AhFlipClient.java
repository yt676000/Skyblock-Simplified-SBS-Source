/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.auctions.ui.AhFlipPopups;
import sbs.modid.client.economy.localflips.logic.LocalFlipConnection;
import sbs.modid.client.economy.localflips.model.LocalFlip;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Client for the AH flip alerts: the flip algorithm on the SBS server
 * ({@code https://skyblocksimplified.info/flips/...}, see {@link FlipServer}) or, for development,
 * the same program on this PC.
 *
 * <p><b>Transport.</b> One {@link LocalFlipConnection}: {@code GET /flips/stream} (Server-Sent
 * Events: {@code sofort} = new find, {@code weg} = auction gone, a ping every 15 s) plus
 * {@code GET /flips/liste} every 30 s. A dropped stream reconnects after 3 s and the server replays
 * every current find, so finds are deduplicated by auction id. No {@code ?client=} on the stream:
 * that marks the Discord bot, and in-game clients are served first.
 *
 * <p><b>Auth.</b> The licence token as {@code X-SBS-Token}, never in the URL. A 401 is shown once
 * ("Flip server: token missing or wrong") and not retried until the token or the source changes.
 *
 * <p><b>Input handling.</b> Every find goes through {@link LocalFlip#parse}: the auction id must be
 * 32 hex characters and names are stripped of formatting codes, so a response can only ever produce
 * a {@code /viewauction} for a real id and no formatting or click events reach chat.
 */
public final class AhFlipClient {

    private static final AhFlipClient INSTANCE = new AhFlipClient();

    /** The server never sends flips below its own floor; mirror it for the range clamp. */

    /** Hypixel auction ids are exactly 32 lowercase hex chars - anything else is refused. */
    private static final Pattern AUCTION_ID = Pattern.compile("[0-9a-f]{32}");

    private Thread superviseThread;
    private Thread soldThread;

    /** Guarded by {@code this}. */
    private LocalFlipConnection connection;
    private String connectionKey;
    /** The settings a 401 was answered for: not reconnected until they change. */
    private volatile String rejectedKey;

    /**
     * How often the sold-watcher polls Hypixel's ended-auctions feed. That feed covers roughly the
     * last minute and refreshes about once a minute, so polling every 30s keeps consecutive windows
     * overlapping - a flip that sells is never missed between two polls.
     */
    private static final long SOLD_POLL_MS = 30_000L;

    /** Auction ids already shown, so a reconnect (the server replays everything) never repeats an alert. */
    private final Set<String> shown = new LinkedHashSet<>();
    private static final int SHOWN_CAP = 512;

    private AhFlipClient() {
    }

    public static AhFlipClient getInstance() {
        return INSTANCE;
    }

    /** Starts the supervisor + sold-watch threads once (both idle cheaply while the toggle is off). */
    public synchronized void start() {
        if (superviseThread != null) {
            return;
        }
        superviseThread = new Thread(this::superviseLoop, "SBS-FlipAlerts-Supervise");
        superviseThread.setDaemon(true);
        superviseThread.start();
        soldThread = new Thread(this::soldLoop, "SBS-FlipAlerts-Sold");
        soldThread.setDaemon(true);
        soldThread.start();
    }

    private static SBSConfig.AhFlipAlertSettings cfg() {
        return ConfigManager.getInstance().get().ahFlips;
    }

    private static boolean inWorld() {
        return Minecraft.getInstance().player != null;
    }

    // ------------------------------------------------------------------
    // Connection supervisor
    // ------------------------------------------------------------------

    /**
     * Every 2 s: opens the connection while the module is on and the player is in a world, closes it
     * otherwise, and replaces it when the source or the token changes. The connection does its own
     * reconnecting; this only decides whether one should exist.
     */
    private void superviseLoop() {
        while (true) {
            try {
                boolean local = FlipServer.isLocal();
                String key = FlipServer.key();
                boolean wanted = cfg().enabled && inWorld()
                        && (local || !FlipServer.token().isEmpty())
                        && !key.equals(rejectedKey);   // 401 for exactly these settings: wait for a change
                synchronized (this) {
                    if (connection != null
                            && (!wanted || !key.equals(connectionKey) || !connection.isRunning())) {
                        connection.stop();
                        connection = null;
                    }
                    if (wanted && connection == null) {
                        connectionKey = key;
                        connection = LocalFlipConnection.flipAlerts(FlipServer.base(), FlipServer.healthPath(),
                                local ? "" : FlipServer.token(), new Sink(key, local),
                                Minecraft.getInstance(), !local);
                        connection.start();
                    }
                }
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                sbs.modid.client.core.api.ApiFailure.logBackground("[SBS][Flips]", "supervise", e);
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** What the connection delivers, always on the client thread. */
    private final class Sink implements LocalFlipConnection.Sink {
        private final String key;
        private final boolean local;

        Sink(String key, boolean local) {
            this.key = key;
            this.local = local;
        }

        @Override
        public void onState(LocalFlipConnection.State state, long retryAtMs) {
            if (state == LocalFlipConnection.State.UNAUTHORIZED && !key.equals(rejectedKey)) {
                rejectedKey = key;
                show(system(local ? "Flip server (local): token missing or wrong"
                        : "Flip server: token missing or wrong"));
            }
        }

        @Override
        public void onSofort(LocalFlip flip) {
            deliver(flip, true);
        }

        @Override
        public void onWeg(String auction) {
            // Bought or gone: out of the popups (they drop dismissed ids) and the window at once.
            AhFlipFeed.getInstance().dismiss(auction);
        }

        @Override
        public void onAlive() {
        }

        @Override
        public void onList(List<LocalFlip> flips) {
            for (LocalFlip flip : flips) {
                deliver(flip, false);
            }
        }

        @Override
        public void onHealth(long apiLastUpdatedMs, double updatedSeconds) {
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
                    List<String> soldBinIds = new java.util.ArrayList<>();
                    List<Long> soldBinPrices = new java.util.ArrayList<>();
                    for (AhEndedAuctionsClient.Ended auction : ended.auctions) {
                        if (auction != null && auction.auction_id != null) {
                            soldIds.add(normalizeAuctionId(auction.auction_id));
                            // A BIN with a buyer is a completed sale at exactly that price.
                            if (auction.bin && auction.buyer != null && auction.price > 0) {
                                soldBinIds.add(auction.auction_id);
                                soldBinPrices.add(auction.price);
                            }
                        }
                    }
                    sbs.modid.client.economy.prices.BackupPrices.getInstance()
                            .observeEnded(soldBinIds, soldBinPrices);
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
                sbs.modid.client.core.api.ApiFailure.logBackground("[SBS][Flips]", "sold-watch", e);
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
    // Delivery (all server data already validated by LocalFlip.parse)
    // ------------------------------------------------------------------

    /**
     * Hands one find to the shared feed; {@code alert} (stream finds) also pops a card and a chat
     * line, list entries only fill the Flips window. HANG RISK finds are filtered by the server.
     */
    private void deliver(LocalFlip in, boolean alert) {
        String auctionId = in.auction();
        if (!AUCTION_ID.matcher(auctionId).matches() || !in.has(in.price())) {
            return;
        }
        long min = Math.max(0, cfg().minPrice); // client-side only; 0 = no lower bound
        long max = cfg().maxPrice > 0 ? Math.max(cfg().maxPrice, min) : 0;
        long price = in.price();
        long profit = in.has(in.netProfit()) ? in.netProfit() : in.has(in.profitNow()) ? in.profitNow() : 0;
        if (price < min || (max > 0 && price > max) || profit <= 0) {
            return;
        }
        if (AhFlipFeed.getInstance().isDismissed(auctionId)) {
            return; // gone or closed by the user - never resurrect it
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
        long target = in.has(in.target()) ? in.target() : price + profit;
        double pct = Double.isNaN(in.netPct()) ? profit * 100.0 / price : in.netPct();
        long weekSales = Double.isNaN(in.salesPerDay()) ? 0 : Math.round(in.salesPerDay() * 7);
        long endAt = Double.isNaN(in.endsMin()) ? 0
                : System.currentTimeMillis() + Math.round(in.endsMin() * 60_000);
        AhFlipFeed.Flip flip = new AhFlipFeed.Flip(auctionId, in.displayName(), price, target, profit,
                pct, weekSales, endAt, Math.max(0, in.safetyPct()), System.currentTimeMillis(), meta(in));
        AhFlipFeed.getInstance().add(flip);
        if (!alert) {
            return;
        }
        SBSConfig.AhFlipAlertSettings cfg = cfg();
        if (cfg.popupAlerts) {
            AhFlipPopups.getInstance().push(flip);
        }
        if (cfg.chatAlerts) {
            showChatLine(flip);
        }
    }

    /** {@code "conf 82% · BOT PREY 72%"} / {@code "quiet (30% bots)"} from the server's numbers ({@code p_success}, {@code bot_p}). */
    static String meta(LocalFlip flip) {
        StringBuilder out = new StringBuilder();
        int safety = flip.safetyPct();
        if (safety >= 0) {
            out.append("conf ").append(safety).append('%');
        }
        int bots = flip.botPct();
        if (bots >= 0) {
            if (out.length() > 0) {
                out.append(" · ");
            }
            out.append(flip.botPrey() ? "BOT PREY " + bots + "%" : "quiet (" + bots + "% bots)");
        }
        if (flip.auctionOnly()) {
            out.append(out.length() > 0 ? " · " : "").append("AUCTION ONLY");
        }
        return out.toString();
    }

    /** The chat output (optional, in addition to the popups): one line with a click-to-open link. */
    private static void showChatLine(AhFlipFeed.Flip flip) {
        // The click runs /viewauction built here from the id validated as 32 hex - the server never
        // supplies a command or a click event, only the id.
        String command = "/viewauction " + flip.auctionId();
        MutableComponent open = Component.literal(" [OPEN]").withColor(SBSChat.PREFIX_COLOR)
                .withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(command))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                Component.literal(command))));
        MutableComponent line = SBSChat.prefix()
                .append(Component.literal(" [FLIP]").withColor(0xFFC94D))
                .append(Component.literal(" " + flip.displayName()).withColor(SBSChat.WHITE))
                .append(Component.literal(" " + coins(flip.price())).withColor(0xFFC94D))
                .append(Component.literal(" → " + coins(flip.target())).withColor(0x8FA6BF))
                .append(Component.literal("  +" + coins(flip.profit())).withColor(0x57D977))
                .append(Component.literal(" (" + Math.round(flip.discountPct()) + "%"
                        + (flip.volumeLabel().isEmpty() ? "" : ", " + flip.volumeLabel()) + ")")
                        .withColor(0x8FA6BF))
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

    private static Component system(String text) {
        return SBSChat.prefix()
                .append(Component.literal(" [FLIP] ").withColor(0xFFC94D))
                .append(Component.literal(text).withColor(0xE0605F));
    }
}
