/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.player;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.ApiFailure;
import sbs.modid.client.core.api.SbsApi;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.core.net.Credentials;
import sbs.modid.client.core.net.FreeAccess;
import sbs.modid.client.core.net.Identity;
import sbs.modid.client.core.net.SecureHttp;
import sbs.modid.client.core.net.validation.MessageSchemas;
import sbs.modid.client.core.net.validation.MessageTypes;
import sbs.modid.client.core.net.validation.ValidationException;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.social.playerviewer.logic.PvCompute;
import sbs.modid.client.social.playerviewer.logic.PvInvalidDataException;
import sbs.modid.client.social.playerviewer.logic.PvJson;
import sbs.modid.client.social.playerviewer.logic.PvModel;
import sbs.modid.client.social.playerviewer.logic.PvParser;
import sbs.modid.client.social.playerviewer.logic.PvResources;
import sbs.modid.client.social.playerviewer.logic.PvSchema;
import sbs.modid.client.social.playerviewer.logic.PvSections;
import sbs.modid.client.social.playerviewer.logic.PvSideSections;
import sbs.modid.client.social.playerviewer.logic.PvValidator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Network side of the Player Viewer. The SBS backend now only relays Hypixel: the profile list
 * comes from {@code /api/hypixel/profiles?uuid=} and one profile from
 * {@code /v1/playerviewer/{uuid}/{profileId}} (raw {@code /skyblock/profile} body plus
 * {@code X-SBS-Schema-Version}). Everything the viewer shows is computed on the client by
 * {@link PvCompute}; nothing the server sends is rendered without passing {@link PvParser}.
 *
 * <p>Transport is {@link SecureHttp} (https, pins, size caps per message type id
 * {@code relay.pv.*}), credentials via {@link Credentials}. The legacy {@code /api/pv} route is no
 * longer called (the server answers it with 410).
 *
 * <p>All requests run on one daemon worker; callbacks run on that worker too and must only hand
 * their result over to the render thread through a volatile field.
 */
public final class PlayerProfileApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** Relayed bodies are full Hypixel profiles; a coop of heavy players reaches several MiB. */
    private static final long RESOURCE_TTL_MS = 6L * 60 * 60 * 1000;
    private static final String NEUTRAL = "SBS server returned invalid data";

    private static final PlayerProfileApi INSTANCE = new PlayerProfileApi();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-PlayerViewer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile List<PvResources.CollectionCategory> collections = List.of();
    private volatile long collectionsAt;

    /**
     * Exactly one of the value / error is non-null. {@code refused} is the consent scope when the
     * error is a consent refusal ({@link ApiFailure#NO_CONSENT}), so the screen can offer the way to
     * the setting; {@code null} otherwise.
     */
    public record Result<T>(T value, String error, ConsentScope refused) {
        public Result(T value, String error) {
            this(value, error, null);
        }

        /** The result for a request that threw: the mapped text, and the scope when it was a refusal. */
        static <T> Result<T> failed(Throwable failure) {
            return new Result<>(null, ApiFailure.message(failure),
                    ApiFailure.of(failure) == ApiFailure.NO_CONSENT ? ApiFailure.refusedScope(failure) : null);
        }
    }

    /** A resolved player and the profiles they are a member of (selected first). */
    public record Profiles(String name, String uuid, List<PvModel.ProfileRef> profiles) {
    }

    /** Other players' computed data, in memory only; see {@link PvCache}. */
    private final PvCache cache = new PvCache(System::currentTimeMillis, PvCache.Memory.RUNTIME,
            () -> sbs.modid.client.core.licence.privacy.ConsentManager.isGranted(ConsentScope.PROFILE_DATA),
            PlayerProfileApi::credentialFingerprint);
    /** Name to uuid answers from Mojang, so reopening a player by name sends no lookup either. */
    private final java.util.Map<String, String> nameToUuid = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > 64;
                }
            });

    private PlayerProfileApi() {
    }

    /** The consent registry the withdrawal listener is attached to (it is per account). */
    private Object listenedRegistry;

    /** The viewer was closed. Past the TTL with it still closed, the cache is dropped. */
    public void viewerClosed() {
        cache.viewerClosed();
        java.util.concurrent.CompletableFuture.delayedExecutor(PvCache.TTL_MS + 1000,
                        java.util.concurrent.TimeUnit.MILLISECONDS, sbs.modid.client.core.async.SbsExecutors.worker())
                .execute(cache::expireIfClosed);
    }

    /** The viewer was opened. */
    public void viewerOpened() {
        // Withdrawing profile data drops what was fetched under it at once. Attached here, on first
        // use, rather than at class load: the registry is per account and needs a logged-in user.
        // PvCache also checks the consent on every lookup, which covers anything this misses.
        var registry = sbs.modid.client.core.licence.privacy.ConsentManager.getInstance().registry();
        synchronized (this) {
            if (registry != listenedRegistry) {
                listenedRegistry = registry;
                registry.addListener((scope, granted) -> {
                    if (scope == ConsentScope.PROFILE_DATA && !granted) {
                        clearCache();
                    }
                });
            }
        }
        cache.viewerOpened();
    }

    /**
     * Changes whenever the credential changes (licence entered or removed, free token re-claimed),
     * so {@link PvCache} drops what was fetched with the old one. A hash, never the token itself.
     */
    private static String credentialFingerprint() {
        return Integer.toHexString(sbs.modid.client.core.config.LicenceToken.getInstance().get().hashCode())
                + ":" + FreeAccess.get().fingerprint();
    }

    /** The cache, for the screen (refresh, fetch time) and for world and consent resets. */
    public PvCache cache() {
        return cache;
    }

    /** Drops everything cached. Called on disconnect and when profile data consent is withdrawn. */
    public void clearCache() {
        cache.clear();
        nameToUuid.clear();
    }

    /** World left: nothing about other players stays in memory past the session. */
    public void onWorldLeave() {
        clearCache();
    }

    /**
     * Refresh for the shown player: drops its list, profiles, museum and status. False while the
     * 10 s cooldown runs ({@link PvCache#refreshCooldownMs}).
     */
    public boolean refresh(String uuid) {
        String plain = Identity.normalizeUuid(uuid).orElse(null);
        return plain != null && cache.refresh(plain);
    }

    /** When the shown profile view was fetched (server time when the relay said so), or -1. */
    public long viewFetchedAt(String uuid, String profileId) {
        String plain = Identity.normalizeUuid(uuid).orElse(null);
        String id = Identity.normalizeUuid(profileId).orElse(null);
        return plain == null || id == null ? -1 : cache.fetchedAt(plain, PvCache.viewKey(id));
    }

    /** The UTF-8 length of {@code text} without encoding it: the size estimate for the cache. */
    static long utf8Length(String text) {
        long n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c)) {
                n += 4;
                i++;
            } else {
                n += 3;
            }
        }
        return n;
    }

    public static PlayerProfileApi getInstance() {
        return INSTANCE;
    }

    /**
     * Resolves {@code name} (unless {@code knownUuid} is given, e.g. from the tab list) and loads
     * the profile list.
     */
    public void profiles(String name, String knownUuid, Consumer<Result<Profiles>> callback) {
        executor.execute(() -> {
            try {
                String uuid = Identity.normalizeUuid(knownUuid).orElse(null);
                if (uuid == null) {
                    if (!Identity.isValidName(name)) {
                        callback.accept(new Result<>(null, "Invalid player name"));
                        return;
                    }
                    String key = name.toLowerCase(java.util.Locale.ROOT);
                    uuid = nameToUuid.get(key);
                    if (uuid == null) {
                        uuid = mojangUuid(name);
                        if (uuid == null) {
                            callback.accept(new Result<>(null, "Unknown player"));
                            return;
                        }
                        nameToUuid.put(key, uuid);
                    }
                }
                String player = uuid;
                callback.accept(cache.<Result<Profiles>>get(player, PvCache.listKey(),
                        () -> loadProfiles(name, player)).value());
            } catch (Exception e) {
                ApiFailure.log("[SBS][PV]", "Profile list", e);
                callback.accept(Result.failed(e));
            }
        });
    }

    private PvCache.Loaded<Result<Profiles>> loadProfiles(String name, String uuid) throws Exception {
            try {
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/api/hypixel/profiles?uuid=<uuid>
// METHOD: GET
// PURPOSE: The Player Viewer's profile list - which SkyBlock profiles the looked-up player has.
// DATA SENT: The looked-up player's uuid (validated hex) in the query.
//   Credential: the licence token, or a hand-entered tester token, or - with no token entered -
//   the automatic free PlayerViewer token from FreeAccess (Authorization: Bearer and X-Api-Token).
// DATA RECEIVED: The relayed public Hypixel profiles body. Size-capped, parsed by the bounded
//   PvParser; only profile ids, names, selection and membership are read.
// SAFETY DECLARATION: A licence token identifies the purchase, not the player; a free token is
//   issued for the player's Mojang-verified uuid and does identify the player to SBS. No Mojang credentials, no session id. Refused until ConsentScope.PROFILE_DATA is
//   granted; with no token entered, the free token is claimed only then (see FreeAccess).
// ============================================================================
                Fetched body = get(SbsApi.base() + "/api/hypixel/profiles?uuid=" + uuid, MessageTypes.PV_PROFILES);
                if (body.status != 200) {
                    return PvCache.Loaded.uncached(new Result<>(null, httpError(body.status)));
                }
                List<PvModel.ProfileRef> list = PvParser.parseProfileList(body.text, uuid);
                List<PvModel.ProfileRef> ordered = new ArrayList<>();
                list.stream().filter(PvModel.ProfileRef::selected).forEach(ordered::add);
                list.stream().filter(p -> !p.selected()).forEach(ordered::add);
                // A list is a few ids and names; 128 bytes a profile is a generous estimate.
                return new PvCache.Loaded<>(new Result<>(new Profiles(name, uuid, ordered), null), true,
                        256 + 128L * ordered.size(), body.fetchedAtMs);
            } catch (PvInvalidDataException e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PV] Rejected profile list: {}", e.getMessage());
                return PvCache.Loaded.uncached(new Result<>(null, PvSchema.UNSUPPORTED));
            } catch (ValidationException e) {
                return PvCache.Loaded.uncached(new Result<>(null, PvSchema.UNSUPPORTED));
            }
    }

    /** Loads and computes one profile. The view is never null: on bad data it is the minimal view. */
    public void profile(String uuid, PvModel.ProfileRef ref, Consumer<Result<JsonObject>> callback) {
        executor.execute(() -> {
            String plain = Identity.normalizeUuid(uuid).orElse(null);
            String profileId = Identity.normalizeUuid(ref.profileId()).orElse(null);
            if (plain == null || profileId == null) {
                callback.accept(new Result<>(null, NEUTRAL));
                return;
            }
            try {
                callback.accept(cache.<Result<JsonObject>>get(plain, PvCache.viewKey(profileId),
                        () -> loadProfile(plain, profileId, ref)).value());
            } catch (Exception e) {
                ApiFailure.log("[SBS][PV]", "Profile request", e);
                callback.accept(Result.failed(e));
            }
        });
    }

    /**
     * Fetches and computes one profile. Only the computed view is kept: the relayed text and every
     * intermediate are local to this method and gone once it returns.
     */
    private PvCache.Loaded<Result<JsonObject>> loadProfile(String plain, String profileId, PvModel.ProfileRef ref)
            throws Exception {
            try {
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/v1/playerviewer/<uuid>/<profileId>
// METHOD: GET
// PURPOSE: The Player Viewer - one public SkyBlock profile, relayed unchanged from Hypixel.
// DATA SENT: The looked-up player's uuid and the profile id (both validated hex) in the path.
//   Credential: the licence token, or a hand-entered tester token, or - with no token entered -
//   the automatic free PlayerViewer token from FreeAccess (Authorization: Bearer and X-Api-Token).
// DATA RECEIVED: The raw public Hypixel /skyblock/profile body. Size-capped, depth-capped,
//   parsed into fixed records by PvParser; all calculations happen on the client.
// SAFETY DECLARATION: A licence token identifies the purchase, not the player; a free token is
//   issued for the player's Mojang-verified uuid and does identify the player to SBS. No Mojang credentials, no session id. Refused until ConsentScope.PROFILE_DATA is
//   granted; with no token entered, the free token is claimed only then (see FreeAccess).
// ============================================================================
                Fetched body = get(SbsApi.base() + "/v1/playerviewer/" + plain + "/" + profileId, MessageTypes.PV_PROFILE);
                if (body.status != 200) {
                    return PvCache.Loaded.uncached(new Result<>(null, httpError(body.status)));
                }
                PvSchema.Outcome out = PvSchema.load(body.schema, body.text, plain, resources(),
                        PvCompute.LIMITED, System.currentTimeMillis());
                JsonObject view = out.view();
                PvCompute.addProfileMeta(view, ref);
                return new PvCache.Loaded<>(new Result<>(view, null), true, utf8Length(view.toString()),
                        body.fetchedAtMs);
            } catch (ValidationException e) {
                // The minimal fallback view is shown, never cached: the next open tries again.
                JsonObject view = PvSchema.minimal().view();
                PvCompute.addProfileMeta(view, ref);
                return PvCache.Loaded.uncached(new Result<>(view, null));
            }
    }

    /**
     * A side section (museum or status): {@code view} is the section's view object or {@code null}
     * for "no data"; {@code invalid} is true when the body failed validation (show unavailable).
     */
    public record Side(JsonObject view, boolean invalid) {
    }

    /** The museum of one profile; requested only while the museum page is open. */
    public void museum(String uuid, String profileId, Consumer<Result<Side>> callback) {
        String plain = Identity.normalizeUuid(uuid).orElse(null);
        String id = Identity.normalizeUuid(profileId).orElse(null);
        if (plain == null || id == null) {
            callback.accept(new Result<>(null, NEUTRAL));
            return;
        }
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/v1/playerviewer/museum/<uuid>/<profileId>
// METHOD: GET
// PURPOSE: The Player Viewer's museum page - the public museum of one profile, relayed from
//   Hypixel. Only requested while that page is open.
// DATA SENT: The looked-up player's uuid and the profile id (validated hex) in the path.
//   Credential: the licence token, or a hand-entered tester token, or - with no token entered -
//   the automatic free PlayerViewer token from FreeAccess (Authorization: Bearer and X-Api-Token).
// DATA RECEIVED: The raw public Hypixel /skyblock/museum body; size-capped and validated by
//   PvSideSections.
// SAFETY DECLARATION: A licence token identifies the purchase, not the player; a free token is
//   issued for the player's Mojang-verified uuid and does identify the player to SBS. No Mojang credentials, no session id. Refused until ConsentScope.PROFILE_DATA is
//   granted; with no token entered, the free token is claimed only then (see FreeAccess).
// ============================================================================
        side(plain, PvCache.museumKey(id), SbsApi.base() + "/v1/playerviewer/museum/" + plain + "/" + id,
                MessageTypes.PV_MUSEUM, PvSections.MUSEUM, text -> PvSideSections.museum(text, plain), callback);
    }

    /** Online status and location; polled at most every 60 s while a page showing it is open. */
    public void status(String uuid, Consumer<Result<Side>> callback) {
        String plain = Identity.normalizeUuid(uuid).orElse(null);
        if (plain == null) {
            callback.accept(new Result<>(null, NEUTRAL));
            return;
        }
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/v1/playerviewer/status/<uuid>
// METHOD: GET
// PURPOSE: The Player Viewer's online marker - whether the looked-up player is online and where,
//   relayed from Hypixel /status. Polled at most once a minute, only while it is on screen.
// DATA SENT: The looked-up player's uuid (validated hex) in the path.
//   Credential: the licence token, or a hand-entered tester token, or - with no token entered -
//   the automatic free PlayerViewer token from FreeAccess (Authorization: Bearer and X-Api-Token).
// DATA RECEIVED: The raw public Hypixel /status body; size-capped and validated by PvSideSections.
// SAFETY DECLARATION: A licence token identifies the purchase, not the player; a free token is
//   issued for the player's Mojang-verified uuid and does identify the player to SBS. No Mojang credentials, no session id. Refused until ConsentScope.PROFILE_DATA is
//   granted; with no token entered, the free token is claimed only then (see FreeAccess).
// ============================================================================
        side(plain, PvCache.statusKey(), SbsApi.base() + "/v1/playerviewer/status/" + plain,
                MessageTypes.PV_STATUS, PvSections.LOCATION, PvSideSections::location, callback);
    }

    @FunctionalInterface
    private interface SideParser {
        JsonObject parse(String body) throws PvInvalidDataException;
    }

    private void side(String player, String key, String url, String typeId, String section, SideParser parser,
                      Consumer<Result<Side>> callback) {
        executor.execute(() -> {
            try {
                callback.accept(cache.<Result<Side>>get(player, key,
                        () -> loadSide(url, typeId, section, parser)).value());
            } catch (Exception e) {
                ApiFailure.log("[SBS][PV]", section + " request", e);
                callback.accept(Result.failed(e));
            }
        });
    }

    private PvCache.Loaded<Result<Side>> loadSide(String url, String typeId, String section, SideParser parser)
            throws Exception {
        try {
            Fetched body = get(url, typeId);
            if (body.status != 200) {
                return PvCache.Loaded.uncached(new Result<>(null, httpError(body.status)));
            }
            try {
                JsonObject view = parser.parse(body.text);
                return new PvCache.Loaded<>(new Result<>(new Side(view, false), null), true,
                        view == null ? 64 : utf8Length(view.toString()), body.fetchedAtMs);
            } catch (PvInvalidDataException e) {
                PvParser.logOnce(section, e.getMessage());
                return PvCache.Loaded.uncached(new Result<>(new Side(null, true), null));
            }
        } catch (ValidationException e) {
            // Outer relayed-schema or size check failed: this section only.
            PvParser.logOnce(section, e.kind() + " " + e.path());
            return PvCache.Loaded.uncached(new Result<>(new Side(null, true), null));
        }
    }

    private static String httpError(int status) {
        if (status == 401 && Credentials.current() == Credentials.Kind.FREE) {
            return Credentials.FREE_TOKEN_REJECTED;
        }
        return switch (status) {
            case 401, 403 -> "SBS token was refused - try again in a moment";
            case 404 -> "Profile not found";
            case 429 -> "Rate limited - try again shortly";
            default -> "Server error (HTTP " + status + ")";
        };
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    /** Accessory catalogue from the shared item catalogue, collections from Hypixel (cached). */
    private PvResources resources() {
        List<String[]> rows = new ArrayList<>();
        for (SkyBlockItemCatalog.Entry e : SkyBlockItemCatalog.getInstance().all()) {
            if ("ACCESSORY".equals(e.category)) {
                rows.add(new String[]{e.id, e.name, e.tier});
            }
        }
        java.util.Map<String, PvResources.Rung> ladders = new java.util.HashMap<>();
        for (sbs.modid.client.helper.inventory.model.Accessory a
                : sbs.modid.client.helper.inventory.logic.AccessoryCatalog.all()) {
            if (a.hasFamily()) {
                ladders.put(a.id, new PvResources.Rung(a.family, a.step));
            }
        }
        return new PvResources(PvResources.accessories(rows), collections(), ladders);
    }

    private List<PvResources.CollectionCategory> collections() {
        if (!collections.isEmpty() && System.currentTimeMillis() - collectionsAt < RESOURCE_TTL_MS) {
            return collections;
        }
        try {
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://api.hypixel.net/v2/resources/skyblock/collections
// METHOD: GET
// PURPOSE: Collection names and tier thresholds for the Player Viewer's collections page.
// DATA SENT: Nothing in the URL and no body.
// DATA RECEIVED: Read-only public JSON (size-capped), parsed by the bounded PvResources parser.
// SAFETY DECLARATION: A keyless public endpoint - no credentials, uuids or telemetry are sent.
// ============================================================================
            String text = external("https://api.hypixel.net/v2/resources/skyblock/collections",
                    MessageTypes.HYPIXEL_COLLECTIONS);
            collections = PvResources.parseCollections(text);
            collectionsAt = System.currentTimeMillis();
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PV] Collections resource unavailable: {}",
                    e.getClass().getSimpleName());
        }
        return collections;
    }

    // ------------------------------------------------------------------
    // Name -> uuid
    // ------------------------------------------------------------------

    private String mojangUuid(String name) throws IOException, InterruptedException {
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://api.mojang.com/users/profiles/minecraft/<name>
// METHOD: GET
// PURPOSE: Resolves the name the user typed or clicked to a uuid, when the player is not in the
//   tab list.
// DATA SENT: The validated name ([A-Za-z0-9_]{3,16}) in the path. No credentials.
// DATA RECEIVED: Read-only public JSON; only the id is read and validated as a uuid.
// SAFETY DECLARATION: Mojang's public lookup; needs no authentication.
// ============================================================================
        String text;
        try {
            text = external("https://api.mojang.com/users/profiles/minecraft/" + name, MessageTypes.MOJANG_PROFILE);
        } catch (SecureHttp.HttpStatusException notFound) {
            return null;
        }
        try {
            JsonElement root = PvJson.parse(text);
            return root.isJsonObject()
                    ? Identity.normalizeUuid(PvValidator.str(root.getAsJsonObject(), "id", 64)).orElse(null) : null;
        } catch (PvInvalidDataException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /** {@code fetchedAtMs}: the relay's {@code X-SBS-Fetched-At} when present, else local time. */
    private record Fetched(int status, String text, String schema, long fetchedAtMs) {
    }

    /**
     * One SBS relay request on the central transport: {@link Credentials#apply}, consent and licence
     * gate in {@link SbsApi#send}, https, pins and the size cap registered for {@code typeId} in
     * {@link SecureHttp}. A 200 body is also checked against the type's relayed schema (outer
     * layer) before {@link PvParser} reads it (inner layer).
     */
    private Fetched get(String url, String typeId) throws IOException, InterruptedException {
        URI uri = SecureHttp.requireHttps(URI.create(url));
        if (!Credentials.usesAutomaticFreeToken(uri)) {
            return fetch(uri, typeId).result();
        }
        // No token entered: the automatic free token, re-claimed once on 401, then fail closed.
        return FreeAccess.get().call(() -> fetch(uri, typeId));
    }

    private FreeAccess.Attempt<Fetched> fetch(URI uri, String typeId) throws IOException, InterruptedException {
        HttpRequest request = Credentials.apply(HttpRequest.newBuilder(uri)
                        .timeout(TIMEOUT)
                        .header("Accept-Encoding", "gzip"), uri)
                .GET()
                .build();
        HttpResponse<byte[]> response = SbsApi.send(ConsentScope.PROFILE_DATA, typeId, request,
                HttpResponse.BodyHandlers.ofByteArray());
        String text = SecureHttp.inflateText(response, typeId);
        if (response.statusCode() == 200) {
            MessageSchemas.parse(typeId, text);
        }
        String schema = response.headers().firstValue("X-SBS-Schema-Version").orElse(null);
        String token = request.headers().firstValue("X-Api-Token").orElse(null);
        long fetchedAt = response.headers().firstValue("X-SBS-Fetched-At")
                .flatMap(PlayerProfileApi::epochSeconds).map(seconds -> seconds * 1000)
                .orElse(System.currentTimeMillis());
        return new FreeAccess.Attempt<>(new Fetched(response.statusCode(), text, schema, fetchedAt),
                response.statusCode(), token);
    }

    /** A plausible epoch-seconds header value, or empty. */
    static java.util.Optional<Long> epochSeconds(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds > 1_500_000_000L && seconds < 32_503_680_000L
                    ? java.util.Optional.of(seconds) : java.util.Optional.empty();
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /** A keyless third-party request (Mojang, Hypixel resources) on the central transport. */
    private static String external(String url, String typeId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(SecureHttp.requireHttps(URI.create(url)))
                .timeout(TIMEOUT).header("User-Agent", "SkyblockSimplifiedSBS").GET().build();
        HttpResponse<byte[]> response = SecureHttp.send(typeId, request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new SecureHttp.HttpStatusException(response.statusCode());
        }
        String text = SecureHttp.inflateText(response, typeId);
        MessageSchemas.parse(typeId, text);
        return text;
    }
}
