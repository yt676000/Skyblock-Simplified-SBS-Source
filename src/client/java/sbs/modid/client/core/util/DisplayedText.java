/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.util;

import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import sbs.modid.client.helper.streamer.logic.StreamerNames;
import sbs.modid.client.helper.visual.logic.TextReplacer;

/**
 * Everything that edits text on its way to the screen, in one order.
 *
 * <p>Two features rewrite displayed text - the Text Editor's replacement rules and Streamer Mode's
 * name and server-id redaction - and both want the same hooks. Rather than each adding its own set,
 * the hooks call here and this decides what runs and in what order. A third one is a line in this
 * file, not another mixin.
 *
 * <p><b>Order: Text Editor first, then Streamer Mode.</b> The Text Editor is the player's own rules
 * about wording; redaction is a privacy guarantee and so has to be the last thing that happens. A
 * replacement rule can then never reintroduce a name after it was taken out, and a rule that happens
 * to <i>produce</i> someone's name still gets that name redacted.
 *
 * <h2>Suppression</h2>
 *
 * Both features rewrite this mod's own screens too, which is a problem for the screens that
 * configure them - so there are two ways to stand the pipeline down, and they are not the same:
 *
 * <ul>
 *   <li>{@link #runRaw} - <b>everything</b> off. For a screen that must show text exactly as stored:
 *       the Text Editor's rule list (a rule would rewrite the row you turn it off with) and Streamer
 *       Mode's alias editor (which cannot ask you to alias a name it is hiding from you).</li>
 *   <li>{@link #runTextRulesApplied} - the <b>Text Editor</b> off, redaction still on. For a caller
 *       that ran the rules itself before measuring its layout, and would otherwise get them applied
 *       a second time while drawing. Redaction must keep running there: the SkyBlock sidebar is
 *       where the server id is written, and it is drawn through exactly this path.</li>
 * </ul>
 *
 * <p>Plain statics rather than thread locals: GUI text is submitted on the render thread only, and
 * both wrappers enclose a single synchronous draw.
 */
public final class DisplayedText {

    /** Everything off. */
    private static boolean raw;

    /** The Text Editor already ran for this draw; redaction has not. */
    private static boolean textRulesApplied;

    private DisplayedText() {
    }

    /** Whether the whole pipeline is currently suppressed - see {@link #runRaw}. */
    public static boolean isRaw() {
        return raw;
    }

    /** Whether the Text Editor stage is currently suppressed - see {@link #runTextRulesApplied}. */
    public static boolean isTextRulesApplied() {
        return raw || textRulesApplied;
    }

    /** Runs {@code body} with every displayed-text edit disabled. */
    public static void runRaw(Runnable body) {
        boolean previous = raw;
        raw = true;
        try {
            body.run();
        } finally {
            raw = previous;
        }
    }

    /** Runs {@code body} with the Text Editor disabled and redaction still active. */
    public static void runTextRulesApplied(Runnable body) {
        boolean previous = textRulesApplied;
        textRulesApplied = true;
        try {
            body.run();
        } finally {
            textRulesApplied = previous;
        }
    }

    /** The component as it should be shown, or the input unchanged when nothing applies. */
    public static Component filter(Component text) {
        if (raw || text == null) {
            return text;
        }
        Component result = textRulesApplied ? text : TextReplacer.getInstance().apply(text);
        return StreamerNames.getInstance().apply(result);
    }

    /**
     * The string form, for the hooks that only ever hold a plain string. Weaker than the component
     * form - it cannot see colour that lives in a {@code Style} - so prefer {@link #filter(Component)}
     * wherever a component is available.
     */
    public static String filter(String text) {
        if (raw || text == null) {
            return text;
        }
        String result = textRulesApplied ? text : TextReplacer.getInstance().apply(text);
        return StreamerNames.getInstance().apply(result);
    }

    /** The sequence form, for the widgets that hand the renderer pre-decomposed styled text. */
    public static FormattedCharSequence filter(FormattedCharSequence text) {
        if (raw || text == null) {
            return text;
        }
        FormattedCharSequence result = textRulesApplied ? text : TextReplacer.getInstance().apply(text);
        return StreamerNames.getInstance().apply(result);
    }
}
