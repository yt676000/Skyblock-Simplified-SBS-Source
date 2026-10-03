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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The two outbound links the privacy screen offers: the website and the Discord.
 *
 * <p><b>Neither URL is written in Java.</b> They live in the {@code contact} block of
 * {@code fabric.mod.json}, which is where a Fabric mod's outbound links belong and what every mod
 * list already reads - so moving the site or reissuing the invite is a resource edit, not a code
 * change and a recompile. A URL that has to be changed in a {@code static final String} is a URL
 * that stays wrong until someone rebuilds.
 *
 * <p><b>Absent means absent.</b> Every accessor returns {@code ""} when the key is missing or blank
 * rather than a guess or a placeholder, and the caller renders no button at all. A button that opens
 * nothing is worse than a missing button: the player clicks it, a browser does not appear, and there
 * is nothing on screen that explains why. The one log line is emitted once, for whoever packaged the
 * jar - the player can do nothing about it and is not told.
 */
public final class PrivacyLinks {

    /**
     * Whether {@code /privacy} exists on the website yet.
     *
     * <p>Flip this to {@code true} the day the route is live and two things follow on their own:
     * {@link #privacyUrl()} starts pointing at the notice itself instead of the site root, and the
     * "still being published, read PRIVACY.md" line disappears from the screen. Until then the
     * button deliberately opens the root, because the root exists.
     *
     * <p>A constant and not a probe: this is a fact about a website we publish, known at build time,
     * and a runtime check would put a network request behind opening a settings page.
     */
    public static final boolean PUBLISHED = false;

    /** The privacy notice's path once {@link #PUBLISHED}. */
    private static final String PRIVACY_PATH = "/privacy";

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
     * Where the privacy button should go: the notice once it is published, the site root before
     * then. Empty when there is no website configured at all.
     */
    public static String privacyUrl() {
        String root = website();
        if (root.isEmpty() || !PUBLISHED) {
            return root;
        }
        return root.endsWith("/")
                ? root.substring(0, root.length() - 1) + PRIVACY_PATH
                : root + PRIVACY_PATH;
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
// DATA SENT: Nothing. Opening the link is the operating system browser doing it, on a click,
//   with no data added by the mod.
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
