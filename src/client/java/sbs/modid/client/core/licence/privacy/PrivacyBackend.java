/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.licence.privacy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.SbsApi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The backend half of consent: mirroring each answer to the audit log, and asking the server to drop
 * what it holds for a scope the user has just withdrawn.
 *
 * <p><b>There is no "request my data" / "delete my data" here.</b> The screen used to carry those
 * two buttons; they were removed along with this class's half of them. A subject access or erasure
 * request is answered by a person within a month, not by an endpoint - erasure in particular is a
 * per-case review, because the licence is tied to a purchase, IP bindings exist to detect sharing,
 * and payment records carry retention obligations that a delete route cannot weigh. Reachable
 * contact details satisfy the obligation; a button that promised an action it could not perform was
 * worse than no button, because a user who clicked it reasonably believed a request had been filed.
 * When the backend can genuinely export and erase, this is where that lands.
 *
 * <p><b>Everything here is best-effort and none of it is load-bearing.</b> The routes it calls do
 * not exist on the deployed backend yet (they are Phase A of {@code docs/PRIVACY-BACKEND.md}), and
 * the client is written to be correct both before and after they appear. So: one attempt, never a
 * retry loop, failures at {@code debug} only, and no path by which a backend response can change
 * what the client believes the user answered. A user who grants a scope gets that scope working
 * immediately whether or not the audit write succeeded - the copy on the server is our evidence, not
 * their permission.
 *
 * <p><b>Why these calls are not themselves gated on a consent scope.</b> They run under
 * {@link ConsentScope#LICENCE_VALIDATION}, the contract scope, so they still carry a licence token
 * and still pass through the one choke point - but they are not refusable. Recording that consent
 * was given, and acting on a deletion request, are obligations that exist <i>because</i> of the
 * consent regime; making them opt-in would mean the only users we could evidence consent for are
 * the ones who consented to being evidenced.
 */
public final class PrivacyBackend {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final Gson GSON = new Gson();

    /**
     * One thread, daemon. Consent writes are rare (a click each) and must never block the render
     * thread that produced them, nor keep the JVM alive at shutdown.
     */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-Privacy");
        thread.setDaemon(true);
        return thread;
    });

    private PrivacyBackend() {
    }

    /**
     * Mirrors one answer to the append-only audit log. Fire-and-forget by design; see the class
     * note on why a failure here is not surfaced and not retried.
     */
    public static void recordConsent(String accountId, ConsentScope scope, ConsentState state) {
        if (scope == null || state == null || !SbsApi.hasLicence()) {
            return;
        }
        IO.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("accountId", accountId);
                body.addProperty("scopeId", scope.id());
                body.addProperty("granted", state.granted());
                body.addProperty("timestamp", state.grantedAt());
                body.addProperty("disclosureVersion", state.disclosureVersion());
                body.addProperty("source", state.source().id());
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: https://skyblocksimplified.info/api/privacy/consent
// METHOD: POST
// PURPOSE: Mirror a consent answer into the server audit log, so both sides hold a record of
//   what was agreed and when.
// DATA SENT: A JSON body of the consent decision itself - licence account id, scope id,
//   granted true/false, timestamp, disclosure version, and where the answer came from. Built
//   with Gson. No game data and no Mojang identifiers.
// DATA RECEIVED: Ignored. No backend response can change what the client believes the user
//   answered. One attempt, never a retry loop, failures at debug only.
// SAFETY DECLARATION: Sends the licence account id and the consent decision, which is the
//   evidence this call exists to create. No Mojang credentials, no session id, no uuid, no OS
//   telemetry. The copy on the server is our evidence, not the user permission - the local
//   answer stays authoritative. See PRIVACY.md and docs/PRIVACY-BACKEND.md.
// ============================================================================
                post("/api/privacy/consent", body);
            } catch (Throwable t) {
                SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Privacy] consent mirror failed: {}", t.toString());
            }
        });
    }

    /**
     * Asks the backend to delete what it holds for one withdrawn scope. Best-effort: the local
     * withdrawal has already stopped the traffic, so this only affects cleanup timing.
     */
    public static void requestScopeDeletion(ConsentScope scope) {
        if (scope == null || !SbsApi.hasLicence()) {
            return;
        }
        IO.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("scopeId", scope.id());
                post("/api/privacy/delete", body);
            } catch (Throwable t) {
                SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Privacy] scope deletion failed: {}", t.toString());
            }
        });
    }

    private static HttpResponse<String> post(String path, JsonObject body) throws Exception {
        HttpRequest request = SbsApi.withLicence(
                        HttpRequest.newBuilder(URI.create(SbsApi.base() + path))
                                .timeout(TIMEOUT)
                                .header("Content-Type", "application/json"))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        return SbsApi.send(ConsentScope.LICENCE_VALIDATION, HTTP, request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
