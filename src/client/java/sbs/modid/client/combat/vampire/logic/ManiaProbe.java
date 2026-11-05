/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.vampire.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.carry.model.SlayerBoss;
import sbs.modid.client.combat.slayer.logic.SlayerTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.hideplayers.model.SkyBlockLevelText;
import sbs.modid.client.helper.timers.ServerTickClock;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Logs what the Riftstalker's Mania phase looks like from the client, so Mania-block marking can be
 * built on measured facts instead of guesses: which block the server places, where, for how long,
 * and whether a chat line or nametag announces the phase.
 *
 * <p>Runs only with {@code vampire.maniaProbe} on (default off) and only while your own Bloodfiend
 * is up. It only writes to the log - nothing is drawn, said or sent. Every line is tagged
 * {@code [SBS][Riftstalker]} and stamped with the server tick:
 *
 * <pre>
 * [SBS][Riftstalker] t=812340.0 fight start (player 12,70,-40)
 * [SBS][Riftstalker] t=812401.0 set minecraft:red_stained_glass at 13,69,-41 (was minecraft:stone, d=1.4)
 * [SBS][Riftstalker] t=812461.0 removed minecraft:red_stained_glass at 13,69,-41 (d=2.0)
 * [SBS][Riftstalker] t=812420.0 chat: '...'
 * [SBS][Riftstalker] t=812420.0 stand: '...' d=3.1
 * [SBS][Riftstalker] t=812421.0 health 1830.0 -> 1418.0 (-412.0) at 13,70,-41 on minecraft:red_stained_glass
 * </pre>
 *
 * <p>Fed from {@code BlockUpdateMixin} (HEAD of {@code setServerVerifiedBlockState}, main thread, old
 * state still in the level). Only block-type transitions are logged; a property-only change
 * (rotation, waterlogging) is not a placement. A swap between two solid blocks is a {@code set}
 * naming the old block - which side is "the Mania block" is exactly what the log is for.
 */
public final class ManiaProbe {

    static final String TAG = "[SBS][Riftstalker] ";
    /** Distance from the player; blocks further out are not logged. */
    static final double RADIUS = 12.0;
    /** Per fight; keeps the log readable and a stuck fight from flooding it. */
    static final int LINE_BUDGET = 500;
    /** Nametags are sampled this often; a phase name does not change every tick. */
    private static final int STAND_PERIOD_TICKS = 10;
    private static final int MAX_STAND_NAMES = 200;

    private static final ManiaProbe INSTANCE = new ManiaProbe();

    /** Set per tick; the block and chat hooks return on this one read outside a fight. */
    private volatile boolean armed;

    private final LineBudget budget = new LineBudget(LINE_BUDGET);
    private final Set<String> seenStandNames = new HashSet<>();
    private float lastHealth = -1.0F;
    private int ticks;

    private ManiaProbe() {}

    public static ManiaProbe getInstance() {
        return INSTANCE;
    }

    public boolean armed() {
        return armed;
    }

    // ---- hooks ---------------------------------------------------------------------------------

    /** Per Rift client tick: opens/closes the fight window, samples health and nametags. */
    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        boolean nowArmed = player != null && mc.level != null
                && ConfigManager.getInstance().get().vampire.maniaProbe
                && SlayerTracker.getInstance().questBoss() == SlayerBoss.BLOODFIEND
                && SlayerTracker.getInstance().bossUp();
        if (nowArmed != armed) {
            armed = nowArmed;
            if (nowArmed) {
                budget.reset();
                seenStandNames.clear();
                lastHealth = -1.0F;
                ticks = 0;
                log("fight start (player " + pos(player.blockPosition()) + ")");
            } else {
                emit("fight end (" + budget.used() + " lines" + (budget.exhausted() ? ", budget hit" : "") + ")");
            }
        }
        if (!armed) {
            return;
        }
        float health = player.getHealth() + player.getAbsorptionAmount();
        if (lastHealth >= 0.0F && health < lastHealth - 0.01F) {
            BlockPos feet = player.blockPosition();
            log(String.format(Locale.ROOT, "health %.1f -> %.1f (-%.1f) at %s on %s", lastHealth, health,
                    lastHealth - health, pos(feet), id(mc.level.getBlockState(feet.below()))));
        }
        lastHealth = health;
        if (++ticks % STAND_PERIOD_TICKS == 0) {
            for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class,
                    player.getBoundingBox().inflate(RADIUS), ArmorStand::hasCustomName)) {
                String name = stand.getCustomName().getString();
                if (seenStandNames.size() < MAX_STAND_NAMES && seenStandNames.add(name)) {
                    log(String.format(Locale.ROOT, "stand: '%s' d=%.1f", name, stand.distanceTo(player)));
                }
            }
        }
    }

    /** From {@code BlockUpdateMixin}; {@code oldState} is still what the level holds. */
    public void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        if (!armed || oldState == newState) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        double d = Math.sqrt(player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        if (d > RADIUS) {
            return;
        }
        log(describe(id(oldState), id(newState), oldState.isAir(), newState.isAir(), pos(pos), d));
    }

    /** From the chat dispatch, prefiltered on {@link #armed()}. */
    public void onChat(String plain) {
        if (armed && isSystemLine(plain)) {
            log("chat: '" + plain.trim() + "'");
        }
    }

    /** Channel prefixes of lines other players wrote; a phase cue never arrives on one of these. */
    private static final String[] PLAYER_CHANNELS = {
        "Party >", "Guild >", "Officer >", "Co-op >", "From ", "To "
    };

    /**
     * Whether a chat line is from the server or a boss rather than a player: not blank, not on a
     * party / guild / private / co-op channel, and not a public "[level] Name: msg" line (the
     * character scan in {@link SkyBlockLevelText#publicSpeaker}).
     */
    static boolean isSystemLine(String plain) {
        if (plain == null || plain.isBlank()) {
            return false;
        }
        String text = SkyBlockLevelText.stripColours(plain).stripLeading();
        for (String channel : PLAYER_CHANNELS) {
            if (text.startsWith(channel)) {
                return false;
            }
        }
        return SkyBlockLevelText.publicSpeaker(text) == null;
    }

    // ---- formatting ----------------------------------------------------------------------------

    /** One block transition as a log line, or {@code null} when the block type did not change. */
    static String describe(String oldId, String newId, boolean oldAir, boolean newAir, String at, double d) {
        if (oldId.equals(newId)) {
            return null;
        }
        if (newAir) {
            return String.format(Locale.ROOT, "removed %s at %s (d=%.1f)", oldId, at, d);
        }
        return String.format(Locale.ROOT, "set %s at %s (was %s, d=%.1f)", newId, at,
                oldAir ? "air" : oldId, d);
    }

    private void log(String body) {
        if (body == null) {
            return;
        }
        LineBudget.Verdict verdict = budget.take();
        if (verdict == LineBudget.Verdict.LOG) {
            emit(body);
        } else if (verdict == LineBudget.Verdict.JUST_EXHAUSTED) {
            emit("line budget (" + LINE_BUDGET + ") reached, rest of fight not logged");
        }
    }

    private static void emit(String body) {
        SkyblockSimplifiedSBS.LOGGER.info(TAG + "t=" + String.format(Locale.ROOT, "%.1f",
                ServerTickClock.getInstance().now()) + " " + body);
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String pos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** Lines left in this fight; the first refused line is reported once, the rest are silent. */
    static final class LineBudget {
        enum Verdict { LOG, JUST_EXHAUSTED, SILENT }

        private final int max;
        private int used;
        private boolean noted;

        LineBudget(int max) {
            this.max = max;
        }

        Verdict take() {
            if (used < max) {
                used++;
                return Verdict.LOG;
            }
            if (!noted) {
                noted = true;
                return Verdict.JUST_EXHAUSTED;
            }
            return Verdict.SILENT;
        }

        int used() {
            return used;
        }

        boolean exhausted() {
            return used >= max;
        }

        void reset() {
            used = 0;
            noted = false;
        }
    }
}
