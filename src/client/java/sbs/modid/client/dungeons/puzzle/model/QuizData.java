/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.model;

import sbs.modid.client.core.data.VersionedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * Oruo's question bank ({@code dungeons/quiz_answers.json}): the question text and the one right
 * answer, nothing else.
 *
 * <p><b>Why a versioned dataset.</b> Hypixel adds questions, and a question this build has never
 * seen must not cost anyone a release cycle - the file updates from the backend through
 * {@code VersionedDataStore} exactly like the Fairy Soul and HotM tables. The bundled copy is what
 * makes an unlicensed or offline client work at all, and it ships small on purpose: the set grows
 * from questions logged during real runs, not from a wiki scrape nobody can check.
 *
 * <p><b>Fields are data, never behaviour.</b> A row names a question and an answer string. It cannot
 * name a command, a slot index or anything else the client would act on - the answer is matched
 * against text already on screen and used to colour a box.
 *
 * <p><b>Partial coverage is the normal state and must stay harmless.</b> A question with no row is
 * not an error and is never guessed at: {@code QuizSolver} shows nothing and logs the full text, so
 * the gap closes from evidence. One wrong click fails the room for the whole party.
 */
public final class QuizData implements VersionedDocument {

    public int schemaVersion = 1;
    public int dataVersion;
    public String generatedAt = "";

    public List<Entry> questions = new ArrayList<>();

    /** One question and its single correct answer, both as Hypixel prints them. */
    public static final class Entry {
        /** The question text. Matched loosely - see {@code QuizAnswers.key}. */
        public String question = "";
        /** The exact correct answer, as it appears on the button's floating label. */
        public String answer = "";
    }

    @Override
    public int schemaVersion() {
        return schemaVersion;
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }

    @Override
    public boolean valid() {
        return questions != null;
    }
}
