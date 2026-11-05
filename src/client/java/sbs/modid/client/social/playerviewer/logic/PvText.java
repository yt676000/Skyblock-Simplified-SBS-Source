/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.logic;

import sbs.modid.client.core.net.RemoteText;

/**
 * The two ways a remote string may enter the Player Viewer's view model.
 *
 * <ul>
 *   <li>{@link #plain} - ids, keys, names: everything stripped by {@link RemoteText#sanitize}.</li>
 *   <li>{@link #formatted} - item names and lore: the same sanitizing, except that formatting codes
 *       from a fixed whitelist (colors {@code 0-9 a-f}, {@code l o n m r}) are rebuilt in lower
 *       case. Obfuscation ({@code k}) and every other code are dropped.</li>
 * </ul>
 *
 * <p>Either result is rendered only through {@code Component.literal}; it is never parsed as a
 * JSON text component, so no click or hover event can come from data.
 */
public final class PvText {

    private static final char SECTION = '§';
    private static final String WHITELIST = "0123456789abcdeflonmr";

    private PvText() {
    }

    public static String plain(String raw, int maxLen) {
        return RemoteText.sanitize(raw, maxLen);
    }

    public static String formatted(String raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), maxLen) + 16);
        StringBuilder segment = new StringBuilder();
        int n = raw.length();
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (c != SECTION) {
                segment.append(c);
                continue;
            }
            out.append(RemoteText.sanitize(segment.toString(), Integer.MAX_VALUE));
            segment.setLength(0);
            if (i + 1 < n) {
                char code = Character.toLowerCase(raw.charAt(i + 1));
                if (WHITELIST.indexOf(code) >= 0) {
                    out.append(SECTION).append(code);
                }
            }
            i++;
        }
        out.append(RemoteText.sanitize(segment.toString(), Integer.MAX_VALUE));
        if (out.length() <= maxLen) {
            return out.toString();
        }
        int end = maxLen;
        if (out.charAt(end - 1) == SECTION || Character.isHighSurrogate(out.charAt(end - 1))) {
            end--;
        }
        return out.substring(0, end);
    }
}
