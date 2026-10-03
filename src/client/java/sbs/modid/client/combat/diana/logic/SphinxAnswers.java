/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Sphinx's riddle: which of the three answers is the right one, and - if the player has asked
 * for it - clicking anywhere while the chat is open to give it.
 *
 * <h2>Matching, and the trap that made this look broken</h2>
 *
 * <p>The question and its three lettered answers arrive as separate chat lines, and <b>neither
 * arrives alone on its line</b>. Hypixel prefixes NPC dialogue with the speaker, so the riddle
 * reaches the client as something closer to {@code [NPC] Sphinx: Which of these is NOT a pet?} than
 * as the bare question. The first version of this class looked the whole line up in the riddle table
 * as an exact key, which therefore never matched: the question was never recognised, the answer
 * lines were dropped by the guard behind it, and nothing was ever printed. From the player's side
 * that reads as "it shows every answer except the right one" - because what they were seeing was
 * Hypixel's own three lines with no mark on any of them.
 *
 * <p>So both the question and the answers are matched as <b>substrings of a normalised line</b>, and
 * the comparison between an offered answer and the expected one is normalised too. Anything that can
 * carry a prefix is assumed to carry one.
 *
 * <h2>Answering</h2>
 *
 * <p>Marking the answer is always available. <b>Giving</b> it - a click anywhere while the chat is
 * open sending the answer command - is a separate setting that ships off, and it is the one place in
 * this mod where a click is turned into a command the player did not aim.
 *
 * <p>It exists because the maintainer asked for it explicitly on 2026-08-26, having been shown the
 * argument against it, and that is recorded here rather than in a chat log because it is the sort of
 * decision a future reader will otherwise mistake for an oversight. The argument against is in
 * {@code SPEC_DIANA.md} section 8 and it has not changed: `AGENTS.md` says the mod never plays the
 * game for the player, and of the four conditions that carve out an exception this fails "restores,
 * never progresses" - answering a riddle advances you through content. What can be said for it is
 * narrower than it looks: the correct answer is already on screen from the marking above, so the
 * click saves aiming, not knowing, and the mod already lets a player bind a key to an arbitrary
 * command themselves.
 *
 * <p>Because it is an exception rather than a rule, it is fenced in as tightly as the shape allows:
 * off by default, armed only between a solved riddle and the first click after it, expiring on its
 * own, one command per riddle, and never retried. If the maintainer later decides against it, the
 * whole behaviour is the two methods at the bottom of this class and one settings row.
 *
 * <h2>The table is small, closed and will drift</h2>
 *
 * <p>Fifteen riddles is the whole set today. A reworded question matches nothing and this says
 * nothing, which is the right failure - an answer confidently given to a question we did not
 * recognise would be a guess dressed up as knowledge. Unrecognised riddles are logged so the table
 * can be extended from what the game actually asked.
 */
public final class SphinxAnswers {

    private static final SphinxAnswers INSTANCE = new SphinxAnswers();

    /**
     * A lettered answer line, wherever it sits in the line.
     *
     * <p>Not anchored at the start: the same speaker prefix that broke the question lookup would
     * break this too. The letter has to be followed by {@code )} and something, which is specific
     * enough that ordinary chat does not trip it.
     */
    private static final Pattern ANSWER = Pattern.compile("(?:^|\\s)([ABC])\\)\\s+(.+?)\\s*$");

    /** A session is abandoned if the three answers do not all arrive within this long. */
    private static final long SESSION_MS = 15_000L;

    /**
     * How long a solved riddle stays clickable.
     *
     * <p>Short on purpose. This is the window in which a click means something other than what the
     * player asked it to mean, so it lasts about as long as it takes to read three lines and no
     * longer.
     */
    private static final long ARMED_MS = 30_000L;

    /** The command that answers, with the chosen index. */
    private static final String ANSWER_COMMAND = "sphinxanswer ";

    /**
     * The riddle set, question to answer.
     *
     * <p>Game facts, and carried as such. Keyed on the question with its punctuation stripped and
     * folded to lower case, so a stray capital or a missing question mark does not lose a match.
     */
    private static final Map<String, String> RIDDLES = new LinkedHashMap<>();

    static {
        riddle("Which of these is NOT a pet?", "Slime");
        riddle("What type of mob is exclusive to the Fishing Festival?", "Shark");
        riddle("Where is Trevor the Trapper found?", "Mushroom Desert");
        riddle("Who helps you apply Rod Parts?", "Roddy");
        riddle("Which type of Gemstone has the lowest Breaking Power?", "Ruby");
        riddle("Which item rarity comes after Mythic?", "Divine");
        riddle("How do you obtain the Dark Purple Dye?", "Dark Auction");
        riddle("Who runs the Chocolate Factory?", "Hoppity");
        riddle("How many floors are there in The Catacombs?", "7");
        riddle("What is the first type of slayer Maddox offers?", "Zombie");
        riddle("What item do you use to kill Pests?", "Vacuum");
        riddle("Who owns the Gold Essence Shop?", "Marigold");
        riddle("Which of these is NOT a type of Gemstone?", "Prismite");
        riddle("What does Junker Joel collect?", "Junk");
        riddle("Where is the Titanoboa found?", "Backwater Bayou");
    }

    /** The answer to the riddle currently being asked, or {@code null}. */
    private String expected;

    /** Letter to the text offered, for the riddle currently being asked. */
    private final Map<String, String> offered = new LinkedHashMap<>();

    private long sessionAt;

    /** The index of the solved answer while a click may still give it, or {@code -1}. */
    private volatile int armedIndex = -1;

    /** When {@link #armedIndex} was set, so the arming expires on its own. */
    private volatile long armedAt;

    private SphinxAnswers() {
    }

    public static SphinxAnswers getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    private static void riddle(String question, String answer) {
        RIDDLES.put(normalise(question), answer);
    }

    /** One chat line. Reads only - nothing is cancelled, nothing is rewritten. */
    public void onChat(String rawText) {
        SBSConfig.DianaSettings cfg = cfg();
        if (!cfg.enabled || !cfg.sphinxAnswers || rawText == null) {
            return;
        }
        String text = PlainText.strip(rawText).trim();
        if (text.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (expected != null && now - sessionAt > SESSION_MS) {
            clear();
        }

        String answer = riddleIn(text);
        if (answer != null) {
            expected = answer;
            offered.clear();
            sessionAt = now;
            disarm();
            return;
        }

        String[] lettered = answerIn(text);
        if (lettered == null) {
            return;
        }
        if (expected == null) {
            // Three lettered answers with no riddle recognised in front of them. Almost certainly a
            // question this build's table does not carry - or carries in different words - and it is
            // the only evidence of that there will ever be.
            DianaDebug.getInstance().note("sphinx: an answer line arrived with no riddle recognised"
                    + " - the riddle table is short or the question was reworded");
            return;
        }
        offered.put(lettered[0], lettered[1]);
        if (offered.size() < 3) {
            return;
        }
        announce();
        clear();
    }

    /**
     * The letter and text of a lettered answer line, or {@code null} when it is not one.
     *
     * <p>Public and static because it is a pure function of one line, which is what lets the shapes
     * Hypixel actually sends be tested without a client, a server or a live event - the same reason
     * {@code DianaEvent.evaluate} is shaped that way.
     *
     * @return {@code {letter, text}}, or {@code null}
     */
    public static String[] answerIn(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = ANSWER.matcher(PlainText.strip(line).trim());
        return matcher.find() ? new String[] {matcher.group(1), matcher.group(2).trim()} : null;
    }

    /**
     * The answer to whichever riddle this line contains, or {@code null}.
     *
     * <p>A scan rather than a map lookup, because the line carries a speaker prefix and the map is
     * keyed on the bare question. Fifteen entries scanned per chat line only while a Diana module is
     * on and only until the first match - which is cheap enough that the alternative, guessing where
     * the prefix ends, is not worth its own bug.
     *
     * <p>Public and static for the reason {@link #answerIn} is.
     */
    public static String riddleIn(String text) {
        String needle = normalise(text);
        if (needle.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> riddle : RIDDLES.entrySet()) {
            if (needle.contains(riddle.getKey())) {
                return riddle.getValue();
            }
        }
        return null;
    }

    /**
     * Prints the verdict, once, and arms the click if the player asked for that.
     *
     * <p>Says nothing at all when none of the three offered answers is the one the table expects.
     * That happens when the riddle has been reworded around an answer we do know, and it is exactly
     * the case where saying something would be worse than saying nothing - and where arming a click
     * would be worse still.
     */
    private void announce() {
        for (Map.Entry<String, String> entry : offered.entrySet()) {
            if (!matches(entry.getValue(), expected)) {
                continue;
            }
            String letter = entry.getKey();
            SBSChat.send(Component.literal(" §bSphinx: §a" + letter + ") "
                    + entry.getValue() + " §7is correct."));
            if (cfg().sphinxClickToAnswer) {
                armedIndex = indexOf(letter);
                armedAt = System.currentTimeMillis();
                SBSChat.send(Component.literal(
                        " §7Open chat and click anywhere to answer, or click the answer yourself."));
            }
            return;
        }
        DianaDebug.getInstance().note("sphinx: expected \"" + expected
                + "\" but it was not among the three offered - the riddle table needs a look");
    }

    /**
     * A left-click landed while the chat screen was open.
     *
     * <p><b>The one place this mod turns an unaimed click into a command.</b> See the class note for
     * why it exists and how narrowly it is fenced. Single-shot: the arming is dropped before the
     * command is sent, so a second click sends nothing and a failed send is never retried.
     *
     * @return whether the click was consumed, so the chat screen does not also act on it
     */
    public boolean onChatScreenClick() {
        SBSConfig.DianaSettings cfg = cfg();
        if (!cfg.enabled || !cfg.sphinxAnswers || !cfg.sphinxClickToAnswer) {
            return false;
        }
        int index = armedIndex;
        if (index < 0) {
            return false;
        }
        if (System.currentTimeMillis() - armedAt > ARMED_MS) {
            disarm();
            return false;
        }
        disarm();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.connection == null) {
            return false;
        }
        try {
            minecraft.player.connection.sendCommand(ANSWER_COMMAND + index);
        } catch (RuntimeException e) {
            // Not connected, or the server refused it. Never retried - one click, one attempt.
            DianaDebug.getInstance().note("sphinx answer not sent: " + e.getMessage());
            return false;
        }
        return true;
    }

    /** Whether a click right now would answer the riddle, for the settings row and the readout. */
    public boolean armed() {
        return armedIndex >= 0 && System.currentTimeMillis() - armedAt <= ARMED_MS;
    }

    private void disarm() {
        armedIndex = -1;
        armedAt = 0L;
    }

    private void clear() {
        expected = null;
        offered.clear();
        sessionAt = 0L;
    }

    /** World change, server hop, or the player asking. Drops the arming with everything else. */
    public void reset() {
        clear();
        disarm();
    }

    /**
     * The index the answer command takes for a letter.
     *
     * <p>Zero-based, and <b>unverified</b>: it is carried from community documentation of the
     * command and nobody has watched it being accepted. A wrong base answers the riddle wrongly,
     * which is why the marking above is the part that ships on and this is the part that does not.
     */
    private static int indexOf(String letter) {
        return switch (letter) {
            case "A" -> 0;
            case "B" -> 1;
            case "C" -> 2;
            default -> -1;
        };
    }

    /**
     * Whether an offered answer is the expected one, comparing the way {@link #normalise} does.
     *
     * <p>Public and static for the reason {@link #answerIn} is: it is the comparison the whole
     * feature turns on, and an exact-string version of it is what shipped broken.
     */
    public static boolean matches(String offeredText, String expectedText) {
        if (offeredText == null || expectedText == null) {
            return false;
        }
        String left = normalise(offeredText);
        String right = normalise(expectedText);
        return !right.isEmpty() && (left.equals(right) || left.contains(right));
    }

    /**
     * Letters, digits and single spaces, lower case. Punctuation and colour are not identity.
     *
     * <p>Null-safe, because the three methods above it are public: a caller outside this class has
     * no reason to know that {@code onChat} happens to check first, and a helper that throws on the
     * one input every caller can produce is a helper that has to be remembered rather than used.
     */
    private static String normalise(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }
}
