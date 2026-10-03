/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config.share;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The wire format: {@code SBSCFG1:<modVersion>:<schemaVersion>:<base64url(gzip(json))>}.
 *
 * <p>Follows the house convention for clipboard blobs ({@code SBSKPEARL:}, {@code SBSROUTE:}), with
 * the header in <b>cleartext ahead of the compressed body</b> so a payload from an unknown schema is
 * refused without inflating anything. Refusing before inflating is also what stops a version
 * mismatch being a free decompression-bomb trigger.
 *
 * <p><b>The prefix is required</b>, deviating from {@code PearlStore}, which also accepts bare JSON
 * because chat clients mangle blobs. Bare JSON has no header, so accepting it means accepting a
 * payload whose schema and mod version are unknown - and it turns an accidental paste of unrelated
 * clipboard content into a parse error instead of "that is not an SBS config".
 *
 * <h2>Caps</h2>
 *
 * Applied in this order, each before the work it protects:
 * <ul>
 *   <li>{@value #MAX_ENCODED_BYTES} bytes of clipboard text, checked before base64 decoding.</li>
 *   <li>{@value #MAX_DECODED_BYTES} of inflated JSON, counted <i>during</i> the inflate and aborted
 *       mid-stream. A ratio check alone does not stop a zip bomb; a bounded-output inflater does,
 *       and it has to fail before the bytes are held.</li>
 * </ul>
 */
public final class ShareCodec {

    /** The format's own version, independent of {@code SBSConfig.schemaVersion}. */
    public static final int SCHEMA_VERSION = 1;

    public static final String PREFIX = "SBSCFG1:";

    /** Clipboard text cap, comfortably above a full allowlisted config. */
    public static final int MAX_ENCODED_BYTES = 256 * 1024;

    /** Inflated JSON cap, enforced while inflating. */
    public static final int MAX_DECODED_BYTES = 2 * 1024 * 1024;

    private ShareCodec() {
    }

    /** A decoded payload's header and body, before any of the body has been trusted. */
    public record Payload(String modVersion, int schemaVersion, String json) {
    }

    /** Thrown for every refusal, carrying the reason the player is shown. */
    /**
     * Which check refused a payload.
     *
     * <p>Exists so a caller - and above all a test - can ask <i>which</i> limit tripped without
     * matching on the message. The messages are written for a player and are free to be reworded;
     * two of them legitimately read alike ("unpacks to far more" and "holds far more" are both true
     * of a payload that is too big, in different ways), so a substring assertion cannot tell them
     * apart and would quietly pass on the wrong one.
     */
    public enum Reason {
        /** Nothing on the clipboard. */
        EMPTY,
        /** The text is longer than a payload may be, checked before anything is decoded. */
        ENCODED_TOO_LARGE,
        /** It decodes or inflates to more than a payload may be. */
        DECODED_TOO_LARGE,
        /** It holds more values than a payload may. */
        TOO_MANY_ELEMENTS,
        /** It nests deeper than a payload may. */
        TOO_DEEP,
        /** Not one of ours: no prefix, or not base64 where base64 was expected. */
        NOT_OURS,
        /** Ours in shape but from a schema this build cannot read. */
        WRONG_SCHEMA,
        /** Truncated, malformed, or carrying a value that is not a value. */
        MALFORMED,
        /** Anything else - the default, so an unannotated throw is never mistaken for a limit. */
        OTHER
    }

    public static final class ShareException extends Exception {

        private final Reason reason;

        public ShareException(String message) {
            this(Reason.OTHER, message);
        }

        public ShareException(Reason reason, String message) {
            super(message);
            this.reason = reason == null ? Reason.OTHER : reason;
        }

        /** Which check refused the payload. */
        public Reason reason() {
            return reason;
        }
    }

    /** The clipboard string for {@code json}, produced by this build. */
    public static String encode(String json, String modVersion) throws ShareException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(json.getBytes(StandardCharsets.UTF_8));
            }
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
            return PREFIX + sanitizeHeader(modVersion) + ":" + SCHEMA_VERSION + ":" + body;
        } catch (IOException impossible) {
            throw new ShareException("Could not pack the settings: " + impossible.getMessage());
        }
    }

    /**
     * The payload inside a clipboard string.
     *
     * <p>Every failure is a distinct message, because "nothing happened" is the one outcome the
     * design does not allow: not an SBS config, wrong schema, too large and corrupt are four
     * different problems with four different things for the player to do about them.
     */
    public static Payload decode(String clipboard) throws ShareException {
        if (clipboard == null || clipboard.isBlank()) {
            throw new ShareException("Your clipboard is empty.");
        }
        String text = clipboard.trim();
        if (text.length() > MAX_ENCODED_BYTES) {
            throw new ShareException("That is too large to be an SBS config ("
                    + (text.length() / 1024) + " KB, limit " + (MAX_ENCODED_BYTES / 1024) + " KB).");
        }
        if (!text.startsWith(PREFIX)) {
            throw new ShareException("That is not an SBS config - it should start with " + PREFIX);
        }
        String[] parts = text.substring(PREFIX.length()).split(":", 3);
        if (parts.length != 3) {
            throw new ShareException("That SBS config is incomplete - it may have been cut short "
                    + "when it was copied.");
        }
        int schema;
        try {
            schema = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException notANumber) {
            throw new ShareException("That SBS config's version could not be read.");
        }
        // Refused, not best-effort applied: a schema this build does not know is a payload whose
        // keys may mean something else entirely, and guessing is how a setting silently changes.
        if (schema != SCHEMA_VERSION) {
            throw new ShareException("That config was made for a different version of the sharing "
                    + "format (" + schema + "; this build reads " + SCHEMA_VERSION + ").");
        }
        byte[] compressed;
        try {
            compressed = Base64.getUrlDecoder().decode(parts[2].trim());
        } catch (IllegalArgumentException notBase64) {
            throw new ShareException("That SBS config is damaged - it may have been altered by the "
                    + "app you copied it from.");
        }
        return new Payload(parts[0].trim(), schema, inflate(compressed));
    }

    /** Inflates with a running byte count that aborts mid-stream rather than after. */
    private static String inflate(byte[] compressed) throws ShareException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            int read;
            while ((read = gzip.read(buffer)) > 0) {
                if (out.size() + read > MAX_DECODED_BYTES) {
                    throw new ShareException("That config unpacks to far more than a config can be. "
                            + "It has been refused.");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException damaged) {
            throw new ShareException("That SBS config is damaged and could not be unpacked.");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Keeps a mod version from carrying a colon into a colon-separated header. */
    private static String sanitizeHeader(String version) {
        if (version == null || version.isBlank()) {
            return "unknown";
        }
        return version.trim().replace(':', '-');
    }
}
