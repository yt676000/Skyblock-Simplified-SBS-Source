/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net;

import com.google.gson.JsonElement;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.net.validation.JsonLimits;
import sbs.modid.client.core.net.validation.MessageSchemas;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;

/**
 * The one HTTP transport for every request the mod makes.
 *
 * <p>Rules applied to every request: the URI must be {@code https} (anything else is refused before
 * a connection is made); the JDK default trust store and hostname verification are used, with an
 * optional SPKI pin check per host ({@link PinningTrustManager}); redirects are not followed; a
 * connect timeout, a per-request timeout and an overall deadline bound the call; the body is read
 * through a counting subscriber that cancels the exchange as soon as the type's size cap is passed;
 * gzip bodies are inflated under the same cap. Request and response details are logged only through
 * {@link Redact}.
 */
public final class SecureHttp {

    /** System property toggling certificate pinning. Default: on when pins are configured. */
    public static final String PINNING_PROPERTY = "sbs.network.pinning";

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofSeconds(30);

    /**
     * SPKI pins per host (base64 SHA-256 of SubjectPublicKeyInfo); each host lists a primary and at
     * least one backup key. Empty: no host is pinned in this build.
     */
    static final Map<String, Set<String>> PINS = Map.of();

    private static volatile HttpClient client;

    private SecureHttp() {
    }

    /** Whether pin checks are active: the property when set, otherwise whether any pins exist. */
    public static boolean pinningEnabled() {
        String property = System.getProperty(PINNING_PROPERTY);
        if (property != null) {
            return Boolean.parseBoolean(property.trim());
        }
        return !PINS.isEmpty();
    }

    /** The shared client: TLS with default trust plus pins, no redirects, daemon threads. */
    public static HttpClient client() {
        HttpClient local = client;
        if (local == null) {
            synchronized (SecureHttp.class) {
                local = client;
                if (local == null) {
                    local = build(PINS, pinningEnabled());
                    client = local;
                }
            }
        }
        return local;
    }

    static HttpClient build(Map<String, Set<String>> pins, boolean pinning) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {
                    new PinningTrustManager(PinningTrustManager.platformDefault(), pins, pinning)}, null);
            SSLParameters parameters = context.getDefaultSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            return HttpClient.newBuilder()
                    .sslContext(context)
                    .sslParameters(parameters)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("TLS unavailable", failure);
        }
    }

    /**
     * Attaches the entered credential; a tester token is refused for routes that do not accept it.
     * See {@link Credentials}.
     */
    public static HttpRequest.Builder withAuth(HttpRequest.Builder builder, URI uri) throws IOException {
        return Credentials.apply(builder, uri);
    }

    /** Refuses anything that is not an absolute {@code https} URI with a host. */
    public static URI requireHttps(URI uri) throws IOException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null) {
            throw new InsecureUriException(uri);
        }
        return uri;
    }

    /** Refuses anything that is not an absolute {@code wss} URI with a host. */
    public static URI requireWss(URI uri) throws IOException {
        if (uri == null || !"wss".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null) {
            throw new InsecureUriException(uri);
        }
        return uri;
    }

    /** Thrown for a non-TLS or otherwise unacceptable URI. */
    public static final class InsecureUriException extends IOException {
        InsecureUriException(URI uri) {
            super("refused non-https request to " + Redact.uri(uri));
        }
    }

    /** Thrown when a body passes its size cap; the exchange has been cancelled. */
    public static final class BodyTooLargeException extends IOException {
        public BodyTooLargeException(long limit) {
            super("response body exceeded " + limit + " bytes");
        }
    }

    /**
     * Sends a request on the shared client: https only, the request's own timeout (or the default)
     * as the read timeout, {@code total} as the overall deadline, and the body capped at
     * {@code maxBytes} while it streams.
     */
    public static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler, long maxBytes,
                                           Duration total) throws IOException, InterruptedException {
        requireHttps(request.uri());
        Credentials.checkRequest(request);
        HttpRequest bounded = request.timeout().isPresent() ? request
                : HttpRequest.newBuilder(request, (name, value) -> true).timeout(DEFAULT_READ_TIMEOUT).build();
        CompletableFuture<HttpResponse<T>> future = client().sendAsync(bounded, limited(handler, maxBytes));
        try {
            return future.get(total.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            future.cancel(true);
            throw new java.net.http.HttpTimeoutException("request exceeded its overall deadline: "
                    + Redact.uri(request.uri()));
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            for (Throwable t = cause; t != null; t = t.getCause()) {
                if (t instanceof BodyTooLargeException tooLarge) {
                    throw tooLarge;
                }
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("request failed: " + Redact.uri(request.uri()), cause);
        }
    }

    /** {@link #send} with the default deadline and the cap registered for {@code typeId}. */
    public static <T> HttpResponse<T> send(String typeId, HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return send(request, handler, MessageSchemas.maxBytes(typeId), totalFor(request));
    }

    /**
     * Fetches, checks the status, inflates gzip under the cap and validates against {@code typeId}.
     * A non-200 status is an {@link HttpStatusException}.
     */
    public static JsonElement fetchJson(String typeId, HttpRequest request) throws IOException, InterruptedException {
        long cap = MessageSchemas.maxBytes(typeId);
        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray(), cap, totalFor(request));
        if (response.statusCode() != 200) {
            throw new HttpStatusException(response.statusCode());
        }
        return MessageSchemas.parse(typeId, decodeBody(response, cap));
    }

    /** A response arrived but its status was not the expected one. */
    public static final class HttpStatusException extends IOException {
        private final int status;

        public HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** The body bytes, inflated when the response is gzip-encoded, never more than {@code cap}. */
    public static byte[] decodeBody(HttpResponse<byte[]> response, long cap) throws IOException {
        byte[] body = response.body();
        boolean gzip = response.headers().firstValue("Content-Encoding").map(v -> v.toLowerCase(java.util.Locale.ROOT)
                .contains("gzip")).orElse(false);
        return gzip ? gunzip(body, cap) : body;
    }

    /**
     * The body as UTF-8 text, inflated when gzip-encoded (by header or magic bytes), never more than
     * the cap registered for {@code typeId}.
     */
    public static String inflateText(HttpResponse<byte[]> response, String typeId) throws IOException {
        byte[] raw = response.body() == null ? new byte[0] : response.body();
        long cap = MessageSchemas.maxBytes(typeId);
        boolean gzip = response.headers().firstValue("Content-Encoding")
                .map(v -> v.toLowerCase(java.util.Locale.ROOT).contains("gzip")).orElse(false)
                || (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B);
        return new String(gzip ? gunzip(raw, cap) : raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Inflates gzip, failing as soon as the output passes {@code cap}. */
    public static byte[] gunzip(byte[] compressed, long cap) throws IOException {
        if (compressed == null) {
            return new byte[0];
        }
        try (InputStream in = new NbtLimits.LimitedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(compressed)), cap)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(compressed.length * 4, 1 << 20));
            in.transferTo(out);
            return out.toByteArray();
        } catch (NbtLimits.LimitExceededException over) {
            throw new BodyTooLargeException(cap);
        }
    }

    /** Wraps an input stream (for streamed downloads) so reading past {@code cap} fails. */
    public static InputStream limit(InputStream in, long cap) {
        return new NbtLimits.LimitedInputStream(in, cap);
    }

    private static Duration totalFor(HttpRequest request) {
        Duration read = request.timeout().orElse(DEFAULT_READ_TIMEOUT);
        Duration total = read.plusSeconds(5);
        return total.compareTo(DEFAULT_TOTAL_TIMEOUT) > 0 ? total : DEFAULT_TOTAL_TIMEOUT;
    }

    /** Caps any body handler: the exchange is cancelled once more than {@code maxBytes} arrive. */
    public static <T> HttpResponse.BodyHandler<T> limited(HttpResponse.BodyHandler<T> inner, long maxBytes) {
        return info -> {
            long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > maxBytes) {
                return new RejectingSubscriber<>(maxBytes);
            }
            return new LimitedSubscriber<>(inner.apply(info), maxBytes);
        };
    }

    /** Fails the body immediately (declared length already over the cap). */
    private static final class RejectingSubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final long limit;

        RejectingSubscriber(long limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<T> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
            result.completeExceptionally(new BodyTooLargeException(limit));
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(new BodyTooLargeException(limit));
        }

        @Override
        public void onComplete() {
            result.completeExceptionally(new BodyTooLargeException(limit));
        }
    }

    /** Counts bytes as they stream and cancels the subscription past the limit. */
    static final class LimitedSubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> inner;
        private final long limit;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long count;
        private boolean exceeded;

        LimitedSubscriber(HttpResponse.BodySubscriber<T> inner, long limit) {
            this.inner = inner;
            this.limit = limit;
            inner.getBody().whenComplete((value, error) -> {
                if (exceeded) {
                    return;
                }
                if (error != null) {
                    result.completeExceptionally(error);
                } else {
                    result.complete(value);
                }
            });
        }

        @Override
        public CompletionStage<T> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            inner.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            if (exceeded) {
                return;
            }
            for (ByteBuffer buffer : item) {
                count += buffer.remaining();
            }
            if (count > limit) {
                exceeded = true;
                subscription.cancel();
                result.completeExceptionally(new BodyTooLargeException(limit));
                inner.onError(new BodyTooLargeException(limit));
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Net] Response body passed its size cap; exchange cancelled");
                return;
            }
            inner.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!exceeded) {
                inner.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!exceeded) {
                inner.onComplete();
            }
        }
    }

    /** Default cap for SBS messages, for callers without a registered type. */
    public static long defaultCap() {
        return JsonLimits.DEFAULT.maxBytes();
    }
}
