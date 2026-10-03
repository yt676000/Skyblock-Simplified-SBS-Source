/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits text into the space a row actually has.
 *
 * <p>Every settings row is "label on the left, value on the right", and both were drawn at fixed
 * positions: the label from the left edge, the value anchored to the right. Nothing checked that the
 * two did not meet, so a long label and a wide value drew straight through each other – which is what
 * happens on a narrow panel, at a large GUI scale, or simply when a label grows by two words. The
 * fixed positions are correct; what was missing is that the side which can be shortened has to be.
 *
 * <p>The label is the side that gives way. A value that is cut ("Amethy...") stops telling the player
 * what the setting is set to, which is the one thing the row exists to say.
 */
public final class RowText {

    private static final String ELLIPSIS = "...";

    private RowText() {
    }

    /**
     * {@code text} if it fits in {@code maxWidth}, otherwise as much of it as fits with a trailing
     * ellipsis. Empty when there is no room at all, so a row squeezed to nothing draws nothing
     * rather than a lone "...".
     */
    public static String fit(Font font, String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        int room = maxWidth - font.width(ELLIPSIS);
        if (room <= 0) {
            return "";
        }
        return font.plainSubstrByWidth(text, room, false) + ELLIPSIS;
    }

    /**
     * The component form. The original is handed back untouched when it fits, so a styled label keeps
     * its formatting in the ordinary case; a cut one keeps the root style, which is what carries the
     * selected SBS font and the colour.
     */
    public static Component fit(Font font, Component text, int maxWidth) {
        if (text == null || maxWidth <= 0) {
            return Component.empty();
        }
        String plain = text.getString();
        if (font.width(plain) <= maxWidth) {
            return text;
        }
        return Component.literal(fit(font, plain, maxWidth)).setStyle(text.getStyle());
    }

    /**
     * Greedy word wrap to a pixel width, for prose that must say all of itself rather than be cut.
     *
     * <p>The counterpart to {@link #fit}, and the choice between them is about what the text is. A
     * label is cut, because the row has one line and the value beside it matters more. A sentence is
     * wrapped, because half a sentence is worse than none - the half that survives a cut is usually
     * the reassuring half, which is exactly the wrong half of a warning to keep.
     *
     * @param colour legacy colour code re-applied to every line, since one does not survive a break
     */
    public static List<String> wrap(Font font, String text, int maxWidth, String colour) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank() || maxWidth <= 0) {
            return out;
        }
        String prefix = colour == null ? "" : colour;
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (font.width(prefix + candidate) > maxWidth && line.length() > 0) {
                out.add(prefix + line);
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            out.add(prefix + line);
        }
        return out;
    }
}
