/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.util;

/**
 * The plain words of a piece of server text — a chat line, an item name, a lore row — with
 * Minecraft's legacy {@code §} codes taken out.
 *
 * <p><b>Why this is needed at all.</b> {@code Component#getString()} flattens a message but does
 * <i>not</i> remove {@code §} codes, because on Hypixel they are not styling in the component tree —
 * they are literal characters inside the text the server sent. So a message reads back as
 * {@code §aYou have §a6K§a hours…}, which is right for measuring and wrong for anything a human
 * reads: a clipboard, a log line, a pattern being matched.
 *
 * <p>In {@code core} because more than one theme needs it — the chat tabs and the clipboard in
 * {@code social}, the calendar reader in {@code helper}. Two implementations would eventually
 * disagree about an edge (does a {@code §} at the end of a line eat the newline? is {@code §x} a
 * code?), and the symptom would be two features reading the same text differently.
 *
 * <p>Only a {@code §} followed by an actual code character is dropped. A lone {@code §} in someone's
 * message is kept, along with whatever came after it: guessing that the next character is formatting
 * would silently eat a letter out of what somebody typed.
 */
public final class PlainText {

    private static final char SECTION_SIGN = (char) 0x00A7;

    /**
     * Every legacy code character: colours {@code 0-9a-f}, styles {@code k-o}, reset {@code r}, and
     * {@code x}, which opens the six-part hex form ({@code §x§f§f§0§0§0§0}) — each of whose parts is
     * itself a {@code §} pair, so removing pairs removes the whole run.
     */
    private static final String CODES = "0123456789abcdefklmnorx";

    private PlainText() {
    }

    /** {@code text} without its {@code §} codes. {@code null} in, empty out. */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int first = text.indexOf(SECTION_SIGN);
        if (first < 0) {
            return text;   // the common case: nothing to do, nothing allocated
        }
        StringBuilder out = new StringBuilder(text.length());
        out.append(text, 0, first);
        for (int i = first; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION_SIGN && i + 1 < text.length() && isCode(text.charAt(i + 1))) {
                i++;   // skip the code character with it
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isCode(char c) {
        return CODES.indexOf(Character.toLowerCase(c)) >= 0;
    }
}
