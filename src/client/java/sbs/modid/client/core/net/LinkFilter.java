/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects links in text a player is about to send through SBS chat (SBS chat, party finder chat
 * and notes, carry board notes and chat). Text that contains one is not sent.
 *
 * <p>This is not a game-chat pattern and does not belong in {@code ChatPatternRegistry}: it never
 * reads Hypixel chat, it checks only the player's own outgoing SBS text before any request is made.
 * The server applies the same rule to everything it receives; this copy gives the player the answer
 * immediately and keeps the text off the network.
 *
 * <p>Normalisation before matching: NFKC; zero-width, bidirectional and other format characters
 * removed; lower case; look-alike dots and written-out dots ({@code [.]}, {@code (.)},
 * {@code (dot)}, {@code [dot]}, {@code " dot "}) become {@code .}; whitespace before a dot, and on
 * both sides of a substituted dot, is removed. Then it looks for a scheme, {@code www.}, invite
 * links, IP addresses and {@code name.tld} with a known top-level domain.
 */
public final class LinkFilter {

    /** Shown when outgoing text is refused. */
    public static final String REFUSED = "Links are not allowed in SBS chat";

    /** Dot look-alikes mapped to a full stop. */
    private static final String DOTS = "。．｡․·‧܁⸳・﹒˙·᛫•";

    private static final Pattern DOT_WORD = Pattern.compile(
            "\\s*(?:\\[\\s*\\.\\s*]|\\(\\s*\\.\\s*\\)|\\{\\s*\\.\\s*}|[\\[({]\\s*dot\\s*[\\])}]|\\s+dot\\s+)\\s*");
    private static final Pattern SPACE_BEFORE_DOT = Pattern.compile("\\s+\\.");
    private static final Pattern SCHEME = Pattern.compile("(?:https?|ftp|hxxps?)\\s*:\\s*/\\s*/");
    private static final Pattern WWW = Pattern.compile("(?<![a-z0-9])www\\d{0,3}\\.");
    private static final Pattern INVITE = Pattern.compile("discord(?:app)?\\.(?:gg|com/invite|io)\\b|\\bdsc\\.gg\\b");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");
    private static final Pattern IPV6 = Pattern.compile("(?<![0-9a-f:])[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}(?![0-9a-f:])");
    private static final Pattern DOMAIN = Pattern.compile(
            "(?<![a-z0-9-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+)([a-z]{2,24})(?![a-z0-9-])");

    private static final Pattern SPACED_DOMAIN =
            Pattern.compile("(?<![a-z0-9-])([a-z0-9-]{2,63})\\.\\s+([a-z]{2,24})(?![a-z0-9-])");
    private static final Set<String> SPACED_TLDS = Set.of("com", "net", "org", "gg", "io", "xyz", "ru", "de", "tk");

    private static final Set<String> TLDS = Set.of(
            "com", "net", "org", "info", "biz", "edu", "gov", "mil", "int", "io", "app", "dev", "xyz", "online",
            "site", "club", "shop", "store", "tech", "live", "stream", "link", "click", "top", "pro", "fun", "art",
            "blog", "cloud", "page", "space", "website", "world", "zone", "tk", "ml", "ga", "cf", "gq", "icu", "vip",
            "win", "bid", "loan", "work", "life", "today", "news", "media", "games", "game", "chat", "social",
            "team", "network", "email", "money", "cash", "gift", "codes", "moe", "lol", "wtf", "gg", "tv", "fm",
            "ly", "to", "me", "co", "sh", "so", "cc", "ws", "su", "eu", "asia", "mobi", "name", "museum", "travel",
            "jobs", "tel", "cat", "aero", "coop", "onion", "local", "localhost", "lan", "host", "server", "download",
            "exe", "zip", "mov", "ru", "de", "uk");

    private LinkFilter() {
    }

    /** The text as it is matched; exposed for tests. */
    static String normalize(String text) {
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            if (Character.getType(c) == Character.FORMAT || RemoteText.removed(c) && !Character.isWhitespace(c)) {
                continue;
            }
            out.append(DOTS.indexOf(c) >= 0 ? '.' : Character.isWhitespace(c) ? ' ' : c);
        }
        String lower = out.toString().toLowerCase(Locale.ROOT);
        String dots = DOT_WORD.matcher(lower).replaceAll(".");
        return SPACE_BEFORE_DOT.matcher(dots).replaceAll(".");
    }

    /** Whether {@code text} contains something that addresses a web resource. */
    public static boolean containsLink(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String normal = normalize(text);
        if (SCHEME.matcher(normal).find() || WWW.matcher(normal).find() || INVITE.matcher(normal).find()
                || IPV4.matcher(normal).find() || hasIpv6(normal)) {
            return true;
        }
        return hasDomain(normal) || spacedDomain(normal);
    }

    private static boolean hasDomain(String normal) {
        Matcher m = DOMAIN.matcher(normal);
        while (m.find()) {
            String labels = m.group(1);
            String tld = m.group(2);
            String last = labels.substring(0, labels.length() - 1);
            last = last.substring(last.lastIndexOf('.') + 1);
            // A known TLD always counts; any other two-letter TLD counts after a label of two or more
            // characters, which keeps abbreviations such as "e.g." and "a.m." out.
            if (TLDS.contains(tld) || tld.length() == 2 && last.length() >= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * "name . com" with spaces after the dot is matched only for the common TLDs, so an ordinary
     * sentence such as "nice. so we go" is not refused.
     */
    private static boolean spacedDomain(String normal) {
        Matcher m = SPACED_DOMAIN.matcher(normal);
        while (m.find()) {
            if (SPACED_TLDS.contains(m.group(2))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasIpv6(String normal) {
        Matcher m = IPV6.matcher(normal);
        while (m.find()) {
            String candidate = m.group();
            long colons = candidate.chars().filter(c -> c == ':').count();
            boolean hexLetter = candidate.chars().anyMatch(c -> c >= 'a' && c <= 'f');
            if (candidate.contains("::") && colons >= 2 || colons >= 5 || colons >= 3 && hexLetter) {
                return true;
            }
        }
        return false;
    }

    /** Thrown instead of sending text that contains a link. */
    public static final class LinkRefusedException extends java.io.IOException {
        public LinkRefusedException() {
            super(REFUSED);
        }
    }

    /** Refuses a message body whose named string fields contain a link. */
    public static void check(com.google.gson.JsonObject body, String... fields) throws LinkRefusedException {
        for (String field : fields) {
            com.google.gson.JsonElement value = body == null ? null : body.get(field);
            if (value != null && value.isJsonPrimitive() && containsLink(value.getAsString())) {
                throw new LinkRefusedException();
            }
        }
    }

    /** The send step guarded by {@link #sendIfClean}. */
    @FunctionalInterface
    public interface Send {
        void run() throws Exception;
    }

    /**
     * Runs {@code send} only when none of {@code texts} contains a link.
     *
     * @return whether it was sent
     */
    public static boolean sendIfClean(Send send, String... texts) throws Exception {
        for (String text : texts) {
            if (containsLink(text)) {
                return false;
            }
        }
        send.run();
        return true;
    }
}
