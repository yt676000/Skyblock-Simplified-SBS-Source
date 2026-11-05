/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.SbsApi;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.core.net.validation.MessageSchemas;
import sbs.modid.client.core.net.validation.MessageTypes;
import sbs.modid.client.core.net.validation.ValidationException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The verified SBS session that party, carry and IRC requests carry instead of a self-declared
 * name and uuid.
 *
 * <p>The handshake proves the account the same way joining a multiplayer server does:
 * <ol>
 *   <li>{@code POST /v1/session/challenge} (licence credential) returns a {@code serverId};</li>
 *   <li>the game's own session service is told we join that {@code serverId} - the exact vanilla
 *       call from {@code ClientHandshakePacketListenerImpl.authenticateServer}:
 *       {@code minecraft.services().sessionService().joinServer(user.getProfileId(),
 *       user.getAccessToken(), serverId)};</li>
 *   <li>{@code POST /v1/session/verify} with our own current name and the {@code serverId} returns
 *       the session, which the SBS server issues only after Mojang confirmed the join.</li>
 * </ol>
 *
 * <p>The Minecraft access token is read only as the argument of that vanilla call; it is never
 * stored, compared, logged or sent anywhere else. The SBS session lives in memory only and goes
 * only to the SBS origin in the {@value #HEADER} header (enforced again in
 * {@link Credentials#checkRequest}). A {@code 401 session_invalid} answer triggers exactly one new
 * handshake and one retry; any other failure stops with a neutral message.
 */
public final class SessionIdentity {

    public static final String HEADER = "X-SBS-Session";
    public static final String ORIGIN = "https://" + Credentials.SBS_HOST;

    public static final String MSG_UUID_LIMIT = "This licence is already used by the maximum number of accounts";
    public static final String MSG_IDENTITY_UNAVAILABLE = "Mojang session service unavailable, try again later";
    public static final String MSG_NOT_VERIFIED = "Could not verify your Minecraft session with SBS";
    public static final String MSG_FAILED = "SBS session could not be started, try again later";

    static final Pattern SERVER_ID = Pattern.compile("^[0-9a-f]{40}$");
    static final Pattern SESSION = Pattern.compile("^sbss_[0-9a-f]{64}$");
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    /** A session is renewed this long before the server's expiry, so a request never races it. */
    private static final long EXPIRY_MARGIN_S = 30;

    /** Sends one request to the SBS backend (production: {@link SbsApi#send} on SecureHttp). */
    @FunctionalInterface
    public interface Transport {
        HttpResponse<String> send(ConsentScope scope, String typeId, HttpRequest request)
                throws IOException, InterruptedException;
    }

    /** Tells Mojang's session service that this account joins {@code serverId}. */
    @FunctionalInterface
    public interface Joiner {
        void join(String serverId) throws Exception;
    }

    /** Adds the credential (licence, tester or free token) to a request for {@code uri} (production: {@link Credentials#apply}). */
    @FunctionalInterface
    public interface Auth {
        HttpRequest.Builder apply(HttpRequest.Builder builder, URI uri) throws IOException;
    }

    /** A handshake or session failure, carrying the message to show the player. */
    public static final class SessionException extends IOException {
        private final String userMessage;

        SessionException(String code, String userMessage) {
            super("session " + code);
            this.userMessage = userMessage;
        }

        public String userMessage() {
            return userMessage;
        }
    }

    private static final SessionIdentity INSTANCE = new SessionIdentity(
            (scope, typeId, request) -> SbsApi.send(scope, typeId, request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),
            SessionIdentity::vanillaJoin, SessionIdentity::ownName, Credentials::apply,
            () -> Credentials.current() == Credentials.Kind.FREE, System::currentTimeMillis,
            message -> SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Session] {}", message));

    private final Transport transport;
    private final Joiner joiner;
    private final Supplier<String> ownName;
    private final Auth auth;
    private final java.util.function.BooleanSupplier freeToken;
    private final LongSupplier clock;
    private final Consumer<String> log;

    /** In memory only; {@code null} until a handshake succeeded. */
    private String session;
    private long expiresAtSeconds;

    SessionIdentity(Transport transport, Joiner joiner, Supplier<String> ownName, Auth auth,
                    java.util.function.BooleanSupplier freeToken, LongSupplier clock, Consumer<String> log) {
        this.transport = transport;
        this.joiner = joiner;
        this.ownName = ownName;
        this.auth = auth;
        this.freeToken = freeToken;
        this.clock = clock;
        this.log = log;
    }

    public static SessionIdentity get() {
        return INSTANCE;
    }

    /**
     * Sends a request with the session header, running the handshake first when there is no valid
     * session. On {@code 401 session_invalid} the handshake is redone once and the request retried
     * once with a freshly built request. Must not be called on the render thread.
     *
     * @param builder builds the request without the session header; called once per attempt
     * @throws SessionException when the session cannot be established (message for the player)
     */
    public HttpResponse<String> send(ConsentScope scope, String typeId, Supplier<HttpRequest.Builder> builder)
            throws IOException, InterruptedException {
        String current = ensure(scope);
        HttpResponse<String> response = transport.send(scope, typeId, attach(builder.get(), current));
        if (response.statusCode() == 401 && isSessionInvalid(response.body())) {
            invalidate(current);
            String fresh = ensure(scope);
            response = transport.send(scope, typeId, attach(builder.get(), fresh));
        }
        return response;
    }

    /**
     * For routes where the session is optional (party list: eligibility): attaches the session
     * when one is already held and never starts a handshake for it. A {@code session_invalid}
     * answer drops the stale session and retries once without it.
     */
    public HttpResponse<String> sendOptional(ConsentScope scope, String typeId, Supplier<HttpRequest.Builder> builder)
            throws IOException, InterruptedException {
        String current = held();
        HttpResponse<String> response = transport.send(scope, typeId,
                current == null ? builder.get().build() : attach(builder.get(), current));
        if (current != null && response.statusCode() == 401 && isSessionInvalid(response.body())) {
            invalidate(current);
            response = transport.send(scope, typeId, builder.get().build());
        }
        return response;
    }

    /** The held session if still valid, else {@code null}; never starts a handshake. */
    private synchronized String held() {
        return session != null && clock.getAsLong() / 1000 < expiresAtSeconds - EXPIRY_MARGIN_S ? session : null;
    }

    /** True for a message produced by a failed handshake (stop, do not retry in a loop). */
    public static boolean isSessionMessage(String message) {
        return MSG_UUID_LIMIT.equals(message) || MSG_IDENTITY_UNAVAILABLE.equals(message)
                || MSG_NOT_VERIFIED.equals(message) || MSG_FAILED.equals(message);
    }

    /** Forgets the session (e.g. on logout or account switch). */
    public synchronized void clear() {
        session = null;
        expiresAtSeconds = 0;
    }

    private synchronized void invalidate(String used) {
        if (used != null && used.equals(session)) {
            clear();
        }
    }

    private static HttpRequest attach(HttpRequest.Builder builder, String session) throws IOException {
        HttpRequest request = builder.build();
        if (!isSbsOrigin(request.uri())) {
            throw new Credentials.CredentialRefusedException(request.uri());
        }
        return HttpRequest.newBuilder(request, (name, value) -> !HEADER.equalsIgnoreCase(name))
                .header(HEADER, session).build();
    }

    /** True only for {@code https://skyblocksimplified.info} on the default port, no user info. */
    public static boolean isSbsOrigin(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                && uri.getPort() == -1 && Credentials.SBS_HOST.equalsIgnoreCase(uri.getHost());
    }

    /** The current session, running the handshake when there is none or it is about to expire. */
    synchronized String ensure(ConsentScope scope) throws IOException, InterruptedException {
        long now = clock.getAsLong() / 1000;
        if (session != null && now < expiresAtSeconds - EXPIRY_MARGIN_S) {
            return session;
        }
        clear();
        String serverId = challenge(scope);
        try {
            joiner.join(serverId);
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception joinFailed) {
            // The exception text may describe the account; only its type is logged.
            log.accept("session join failed: " + joinFailed.getClass().getSimpleName());
            throw new SessionException("join_failed", MSG_NOT_VERIFIED);
        }
        verify(scope, serverId);
        return session;
    }

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/v1/session/challenge
// METHOD: POST
// PURPOSE: First step of the verified SBS session that party, carry and IRC requests carry: a
//   random, single-use serverId to join.
// DATA SENT: The body {} and the credential headers (Credentials.apply): the licence token, or for
//   a player with no licence the free token, which is tied to the player's uuid. No name, no uuid.
// DATA RECEIVED: The serverId (40 hex); parsed with Gson against a fixed schema.
// SAFETY DECLARATION: No Mojang credential, no session id. Sent under the caller's ConsentScope
//   (PARTY_FINDER, CARRY_TICKETS or IRC_IDENTITY) and refused until it is granted.
// ============================================================================
    private String challenge(ConsentScope scope) throws IOException, InterruptedException {
        URI uri = URI.create(ORIGIN + "/v1/session/challenge");
        HttpRequest request = auth.apply(HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                        .header("Content-Type", "application/json"), uri)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        HttpResponse<String> response = transport.send(scope, MessageTypes.SESSION_CHALLENGE, request);
        if (response.statusCode() != 200) {
            throw failure(response);
        }
        JsonObject body = parsed(MessageTypes.SESSION_CHALLENGE, response.body());
        String serverId = body.get("serverId").getAsString();
        if (!SERVER_ID.matcher(serverId).matches()) {
            throw invalid("challenge");
        }
        return serverId;
    }

// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/v1/session/verify
// METHOD: POST
// PURPOSE: Second step of the verified SBS session, after the game told Mojang it joins the
//   serverId (vanillaJoin below, the vanilla multiplayer join call).
// DATA SENT: A JSON body with the player's own Minecraft name and the serverId, plus the credential
//   headers (licence token, or the free token tied to the player's uuid). No Mojang access token.
// DATA RECEIVED: The SBS session (sbss_ + 64 hex), the verified uuid, name and expiry; parsed with
//   Gson against a fixed schema. The session is held in memory only, never written or logged.
// SAFETY DECLARATION: The server asks Mojang (hasJoined) and binds the session to the uuid Mojang
//   returns, so the session identifies the player to SBS - that is its purpose. Sent under the
//   caller's ConsentScope and refused until it is granted.
// ============================================================================
    private void verify(ConsentScope scope, String serverId) throws IOException, InterruptedException {
        String name = ownName.get();
        if (!Identity.isValidName(name)) {
            throw new SessionException("no_profile", MSG_NOT_VERIFIED);
        }
        JsonObject out = new JsonObject();
        out.addProperty("username", name);
        out.addProperty("serverId", serverId);
        MessageSchemas.checkOutgoing(MessageTypes.OUT_SESSION_VERIFY, out);
        URI uri = URI.create(ORIGIN + "/v1/session/verify");
        HttpRequest request = auth.apply(HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                        .header("Content-Type", "application/json"), uri)
                .POST(HttpRequest.BodyPublishers.ofString(out.toString())).build();
        HttpResponse<String> response = transport.send(scope, MessageTypes.SESSION_VERIFY, request);
        if (response.statusCode() != 200) {
            throw failure(response);
        }
        JsonObject body = parsed(MessageTypes.SESSION_VERIFY, response.body());
        String token = body.get("session").getAsString();
        if (!SESSION.matcher(token).matches()
                || Identity.normalizeUuid(body.get("uuid").getAsString()).isEmpty()
                || !Identity.isValidName(body.get("name").getAsString())) {
            throw invalid("verify");
        }
        session = token;
        expiresAtSeconds = body.get("expiresAt").getAsLong();
    }

    private JsonObject parsed(String typeId, String body) throws SessionException {
        try {
            return MessageSchemas.parse(typeId, body).getAsJsonObject();
        } catch (ValidationException | IllegalStateException e) {
            throw invalid(typeId);
        }
    }

    private SessionException invalid(String where) {
        log.accept("session handshake: invalid " + where + " response");
        return new SessionException("invalid", ValidationException.USER_MESSAGE);
    }

    /** Maps a handshake error answer to the message for the player; logs the code only. */
    private SessionException failure(HttpResponse<String> response) {
        String code = errorCode(response.body());
        log.accept("session handshake refused: HTTP " + response.statusCode() + " " + code);
        return new SessionException(code, switch (code) {
            case "licence_uuid_limit" -> MSG_UUID_LIMIT;
            case "identity_unavailable" -> MSG_IDENTITY_UNAVAILABLE;
            case "session_not_verified", "bad_challenge" -> MSG_NOT_VERIFIED;
            default -> response.statusCode() == 401 && freeToken.getAsBoolean()
                    ? Credentials.FREE_TOKEN_REJECTED : MSG_FAILED;
        });
    }

    /** The {@code error} code of an SBS error body, reduced to a safe charset; "unknown" otherwise. */
    static String errorCode(String body) {
        try {
            JsonElement root = body == null ? null : JsonParser.parseString(body);
            if (root != null && root.isJsonObject() && root.getAsJsonObject().has("error")
                    && root.getAsJsonObject().get("error").isJsonPrimitive()) {
                String code = root.getAsJsonObject().get("error").getAsString();
                if (code.matches("^[a-z_]{1,40}$")) {
                    return code;
                }
            }
        } catch (RuntimeException ignored) {
            // not JSON: treated as unknown
        }
        return "unknown";
    }

    static boolean isSessionInvalid(String body) {
        return "session_invalid".equals(errorCode(body));
    }

    // ------------------------------------------------------------------
    // Production hooks
    // ------------------------------------------------------------------

    /** The player's own current name from the game profile. */
    private static String ownName() {
        return Minecraft.getInstance().getUser().getName();
    }

    /**
     * The vanilla join call ({@code ClientHandshakePacketListenerImpl.authenticateServer}). The
     * access token goes straight from {@code User} into the session service and nowhere else.
     */
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: Mojang session service (sessionserver.mojang.com), through the game's own
//   minecraft.services().sessionService().joinServer - the call vanilla makes when joining a server.
// METHOD: POST (inside the game's session service)
// PURPOSE: Prove to SBS which Minecraft account is asking (verified session, free token), without
//   SBS ever seeing the account's credentials.
// DATA SENT: The serverId from SBS, the account's profile id and the game's own session access
//   token - all to Mojang only. The access token is passed only into that call; it is never stored,
//   logged or sent to SBS.
// DATA RECEIVED: Nothing is read beyond success or failure.
// SAFETY DECLARATION: Identical to a normal multiplayer join. Runs only after an SBS challenge was
//   sent, which is refused until the consent of the feature asking is granted.
// ============================================================================
    static void vanillaJoin(String serverId) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            throw new IllegalStateException("session join on the render thread");
        }
        minecraft.services().sessionService().joinServer(minecraft.getUser().getProfileId(),
                minecraft.getUser().getAccessToken(), serverId);
    }
}
