/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The JDK's default trust manager, plus an optional SPKI pin check for selected hosts.
 *
 * <p>Chain validation always runs first, against the default trust store. When pinning is on and
 * the peer host has pins, at least one certificate in the validated chain must have a
 * SubjectPublicKeyInfo whose SHA-256 (base64) is in that host's pin set, which holds a primary and
 * at least one backup pin. A mismatch fails the handshake with a message that says how pinning is
 * switched off. Hostname verification is unaffected: the HTTP client sets the {@code HTTPS}
 * endpoint identification algorithm, which the default extended trust manager enforces.
 */
public final class PinningTrustManager extends X509ExtendedTrustManager {

    public static final String DISABLE_HINT = "Start the game with -D" + SecureHttp.PINNING_PROPERTY
            + "=false to turn certificate pinning off (for example behind a TLS-inspecting proxy).";

    private final X509ExtendedTrustManager delegate;
    private final Map<String, Set<String>> pins;
    private final boolean enabled;

    public PinningTrustManager(X509ExtendedTrustManager delegate, Map<String, Set<String>> pins, boolean enabled) {
        this.delegate = delegate;
        this.pins = pins;
        this.enabled = enabled;
    }

    /** The platform default trust manager. */
    public static X509ExtendedTrustManager platformDefault() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (var manager : factory.getTrustManagers()) {
                if (manager instanceof X509ExtendedTrustManager extended) {
                    return extended;
                }
            }
        } catch (Exception unavailable) {
            throw new IllegalStateException("no default trust manager", unavailable);
        }
        throw new IllegalStateException("no default X509 trust manager");
    }

    /** Base64 SHA-256 of a certificate's SubjectPublicKeyInfo. */
    public static String spkiSha256(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded());
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Applies the pin check for {@code host} to an already validated chain. */
    public void checkPins(X509Certificate[] chain, String host) throws CertificateException {
        if (!enabled || host == null) {
            return;
        }
        Set<String> expected = pins.get(host.toLowerCase(Locale.ROOT));
        if (expected == null || expected.isEmpty()) {
            return;
        }
        for (X509Certificate certificate : chain) {
            if (expected.contains(spkiSha256(certificate))) {
                return;
            }
        }
        throw new CertificateException("Certificate pin mismatch for " + host + ". " + DISABLE_HINT);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, engine);
        checkPins(chain, engine == null ? null : engine.getPeerHost());
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, socket);
        String host = socket instanceof javax.net.ssl.SSLSocket ssl ? ssl.getHandshakeSession() == null
                ? null : ssl.getHandshakeSession().getPeerHost() : null;
        checkPins(chain, host);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        ((X509TrustManager) delegate).checkServerTrusted(chain, authType);
        // No peer host is known on this path, so a pinned configuration cannot be satisfied.
        if (enabled && !pins.isEmpty()) {
            throw new CertificateException("Certificate pin check needs the peer host. " + DISABLE_HINT);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
