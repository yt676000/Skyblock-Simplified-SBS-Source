/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.requeue.logic;

import sbs.modid.client.dungeons.requeue.model.RequeueTarget;
import sbs.modid.client.dungeons.requeue.model.RunEnd;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells a finished run from every other chat line. Pure: the game state it needs comes in as a
 * {@link Snapshot}, so the whole decision is testable on the real lines.
 *
 * <p><b>CONFIRMED</b> (maintainer's logs, colour-stripped): {@code > EXTRA STATS <}, {@code Team
 * Score: 300 (S+)}, the floor title {@code The Catacombs - Floor VII} / {@code Master Mode The
 * Catacombs - Floor VII} (older: {@code Master Mode Catacombs - Floor VI}), {@code KUUDRA DOWN!} and
 * {@code DEFEAT}.
 *
 * <p>Every pattern is anchored on the whole (trimmed) line. That is what rejects a relay such as
 * {@code Party > [MVP+] X: KUUDRA DOWN!}: anything with a speaker in front is not the server's line.
 *
 * <p>A dungeon run only counts as completed when the summary arrives <b>and</b> the run was in the
 * boss room ({@code DungeonStateManager.phase() == BOSS}). Whether a <i>failed</i> run prints the same
 * summary is unverified; the phase gate is what keeps an early leave from counting.
 */
public final class RunEndDetector {

    private static final Pattern EXTRA_STATS = Pattern.compile("^>\\s*EXTRA STATS\\s*<$");
    private static final Pattern TEAM_SCORE = Pattern.compile("^Team Score: \\d+ \\([SABCD]\\+?\\)");
    private static final Pattern FLOOR_TITLE = Pattern.compile(
            "^(Master Mode )?(?:The )?Catacombs - (?:Floor ([IV]{1,3})|Entrance)$");

    /** What the trackers say at the moment the line arrives. */
    public record Snapshot(boolean dungeonBoss, char floorType, int floorNumber,
                           boolean inHollow, int kuudraTier) {
    }

    /** The floor the summary's title line named; preferred over the sidebar's. */
    private RequeueTarget titleFloor;
    /** One end per run: the summary has several lines that would each count. */
    private boolean ended;

    /** Why the last summary line did not count, for the log; {@code null} when it was not one. */
    private String rejected;

    /** @return why the last line was a summary that did not count, or {@code null} */
    public String rejected() {
        return rejected;
    }

    /** A new instance (or world) starts over. */
    public void reset() {
        titleFloor = null;
        ended = false;
    }

    /** @return the run end this line completes, or {@code null} */
    public RunEnd accept(String line, Snapshot s) {
        if (line == null) {
            return null;
        }
        rejected = null;
        String text = line.strip();
        if (text.isEmpty()) {
            return null;
        }
        char first = text.charAt(0);
        if (first == 'M' || first == 'T' || first == 'C') {
            Matcher title = FLOOR_TITLE.matcher(text);
            if (title.matches()) {
                boolean master = title.group(1) != null;
                int number = title.group(2) == null ? 0 : roman(title.group(2));
                titleFloor = master ? RequeueTarget.floor('M', number)
                        : RequeueTarget.floor(number == 0 ? 'E' : 'F', number);
                return null;
            }
        }
        if (ended) {
            return null;
        }
        if ((first == '>' && EXTRA_STATS.matcher(text).matches())
                || (first == 'T' && TEAM_SCORE.matcher(text).find())) {
            if (!s.dungeonBoss()) {
                rejected = "not in the boss phase";
                return null;
            }
            ended = true;
            RequeueTarget target = titleFloor != null ? titleFloor
                    : RequeueTarget.floor(s.floorType(), s.floorNumber());
            return new RunEnd(RunEnd.Kind.COMPLETED, RequeueTarget.Category.DUNGEONS, target, text);
        }
        if (text.equals("KUUDRA DOWN!") && s.inHollow()) {
            ended = true;
            return new RunEnd(RunEnd.Kind.COMPLETED, RequeueTarget.Category.KUUDRA,
                    RequeueTarget.kuudra(s.kuudraTier()), text);
        }
        if (text.equals("DEFEAT") && s.inHollow()) {
            ended = true;
            return new RunEnd(RunEnd.Kind.LOST, RequeueTarget.Category.KUUDRA,
                    RequeueTarget.kuudra(s.kuudraTier()), text);
        }
        return null;
    }

    /** I-VII; the floors never go higher. */
    static int roman(String numeral) {
        return switch (numeral) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            case "VII" -> 7;
            default -> 0;
        };
    }
}
