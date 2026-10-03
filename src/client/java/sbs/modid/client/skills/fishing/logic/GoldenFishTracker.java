/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import net.minecraft.world.entity.player.Player;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;

/**
 * Feeds the {@link GoldenFishTimer} from the game and sends its alerts.
 *
 * <p>Not a second cast detector: {@link FishingTracker#onTick} already owns the bobber edge and hands
 * it here. A cast counts once its bobber is <i>in lava</i> on the Crimson Isle - at the deploy edge
 * the bobber is still in the air, so the lava check runs per tick until it lands. Leaving the island,
 * or the world, resets the clock (assumed: a new lobby has no memory of your fishing, ESTIMATED).
 *
 * <p>Displays and warns only; it never casts or reels.
 */
public final class GoldenFishTracker {

    private static final GoldenFishTracker INSTANCE = new GoldenFishTracker();
    private static final String ISLAND = "Crimson Isle";

    private final GoldenFishTimer timer = new GoldenFishTimer();
    /** Whether the current cast's bobber has already been counted as a lava cast. */
    private boolean castCounted;

    private GoldenFishTracker() {
    }

    public static GoldenFishTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FishingSettings cfg() {
        return ConfigManager.getInstance().get().fishing;
    }

    public GoldenFishTimer.View view(long now) {
        return timer.view(now);
    }

    /** Left the world. */
    public void onNoPlayer() {
        timer.reset();
        castCounted = false;
    }

    /**
     * Once per tick from {@link FishingTracker#onTick}.
     *
     * @param bobberOut    a bobber is out this tick
     * @param bobberWasOut one was out last tick
     */
    public void onTick(Player player, boolean bobberOut, boolean bobberWasOut) {
        if (!cfg().goldenFishTimer) {
            return;
        }
        if (!SkyBlockLocation.onIsland(ISLAND)) {
            timer.reset();
            castCounted = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (bobberOut && !bobberWasOut) {
            castCounted = false;   // a new cast; it counts once it reaches lava
        }
        if (bobberOut && !castCounted && player.fishing != null && player.fishing.isInLava()) {
            castCounted = true;
            timer.onLavaCast(now);
        }
        if (!bobberOut && bobberWasOut) {
            timer.onReel(now);
        }
        switch (timer.tick(now)) {
            case RESET_WARNING -> {
                if (cfg().goldenFishResetWarning) {
                    Alerts.send(Alerts.Alert.of("Cast again",
                            "Golden Fish progress resets in about "
                                    + GoldenFishTimer.clock(timer.view(now).resetInMs())
                                    + " without a lava cast (estimated timing)."),
                            cfg().goldenFishResetChannels);
                }
            }
            case RESET -> SkyblockSimplifiedSBS.LOGGER.info("[SBS][GoldenFish] progress reset after idling");
            case EXPIRED -> SkyblockSimplifiedSBS.LOGGER.info("[SBS][GoldenFish] fish timed out with no gone line");
            case NONE -> { }
        }
    }

    /** Every chat line, colour-stripped. Logs anything naming the fish, for the probe. */
    public void onChat(String plain) {
        if (!cfg().goldenFishTimer) {
            return;
        }
        GoldenFishChat.Kind kind = GoldenFishChat.classify(plain);
        if (kind == GoldenFishChat.Kind.NONE) {
            return;
        }
        long now = System.currentTimeMillis();
        GoldenFishTimer.View before = timer.view(now);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][GoldenFish] {} line after {} fishing, {} since cast: {}",
                kind, GoldenFishTimer.clock(before.fishingMs()), GoldenFishTimer.clock(before.sinceCastMs()), plain);
        switch (kind) {
            case SPAWN -> {
                timer.onSpawn(now);
                Alerts.send(Alerts.Alert.of("Golden Fish!", "A Golden Fish is up - reel it in."),
                        cfg().goldenFishSpawnChannels);
            }
            case GONE -> {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][GoldenFish] gone after {} hooks", before.hooks());
                timer.onFishGone(now);
            }
            default -> { }
        }
    }
}
