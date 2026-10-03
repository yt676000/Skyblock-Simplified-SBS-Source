/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.events.DungeonAlert;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Calls out the first Prince killed in a run. The Prince is worth <b>+1 run score</b> once its shard
 * has been unlocked, and that point is easy to lose: the mob looks like any other, and nobody counts
 * a kill they did not notice. One line and one flash the moment it goes down is the whole feature -
 * it announces, it never kills, and nothing is sent to anyone else.
 *
 * <p><b>Once per run.</b> The bonus is granted for the first Prince, so the second one is not news.
 * The guard resets when the run is left, the same way {@link MimicDetector} handles its announce.
 *
 * <p><b>Two signals, both gated on being in a dungeon.</b>
 * <ul>
 *   <li><b>The mob dying</b> - the reliable one. A Prince nametag near you is tracked by entity id;
 *       when that entity is gone or dying while its tag was still being read a moment ago, it died
 *       here. That "still being read a moment ago" window is what keeps a Prince you simply walked
 *       away from (its tag goes stale long before the entity does) from counting as a kill - the
 *       same rule the carry counter uses for slayer bosses.</li>
 *   <li><b>The shard line</b> - a backstop for the kill you were not next to. Hypixel prints the
 *       shard pickup to chat, so a Prince line there means one went down on your team. It matches
 *       the pickup wording generically rather than a memorised sentence, and the once-per-run guard
 *       means whichever signal lands first is the one that speaks.</li>
 * </ul>
 */
public final class PrinceTracker {

    private static final PrinceTracker INSTANCE = new PrinceTracker();

    /** How far Prince nametags are collected from - well past melee, short of the whole floor. */
    private static final double SCAN_RADIUS = 40.0;

    /** A tag read this recently, on an entity now gone: that is a kill, not a walk-away. */
    private static final long DEATH_WINDOW_MS = 1_500L;

    /** A tracked Prince nobody has seen for this long is dropped without counting. */
    private static final long STALE_MS = 20_000L;

    /** Every fourth tick: a Prince dies in front of you, and the death window is far wider. */
    private static final int SCAN_INTERVAL_TICKS = 4;

    /** The call-out colour: the Prince's own pink, and not the red the blood room already uses. */
    private static final int ALERT_COLOR = 0xFFFF7BEF;

    /** The mob name as it reads in the nametag once the level tag and the glyphs are stripped. */
    private static final String PRINCE = "prince";

    /** The one other "Prince" in SkyBlock: a sea creature, and never this. */
    private static final String FROG_PRINCE = "frog prince";

    /** Last time each tracked Prince's nametag was read, by entity id. */
    private final Map<Integer, Long> lastSeen = new HashMap<>();

    private boolean killedThisRun;
    private boolean wasInDungeon;
    private int tickCounter;

    private PrinceTracker() {
        // The shard pickup: Hypixel's "SHARD!" banner with Prince named on the same line. Loose about
        // the sentence between them (that wording gets reworded) but strict about the banner itself:
        // the bang and the capitals are what keep a party member typing "prince shard" out of it.
        // "Frog Prince" is a sea creature, not this mob, so it is refused outright - and nothing
        // fires outside a dungeon anyway.
        ChatPatternRegistry.getInstance().register(
                "SHARD!.*(?i:(?<!frog )\\bprince\\b)",
                matcher -> onPrinceKilled(), "prince: shard line");
    }

    public static PrinceTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** Whether a Prince has already gone down this run - read by the score card for its +1. */
    public boolean killed() {
        return killedThisRun;
    }

    /**
     * Called every client tick: watches the Prince nametags near the player while a run is going,
     * and forgets the run on the way out.
     */
    public void onClientTick() {
        boolean inDungeon = DungeonStateManager.getInstance().inDungeon();
        if (!inDungeon) {
            if (wasInDungeon) {
                reset();
            }
            wasInDungeon = false;
            return;
        }
        wasInDungeon = true;
        // Nothing left to detect once one is down: the bonus is for the first, and the score card
        // already has its answer.
        if (killedThisRun || !cfg().princeAlert) {
            return;
        }
        if (++tickCounter % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level != null && player != null) {
            scan(level, player);
        }
    }

    private void reset() {
        lastSeen.clear();
        killedThisRun = false;
    }

    /**
     * One pass: re-stamp every Prince whose tag is being read right now, then judge the ones that
     * dropped out of that set. Same stand→mob association the Mob Highlight uses, so a Prince whose
     * nametag floats above its body is tracked as the body, not as the stand.
     */
    private void scan(ClientLevel level, LocalPlayer player) {
        long now = System.currentTimeMillis();
        Set<Integer> present = new HashSet<>();

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living instanceof Player
                    || !living.hasCustomName()
                    || living.distanceToSqr(player) > SCAN_RADIUS * SCAN_RADIUS) {
                continue;
            }
            Component custom = living.getCustomName();
            String name = MobHighlightTracker.mobNameInNametag(custom == null ? "" : custom.getString());
            if (!isPrince(name)) {
                continue;
            }
            LivingEntity target = living instanceof ArmorStand stand
                    ? MobHighlightTracker.mobBelow(level, stand)
                    : living;
            if (target == null || !target.isAlive()) {
                continue;
            }
            present.add(target.getId());
            lastSeen.put(target.getId(), now);
        }

        Iterator<Map.Entry<Integer, Long>> it = lastSeen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> tracked = it.next();
            if (present.contains(tracked.getKey())) {
                continue;
            }
            Entity entity = level.getEntity(tracked.getKey());
            boolean gone = !(entity instanceof LivingEntity living) || !living.isAlive()
                    || living.isDeadOrDying();
            if (!gone) {
                if (now - tracked.getValue() > STALE_MS) {
                    it.remove(); // alive, but its tag stopped being read long ago - stop watching it
                }
                continue;
            }
            it.remove();
            // Gone while its tag was still being read: it died here. A Prince that only left render
            // distance stops being parsed first, so its stamp is already well past the window.
            if (now - tracked.getValue() <= DEATH_WINDOW_MS) {
                onPrinceKilled();
                return;
            }
        }
    }

    /**
     * Whether an extracted nametag name is the Prince. The name has to <b>end</b> in "Prince" rather
     * than equal it, so the buffed variants Hypixel prefixes ("Corrupted Prince") still count - they
     * are the same mob and drop the same shard. "Frog Prince" is the one name that ends that way and
     * is a different mob entirely.
     */
    private static boolean isPrince(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return (lower.equals(PRINCE) || lower.endsWith(" " + PRINCE)) && !lower.endsWith(FROG_PRINCE);
    }

    /** The call-out: once per run, flash + ping + one line in your own chat. Sends nothing out. */
    private void onPrinceKilled() {
        if (killedThisRun || !cfg().princeAlert || !DungeonStateManager.getInstance().inDungeon()) {
            return;
        }
        killedThisRun = true;
        DungeonAlert.getInstance().trigger("PRINCE KILLED", ALERT_COLOR, true, 1.9f);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(
                    "§8[§bSBS§8]§r §dPrince killed §7- §a+1 §7score"));
        }
    }
}
