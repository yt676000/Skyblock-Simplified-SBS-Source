/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.licence.privacy;

/**
 * The rule that decides whether one request may leave the client, as a pure function.
 *
 * <p>Split out of {@code SbsApi.send} so the rule can be tested exhaustively. The check itself is
 * four lines, but it is the four lines the whole privacy guarantee rests on, and left inline it
 * could only be exercised by launching the game and watching packets - which in practice means it
 * would be verified once, by hand, and never again. Here every combination of licence, scope and
 * stored answer is a unit test.
 *
 * <p>{@code SbsApi.send} keeps the transport and the exceptions; this keeps the decision.
 */
public final class ConsentGate {

    /** Why a request was allowed or refused. One value per distinct reason, so tests can be exact. */
    public enum Decision {
        /** Licensed, and the declared purpose is consented to. */
        ALLOW,
        /** No licence token: the client is not entitled to talk to the backend at all. */
        NO_LICENCE,
        /** Licensed, but the user has not agreed to this purpose. */
        NO_CONSENT,
        /**
         * The caller named no purpose. Refused rather than assumed: a request nobody could classify
         * is a request nobody consented to, and guessing a scope for it would defeat the point of
         * declaring one.
         */
        UNDECLARED;

        public boolean allowed() {
            return this == ALLOW;
        }
    }

    private ConsentGate() {
    }

    /**
     * Applies the rule.
     *
     * <p>The order matters and is not arbitrary. An undeclared scope is caught first because it is a
     * programming error rather than a user choice, and it should surface as one. The licence is
     * checked before consent so a tokenless client reports the thing it can actually fix. Consent is
     * last because it is the only one of the three the user controls.
     *
     * @param registry   the answers to consult; {@code null} is treated as "no answers", i.e. a refusal
     * @param hasLicence whether a licence token is configured
     * @param scope      the declared purpose, or {@code null} when the caller declared none
     */
    public static Decision evaluate(ConsentRegistry registry, boolean hasLicence, ConsentScope scope) {
        if (scope == null) {
            return Decision.UNDECLARED;
        }
        if (!hasLicence) {
            return Decision.NO_LICENCE;
        }
        if (registry == null || !registry.isGranted(scope)) {
            return Decision.NO_CONSENT;
        }
        return Decision.ALLOW;
    }
}
