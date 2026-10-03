/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.io;

import sbs.modid.client.core.build.model.Schematic;

import java.util.Base64;

/**
 * Share codes: a saved build as one line of text to paste to a friend - {@code SBSBP:} followed by
 * the base64url of exactly the bytes a {@code .sbsbp} file holds.
 *
 * <p>Follows the house clipboard conventions ({@code SBSROUTE:}, {@code SBSCFG1:}): the prefix is
 * required, so an accidental paste of anything else is "not an SBS build" rather than a parse error;
 * the length is capped <i>before</i> decoding; and decoding goes through {@link SchematicCodec}, whose
 * own caps bound the inflate. One parser for files and codes means one set of limits to get right.
 *
 * <p>Nothing here touches the network. A code is text on the player's own clipboard.
 */
public final class SchematicShareCode {

    public static final String PREFIX = "SBSBP:";

    /**
     * Longest code accepted or produced: 1 MiB of text, about 768 KiB of compressed build - far past
     * a detailed house, and short enough that a paste cannot stall the game while it decodes.
     */
    public static final int MAX_CODE_CHARS = 1024 * 1024;

    private SchematicShareCode() {
    }

    /** Thrown with a sentence for the player. */
    public static final class ShareException extends Exception {
        public ShareException(String message) {
            super(message);
        }
    }

    /** The code for {@code schematic}; refuses one that would be longer than {@link #MAX_CODE_CHARS}. */
    public static String encode(Schematic schematic) throws ShareException {
        byte[] bytes = SchematicCodec.encode(schematic);
        String code = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (code.length() > MAX_CODE_CHARS) {
            throw new ShareException("that build is too large to share as a code (" + (code.length() / 1024)
                    + " KB of text, the limit is " + (MAX_CODE_CHARS / 1024) + " KB) - share the .sbsbp file instead");
        }
        return code;
    }

    /** Reads a pasted code. Whitespace and line breaks a chat or editor added are ignored. */
    public static Schematic decode(String text) throws ShareException {
        if (text == null || text.isBlank()) {
            throw new ShareException("the clipboard is empty");
        }
        if (text.length() > MAX_CODE_CHARS + 4096) {
            throw new ShareException("that text is far longer than any build code");
        }
        String compact = text.replaceAll("\\s+", "");
        if (!compact.startsWith(PREFIX)) {
            throw new ShareException("that is not an SBS build code (they start with " + PREFIX + ")");
        }
        String body = compact.substring(PREFIX.length());
        if (body.length() > MAX_CODE_CHARS) {
            throw new ShareException("that code is longer than a build code may be");
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(body);
        } catch (IllegalArgumentException damaged) {
            throw new ShareException("the code is damaged - copy it again, all of it");
        }
        try {
            return SchematicCodec.decode(bytes);
        } catch (SchematicCodec.FormatException refused) {
            throw new ShareException("the code could not be read: " + refused.getMessage());
        }
    }
}
