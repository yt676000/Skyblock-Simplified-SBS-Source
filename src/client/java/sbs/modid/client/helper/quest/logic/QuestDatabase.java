/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest.logic;

import com.google.gson.reflect.TypeToken;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.helper.quest.model.Quest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The quest definitions, loaded once at startup from the mod's bundled resources.
 *
 * <p>{@code assets/skyblock-simplified-sbs/quests/index.json} is a plain array of file names; each
 * names a quest file in the same folder. Adding a quest is therefore <b>two data edits and no
 * code</b> - the file itself, and one line in the index - exactly like
 * {@link sbs.modid.client.helper.map.logic.MapDatabase}.
 *
 * <p><b>Bundled, not fetched.</b> The definitions used to live on the SBS API behind the licence
 * check; they ship in the jar now, so the Quest Guide needs no token, no network and no loading
 * state. Everything a quest costs is one read at startup.
 *
 * <p>Read through the classloader ({@link Class#getResourceAsStream}): pure Java, no Fabric API, and
 * it works the same in {@code runClient} and in the built jar.
 *
 * <p><b>One bad file does not take the guide down.</b> Each quest is parsed on its own and a failure
 * is logged and skipped, because one working quest is far more useful than none.
 */
public final class QuestDatabase {

    private static final String FOLDER = "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/quests/";
    private static final String INDEX = FOLDER + "index.json";

    private static final Type INDEX_TYPE = new TypeToken<List<String>>() {
    }.getType();

    private static List<Quest> quests = List.of();

    private QuestDatabase() {
    }

    /** Loads (or reloads) every quest into memory. Safe to call on client init. */
    public static void load() {
        List<String> files = readIndex();
        if (files.isEmpty()) {
            return;
        }
        List<Quest> loaded = new ArrayList<>(files.size());
        for (String file : files) {
            Quest quest = readQuest(file);
            if (quest != null) {
                loaded.add(quest);
            }
        }
        quests = List.copyOf(loaded);

        int steps = 0;
        for (Quest quest : quests) {
            steps += quest.stepCount();
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Quests] Loaded {} quest(s), {} step(s).",
                quests.size(), steps);
    }

    /**
     * Whether every step in a quest carries a unique, non-blank id - and refuses the whole quest
     * when they do not.
     *
     * <p><b>Refusing is the point.</b> Progress is stored by step id, so a blank id is a step nobody
     * can be recorded as standing on, and a duplicated id is two steps the player's record cannot
     * tell apart - resuming would land on whichever came first, which is the position-based bug this
     * whole change removes, wearing an id. Loading such a quest "mostly correctly" would put a
     * player's progress somewhere silently wrong; a quest that visibly does not appear is a bug
     * report, and a bug report is the better failure.
     */
    private static boolean hasUsableStepIds(Quest quest, String path) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < quest.steps.size(); i++) {
            Quest.QuestStep step = quest.steps.get(i);
            String id = step == null ? null : step.id;
            if (id == null || id.isBlank()) {
                SkyblockSimplifiedSBS.LOGGER.error("[SBS][Quests] Skipping {} - step {} has no "
                        + "\"id\". Progress is stored by id, so a step without one can never be "
                        + "resumed", path, i);
                return false;
            }
            if (!seen.add(id)) {
                SkyblockSimplifiedSBS.LOGGER.error("[SBS][Quests] Skipping {} - step id \"{}\" is "
                        + "used twice. Two steps sharing an id cannot be told apart in a saved "
                        + "record", path, id);
                return false;
            }
        }
        return true;
    }

    private static List<String> readIndex() {
        try (InputStream in = QuestDatabase.class.getResourceAsStream(INDEX)) {
            if (in == null) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Quests] Quest index not found: {}", INDEX);
                return List.of();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                List<String> parsed = SBSFiles.GSON.fromJson(reader, INDEX_TYPE);
                return parsed == null ? List.of() : parsed;
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Quests] Failed to read the quest index", e);
            return List.of();
        }
    }

    private static Quest readQuest(String file) {
        String path = FOLDER + file;
        try (InputStream in = QuestDatabase.class.getResourceAsStream(path)) {
            if (in == null) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Quests] Quest file listed but missing: {}",
                        path);
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Quest quest = SBSFiles.GSON.fromJson(reader, Quest.class);
                if (quest == null || quest.id == null || quest.id.isBlank()
                        || quest.steps == null || quest.steps.isEmpty()) {
                    SkyblockSimplifiedSBS.LOGGER.warn(
                            "[SBS][Quests] Skipping {} - no \"id\" or no steps, so nothing could be "
                                    + "tracked", path);
                    return null;
                }
                if (quest.name == null || quest.name.isBlank()) {
                    quest.name = quest.id;
                }
                if (!hasUsableStepIds(quest, path)) {
                    return null;
                }
                return quest;
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Quests] Failed to load quest file {}", path, e);
            return null;
        }
    }

    /** Every loaded quest, in index order. */
    public static List<Quest> all() {
        return quests;
    }

    /** The quest with this id, or {@code null}. */
    public static Quest byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Quest quest : quests) {
            if (id.equalsIgnoreCase(quest.id)) {
                return quest;
            }
        }
        return null;
    }
}
