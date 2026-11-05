/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.text.Normalizer;

/**
 * Turns a string from a remote source into plain display text.
 *
 * <p>{@link #sanitize} removes formatting codes ({@code §} and the character after it, and a
 * trailing stray {@code §}), C0 and C1 control characters including line breaks, bidirectional
 * overrides and isolates, zero-width characters and line/paragraph separators; normalises to NFC;
 * and caps the length without splitting a surrogate pair. The result is only ever rendered through
 * {@link Component#literal}, so it is never interpreted as a JSON text component and never carries
 * a click or hover event.
 */
public final class RemoteText {

    /** A sensible cap for names and short labels. */
    public static final int SHORT = 64;

    /** A sensible cap for one line of descriptive text. */
    public static final int LINE = 256;

    private RemoteText() {
    }

    public static String sanitize(String raw, int maxLength) {
        if (raw == null || raw.isEmpty() || maxLength <= 0) {
            return "";
        }
        String stripped = strip(raw);
        String normalised = Normalizer.normalize(stripped, Normalizer.Form.NFC);
        // NFC can compose a character back into one of the removed ranges only in theory; one more
        // pass keeps the guarantee unconditional.
        String clean = normalised.equals(stripped) ? normalised : strip(normalised);
        if (clean.length() <= maxLength) {
            return clean;
        }
        int end = maxLength;
        if (Character.isHighSurrogate(clean.charAt(end - 1))) {
            end--;
        }
        return clean.substring(0, end);
    }

    /** {@link #sanitize} wrapped in a plain literal component with no style. */
    public static MutableComponent literal(String raw, int maxLength) {
        return Component.literal(sanitize(raw, maxLength));
    }

    /**
     * A copy of a validated JSON tree with every string value and member name passed through
     * {@link #sanitize}, so text concatenated into a display line further down can carry no
     * formatting or control characters of its own.
     */
    public static com.google.gson.JsonElement sanitizeTree(com.google.gson.JsonElement tree, int maxLength) {
        if (tree == null || tree.isJsonNull()) {
            return tree;
        }
        if (tree.isJsonPrimitive()) {
            return tree.getAsJsonPrimitive().isString()
                    ? new com.google.gson.JsonPrimitive(sanitize(tree.getAsString(), maxLength)) : tree;
        }
        if (tree.isJsonArray()) {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray(tree.getAsJsonArray().size());
            for (com.google.gson.JsonElement element : tree.getAsJsonArray()) {
                out.add(sanitizeTree(element, maxLength));
            }
            return out;
        }
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        for (var entry : tree.getAsJsonObject().entrySet()) {
            out.add(sanitize(entry.getKey(), maxLength), sanitizeTree(entry.getValue(), maxLength));
        }
        return out;
    }

    private static String strip(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        int length = raw.length();
        for (int i = 0; i < length; i++) {
            char c = raw.charAt(i);
            if (c == '§') {
                i++; // the code character after the section sign, if any
                continue;
            }
            if (removed(c)) {
                continue;
            }
            if (Character.isSurrogate(c)) {
                if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(raw.charAt(i + 1))) {
                    int codePoint = Character.toCodePoint(c, raw.charAt(i + 1));
                    i++;
                    if (!removedCodePoint(codePoint)) {
                        out.appendCodePoint(codePoint);
                    }
                }
                continue; // unpaired surrogates are dropped
            }
            out.append(c);
        }
        return out.toString();
    }

    static boolean removed(char c) {
        return c < 0x20 || (c >= 0x7F && c <= 0x9F)
                || c == '؜'
                || (c >= '​' && c <= '‏')
                || (c >= ' ' && c <= '‮')
                || (c >= '⁠' && c <= '⁩')
                || c == '﻿'
                || c == '￹' || c == '￺' || c == '￻';
    }

    private static boolean removedCodePoint(int codePoint) {
        // Tag characters can hide text; private-use planes have no meaning in remote text.
        return (codePoint >= 0xE0000 && codePoint <= 0xE007F) || codePoint >= 0xF0000;
    }
}
