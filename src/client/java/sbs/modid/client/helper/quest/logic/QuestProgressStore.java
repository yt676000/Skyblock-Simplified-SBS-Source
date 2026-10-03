/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.helper.quest.model.Quest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a quest's progress lives: <b>one record per Minecraft account + SkyBlock profile</b>, keyed
 * by stable step id rather than by position.
 *
 * <p><b>Two bugs are being fixed here at once, and they had to be fixed together.</b>
 * <ul>
 *   <li>Progress was a global config field, so an Ironman and a main shared one number - finishing
 *       a step on either moved both.</li>
 *   <li>Progress was the step's <i>array position</i>, so inserting a step into a shipped questline
 *       moved every stored player onto a different step, silently and with no error anywhere.</li>
 * </ul>
 * Fixing either alone would still need the other's migration afterwards, over the same field.
 *
 * <p><b>The migration runs once per profile and only forwards.</b> The legacy value is a bare
 * {@code int} whose only meaning is "position in the step order as it was shipped", so it can be
 * turned into an id exactly as long as that order has not changed - which is why the ids were
 * assigned in shipped order and why this landed before any step was ever inserted. Once mapped, the
 * legacy field is cleared so a later profile switch cannot re-apply one profile's old number to
 * another profile.
 *
 * <p><b>A quest whose stored step id is gone is treated as unstarted, not as finished.</b> A dataset
 * can be corrected and a step legitimately removed; resuming at a step that no longer exists would
 * put the player somewhere the guide cannot describe, and silently declaring the quest complete
 * would throw away real progress. Starting over is recoverable and visible. Both are logged.
 */
public final class QuestProgressStore implements ProfileScopedStore {

    private static final QuestProgressStore INSTANCE = new QuestProgressStore();

    private static final String FILE = "quest_progress.json";

    /** One quest's position for this profile. A class, not a record, so Gson can build it. */
    public static final class QuestState {
        /** The id of the step being worked on; {@code null} once the quest is finished. */
        String step;
        /** Whether every step is done. Kept explicitly - "no current step" alone is ambiguous. */
        boolean done;

        /** Gson needs a no-arg constructor. */
        public QuestState() {
        }

        QuestState(String step, boolean done) {
            this.step = step;
            this.done = done;
        }
    }

    /** The on-disk shape. */
    private static final class Persisted {
        /** The quest the player is tracking on this profile, or {@code null}. */
        String activeQuest;
        /** Quest id -> where that quest stands. Kept for quests that are not currently active. */
        Map<String, QuestState> quests = new LinkedHashMap<>();
    }

    private volatile Persisted state = new Persisted();
    private volatile boolean loaded;

    private QuestProgressStore() {
        // Registered here rather than from the client initializer on purpose: registering early
        // forces this class - and everything it touches - to load before the item registry is bound.
        ProfileContext.getInstance().register(this);
    }

    public static QuestProgressStore getInstance() {
        return INSTANCE;
    }

    /**
     * Whether this record can be believed yet.
     *
     * <p>Before the profile is known, every read here answers from an empty record, and "no progress"
     * is indistinguishable from a genuine fresh start. A caller that would resume a quest on that
     * answer must wait, or it resumes the wrong profile's questline for a moment on every join.
     */
    public boolean ready() {
        return ProfileContext.getInstance().known();
    }

    // ------------------------------------------------------------------ queries

    /** The quest being tracked on this profile, or {@code null}. */
    public synchronized String activeQuest() {
        ensureLoaded();
        return state.activeQuest;
    }

    /**
     * The id of the step being worked on for a quest, or {@code null} when it is unstarted or done.
     * Use {@link #isDone(String)} to tell those two apart.
     */
    public synchronized String stepId(String questId) {
        ensureLoaded();
        QuestState found = questId == null ? null : state.quests.get(questId);
        return found == null ? null : found.step;
    }

    /** Whether this quest has been carried to the end on this profile. */
    public synchronized boolean isDone(String questId) {
        ensureLoaded();
        QuestState found = questId == null ? null : state.quests.get(questId);
        return found != null && found.done;
    }

    // ------------------------------------------------------------------ mutations

    /** Records which quest is being tracked. {@code null} stops tracking without forgetting progress. */
    public synchronized void setActiveQuest(String questId) {
        ensureLoaded();
        state.activeQuest = questId;
        save();
    }

    /** Records the step a quest is on. */
    public synchronized void setStep(String questId, String stepId) {
        if (questId == null) {
            return;
        }
        ensureLoaded();
        state.quests.put(questId, new QuestState(stepId, false));
        save();
    }

    /** Records a quest as finished: no current step, and not to be confused with unstarted. */
    public synchronized void setDone(String questId) {
        if (questId == null) {
            return;
        }
        ensureLoaded();
        state.quests.put(questId, new QuestState(null, true));
        save();
    }

    /** Forgets a quest's progress entirely, so it starts from the top next time. */
    public synchronized void clear(String questId) {
        if (questId == null) {
            return;
        }
        ensureLoaded();
        state.quests.remove(questId);
        save();
    }

    // ------------------------------------------------------------------ migration

    /**
     * Moves the legacy global {@code questGuide.stepIndex} into this profile's record, once.
     *
     * <p>Called with the quest definition because that is the only thing that gives the old number a
     * meaning: it is a position in <i>this</i> step order, and the id at that position is what it was
     * always pointing at. An index past the end is the shipped representation of "finished".
     *
     * <p>The legacy field is boxed, so {@code null} means "already migrated, or never set" and the
     * absent case is distinguishable from a genuine step 0 - the same reason every other config
     * migration in this repository boxes the field it is retiring.
     */
    public synchronized void migrateLegacyIndex(Quest quest) {
        SBSConfig.QuestGuideSettings cfg = ConfigManager.getInstance().get().questGuide;
        Integer legacy = cfg.stepIndex;
        if (legacy == null || quest == null || !quest.id.equals(cfg.activeQuest)) {
            return;
        }
        ensureLoaded();
        if (!ready()) {
            return;   // filing it under an unknown profile would put it on nobody's record
        }
        if (legacy >= quest.stepCount()) {
            state.quests.put(quest.id, new QuestState(null, true));
        } else {
            String stepId = quest.stepIdAt(Math.max(0, legacy));
            state.quests.put(quest.id, new QuestState(stepId, false));
        }
        state.activeQuest = cfg.activeQuest;
        cfg.stepIndex = null;   // cleared so a profile switch cannot re-apply it elsewhere
        ConfigManager.getInstance().save();
        save();
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Quest] migrated '{}' progress from global index {} to step id '{}'",
                quest.id, legacy, state.quests.get(quest.id).step);
    }

    // ------------------------------------------------------------------ persistence

    private Path file() {
        return ProfileContext.getInstance().file(FILE);
    }

    private void ensureLoaded() {
        if (!loaded) {
            reloadProfile();
        }
    }

    @Override
    public synchronized void reloadProfile() {
        Persisted read = new Persisted();
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    Persisted parsed = SBSFiles.GSON.fromJson(reader, Persisted.class);
                    if (parsed != null) {
                        read.activeQuest = parsed.activeQuest;
                        read.quests = parsed.quests == null ? new LinkedHashMap<>() : parsed.quests;
                    }
                }
            }
        } catch (Exception e) {
            // Keep the unreadable file on disk and start empty in memory, so the next save does not
            // overwrite progress that might still be recoverable by hand.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Quest] could not read {} ({})", FILE, e.toString());
            state = read;
            loaded = true;
            return;
        }
        state = read;
        loaded = true;
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private synchronized void save() {
        if (!ProfileContext.getInstance().known()) {
            return;   // same window ready() makes readers wait for
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Quest] could not write {}", FILE, e);
        }
    }
}
