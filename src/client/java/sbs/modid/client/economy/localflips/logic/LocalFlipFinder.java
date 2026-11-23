/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.audio.SbsSounds;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.localflips.model.LocalFlip;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Local Flip Finder's state and lifecycle: which connection runs, what it has delivered, and
 * what the player has hidden. Lives on the client thread; the connection hands everything here
 * through {@code Minecraft.execute}, so a SOFORT find is stored, alerted and drawn in one tick.
 *
 * <p><b>Developer mode only.</b> This is a tool for a flip program the maintainer runs locally, not a
 * player feature: with developer mode off nothing connects, and the settings are on the Developer
 * page that only exists in developer mode.
 *
 * <p><b>Why it is allowed.</b> The finder shows offers and opens one auction when the player presses
 * a button, clicks a chat link or a key - the same {@code /viewauction} the player could type, one per
 * press. It never buys, bids or clicks inside the auction window; the purchase is the player's own
 * click, at the player's own speed. That is the same line the AH flip alerts already draw.
 *
 * <p><b>Lifecycle.</b> {@link #tick} compares the wanted connection (dev mode, enabled, address,
 * token) with the running one and restarts it when they differ - after a short settle time, so
 * typing an address in the settings does not open a connection per keystroke. A 401 or a refused
 * address ends the connection's thread and nothing retries until the settings change.
 */
public final class LocalFlipFinder implements LocalFlipConnection.Sink {

    private static final LocalFlipFinder INSTANCE = new LocalFlipFinder();

    /** SOFORT finds leave the window this long after they were found. */
    public static final long SOFORT_LIFETIME_MS = 10 * 60_000L;
    /** A gone entry stays visible, greyed, for this long. */
    public static final long GONE_VISIBLE_MS = 5_000L;
    /** How long a typed address must be stable before it is connected to. */
    private static final long SETTLE_MS = 800L;
    /** The Hypixel auction data is considered stuck past this age. */
    public static final long API_STUCK_MS = 150_000L;

    /** What the window's status line says. */
    public enum Status {
        OFF, CONNECTING, CONNECTED, DISCONNECTED, UNAUTHORIZED, INVALID_ADDRESS
    }

    /** One SOFORT find and when it reached the client. */
    public record Entry(LocalFlip flip, long receivedMs) {
    }

    private final AlertGate gate = new AlertGate();
    private final Map<String, Entry> sofort = new LinkedHashMap<>();
    private List<LocalFlip> liste = List.of();
    private final Map<String, Long> goneAt = new HashMap<>();
    private final Set<String> hidden = new HashSet<>();

    private Status status = Status.OFF;
    private long retryAtMs;
    private long lastSignalMs;
    private long apiLastUpdatedMs = -1;
    private int revision;
    private boolean downLogged;
    /** VPN mode only: "lost" was said in chat, so the next connect says "reconnected" once. */
    private boolean lostAnnounced;
    private boolean vpn;

    private LocalFlipConnection connection;
    private String appliedKey;
    private String pendingKey;
    private long pendingSinceMs;
    private int housekeeping;

    private LocalFlipFinder() {
    }

    public static LocalFlipFinder getInstance() {
        return INSTANCE;
    }

    public static SBSConfig.LocalFlipSettings cfg() {
        return ConfigManager.getInstance().get().dev.localFlips;
    }

    /** True while developer mode is on - the only state in which this feature exists. */
    public static boolean available() {
        return sbs.modid.client.core.dev.DevMode.ACTIVE; // DEV-ONLY: maintainer's local flip tool
    }

    // ------------------------------------------------------------------
    // Lifecycle (client tick)
    // ------------------------------------------------------------------

    /** Client tick: start, restart or stop the connection; drop expired entries once a second. */
    public void tick() {
        SBSConfig.LocalFlipSettings cfg = cfg();
        if (!available() || !cfg.enabled) {
            if (connection != null || status != Status.OFF) {
                stopConnection();
                setStatus(Status.OFF, 0);
            }
            return;
        }
        String key = connectionKey(cfg);
        long now = System.currentTimeMillis();
        if (appliedKey == null) {
            apply(cfg);
        } else if (!key.equals(appliedKey)) {
            if (!key.equals(pendingKey)) {
                pendingKey = key;
                pendingSinceMs = now;
            } else if (now - pendingSinceMs >= SETTLE_MS) {
                apply(cfg);
            }
        }
        if (++housekeeping >= 20) {
            housekeeping = 0;
            prune(now);
        }
    }

    /** Rebuilds the connection now from the saved settings (the window's Connect button). */
    public void reconnectNow() {
        stopConnection();
        if (available() && cfg().enabled) {
            apply(cfg());
        }
    }

    private void apply(SBSConfig.LocalFlipSettings cfg) {
        stopConnection();
        appliedKey = connectionKey(cfg);
        pendingKey = null;
        downLogged = false;
        lostAnnounced = false;
        vpn = FlipSource.isVpn();
        URI base = FlipSource.base(cfg.url);
        if (base == null) {
            setStatus(Status.INVALID_ADDRESS, 0);
            if (vpn) {
                SBSChat.send(FlipSource.NEED_IP);
            }
            return;
        }
        connection = new LocalFlipConnection(base, cfg.token, this, Minecraft.getInstance(), vpn);
        setStatus(Status.CONNECTING, 0);
        connection.start();
    }

    /** Everything whose change must restart the connection. */
    private static String connectionKey(SBSConfig.LocalFlipSettings cfg) {
        return cfg.url + '\n' + cfg.token + '\n' + FlipSource.key();
    }

    private void stopConnection() {
        if (connection != null) {
            connection.stop();
            connection = null;
        }
        appliedKey = null;
        pendingKey = null;
    }

    // ------------------------------------------------------------------
    // Sink: everything below runs on the client thread
    // ------------------------------------------------------------------

    @Override
    public void onState(LocalFlipConnection.State state, long retryAt) {
        switch (state) {
            case CONNECTING -> setStatus(Status.CONNECTING, 0);
            case CONNECTED -> {
                lastSignalMs = System.currentTimeMillis();
                setStatus(Status.CONNECTED, 0);
            }
            case DISCONNECTED -> setStatus(Status.DISCONNECTED, retryAt);
            case UNAUTHORIZED -> setStatus(Status.UNAUTHORIZED, 0);
            case INVALID_ADDRESS -> setStatus(Status.INVALID_ADDRESS, 0);
        }
    }

    @Override
    public void onSofort(LocalFlip flip) {
        long now = System.currentTimeMillis();
        lastSignalMs = now;
        goneAt.remove(flip.auction());
        sofort.remove(flip.auction());
        sofort.put(flip.auction(), new Entry(flip, now));
        revision++;
        alert(flip, now);
    }

    @Override
    public void onWeg(String auction) {
        long now = System.currentTimeMillis();
        lastSignalMs = now;
        goneAt.put(auction, now);
        revision++;
    }

    @Override
    public void onAlive() {
        lastSignalMs = System.currentTimeMillis();
    }

    @Override
    public void onList(List<LocalFlip> flips) {
        liste = flips;
        revision++;
    }

    @Override
    public void onHealth(long apiLastUpdated, double updatedSeconds) {
        if (apiLastUpdated > 0) {
            apiLastUpdatedMs = apiLastUpdated;
            revision++;
        }
    }

    /**
     * One log line per state change that matters: connected, the first failure of a run, a wrong
     * token, a refused address. Retries after the first failure are silent.
     */
    private void setStatus(Status next, long retryAt) {
        Status previous = status;
        status = next;
        retryAtMs = retryAt;
        if (previous != next) {
            revision++;
        }
        switch (next) {
            case CONNECTED -> {
                if (previous != Status.CONNECTED) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][LocalFlips] connected to the flip program");
                    if (vpn && lostAnnounced) {
                        SBSChat.send("[VPN] Flip source reconnected.");
                    }
                }
                lostAnnounced = false;
                downLogged = false;
            }
            case DISCONNECTED -> {
                if (!downLogged) {
                    downLogged = true;
                    if (vpn && !lostAnnounced) {
                        lostAnnounced = true;
                        SBSChat.send("[VPN] Flip source lost - reconnecting quietly.");
                    }
                    SkyblockSimplifiedSBS.LOGGER.info(
                            "[SBS][LocalFlips] flip program not reachable - retrying quietly");
                }
            }
            case UNAUTHORIZED -> {
                if (previous != next) {
                    SkyblockSimplifiedSBS.LOGGER.warn(
                            "[SBS][LocalFlips] the flip program refused the token (401) - waiting for a settings change");
                    if (vpn) {
                        SBSChat.send("[VPN] Token wrong or missing (401) - set it with /sbs flips token <token>.");
                    }
                }
            }
            case INVALID_ADDRESS -> {
                if (previous != next) {
                    SkyblockSimplifiedSBS.LOGGER.warn(
                            "[SBS][LocalFlips] address refused: not an http(s) address on this PC or the LAN");
                }
            }
            default -> {
            }
        }
    }

    /** Drops SOFORT finds older than ten minutes and gone entries past their grey period. */
    private void prune(long now) {
        boolean changed = false;
        double nowSeconds = now / 1000.0;
        Iterator<Entry> it = sofort.values().iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            Long gone = goneAt.get(entry.flip().auction());
            double foundAt = entry.flip().foundAt();
            boolean old = Double.isNaN(foundAt)
                    ? now - entry.receivedMs() > SOFORT_LIFETIME_MS
                    : (nowSeconds - foundAt) * 1000 > SOFORT_LIFETIME_MS;
            if (old || (gone != null && now - gone > GONE_VISIBLE_MS)) {
                it.remove();
                changed = true;
            }
        }
        // Gone marks outlive the entry so a list refresh that still carries it cannot bring it back.
        changed |= goneAt.values().removeIf(at -> now - at > SOFORT_LIFETIME_MS);
        if (changed) {
            revision++;
        }
    }

    // ------------------------------------------------------------------
    // Alerts
    // ------------------------------------------------------------------

    private void alert(LocalFlip flip, long nowMs) {
        SBSConfig.LocalFlipSettings cfg = cfg();
        if (!gate.shouldAlert(flip, cfg.minProfit, cfg.minSafety, nowMs / 1000.0)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (cfg.chatAlerts && minecraft.player != null) {
            minecraft.player.sendSystemMessage(chatLine(flip));
        }
        if (cfg.sound) {
            SbsSounds.play(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.6f);
        }
        if (cfg.toast) {
            SystemToast.addOrUpdate(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal("SOFORT: " + flip.displayName()),
                    Component.literal(FlipText.coins(flip.price()) + " → " + FlipText.coins(flip.target())
                            + "  +" + FlipText.coins(flip.netProfit())));
        }
    }

    /**
     * {@code [SBS-Flip] Cropie Talisman  7.00M → 9.00M  +1.82M (26 %)  100 %  [OPEN] [COPY]}. The
     * hover carries the normal and patient targets, the sell time and the note. Both links are built
     * from the validated auction id, never from text in the payload.
     */
    static MutableComponent chatLine(LocalFlip flip) {
        String slashCommand = "/" + flip.command();
        Component hover = Component.literal(hoverText(flip));
        MutableComponent line = Component.literal("[SBS-Flip] ").withColor(SBSChat.PREFIX_COLOR)
                .append(Component.literal(FlipSource.tag() + " ").withColor(0x8FA6BF))
                .append(Component.literal(flip.displayName())
                        .withColor(SBSChat.WHITE)
                        .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover))))
                .append(Component.literal("  " + FlipText.coins(flip.price()) + " → "
                        + FlipText.coins(flip.target())).withColor(0xFFC94D))
                .append(Component.literal("  +" + FlipText.coins(flip.netProfit())
                        + " (" + FlipText.pct(flip.netPct()) + ")").withColor(0x57D977));
        int safety = flip.safetyPct();
        if (safety >= 0) {
            line.append(Component.literal("  " + safety + " %").withColor(safetyColour(safety)));
        }
        line.append(Component.literal("  "))
                .append(Component.literal("[OPEN]").withColor(0x57D977).withStyle(style -> style
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(slashCommand))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Öffnet " + slashCommand)))))
                .append(Component.literal(" "))
                .append(Component.literal("[COPY]").withColor(0x8FA6BF).withStyle(style -> style
                        .withClickEvent(new ClickEvent.CopyToClipboard(slashCommand))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Kopiert " + slashCommand)))));
        return line;
    }

    private static String hoverText(LocalFlip flip) {
        StringBuilder text = new StringBuilder(flip.displayName());
        if (flip.has(flip.priceNormal())) {
            text.append("\nNormal: ").append(FlipText.coins(flip.priceNormal()))
                    .append(" (").append(FlipText.hours(flip.timeNormalH())).append(')');
        }
        if (flip.has(flip.pricePatient())) {
            text.append("\nGeduldig: ").append(FlipText.coins(flip.pricePatient()))
                    .append(" (").append(FlipText.hours(flip.timePatientH())).append(')');
        }
        if (!Double.isNaN(flip.sellTimeH())) {
            text.append("\nVerkauf in ~").append(FlipText.hours(flip.sellTimeH()));
        }
        for (String part : noteParts(flip.note())) {
            text.append('\n').append(part);
        }
        return text.toString();
    }

    /** The note's hints, one per entry ({@code "; "}-separated in the payload). */
    public static List<String> noteParts(String note) {
        List<String> parts = new ArrayList<>();
        if (note == null || note.isBlank()) {
            return parts;
        }
        for (String part : note.split("; ")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    /** Green from 90 %, yellow from 75 %, orange below. */
    public static int safetyColour(int pct) {
        return pct >= 90 ? 0x57D977 : pct >= 75 ? 0xFFD64D : 0xFF9A3C;
    }

    // ------------------------------------------------------------------
    // Player actions
    // ------------------------------------------------------------------

    /**
     * Opens the auction: one {@code /viewauction}, sent through the connection rather than typed
     * into the chat field. A container the player has open is closed first, as the other auction
     * links do. Nothing is clicked in the auction window that opens.
     */
    public static void openAuction(LocalFlip flip) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        if (sbs.modid.client.core.api.ScreenAccess.current() instanceof AbstractContainerScreen<?>) {
            minecraft.player.closeContainer();
        }
        minecraft.player.connection.sendCommand(flip.command());
    }

    /** Copies {@code /viewauction <id>} to the clipboard. */
    public static void copyCommand(LocalFlip flip) {
        Minecraft.getInstance().keyboardHandler.setClipboard("/" + flip.command());
    }

    /** The "Open newest find" key: the newest SOFORT find that is neither gone nor hidden. */
    public void openNewest() {
        Entry newest = null;
        for (Entry entry : sofort.values()) {
            if (isGone(entry.flip().auction()) || hidden.contains(entry.flip().auction())) {
                continue;
            }
            if (newest == null || foundOrReceived(entry) >= foundOrReceived(newest)) {
                newest = entry;
            }
        }
        if (newest == null) {
            SBSChat.send("Lokaler Flip-Finder: kein gültiger SOFORT-Fund.");
            return;
        }
        openAuction(newest.flip());
    }

    /** Hides an entry for this session. */
    public void hide(String auction) {
        if (hidden.add(auction)) {
            revision++;
        }
    }

    // ------------------------------------------------------------------
    // Test connection (settings button)
    // ------------------------------------------------------------------

    /**
     * {@code GET /health} once, off the client thread, and reports the answer, its round-trip time
     * and whether a token is required in chat. Works whether or not the feature is switched on.
     */
    public static void testConnection() {
        SBSConfig.LocalFlipSettings cfg = cfg();
        boolean vpn = FlipSource.isVpn();
        URI base = FlipSource.base(cfg.url);
        if (base == null) {
            SBSChat.send(vpn ? FlipSource.NEED_IP : "Lokaler Flip-Finder: ungültige Adresse.");
            return;
        }
        String token = cfg.token == null ? "" : cfg.token.trim();
        Minecraft minecraft = Minecraft.getInstance();
        Thread test = new Thread(() -> {
            String message;
            try {
                LocalFlipConnection.Health health = LocalFlipConnection.health(base, token, vpn);
                LocalFlipConnection.probeSofort(base, token, vpn);
                message = (vpn ? "Connected via VPN" : "Connected (local)")
                        + " - " + health.millis() + " ms"
                        + (health.apiLastUpdatedMs() > 0
                        ? ", AH data " + FlipText.ago((System.currentTimeMillis()
                        - health.apiLastUpdatedMs()) / 1000) + " old" : "")
                        + ".";
            } catch (LocalFlipConnection.Unauthorized unauthorized) {
                message = "Token wrong or missing (401)";
            } catch (IOException | RuntimeException e) {
                message = "Host not reachable – is Radmin VPN on and the host's flip mode running?";
            }
            String result = message;
            minecraft.execute(() -> SBSChat.send(result));
        }, "SBS-LocalFlips-Test");
        test.setDaemon(true);
        test.start();
    }

    // ------------------------------------------------------------------
    // Read side, for the window (client thread)
    // ------------------------------------------------------------------

    /** Bumped on every change the window draws; the window rebuilds its model when it moves. */
    public int revision() {
        return revision;
    }

    public Status status() {
        return status;
    }

    public long retryAtMs() {
        return retryAtMs;
    }

    public long lastSignalMs() {
        return lastSignalMs;
    }

    public long apiLastUpdatedMs() {
        return apiLastUpdatedMs;
    }

    public Collection<Entry> sofort() {
        return sofort.values();
    }

    public List<LocalFlip> liste() {
        return liste;
    }

    public boolean isGone(String auction) {
        return goneAt.containsKey(auction);
    }

    /** True once a gone entry has been greyed for its five seconds and should no longer be drawn. */
    public boolean goneExpired(String auction, long now) {
        Long at = goneAt.get(auction);
        return at != null && now - at > GONE_VISIBLE_MS;
    }

    public boolean isHidden(String auction) {
        return hidden.contains(auction);
    }

    private static double foundOrReceived(Entry entry) {
        return Double.isNaN(entry.flip().foundAt()) ? entry.receivedMs() / 1000.0 : entry.flip().foundAt();
    }
}
