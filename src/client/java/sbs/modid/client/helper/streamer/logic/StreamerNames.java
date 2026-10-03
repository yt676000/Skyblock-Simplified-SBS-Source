/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.streamer.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.helper.streamer.model.FakeLevel;
import sbs.modid.client.helper.streamer.model.FakeRank;
import sbs.modid.client.helper.streamer.model.NameMode;
import sbs.modid.client.helper.streamer.model.PlayerAlias;
import sbs.modid.client.helper.timers.ServerWorldTime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streamer Mode's redaction: what your own name, other players' names and the Hypixel instance id
 * are replaced with wherever they would otherwise be shown.
 *
 * <p><b>Why a roster and not a guess.</b> Nothing about a drawn string says which of its words is a
 * player name - "Notch" in chat looks exactly like "Notch" in an item description. So the names to
 * redact are not detected, they are <i>known</i>: your own from your profile, everybody else's from
 * the tab-list roster the server keeps up to date, and the aliases from the player's own list. A
 * pattern built from that list is exact, which is the only acceptable standard for a feature whose
 * failure mode is a name reaching a stream.
 *
 * <p><b>Whole words only.</b> A match must have no word character on either side of it, so a player
 * called "Cat" does not turn "Catacombs" into "acombs". Minecraft names are made of exactly the
 * characters that test calls word characters, which is what makes it the right boundary rather than
 * an approximation of one.
 *
 * <p><b>One pattern, rebuilt on a throttle.</b> Everything to redact goes into a single alternation
 * that is matched once per string, rather than one search per name - a busy lobby holds eighty
 * players, and eighty searches per drawn string per frame is not a cost anybody should pay. The
 * roster changes as people come and go, so the pattern is rebuilt every {@value #REBUILD_MS} ms;
 * that is the window in which a player who just joined is still shown, and it is short enough to be
 * under one frame's worth of names at any normal frame rate.
 *
 * <p><b>Blur, fake rank, fake level.</b> A name can also be drawn obfuscated ({@code §k}, same
 * length), and your own can be given a rank bracket and a SkyBlock level of your choosing in place
 * of the real ones. All of it is produced as {@code §} codes inside the replacement, which every
 * surface this reaches renders - the chat funnel, the GUI text hook and the nametag hook all hand
 * the text to the font renderer. The rewrite is idempotent, which matters because chat is rewritten
 * twice: once at the display funnel and again by the GUI text hook when the line is drawn.
 *
 * <p><b>Display only.</b> Nothing here touches what the client sends or what any other feature
 * reads - the party tracker still sees real names, because redaction happens on the way to the
 * screen and nowhere else.
 */
public final class StreamerNames {

    /** How long a built pattern is reused before the roster is read again. */
    private static final long REBUILD_MS = 250L;

    /**
     * The shape of a Hypixel instance id, in both spellings: "mini24CD" as the tab widget writes it
     * and "m24CD" as the sidebar writes it after the date.
     *
     * <p><b>Why a shape as well as the exact id.</b> The exact string is read from the tab widget and
     * is the reliable half - but it arrives a second or two after the world does, and it is missing
     * entirely on a lobby that publishes no widget. This covers that window. It is deliberately
     * narrow: a prefix, then digits and letters run together, which no English word does.
     *
     * <p><b>Unverified</b> in one respect - that the sidebar drops the "mini" and writes "m". Both
     * spellings are covered rather than picking one, and "Log Redactions" is what will say which of
     * them a real game actually produces.
     */
    private static final Pattern SERVER_ID_SHAPE =
            Pattern.compile("(?:mini|mega|m)\\d{1,4}[A-Za-z]{1,3}");

    /** What a Minecraft name may be, used to tell a roster entry from one of Hypixel's fake ones. */
    private static final Pattern PLAYER_NAME = Pattern.compile("\\w{3,16}");

    private static final StreamerNames INSTANCE = new StreamerNames();

    private StreamerNames() {
    }

    public static StreamerNames getInstance() {
        return INSTANCE;
    }

    /**
     * What one matched name becomes.
     *
     * @param text the replacement, or {@code null} to keep the name as it was written
     * @param blur draw the (kept) name obfuscated
     * @param own  this is your own name, so the fake rank and level apply to it
     */
    record Target(String text, boolean blur, boolean own) {

        static final Target BLANK = new Target("", false, false);

        /** The target for a name mode; {@code null} for {@link NameMode#OFF} on someone else. */
        static Target of(NameMode mode, String customText, boolean own) {
            return switch (mode) {
                case OFF -> own ? new Target(null, false, true) : null;
                case BLANK -> new Target("", false, own);
                case CUSTOM -> new Target(customText, false, own);
                case BLUR -> new Target(null, true, own);
            };
        }

        /** Whether the name itself is changed (as opposed to only what stands before it). */
        boolean changesName() {
            return blur || text != null;
        }

        /** How the "Show What Is Hidden" button describes it. */
        String describe() {
            if (blur) {
                return "(blurred)";
            }
            return text == null ? "(unchanged)" : text;
        }
    }

    /**
     * A compiled set of redactions: one pattern matching every name and id to take out, and what to
     * put in place of each.
     *
     * @param pattern    the alternation, case-insensitive and boundary-anchored, or {@code null}
     *                   when there is nothing to redact
     * @param byLowered  matched text (lower-cased) to what it becomes
     * @param rankPrefix what replaces the rank bracket before your own name, from
     *                   {@link FakeRank#render}; {@code null} to leave the real one
     * @param level      what replaces the "[312]" before your own name, from
     *                   {@link FakeLevel#render}; {@code null} to leave the real one
     */
    record Redactions(Pattern pattern, Map<String, Target> byLowered, String rankPrefix,
                      String level) {
    }

    private volatile Redactions current;
    private volatile long builtAt;

    /** Names and ids already reported to the log, so the debug switch says each thing once. */
    private final Set<String> logged = Collections.synchronizedSet(new HashSet<>());

    private static SBSConfig.StreamerSettings cfg() {
        return ConfigManager.getInstance().get().streamer;
    }

    /** True while the feature is on. The cheap test every caller opens with. */
    public boolean active() {
        return cfg().enabled;
    }

    /**
     * The component with every name and id redacted, or the input unchanged when the feature is off
     * or nothing in it needed redacting.
     *
     * <p>The returned component is the same instance when nothing matched, which is what lets the
     * callers that must not re-encode a value tell "unchanged" from "rewritten to the same thing".
     */
    public Component apply(Component text) {
        if (text == null || !active()) {
            return text;
        }
        Redactions redactions = redactions();
        if (redactions == null) {
            return text;
        }
        // The cheap pass first: flattening a component allocates a style entry per character, and
        // the overwhelming majority of drawn strings contain no name at all. Stripping is required
        // rather than optional - "§aNotch" has a word character ('a') hard against the name, so a
        // boundary match on the raw text would miss exactly the coloured names this exists for.
        String plain = StyledText.strip(text.getString());
        if (plain.isEmpty() || !redactions.pattern().matcher(plain).find()) {
            return text;
        }
        StyledText.Flat flat = StyledText.Flat.of(text);
        List<StyledText.Span> spans = logged(spans(flat.plain(), redactions));
        return spans.isEmpty() ? text : flat.rebuild(spans);
    }

    /**
     * The string form, for the callers that only ever hold a plain string. Splices into the original
     * so any {@code §} codes around a redacted name survive it.
     */
    public String apply(String text) {
        if (text == null || text.isEmpty() || !active()) {
            return text;
        }
        Redactions redactions = redactions();
        if (redactions == null) {
            return text;
        }
        // Strip once, keeping where each surviving character came from, so a match found in the
        // stripped text can be cut out of the original at the right place.
        StringBuilder stripped = new StringBuilder(text.length());
        int[] origIndex = new int[text.length() + 1];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                i++;
                continue;
            }
            origIndex[stripped.length()] = i;
            stripped.append(c);
        }
        origIndex[stripped.length()] = text.length();
        List<StyledText.Span> spans = logged(spans(stripped.toString(), redactions));
        return spans.isEmpty() ? text : splice(text, origIndex, spans);
    }

    /**
     * {@code spans} (over the stripped text) cut into the original string. A replacement carrying
     * its own {@code §} codes - a blur, a fake rank - is followed by a reset and the codes that were
     * in force after it, so its colour does not run on into the rest of the line.
     */
    static String splice(String text, int[] origIndex, List<StyledText.Span> spans) {
        // Back to front, so an earlier splice never invalidates a later index.
        StringBuilder result = new StringBuilder(text);
        for (int i = spans.size() - 1; i >= 0; i--) {
            StyledText.Span span = spans.get(i);
            int end = origIndex[span.end()];
            String replacement = span.replacement();
            if (replacement.indexOf('§') >= 0) {
                replacement = replacement + "§r" + activeCodes(text, end);
            }
            result.replace(origIndex[span.start()], end, replacement);
        }
        return result.toString();
    }

    /** The legacy codes in force at {@code upTo} in {@code text}, same rules as {@link StyledText}. */
    static String activeCodes(String text, int upTo) {
        StringBuilder active = new StringBuilder(4);
        for (int i = 0; i + 1 < upTo && i + 1 < text.length(); i++) {
            if (text.charAt(i) == '§') {
                char c = Character.toLowerCase(text.charAt(++i));
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == 'r') {
                    active.setLength(0);
                }
                if (c != 'r') {
                    active.append('§').append(c);
                }
            }
        }
        return active.toString();
    }

    /** The string form over an already-built set, with no config read - what the tests drive. */
    static String rewrite(String text, Redactions redactions) {
        StringBuilder stripped = new StringBuilder(text.length());
        int[] origIndex = new int[text.length() + 1];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                i++;
                continue;
            }
            origIndex[stripped.length()] = i;
            stripped.append(c);
        }
        origIndex[stripped.length()] = text.length();
        List<StyledText.Span> spans = spans(stripped.toString(), redactions);
        return spans.isEmpty() ? text : splice(text, origIndex, spans);
    }

    /** The sequence form, for the widgets that hand the renderer pre-decomposed styled text. */
    public FormattedCharSequence apply(FormattedCharSequence sequence) {
        if (sequence == null || !active()) {
            return sequence;
        }
        Redactions redactions = redactions();
        if (redactions == null) {
            return sequence;
        }
        Component rebuilt = StyledText.toComponent(sequence);
        Component replaced = apply(rebuilt);
        // apply() hands back the very same instance when nothing matched, which is the cue to leave
        // the caller's own sequence alone rather than swapping in a re-encoded copy of it.
        return replaced == rebuilt ? sequence : replaced.getVisualOrderText();
    }

    /**
     * Every match in {@code plain}, in order, paired with what goes in its place. Pure: reads no
     * config, so the tests can drive it with a hand-built set.
     *
     * <p>For your own name with a fake rank set, the span is widened backwards over the real rank
     * bracket and its space, and the replacement is the fake bracket followed by the name. With a
     * fake level set, the "[312]" in front of that (past an emblem, if there is one) gets a span of
     * its own, so the emblem between them keeps its own colour. Only what is really there is
     * replaced - a bare mention of your name ("… joined") gets no rank or level added, because
     * nothing tells a mention from a chat line's sender.
     */
    static List<StyledText.Span> spans(String plain, Redactions redactions) {
        List<StyledText.Span> spans = new ArrayList<>(2);
        Matcher matcher = redactions.pattern().matcher(plain);
        while (matcher.find()) {
            String matched = matcher.group();
            Target target = redactions.byLowered().get(matched.toLowerCase(Locale.ROOT));
            if (target == null) {
                // The generic instance-id shape matched something the map does not name. That is
                // what it is for, and blanking is the whole point of the setting.
                target = Target.BLANK;
            }
            int start = matcher.start();
            String name = target.blur() ? "§k" + matched
                    : target.text() == null ? matched : target.text();
            boolean changed = target.changesName();
            // Where the real rank bracket starts, or the name when there is none - the level sits
            // in front of that either way.
            int bracket = start;
            if (target.own() && (redactions.rankPrefix() != null || redactions.level() != null)) {
                int from = Math.max(0, start - FakeRank.PREFIX_LOOKBACK);
                Matcher prefix = FakeRank.REAL_PREFIX.matcher(plain.substring(from, start));
                if (prefix.find()) {
                    bracket = from + prefix.start();
                }
            }
            if (redactions.rankPrefix() != null && bracket < start) {
                start = bracket;
                name = redactions.rankPrefix() + name;
                changed = true;
            }
            if (target.own() && redactions.level() != null) {
                int from = Math.max(0, bracket - FakeLevel.LOOKBACK);
                Matcher level = FakeLevel.REAL_LEVEL.matcher(plain.substring(from, bracket));
                if (level.find()) {
                    int levelStart = from + level.start();
                    int levelEnd = from + level.end(1);
                    // Behind the previous span, or it would overlap - a second own-name match
                    // cannot sit in front of the first one's level, but a malformed line could.
                    if (spans.isEmpty() || spans.get(spans.size() - 1).end() <= levelStart) {
                        spans.add(new StyledText.Span(levelStart, levelEnd, redactions.level()));
                    }
                }
            }
            if (changed) {
                spans.add(new StyledText.Span(start, matcher.end(), name));
            }
        }
        return spans;
    }

    /** Passes {@code spans} through, reporting each new one to the log when that is switched on. */
    private List<StyledText.Span> logged(List<StyledText.Span> spans) {
        if (!spans.isEmpty() && cfg().debugLog) {
            for (StyledText.Span span : spans) {
                if (logged.add(span.replacement())) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Streamer] redacting {}..{} as \"{}\"",
                            span.start(), span.end(), span.replacement());
                }
            }
        }
        return spans;
    }

    /** The current redactions, rebuilt when the throttle is up; {@code null} when there are none. */
    private Redactions redactions() {
        long now = System.currentTimeMillis();
        Redactions cached = current;
        if (cached != null && now - builtAt < REBUILD_MS) {
            return cached.pattern() == null ? null : cached;
        }
        Redactions built = build();
        current = built;
        builtAt = now;
        return built.pattern() == null ? null : built;
    }

    /**
     * Reads the config, your profile and the roster into one compiled set.
     *
     * <p>Order matters where two sources claim the same name: an alias the player wrote wins over
     * both blanket modes, because it is the more specific instruction and the only one they typed
     * out by hand.
     */
    private Redactions build() {
        SBSConfig.StreamerSettings cfg = cfg();
        Map<String, Target> byLowered = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getInstance();
        String own = mc.player == null ? null : mc.player.getGameProfile().name();
        FakeRank rank = FakeRank.parse(cfg.ownRank);
        boolean fakeLevel = cfg.fakeLevel > 0;

        Target others = Target.of(NameMode.parse(cfg.othersMode), text(cfg.othersName), false);
        if (others != null) {
            for (String name : roster(mc)) {
                if (own == null || !name.equalsIgnoreCase(own)) {
                    byLowered.put(name.toLowerCase(Locale.ROOT), others);
                }
            }
        }

        if (own != null && !own.isBlank()) {
            Target mine = Target.of(NameMode.parse(cfg.ownMode), text(cfg.ownName), true);
            // Your name with its mode off still goes in when a fake rank or level is set: those
            // are found by finding the name.
            if (mine.changesName() || rank.active() || fakeLevel) {
                byLowered.put(own.toLowerCase(Locale.ROOT), mine);
            }
        }

        // Last, so a hand-written alias overwrites whatever a blanket mode put there.
        for (PlayerAlias alias : cfg.aliases) {
            if (alias != null && alias.usable()) {
                String name = alias.name.trim();
                boolean mine = own != null && name.equalsIgnoreCase(own);
                byLowered.put(name.toLowerCase(Locale.ROOT), new Target(text(alias.alias), false, mine));
            }
        }

        if (cfg.hideServerId) {
            for (String id : serverIds()) {
                byLowered.put(id.toLowerCase(Locale.ROOT), Target.BLANK);
            }
        }
        String prefix = rank.active() ? rank.render(cfg.rankPlusColour, cfg.rankAquaMvpPlusPlus) : null;
        String level = fakeLevel ? FakeLevel.render(cfg.fakeLevel) : null;
        return new Redactions(compile(byLowered.keySet(), cfg.hideServerId), Map.copyOf(byLowered),
                prefix, level);
    }

    /**
     * One case-insensitive alternation over {@code words}, plus the generic instance-id shape when
     * the server id is being hidden - which is included even with no id read yet, since that is
     * precisely the case the generic shape is the backstop for.
     *
     * <p>Longest first: the regex engine takes the first alternative that matches at a position, so
     * without this "Bob" would win over "Bobby" and leave three characters of the longer name on
     * screen.
     *
     * <p>The boundary is a pair of lookarounds rather than {@code \b}, which is not the same thing
     * for a term that starts or ends with something other than a word character. {@code \b} means
     * "a word character on exactly one side"; the alias list takes free text, and a player who types
     * "[Bob]" would get a rule that silently never fires. "No word character next to it" is what was
     * meant in both cases.
     */
    static Pattern compile(Set<String> words, boolean includeServerShape) {
        List<String> sorted = new ArrayList<>(words);
        sorted.removeIf(word -> word == null || word.isBlank());
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        if (sorted.isEmpty() && !includeServerShape) {
            return null;
        }
        StringBuilder alternation = new StringBuilder();
        for (String word : sorted) {
            if (!alternation.isEmpty()) {
                alternation.append('|');
            }
            alternation.append(Pattern.quote(word));
        }
        if (includeServerShape) {
            if (!alternation.isEmpty()) {
                alternation.append('|');
            }
            alternation.append(SERVER_ID_SHAPE.pattern());
        }
        try {
            return Pattern.compile("(?<!\\w)(?:" + alternation + ")(?!\\w)", Pattern.CASE_INSENSITIVE);
        } catch (Exception broken) {
            // A name cannot make this fail - every one is quoted - but the alternation is built from
            // player input, and a redaction that silently stops working is the one outcome this
            // feature must never have.
            SkyblockSimplifiedSBS.LOGGER.error(
                    "[SBS][Streamer] could not build the redaction pattern, nothing is hidden: {}",
                    broken.toString());
            return null;
        }
    }

    /**
     * Every name the server currently lists that could be a player's.
     *
     * <p><b>Not filtered by "is this a real player".</b> Hypixel serves its side widgets as fake tab
     * entries, and the obvious tidy-up - skip anything carrying a tab display name - would be wrong
     * twice over. Hypixel gives ranked <i>players</i> a display name too, so that test would throw
     * away most of the roster and quietly leave the feature doing nothing. And it would buy nothing:
     * a widget row is drawn from its display name, never from the profile name read here, so a fake
     * entry landing in this list cannot change anything on screen.
     *
     * <p>The shape test is all that is needed, and it errs the safe way: something that looks like a
     * Minecraft name is treated as one. Over-redacting costs a word; under-redacting is the failure
     * this feature exists to prevent.
     */
    private static List<String> roster(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>(32);
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            String name = info.getProfile().name();
            if (name != null && PLAYER_NAME.matcher(name).matches()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * The instance ids to take out: the exact one the tab widget publishes, plus the short form the
     * sidebar is believed to write it as.
     */
    private static List<String> serverIds() {
        String full = ServerWorldTime.serverName();
        if (full == null || full.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(2);
        ids.add(full);
        // "mini24CD" on the tab, "m24CD" after the date on the sidebar - same instance, two spellings,
        // and only one of them can be read from anywhere.
        for (String prefix : new String[] {"mini", "mega"}) {
            if (full.regionMatches(true, 0, prefix, 0, prefix.length())
                    && full.length() > prefix.length()) {
                ids.add("m" + full.substring(prefix.length()));
            }
        }
        return ids;
    }

    /** A configured replacement, never {@code null}, trimmed of the whitespace a field collects. */
    private static String text(String configured) {
        return configured == null ? "" : configured.trim();
    }

    /** Forgets what has been logged, so the debug switch reports afresh after it is turned on. */
    public void resetLog() {
        logged.clear();
    }

    /**
     * Drops the compiled pattern so the next call rebuilds it. Called when a setting changes, which
     * would otherwise wait out the throttle before taking effect.
     */
    public void invalidate() {
        current = null;
        builtAt = 0L;
    }

    /** What the feature would currently redact, for the "what is this hiding?" button. */
    public Map<String, String> preview() {
        Redactions built = build();
        current = built;
        builtAt = System.currentTimeMillis();
        Map<String, String> out = new HashMap<>();
        built.byLowered().forEach((name, target) -> out.put(name, target.describe()));
        return out;
    }
}
