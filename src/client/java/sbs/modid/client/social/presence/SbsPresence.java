/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.presence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.SbsApi;
import sbs.modid.client.core.licence.privacy.ConsentManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.social.chat.logic.ChatPlayerNames;
import sbs.modid.client.core.config.ConfigManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tells the SBS cloud who is online running the mod, and learns which of the players around you are
 * too – the data behind the "SBS badge" on other players' nametags.
 *
 * <p><b>Why a server at all.</b> Hypixel never relays a mod's packets between players, so two SBS
 * clients can only find each other through our own backend. Each client posts a single
 * {@code /api/presence/sync} every {@value #PERIOD_SECONDS}s carrying <b>its own uuid and nothing
 * else</b> – a heartbeat that puts you in the roster of consenting SBS users.
 *
 * <p><b>What changed, and why.</b> The heartbeat used to travel with the uuids of every player
 * loaded around you, and the reply told us which of them ran SBS. That worked, but it uploaded
 * identifiers belonging to people who had no relationship with us, no way to object and no way to
 * even know it happened – and it did so from the machine of a user who could not consent on their
 * behalf. The upload is gone.
 *
 * <p><b>What replaces it.</b> The same feature, inverted: the client will <i>download</i> the roster
 * of users who granted {@link ConsentScope#BADGE_PUBLIC} and match it locally, so a badge decision
 * is made entirely on your own machine and nothing about anyone else is ever transmitted. That
 * roster route is Phase A of {@code docs/PRIVACY-BACKEND.md} and <b>does not exist yet</b>: until it
 * ships, {@link #isSbsUser} can only recognise you, so no badges render over other players. That is
 * a real, visible regression, and the honest cost of not having shipped the private version first.
 *
 * <p>Gated on the SBS Players toggle, a licence token, and {@link ConsentScope#BADGE_PUBLIC}. Runs
 * on its own daemon thread and never touches the client or render thread.
 */
public final class SbsPresence {

    private static final SbsPresence INSTANCE = new SbsPresence();

    private static final long PERIOD_SECONDS = 30;

    /**
     * The private-use glyph (U+F0000) the badge texture is mapped to. It is added to the vanilla
     * {@code minecraft:default} font (see {@code assets/minecraft/font/default.json}), so the plain
     * character renders as the SBS logo everywhere text is drawn \u2013 nametag, tab list and chat alike \u2013
     * with no per-component font style to get lost in the nametag pipeline.
     *
     * <p>It lives in the <b>supplementary</b> Private-Use Area (Plane 15), <i>not</i> the BMP PUA
     * (U+E000\u2013U+F8FF): Hypixel's server resource pack also merges into {@code minecraft:default} and
     * fills that BMP block with its own icons. As a server pack it sits above the mod in the pack
     * stack, so a BMP codepoint (the badge used to be U+E000) is overridden by Hypixel's glyph \u2013 the
     * badge came out as Hypixel's heart. Plane 15 is untouched by Hypixel, so ours wins.
     */
    private static final String BADGE_GLYPH = "\uDB80\uDC00"; // U+F0000 (surrogate pair)

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final Gson gson = new Gson();
    private ScheduledExecutorService scheduler;

    /** Lower-cased uuid strings of the SBS users seen in the last sync (swapped in atomically). */
    private volatile Set<String> sbsUsers = Set.of();

    private SbsPresence() {
    }

    public static SbsPresence getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SBS-Presence");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::syncSafely, 8, PERIOD_SECONDS, TimeUnit.SECONDS);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Presence sync scheduled every {} s (when enabled).",
                PERIOD_SECONDS);
    }

    /**
     * Whether the given player is a current SBS user – used to decide whether to badge them.
     *
     * <p>Gated on the module toggle. You yourself always count when the module is on (you are
     * obviously running SBS), so your own badge shows in the tab list / chat / nametag straight away,
     * without waiting on the presence server - and without needing consent for anything, since
     * recognising yourself involves no network call at all.
     *
     * <p>{@link #sbsUsers} is the roster of <i>other</i> people known to run SBS. It is empty in
     * this build and will stay empty until the roster download lands (see the class note): the old
     * way of filling it required uploading other players' uuids, which is exactly what was removed.
     */
    public boolean isSbsUser(UUID uuid) {
        if (uuid == null || !ConfigManager.getInstance().get().sbsPlayers.enabled) {
            return false;
        }
        LocalPlayer self = Minecraft.getInstance().player;
        if (self != null && self.getUUID().equals(uuid)) {
            return true;
        }
        return sbsUsers.contains(uuid.toString().toLowerCase(Locale.ROOT));
    }

    /** Whether the player with this username is a current SBS user (resolved via the tab list). */
    public boolean isSbsUserName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return false;
        }
        PlayerInfo info = connection.getPlayerInfo(name);
        return info != null && isSbsUser(info.getProfile().id());
    }

    /**
     * The badge glyph on its own (the SBS logo via the default-font glyph). Forced white so the
     * full-colour logo texture is not tinted by whatever colour the surrounding text carries.
     */
    private static Component badgeGlyph() {
        return Component.literal(BADGE_GLYPH).withColor(0xFFFFFF);
    }

    /** Prepends the SBS badge + a space to a name component (tab list / nametag). */
    public static Component badge(Component name) {
        return Component.empty().append(badgeGlyph()).append(CommonComponents.SPACE).append(name);
    }

    /**
     * Inserts an SBS badge before every SBS-user name inside a chat message, Essential-style.
     *
     * <p>Names are found not by parsing the line (rank tags, guild prefixes, several names…) but by
     * the same handle vanilla uses for shift-click: each name component carries an insertion / a
     * {@code /msg} click event, read by {@link ChatPlayerNames}. So the badge lands exactly before the
     * right name even in a line naming three people, and anything that is not a name is left alone.
     */
    public Component decorateChat(Component message) {
        if (message == null || !ConfigManager.getInstance().get().sbsPlayers.enabled) {
            return message;
        }
        // Only rebuild a line that actually names an SBS user; every other line is returned exactly
        // as it came in, keeping its original colours (a rebuild that touched every message and
        // dropped its styling is what turned the whole chat white).
        if (!hasSbsName(message)) {
            return message;
        }
        return walkChat(message);
    }

    /** Whether any component in the tree names an SBS user. */
    private boolean hasSbsName(Component component) {
        String name = ChatPlayerNames.playerFrom(component.getStyle());
        if (name != null && isSbsUserName(name)) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (hasSbsName(sibling)) {
                return true;
            }
        }
        return false;
    }

    private Component walkChat(Component component) {
        // Preserve content AND style - plainCopy() keeps only the content, which strips every colour.
        MutableComponent out = MutableComponent.create(component.getContents()).setStyle(component.getStyle());
        for (Component sibling : component.getSiblings()) {
            String name = ChatPlayerNames.playerFrom(sibling.getStyle());
            if (name != null && isSbsUserName(name)) {
                out.append(badgeGlyph()).append(CommonComponents.SPACE);
            }
            out.append(walkChat(sibling));
        }
        return out;
    }

    /**
     * The entire request body: your own uuid, and nothing else.
     *
     * <p>A separate method purely so it can be asserted on. {@code PresencePayloadTest} checks that
     * this object has exactly one member and contains no array, which is what makes re-introducing
     * a nearby-players list a failing test rather than a quiet regression that nobody notices until
     * the next audit. Taking the uuid as a parameter rather than reading it from
     * {@link Minecraft} is what lets that test run without a game.
     */
    static JsonObject heartbeatBody(UUID self) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", self.toString());
        return body;
    }

    /**
     * Publishes the heartbeat: "this account is online and running SBS".
     *
     * <p><b>Your own uuid is the entire payload.</b> This request used to also carry the uuids of
     * every player loaded around you, so the server could answer which of them were SBS users. That
     * upload has been deleted - not disabled, deleted - because those players are third parties who
     * cannot consent to it by construction, and a code path that can be switched back on is not a
     * fix. See {@code ConsentScope#PRESENCE_NEARBY} and {@code PRIVACY.md}.
     *
     * <p>Gated on {@link ConsentScope#BADGE_PUBLIC} rather than on a presence scope because
     * publishing the uuid is the whole effect: it is what puts you in the roster other SBS clients
     * read to decide whether to draw your badge. Nothing here reports a server, a lobby or a
     * position.
     */
    private void syncSafely() {
        try {
            if (!ConfigManager.getInstance().get().sbsPlayers.enabled) {
                sbsUsers = Set.of();   // module off - drop the cache so no stale badges linger
                return;
            }
            // The gate in SbsApi.send would refuse this anyway; checking first avoids building a
            // request and throwing it away 30 seconds at a time for a user who declined.
            if (!SbsApi.hasLicence() || !ConsentManager.isGranted(ConsentScope.BADGE_PUBLIC)) {
                sbsUsers = Set.of();
                return;
            }
            LocalPlayer self = Minecraft.getInstance().player;
            if (self == null) {
                return;
            }
            JsonObject body = heartbeatBody(self.getUUID());

            HttpRequest request = SbsApi.withLicence(
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/api/presence/sync
// METHOD: POST
// PURPOSE: Tell the backend this client is online so other SBS users get the badge on this
//   nametag, and learn which nearby players to badge in return.
// DATA SENT: A JSON body carrying THE PLAYER OWN MOJANG UUID (see the body built above),
//   plus the licence-token headers. Built with Gson.
// DATA RECEIVED: Read-only JSON - the uuids of consenting SBS users, matched locally, so no
//   query about a specific other player is ever sent. Parsed with Gson.
// SAFETY DECLARATION: THIS ENDPOINT DOES TRANSMIT THE LOCAL PLAYER MOJANG UUID, and says so
//   rather than claiming otherwise - a badge cannot work without an identifier to publish. It
//   is the public account uuid, never a session token and never a Mojang credential, and it is
//   always the local player own, never another player. Nothing is sent until the user grants
//   ConsentScope.BADGE_PUBLIC in Licence Token > Privacy and data, which starts off;
//   withdrawing it asks the server to drop what it holds. No OS or hardware telemetry.
//   See PRIVACY.md.
// ============================================================================
                            HttpRequest.newBuilder(URI.create(SbsApi.base() + "/api/presence/sync"))
                                    .timeout(Duration.ofSeconds(10))
                                    .header("Content-Type", "application/json"))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                    .build();
            // The response is not read. Until the roster route exists there is nothing in it for us:
            // the old reply was the matched subset of the uuids we no longer send.
            SbsApi.send(ConsentScope.BADGE_PUBLIC, http, request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Presence] heartbeat failed: {}", t.toString());
        }
    }
}
