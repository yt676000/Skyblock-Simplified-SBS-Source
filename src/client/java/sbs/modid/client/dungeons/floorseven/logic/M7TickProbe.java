/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.sound.SoundListeners;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.helper.timers.ServerTickClock;
import sbs.modid.client.ui.hud.logic.ServerStatsTracker;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Logs what happens in the F7 / M7 Storm and Goldor phases on the server-tick clock, so the tick
 * cycles {@link M7TickTimers} predicts can be measured from ordinary runs instead of guessed.
 *
 * <p>Runs while {@link #TIMING_VERIFIED} is false, only on floor 7 and only while
 * {@link FloorSevenPhaseTimer} says the fight is in Storm or Goldor. It only writes to the log:
 * nothing is drawn, said or sent. Every line carries the server tick ({@link ServerTickClock}) and
 * the measured tick rate, so a period can be read off the tick column directly:
 *
 * <pre>
 * [SBS][TickTimers] t=8123401.0 phase STORM (tps 19.8)
 * [SBS][TickTimers] t=8123402.0 boss: 'Storm: ...' (tps 19.8)
 * [SBS][TickTimers] t=8123455.0 chat: storm enraged (tps 19.8)
 * [SBS][TickTimers] t=8123455.0 blocks: 12 changes, first minecraft:air -> minecraft:... at 52,169,41 (tps 19.8)
 * [SBS][TickTimers] t=8123455.0 sound: minecraft:block.anvil.land x2 at 52,169,41 (tps 19.8)
 * [SBS][TickTimers] t=8123460.0 self damage: 412.0 (health 1830.0 -> 1418.0) source: generic (tps 19.8)
 * </pre>
 *
 * <p>Block changes and sounds are summed per client tick into one line each, because a pad crush
 * is many blocks at once and one line per block would bury the rest of the log. Self damage is the
 * drop in health plus absorption between two ticks; the source guess is the client's last damage
 * event when one arrived within the last two ticks, otherwise "no damage event".
 */
public final class M7TickProbe {

    /** Flip once the pad and Goldor periods and anchors are measured; the probe then stands down. */
    public static final boolean TIMING_VERIFIED = false;

    static final String TAG = "[SBS][TickTimers] ";

    /** CONFIRMED chat line in the Storm phase. */
    public static final String STORM_ENRAGED = "Storm is enraged!";
    /** CONFIRMED chat line when an ability is used while standing on a launch pad. */
    public static final String LAUNCH_PAD_ABILITY = "^You can't use abilities while on a launch pad!";

    private static final M7TickProbe INSTANCE = new M7TickProbe();

    /** Set per tick; the block and sound hooks return on this one read outside the window. */
    private volatile boolean armed;

    private FloorSevenPhaseTimer.Phase loggedPhase;
    private float lastHealth = -1.0F;

    // Per-tick aggregation; flushed at the end of each client tick. Main thread only.
    private int blockChanges;
    private String firstBlock;
    private final Map<String, int[]> sounds = new LinkedHashMap<>();
    private final Map<String, String> soundPos = new LinkedHashMap<>();
    /** One stable listener reference, so remove() finds the instance add() registered. */
    private final SoundListeners.Listener listener = this::onSound;

    private M7TickProbe() {
        ChatPatternRegistry.getInstance().register("^\\[BOSS\\]\\s+(.*)$",
                matcher -> chat("boss: '" + matcher.group(1).trim() + "'"), "m7 tick probe: boss line");
        // CONFIRMED wording (seen in game); what each one means for the pad cycle is the question.
        ChatPatternRegistry.getInstance().register(STORM_ENRAGED,
                matcher -> chat("chat: storm enraged"), "m7 tick probe: storm enraged");
        ChatPatternRegistry.getInstance().register(LAUNCH_PAD_ABILITY,
                matcher -> chat("chat: no abilities on launch pad"), "m7 tick probe: launch pad");
    }

    public static M7TickProbe getInstance() {
        return INSTANCE;
    }

    /** Whether the window is open: floor 7, Storm or Goldor, timings not yet verified. */
    public boolean armed() {
        return armed;
    }

    // ---- hooks ---------------------------------------------------------------------------------

    /** Per Catacombs client tick: opens/closes the window, samples health, flushes the aggregates. */
    public void onClientTick() {
        boolean nowArmed = !TIMING_VERIFIED && inWindow();
        if (nowArmed != armed) {
            armed = nowArmed;
            if (nowArmed) {
                SoundListeners.add(listener);
            } else {
                SoundListeners.remove(listener);
                lastHealth = -1.0F;
            }
        }
        FloorSevenPhaseTimer.Phase phase = FloorSevenPhaseTimer.getInstance().current();
        if (armed && phase != loggedPhase) {
            log("phase " + phase);
        }
        loggedPhase = armed ? phase : null;
        if (!armed) {
            clearAggregates();
            return;
        }
        sampleHealth();
        flush();
    }

    /** From {@code BlockUpdateMixin}: one server-sent block change, before it is applied. */
    public void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        if (!armed) {
            return;
        }
        if (blockChanges++ == 0) {
            firstBlock = BuiltInRegistries.BLOCK.getKey(oldState.getBlock()) + " -> "
                    + BuiltInRegistries.BLOCK.getKey(newState.getBlock())
                    + " at " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }

    public void onWorldChange() {
        armed = false;
        SoundListeners.remove(listener);
        clearAggregates();
        lastHealth = -1.0F;
        loggedPhase = null;
    }

    // ---- internals -----------------------------------------------------------------------------

    private void onSound(SoundInstance instance) {
        if (!armed) {
            return;
        }
        String id = instance.getIdentifier().toString();
        int[] count = sounds.get(id);
        if (count == null) {
            sounds.put(id, new int[] {1});
            soundPos.put(id, (int) Math.floor(instance.getX()) + "," + (int) Math.floor(instance.getY())
                    + "," + (int) Math.floor(instance.getZ()));
        } else {
            count[0]++;
        }
    }

    private void chat(String event) {
        if (armed) {
            log(event);
        }
    }

    private void sampleHealth() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            lastHealth = -1.0F;
            return;
        }
        float health = player.getHealth() + player.getAbsorptionAmount();
        if (lastHealth >= 0.0F && health < lastHealth) {
            log(String.format(Locale.ROOT, "self damage: %.1f (health %.1f -> %.1f) source: %s",
                    lastHealth - health, lastHealth, health, sourceGuess(player)));
        }
        lastHealth = health;
    }

    /** The client's own record of the last hit, when it is fresh enough to belong to this drop. */
    private static String sourceGuess(LocalPlayer player) {
        DamageSource source = player.getLastDamageSource();
        if (source == null || player.hurtTime <= 0) {
            return "no damage event";
        }
        Entity attacker = source.getEntity();
        return source.getMsgId() + (attacker == null ? "" : " by " + attacker.getName().getString());
    }

    private void flush() {
        if (blockChanges > 0) {
            log("blocks: " + blockChanges + (blockChanges == 1 ? " change, " : " changes, first ") + firstBlock);
        }
        for (Map.Entry<String, int[]> entry : sounds.entrySet()) {
            log("sound: " + entry.getKey() + " x" + entry.getValue()[0] + " at " + soundPos.get(entry.getKey()));
        }
        clearAggregates();
    }

    private void clearAggregates() {
        blockChanges = 0;
        firstBlock = null;
        if (!sounds.isEmpty()) {
            sounds.clear();
            soundPos.clear();
        }
    }

    private static boolean inWindow() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        if (!state.inDungeon() || state.floorNumber() != 7) {
            return false;
        }
        FloorSevenPhaseTimer.Phase phase = FloorSevenPhaseTimer.getInstance().current();
        return phase == FloorSevenPhaseTimer.Phase.STORM || phase == FloorSevenPhaseTimer.Phase.GOLDOR;
    }

    private static void log(String event) {
        SkyblockSimplifiedSBS.LOGGER.info(line(ServerTickClock.getInstance().now(),
                ServerStatsTracker.getInstance().tps(), event));
    }

    /** The log line; the tick is {@code ?} while the clock has had no packet yet. */
    static String line(double tick, double tps, String event) {
        String t = tick < 0 ? "?" : String.format(Locale.ROOT, "%.1f", tick);
        String rate = tps < 0 ? "?" : String.format(Locale.ROOT, "%.1f", tps);
        return TAG + "t=" + t + " " + event + " (tps " + rate + ")";
    }
}
