/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.logic;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.DisplayedText;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.helper.visual.model.TextReplacement;

import java.util.List;
import java.util.Map;

/**
 * Applies the Text Editor rules to displayed text – the rule half of the item-name, tooltip and
 * chat hooks. Plain literal replacement (no regex), only while the master toggle is on and per-rule
 * {@code enabled} is set.
 *
 * <p>The colour-preserving machinery underneath is {@link StyledText}, shared with every other
 * feature that edits shown text; this class only decides what to match and what to put there.
 *
 * <p><b>Callers should use {@link #apply(Component)}, not {@link #apply(String)}.</b> A component's
 * colour usually lives in its {@link Style}, not as a {@code §} code in its text, so flattening it
 * with {@code getString()} and rebuilding a {@code Component.literal} throws every colour away and
 * the line renders white. The component form rewrites the text run by run and hands each surviving
 * run its original style back – which keeps hover and click events too, since those are style as
 * well. The string form remains for the few callers that only ever hold a plain string.
 */
public final class TextReplacer {

    private static final TextReplacer INSTANCE = new TextReplacer();

    private TextReplacer() {
    }

    public static TextReplacer getInstance() {
        return INSTANCE;
    }

    /** True when the feature is on and at least one enabled rule exists (cheap pre-check). */
    public boolean hasRules() {
        SBSConfig.VisualsSettings visuals = ConfigManager.getInstance().get().visuals;
        if (!visuals.textEditorEnabled || visuals.textReplacements.isEmpty()) {
            return false;
        }
        for (TextReplacement rule : visuals.textReplacements) {
            if (rule.enabled && rule.from != null && !rule.from.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The component with every enabled rule applied, or the input unchanged when the feature is off
     * or nothing matched. Every character that survives keeps the style it arrived with, so a rule
     * firing on one word of a line leaves the rest of the line looking exactly as it did.
     *
     * <p>The replacement text takes the style and the legacy {@code §} codes in force at the first
     * character it replaces – of the styles the match spanned, the one it starts in is the only
     * defensible choice, and it is what makes "Ender Dragon" → "ED" keep the dragon's colour.
     */
    public Component apply(Component text) {
        if (text == null || !hasRules() || done.containsKey(text)) {
            return text;
        }
        Component result = text;
        for (TextReplacement rule : ConfigManager.getInstance().get().visuals.textReplacements) {
            if (rule.enabled && rule.from != null && !rule.from.isEmpty()) {
                result = applyRule(result, rule.from, rule.to == null ? "" : rule.to);
            }
        }
        if (result != text) {
            done.put(result, Boolean.TRUE);
        }
        return result;
    }

    /**
     * Texts the rules have already produced, so nothing is rewritten twice.
     *
     * <p>Chat lines and tooltips are rewritten where they are built - they have to be, because both
     * are wrapped and measured before they are drawn - and then pass the central draw hook on their
     * way to the screen. Without this, a rule that <i>grows</i> its text ({@code Dragon} →
     * {@code Dragon Slayer}) would match its own output and run away.
     *
     * <p>Keyed by value rather than identity, which is the stronger guarantee: text equal to
     * something the rules already produced is text they have nothing left to do to. Weak keys, so an
     * entry lives no longer than the component it describes.
     */
    private final Map<Component, Boolean> done =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * The same for a {@link FormattedCharSequence} - the shape the tab list and several vanilla
     * widgets hand their text to the renderer in.
     *
     * <p>A sequence is already decomposed into styled code points, so it is walked back into a
     * component, rewritten, and re-encoded. Only worth doing because it is the difference between the
     * rules working in the tab list and not: {@link #apply(Component)} is the cheaper path and is
     * what the component overloads use.
     */
    public FormattedCharSequence apply(FormattedCharSequence sequence) {
        if (sequence == null || !hasRules()) {
            return sequence;
        }
        Component rebuilt = StyledText.toComponent(sequence);
        Component replaced = apply(rebuilt);
        // apply() hands back the very same instance when no rule matched, which is the cue to leave
        // the caller's own sequence alone rather than swapping in a re-encoded copy of it.
        return replaced == rebuilt ? sequence : replaced.getVisualOrderText();
    }

    /** One rule over one component: flatten to styled characters, match, splice, rebuild. */
    private static Component applyRule(Component text, String from, String to) {
        StyledText.Flat flat = StyledText.Flat.of(text);
        List<StyledText.Span> matches = flat.find(from, to);
        return matches.isEmpty() ? text : flat.rebuild(matches);
    }

    /**
     * The text with every enabled rule applied (returns the input unchanged when off). Matching is
     * tolerant of legacy formatting codes: a rule matches even when § color codes sit inside the
     * word ("Ender §6Dragon" still matches "Ender Dragon"); the codes inside the match are dropped.
     *
     * <p>Prefer {@link #apply(Component)} wherever a component is available – this form cannot see
     * styled colour at all, so a caller that rebuilds a component from the result loses it.
     */
    public String apply(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        SBSConfig.VisualsSettings visuals = ConfigManager.getInstance().get().visuals;
        if (!visuals.textEditorEnabled) {
            return text;
        }
        String result = text;
        List<TextReplacement> rules = visuals.textReplacements;
        for (TextReplacement rule : rules) {
            if (rule.enabled && rule.from != null && !rule.from.isEmpty()) {
                result = replaceStripAware(result, rule.from, rule.to == null ? "" : rule.to);
            }
        }
        return result;
    }

    /** Plain replace, falling back to a §-code-tolerant match when the plain one finds nothing. */
    private static String replaceStripAware(String text, String from, String to) {
        if (text.contains(from)) {
            return text.replace(from, to);
        }
        if (text.indexOf('§') < 0) {
            return text;
        }
        // Map stripped indices back to original indices, match on the stripped text, splice there.
        StringBuilder stripped = new StringBuilder(text.length());
        int[] origIndex = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                i++; // skip code char
                continue;
            }
            origIndex[stripped.length()] = i;
            stripped.append(c);
        }
        String plain = stripped.toString();
        // Collect all match positions first, then splice back-to-front so indices stay valid
        // (and a replacement containing the search term can never re-match itself).
        java.util.List<Integer> matches = new java.util.ArrayList<>();
        int match = plain.indexOf(from);
        while (match >= 0) {
            matches.add(match);
            match = plain.indexOf(from, match + from.length());
        }
        if (matches.isEmpty()) {
            return text;
        }
        StringBuilder result = new StringBuilder(text);
        for (int i = matches.size() - 1; i >= 0; i--) {
            int m = matches.get(i);
            int startOrig = origIndex[m];
            int endOrig = origIndex[m + from.length() - 1] + 1;
            result.replace(startOrig, endOrig, to);
        }
        return result.toString();
    }
}
