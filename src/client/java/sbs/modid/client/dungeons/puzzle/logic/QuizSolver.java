/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.skills.farming.model.FarmingText;

import java.util.ArrayList;
import java.util.List;

/**
 * Oruo's Quiz: three SkyBlock questions, three buttons each, and one wrong click fails the whole
 * room. The most tractable solver of the set - the question is printed in chat and each answer is
 * written on the button it belongs to, so nothing has to be inferred from geometry.
 *
 * <p><b>How the answer is found on screen.</b> Rather than parsing the option lines out of chat and
 * mapping option order to button position - two guesses stacked on each other - the correct answer
 * text from {@link QuizAnswers} is matched against the floating labels already in the world. The
 * button carrying that label is the button to press. Chat supplies the question; the world supplies
 * the position.
 *
 * <p><b>Three ways this refuses to answer, all of them silent:</b>
 * <ul>
 *   <li>the question is not in the data set - logged in full so the set can be extended from a real
 *       run, never guessed at;</li>
 *   <li>no label in the world matches the answer;</li>
 *   <li><b>more than one</b> label matches. An ambiguous read is treated exactly like no read: with
 *       one wrong click costing the party the room, "probably that one" is not worth showing.</li>
 * </ul>
 *
 * <p><b>Unverified:</b> the shape of the line Oruo speaks. It is keyed on the name plus a question
 * mark, which is as narrow as it can honestly be made before someone reads a real transcript. Every
 * line that looks like a question and every question that misses the data set is logged under
 * {@code [SBS][Puzzle]}.
 */
public final class QuizSolver implements PuzzleStrategy {

    /** Green box on the correct button. */
    private static final int ANSWER_COLOR = 0xFF55FF55;

    /** How far a matching label may be before it is assumed to belong to another room. */
    private static final double MAX_RANGE = 24.0;

    /** The nametag sweep is throttled; the labels do not move. */
    private static final long SCAN_MS = 250;

    private String question;
    private String answer;
    private String answerKey;
    private AABB target;

    private long lastScan;
    private String lastUnknownLogged = "";

    @Override
    public PuzzleType type() {
        return PuzzleType.QUIZ;
    }

    @Override
    public void onChat(String line) {
        String asked = questionFrom(line);
        if (asked == null || asked.equals(question)) {
            return;
        }
        question = asked;
        answer = QuizAnswers.answerFor(asked);
        answerKey = answer == null ? null : QuizAnswers.key(answer);
        target = null;
        if (answer == null) {
            logUnknown(asked);
        } else {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Puzzle] quiz question matched, answer '{}'", answer);
        }
    }

    /**
     * The question text out of a line Oruo spoke, or {@code null} when this is not one.
     *
     * <p>Keyed on the speaker's name and the presence of a question mark rather than on a
     * transcribed prefix: the prefix is exactly the part nobody has verified, and a pattern written
     * around a guessed one fails closed and silently. Everything up to and including the first colon
     * after the name is the speaker tag, so the question is what follows it.
     */
    private static String questionFrom(String line) {
        if (line == null) {
            return null;
        }
        int speaker = line.indexOf("Oruo");
        if (speaker < 0 || line.indexOf('?') < 0) {
            return null;
        }
        int colon = line.indexOf(':', speaker);
        String text = (colon >= 0 ? line.substring(colon + 1) : line.substring(speaker + 4)).trim();
        return text.length() < 8 ? null : text;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (answerKey == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_MS) {
            return;
        }
        lastScan = now;

        AABB found = null;
        int matches = 0;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.distanceToSqr(minecraft.player.position()) > MAX_RANGE * MAX_RANGE) {
                continue;
            }
            Component name = entity.getCustomName();
            if (name == null) {
                continue;
            }
            if (QuizAnswers.key(FarmingText.strip(name.getString())).contains(answerKey)) {
                matches++;
                found = entity.getBoundingBox();
            }
        }
        // Two labels carrying the same answer text means the read is ambiguous, and an ambiguous
        // read is worth exactly as much as no read at all here.
        target = matches == 1 ? found : null;
    }

    @Override
    public List<PuzzleHighlight> highlights() {
        if (target == null) {
            return List.of();
        }
        List<PuzzleHighlight> out = new ArrayList<>(1);
        out.add(PuzzleHighlight.answer(target.inflate(0.15), ANSWER_COLOR, "§aANSWER"));
        return out;
    }

    @Override
    public void reset() {
        question = null;
        answer = null;
        answerKey = null;
        target = null;
        lastScan = 0;
        lastUnknownLogged = "";
    }

    /**
     * Logs a question with no row in the data set, once per distinct question.
     *
     * <p>At {@code info} and with the full text, because this log <i>is</i> the mechanism by which
     * the answer set grows - a truncated or debug-level line would make the one artefact the feature
     * needs from a real run the one thing nobody has.
     */
    private void logUnknown(String asked) {
        if (asked.equals(lastUnknownLogged)) {
            return;
        }
        lastUnknownLogged = asked;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] quiz question NOT in the answer set - "
                + "add it to quiz_answers.json with the option that turned out correct: '{}'", asked);
    }
}
