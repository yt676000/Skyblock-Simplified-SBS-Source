/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.licence.privacy;

/**
 * The version of the published privacy policy this build was written against.
 *
 * <p>The value is the "last updated" date printed on
 * {@code https://skyblocksimplified.de/datenschutz.html}. It is compiled in and never fetched: a
 * version supplied by the backend is not text the client can show the user, so it cannot be what
 * a consent answer is bound to. Changing the website policy and changing this constant belong in
 * the same release.
 *
 * <p>Every {@link ConsentState} stores the version in force when it was answered. A
 * {@link ConsentScope.Kind#CONSENT} answer given against a different version does not count (see
 * {@link ConsentState#countsFor}), and {@link PrivacyNotice} tells the user once per new version.
 */
public final class PrivacyPolicy {

    /** The "last updated" date of the published policy, {@code yyyy-MM-dd}. */
    public static final String VERSION = "2026-09-28";

    private PrivacyPolicy() {
    }
}
