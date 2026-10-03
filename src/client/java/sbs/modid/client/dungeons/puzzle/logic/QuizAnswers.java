/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.dungeons.puzzle.model.QuizData;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The lookup in front of {@link QuizData}: question text in, correct answer out, {@code null} when
 * the question is not in the set.
 *
 * <p><b>{@code null} is the important return value.</b> It is what makes the solver show nothing, and
 * nothing is the only safe output for a question this build does not know - one wrong click fails
 * Oruo's room outright and costs the party 14 points of Skill score.
 *
 * <p>Matching is on a {@linkplain #key normalised} form so a stray comma, a colour code that survived
 * stripping or a changed apostrophe cannot turn a known question into an unknown one. It is
 * deliberately not fuzzy beyond that: a near-match that resolved to the wrong question would produce
 * a confident wrong highlight, which is worse than the blank one an exact miss produces.
 */
public final class QuizAnswers {

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/dungeons/quiz_answers.json";

    /** The highest schema this build reads; bump only alongside a parser change. */
    private static final int SUPPORTED_SCHEMA = 1;

    private static final VersionedDataStore<QuizData> STORE = new VersionedDataStore<>(
            "QuizAnswers", RESOURCE,
            SBSFiles.root().resolve("data").resolve("quiz_answers.json"),
            "/api/dungeons/quiz", QuizData.class, SUPPORTED_SCHEMA);

    /** Normalised question → answer, rebuilt when the store publishes a different document. */
    private static volatile Map<String, String> index = Map.of();
    private static volatile QuizData indexed;

    private QuizAnswers() {
    }

    /** Loads bundled + cached copies and starts the background refresh. Call on client init. */
    public static void load() {
        STORE.load();
        reindex();
    }

    /**
     * The correct answer for a question, or {@code null} when it is not in the set.
     *
     * @param question the question text, colour-stripped
     */
    public static String answerFor(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        QuizData document = STORE.get();
        if (document != indexed) {
            reindex();
        }
        return index.get(key(question));
    }

    /** How many questions are loaded, for the settings status line. */
    public static int size() {
        return index.size();
    }

    /** Where the live document came from, for the settings status line. */
    public static String source() {
        return STORE.source();
    }

    private static synchronized void reindex() {
        QuizData document = STORE.get();
        indexed = document;
        if (document == null || document.questions == null) {
            index = Map.of();
            return;
        }
        Map<String, String> built = new HashMap<>();
        for (QuizData.Entry entry : document.questions) {
            if (entry == null || entry.question == null || entry.answer == null
                    || entry.question.isBlank() || entry.answer.isBlank()) {
                continue;
            }
            built.put(key(entry.question), entry.answer.trim());
        }
        index = Map.copyOf(built);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] quiz answers loaded: {} questions ({})",
                index.size(), STORE.source());
    }

    /**
     * Lower-cased with everything that is not a letter or digit removed.
     *
     * <p>Punctuation is the whole reason this exists: the same question reaches us with and without
     * a trailing question mark, with a curly or a straight apostrophe, and with the spacing Hypixel
     * happens to use that week. None of that changes which question it is.
     */
    static String key(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
