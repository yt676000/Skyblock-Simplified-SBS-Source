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
import sbs.modid.client.economy.minions.logic.MinionCatalogs;
import sbs.modid.client.economy.minions.model.MinionData;
import sbs.modid.client.helper.fairysouls.logic.FairySoulDatabase;
import sbs.modid.client.helper.timers.model.EventCalendar;

import java.util.List;

/**
 * The live answer table: {@link QuizData} through {@code VersionedDataStore}, resolved by
 * {@link QuizResolver} against the mod's own Fairy Soul file, minion catalogue and calendar.
 *
 * <p>The rules themselves live in {@link QuizResolver}; this class only owns the store and the
 * {@link QuizResolver.Facts} that read the other datasets at the moment a question is asked.
 */
public final class QuizAnswers {

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/dungeons/quiz_answers.json";

    /** The highest schema this build reads; bump only alongside a parser change. */
    private static final int SUPPORTED_SCHEMA = 2;

    private static final VersionedDataStore<QuizData> STORE = new VersionedDataStore<>(
            "QuizAnswers", RESOURCE,
            SBSFiles.root().resolve("data").resolve("quiz_answers.json"),
            "/api/dungeons/quiz", QuizData.class, SUPPORTED_SCHEMA);

    /** The mod's own numbers, read when asked - never cached here, so a data refresh is seen at once. */
    private static final QuizResolver.Facts LIVE = new QuizResolver.Facts() {
        @Override
        public int fairySoulsOn(String island) {
            return FairySoulDatabase.countOn(island);
        }

        @Override
        public int fairySoulsTotal() {
            return FairySoulDatabase.totalInGame();
        }

        @Override
        public int minionCount() {
            MinionData minions = MinionCatalogs.minions();
            return minions == null || minions.minions == null ? 0 : minions.minions.size();
        }

        @Override
        public long skyblockYear() {
            return EventCalendar.yearAt(System.currentTimeMillis());
        }
    };

    private static volatile QuizResolver resolver = new QuizResolver(null);
    private static volatile QuizData indexed;

    private QuizAnswers() {
    }

    /** Loads bundled + cached copies and starts the background refresh. Call on client init. */
    public static void load() {
        STORE.load();
        reindex();
    }

    /** The option answering {@code question}, or {@code null} - see {@link QuizResolver#resolve}. */
    public static QuizResolver.Resolution resolve(String question, List<String> options) {
        return current().resolve(question, options, LIVE);
    }

    /** Whether the table has a row for this question at all. */
    public static boolean knows(String question) {
        return current().knows(question);
    }

    /** How many questions are loaded, for the settings status line. */
    public static int size() {
        return current().size();
    }

    /** Where the live document came from, for the settings status line. */
    public static String source() {
        return STORE.source();
    }

    private static QuizResolver current() {
        if (STORE.get() != indexed) {
            reindex();
        }
        return resolver;
    }

    private static synchronized void reindex() {
        QuizData document = STORE.get();
        if (document == indexed) {
            return;
        }
        indexed = document;
        resolver = new QuizResolver(document);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Quiz] answer table loaded: {} questions ({})",
                resolver.size(), STORE.source());
    }
}
