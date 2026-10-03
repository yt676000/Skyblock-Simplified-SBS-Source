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
import sbs.modid.client.core.api.ApiFailure;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The banner a floating flip window shows while it is displaying a <b>locally computed</b> ranking
 * instead of the licence-backed server one.
 *
 * <p><b>Why it is pinned rather than part of the list.</b> The full screens carry the same warning as
 * scrolling text, which is fine there because the panel also renames itself. A floating window is
 * small, its content scrolls, and a warning that scrolls away is a warning that most readers see once
 * and then argue with numbers they have stopped attributing to the weaker engine. This block is drawn
 * between the header and the rows, out of the scroll region, so it is on screen for as long as the
 * local ranking is.
 *
 * <p><b>Why it names the cause.</b> "No licence token" and "no connection" are the same picture to
 * the person looking at it and opposite instructions: one is fixed by buying or entering a token, the
 * other by waiting. {@link ApiFailure#headline()} owns the wording so all four surfaces say it the
 * same way; this class only measures and draws it.
 *
 * <p>Deliberately the same API shape as {@link DevNotice} — {@code lines} / {@code height} / {@code
 * draw} — because callers place content underneath both, and a layout that measures one and assumes
 * the other overlaps silently (see {@code ui/AGENTS.md}).
 */
public final class LocalRankingNotice {

    private LocalRankingNotice() {
    }

    /**
     * The banner as drawable lines, wrapped to {@code width}. Empty when there is nothing to say —
     * a caller can therefore always ask and lay out on what comes back.
     */
    public static List<String> lines(Font font, int width, ApiFailure failure) {
        List<String> out = new ArrayList<>();
        if (font == null || width <= 0) {
            return out;
        }
        // No recorded cause means nothing failed — the player picked Local on the switch. That is a
        // different sentence from every failure case: there is nothing to fix and nothing to wait
        // for, so it points at the switch instead of at a token or a connection.
        String headline = failure == null
                ? "Local mode — weaker and less accurate. Switch to Server for the full ranking."
                : failure.headline();
        out.addAll(RowText.wrap(font, "⚠ " + headline, width, "§e"));
        return out;
    }

    /**
     * The settings-page form: the one line that sits <b>above</b> a Server/Local row so the choice is
     * never made blind.
     *
     * <p>Deliberately the same sentence everywhere the choice appears. A player meets this switch on
     * four different pages, and four differently-worded warnings read as four different warnings of
     * four different weights rather than as one fact about one thing.
     */
    public static String settingsNote() {
        return "§e⚠ Local is the weaker one: live prices only, no price history";
    }

    /** The height {@link #draw} takes at this width. Ask before laying out, never after. */
    public static int height(Font font, int width, ApiFailure failure) {
        List<String> lines = lines(font, width, failure);
        return lines.isEmpty() || font == null ? 0 : lines.size() * (font.lineHeight + 1) + 2;
    }

    /**
     * Draws the banner at {@code (x, y)}.
     *
     * @return the height consumed, matching {@link #height} for the same arguments
     */
    public static int draw(GuiGraphicsExtractor g, Font font, int x, int y, int width,
                           ApiFailure failure) {
        List<String> lines = lines(font, width, failure);
        if (lines.isEmpty()) {
            return 0;
        }
        int lineY = y;
        for (String line : lines) {
            g.text(font, Component.literal(line), x, lineY, SBSTheme.WARN);
            lineY += font.lineHeight + 1;
        }
        return lineY - y + 2;
    }
}
