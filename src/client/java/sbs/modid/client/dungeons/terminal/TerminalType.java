/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.terminal;

import java.util.Locale;

/**
 * The phase-3 terminals of F7 / M7, recognised by their GUI title. Six of them exist in the gates
 * between Goldor's sections, and each is a different little puzzle:
 *
 * <ul>
 *   <li>{@link #ORDER} - "Click in order!": panes numbered 1..14 by stack size, clicked ones turn green.</li>
 *   <li>{@link #COLOR} - "Select all the &lt;colour&gt; items!": 27 items, click every one of that colour.</li>
 *   <li>{@link #STARTS_WITH} - "What starts with '&lt;letter&gt;'?": 27 items, click every matching name.</li>
 *   <li>{@link #PANES} - "Correct all the panes!": red and green panes, flip every red one.</li>
 *   <li>{@link #RUBIX} - "Change all to same color!": five colours in a cycle, left/right click steps
 *       forwards/backwards - the puzzle is picking the colour that costs the fewest clicks.</li>
 *   <li>{@link #MELODY} - "Click the button on time!": the timing one, a note travelling towards a
 *       marked column.</li>
 * </ul>
 *
 * <p>Matching is done on the title's <b>letters only</b> ({@code "Click in order!"} →
 * {@code "clickinorder"}), so punctuation, colour codes and spacing tweaks on Hypixel's side cannot
 * break the recognition. The order below matters: melody is the one whose title also contains
 * "click the", so it is asked first and the loose {@link #ITEM_NAME} fallback comes last.
 */
public enum TerminalType {

    /** Not a terminal (or one we do not recognise) - nothing is drawn and nothing is blocked. */
    NONE,
    /** "Click in order!" */
    ORDER,
    /** "Select all the &lt;colour&gt; items!" */
    COLOR,
    /** "What starts with '&lt;letter&gt;'?" */
    STARTS_WITH,
    /** "Correct all the panes!" */
    PANES,
    /** "Change all to same color!" */
    RUBIX,
    /** "Click the button on time!" */
    MELODY,
    /** Fallback for a "Click the &lt;item&gt;!" style title we have not seen spelled out. */
    ITEM_NAME;

    public static TerminalType classify(String title) {
        if (title == null || title.isBlank()) {
            return NONE;
        }
        String norm = title.toLowerCase(Locale.ROOT);
        String letters = norm.replaceAll("[^a-z]", "");
        if (letters.contains("buttonontime")) {
            return MELODY;
        }
        if (letters.contains("allthepanes")) {
            return PANES;
        }
        if (letters.contains("changealltosame")) {
            return RUBIX;
        }
        if (letters.contains("startswith")) {
            return STARTS_WITH;
        }
        if (letters.contains("selectall")) {
            return COLOR;
        }
        if (letters.contains("inorder")) {
            return ORDER;
        }
        if (norm.contains("click the") && !norm.contains("button")) {
            return ITEM_NAME;
        }
        return NONE;
    }

    /**
     * Whether a click outside the solution is a mistake worth refusing.
     *
     * <p>False for {@link #MELODY} (its whole point is <i>when</i> you click, and the solver only
     * points at the button) and for the {@link #ITEM_NAME} fallback, whose title we are guessing at -
     * a guard is only allowed to stand where being wrong is impossible, not merely unlikely.
     */
    public boolean guardsMisclicks() {
        return this == ORDER || this == COLOR || this == STARTS_WITH || this == PANES || this == RUBIX;
    }
}
