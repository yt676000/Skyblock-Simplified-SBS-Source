/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

/**
 * Why a licence-backed ranking could not be had, as a <b>value</b> rather than a message string.
 *
 * <p>The distinction is not cosmetic. The failure used to collapse into one string, and a tokenless
 * client took the {@link SbsApi.NoLicenceException} path — an {@link java.io.IOException} by design —
 * straight into the connect-failure branch and was told "Connection failed". Telling someone with no
 * licence that their network is broken sends them to fix the wrong thing. Every case below asks the
 * player for a different action, so every case has to survive as far as the UI.
 *
 * <p>Shared by every feed that falls back to a local ranking (bazaar flips, forge flips). One copy:
 * two enums with five near-identical constants is how the same five cases end up worded five
 * different ways on four screens.
 */
public enum ApiFailure {

    /** No token is set at all — the ordinary unlicensed case, not an error. */
    NO_LICENCE("You have no licence token set.",
            "Local mode — weaker and less accurate. A licence token unlocks the full server ranking."),

    /** The backend rejected the token (401): expired, revoked or mistyped. */
    EXPIRED("Your licence token was rejected — it may have expired.",
            "Local mode — weaker and less accurate. Your licence token was rejected, so the server "
                    + "ranking is unavailable."),

    /** The token is in use from another connection (423). */
    LOCKED("Your licence is in use on another connection.",
            "Local mode — weaker and less accurate. Your licence is in use on another connection."),

    /** The backend answered, but not with a ranking (5xx, malformed, empty). */
    BACKEND("The ranking server is not answering correctly right now.",
            "Local mode — weaker and less accurate. The server ranking is not answering right now."),

    /** Nothing answered — no route to the backend. */
    OFFLINE("The ranking server could not be reached.",
            "Offline mode — weaker and less accurate. No connection to the server ranking.");

    private final String sentence;
    private final String headline;

    ApiFailure(String sentence, String headline) {
        this.sentence = sentence;
        this.headline = headline;
    }

    /**
     * A complete sentence naming the case, for the paragraph-sized disclaimer on a full screen.
     * States what happened; {@link #headline()} states what it costs the player.
     */
    public String sentence() {
        return sentence;
    }

    /**
     * The short form for a pinned banner in a floating window, where there is room for one or two
     * wrapped lines and no more.
     *
     * <p>Every one of them leads with the same two facts — that this is the local ranking and that it
     * is weaker — and only then splits on the cause, because the cause is the part that changes what
     * the player should do and the weakness is the part they must not miss whichever case they are in.
     */
    public String headline() {
        return headline;
    }

    /**
     * True when the licence itself is the problem, rather than the connection to the server.
     *
     * <p>The two families call for different actions — buy or fix a token versus wait for the network
     * — so a surface with room for only one hint can pick the right one from this.
     */
    public boolean licenceProblem() {
        return this == NO_LICENCE || this == EXPIRED || this == LOCKED;
    }
}
