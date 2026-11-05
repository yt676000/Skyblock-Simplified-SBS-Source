/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.SbsApi;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.core.net.validation.MessageSchemas;
import sbs.modid.client.core.net.validation.MessageTypes;
import sbs.modid.client.core.net.validation.ValidationException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The automatic credential for players without a licence: a free token, obtained by proving the
 * Minecraft account and accepted by the server only on the PlayerViewer routes and the party
 * finder, never on party chat ({@link Credentials#FREE_ROUTES}, {@link Credentials#FREE_EXCLUDED}).
 * The server issues it for the account's uuid, so unlike a licence token it identifies the player.
 *
 * <p>The handshake is the one {@link SessionIdentity} uses, against the free-token routes:
 * <ol>
 *   <li>{@code POST /v1/free-token/challenge} (no credential) returns a single-use
 *       {@code serverId};</li>
 *   <li>the game's own session service is told we join that {@code serverId}
 *       ({@link SessionIdentity#vanillaJoin}, the vanilla multiplayer join call);</li>
 *   <li>{@code POST /v1/free-token/claim} with our own name and the {@code serverId} returns the
 *       token, which the server issues only after Mojang confirmed the join.</li>
 * </ol>
 *
 * <p>Every request of the handshake goes through {@link SbsApi#send} under the consent of the
 * feature that needs the token ({@link #scopeFor}: {@link ConsentScope#PROFILE_DATA} for the
 * PlayerViewer, {@link ConsentScope#PARTY_FINDER} for the party finder), so without that consent
 * nothing at all is sent - not even the challenge. A licence entered in the Licence Token module, or a hand-entered tester token, always
 * takes precedence; {@link Credentials#apply} only asks here when no token is entered.
 *
 * <p>The token is kept in memory and in {@code config/sbs/license/free_token.json} (versioned,
 * bound to the account uuid it was issued for). It is never logged. A {@code 401} on a PlayerViewer
 * request drops it and the next request claims once more; when that one is rejected too, no further
 * claim is made for {@link #FAIL_CLOSED_MS} and requests fail with {@link UnavailableException}.
 */
public final class FreeAccess {

    static final String CHALLENGE_PATH = "/v1/free-token/challenge";
    static final String CLAIM_PATH = "/v1/free-token/claim";
    /** The routes that issue a free token. They take no credential. */
    static final java.util.List<String> ISSUE_ROUTES = java.util.List.of(CHALLENGE_PATH, CLAIM_PATH);

    static final Pattern TOKEN = Pattern.compile("^sbsfree_[0-9a-f]{64}$");
    static final int STORE_VERSION = 1;
    /** After a re-claimed token is rejected as well, no claim is attempted for this long. */
    static final long FAIL_CLOSED_MS = 15L * 60 * 1000;
    /** A token is renewed this long before its expiry, so a request never races it. */
    private static final long EXPIRY_MARGIN_S = 300;
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    /** Thrown when no free token can be had right now. Shown as "temporarily unavailable". */
    public static final class UnavailableException extends IOException {
        public UnavailableException(String code) {
            super("free access unavailable: " + code);
        }
    }

    /** Where the token survives a restart (production: a JSON file under config/sbs/license). */
    interface Store {
        Held load();

        void save(Held held);

        void clear();
    }

    /** A token and the account and expiry it belongs to. */
    record Held(String token, String uuid, long expiresAtSeconds) {
    }

    private static final FreeAccess INSTANCE = new FreeAccess(
            (scope, typeId, request) -> SbsApi.send(scope, typeId, request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),
            SessionIdentity::vanillaJoin, FreeAccess::ownName, FreeAccess::ownUuid, new FileStore(),
            System::currentTimeMillis,
            message -> SkyblockSimplifiedSBS.LOGGER.debug("[SBS][PV] {}", message));

    private final SessionIdentity.Transport transport;
    private final SessionIdentity.Joiner joiner;
    private final Supplier<String> ownName;
    private final Supplier<String> ownUuid;
    private final Store store;
    private final LongSupplier clock;
    private final Consumer<String> log;

    private Held held;
    private boolean loaded;
    /** Set when a token was rejected right after being claimed; cleared by a successful claim. */
    private String lastRejected;
    private long closedUntilMs;

    FreeAccess(SessionIdentity.Transport transport, SessionIdentity.Joiner joiner, Supplier<String> ownName,
               Supplier<String> ownUuid, Store store, LongSupplier clock, Consumer<String> log) {
        this.transport = transport;
        this.joiner = joiner;
        this.ownName = ownName;
        this.ownUuid = ownUuid;
        this.store = store;
        this.clock = clock;
        this.log = log;
    }

    public static FreeAccess get() {
        return INSTANCE;
    }

    /** True for the two issue routes on the SBS origin. */
    static boolean isIssueRoute(URI uri) {
        return SessionIdentity.isSbsOrigin(uri) && uri.getRawPath() != null
                && ISSUE_ROUTES.contains(uri.getRawPath());
    }

    /**
     * The free token for this account, claiming one when none is held or it is about to expire.
     * Must not be called on the render thread (it may run the handshake).
     *
     * @throws SbsApi.NoConsentException when profile data is not granted (nothing was sent)
     * @throws UnavailableException      when the server or Mojang refused, or the fail-closed pause
     *                                   after a rejected re-claim is running
     */
    public String token() throws IOException, InterruptedException {
        return token(ConsentScope.PROFILE_DATA);
    }

    /**
     * The consent a claim needed for {@code uri} runs under: the party finder (and the session
     * handshake it uses) under {@link ConsentScope#PARTY_FINDER}, everything else under
     * {@link ConsentScope#PROFILE_DATA}. A player who allowed only one of the two features gets the
     * free token for that feature, and nothing is sent for the one they did not allow.
     */
    public static ConsentScope scopeFor(URI uri) {
        String path = uri == null ? null : uri.getRawPath();
        return path != null && (path.startsWith("/party/") || path.startsWith("/v1/session/"))
                ? ConsentScope.PARTY_FINDER : ConsentScope.PROFILE_DATA;
    }

    /**
     * {@link #token()} for a request made for {@code scope}: a claim, when one is needed, is sent
     * under that scope and refused like any other request when it is not granted.
     */
    public synchronized String token(ConsentScope scope) throws IOException, InterruptedException {
        long nowMs = clock.getAsLong();
        String uuid = Identity.normalizeUuid(ownUuid.get()).orElse(null);
        if (!loaded) {
            loaded = true;
            held = store.load();
        }
        if (held != null && (!held.uuid().equals(uuid)
                || nowMs / 1000 >= held.expiresAtSeconds() - EXPIRY_MARGIN_S)) {
            // Another account, or about to expire: never sent, replaced below.
            held = null;
            store.clear();
        }
        if (held != null) {
            return held.token();
        }
        if (nowMs < closedUntilMs) {
            throw new UnavailableException("closed");
        }
        if (uuid == null) {
            throw new UnavailableException("no_profile");
        }
        Held claimed = claim(scope);
        if (!claimed.uuid().equals(uuid)) {
            // The server verified a different account than the one playing. Not used.
            log.accept("free access: claim answered for another account");
            throw new UnavailableException("account_mismatch");
        }
        held = claimed;
        store.save(claimed);
        return claimed.token();
    }

    /**
     * A PlayerViewer request answered 401 with {@code token}. Drops it, so the next {@link #token()}
     * claims a fresh one; returns whether that one retry is allowed. A second rejection in a row
     * closes the free path for {@link #FAIL_CLOSED_MS}.
     */
    public synchronized boolean rejected(String token) {
        if (token == null) {
            return false;
        }
        if (held != null && token.equals(held.token())) {
            held = null;
            store.clear();
        }
        if (token.equals(lastRejected)) {
            return false;
        }
        if (lastRejected != null) {
            closedUntilMs = clock.getAsLong() + FAIL_CLOSED_MS;
            log.accept("free access: re-claimed token rejected too, pausing");
            lastRejected = null;
            return false;
        }
        lastRejected = token;
        return true;
    }

    /** A request with {@code token} succeeded: the rejection streak is over. */
    public synchronized void accepted(String token) {
        if (token != null && held != null && token.equals(held.token())) {
            lastRejected = null;
        }
    }

    /** One request made with the free token: what it returned, its HTTP status and the token it carried. */
    public record Attempt<T>(T result, int status, String token) {
    }

    /** Makes one request, taking the token from {@link #token()} (via {@link Credentials#apply}). */
    @FunctionalInterface
    public interface Call<T> {
        Attempt<T> run() throws IOException, InterruptedException;
    }

    /**
     * Runs {@code call}; on {@code 401} drops the token and runs it once more with a freshly claimed
     * one. A second {@code 401} fails closed with {@link UnavailableException} and pauses claims for
     * {@link #FAIL_CLOSED_MS}. Any other status is returned to the caller as it is.
     */
    public <T> T call(Call<T> call) throws IOException, InterruptedException {
        Attempt<T> first = call.run();
        if (first.status() != 401) {
            accepted(first.token());
            return first.result();
        }
        if (!rejected(first.token())) {
            throw new UnavailableException("rejected");
        }
        Attempt<T> second = call.run();
        if (second.status() == 401) {
            rejected(second.token());
            throw new UnavailableException("rejected");
        }
        accepted(second.token());
        return second.result();
    }

    /**
     * Changes whenever the held token changes; a short hash, never the token. Lets caches of data
     * fetched with one token drop it when another takes its place.
     */
    public synchronized String fingerprint() {
        return held == null ? "-" : Integer.toHexString(held.token().hashCode());
    }

    /** Forgets the token in memory and on disk (licence entered, account switched). */
    public synchronized void clear() {
        held = null;
        loaded = true;
        lastRejected = null;
        closedUntilMs = 0;
        store.clear();
    }

    // ------------------------------------------------------------------
    // Handshake
    // ------------------------------------------------------------------

    private Held claim(ConsentScope scope) throws IOException, InterruptedException {
        String serverId = challenge(scope);
        try {
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: Mojang session service (sessionserver.mojang.com), through the game's own
//   minecraft.services().sessionService().joinServer - the call vanilla makes when joining a server.
// METHOD: POST (inside the game's session service)
// PURPOSE: Prove to SBS that this Minecraft account is the one asking for free access (PlayerViewer
//   and party finder), without SBS ever seeing the account's credentials.
// DATA SENT: The serverId from the challenge, the account's profile id and the game's own session
//   access token - all to Mojang only. The access token is passed only into that call; it is never
//   stored, logged or sent to SBS.
// DATA RECEIVED: Nothing is read beyond success or failure.
// SAFETY DECLARATION: Identical to a normal multiplayer join. Runs only after the challenge was sent,
//   which is refused until the consent of the feature asking is granted (ConsentScope.PROFILE_DATA
//   for the PlayerViewer, ConsentScope.PARTY_FINDER for the party finder - see scopeFor).
// ============================================================================
            joiner.join(serverId);
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception joinFailed) {
            // The exception text may describe the account; only its type is logged.
            log.accept("free access join failed: " + joinFailed.getClass().getSimpleName());
            throw new UnavailableException("join_failed");
        }
        String name = ownName.get();
        if (!Identity.isValidName(name)) {
            throw new UnavailableException("no_profile");
        }
        JsonObject out = new JsonObject();
        out.addProperty("username", name);
        out.addProperty("serverId", serverId);
        MessageSchemas.checkOutgoing(MessageTypes.OUT_FREE_ACCESS_CLAIM, out);
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/v1/free-token/claim
// METHOD: POST
// PURPOSE: Obtain the free token (PlayerViewer and party finder) for a player with no licence, after
//   the game told Mojang it joins the serverId from the challenge.
// DATA SENT: A JSON body with the player's own Minecraft name and the serverId. No licence token,
//   no Mojang access token, no session id.
// DATA RECEIVED: The free token (sbsfree_ + 64 hex), the verified uuid and the expiry; parsed with
//   Gson against a fixed schema. The token is kept in memory and in
//   config/sbs/license/free_token.json and is never logged.
// SAFETY DECLARATION: The server asks Mojang (hasJoined) whether this name joined that serverId and
//   issues the token for the uuid Mojang returns, so THE FREE TOKEN IS TIED TO THE PLAYER'S UUID and
//   identifies the player to SBS (it is stored server-side as a hash, next to that uuid and hashed
//   IPs for the per-IP limits). It is accepted only on the PlayerViewer routes and the party finder,
//   never on party chat. Refused until the consent of the feature asking is granted (scopeFor).
// ============================================================================
        URI uri = URI.create(SessionIdentity.ORIGIN + CLAIM_PATH);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(out.toString())).build();
        HttpResponse<String> response = transport.send(scope,
                MessageTypes.FREE_ACCESS_CLAIM, request);
        if (response.statusCode() != 200) {
            throw failure("claim", response);
        }
        JsonObject body = parsed(MessageTypes.FREE_ACCESS_CLAIM, response.body());
        String token = body.get("token").getAsString();
        String uuid = Identity.normalizeUuid(body.get("uuid").getAsString()).orElse(null);
        if (!TOKEN.matcher(token).matches() || uuid == null) {
            throw new UnavailableException("invalid");
        }
        return new Held(token, uuid, body.get("expiresAt").getAsLong());
    }

    private String challenge(ConsentScope scope) throws IOException, InterruptedException {
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/v1/free-token/challenge
// METHOD: POST
// PURPOSE: First step of the free access (PlayerViewer, party finder) for a player with no licence:
//   a random, single-use serverId (valid 60 s) to join.
// DATA SENT: The body {}. No credential, no name, no uuid.
// DATA RECEIVED: The serverId (40 hex) and its lifetime; parsed with Gson against a fixed schema.
// SAFETY DECLARATION: Nothing about the player is sent. Refused until the consent of the feature
//   asking is granted (scopeFor), so without that consent the handshake never starts.
// ============================================================================
        URI uri = URI.create(SessionIdentity.ORIGIN + CHALLENGE_PATH);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        HttpResponse<String> response = transport.send(scope,
                MessageTypes.FREE_ACCESS_CHALLENGE, request);
        if (response.statusCode() != 200) {
            throw failure("challenge", response);
        }
        JsonObject body = parsed(MessageTypes.FREE_ACCESS_CHALLENGE, response.body());
        String serverId = body.get("serverId").getAsString();
        if (!SessionIdentity.SERVER_ID.matcher(serverId).matches()) {
            throw new UnavailableException("invalid");
        }
        return serverId;
    }

    private JsonObject parsed(String typeId, String body) throws UnavailableException {
        try {
            return MessageSchemas.parse(typeId, body).getAsJsonObject();
        } catch (ValidationException | IllegalStateException e) {
            log.accept("free access: invalid " + typeId + " response");
            throw new UnavailableException("invalid");
        }
    }

    private UnavailableException failure(String step, HttpResponse<String> response) {
        String code = SessionIdentity.errorCode(response.body());
        log.accept("free access " + step + " refused: HTTP " + response.statusCode() + " " + code);
        return new UnavailableException(code);
    }

    // ------------------------------------------------------------------
    // Production hooks
    // ------------------------------------------------------------------

    private static String ownName() {
        return Minecraft.getInstance().getUser().getName();
    }

    private static String ownUuid() {
        var id = Minecraft.getInstance().getUser().getProfileId();
        return id == null ? null : id.toString();
    }

    /**
     * {@code config/sbs/license/free_token.json}: {@code {"version":1,"uuid":..,"token":..,
     * "expiresAt":..}}. Read once on first use; writes go through {@link SbsExecutors#io()} and
     * replace the file atomically. A file of another version or shape is ignored, never trusted.
     */
    static final class FileStore implements Store {

        private static Path path() {
            return SBSFiles.licenseDir().resolve("free_token.json");
        }

        @Override
        public Held load() {
            Path path = path();
            if (!Files.exists(path)) {
                return null;
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonObject root = SBSFiles.GSON.fromJson(reader, JsonObject.class);
                return parse(root);
            } catch (Exception e) {
                // The content is a credential: only the type of the failure is logged.
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][PV] Ignoring an unreadable free token file: {}",
                        e.getClass().getSimpleName());
                return null;
            }
        }

        static Held parse(JsonObject root) {
            if (root == null || !root.has("version") || root.get("version").getAsInt() != STORE_VERSION
                    || !root.has("token") || !root.has("uuid") || !root.has("expiresAt")) {
                return null;
            }
            String token = root.get("token").getAsString();
            String uuid = Identity.normalizeUuid(root.get("uuid").getAsString()).orElse(null);
            if (!TOKEN.matcher(token).matches() || uuid == null) {
                return null;
            }
            return new Held(token, uuid, root.get("expiresAt").getAsLong());
        }

        static JsonObject encode(Held held) {
            JsonObject root = new JsonObject();
            root.addProperty("version", STORE_VERSION);
            root.addProperty("uuid", held.uuid());
            root.addProperty("token", held.token());
            root.addProperty("expiresAt", held.expiresAtSeconds());
            return root;
        }

        @Override
        public void save(Held held) {
            String text = SBSFiles.GSON.toJson(encode(held));
            SbsExecutors.io().execute(() -> {
                Path path = path();
                try {
                    SBSFiles.ensureParent(path);
                    Path temp = path.resolveSibling("free_token.json.tmp");
                    try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                        writer.write(text);
                    }
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PV] Could not store the free token: {}",
                            e.getClass().getSimpleName());
                }
            });
        }

        @Override
        public void clear() {
            SbsExecutors.io().execute(() -> {
                try {
                    Files.deleteIfExists(path());
                } catch (IOException e) {
                    SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PV] Could not remove the free token file: {}",
                            e.getClass().getSimpleName());
                }
            });
        }
    }
}
