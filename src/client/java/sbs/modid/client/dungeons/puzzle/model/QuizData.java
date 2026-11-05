/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.model;

import sbs.modid.client.core.data.VersionedDocument;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.List;

/**
 * Oruo's question bank ({@code dungeons/quiz_answers.json}): per question, how to tell which of the
 * three offered options is right.
 *
 * <p><b>Why a versioned dataset.</b> Hypixel adds questions, and a question this build has never
 * seen must not cost anyone a release cycle - the file updates from the backend through
 * {@code VersionedDataStore} exactly like the Fairy Soul and HotM tables.
 *
 * <p><b>Three kinds of row</b>, exactly one rule per row:
 * <ul>
 *   <li>{@link Entry#answers} - the right option is one of these strings (a name, a status);</li>
 *   <li>{@link Entry#notAmong} - the right option is the one that is <i>not</i> in this list ("which
 *       of these is not a dragon in The End?" lists the real dragons);</li>
 *   <li>{@link Entry#dynamic} - the answer is a number this build already knows from its own data
 *       (a fairy-soul count, the minion catalogue, the calendar), so a stale constant cannot drift
 *       out of step with the rest of the mod.</li>
 * </ul>
 * Whatever the rule, the solver only shows an answer when <b>exactly one</b> of the three options
 * satisfies it.
 *
 * <p><b>Fields are data, never behaviour.</b> {@code dynamic} is a closed enum of quantities the
 * client computes itself; the file cannot name a command, a slot or anything the client acts on.
 *
 * <p>Schema 2 replaced schema 1's single {@code answer} string. A schema-1 build refuses this file
 * rather than reading every row as blank.
 */
public final class QuizData implements VersionedDocument {

    public int schemaVersion = 2;
    public int dataVersion;
    public String generatedAt = "";

    public List<Entry> questions = new ArrayList<>();

    /** A quantity the client computes from its own data at the moment the question is asked. */
    public enum Dynamic {
        /** "Year N" from the SkyBlock calendar. */
        SKYBLOCK_YEAR,
        /** "N Fairy Souls" on {@link Entry#island}, from the bundled Fairy Soul file. */
        FAIRY_SOULS_ON_ISLAND,
        /** "N Fairy Souls" across the game, from the Fairy Soul file's own total. */
        FAIRY_SOULS_TOTAL,
        /** "N Minions", the number of minion types in the minion catalogue. */
        MINION_COUNT
    }

    /** One question and the rule that picks its answer. */
    public static final class Entry {
        /** The question text, wrapped lines joined with one space. Matched loosely - see {@code QuizResolver.key}. */
        public String question = "";
        /** Accepted answers, as the option reads. */
        public List<String> answers = new ArrayList<>();
        /** The answer is the single option not in this list. */
        public List<String> notAmong = new ArrayList<>();
        /** Computed answer; {@code null} for a static row. */
        public Dynamic dynamic;
        /** The island a {@link Dynamic#FAIRY_SOULS_ON_ISLAND} row counts, as the Fairy Soul file names it. */
        public String island = "";
        /** How far this row is trusted. Only CONFIRMED and WIKI rows are shown. */
        public Certainty certainty = Certainty.UNKNOWN;
        /** Where the fact comes from - a wiki page, or the dataset a dynamic row reads. */
        public String source = "";
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
