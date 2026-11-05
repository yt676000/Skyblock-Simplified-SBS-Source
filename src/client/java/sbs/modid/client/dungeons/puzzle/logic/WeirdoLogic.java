/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Three Weirdos: which of the three NPCs' chests holds the reward, as a pure function of what they
 * said.
 *
 * <p><b>The answer is read off the statement, not deduced from it.</b> Every statement the room uses
 * belongs to one of two fixed sets: six are only ever spoken by the NPC whose chest holds the reward,
 * twelve only ever by the other two. This is {@code CONFIRMED} from the maintainer's own chat logs -
 * 307 complete rooms, each closed by the server's {@code "PUZZLE SOLVED! <player> wasn't fooled by
 * <Name>!"} line, and in every one of them the named NPC is the one who spoke a {@link Role#REWARD}
 * statement and the other two spoke {@link Role#DECOY} ones. The test fixture
 * {@code src/test/resources/dungeons/puzzle/three-weirdos-rooms.txt} is those rooms.
 *
 * <p>A truth-table deduction ("exactly one of them tells the truth") was the first version of this
 * class, and the same logs refute it: it named the logged chest in 47 rooms, a different chest in
 * 56, and could not decide 204. Several reward statements are false when read literally ("The reward
 * is not in my chest!" is said by the NPC whose chest has it), so no consistent reading of the words
 * reproduces the server's answer. Reading the statements as a puzzle is what the room invites and is
 * the wrong model.
 *
 * <p>Every pattern is anchored and matched against colour-stripped text, so a reworded line matches
 * nothing and the room stays undetermined - it never falls through to a wrong role.
 */
public final class WeirdoLogic {

    /** Which side of the room a statement puts its speaker on. */
    public enum Role {
        /** Spoken only by the NPC whose chest holds the reward. */
        REWARD,
        /** Spoken only by the other two. */
        DECOY
    }

    /** One weirdo's line, reduced to who said it and which side that puts them on. */
    public record Statement(String speaker, Role role) {
    }

    /**
     * {@code "[NPC] Carver: The reward is in my chest!"}, colour-stripped. The name is one capitalised
     * word in every logged room; a client-side chat stacking suffix {@code " (2)"} is dropped.
     */
    private static final Pattern NPC_LINE =
            Pattern.compile("^\\[NPC] ([A-Z][A-Za-z]{1,15}): (.+?)(?: \\(\\d+\\))?$");

    /** A name inside a statement - another of the three weirdos. */
    private static final String NAME = "[A-Z][A-Za-z]{1,15}";

    /**
     * The complete set of statements seen in the logs, each with the role it always carried.
     * Wording is verbatim, punctuation included: the server uses both "!" and "." endings and the
     * patterns keep them, because a looser match is how a new line would get an old line's role.
     */
    private static final List<Map.Entry<Pattern, Role>> STATEMENTS = List.of(
            // Spoken by the NPC whose chest holds the reward.
            rule("The reward is not in my chest!", Role.REWARD),
            rule("The reward isn't in any of our chests\\.", Role.REWARD),
            rule("My chest has the reward and I'm telling the truth!", Role.REWARD),
            rule("My chest doesn't have the reward\\. We are all telling the truth\\.", Role.REWARD),
            rule("At least one of them is lying, and the reward is not in " + NAME + "'s chest!",
                    Role.REWARD),
            rule("Both of them are telling the truth\\. Also, " + NAME
                    + " has the reward in their chest!", Role.REWARD),
            // Spoken by the other two.
            rule("The reward is in my chest!", Role.DECOY),
            rule("My chest has the reward!", Role.DECOY),
            rule("The reward is not in my chest\\. They are both lying\\.", Role.DECOY),
            rule("They are both lying, the reward is in my chest!", Role.DECOY),
            rule("My chest doesn't have the reward\\. At least one of the others is telling the truth!",
                    Role.DECOY),
            rule("One of us is telling the truth!", Role.DECOY),
            rule("One of the others is lying!", Role.DECOY),
            rule("We are all telling the truth!", Role.DECOY),
            rule(NAME + " is telling the truth\\.", Role.DECOY),
            rule(NAME + " is telling the truth and the reward is in his chest\\.", Role.DECOY),
            rule("They are both telling the truth\\. The reward isn't in " + NAME + "'s chest\\.",
                    Role.DECOY),
            rule("They are both telling the truth, the reward is in " + NAME + "'s chest!", Role.DECOY));

    private static Map.Entry<Pattern, Role> rule(String regex, Role role) {
        return Map.entry(Pattern.compile("^" + regex + "$"), role);
    }

    private WeirdoLogic() {
    }

    /** What a weirdo says when clicked before the others have been - not a statement. */
    private static final String OPENER = "Talk to all of us first!";

    /** Whether a colour-stripped chat line has the {@code [NPC] Name: text} shape at all. */
    public static boolean isNpcLine(String line) {
        return line != null && line.startsWith("[NPC] ");
    }

    /** Whether the line is a weirdo's "Talk to all of us first!" opener, stacking counter or not. */
    public static boolean isOpener(String line) {
        if (!isNpcLine(line)) {
            return false;
        }
        Matcher npc = NPC_LINE.matcher(line);
        return npc.matches() && OPENER.equals(npc.group(2));
    }

    /**
     * The statement in a colour-stripped chat line, or {@code null} when the line is not one of the
     * known weirdo statements - another NPC, the "Talk to all of us first!" opener, or wording this
     * build has never seen.
     */
    public static Statement parse(String line) {
        if (!isNpcLine(line)) {
            return null;
        }
        Matcher npc = NPC_LINE.matcher(line);
        if (!npc.matches()) {
            return null;
        }
        String text = npc.group(2);
        for (Map.Entry<Pattern, Role> statement : STATEMENTS) {
            if (statement.getKey().matcher(text).matches()) {
                return new Statement(npc.group(1), statement.getValue());
            }
        }
        return null;
    }

    /**
     * The name of the NPC whose chest holds the reward, or {@code null} when that is not certain.
     *
     * <p>Decided as soon as one reward statement has been heard: in no logged room did a decoy say
     * one, so the player does not have to talk to all three first. Returns {@code null} when no
     * reward statement has been heard, when two different speakers spoke one, when one speaker is
     * recorded on both sides, or when more than three speakers are in the set - each of those means
     * the lines were mis-attributed, and an answer built on them would be a guess.
     */
    public static String solve(List<Statement> heard) {
        if (heard == null || heard.isEmpty()) {
            return null;
        }
        Map<String, Role> bySpeaker = new HashMap<>();
        for (Statement statement : heard) {
            if (statement == null) {
                continue;
            }
            Role previous = bySpeaker.putIfAbsent(statement.speaker(), statement.role());
            if (previous != null && previous != statement.role()) {
                return null;
            }
        }
        if (bySpeaker.size() > 3) {
            return null;
        }
        String winner = null;
        for (Map.Entry<String, Role> entry : bySpeaker.entrySet()) {
            if (entry.getValue() != Role.REWARD) {
                continue;
            }
            if (winner != null) {
                return null;
            }
            winner = entry.getKey();
        }
        return winner;
    }
}
