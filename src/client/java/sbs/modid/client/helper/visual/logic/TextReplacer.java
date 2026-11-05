/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.DisplayedText;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.helper.visual.model.TextReplacement;

import java.util.LinkedHashMap;
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

    // BOOKMARK(merge 2026-10-05): scoreboard-render-cache revision() placed beside main's config-revision caches.

    // Revision state: the rules as last seen. The editor mutates the TextReplacement objects in
    // place and nothing announces it, so a change is found by comparing against this copy.
    private int revision;
    private boolean seenOn;
    private String[] seenFrom = new String[0];
    private String[] seenTo = new String[0];
    private boolean[] seenEnabled = new boolean[0];

    /**
     * A number that moves exactly when what {@link #apply} does may have changed: the Text Editor
     * switched on or off, or a rule added, removed, re-ordered, toggled or re-worded. Compared field
     * by field against the rules as last seen, so an unchanged call allocates nothing.
     *
     * <p>Client thread only - the state is plain fields.
     */
    public int revision() {
        SBSConfig.VisualsSettings visuals = ConfigManager.getInstance().get().visuals;
        List<TextReplacement> rules = visuals.textReplacements;
        if (!sameAsSeen(visuals.textEditorEnabled, rules)) {
            int n = rules.size();
            seenOn = visuals.textEditorEnabled;
            seenFrom = new String[n];
            seenTo = new String[n];
            seenEnabled = new boolean[n];
            for (int i = 0; i < n; i++) {
                TextReplacement rule = rules.get(i);
                seenFrom[i] = rule.from;
                seenTo[i] = rule.to;
                seenEnabled[i] = rule.enabled;
            }
            revision++;
        }
        return revision;
    }

    private boolean sameAsSeen(boolean on, List<TextReplacement> rules) {
        if (on != seenOn || rules.size() != seenFrom.length) {
            return false;
        }
        for (int i = 0; i < seenFrom.length; i++) {
            TextReplacement rule = rules.get(i);
            if (rule.enabled != seenEnabled[i] || !java.util.Objects.equals(rule.from, seenFrom[i])
                    || !java.util.Objects.equals(rule.to, seenTo[i])) {
                return false;
            }
        }
        return true;
    }


    /** Distinct visible strings remembered per cache; a HUD, sidebar, tab list and chat fit easily. */
    private static final int CACHE_SIZE = 1024;

    /** The {@link ConfigManager#revision()} {@link #hasRules} and both caches were derived from. */
    private volatile int seenRevision = -1;

    private volatile boolean hasRules;

    /**
     * What the rules did to a component, by its visible string ({@code getString()}).
     *
     * <p>Whether any rule matches depends only on that string, so {@link #UNCHANGED} is valid for
     * every component that has it - the common case, and the one that used to be rescanned every
     * frame. A rewrite also depends on the styles, so a positive entry holds the exact source it was
     * computed from and is only reused for a component {@code equals} to it.
     *
     * <p>Cleared whenever the rules change ({@link #seenRevision}), which is what keys it on the rule
     * revision as well. Access-ordered and bounded, so what is drawn every frame stays and what
     * scrolled away falls out.
     *
     * <p><b>Threads:</b> almost every call is the render thread's draw hook, but item names and
     * tooltips ({@code ItemNameMixin}, {@code ScreenTooltipReplaceMixin}) can be resolved wherever
     * vanilla asks for them. An access-ordered map mutates on {@code get}, so both caches are only
     * touched under their own monitor - uncontended, and held for a map operation only, never for a
     * scan.
     */
    private final Map<String, Entry> results = lru();

    /** {@link #apply(String)}'s results; same rules as {@link #results}, the string is the whole key. */
    private final Map<String, String> strings = lru();

    /** A cache entry: {@code result} {@code null} means no rule matches this visible string at all. */
    private record Entry(Component source, Component result) {
    }

    private static final Entry UNCHANGED = new Entry(null, null);

    private static <V> Map<String, V> lru() {
        return new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > CACHE_SIZE;
            }
        };
    }

    /**
     * True when the feature is on and at least one enabled rule exists. A field read per call: the
     * answer is recomputed only when the config's revision moves.
     */
    public boolean hasRules() {
        int revision = ConfigManager.getInstance().revision();
        if (revision != seenRevision) {
            refresh(revision);
        }
        return hasRules;
    }

    private synchronized void refresh(int revision) {
        if (revision == seenRevision) {
            return;
        }
        hasRules = computeHasRules();
        synchronized (results) {
            results.clear();
        }
        synchronized (strings) {
            strings.clear();
        }
        // Written last: a reader that sees the new revision also sees what was derived from it. If
        // computeHasRules() loaded the config and bumped the revision again, the next call refreshes
        // once more, which is harmless.
        seenRevision = revision;
    }

    private static boolean computeHasRules() {
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
        if (text == null || !hasRules()) {
            return text;
        }
        boolean main = onClientThread();
        if (main) {
            Object hit = identityGet(text);
            if (hit != null) {
                return hit == UNCHANGED ? text : ((Component) hit).copy();
            }
        }
        Component out = applyByValue(text);
        if (main) {
            identityPut(text, out == text ? UNCHANGED : out);
        }
        return out;
    }

    /**
     * First tier, in front of {@link #results}: what the rules did to this very component or sequence
     * instance. Vanilla and the HUD hand the same instances to the renderer frame after frame, so a
     * hit skips {@code getString()}, the monitor and the {@code equals}. Client thread only, hence no
     * lock; cleared on every rule revision and wholesale at {@link #IDENTITY_CAP}.
     *
     * <p>Values: {@link #UNCHANGED}, or the rewritten {@link Component} / {@link FormattedCharSequence}.
     * A caller that mutates a component it already had drawn would be missed here - none does: a
     * changed line is a new component.
     */
    private final java.util.IdentityHashMap<Object, Object> byInstance = new java.util.IdentityHashMap<>();
    private int byInstanceRevision = -1;
    private static final int IDENTITY_CAP = 4096;

    private Object identityGet(Object key) {
        int current = seenRevision;
        if (current != byInstanceRevision) {
            byInstance.clear();
            byInstanceRevision = current;
            return null;
        }
        return byInstance.get(key);
    }

    /** The identity tier is the client thread's alone; item names may be resolved elsewhere. */
    private static boolean onClientThread() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.isSameThread();
    }

    private void identityPut(Object key, Object value) {
        if (byInstance.size() >= IDENTITY_CAP) {
            byInstance.clear();
        }
        byInstance.put(key, value);
    }

    private Component applyByValue(Component text) {
        String key = text.getString();
        Entry cached;
        synchronized (results) {
            cached = results.get(key);
        }
        if (cached != null) {
            if (cached.result() == null) {
                return text;
            }
            if (cached.source().equals(text)) {
                // Source and result are the same for text the rules produced themselves. Otherwise a
                // copy, so a caller that styles what it got back cannot reach into the cache.
                return cached.result() == cached.source() ? text : cached.result().copy();
            }
        }
        if (done.containsKey(text)) {
            return text;
        }
        Component result = text;
        for (TextReplacement rule : ConfigManager.getInstance().get().visuals.textReplacements) {
            if (rule.enabled && rule.from != null && !rule.from.isEmpty()) {
                result = applyRule(result, rule.from, rule.to == null ? "" : rule.to);
            }
        }
        synchronized (results) {
            if (result == text) {
                results.put(key, UNCHANGED);
            } else {
                results.put(key, new Entry(text, result));
                // Put last so it wins when a rule leaves the visible string as it was: the output
                // must keep being recognised as finished, or the next draw rewrites it again.
                results.put(result.getString(), new Entry(result, result));
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
     *
     * <p>Only consulted on a {@link #results} miss. It stays because that cache is bounded: a chat
     * line scrolled out of it and back must still be recognised as already rewritten. Synchronized
     * for the same threads {@link #results} documents.
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
        boolean main = onClientThread();
        if (main) {
            Object hit = identityGet(sequence);
            if (hit != null) {
                return hit == UNCHANGED ? sequence : (FormattedCharSequence) hit;
            }
        }
        FormattedCharSequence out = applyByValue(sequence);
        if (main) {
            identityPut(sequence, out == sequence ? UNCHANGED : out);
        }
        return out;
    }

    private FormattedCharSequence applyByValue(FormattedCharSequence sequence) {
        // The visible string is enough to know nothing matches, and costs one pass and no component.
        StringBuilder plain = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            plain.appendCodePoint(codePoint);
            return true;
        });
        Entry cached;
        synchronized (results) {
            cached = results.get(plain.toString());
        }
        if (cached == UNCHANGED) {
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
        // No usable rule means the loop below would change nothing, so this is the same answer early.
        if (text == null || text.isEmpty() || !hasRules()) {
            return text;
        }
        String cached;
        synchronized (strings) {
            cached = strings.get(text);
        }
        if (cached != null) {
            return cached;
        }
        String result = text;
        List<TextReplacement> rules = ConfigManager.getInstance().get().visuals.textReplacements;
        for (TextReplacement rule : rules) {
            if (rule.enabled && rule.from != null && !rule.from.isEmpty()) {
                result = replaceStripAware(result, rule.from, rule.to == null ? "" : rule.to);
            }
        }
        synchronized (strings) {
            strings.put(text, result);
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
