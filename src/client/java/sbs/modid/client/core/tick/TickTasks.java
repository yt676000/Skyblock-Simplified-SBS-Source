/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tick;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.perf.Perf;
import sbs.modid.client.core.perf.TierScheduler;

import java.util.List;
import java.util.function.Predicate;

/**
 * Every client-tick consumer, in the order it has always run, with the gate that decides whether it
 * is relevant this tick. {@code AutoSprintMixin} runs {@link #runHead} at the start of the tick,
 * {@code GuiTrackingMixin} runs {@link #runTail} at its end.
 *
 * <p><b>Choosing a gate.</b> The gate is a superset of every situation in which the consumer's tick
 * does anything observable - a state change, a file write, a chat line, a timer expiring, a marker
 * published or cleared. Consumers that hold wall-clock timers, flush files, follow you across
 * islands, or work off SkyBlock / in singleplayer are {@link TickTask#ALWAYS}. A consumer's own leave
 * edge runs inside the dispatcher's run-on, and its "no player" cleanup inside the run-on every task
 * gets on a world change. The feature's own checks stay where they are, as the second level. The
 * audit behind each gate is in the PR that introduced this class.
 *
 * <p>Gates are non-capturing lambdas on the shared context, so a steady tick allocates nothing.
 */
public final class TickTasks {

    // ------------------------------------------------------------------ gates

    private static final Predicate<TickContext> ALWAYS = TickTask.ALWAYS;
    private static final Predicate<TickContext> PLAYER = TickContext::hasPlayer;
    private static final Predicate<TickContext> SKYBLOCK = TickContext::inSkyBlock;
    private static final Predicate<TickContext> CONTAINER = TickContext::container;
    private static final Predicate<TickContext> CATACOMBS = TickContext::inCatacombs;
    private static final Predicate<TickContext> CATACOMBS_OR_CONTAINER = c -> c.inCatacombs() || c.container();
    /** {@code GardenBlueprintManager.inGarden()}: on the Garden, or the island unreadable and the zone says so. */
    private static final Predicate<TickContext> GARDEN = c -> c.on(Island.GARDEN) || c.islandUnknown();
    private static final Predicate<TickContext> GARDEN_OR_CONTAINER = c -> GARDEN.test(c) || c.container();
    /** {@code SkillIslands.miningAllowed()}: the island lock off means everywhere. */
    private static final Predicate<TickContext> MINING_ALLOWED =
            c -> !ConfigManager.getInstance().get().mining.islandLock || c.location().miningIsland();
    /** Where ore features look: the mining islands by zone or island, or an unreadable island. */
    private static final Predicate<TickContext> ORES = c -> c.location().miningIsland()
            || c.on(Island.DWARVEN_MINES) || c.on(Island.CRYSTAL_HOLLOWS) || c.on(Island.GLACITE_MINESHAFTS)
            || c.islandUnknown();
    private static final Predicate<TickContext> SAFARI = c -> c.location().inSafariArea();
    private static final Predicate<TickContext> RIFT = c -> c.on(Island.RIFT);

    private static TickDispatcher head;
    private static TickDispatcher tail;

    private TickTasks() {
    }

    /** The start of the client tick, after the sidebar snapshot and tab revision are current. */
    public static void runHead() {
        if (head == null) {
            head = new TickDispatcher(headTasks(), new PerfTickMeter());
        }
        PerfTickMeter.flushChat();
        TickContext context = refreshContext();
        if (context != null) {
            head.run(context);
        }
    }

    /** The end of the client tick (after the vanilla tick applied this tick's packets). */
    public static void runTail() {
        if (tail == null) {
            tail = new TickDispatcher(tailTasks(), new PerfTickMeter());
        }
        TickContext context = refreshContext();
        if (context != null) {
            tail.run(context);
        }
    }

    private static boolean contextFailureLogged;

    /**
     * The shared context, or {@code null} when building it threw - then this tick's tasks are skipped
     * rather than the throw ending the game (the dispatcher's fence, one level up). Logged once.
     */
    private static TickContext refreshContext() {
        try (Perf.Section perf = Perf.tick("tick.TickContext")) {
            return TickContexts.refresh();
        } catch (RuntimeException | LinkageError | StackOverflowError thrown) {
            if (!contextFailureLogged) {
                contextFailureLogged = true;
                SkyblockSimplifiedSBS.LOGGER.error("[SBS][Tick] tick.TickContext failed: {}",
                        thrown.toString(), thrown);
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ the start of the tick

    private static List<TickTask> headTasks() {
        return List.of(
                // Fishing: a bobber out or a rod in hand, a menu (the Bait Sack), or the tracker still
                // owing something - its 5 min activity window or a Golden Fish clock. Slot changes
                // alone do not count: Hypixel resends slots constantly, so that window never closed.
                TickTask.of("tick.FishingTracker",
                        c -> c.fishing() || c.container()
                                || sbs.modid.client.skills.fishing.logic.FishingTracker.getInstance().needsTick(),
                        () -> sbs.modid.client.skills.fishing.logic.FishingTracker.getInstance().onTick()),
                TickTask.of("tick.SeaCreatureAnnouncer",
                        c -> c.fishing()
                                || sbs.modid.client.skills.fishing.logic.SeaCreatureAnnouncer.getInstance().needsTick(),
                        () -> sbs.modid.client.skills.fishing.logic.SeaCreatureAnnouncer.getInstance().onClientTick()),
                // Any island, and singleplayer.
                TickTask.of("tick.MobHighlightTracker", PLAYER,
                        () -> sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker.getInstance().onClientTick()),
                // In the Mist - unless "only in the Mist" is off, which is the try-it-anywhere switch.
                // Spring Boots charge meter: SkyBlock with the feature on; boots and sneak are read inside.
                TickTask.of("tick.SpringBoots",
                        sbs.modid.client.helper.springboots.logic.SpringBootsTracker::gate,
                        () -> sbs.modid.client.helper.springboots.logic.SpringBootsTracker.getInstance().onClientTick()),
                TickTask.of("tick.GhostTracker",
                        c -> c.hasPlayer() && (!ConfigManager.getInstance().get().ghostHunter.onlyInTheMist
                                || c.location().inTheMist()),
                        () -> sbs.modid.client.combat.ghost.logic.GhostTracker.getInstance().onClientTick()),
                // Class colours / skins for the dungeon map: re-parsed only when tab or sidebar moved.
                TickTask.of("tick.DungeonTeamClasses", ALWAYS,
                        sbs.modid.client.dungeons.run.model.DungeonTeamClasses::onClientTick),
                TickTask.of("tick.BloodRoomTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.blood.logic.BloodRoomTracker.getInstance().onClientTick()),
                // The camp clock only means something in a dungeon; its world-change hook clears it
                // on the way out.
                TickTask.of("tick.BloodMoveTimer", CATACOMBS,
                        () -> sbs.modid.client.dungeons.blood.logic.BloodMoveTimer.getInstance().onClientTick()),
                TickTask.of("tick.PrinceTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.run.logic.PrinceTracker.getInstance().onClientTick()),
                TickTask.of("tick.SpiritBearTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.spiritbear.logic.SpiritBearTracker.getInstance().onClientTick()),
                TickTask.of("tick.KuudraTracker", c -> c.on(Island.KUUDRAS_HOLLOW),
                        () -> sbs.modid.client.combat.kuudra.logic.KuudraTracker.getInstance().onClientTick()),
                // The quest window expires anywhere; the scan itself gates on PeltTracker.onFarmingIslands().
                TickTask.of("tick.PeltTracker", ALWAYS,
                        () -> sbs.modid.client.skills.hunting.logic.PeltTracker.getInstance().onClientTick()),
                // Trevor is only on the Farming Islands; the render path only resolves the id found here.
                TickTask.of("tick.TrevorLocator", c -> c.on(Island.FARMING_ISLANDS),
                        sbs.modid.client.skills.hunting.logic.TrevorLocator::onClientTick),
                // Its leave confirm (2 s + a poll) is longer than the run-on, and an unreadable
                // location must not end the trip: no gate.
                TickTask.of("tick.SafariTracker", ALWAYS,
                        () -> sbs.modid.client.skills.hunting.logic.SafariTracker.getInstance().onClientTick()),
                TickTask.of("tick.HidingCritterTracker", SAFARI,
                        () -> sbs.modid.client.skills.hunting.logic.HidingCritterTracker.getInstance().onClientTick()),
                TickTask.of("tick.SnoozleWallProbe", TierScheduler.Tier.BACKGROUND, SAFARI,
                        () -> sbs.modid.client.skills.hunting.logic.SnoozleWallProbe.getInstance().onClientTick()),
                TickTask.of("tick.HideyhoTracker", SAFARI,
                        () -> sbs.modid.client.skills.hunting.hideyho.logic.HideyhoTracker.getInstance().onClientTick()),
                TickTask.of("tick.FloorDropTracker", c -> c.location().floorDropArea(),
                        () -> sbs.modid.client.helper.floordrop.logic.FloorDropTracker.getInstance().onClientTick()),
                // Craft Helper: re-reads counts every half second while a tree is shown, rebuilds on change.
                TickTask.of("tick.CraftHelper", TierScheduler.Tier.BACKGROUND, PLAYER,
                        () -> sbs.modid.client.economy.crafthelper.logic.CraftHelper.getInstance().tick()),
                TickTask.of("tick.CroesusReminder", TierScheduler.Tier.BACKGROUND, c -> c.on(Island.DUNGEON_HUB),
                        () -> sbs.modid.client.dungeons.croesus.logic.CroesusReminder.getInstance().onClientTick()),
                TickTask.of("tick.TacTimerTracker", ALWAYS,
                        () -> sbs.modid.client.combat.abilitytimers.logic.TacTimerTracker.getInstance().onClientTick()),
                TickTask.of("tick.AbilityCooldownTracker", ALWAYS,
                        () -> sbs.modid.client.combat.abilitytimers.logic.AbilityCooldownTracker.getInstance().onClientTick()),
                TickTask.of("tick.StatBuffTracker", SKYBLOCK,
                        () -> sbs.modid.client.combat.statbuffs.logic.StatBuffTracker.getInstance().onClientTick()),
                // Chat-armed, and their phase is read by the F7 trackers below.
                TickTask.of("tick.FloorSevenPhaseTimer", ALWAYS,
                        () -> sbs.modid.client.dungeons.floorseven.logic.FloorSevenPhaseTimer.getInstance().onClientTick()),
                // Log-only timing probe for the Storm pad and Goldor tick cycles; arms itself in P2/P3.
                TickTask.of("tick.M7TickProbe", CATACOMBS,
                        () -> sbs.modid.client.dungeons.floorseven.logic.M7TickProbe.getInstance().onClientTick()),
                // Rebuilds the tick-timer card once per tick; needs the phase timer above first.
                TickTask.of("tick.M7TickTimers", CATACOMBS,
                        () -> sbs.modid.client.dungeons.floorseven.logic.M7TickTimers.getInstance().onClientTick()),
                TickTask.of("tick.TerminalTracker", ALWAYS,
                        () -> sbs.modid.client.dungeons.floorseven.logic.TerminalTracker.getInstance().onClientTick()),
                TickTask.of("tick.SimonSaysTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.floorseven.logic.SimonSaysTracker.getInstance().onClientTick()),
                TickTask.of("tick.ArrowAlignTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.floorseven.logic.ArrowAlignTracker.getInstance().onClientTick()),
                TickTask.of("tick.LightsDeviceTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.floorseven.logic.LightsDeviceTracker.getInstance().onClientTick()),
                TickTask.of("tick.ShootTargetTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.floorseven.logic.ShootTargetTracker.getInstance().onClientTick()),
                TickTask.of("tick.DragonTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.floorseven.logic.DragonTracker.getInstance().onClientTick()),
                TickTask.of("tick.RelicTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.floorseven.logic.RelicTracker.getInstance().onClientTick()),
                // Log-only: where the M7 relics appear, are picked and are placed ([SBS][Relics]).
                TickTask.of("tick.RelicProbe", CATACOMBS,
                        () -> sbs.modid.client.dungeons.floorseven.logic.RelicProbe.getInstance().onClientTick()),
                // Command-armed dev capture; expires and writes its file anywhere.
                TickTask.of("tick.M7DragonProbe", ALWAYS,
                        () -> sbs.modid.client.core.dev.M7DragonProbe.getInstance().onClientTick()),
                TickTask.of("tick.FireFreezeTimer", ALWAYS,
                        () -> sbs.modid.client.dungeons.floorthree.logic.FireFreezeTimer.getInstance().onClientTick()),
                TickTask.of("tick.TerracottaTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.floorsix.logic.TerracottaTracker.getInstance().onClientTick()),
                TickTask.of("tick.GiantHpTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.floorsix.logic.GiantHpTracker.getInstance().onClientTick()),
                TickTask.of("tick.BossHealthModel", CATACOMBS,
                        () -> sbs.modid.client.dungeons.bossbar.logic.BossHealthModel.getInstance().onClientTick()),
                TickTask.of("tick.GuardianHpTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.floorthree.logic.GuardianHpTracker.getInstance().onClientTick()),
                TickTask.of("tick.LividTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.livid.logic.LividTracker.getInstance().onClientTick()),
                // Slayer bosses, kills and damage happen on any island.
                TickTask.of("tick.CarryCounter", PLAYER,
                        () -> sbs.modid.client.combat.carry.logic.CarryCounter.getInstance().onClientTick()),
                TickTask.of("tick.CollectionTracker", PLAYER,
                        () -> sbs.modid.client.skills.collection.CollectionTracker.getInstance().onClientTick()),
                TickTask.of("tick.BestiaryTracker", PLAYER,
                        () -> sbs.modid.client.combat.bestiary.BestiaryTracker.getInstance().onClientTick()),
                TickTask.of("tick.DamageAttribution", PLAYER,
                        () -> sbs.modid.client.combat.damage.logic.DamageAttribution.getInstance()
                                .onClientTick(Minecraft.getInstance())),
                // Ticks PlayerStatsTracker unconditionally first.
                TickTask.of("tick.DamageEstimator", ALWAYS,
                        () -> sbs.modid.client.combat.damage.logic.DamageEstimator.getInstance().onClientTick()),
                TickTask.of("tick.SlayerTracker", ALWAYS,
                        () -> sbs.modid.client.combat.slayer.logic.SlayerTracker.getInstance().onClientTick()),
                // Runs in the hub after a run, about the instance just left; one config read while off.
                TickTask.of("tick.AutoRequeue", ALWAYS,
                        () -> sbs.modid.client.dungeons.requeue.logic.AutoRequeue.getInstance().onClientTick()),
                // Lobbies and limbo after a kick are exactly where it works.
                TickTask.of("tick.RejoinTimer", ALWAYS,
                        () -> sbs.modid.client.helper.rejoin.RejoinTimer.getInstance().onClientTick()),
                TickTask.of("tick.ReminderTracker", PLAYER,
                        () -> sbs.modid.client.helper.reminder.logic.ReminderTracker.getInstance().onClientTick()),
                TickTask.of("tick.ShinyOrbTracker", PLAYER,
                        () -> sbs.modid.client.helper.yearofthepig.logic.ShinyOrbTracker.getInstance().onClientTick()),
                // A target can be set anywhere, and leaving its island is what clears the waypoint.
                TickTask.of("tick.NpcLocator", ALWAYS,
                        () -> sbs.modid.client.helper.npc.NpcLocator.getInstance().onClientTick()),
                TickTask.of("tick.ObjectiveNpcTracker", ALWAYS,
                        () -> sbs.modid.client.helper.npc.ObjectiveNpcTracker.getInstance().onClientTick()),
                TickTask.of("tick.ComposterOverlay", CONTAINER,
                        () -> sbs.modid.client.skills.garden.ui.ComposterOverlay.getInstance().onClientTick()),
                TickTask.of("tick.VisitorOfferStore", GARDEN_OR_CONTAINER,
                        () -> sbs.modid.client.skills.garden.logic.VisitorOfferStore.getInstance().tick()),
                TickTask.of("tick.VisitorTimer", GARDEN,
                        () -> sbs.modid.client.skills.garden.logic.VisitorTimer.getInstance().tick()),
                TickTask.of("tick.GreenhouseCapture", GARDEN,
                        () -> sbs.modid.client.skills.garden.logic.GreenhouseCapture.getInstance().tick()),
                // Research capture with a trail after the menu closes; one boolean read while off.
                TickTask.of("tick.CropAnalyzerCapture", ALWAYS,
                        () -> sbs.modid.client.skills.garden.logic.CropAnalyzerCapture.getInstance().tick()),
                // Farming stats and tools exist outside the Garden (Farming Islands, Hub farms).
                TickTask.of("tick.FarmingTracker", SKYBLOCK,
                        () -> sbs.modid.client.skills.garden.logic.FarmingTracker.getInstance().onClientTick()),
                TickTask.of("tick.CropMilestoneTracker", SKYBLOCK,
                        () -> sbs.modid.client.skills.farming.logic.CropMilestoneTracker.getInstance().onClientTick()),
                TickTask.of("tick.MousematAngles", SKYBLOCK,
                        () -> sbs.modid.client.skills.farming.logic.MousematAngles.getInstance().onClientTick()),
                TickTask.of("tick.GardenLevel", GARDEN,
                        () -> sbs.modid.client.skills.garden.model.GardenLevel.getInstance().onClientTick()),
                // Its world-change clear is covered by the world-change run-on; trap menus by CONTAINER.
                TickTask.of("tick.PestTracker", GARDEN_OR_CONTAINER,
                        () -> sbs.modid.client.skills.garden.logic.PestTracker.getInstance().onClientTick()),
                TickTask.of("tick.SprayTracker", SKYBLOCK,
                        () -> sbs.modid.client.skills.garden.logic.SprayTracker.getInstance().onClientTick()),
                // Cake and consumable timers, their alerts and saves run on every island.
                TickTask.of("tick.BuffTracker", ALWAYS,
                        () -> sbs.modid.client.helper.buffs.BuffTracker.getInstance().onClientTick()),
                // Ends the session after you left - off the farming islands, after its timeout.
                TickTask.of("tick.FarmingSessionTracker", ALWAYS,
                        () -> sbs.modid.client.skills.farming.session.FarmingSessionTracker.getInstance().onClientTick()),
                // Farming Net polls the crop / pest / spray trackers once a second, farming islands only.
                TickTask.of("tick.FarmingNetTracker", TierScheduler.Tier.BACKGROUND, c -> c.on(Island.FARMING_ISLANDS),
                        () -> sbs.modid.client.skills.farming.net.FarmingNetTracker.getInstance().onClientTick()),
                TickTask.of("tick.TabInfoTracker", ALWAYS,
                        () -> sbs.modid.client.helper.scoreboard.TabInfoTracker.getInstance().onClientTick()),
                TickTask.of("tick.TankRange", ALWAYS,
                        sbs.modid.client.dungeons.run.render.TankRangeRender::onClientTick),
                TickTask.of("tick.ServerStatsTracker", ALWAYS,
                        () -> sbs.modid.client.ui.hud.logic.ServerStatsTracker.getInstance().onClientTick()),
                // Custom Scoreboard: rebuild the drawn panel when its inputs moved. After the sidebar
                // snapshot, the buffs, the tab info and the ping above, so it is built from this tick's.
                // BOOKMARK(merge 2026-10-05): added - came from scoreboard-render-cache (AutoSprintMixin).
                TickTask.of("tick.CustomScoreboard", ALWAYS,
                        sbs.modid.client.helper.scoreboard.CustomScoreboardRenderer::onClientTick),
                TickTask.of("tick.EventTimers", ALWAYS,
                        () -> sbs.modid.client.helper.timers.EventTimers.getInstance().onClientTick()),
                TickTask.of("tick.JacobContestTracker", ALWAYS,
                        () -> sbs.modid.client.skills.farming.contest.JacobContestTracker.getInstance().tick()),
                TickTask.of("tick.FarmingSpeed", GARDEN,
                        () -> sbs.modid.client.skills.farming.logic.FarmingSpeed.getInstance().tick()),
                TickTask.of("tick.LaneEndWarning", GARDEN,
                        () -> sbs.modid.client.skills.farming.logic.LaneEndWarning.getInstance().tick()),
                TickTask.of("tick.MilestoneTracker", SKYBLOCK,
                        () -> sbs.modid.client.helper.milestone.MilestoneTracker.getInstance().onClientTick()),
                TickTask.of("tick.MiningTracker", MINING_ALLOWED,
                        () -> sbs.modid.client.skills.mining.logic.MiningTracker.getInstance().onClientTick()),
                TickTask.of("tick.ColdTracker", ORES,
                        () -> sbs.modid.client.skills.mining.logic.ColdTracker.getInstance().onClientTick()),
                TickTask.of("tick.EffectiveBlocks", ORES,
                        () -> sbs.modid.client.skills.mining.logic.EffectiveBlockScanner.getInstance().onClientTick()),
                // Backup prices: merge queued observations, then save in a calm moment. Both are the
                // lowest tier and also check Headroom themselves, so under load they wait.
                TickTask.of("tick.price.backup.update", TierScheduler.Tier.BACKGROUND, ALWAYS,
                        () -> sbs.modid.client.economy.prices.BackupPrices.getInstance().onUpdateTick()),
                TickTask.of("tick.price.backup.save", TierScheduler.Tier.BACKGROUND,
                        sbs.modid.client.economy.prices.BackupPrices::calm,
                        () -> sbs.modid.client.economy.prices.BackupPrices.getInstance().onSaveTick()),
                TickTask.of("tick.EffectiveBlocksSweep", TierScheduler.Tier.BACKGROUND, ORES,
                        () -> sbs.modid.client.skills.mining.logic.EffectiveBlockScanner.getInstance().onSweepTick()),
                TickTask.of("tick.PrecisionMining", c -> c.location().miningIsland(),
                        () -> sbs.modid.client.skills.mining.precision.logic.PrecisionMiningTracker.getInstance().onClientTick()),
                TickTask.of("tick.GyroHelper", SKYBLOCK,
                        () -> sbs.modid.client.combat.gyro.logic.GyroState.getInstance().onClientTick()),
                TickTask.of("tick.AbilityReadyAlert", ALWAYS,
                        () -> sbs.modid.client.combat.cooldowns.AbilityReadyAlert.getInstance().onClientTick()),
                // Per-screen opacity: with no screen open, the palette goes back to the global value.
                TickTask.selfMeasured("tick.ScreenOpacity", ALWAYS, () -> {
                    if (sbs.modid.client.core.api.ScreenAccess.current() == null) {
                        sbs.modid.client.ui.theme.ScreenOpacity.leave();
                    }
                }),
                TickTask.of("tick.TeamHealthTracker", c -> c.location().inDungeonFloor(),
                        () -> sbs.modid.client.dungeons.teamhealth.logic.TeamHealthTracker.getInstance().onClientTick()),
                TickTask.of("tick.CoinsPerHourTracker", PLAYER,
                        () -> sbs.modid.client.economy.coinsperhour.logic.CoinsPerHourTracker.getInstance().onClientTick()),
                TickTask.of("tick.DiceTracker", PLAYER,
                        () -> sbs.modid.client.economy.dice.logic.DiceTracker.getInstance().onClientTick()),
                TickTask.of("tick.DragonProfitTracker", PLAYER,
                        () -> sbs.modid.client.combat.dragonprofit.logic.DragonProfitTracker.getInstance().onClientTick()),
                TickTask.of("tick.SacrificeTracker", c -> c.location().on(Island.END),
                        () -> sbs.modid.client.economy.sacrifice.logic.SacrificeTracker.getInstance().onClientTick()),
                TickTask.of("tick.ExperimentProfit", PLAYER,
                        () -> sbs.modid.client.helper.experiment.logic.ExperimentProfit.getInstance().onClientTick(net.minecraft.client.Minecraft.getInstance())),
                TickTask.of("tick.FallenStarTracker", ALWAYS,
                        () -> sbs.modid.client.skills.mining.fallenstar.logic.FallenStarTracker.getInstance().onClientTick()),
                // Visit bookkeeping on every level change, Hollows or not.
                TickTask.of("tick.HollowsLobbyWatch", ALWAYS,
                        () -> sbs.modid.client.helper.reminder.logic.HollowsLobbyWatch.getInstance().onClientTick()),
                TickTask.of("tick.MiningEventTracker", c -> c.on(Island.DWARVEN_MINES) || c.on(Island.CRYSTAL_HOLLOWS),
                        () -> sbs.modid.client.skills.mining.events.logic.MiningEventTracker.getInstance().onClientTick()),
                TickTask.of("tick.ToolDurability", PLAYER,
                        () -> sbs.modid.client.skills.mining.logic.ToolDurability.getInstance().onClientTick()),
                TickTask.of("tick.GemstoneTracker", ALWAYS,
                        () -> sbs.modid.client.skills.mining.logic.GemstoneTracker.getInstance().onClientTick()),
                // Saves, prunes and pauses its clock off the Hollows.
                TickTask.of("tick.NucleusRunTracker", ALWAYS,
                        () -> sbs.modid.client.skills.mining.nucleus.logic.NucleusRunTracker.getInstance().onClientTick()),
                // Run start/end from the dungeon state, menu cost reads, saves; Croesus is outside a run.
                TickTask.of("tick.DungeonProfitTracker", ALWAYS,
                        () -> sbs.modid.client.dungeons.profit.logic.DungeonProfitTracker.getInstance().onClientTick()),
                // Arms on a live Kuudra run, flushes reward blocks, saves; keys are bought off the Hollow.
                TickTask.of("tick.KuudraProfitTracker", ALWAYS,
                        () -> sbs.modid.client.combat.kuudraprofit.logic.KuudraProfitTracker.getInstance().onClientTick()),
                TickTask.of("tick.DivanTracker", c -> c.on(Island.CRYSTAL_HOLLOWS),
                        () -> sbs.modid.client.skills.mining.metaldetector.logic.DivanTracker.getInstance().onClientTick()),
                // Clears its waypoint anywhere when switched off.
                TickTask.of("tick.TempleCheese", ALWAYS,
                        () -> sbs.modid.client.skills.mining.nucleus.logic.TempleCheeseWaypoint.getInstance().onClientTick()),
                TickTask.of("tick.GhastObserver", PLAYER,
                        () -> sbs.modid.client.skills.mining.logic.GhastObserver.getInstance().onClientTick()),
                TickTask.of("tick.MiningWidgetCapture", PLAYER,
                        () -> sbs.modid.client.skills.mining.logic.MiningWidgetCapture.getInstance().onClientTick()),
                // The powder-spend check runs before its menu check, on any screen.
                TickTask.of("tick.HotmTreeReader", ALWAYS,
                        () -> sbs.modid.client.skills.mining.logic.HotmTreeReader.getInstance().onClientTick()),
                TickTask.of("tick.HotmReminder", ALWAYS,
                        () -> sbs.modid.client.skills.mining.logic.HotmReminder.getInstance().onClientTick()),
                TickTask.of("tick.PartyChatCommands", ALWAYS,
                        () -> sbs.modid.client.social.chat.command.PartyChatCommands.getInstance().onClientTick()),
                TickTask.of("tick.TransferCooldown", ALWAYS,
                        () -> sbs.modid.client.core.command.TransferCooldown.getInstance().onClientTick()),
                // Visuals: republish on leaving, and work in singleplayer / the vanilla End.
                TickTask.of("tick.EndVisuals", ALWAYS,
                        sbs.modid.client.helper.visual.logic.EndVisuals::onClientTick),
                TickTask.of("tick.MistVisuals", ALWAYS,
                        sbs.modid.client.helper.visual.logic.MistVisuals::onClientTick),
                TickTask.of("tick.DarkMode", ALWAYS,
                        sbs.modid.client.helper.visual.logic.DarkMode::onClientTick),
                TickTask.of("tick.BorderlessWindow", ALWAYS,
                        sbs.modid.client.helper.visual.logic.BorderlessWindow::onClientTick),
                // The Rift's edge publisher sees every arrival and departure; the readers gate on it.
                TickTask.of("tick.RiftState", ALWAYS,
                        () -> sbs.modid.client.helper.rift.logic.RiftState.getInstance().tick()),
                TickTask.of("tick.RiftTime", RIFT,
                        () -> sbs.modid.client.helper.rift.logic.RiftTime.getInstance().tick()),
                TickTask.of("tick.RiftMotes", RIFT,
                        () -> sbs.modid.client.helper.rift.logic.RiftMotes.getInstance().tick()),
                TickTask.of("tick.RiftTimeAlerts", RIFT,
                        () -> sbs.modid.client.helper.rift.logic.RiftTimeAlerts.getInstance().tick()),
                TickTask.of("tick.BloodEffigyTracker", RIFT,
                        () -> sbs.modid.client.combat.vampire.logic.BloodEffigyTracker.getInstance()
                                .tick(Minecraft.getInstance())),
                TickTask.of("tick.ManiaProbe", RIFT,
                        () -> sbs.modid.client.combat.vampire.logic.ManiaProbe.getInstance()
                                .tick(Minecraft.getInstance())),
                TickTask.of("tick.SweepTracker", SKYBLOCK,
                        () -> sbs.modid.client.skills.foraging.logic.SweepTracker.getInstance().tick()),
                TickTask.of("tick.HoneyTreeTimers", ALWAYS,
                        () -> sbs.modid.client.skills.foraging.logic.HoneyTreeTimers.getInstance().onClientTick()),
                TickTask.of("tick.ForgeTimers", ALWAYS,
                        () -> sbs.modid.client.economy.forge.logic.ForgeTimers.getInstance().onClientTick()),
                // Preview waypoints, guess TTLs and the Hub-leave clear all run off the Hub.
                TickTask.of("tick.Diana", ALWAYS, () ->
                        sbs.modid.client.combat.diana.logic.DianaGuard.run(
                                sbs.modid.client.combat.diana.logic.DianaGuard.Hook.TICK, "client tick", () -> {
                                    sbs.modid.client.combat.diana.logic.ArrowGuess.getInstance().onClientTick();
                                    sbs.modid.client.combat.diana.logic.SpadeGuess.getInstance().onClientTick();
                                    sbs.modid.client.combat.diana.logic.MythMobTracker.getInstance().onClientTick();
                                    sbs.modid.client.combat.diana.render.BurrowMarkers.onClientTick();
                                })),
                // Outside the guard on purpose, and measures itself.
                TickTask.selfMeasured("tick.DianaDevLog", ALWAYS,
                        sbs.modid.client.combat.diana.devlog.DianaDevLog::onClientTick),
                TickTask.selfMeasured("tick.DianaGuard", ALWAYS,
                        sbs.modid.client.combat.diana.logic.DianaGuard::onClientTick),
                // Follows account switches anywhere, and saves on leaving the Hollows.
                TickTask.of("tick.HollowsMap", ALWAYS, () -> {
                    sbs.modid.client.core.location.hollows.HollowsDetector.getInstance().onClientTick();
                    sbs.modid.client.helper.map.logic.HollowsMapTracker.getInstance().onClientTick();
                }),
                // A 60 s socket linger, and consent withdrawal must stop it at once.
                TickTask.of("tick.StructureSharing", ALWAYS,
                        () -> sbs.modid.client.helper.map.logic.StructureSharing.getInstance().onClientTick()),
                TickTask.selfMeasured("tick.LearnArea", ALWAYS, TickTasks::learnArea),
                // Build tools work in singleplayer.
                TickTask.of("tick.EditEngine", ALWAYS, sbs.modid.client.helper.build.logic.EditEngine::tick),
                TickTask.of("tick.Freecam", ALWAYS, sbs.modid.client.helper.build.logic.Freecam::tick),
                TickTask.of("tick.HologramCensus", ALWAYS, sbs.modid.client.helper.build.logic.HologramCensus::tick));
    }

    /** Locations change only when you travel, so once a second is plenty and costs nothing. */
    private static long lastAreaCheck;

    /** Learn the scoreboard area for the keybind island dropdown (only writes on a new area). */
    private static void learnArea() {
        long now = System.currentTimeMillis();
        if (now - lastAreaCheck < 1000L) {
            return;
        }
        lastAreaCheck = now;
        sbs.modid.client.core.location.SkyBlockLocation.learn();
    }

    // ------------------------------------------------------------------ the end of the tick

    private static List<TickTask> tailTasks() {
        Minecraft minecraft = Minecraft.getInstance();
        return List.of(
                // Index the config options on the first tick, before any player exists.
                TickTask.of("tick.OptionIndex", ALWAYS, sbs.modid.client.ui.settings.OptionIndex::auditOnce),
                // Account/profile first, so the per-profile stores point at the right folder.
                TickTask.of("tick.ProfileContext", ALWAYS,
                        () -> sbs.modid.client.core.config.ProfileContext.getInstance().tick(minecraft)),
                TickTask.selfMeasured("tick.Alerts", ALWAYS, sbs.modid.client.core.alert.Alerts::tick),
                TickTask.of("tick.AlertNotice", ALWAYS, sbs.modid.client.core.alert.AlertNotice::tick),
                // Follows the "Local API Server" setting when it changes by any route other than its
                // toggle (reset, disable-all, profile switch, backup restore). Two field reads a tick.
                TickTask.of("tick.ApiServerSync",
                        c -> sbs.modid.client.core.api.ApiServer.getInstance().enabled()
                                != sbs.modid.client.core.config.ConfigManager.getInstance().get().localApi.enabled,
                        () -> sbs.modid.client.core.api.ApiServer.getInstance().setEnabled(
                                sbs.modid.client.core.config.ConfigManager.getInstance().get().localApi.enabled)),
                // Feeds only the local HTTP API: runs while the server is up and was polled in the
                // last 30 s. (getCurrentScreen() reads the live screen and never needed this tick.)
                TickTask.of("tick.GuiStateManager",
                        c -> sbs.modid.client.core.api.GuiStateManager.captureWanted(),
                        () -> sbs.modid.client.core.api.GuiStateManager.getInstance().tick(minecraft)),
                TickTask.of("tick.EntityProbe", ALWAYS,
                        () -> sbs.modid.client.core.dev.EntityProbe.getInstance().tick(minecraft)),
                TickTask.of("tick.SkyBlockItemIcons", ALWAYS,
                        () -> sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons.getInstance().tick(minecraft)),
                TickTask.of("tick.BeaconBeatReader", c -> c.on(Island.MOONGLADE_MARSH),
                        () -> sbs.modid.client.skills.foraging.beacon.logic.BeaconBeatReader.getInstance().tick(minecraft)),
                // Debounced file writes: dirtied from any screen, flushed wherever you are.
                TickTask.of("tick.WindowMemory", ALWAYS, sbs.modid.client.ui.window.WindowMemory::tick),
                // Config: write a settled change - a slider drag costs one file write, not one per value.
                // BOOKMARK(merge 2026-10-05): added - ConfigManager tick came from the main-checkout WIP (GuiTrackingMixin).
                TickTask.of("tick.ConfigManager", ALWAYS, () -> ConfigManager.getInstance().tick()),
                TickTask.of("tick.StorageIndex", ALWAYS,
                        () -> sbs.modid.client.helper.storage.StorageIndex.getInstance().tick()),
                TickTask.of("tick.AccessoryIndex", ALWAYS,
                        () -> sbs.modid.client.helper.inventory.logic.AccessoryIndex.getInstance().tick()),
                TickTask.of("tick.ShardOwnership", ALWAYS,
                        () -> sbs.modid.client.skills.hunting.logic.ShardOwnership.getInstance().tick()),
                TickTask.of("tick.ProtectedItems", ALWAYS,
                        () -> sbs.modid.client.helper.itemprotection.logic.ProtectedItems.getInstance().tick()),
                // Hides players on any server with "everywhere" on.
                // Levels first: PlayerHiding reads them in the same tick.
                TickTask.of("tick.PlayerLevels", PLAYER,
                        () -> sbs.modid.client.helper.hideplayers.logic.PlayerLevels.tick(minecraft)),
                TickTask.of("tick.PlayerHiding", PLAYER,
                        () -> sbs.modid.client.helper.hideplayers.logic.PlayerHiding.tick(minecraft)),
                TickTask.of("tick.MuseumMenuReader", CONTAINER,
                        () -> sbs.modid.client.helper.museum.logic.MuseumMenuReader.getInstance().tick(minecraft)),
                TickTask.of("tick.EquipmentStore", CONTAINER,
                        () -> sbs.modid.client.helper.inventory.logic.EquipmentStore.getInstance().tick(minecraft)),
                // Also reads the Bingo marker off the tab, with no menu open.
                TickTask.of("tick.BingoMenuReader", ALWAYS,
                        () -> sbs.modid.client.helper.bingo.logic.BingoMenuReader.getInstance().tick(minecraft)),
                TickTask.of("tick.TrophyFishTracker", CONTAINER,
                        () -> sbs.modid.client.skills.trophyfish.logic.TrophyFishTracker.getInstance().tick(minecraft)),
                // Saves the notes file every tick it is dirty; scans any server's players.
                TickTask.of("tick.PlayerNotesWarner", ALWAYS,
                        () -> sbs.modid.client.social.notes.logic.PlayerNotesWarner.getInstance().tick(minecraft)),
                TickTask.of("tick.ScoreboardLayoutMigration", PLAYER,
                        () -> sbs.modid.client.helper.scoreboard.ScoreboardLayoutMigration.tickNotice(minecraft)),
                TickTask.of("tick.PrivacyNotice", ALWAYS, sbs.modid.client.core.licence.privacy.PrivacyNotice::tick),
                // Counts ticks in any world; gating it would move when the wizard opens.
                TickTask.of("tick.WizardTrigger", ALWAYS, sbs.modid.client.ui.wizard.logic.WizardTrigger::tick),
                TickTask.of("tick.BazaarOrderTracker", ALWAYS,
                        () -> sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker.getInstance().tick(minecraft)),
                TickTask.of("tick.ScrapedRecipeStore", CONTAINER,
                        () -> sbs.modid.client.economy.recipe.logic.ScrapedRecipeStore.getInstance().tick(minecraft)),
                TickTask.of("tick.BitsShop", CONTAINER,
                        () -> sbs.modid.client.economy.bitsshop.BitsShop.learn(minecraft)),
                // Flushes the NPC shop catalog before its menu check.
                TickTask.of("tick.NpcShopReader", ALWAYS,
                        () -> sbs.modid.client.economy.npcshop.logic.NpcShopReader.tick(minecraft)),
                TickTask.of("tick.MinionStateCapture", c -> c.on(Island.PRIVATE_ISLAND) || c.container(),
                        () -> sbs.modid.client.economy.minions.logic.MinionStateCapture.tick(minecraft)),
                // Its timeout must expire even with no menu open.
                TickTask.of("tick.SupercraftHelper", ALWAYS,
                        () -> sbs.modid.client.economy.recipe.logic.SupercraftHelper.getInstance().tick(minecraft)),
                TickTask.of("tick.PetTracker", ALWAYS,
                        () -> sbs.modid.client.ui.hud.logic.PetTracker.getInstance().tick(minecraft)),
                TickTask.of("tick.MayorVoteTracker", PLAYER,
                        () -> sbs.modid.client.economy.mayor.MayorVoteTracker.getInstance().tick(minecraft)),
                TickTask.of("tick.ChocolateFactory", CONTAINER,
                        () -> sbs.modid.client.helper.chocolate.logic.ChocolateFactory.getInstance().tick(minecraft)),
                // Its close timer runs with no menu open; measures its scan itself.
                TickTask.selfMeasured("tick.ExperimentationTable", ALWAYS,
                        () -> sbs.modid.client.helper.experiment.logic.ExperimentationTable.getInstance().tick(minecraft)),
                TickTask.of("tick.BeaconTuning", CONTAINER,
                        () -> sbs.modid.client.skills.foraging.logic.BeaconTuning.getInstance().tick(minecraft)),
                TickTask.of("tick.CommissionRoute", MINING_ALLOWED,
                        () -> sbs.modid.client.skills.mining.logic.CommissionRoute.getInstance().tick(minecraft)),
                TickTask.of("tick.BestiaryTracker.tail", PLAYER,
                        () -> sbs.modid.client.combat.bestiary.BestiaryTracker.getInstance().onClientTick()),
                TickTask.of("tick.FreeSlotWatcher", SKYBLOCK,
                        () -> sbs.modid.client.helper.inventory.logic.FreeSlotWatcher.getInstance().tick(minecraft)),
                // Heals and captures worn armor with no menu open.
                TickTask.of("tick.LoadoutsOverlay", PLAYER,
                        () -> sbs.modid.client.helper.loadouts.LoadoutsOverlay.getInstance().onClientTick()),
                TickTask.of("tick.PetsOverlay", CONTAINER,
                        () -> sbs.modid.client.helper.pets.PetsOverlay.getInstance().onClientTick()),
                TickTask.of("tick.ArmorSetsOverlay", CONTAINER,
                        () -> sbs.modid.client.helper.wardrobe.ArmorSetsOverlay.getInstance().onClientTick()),
                // Also logs terminal-looking menus anywhere.
                TickTask.of("tick.TerminalSolver", CATACOMBS_OR_CONTAINER,
                        () -> sbs.modid.client.dungeons.terminal.TerminalSolver.getInstance().tick(minecraft)),
                TickTask.of("tick.PuzzleCoordinator", CATACOMBS,
                        () -> sbs.modid.client.dungeons.puzzle.logic.PuzzleCoordinator.getInstance().tick(minecraft)),
                TickTask.of("tick.HuntingBoxScanner", CONTAINER,
                        () -> sbs.modid.client.skills.hunting.logic.HuntingBoxScanner.getInstance().tick(minecraft)),
                TickTask.of("tick.AttributeMenuReader", CONTAINER,
                        () -> sbs.modid.client.skills.hunting.logic.AttributeMenuReader.getInstance().tick(minecraft)),
                TickTask.of("tick.DungeonRoomTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.run.logic.DungeonRoomTracker.getInstance().tick(minecraft)),
                TickTask.of("tick.WitherDoorTracker", CATACOMBS,
                        () -> sbs.modid.client.dungeons.run.logic.WitherDoorTracker.getInstance().tick(minecraft)),
                // The dungeon state every other dungeon consumer reads; it notices leaving itself.
                TickTask.of("tick.DungeonStateManager", ALWAYS,
                        () -> sbs.modid.client.dungeons.run.logic.DungeonStateManager.getInstance().tick(minecraft)),
                // Its tick counter is the time base of its secret counters.
                TickTask.of("tick.CollectedSecrets", PLAYER,
                        () -> sbs.modid.client.dungeons.run.logic.CollectedSecrets.getInstance().tick(minecraft)),
                TickTask.selfMeasured("tick.RewardChestGlow", CATACOMBS,
                        () -> sbs.modid.client.dungeons.chest.RewardChestGlow.getInstance().tick(minecraft)),
                TickTask.of("tick.TrapIndex", CATACOMBS,
                        () -> sbs.modid.client.dungeons.traps.logic.TrapIndex.getInstance().tick(minecraft)),
                TickTask.of("tick.MimicDetector", CATACOMBS,
                        () -> sbs.modid.client.dungeons.run.logic.MimicDetector.getInstance().tick(minecraft)),
                // Has no island filter: a saved coordinate can be anywhere.
                TickTask.of("tick.PositionalMessages", PLAYER,
                        () -> sbs.modid.client.dungeons.run.logic.PositionalMessages.getInstance().tick(minecraft)),
                TickTask.of("tick.SecretRoutesManager", CATACOMBS,
                        () -> sbs.modid.client.dungeons.secretroutes.logic.SecretRoutesManager.getInstance().tick(minecraft)),
                TickTask.of("tick.SecretRouting", CATACOMBS,
                        () -> sbs.modid.client.dungeons.secretroutes.logic.SecretRouting.getInstance().tick(minecraft)),
                // Dev tools work anywhere once armed.
                TickTask.of("tick.SecretScanner", ALWAYS,
                        () -> sbs.modid.client.core.dev.SecretScanner.getInstance().tick(minecraft)),
                TickTask.of("tick.MenuProbe", ALWAYS,
                        () -> sbs.modid.client.core.dev.MenuProbe.getInstance().tick(minecraft)),
                TickTask.of("tick.LayoutRecorder", ALWAYS,
                        () -> sbs.modid.client.core.dev.LayoutRecorder.getInstance().tick(minecraft)),
                TickTask.selfMeasured("tick.ServerScanner", ALWAYS,
                        () -> sbs.modid.client.core.dev.scanner.ServerScanner.tick(minecraft)),
                TickTask.of("tick.ShardDump", CONTAINER,
                        () -> sbs.modid.client.core.dev.ShardDump.getInstance().tick(minecraft)),
                // Pathfinding spans islands and server hops.
                TickTask.of("tick.JumpPads", ALWAYS,
                        () -> sbs.modid.client.core.pathfinding.JumpPads.getInstance().tick(minecraft)),
                TickTask.of("tick.PathfindingManager", ALWAYS,
                        () -> sbs.modid.client.core.pathfinding.PathfindingManager.getInstance().tick(minecraft)),
                TickTask.of("tick.PathRouting", ALWAYS, sbs.modid.client.core.pathfinding.PathRouting::refresh),
                TickTask.of("tick.QuestGuideTicker", PLAYER,
                        () -> sbs.modid.client.helper.quest.logic.QuestGuideTicker.tick(minecraft)),
                TickTask.of("tick.MapNavigation", ALWAYS,
                        () -> sbs.modid.client.helper.map.logic.MapNavigation.getInstance().tick(minecraft)),
                // React to the island becoming "" - that edge has to be seen however long it takes.
                TickTask.of("tick.FairySoulTracker", ALWAYS,
                        () -> sbs.modid.client.helper.fairysouls.logic.FairySoulTracker.getInstance().tick(minecraft)),
                TickTask.of("tick.FairySoulRouting", ALWAYS,
                        () -> sbs.modid.client.helper.fairysouls.logic.FairySoulRouting.getInstance().tick(minecraft)),
                TickTask.of("tick.WaypointPresetPublisher", ALWAYS,
                        () -> sbs.modid.client.skills.foraging.logic.WaypointPresetPublisher.getInstance().onClientTick()),
                TickTask.of("tick.GemzieWaypointPublisher", SAFARI,
                        () -> sbs.modid.client.skills.hunting.logic.GemzieWaypointPublisher.getInstance().onClientTick()),
                TickTask.of("tick.PingManager", ALWAYS,
                        () -> sbs.modid.client.helper.ping.PingManager.getInstance().onClientTick()),
                // Window-level: the title screen counts.
                TickTask.of("tick.WindowOpacity", ALWAYS,
                        () -> sbs.modid.client.helper.visual.logic.WindowOpacity.tick(minecraft)),
                TickTask.of("tick.FarTerrainManager", ALWAYS,
                        () -> sbs.modid.client.helper.terrain.FarTerrainManager.getInstance().tick(minecraft)),
                // Last: it hands memory back once everything above has had its say.
                TickTask.of("tick.MemoryGuard", ALWAYS,
                        () -> sbs.modid.client.core.memory.MemoryGuard.tick(minecraft)));
    }
}
