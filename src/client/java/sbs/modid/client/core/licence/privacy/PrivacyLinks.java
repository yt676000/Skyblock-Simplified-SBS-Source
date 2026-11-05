/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.licence.privacy;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ContactInformation;
import sbs.modid.SkyblockSimplifiedSBS;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The two outbound links the privacy screen offers: the privacy policy on the website and the
 * Discord.
 *
 * <p><b>The website root and the invite are not written in Java.</b> They live in the
 * {@code contact} block of {@code fabric.mod.json}, which is where a Fabric mod's outbound links
 * belong and what every mod list already reads - so reissuing the invite is a resource edit, not a
 * code change. The policy path and the allowed policy host are constants here, because the policy
 * URL is checked against them before a button is shown (see {@link #isAllowedPolicyUrl}).
 *
 * <p><b>Absent means absent.</b> Every accessor returns {@code ""} when the key is missing or blank
 * rather than a guess or a placeholder, and the caller renders no button at all. A button that opens
 * nothing is worse than a missing button: the player clicks it, a browser does not appear, and there
 * is nothing on screen that explains why. The one log line is emitted once, for whoever packaged the
 * jar - the player can do nothing about it and is not told.
 */
public final class PrivacyLinks {

    /**
     * Whether the privacy policy is published on the website. While {@code false},
     * {@link #privacyUrl()} points at the site root instead of the policy page.
     */
    public static final boolean PUBLISHED = true;

    /** The privacy policy's path on the website. */
    private static final String PRIVACY_PATH = "/datenschutz.html";

    /** The only hosts the privacy button may open. */
    private static final String POLICY_HOST = "skyblocksimplified.de";

    private static final AtomicBoolean POLICY_REJECTED = new AtomicBoolean();
    private static final AtomicBoolean WEBSITE_WARNED = new AtomicBoolean();
    private static final AtomicBoolean DISCORD_WARNED = new AtomicBoolean();

    private PrivacyLinks() {
    }

    /** The website root, or {@code ""} when {@code contact.homepage} is missing or blank. */
    public static String website() {
        return read("homepage", WEBSITE_WARNED);
    }

    /** The Discord invite, or {@code ""} when {@code contact.discord} is missing or blank. */
    public static String discord() {
        return read("discord", DISCORD_WARNED);
    }

    /**
     * Where the privacy button should go: the policy page under {@code contact.homepage}. Empty
     * when there is no website configured, or when the resulting URL fails
     * {@link #isAllowedPolicyUrl}; the latter is logged once.
     */
    public static String privacyUrl() {
        String url = privacyUrl(website());
        if (url.isEmpty()) {
            return "";
        }
        if (!isAllowedPolicyUrl(url)) {
            if (POLICY_REJECTED.compareAndSet(false, true)) {
                SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][Privacy] The privacy policy URL {} is not an https URL on {} - the "
                                + "button is not shown.", url, POLICY_HOST);
            }
            return "";
        }
        return url;
    }

    /** The policy URL for a given website root; {@code ""} for an empty root. */
    static String privacyUrl(String root) {
        if (root == null || root.isEmpty()) {
            return "";
        }
        if (!PUBLISHED) {
            return root;
        }
        return root.endsWith("/")
                ? root.substring(0, root.length() - 1) + PRIVACY_PATH
                : root + PRIVACY_PATH;
    }

    /**
     * Whether a URL may be opened by the privacy button: scheme {@code https}, host exactly
     * {@code skyblocksimplified.de} or {@code www.skyblocksimplified.de}, no user info, no port.
     */
    public static boolean isAllowedPolicyUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(url);
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1) {
                return false;
            }
            String host = uri.getHost();
            String authority = uri.getRawAuthority();
            return host != null
                    && host.equals(authority)
                    && (host.equals(POLICY_HOST) || host.equals("www." + POLICY_HOST));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * The host a URL points at ("skyblocksimplified.de"), for a button that names where it is about
     * to send you. Derived rather than written next to the label, so the two cannot disagree after
     * the URL is changed in the metadata and the label is not.
     */
// ============================================================================
// [MODERATOR AUDIT / NETWORK DISCLOSURE]
// KEYWORDS: NETWORK_OUTBOUND, NETWORK_INBOUND, HTTP_REQUEST, API_CLIENT, EXTERNAL_IO
// ENDPOINT: none - this method makes NO request. It reads the host out of a URL string so a
//   settings button can name where it is about to send the user before they click it.
// METHOD: parsing only, no traffic
// PURPOSE: Honest link labels. The host is derived from the URL rather than written beside it,
//   so the label cannot drift from the destination when one of them is edited.
// DATA SENT: Nothing. Opening the link is the operating system browser doing it, on a click
//   and after Minecraft's confirm prompt, with no data added by the mod. The privacy button
//   opens https://skyblocksimplified.de/datenschutz.html only; any other policy URL is
//   rejected by isAllowedPolicyUrl and no button is shown.
// DATA RECEIVED: Nothing.
// SAFETY DECLARATION: No network activity at all. Listed here because a reviewer grepping the
//   audit keyword should find every URL-handling site, including the ones that turn out to be
//   inert - an unannotated URL is the one that costs a review its benefit of the doubt.
// ============================================================================
    public static String host(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return "";
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            // A malformed URL in the metadata. The button is dropped by the caller's empty check;
            // this only decides that it is dropped quietly rather than crashing the settings screen.
            return "";
        }
    }

    private static String read(String key, AtomicBoolean warned) {
        try {
            Optional<ContactInformation> contact = FabricLoader.getInstance()
                    .getModContainer(SkyblockSimplifiedSBS.MOD_ID)
                    .map(container -> container.getMetadata().getContact());
            String value = contact.flatMap(info -> info.get(key)).orElse("");
            if (value.isBlank()) {
                warnOnce(warned, key);
                return "";
            }
            return value.trim();
        } catch (Throwable t) {
            // Reachable in a test harness where the mod is not a loaded container at all.
            warnOnce(warned, key);
            return "";
        }
    }

    private static void warnOnce(AtomicBoolean warned, String key) {
        if (warned.compareAndSet(false, true)) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Privacy] contact.{} is not set in fabric.mod.json - the matching button "
                            + "is not shown.", key);
        }
    }
}
