/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The "this is still being built" notice, in one place, for the features that work out what
 * something is <i>worth</i> - the minion calculator, the flip rankings, the appraisal and price
 * windows.
 *
 * <p><b>Why these features and not the mod as a whole.</b> Everything here answers a question with a
 * number the player then spends coins against, and every one of those numbers is a chain of
 * assumptions - a price snapshot, a tax rate, a fill rate, a recipe, an hourly projection - any link
 * of which can be stale or simply wrong. A wrong overlay position costs nothing; a wrong flip costs
 * the bankroll. The rest of the mod shows you what the game already told you, and needs no such
 * warning.
 *
 * <p><b>One wording, one place.</b> Written out per screen it drifts: the screens are edited at
 * different times by different people, and three versions of a warning read as three different
 * warnings of three different weights. The text lives here and every surface asks for it.
 *
 * <p>Two forms, because the surfaces are two shapes. Screens have a panel and get the
 * {@linkplain #draw full block}, measured from the real strings so the layout beneath it is placed
 * against what is actually drawn rather than a constant (see {@code ui/AGENTS.md}). The floating
 * in-game windows are 15px of header and get {@linkplain #tag the tag}, which is dropped rather than
 * truncated when the window is dragged too narrow for it.
 */
public final class DevNotice {

    /** The headline, on its own line above the body. Never abbreviated. */
    public static final String HEADLINE = "§e⚠ In development - these numbers can be wrong";

    /** The header marker for a window with no room for a paragraph. */
    public static final String TAG = "§8· in development";

    private DevNotice() {
    }

    /**
     * The body, naming the thing that is unfinished.
     *
     * <p>It says what to <i>do</i> about it rather than only that a risk exists. "May be inaccurate"
     * is a phrase players read past; "check it in game before you spend" is an instruction, and it is
     * the one that would actually have prevented the loss.
     *
     * @param subject how the notice names the feature, e.g. "This calculator" or "These flips"
     */
    public static String body(String subject) {
        String what = subject == null || subject.isBlank() ? "This feature" : subject;
        return what + " is still being built and has not been fully verified against the game. "
                + "Every figure is an estimate from prices and assumptions that can be stale or "
                + "wrong - check it yourself before you spend coins on it.";
    }

    /** The notice as drawable lines, wrapped to {@code width}. */
    public static List<String> lines(Font font, int width, String subject) {
        List<String> out = new java.util.ArrayList<>();
        if (font == null || width <= 0) {
            return out;
        }
        out.add(HEADLINE);
        out.addAll(RowText.wrap(font, body(subject), width, "§7"));
        return out;
    }

    /**
     * The height {@link #draw} will take at this width, for a caller placing content below it.
     *
     * <p>Ask before you lay out, not after: the body wraps to two lines on a narrow panel and one on
     * a wide one, and a constant here is a constant that is wrong at one of those widths.
     */
    public static int height(Font font, int width, String subject) {
        return font == null ? 0 : lines(font, width, subject).size() * (font.lineHeight + 1) + 2;
    }

    /**
     * Draws the notice at {@code (x, y)}.
     *
     * @return the height consumed, which matches {@link #height} for the same arguments
     */
    public static int draw(GuiGraphicsExtractor g, Font font, int x, int y, int width, String subject) {
        if (font == null || width <= 0) {
            return 0;
        }
        int lineY = y;
        for (String line : lines(font, width, subject)) {
            g.text(font, Component.literal(line), x, lineY, SBSTheme.TEXT_MUTED);
            lineY += font.lineHeight + 1;
        }
        return lineY - y + 2;
    }

    /**
     * {@code title} with {@link #TAG} appended, or the bare title when the tag would not fit.
     *
     * <p>Dropping it is deliberate. A window header is also a drag handle and a close button, and a
     * warning squeezed into "· in dev..." is not a warning - the full block is still on the screen
     * this window's numbers come from.
     */
    public static String tagged(Font font, String title, int maxWidth) {
        if (font == null) {
            return title;
        }
        String tagged = title + " " + TAG;
        return font.width(tagged) <= maxWidth ? tagged : title;
    }

    /**
     * The one-sentence form for a settings row's hover text, where the row is the only place the
     * player meets the feature before switching it on.
     */
    public static String settingsNote() {
        return "In development: this is still being built and can be wrong. Treat what it tells you "
                + "as an estimate and check it before you spend coins on it.";
    }
}
