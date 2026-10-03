/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import sbs.modid.client.combat.diana.model.BurrowKind;
import sbs.modid.client.combat.diana.model.BurrowRecord;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.PlainText;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat half of burrow bookkeeping: what the player just dug, how far through a chain they are,
 * and when a chain has ended.
 *
 * <h2>Chat is the authority, particles are the sighting</h2>
 *
 * <p>Particles say a burrow exists. Chat says what happened to it, and chat is what the server
 * actually asserts - so where the two disagree about how many times something has been dug, this
 * wins. The dig counter is reconciled rather than incremented for exactly that reason: a client that
 * missed a packet or joined mid-chain has a count that drifted, and a count that drifts is a marker
 * that vanishes early or outstays the hole it was on.
 *
 * <h2>Matching, and what it deliberately does not do</h2>
 *
 * <p>Every pattern here runs over colour-stripped text and describes the shape of a sentence rather
 * than its exact wording, because Hypixel rewords these lines. Nothing is cancelled and nothing is
 * rewritten by this class - it reads, counts and returns. Hiding a line is a separate, opt-in
 * feature with its own switch, so a parser bug here can never swallow a message the player was meant
 * to see.
 *
 * <h2>The line that carries no progress</h2>
 *
 * <p>The chain-finished message has no {@code (n/m)} suffix, so a single pattern written against the
 * progress line misses the end of every chain. That is why there are two patterns rather than one
 * with an optional group: an optional group that never matches is indistinguishable from a working
 * one until somebody notices their chain count only ever goes up.
 */
public final class BurrowChat {

    private static final BurrowChat INSTANCE = new BurrowChat();

    /** "You dug out a Griffin Burrow! (2/4)" - the only line that states the chain's length. */
    private static final Pattern PROGRESS = Pattern.compile(
            "(?i)^.*\\byou\\b.*\\bgriffin burrow\\b.*\\((\\d+)\\s*/\\s*(\\d+)\\)\\s*$");

    /** "You finished the Griffin burrow chain!" - no progress suffix, hence its own pattern. */
    private static final Pattern CHAIN_DONE = Pattern.compile(
            "(?i)^.*\\byou finished the griffin burrow chain\\b.*$");

    /** "You dug out a Griffin Feather!" and its relatives - the reward, with no progress suffix. */
    private static final Pattern REWARD = Pattern.compile(
            "(?i)^.*\\byou (?:just )?dug (?:out )?(.+?)!?\\s*$");

    /** "You dug out a Minos Inquisitor!" - the mob a burrow spawned, named in the sentence. */
    private static final Pattern SPAWN = Pattern.compile(
            "(?i)^.*\\byou dug (?:up |out )?(?:a |an )?(.+?)!\\s*$");

    /** The death line. In the Hub this is a failed chain. */
    private static final Pattern DEATH = Pattern.compile("(?i)^\\s*☠\\s*you\\b.*$");

    /** Reward words that mean the burrow was treasure rather than a mob. */
    private static final String[] TREASURE_WORDS = {
            "feather", "coins", "fragment", "myth the fish", "relic", "shelmet", "remedies",
            "plushie", "urn", "hilt", "dye",
    };

    /** Ritual chatter the player may ask to be rid of once the toolkit is doing the same job. */
    private static final Pattern[] CHATTER = {
            Pattern.compile("(?i)^\\s*follow the arrows to find the .*treasure.*$"),
            Pattern.compile("(?i)^\\s*this ability is on cooldown for .*$"),
            Pattern.compile("(?i)^\\s*warping\\.\\.\\.\\s*$"),
            Pattern.compile("(?i)^\\s*there are blocks in the way!\\s*$"),
    };

    /** The block the player most recently dug, so a chat line with no coordinates can find it. */
    private volatile BlockPos lastDug;

    /**
     * The last {@code (n/m)} progress line and when it arrived. Read by nothing but the guard's error
     * report, where it is the chain index the toolkit was acting on.
     */
    private volatile int lastProgress;
    private volatile int lastProgressOf;
    private volatile long lastProgressAt;

    private BurrowChat() {
    }

    public static BurrowChat getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DianaSettings cfg() {
        return ConfigManager.getInstance().get().diana;
    }

    /** The block the player most recently dug, or {@code null}. */
    public BlockPos lastDug() {
        return lastDug;
    }

    /** Called from the attack mixin when the player left-clicks (digs) a block with a spade. */
    public void onBlockDug(BlockPos pos) {
        lastDug = pos;
    }

    /**
     * One chat line, read.
     *
     * <p>Reads and counts; never cancels and never rewrites. Hiding is a separate, opt-in question
     * asked at the display funnel through {@link #shouldHide}, which is what keeps a parser bug here
     * from ever swallowing a message the player was meant to see.
     */
    public void onChat(String rawText) {
        SBSConfig.DianaSettings cfg = cfg();
        if (!cfg.enabled || rawText == null || rawText.isEmpty()) {
            return;
        }
        String text = PlainText.strip(rawText).trim();
        if (text.isEmpty()) {
            return;
        }

        Matcher progress = PROGRESS.matcher(text);
        if (progress.matches()) {
            onProgress(parseInt(progress.group(1)), parseInt(progress.group(2)));
            return;
        }
        if (CHAIN_DONE.matcher(text).matches()) {
            onChainFinished();
            return;
        }
        if (DEATH.matcher(text).matches() && SkyBlockLocation.onIsland("Hub")) {
            onDeath();
            return;
        }
        if (text.toLowerCase(Locale.ROOT).contains("dug")) {
            onDugSomething(text);
        }
    }

    /**
     * Whether this line is ritual chatter the player asked to be rid of.
     *
     * <p>Four exact shapes and nothing else. A line that merely mentions the ritual, or one this
     * build half-recognises, is never hidden: swallowing a message the player was supposed to see is
     * a bug, and so is rewriting one that was not fully matched.
     */
    public boolean shouldHide(String rawText) {
        SBSConfig.DianaSettings cfg = cfg();
        if (!cfg.enabled || !cfg.hideRitualChatter || rawText == null || rawText.isEmpty()) {
            return false;
        }
        String text = PlainText.strip(rawText).trim();
        for (Pattern pattern : CHATTER) {
            if (pattern.matcher(text).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A burrow in a chain was dug, and the line said how far through it is.
     *
     * <p>The first of a chain starts one, the last ends one and everything between refreshes the
     * oldest. The chain length is also the one place the game ever states it, so it is learned here
     * rather than looked up - which is what makes an unrecorded spade tier a temporary gap instead of
     * a permanent one.
     */
    private void onProgress(int current, int total) {
        if (current <= 0 || total <= 0) {
            return;
        }
        lastProgress = current;
        lastProgressOf = total;
        lastProgressAt = System.currentTimeMillis();
        ChainTracker tracker = ChainTracker.getInstance();
        if (current == 1) {
            tracker.started();
        } else if (current < total) {
            tracker.advanced();
        }
        // The last burrow of a chain is not ended here: the chain-finished line is what says so, and
        // ending on both would pop two chains for one chain's worth of digging.

        DianaTracker.getInstance().onBurrowDug();
        countDig(null);
        DianaDebug.getInstance().note("burrow " + current + "/" + total);
    }

    private void onChainFinished() {
        ChainTracker.getInstance().ended();
        countDig(null);
        DianaPrompts.chainEnded();
    }

    /**
     * The player died in the Hub, which fails a chain.
     *
     * <p>The mob burrow they died to is also gone as far as they are concerned - the creature is
     * still there but the chain it belonged to is not - so its marker goes with the chain rather
     * than standing over a hole that leads nowhere.
     */
    private void onDeath() {
        ChainTracker.getInstance().ended();
        BlockPos pos = lastDug;
        BurrowRecord record = BurrowStore.getInstance().peek(pos);
        if (record != null && record.kind == BurrowKind.MOB && record.timesDug >= 1) {
            BurrowStore.getInstance().remove(pos);
            DianaDebug.getInstance().note("mob burrow dropped: died to it");
        }
    }

    /**
     * A line naming something dug up with no chain progress on it - either a reward or a creature.
     *
     * <p>The reward classifies the burrow after the fact, which matters for a burrow the player dug
     * off a guess before any particle had said what it was. The word list is what separates the two:
     * anything naming loot is treasure, and everything else is assumed to be a creature, because a
     * creature's name is whatever Hypixel called it and cannot be enumerated.
     */
    private void onDugSomething(String text) {
        Matcher reward = REWARD.matcher(text);
        if (!reward.matches()) {
            Matcher spawn = SPAWN.matcher(text);
            if (spawn.matches()) {
                DianaTracker.getInstance().onCreatureSpawned(spawn.group(1));
            } else {
                DianaDebug.getInstance().onUnmatchedChat(text);
            }
            return;
        }
        String what = reward.group(1).toLowerCase(Locale.ROOT);
        boolean treasure = false;
        for (String word : TREASURE_WORDS) {
            if (what.contains(word)) {
                treasure = true;
                break;
            }
        }
        countDig(treasure ? BurrowKind.TREASURE : BurrowKind.MOB);
        if (treasure) {
            DianaTracker.getInstance().onTreasure(reward.group(1));
        } else {
            DianaTracker.getInstance().onCreatureSpawned(reward.group(1));
        }
    }

    /**
     * Counts a dig against the burrow the player last touched, and retires its marker when it is
     * finished with.
     *
     * <p>A guess the player dug before the particles caught up is promoted here rather than being
     * left as a guess: the chat line is proof there was a burrow, and the reward is proof of what
     * kind. That is the one path by which a burrow becomes known without a single particle.
     *
     * @param kindFromChat the kind the reward line implies, or {@code null} when it said nothing
     */
    private void countDig(BurrowKind kindFromChat) {
        BlockPos pos = lastDug;
        if (pos == null) {
            return;
        }
        BurrowStore store = BurrowStore.getInstance();
        BurrowRecord record = store.peek(pos);
        if (record == null) {
            int carried = ArrowGuess.getInstance().onBlockResolved(pos);
            carried = Math.max(carried, SpadeGuess.getInstance().onBlockResolved(pos));
            if (kindFromChat == null && carried == 0) {
                return;
            }
            record = store.recordAt(pos);
            record.timesDug = carried;
        }
        if (kindFromChat != null && record.kind == null) {
            record.kind = kindFromChat;
        }
        record.timesDug++;
        record.touch();
        if (record.finished()) {
            store.remove(pos);
        }
    }

    /** World change, server hop, island change, or the player asking. */
    public void reset() {
        lastDug = null;
        lastProgress = 0;
        lastProgressOf = 0;
        lastProgressAt = 0L;
    }

    /** The last dug block and the last chain position, for the guard's error report. */
    public JsonObject snapshot() {
        JsonObject out = new JsonObject();
        out.addProperty("lastDug", DianaSnapshot.pos(lastDug));
        out.addProperty("lastProgress", lastProgress);
        out.addProperty("lastProgressOf", lastProgressOf);
        out.addProperty("lastProgressAt", lastProgressAt);
        return out;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
