/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import java.util.regex.Pattern;

/**
 * When Oruo's next question arrives, and whether the shown answer is done. Pure, so the timings
 * are unit-tested.
 *
 * <p><b>Timings</b> (CONFIRMED, maintainer's logs 2026-10-12/15/16, three rooms, every gap equal to
 * the second): the opening line {@code "I am Oruo the Omniscient..."} comes 8 s before Question #1,
 * and each {@code "<player> answered Question #N correctly!"} 4 s before the next question. After
 * {@code "answered the final question correctly!"} nothing follows.
 *
 * <p>Lines are colour-stripped, with the 26.x {@code "> "} prefix already removed by the coordinator.
 */
public final class QuizTimer {

    static final long FIRST_QUESTION_MS = 8_000;
    static final long NEXT_QUESTION_MS = 4_000;

    private static final String ORUO = "^\\[STATUE] Oruo the Omniscient: ";
    private static final Pattern INTRO = Pattern.compile(ORUO + "I am Oruo the Omniscient\\b.*");
    private static final Pattern ANSWERED = Pattern.compile(ORUO + "\\S+ answered Question #\\d+ correctly!$");
    private static final Pattern FINAL = Pattern.compile(ORUO + "\\S+ answered the final question correctly!$");

    /** What a line means for the quiz. */
    public enum Event {
        NONE,
        /** The opening line: the first question is on its way. */
        INTRO,
        /** A question was answered right and another follows: the answer box goes. */
        ANSWERED,
        /** The last question was answered right: the answer box goes, no timer. */
        FINISHED
    }

    private long nextQuestionAt;

    /** Reads one line at {@code nowMs}; arms or clears the countdown accordingly. */
    public Event offer(String line, long nowMs) {
        if (line == null || !line.startsWith("[STATUE] Oruo")) {
            return Event.NONE;
        }
        if (INTRO.matcher(line).matches()) {
            nextQuestionAt = nowMs + FIRST_QUESTION_MS;
            return Event.INTRO;
        }
        if (ANSWERED.matcher(line).matches()) {
            nextQuestionAt = nowMs + NEXT_QUESTION_MS;
            return Event.ANSWERED;
        }
        if (FINAL.matcher(line).matches()) {
            nextQuestionAt = 0;
            return Event.FINISHED;
        }
        return Event.NONE;
    }

    /** A question arrived: the countdown is over. */
    public void questionShown() {
        nextQuestionAt = 0;
    }

    /** Milliseconds until the next question, or {@code 0} when no countdown runs. */
    public long remainingMs(long nowMs) {
        return nextQuestionAt == 0 ? 0 : Math.max(0, nextQuestionAt - nowMs);
    }

    public void clear() {
        nextQuestionAt = 0;
    }
}
