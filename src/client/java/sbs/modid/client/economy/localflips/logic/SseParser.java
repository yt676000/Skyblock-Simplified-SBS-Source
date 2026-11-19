/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.logic;

/**
 * A Server-Sent Events line parser: feed it one line at a time, it reports whole events.
 *
 * <p>The rules it implements are the subset of the SSE format the local flip program uses:
 * <ul>
 *   <li>{@code event: <type>} sets the type of the event being built;</li>
 *   <li>one or more {@code data: <text>} lines form the payload, joined with {@code \n};</li>
 *   <li>a blank line ends the event and dispatches it - an event with no data line is dropped, and
 *       one with no {@code event:} line has type {@code message};</li>
 *   <li>a line starting with {@code :} is a comment (the program's keep-alive ping);</li>
 *   <li>any other field ({@code id}, {@code retry}, unknown names) is ignored.</li>
 * </ul>
 * One optional space after the colon is removed, and a trailing {@code \r} is tolerated so CRLF
 * streams parse the same. Not thread-safe: one parser per connection, used by its reading thread.
 */
public final class SseParser {

    /** Receives what the parser recognised. Called synchronously from {@link #accept}. */
    public interface Listener {
        void onEvent(String type, String data);

        void onComment(String text);
    }

    private static final String DEFAULT_TYPE = "message";

    private final Listener listener;
    private String type = DEFAULT_TYPE;
    private final StringBuilder data = new StringBuilder();
    private boolean hasData;

    public SseParser(Listener listener) {
        this.listener = listener;
    }

    /** Feeds one line, without its line terminator. */
    public void accept(String rawLine) {
        String line = rawLine;
        if (line == null) {
            return;
        }
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            line = line.substring(0, line.length() - 1);
        }
        if (line.isEmpty()) {
            dispatch();
            return;
        }
        if (line.charAt(0) == ':') {
            listener.onComment(stripSpace(line.substring(1)));
            return;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : stripSpace(line.substring(colon + 1));
        switch (field) {
            case "event" -> type = value.isEmpty() ? DEFAULT_TYPE : value;
            case "data" -> {
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
            }
            default -> {
                // id, retry and unknown fields carry nothing this client uses.
            }
        }
    }

    /** Drops a half-built event, e.g. when the connection it came from is gone. */
    public void reset() {
        type = DEFAULT_TYPE;
        data.setLength(0);
        hasData = false;
    }

    private void dispatch() {
        if (hasData) {
            String eventType = type;
            String payload = data.toString();
            reset();
            listener.onEvent(eventType, payload);
        } else {
            reset();
        }
    }

    private static String stripSpace(String value) {
        return !value.isEmpty() && value.charAt(0) == ' ' ? value.substring(1) : value;
    }
}
