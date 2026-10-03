/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.logic;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.licence.privacy.ConsentManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client for the SBS IRC chat ({@code /irc/} on the SBS backend).
 *
 * <p><b>Transport: long-poll.</b> One background daemon thread holds {@code GET /irc/poll} open
 * for up to ~25s; the server answers immediately when a message arrives (push latency) or empty on
 * timeout, and the loop re-polls. That is push behaviour at a fraction of the bandwidth of
 * 1-second polling. Sending goes through {@code POST /irc/send} on a second single-thread executor
 * so a held poll never delays a send.
 *
 * <p>Auth is the licence token on every request; moderation and rate limiting are handled by the
 * server. Incoming messages pass the local whitelist/blacklist filter (Chat
 * Options) before being shown as {@code [IRC] name: message} in the SBS chat style. Your own
 * messages always show (send confirmation).
 */
public final class IrcClient {

    private static final IrcClient INSTANCE = new IrcClient();

    /** Every request goes to the SBS backend over HTTPS. The licence token travels as a header,
     *  never in the URL. */
    private static final String BASE = "https://skyblocksimplified.info";
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(32);
    private static final int POLL_HOLD_SECONDS = 25;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final Gson gson = new Gson();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-IRC-Send");
        thread.setDaemon(true);
        return thread;
    });

    /** -1 = fresh session: the server answers with the current cursor and NO history
     *  (the 24h server buffer is moderation-only, never replayed to clients). */
    private volatile long since = -1;
    private volatile boolean authFailureShown;
    private Thread pollThread;

    private IrcClient() {
    }

    public static IrcClient getInstance() {
        return INSTANCE;
    }

    /**
     * Starts the poll loop thread once (idles cheaply while the toggle is off) and wires the
     * consent listener that stops it again.
     *
     * <p><b>Withdrawal has to be immediate, and here that takes an interrupt.</b> The loop spends
     * almost all of its time blocked inside a single {@code GET /irc/poll} held open for up to
     * {@value #POLL_HOLD_SECONDS}s. Simply flipping a flag would leave that connection - opened
     * under a consent that no longer exists, carrying a token that identifies the user - alive for
     * up to another 25 seconds. Interrupting the thread aborts the in-flight request instead, so the
     * connection is gone at the moment the user clicks, which is what "withdraw" has to mean.
     */
    public synchronized void start() {
        if (pollThread != null) {
            return;
        }
        pollThread = new Thread(this::pollLoop, "SBS-IRC-Poll");
        pollThread.setDaemon(true);
        pollThread.start();

        ConsentManager.getInstance().addListener((scope, granted) -> {
            if (scope == ConsentScope.IRC_IDENTITY && !granted) {
                since = -1;   // next grant starts fresh, with no replay of what was missed
                Thread thread = pollThread;
                if (thread != null) {
                    thread.interrupt();
                }
            }
        });
    }

    private static SBSConfig.ChatOptionsSettings cfg() {
        return ConfigManager.getInstance().get().chatOptions;
    }

    private static String token() {
        String token = sbs.modid.client.core.config.LicenceToken.getInstance().get();
        return token == null ? "" : token.trim();
    }

    /** The local player's IGN, or null while not in a world. */
    private static String playerName() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null ? minecraft.player.getGameProfile().name() : null;
    }

    // ------------------------------------------------------------------
    // Receiving (long-poll loop)
    // ------------------------------------------------------------------

    private void pollLoop() {
        while (true) {
            try {
                // Consent is checked every iteration rather than once at start-up, so a withdrawal
                // that lands while the thread is between polls is honoured without an interrupt.
                if (!cfg().ircEnabled || playerName() == null || token().isEmpty()
                        || !ConsentManager.isGranted(ConsentScope.IRC_IDENTITY)) {
                    since = -1; // next enable starts fresh - no missed-message replay
                    Thread.interrupted();   // clear a revoke interrupt; this branch IS the stop
                    Thread.sleep(2_000);
                    continue;
                }
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/irc/poll?since=<cursor>&timeout=<seconds>
// METHOD: GET, long-poll - held open about 25s, answered early when a message arrives, then
//   the loop re-polls. Ordinary HTTPS on 443, deliberately not a socket.
// PURPOSE: Receive SBS chat messages without polling every second.
// DATA SENT: A message cursor and the hold time in the query string, and the licence token as
//   Authorization: Bearer. No body.
// DATA RECEIVED: Read-only JSON - messages from other users. Parsed with Gson and filtered
//   locally against the user own whitelist and blacklist before anything is shown.
// SAFETY DECLARATION: Sends the licence token (Authorization: Bearer, X-Api-Token),
//   which identifies the PURCHASE and not the player. No Mojang credentials, no
//   session id, no OS or hardware telemetry. Refused until the user grants
//   ConsentScope.IRC_IDENTITY in Licence Token > Privacy and data; every switch there
//   starts off. See PRIVACY.md.
// ============================================================================
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                BASE + "/irc/poll?since=" + since + "&timeout=" + POLL_HOLD_SECONDS))
                        .timeout(POLL_TIMEOUT)
                        .header("Authorization", "Bearer " + token())
                        .GET().build();
                HttpResponse<String> response = sbs.modid.client.core.api.SbsApi.send(
                        ConsentScope.IRC_IDENTITY, http, request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 401) {
                    if (!authFailureShown) {
                        authFailureShown = true;
                        show(system("IRC: invalid licence token - check the Licence Token module."));
                    }
                    Thread.sleep(30_000);
                    continue;
                }
                if (response.statusCode() != 200) {
                    Thread.sleep(5_000);
                    continue;
                }
                authFailureShown = false;
                JsonObject body = gson.fromJson(response.body(), JsonObject.class);
                if (body == null) {
                    continue;
                }
                long newSince = body.has("seq") ? body.get("seq").getAsLong() : since;
                if (body.has("messages")) {
                    for (var element : body.getAsJsonArray("messages")) {
                        var row = element.getAsJsonArray();
                        if (row.size() >= 4) {
                            // [seq, ts, name, msg] + optional [roleLabel, roleColor]. The role is
                            // optional so an older server (or one before roles) still works - it
                            // simply renders without the tag.
                            String roleLabel = row.size() >= 5 ? row.get(4).getAsString() : "";
                            String roleColor = row.size() >= 6 ? row.get(5).getAsString() : "";
                            deliver(row.get(2).getAsString(), row.get(3).getAsString(),
                                    roleLabel, roleColor);
                        }
                    }
                }
                since = Math.max(since, newSince);
            } catch (InterruptedException interrupted) {
                // Almost always a withdrawal aborting the held poll. The flag is cleared and the
                // loop continues rather than returning: the thread must survive so that granting
                // the scope again resumes chat, and the guard at the top of the loop is what parks
                // it in the meantime. Returning here would make the first withdrawal permanent
                // until the game restarted.
                Thread.interrupted();
                since = -1;
            } catch (Exception e) {
                SkyblockSimplifiedSBS.LOGGER.debug("[SBS][IRC] poll failed: {}", e.toString());
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException interrupted) {
                    Thread.interrupted();
                }
            }
        }
    }

    /**
     * Applies the local whitelist/blacklist, then shows the message as
     * {@code [SBS] [IRC] [Role] Name: message}.
     *
     * <p>The role tag and its colour come from the <b>server</b>, which resolves them from the
     * sender's token. Nothing about a role is decided here: a client that could pick its own tag
     * could claim to be the owner, and a role table compiled into the mod would need a release
     * every time one is renamed.
     *
     * @param roleLabel the role's display text, empty for the default role (no tag shown)
     * @param roleColor {@code RRGGBB}, empty to fall back to the prefix colour
     */
    private void deliver(String name, String message, String roleLabel, String roleColor) {
        String self = playerName();
        boolean own = self != null && self.equalsIgnoreCase(name);
        if (!own && !passesFilter(name)) {
            return;
        }
        MutableComponent line = SBSChat.prefix()
                .append(Component.literal(" [IRC]").withColor(SBSChat.PREFIX_COLOR));
        if (roleLabel != null && !roleLabel.isBlank()) {
            line.append(Component.literal(" [" + roleLabel + "]").withColor(parseColor(roleColor)));
        }
        line.append(Component.literal(" " + name).withColor(own ? 0x57D977 : 0xFFD64D))
                .append(Component.literal(": " + message).withColor(SBSChat.WHITE));
        show(line);
    }

    /** {@code RRGGBB} from the server, or the prefix colour when it is missing / malformed. */
    private static int parseColor(String hex) {
        if (hex == null || hex.isBlank()) {
            return SBSChat.PREFIX_COLOR;
        }
        try {
            return Integer.parseInt(hex.trim().replace("#", ""), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return SBSChat.PREFIX_COLOR;
        }
    }

    /** OFF = everyone; WHITELIST = only listed names; BLACKLIST = everyone except listed. */
    private static boolean passesFilter(String name) {
        SBSConfig.ChatOptionsSettings chat = cfg();
        String mode = chat.ircFilterMode == null ? "OFF" : chat.ircFilterMode;
        if ("OFF".equalsIgnoreCase(mode)) {
            return true;
        }
        boolean listed = chat.ircNames != null && chat.ircNames.stream()
                .anyMatch(entry -> entry != null && entry.trim().equalsIgnoreCase(name));
        return "WHITELIST".equalsIgnoreCase(mode) == listed;
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
        return Component.literal("[IRC] ").withColor(SBSChat.PREFIX_COLOR)
                .append(Component.literal(text).withColor(0xE0605F));
    }

    // ------------------------------------------------------------------
    // Sending (/sbs irc <message>)
    // ------------------------------------------------------------------

    /** Entry point for {@code /sbs irc <message>} - validates, then sends async. */
    public void sendFromCommand(String message) {
        String msg = message == null ? "" : message.trim();
        if (!cfg().ircEnabled) {
            show(system("IRC chat is disabled - enable it in Chat Options."));
            return;
        }
        if (msg.isEmpty()) {
            show(system("Usage: /sbs irc <message>"));
            return;
        }
        String name = playerName();
        if (name == null || token().isEmpty()) {
            show(system("IRC: not connected (need a world + licence token)."));
            return;
        }
        // Sending is what actually publishes the name to other players, so it gets its own check
        // rather than relying on the poll loop being up: told plainly, because a message that
        // silently went nowhere is worse than one that was refused out loud.
        if (!ConsentManager.isGranted(ConsentScope.IRC_IDENTITY)) {
            show(system("IRC: community chat is off until you allow it in Licence Token > Privacy."));
            return;
        }
        sender.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("name", name);
                body.addProperty("msg", msg);
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/irc/send
// METHOD: POST
// PURPOSE: Send one message the user has typed into the SBS cross-game chat.
// DATA SENT: A JSON body with the display name and the message text, plus the licence-token
//   headers. Built with Gson. Nothing is sent unless the user presses enter on a message.
// DATA RECEIVED: A send confirmation.
// SAFETY DECLARATION: THIS ENDPOINT DOES TRANSMIT A DISPLAY NAME AND MESSAGE TEXT - that is
//   what a chat is, and both are supplied by the user in the act of sending. No Mojang
//   credentials, no session id, no OS telemetry. Gated on ConsentScope.IRC_IDENTITY, which
//   starts off. See PRIVACY.md.
// ============================================================================
                HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/irc/send"))
                        .timeout(SEND_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token())
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                        .build();
                HttpResponse<String> response = sbs.modid.client.core.api.SbsApi.send(
                        ConsentScope.IRC_IDENTITY, http, request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                switch (response.statusCode()) {
                    case 200 -> { /* echoed back through the poll loop */ }
                    case 401 -> show(system("IRC: invalid licence token."));
                    case 403 -> show(system("IRC: you are banned from this chat."));
                    case 423 -> show(system("IRC: token locked to another connection."));
                    case 429 -> show(system("IRC: slow down (rate limit)."));
                    default -> show(system("IRC: send failed (HTTP " + response.statusCode() + ")."));
                }
            } catch (Exception e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][IRC] send failed: {}", e.toString());
                show(system("IRC: connection failed."));
            }
        });
    }

    /** Cycles OFF -> WHITELIST -> BLACKLIST (used by the Chat Options setting row). */
    public static String cycleFilterMode(String current) {
        return switch (current == null ? "OFF" : current.toUpperCase(Locale.ROOT)) {
            case "OFF" -> "WHITELIST";
            case "WHITELIST" -> "BLACKLIST";
            default -> "OFF";
        };
    }
}
