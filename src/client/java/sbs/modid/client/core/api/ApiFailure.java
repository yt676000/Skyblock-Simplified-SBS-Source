/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.licence.privacy.ConsentScope;

import java.util.EnumSet;
import java.util.Set;

/**
 * Why a licence-backed ranking could not be had, as a <b>value</b> rather than a message string.
 *
 * <p>The distinction is not cosmetic. The failure used to collapse into one string, and a tokenless
 * client took the {@link SbsApi.NoLicenceException} path — an {@link java.io.IOException} by design —
 * straight into the connect-failure branch and was told the connection had failed. Telling someone with no
 * licence that their network is broken sends them to fix the wrong thing. Every case below asks the
 * player for a different action, so every case has to survive as far as the UI.
 *
 * <p>The consent case is the same problem a second time: {@link SbsApi.NoConsentException} is an
 * {@link java.io.IOException} too, so a feature the player turned off in Licence Token &gt; Privacy
 * &amp; data used to report a failed connection. {@link #of(Throwable)} is the one mapper from an
 * exception to a case, and {@link #message(Throwable)} the one place that turns it into text, so no
 * caller writes its own instanceof chain.
 *
 * <p>Shared by every feed that falls back to a local ranking (bazaar flips, forge flips). One copy:
 * two enums with five near-identical constants is how the same five cases end up worded five
 * different ways on four screens.
 */
public enum ApiFailure {

    /**
     * No token is set at all - the ordinary state of a free player, not an error. Shown as a muted
     * note ({@link #isError()} is false). Text from the language files.
     */
    NO_LICENCE(null, null),

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

    /** The backend answered with data that failed validation; nothing of it was used. */
    INVALID_DATA("SBS server returned invalid data.",
            "Local mode — weaker and less accurate. SBS server returned invalid data."),

    /**
     * The player has not granted the {@link ConsentScope} the request declared. Not an error: the
     * consent gate working as intended. Text comes from the language files and names the scope.
     */
    NO_CONSENT(null, null),

    /**
     * No licence, and the automatic free PlayerViewer access could not be obtained right now (the
     * server or Mojang refused, or a re-claimed token was rejected). Text from the language files.
     */
    FREE_ACCESS_UNAVAILABLE(null, null),

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
        return switch (this) {
            case NO_CONSENT -> consentSentence(null);
            case NO_LICENCE -> translate("sbs.api.failure.no_licence");
            case FREE_ACCESS_UNAVAILABLE -> translate("sbs.api.failure.free_access");
            default -> sentence;
        };
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
        return switch (this) {
            case NO_CONSENT -> translate("sbs.api.failure.no_consent.headline");
            case NO_LICENCE -> translate("sbs.api.failure.no_licence.headline");
            case FREE_ACCESS_UNAVAILABLE -> translate("sbs.api.failure.free_access");
            default -> headline;
        };
    }

    /**
     * True when the licence itself is the problem, rather than the connection to the server.
     *
     * <p>The two families call for different actions — buy or fix a token versus wait for the network
     * — so a surface with room for only one hint can pick the right one from this.
     */
    /**
     * True for a real failure the player should notice (drawn red); false for a state that is simply
     * how this player uses the mod - no licence, or a feature turned off in the privacy settings -
     * which is drawn as a muted note. {@code ui/render/FailureStyle} turns this into a colour.
     */
    public boolean isError() {
        return this != NO_LICENCE && this != NO_CONSENT;
    }

    public boolean licenceProblem() {
        return this == NO_LICENCE || this == EXPIRED || this == LOCKED;
    }

    /** Resolves a language key; replaceable so tests can read the lang files without a client. */
    @FunctionalInterface
    public interface Translator {
        String get(String key, Object... args);
    }

    private static volatile Translator translator =
            (key, args) -> net.minecraft.client.resources.language.I18n.get(key, args);

    private static final Set<ConsentScope> LOGGED = EnumSet.noneOf(ConsentScope.class);
    private static boolean loggedUndeclared;

    static void translator(Translator replacement) {
        translator = replacement;
    }

    private static String translate(String key, Object... args) {
        return translator.get(key, args);
    }

    /**
     * Maps a failed request to its case: a consent refusal to {@link #NO_CONSENT}, a missing token to
     * {@link #NO_LICENCE}, and everything else - any other {@code IOException} included - to
     * {@link #OFFLINE}. Walks the cause chain, since executors may wrap the original exception.
     */
    public static ApiFailure of(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SbsApi.NoConsentException) {
                return NO_CONSENT;
            }
            if (t instanceof SbsApi.NoLicenceException) {
                return NO_LICENCE;
            }
            if (t instanceof sbs.modid.client.core.net.FreeAccess.UnavailableException) {
                return FREE_ACCESS_UNAVAILABLE;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return OFFLINE;
    }

    /** The scope a consent refusal was for, or {@code null} when it is not one (or was undeclared). */
    public static ConsentScope refusedScope(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SbsApi.NoConsentException refused) {
                return refused.scope();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    /**
     * The sentence a screen shows for a failed request. Consent and licence refusals name the action
     * that fixes them; anything else says the server could not be reached.
     */
    public static String message(Throwable failure) {
        return switch (of(failure)) {
            case NO_CONSENT -> consentSentence(refusedScope(failure));
            case NO_LICENCE -> NO_LICENCE.sentence();
            case FREE_ACCESS_UNAVAILABLE -> FREE_ACCESS_UNAVAILABLE.sentence();
            default -> translate("sbs.api.failure.offline");
        };
    }

    /** "&lt;scope title&gt; is turned off in your privacy settings ...", or the generic form for null. */
    public static String consentSentence(ConsentScope scope) {
        return scope == null
                ? translate("sbs.api.failure.no_consent")
                : translate("sbs.api.failure.no_consent.scope", translate(scope.titleKey()));
    }

    /**
     * True when {@code text} is (or ends with) a consent sentence from {@link #consentSentence}. For
     * screens whose client hands over only a message string; they know their own scope, so this
     * only has to answer whether to offer the way to the setting.
     */
    public static boolean isConsentMessage(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (text.endsWith(consentSentence(null))) {
            return true;
        }
        for (ConsentScope scope : ConsentScope.values()) {
            if (text.endsWith(consentSentence(scope))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logs a failed request of a background loader that shows no text of its own (a bundled-data
     * fallback is already serving). Always debug; a consent refusal only once per scope per session.
     */
    public static void logBackground(String area, String what, Throwable failure) {
        if (of(failure) == NO_CONSENT) {
            log(area, what, failure);
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.debug("{} {} failed: {}", area, what,
                failure == null ? "unknown" : failure.toString());
    }

    /**
     * Logs a failed request under the caller's area tag. A consent refusal is expected behaviour, so
     * it goes to debug and only once per scope per session; anything else is logged at warn as before.
     */
    public static void log(String area, String what, Throwable failure) {
        if (of(failure) == NO_CONSENT) {
            ConsentScope scope = refusedScope(failure);
            boolean first;
            synchronized (LOGGED) {
                if (scope == null) {
                    first = !loggedUndeclared;
                    loggedUndeclared = true;
                } else {
                    first = LOGGED.add(scope);
                }
            }
            if (first) {
                SkyblockSimplifiedSBS.LOGGER.debug("{} {} not sent: scope {} is not granted.", area, what,
                        scope == null ? "<undeclared>" : scope.id());
            }
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.warn("{} {} failed: {}", area, what,
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }
}
