/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net;

import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * A short-lived session credential, held in memory only.
 *
 * <p>A token is used for at most {@link #LIFETIME} after it was issued; after that {@link #get}
 * calls the refresh hook for a new one. The value is never written to disk and never appears in
 * {@link #toString} or a log line.
 */
public final class SessionToken {

    public static final Duration LIFETIME = Duration.ofMinutes(15);

    /** Obtains a new token, for example from a ticket endpoint. */
    @FunctionalInterface
    public interface Refresh {
        String issue() throws IOException, InterruptedException;
    }

    private final Refresh refresh;
    private final LongSupplier clock;
    private String value;
    private long issuedAt;

    public SessionToken(Refresh refresh) {
        this(refresh, System::currentTimeMillis);
    }

    public SessionToken(Refresh refresh, LongSupplier clock) {
        this.refresh = refresh;
        this.clock = clock;
    }

    /** A token younger than {@link #LIFETIME}, refreshing when needed. */
    public synchronized String get() throws IOException, InterruptedException {
        long now = clock.getAsLong();
        if (value == null || now - issuedAt >= LIFETIME.toMillis() || now < issuedAt) {
            String fresh = refresh.issue();
            if (fresh == null || fresh.isBlank()) {
                clear();
                throw new IOException("no session token issued");
            }
            value = fresh;
            issuedAt = now;
        }
        return value;
    }

    /** Whether a token is held and still within its lifetime. */
    public synchronized boolean isValid() {
        long now = clock.getAsLong();
        return value != null && now >= issuedAt && now - issuedAt < LIFETIME.toMillis();
    }

    /** Drops the token, for example after the server refused it. */
    public synchronized void clear() {
        value = null;
        issuedAt = 0;
    }

    @Override
    public String toString() {
        return "SessionToken[" + Redact.MASK + "]";
    }
}
