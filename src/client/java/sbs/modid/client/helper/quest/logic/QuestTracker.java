/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.quest.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.quest.model.Quest;
import sbs.modid.client.ui.hud.logic.HypixelHudState;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.Locale;

/**
 * The active quest and how far along it is – <b>all client-side</b>.
 *
 * <p>The definition is read out of the jar ({@link QuestDatabase}); everything that watches the
 * world happens here, so a quest costs nothing beyond one resource read at startup.
 *
 * <p><b>Progress comes from the chat.</b> Hypixel narrates every step ({@code [NPC] Romero: Could
 * you find some gold for me, please?}), so the dialogue is the one signal that is both unambiguous
 * and impossible to desync: it fires exactly when the game itself considers the step done. Each step
 * carries the lines that complete it and they are matched as substrings, so rank colours, the
 * {@code [NPC]} prefix and the player's own name in a line need no special handling.
 *
 * <p><b>Item counts are live.</b> The required item is counted straight out of the inventory each
 * frame the overlay draws, so the checkbox ticks the moment the item is picked up.
 *
 * <p>The step index is persisted, so a quest survives a restart mid-run.
 */
public final class QuestTracker {

    private static final QuestTracker INSTANCE = new QuestTracker();

    /** Vanilla ids do not carry SkyBlock ids; those match on the registry path instead. */
    private static final String VANILLA_PREFIX = "minecraft:";

    /** Hypixel prefixes every NPC dialogue line with this. */
    private static final String NPC_PREFIX = "[NPC] ";
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private volatile Quest quest;
    private volatile String error;

    /** The active id whose resume already failed, so the ticker stops retrying it. */
    private volatile String resumeFailedId;

    private QuestTracker() {
    }

    public static QuestTracker getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** The active quest, or {@code null} when none is running. */
    public Quest quest() {
        return quest;
    }

    /** The last load error (e.g. "Unknown quest"), or {@code null}. */
    public String error() {
        return error;
    }

    /**
     * The position of the step being worked on, <b>derived</b> from the stored step id.
     *
     * <p>Every caller above this line still wants a number - "step 7/30", a progress bar, the cost
     * of what is left. What changed is that the number is no longer what gets stored: the id is, and
     * the position is recomputed against the current step order. Insert a step and this number moves
     * while the player stays exactly where they were.
     *
     * <p>Returns {@link Quest#stepCount()} when the quest is finished, which is what "past the last
     * step" meant before and what {@link #finished()} and the bar both read.
     */
    public int stepIndex() {
        Quest active = quest;
        if (active == null) {
            return 0;
        }
        QuestProgressStore store = QuestProgressStore.getInstance();
        if (store.isDone(active.id)) {
            return active.stepCount();
        }
        String stepId = store.stepId(active.id);
        if (stepId == null) {
            return 0;   // unstarted
        }
        int index = active.indexOfStep(stepId);
        if (index >= 0) {
            return index;
        }
        // The dataset no longer contains the step this profile was on. Treated as unstarted rather
        // than as finished: a corrected dataset may legitimately drop a step, and starting over is
        // recoverable where a false "complete" throws the progress away.
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Quest] '{}' stored step '{}' is not in the current "
                + "dataset - restarting this quest from the first step", active.id, stepId);
        store.setStep(active.id, active.firstStepId());
        return 0;
    }

    /** Moves the stored position by id, or marks the quest done when the index is past the end. */
    private void setStepIndex(int index) {
        Quest active = quest;
        if (active == null) {
            return;
        }
        QuestProgressStore store = QuestProgressStore.getInstance();
        if (index >= active.stepCount()) {
            store.setDone(active.id);
        } else {
            store.setStep(active.id, active.stepIdAt(Math.max(0, index)));
        }
    }

    /** The step being worked on, or {@code null} when the quest is finished / not running. */
    public Quest.QuestStep currentStep() {
        Quest active = quest;
        return active == null ? null : active.step(stepIndex());
    }

    /** Whether every step is done. */
    public boolean finished() {
        Quest active = quest;
        return active != null && stepIndex() >= active.stepCount();
    }

    /** Progress in {@code [0,1]} for the bar. */
    public double progress() {
        Quest active = quest;
        if (active == null || active.stepCount() == 0) {
            return 0;
        }
        return Math.min(1.0, stepIndex() / (double) active.stepCount());
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts (or resumes) a quest: looks its definition up in the bundled database, then tracks it.
     *
     * <p>Synchronous - the definition is already in memory, so there is nothing to wait for. The
     * {@code onDone} callback is kept so callers can refresh their widgets in one place.
     */
    public void start(String questId, Runnable onDone) {
        error = null;
        Quest found = QuestDatabase.byId(questId);
        if (found == null) {
            error = "Unknown quest";
            quest = null;
        } else {
            quest = found;
            resumeFailedId = null;
            // Before anything reads the progress: a pre-per-profile install still has its position
            // in the old global field, and the definition just looked up is what gives that number
            // a meaning. A no-op once it has run, and on a fresh install.
            QuestProgressStore.getInstance().migrateLegacyIndex(found);
            SBSConfigQuest cfg = new SBSConfigQuest();
            if (!questId.equals(cfg.activeId())) {
                cfg.setActive(questId, 0); // a different quest starts from the top
            }
        }
        if (onDone != null) {
            onDone.run();
        }
    }

    /**
     * Stops tracking and forgets the progress.
     *
     * <p>Also drops the quest waypoint: it is the pathfinder's only reason to be running, so leaving
     * it behind would keep routing the player to an objective they just abandoned.
     */
    public void stop() {
        quest = null;
        error = null;
        resumeFailedId = null;
        new SBSConfigQuest().setActive(null, 0);
        QuestWaypoints.clear();
    }

    /**
     * Re-attaches the active quest on world join, so a restart resumes where it left off.
     *
     * <p>Called every tick while no quest is attached, so an id that names nothing is remembered:
     * re-running the lookup is cheap, but it would otherwise re-raise the same error forever.
     */
    public void resumeIfActive() {
        String active = new SBSConfigQuest().activeId();
        if (active == null || active.isBlank() || quest != null || active.equals(resumeFailedId)) {
            return;
        }
        start(active, null);
        if (quest == null) {
            resumeFailedId = active;
        }
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    /**
     * Feeds one chat line in. Advances at most one step per line: Hypixel sends the dialogue as a
     * burst of separate lines, and two steps whose triggers both appear in that burst must not
     * collapse into one jump.
     */
    public void onChatLine(String line) {
        Quest active = quest;
        if (active == null || line == null || finished()) {
            return;
        }
        String clean = stripCodes(line).trim();
        if (!isNpcLine(clean)) {
            return;
        }
        Quest.QuestStep step = currentStep();
        if (step != null && step.advancedBy(clean)) {
            advance();
        }
    }

    /**
     * Whether a line is actually NPC dialogue.
     *
     * <p>Triggers are matched as substrings, which on its own would let <b>any</b> player advance
     * someone else's quest just by typing a trigger line into party chat. Requiring the
     * {@code [NPC] } prefix at the <i>start</i> closes that: a quoted line renders as
     * {@code Party > Someone: [NPC] ...} and never leads with it.
     */
    private static boolean isNpcLine(String clean) {
        return clean.startsWith(NPC_PREFIX);
    }

    /** Drops legacy §-colour codes, which Hypixel sometimes leaves in the literal text. */
    private static String stripCodes(String text) {
        return text.replaceAll(SECTION_SIGN + ".", "");
    }

    private void advance() {
        int next = stepIndex() + 1;
        setStepIndex(next);
        Quest active = quest;
        if (active != null && next >= active.stepCount()) {
            announce("§a[SBS] Quest complete: §f" + active.name);
        }
    }

    /** Manual override for a step the chat check missed (dialogue skipped, line already seen). */
    public void skipStep() {
        if (quest != null && !finished()) {
            advance();
        }
    }

    /** Steps back one, for when a step advanced by accident. */
    public void previousStep() {
        if (quest != null && stepIndex() > 0) {
            setStepIndex(stepIndex() - 1);
        }
    }

    private static void announce(String text) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }

    // ------------------------------------------------------------------
    // Live checks (client-side scanners)
    // ------------------------------------------------------------------

    /**
     * How many of a step's item the player is carrying. Counts the whole inventory, so it does not
     * matter which slot the item ended up in.
     */
    public static int countItem(Quest.QuestItem item) {
        Player player = Minecraft.getInstance().player;
        if (player == null || item == null || item.id == null) {
            return 0;
        }
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matches(stack, item.id)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Whether a stack is the wanted item, by SkyBlock id first and vanilla registry path second. */
    private static boolean matches(ItemStack stack, String wantedId) {
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        String skyblockId = extra.getStringOr("id", "");
        if (!skyblockId.isEmpty()) {
            return skyblockId.equalsIgnoreCase(wantedId);
        }
        // Plain vanilla drops (Poppy, Emerald) carry no SkyBlock id at all.
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).getPath();
        return path.equalsIgnoreCase(wantedId)
                || (VANILLA_PREFIX + path).equalsIgnoreCase(wantedId);
    }

    /**
     * Whether a step's non-item requirement is met. Intelligence is read from the live mana value
     * the action bar already publishes ({@link HypixelHudState}) – max mana is 100 + intelligence.
     * Anything unrecognised counts as met, so an unknown requirement can never wedge a quest.
     */
    public static boolean requirementMet(Quest.QuestRequirement requirement) {
        if (requirement == null || requirement.type == null) {
            return true;
        }
        if ("intelligence".equalsIgnoreCase(requirement.type)) {
            HypixelHudState state = HypixelHudState.getInstance();
            return state.hasMana() && state.manaMax() - 100 >= requirement.amount;
        }
        if ("armor".equalsIgnoreCase(requirement.type)) {
            return wearing(requirement.name);
        }
        return true;
    }

    /** Whether any worn armour piece's name contains {@code name} (e.g. "Tuxedo"). */
    private static boolean wearing(String name) {
        Player player = Minecraft.getInstance().player;
        if (player == null || name == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        String needle = name.toLowerCase(Locale.ROOT);
        // Armour occupies player-inventory indices 36..39.
        for (int i = 36; i <= 39 && i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()
                    && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the current step's collectible / requirement is satisfied right now. */
    public static boolean stepSatisfied(Quest.QuestStep step) {
        if (step == null) {
            return false;
        }
        if (step.item != null) {
            return countItem(step.item) >= step.item.amount;
        }
        return step.requirement != null && requirementMet(step.requirement);
    }

    /**
     * Tiny helper so the progress access stays readable above.
     *
     * <p>Which quest is active is profile state like any other - an Ironman tracking Romeo and
     * Juliette must not make the main track it too - so it moved out of the global config with the
     * step. {@code questGuide.activeQuest} is still written for one release as the migration's only
     * way of knowing which quest an orphaned legacy index belonged to.
     */
    private static final class SBSConfigQuest {
        String activeId() {
            return QuestProgressStore.getInstance().activeQuest();
        }

        void setActive(String id, int index) {
            QuestProgressStore store = QuestProgressStore.getInstance();
            store.setActiveQuest(id);
            if (id == null) {
                ConfigManager.getInstance().get().questGuide.activeQuest = null;
                ConfigManager.getInstance().save();
                return;
            }
            if (index <= 0 && store.stepId(id) == null && !store.isDone(id)) {
                Quest definition = QuestDatabase.byId(id);
                if (definition != null) {
                    store.setStep(id, definition.firstStepId());
                }
            }
            ConfigManager.getInstance().get().questGuide.activeQuest = id;
            ConfigManager.getInstance().save();
        }
    }
}
