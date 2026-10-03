/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;

/**
 * Sprint without holding the key.
 *
 * <p>Holds the sprint key down each tick rather than calling {@code setSprinting(true)} directly:
 * the key is the input vanilla's own movement code reads, so sprinting starts, stops and resumes
 * exactly as if you were holding it – including when you run out of hunger or hit a wall. Forcing
 * the sprint flag instead would fight that logic every tick.
 *
 * <p>Only pressed, never released: with the toggle off, the key is left entirely alone, so it stays
 * yours to hold normally.
 */
@Mixin(Minecraft.class)
public abstract class AutoSprintMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void sbs$clientTick(CallbackInfo ci) {
        // Closes the previous tick's measurement window (and runs the budget) first.
        sbs.modid.client.core.perf.Perf.endTick();
        // The fishing tracker diffs the inventory each tick; it gates itself on its own toggle.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.FishingTracker")) {
            sbs.modid.client.skills.fishing.logic.FishingTracker.getInstance().onTick();
        }
        // Sea Creature Announcer: follows the player's own bobber, runs the nametag fallback in the
        // window after a cast ends, and keeps the spawn highlight on its mob (gated on its toggle).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.SeaCreatureAnnouncer")) {
            sbs.modid.client.skills.fishing.logic.SeaCreatureAnnouncer.getInstance().onClientTick();
        }
        // Mob Highlight collects its target entities once per tick (gated on its own toggle).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.MobHighlightTracker")) {
            sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker.getInstance().onClientTick();
        }
        // Ghost Hunter: score the ghosts in The Mist and pick the next one (gated on its toggle + zone).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.GhostTracker")) {
            sbs.modid.client.combat.ghost.logic.GhostTracker.getInstance().onClientTick();
        }
        // Blood Helper: blood-room mob scan + the start-killing clock (gated on its toggle + the room).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.BloodRoomTracker")) {
            sbs.modid.client.dungeons.blood.logic.BloodRoomTracker.getInstance().onClientTick();
        }
        // Blood camp: the move call-out's clock (inert until the Watcher speaks; own toggle).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.BloodMoveTimer")) {
            sbs.modid.client.dungeons.blood.logic.BloodMoveTimer.getInstance().onClientTick();
        }
        // Prince call-out: watches for the first Prince death of the run (gated on its toggle + a run).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.PrinceTracker")) {
            sbs.modid.client.dungeons.run.logic.PrinceTracker.getInstance().onClientTick();
        }
        // Spirit Bear: the arena lantern ring + the bear watch (gated on its toggle + the F4/M4 boss).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.SpiritBearTracker")) {
            sbs.modid.client.dungeons.spiritbear.logic.SpiritBearTracker.getInstance().onClientTick();
        }
        // Kuudra: the run state machine, and behind it the supply / fresh / floor watches (all gated
        // on the module toggle and on actually being in Kuudra's Hollow).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.KuudraTracker")) {
            sbs.modid.client.combat.kuudra.logic.KuudraTracker.getInstance().onClientTick();
        }
        // Pelt Tracker: hunt the trapper animal (throttled inside; inert outside an active quest).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.PeltTracker")) {
            sbs.modid.client.skills.hunting.logic.PeltTracker.getInstance().onClientTick();
        }
        // Safari Summary: watches the scoreboard zone for entering / leaving the Safari (throttled
        // to 4/s inside; the trip summary is published on the way out).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.SafariTracker")) {
            sbs.modid.client.skills.hunting.logic.SafariTracker.getInstance().onClientTick();
        }
        // Critter Finder: sweeps for the critters that hide from you (throttled to every 10 ticks,
        // and only inside the Critter Safari - it returns on its own toggle before anything else).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.HidingCritterTracker")) {
            sbs.modid.client.skills.hunting.logic.HidingCritterTracker.getInstance().onClientTick();
        }
        // Snoozle walls: logs which block is really at each shipped spot, once per spot per visit
        // (throttled to every 20 ticks; inert unless the marker group is switched on). Never changes
        // what is drawn - the markers themselves are published by the preset waypoint path.
        // BACKGROUND: nothing is lost by running it less often, so the budget may defer it.
        if (sbs.modid.client.core.perf.Perf.allowTick("tick.SnoozleWallProbe",
                sbs.modid.client.core.perf.TierScheduler.Tier.BACKGROUND)) {
            try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.SnoozleWallProbe")) {
                sbs.modid.client.skills.hunting.logic.SnoozleWallProbe.getInstance().onClientTick();
            }
        }
        // Hideyho Finder: publishes the learned hiding places while a round runs and crosses off the
        // ones actually looked at (throttled inside; returns on its own toggle and the Safari gate
        // before touching the level).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.HideyhoTracker")) {
            sbs.modid.client.skills.hunting.hideyho.logic.HideyhoTracker.getInstance().onClientTick();
        }
        // Floor Drops: sweeps the item-display entities lying on the ground in the Galatea region
        // (throttled to every 5 ticks, and only in those three areas - it returns on its own toggle
        // before touching the level). Also what arms the particle listener, so no particle packet is
        // looked at anywhere else.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.FloorDropTracker")) {
            sbs.modid.client.helper.floordrop.logic.FloorDropTracker.getInstance().onClientTick();
        }
        // Croesus: one line on arriving in the Dungeon Hub when chests were left behind.
        // BACKGROUND: nothing is lost by running it less often, so the budget may defer it.
        if (sbs.modid.client.core.perf.Perf.allowTick("tick.CroesusReminder",
                sbs.modid.client.core.perf.TierScheduler.Tier.BACKGROUND)) {
            try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.CroesusReminder")) {
                sbs.modid.client.dungeons.croesus.logic.CroesusReminder.getInstance().onClientTick();
            }
        }
        // Death-Save Timers: registers its proc patterns on first touch, clears the clocks on logout.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.AbilityCooldownTracker")) {
            sbs.modid.client.combat.abilitytimers.logic.AbilityCooldownTracker.getInstance().onClientTick();
        }
        // Stat Buff Feedback: samples the tab stats and measures what an ability changed (gated on
        // its own toggle; throttled to 5/s inside).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.StatBuffTracker")) {
            sbs.modid.client.combat.statbuffs.logic.StatBuffTracker.getInstance().onClientTick();
        }
        // F7/M7 phase timer: registers its chat patterns on first touch, and forgets a run on leaving.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.FloorSevenPhaseTimer")) {
            sbs.modid.client.dungeons.floorseven.logic.FloorSevenPhaseTimer.getInstance().onClientTick();
        }
        // F7/M7 terminal progress: same shape - chat-driven counters, cleared when the run is left.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.TerminalTracker")) {
            sbs.modid.client.dungeons.floorseven.logic.TerminalTracker.getInstance().onClientTick();
        }
        // Simon Says device: watches the button lights during Goldor (throttled; gated on its toggle).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.SimonSaysTracker")) {
            sbs.modid.client.dungeons.floorseven.logic.SimonSaysTracker.getInstance().onClientTick();
        }
        // M7 dragons: health + which statue each has to die at (gated on its toggles + the M7 boss).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.DragonTracker")) {
            sbs.modid.client.dungeons.floorseven.logic.DragonTracker.getInstance().onClientTick();
        }
        // M7 dragon probe: samples the phase gate and the player's Y while a capture is armed, and
        // returns on a static boolean read otherwise. The packet halves hang off their own mixins.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.M7DragonProbe")) {
            sbs.modid.client.core.dev.M7DragonProbe.getInstance().onClientTick();
        }
        // F3/M3 Fire Freeze: the cast call-out (chat-armed, inert outside the Professor's window).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.FireFreezeTimer")) {
            sbs.modid.client.dungeons.floorthree.logic.FireFreezeTimer.getInstance().onClientTick();
        }
        // F6/M6 terracotta: arms the block watch and sweeps delivered markers (toggle + Sadan's room).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.TerracottaTracker")) {
            sbs.modid.client.dungeons.floorsix.logic.TerracottaTracker.getInstance().onClientTick();
        }
        // F6/M6 giants: reads their health off the nametags (gated on its toggle + Sadan's room).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.GiantHpTracker")) {
            sbs.modid.client.dungeons.floorsix.logic.GiantHpTracker.getInstance().onClientTick();
        }
        // F3/M3 guardians: same, for the Professor's four (gated on its toggle + the Professor's room).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.GuardianHpTracker")) {
            sbs.modid.client.dungeons.floorthree.logic.GuardianHpTracker.getInstance().onClientTick();
        }
        // Livid Tracker: pick the real Livid out of the clones (gated on its toggle + the F5/M5 room).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.LividTracker")) {
            sbs.modid.client.dungeons.livid.logic.LividTracker.getInstance().onClientTick();
        }
        // Slayer Carry Counter watches for boss deaths next to you (gated on its own toggle).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.CarryCounter")) {
            sbs.modid.client.combat.carry.logic.CarryCounter.getInstance().onClientTick();
        }
        // Collection Tracker scans the tab Collection widget (throttled internally to ~2/s).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.CollectionTracker")) {
            sbs.modid.client.skills.collection.CollectionTracker.getInstance().onClientTick();
        }
        // Bestiary Tracker reads the open Bestiary menu (throttled internally; gated on its toggle).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.BestiaryTracker")) {
            sbs.modid.client.combat.bestiary.BestiaryTracker.getInstance().onClientTick();
        }
        // Damage Attribution: classifies new damage splashes, re-hides foreign ones (gated inside).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.DamageAttribution")) {
            sbs.modid.client.combat.damage.logic.DamageAttribution.getInstance().onClientTick(Minecraft.getInstance());
        }

        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.DamageEstimator")) {
            sbs.modid.client.combat.damage.logic.DamageEstimator.getInstance().onClientTick();
        }
        // Slayer module: boss/beacon/nukekubi/miniboss scan (throttled internally to 4/s).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.SlayerTracker")) {
            sbs.modid.client.combat.slayer.logic.SlayerTracker.getInstance().onClientTick();
        }
        // Rejoin Timer: fire the end-of-countdown message once the wait elapses (gated on its toggle).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.RejoinTimer")) {
            sbs.modid.client.helper.rejoin.RejoinTimer.getInstance().onClientTick();
        }
        // Reminders: ping the chores whose cooldown ran out (throttled to every 5s; gated inside).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.ReminderTracker")) {
            sbs.modid.client.helper.reminder.logic.ReminderTracker.getInstance().onClientTick();
        }
        // Year of the Pig: spent-orb diff, the 90s orb expiry and the shiny pig scan (gated inside).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.ShinyOrbTracker")) {
            sbs.modid.client.helper.yearofthepig.logic.ShinyOrbTracker.getInstance().onClientTick();
        }
        // Recipe Viewer NPC locator: publish / clear its pathfinding waypoint as you travel + arrive.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.NpcLocator")) {
            sbs.modid.client.helper.npc.NpcLocator.getInstance().onClientTick();
        }
        // NPC module: read the scoreboard objective and mark/route to the NPC it names (throttled).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.ObjectiveNpcTracker")) {
            sbs.modid.client.helper.npc.ObjectiveNpcTracker.getInstance().onClientTick();
        }
        // Composter status card: read the open Composter menu (throttled internally).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.ComposterOverlay")) {
            sbs.modid.client.skills.garden.ui.ComposterOverlay.getInstance().onClientTick();
        }
        // Visitor shopping list: record an open visitor's offer, prune by the tab's Visitors widget.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.VisitorOfferStore")) {
            sbs.modid.client.skills.garden.logic.VisitorOfferStore.getInstance().tick();
        }
        // Visitor Timer: the Next Visitor row, the waiting count and the queue alerts (1 s throttle).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.VisitorTimer")) {
            sbs.modid.client.skills.garden.logic.VisitorTimer.getInstance().tick();
        }
        // Greenhouse capture (research): zone / menu / tab logging, one boolean read while off.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.GreenhouseCapture")) {
            sbs.modid.client.skills.garden.logic.GreenhouseCapture.getInstance().tick();
        }
        // Crop Analyzer capture (research): every menu frame while the analyzer is open, any island.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.CropAnalyzerCapture")) {
            sbs.modid.client.skills.garden.logic.CropAnalyzerCapture.getInstance().tick();
        }
        // Farming Tracker: fortune / overbloom / pest chance from the tab widgets (throttled).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.FarmingTracker")) {
            sbs.modid.client.skills.garden.logic.FarmingTracker.getInstance().onClientTick();
        }
        // Crop Milestones: diff the held tool's crop counter and read the milestone menu (gated inside).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.CropMilestoneTracker")) {
            sbs.modid.client.skills.farming.logic.CropMilestoneTracker.getInstance().onClientTick();
        }
        // Mousemat angles: remember the crop of the held tool, so the Mousemat's sign still knows
        // which crop it is being opened for once the Mousemat itself has taken the tool's place.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.MousematAngles")) {
            sbs.modid.client.skills.farming.logic.MousematAngles.getInstance().onClientTick();
        }
        // Garden Level: read the Garden tab widget (throttled; gated on its toggle).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.GardenLevel")) {
            sbs.modid.client.skills.garden.model.GardenLevel.getInstance().onClientTick();
        }
        // Pests: read the Pests widget and fire the pre-cooldown warning (throttled; gated inside).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.PestTracker")) {
            sbs.modid.client.skills.garden.logic.PestTracker.getInstance().onClientTick();
        }
        // Sprayonator: fold the widget's spray row and the held Sprayonator into the card's state.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.SprayTracker")) {
            sbs.modid.client.skills.garden.logic.SprayTracker.getInstance().onClientTick();
        }
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.BuffTracker")) {
            sbs.modid.client.helper.buffs.BuffTracker.getInstance().onClientTick();
        }
        // Farming Session Summary: end the session on leaving the farming islands or going idle.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.FarmingSessionTracker")) {
            sbs.modid.client.skills.farming.session.FarmingSessionTracker.getInstance().onClientTick();
        }
        // Tab info widget: the Bank and Gems rows the sidebar does not carry (throttled).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.TabInfoTracker")) {
            sbs.modid.client.helper.scoreboard.TabInfoTracker.getInstance().onClientTick();
        }
        // Server stats: settle any on-demand ping measurement (/sbs ping, !ping) on the client thread.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.ServerStatsTracker")) {
            sbs.modid.client.ui.hud.logic.ServerStatsTracker.getInstance().onClientTick();
        }
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.EventTimers")) {
            sbs.modid.client.helper.timers.EventTimers.getInstance().onClientTick();
        }
        // Jacob's Contest: the running contest's standing and the [SBS][Jacob] sidebar probe (1/s).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.JacobContestTracker")) {
            sbs.modid.client.skills.farming.contest.JacobContestTracker.getInstance().tick();
        }
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.FarmingSpeed")) {
            sbs.modid.client.skills.farming.logic.FarmingSpeed.getInstance().tick();
        }
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.LaneEndWarning")) {
            sbs.modid.client.skills.farming.logic.LaneEndWarning.getInstance().tick();
        }
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.MilestoneTracker")) {
            sbs.modid.client.helper.milestone.MilestoneTracker.getInstance().onClientTick();
        }
        // Mining Helpers: commissions / HotM / powder off the tab widget (throttled internally to 2/s).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.MiningTracker")) {
            sbs.modid.client.skills.mining.logic.MiningTracker.getInstance().onClientTick();
        }
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.ColdTracker")) {
            sbs.modid.client.skills.mining.logic.ColdTracker.getInstance().onClientTick();
        }
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.AbilityReadyAlert")) {
            sbs.modid.client.combat.cooldowns.AbilityReadyAlert.getInstance().onClientTick();
        }
        // Per-screen opacity: with no screen open, the palette goes back to the global value.
        if (sbs.modid.client.core.api.ScreenAccess.current() == null) {
            sbs.modid.client.ui.theme.ScreenOpacity.leave();
        }
        // Teammate low health: the dungeon sidebar's teammate rows (throttled to 4/s, off by default).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.TeamHealthTracker")) {
            sbs.modid.client.dungeons.teamhealth.logic.TeamHealthTracker.getInstance().onClientTick();
        }
        // Coins per Hour: purse off the sidebar and the idle clock (throttled internally).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.CoinsPerHourTracker")) {
            sbs.modid.client.economy.coinsperhour.logic.CoinsPerHourTracker.getInstance().onClientTick();
        }
        // Fallen Star: time-out and the world probe; returns on a null check with no star up.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.FallenStarTracker")) {
            sbs.modid.client.skills.mining.fallenstar.logic.FallenStarTracker.getInstance().onClientTick();
        }
        // Crystal Hollows lobby clock: rate sampling, the Lobby Day card text and the capture log.
        // Once a second; off the Hollows that is one location read.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.HollowsLobbyWatch")) {
            sbs.modid.client.helper.reminder.logic.HollowsLobbyWatch.getInstance().onClientTick();
        }
        // Mining Event Timer: sidebar resync, stale-event expiry, alerts, history save (1 Hz, gated
        // on the two mining islands).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.MiningEventTracker")) {
            sbs.modid.client.skills.mining.events.logic.MiningEventTracker.getInstance().onClientTick();
        }
        // Tool uses: only re-reads the held item's lore when the held item actually changed.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.ToolDurability")) {
            sbs.modid.client.skills.mining.logic.ToolDurability.getInstance().onClientTick();
        }
        // Gemstones: price what the sacks feed booked, off the cached Bazaar snapshot (throttled).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.GemstoneTracker")) {
            sbs.modid.client.skills.mining.logic.GemstoneTracker.getInstance().onClientTick();
        }
        // Nucleus Run: online time on the Hollows, idle reward blocks, cost windows, the card preview.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.NucleusRunTracker")) {
            sbs.modid.client.skills.mining.nucleus.logic.NucleusRunTracker.getInstance().onClientTick();
        }
        // Jungle Temple cheese waypoint: guardian within 8 blocks, lobby id, run state (every 5 ticks).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.TempleCheese")) {
            sbs.modid.client.skills.mining.nucleus.logic.TempleCheeseWaypoint.getInstance().onClientTick();
        }
        // Powder Ghast: accumulate sightings so the spawn interval can be derived from observation.
        // Nothing is displayed off this yet - the mechanic is unverified and stays "unknown".
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.GhastObserver")) {
            sbs.modid.client.skills.mining.logic.GhastObserver.getInstance().onClientTick();
        }
        // Dev-only: write down what the tab widget / footer / sidebar actually say in each mining
        // area. Costs one boolean read while developer mode is off.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.MiningWidgetCapture")) {
            sbs.modid.client.skills.mining.logic.MiningWidgetCapture.getInstance().onClientTick();
        }
        // Heart of the Mountain: read the tree when its menu is open and its contents changed, and
        // notice a spend against the cached copy. Reads only - nothing is clicked or bought.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.HotmTreeReader")) {
            sbs.modid.client.skills.mining.logic.HotmTreeReader.getInstance().onClientTick();
        }
        // HotM Upgrade Reminder: affordable watched perks, unspent tokens, the HUD line. Alerts only.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.HotmReminder")) {
            sbs.modid.client.skills.mining.logic.HotmReminder.getInstance().onClientTick();
        }
        // Party Commands: fire the delayed half of !reinvite (kick now, invite a few seconds later).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.PartyChatCommands")) {
            sbs.modid.client.social.chat.command.PartyChatCommands.getInstance().onClientTick();
        }
        // Short Commands: send a warp that was held back by the server transfer cooldown.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.TransferCooldown")) {
            sbs.modid.client.core.command.TransferCooldown.getInstance().onClientTick();
        }
        // End block effects: republish the glow/darkness snapshot the chunk mesher reads, and rebuild
        // the geometry when it changed (a setting moved, or the End was entered or left).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.EndVisuals")) {
            sbs.modid.client.helper.visual.logic.EndVisuals.onClientTick();
        }
        // Mist ghost grinding: republish the ghost-aura / white-block dim snapshot on the same terms.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.MistVisuals")) {
            sbs.modid.client.helper.visual.logic.MistVisuals.onClientTick();
        }
        // Dark Mode: republish the world-wide block darkening (the fallback layer under the two
        // above). Its time and brightness halves need no tick - they are read live where they apply.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.DarkMode")) {
            sbs.modid.client.helper.visual.logic.DarkMode.onClientTick();
        }
        // Borderless window: put the window back if the desktop resized or moved it behind our back.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.BorderlessWindow")) {
            sbs.modid.client.helper.visual.logic.BorderlessWindow.onClientTick();
        }
        // The Rift: notice arriving / leaving, then read the clock and the motes purse. Ordered
        // deliberately - the readers gate on the state, so the edge has to be published first or the
        // first tick of a visit reads into a session that has not been reset yet.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.RiftState")) {
            sbs.modid.client.helper.rift.logic.RiftState.getInstance().tick();
        }
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.RiftTime")) {
            sbs.modid.client.helper.rift.logic.RiftTime.getInstance().tick();
        }
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.RiftMotes")) {
            sbs.modid.client.helper.rift.logic.RiftMotes.getInstance().tick();
        }
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.RiftTimeAlerts")) {
            sbs.modid.client.helper.rift.logic.RiftTimeAlerts.getInstance().tick();
        }
        // Blood Effigies: scan the château for the redstone stacks (throttled; gated on the zone).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.BloodEffigyTracker")) {
            sbs.modid.client.combat.vampire.logic.BloodEffigyTracker.getInstance()
                    .tick(Minecraft.getInstance());
        }
        // Sweep: notice that Foraging XP is flowing with no per-chop lines arriving, which is the
        // only evidence there will ever be that Hypixel's detail setting is off.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.SweepTracker")) {
            sbs.modid.client.skills.foraging.logic.SweepTracker.getInstance().tick();
        }
        // Honey trees: settle the clicks waiting for a confirmation, announce what has come back
        // around, and drop what has been ready for an hour. Wall clock throughout - nothing here
        // counts ticks, so this tick only decides how often the clock is looked at.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.HoneyTreeTimers")) {
            sbs.modid.client.skills.foraging.logic.HoneyTreeTimers.getInstance().onClientTick();
        }
        // Forge timers: read the forge menu when it is open, announce finished slots. Wall clock.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.ForgeTimers")) {
            sbs.modid.client.economy.forge.logic.ForgeTimers.getInstance().onClientTick();
        }
        // Diana: age out guesses whose block has stopped looking like ground or that the player has
        // walked past, re-read the mythological nametags, and republish the marker sets. All three
        // gate on the module and on being in the Hub during the event, so this is a field read the
        // rest of the year. Both guesses fit here, throttled, rather than on the particle packet.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.Diana")) {
            sbs.modid.client.combat.diana.logic.DianaGuard.run(sbs.modid.client.combat.diana.logic.DianaGuard.Hook.TICK, "client tick", () -> {
                sbs.modid.client.combat.diana.logic.ArrowGuess.getInstance().onClientTick();
                sbs.modid.client.combat.diana.logic.SpadeGuess.getInstance().onClientTick();
                sbs.modid.client.combat.diana.logic.MythMobTracker.getInstance().onClientTick();
                sbs.modid.client.combat.diana.render.BurrowMarkers.onClientTick();
            });
        }
        // Diana Log Mode: player samples, location / scoreboard / tab changes and the per-second
        // packet counts. Outside the guard on purpose - the capture keeps running when the tracker is
        // disabled, because it is the evidence for why. One volatile read while no capture runs.
        sbs.modid.client.combat.diana.devlog.DianaDevLog.onClientTick();
        // The chat line a Diana failure left owed - sent from here rather than from inside the hook
        // that threw, which can be the chat handler itself. One volatile read when nothing is owed.
        sbs.modid.client.combat.diana.logic.DianaGuard.onClientTick();
        // Crystal Hollows: notice the lobby and the zone you walked into (shared detection), then let
        // the Hollows map sample your trail and keep its file. Gated on the island and throttled -
        // off the Hollows it is a config read and a counter.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.HollowsMap")) {
            sbs.modid.client.core.location.hollows.HollowsDetector.getInstance().onClientTick();
            sbs.modid.client.helper.map.logic.HollowsMapTracker.getInstance().onClientTick();
        }
        // Crystal Hollows Structure Sharing: connect, linger and disconnect, and move what the
        // server sent onto the markers. Off by default; then it is a config read per tick.
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.StructureSharing")) {
            sbs.modid.client.helper.map.logic.StructureSharing.getInstance().onClientTick();
        }
        // Learn the scoreboard area for the keybind island dropdown (throttled; only writes on a
        // genuinely new area).
        sbs$learnArea();
        // Build Tools: hand the integrated server the next slice of a running edit (idle otherwise).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.EditEngine")) {
            sbs.modid.client.helper.build.logic.EditEngine.tick();
        }
        // Build Tools freecam: snap-back checks and camera movement (idle while off).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.Freecam")) {
            sbs.modid.client.helper.build.logic.Freecam.tick();
        }
        // Build Tools: count the shown hologram against the world, a slice per tick (idle without one).
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.tick("tick.HologramCensus")) {
            sbs.modid.client.helper.build.logic.HologramCensus.tick();
        }
        // World-bound storage snapshots (island chests) die with the world they were seen in.
        sbs$trackWorldChange();
        sbs$autoSprint();
    }

    /** Whether the previous tick had a player – a true->false edge is a world leave. */
    @org.spongepowered.asm.mixin.Unique
    private static boolean sbs$hadPlayer;

    @org.spongepowered.asm.mixin.Unique
    private void sbs$trackWorldChange() {
        boolean has = Minecraft.getInstance().player != null;
        if (sbs$hadPlayer && !has) {
            sbs.modid.client.helper.storage.StorageIndex.getInstance().onWorldChange();
            // Coins per Hour: the next purse reading is a new baseline, not a gain or a loss.
            sbs.modid.client.economy.coinsperhour.logic.CoinsPerHourTracker.getInstance().onWorldChange();
            // The inventory on the other side of a hop is a new reading, not a continuation. Without
            // this the warning could still be owed from the previous server, or suppressed by a
            // cooldown started there.
            sbs.modid.client.helper.inventory.logic.FreeSlotWatcher.getInstance().onWorldChange();
            // The send channel dies with the instance. A party selected on one server is not a
            // party on the next, and the first message after a hop is the one nobody checks.
            sbs.modid.client.social.chat.logic.ActiveChannel.getInstance().onWorldChange();
            // Persist any pending lifetime drop tallies before the world (and its data) goes away.
            sbs.modid.client.core.tracker.TrackerStore.flushAll();
            // A Rift-to-Rift hop never changes the island, so the location poll cannot see it - but
            // it is exactly when the clock restarts. Ending the visit here is what stops the next
            // one inheriting the previous one's maximum and session totals.
            sbs.modid.client.helper.rift.logic.RiftState.getInstance().onWorldChange();
            // Mining: the powder baseline is what the session's gain is measured against, so it
            // belongs to one instance and one profile. Carried across a hop it reports the previous
            // server's numbers, and carried across a profile switch it reports a gain that never
            // happened - the tracker's own guard only catches a total that went DOWN.
            sbs.modid.client.skills.mining.logic.MiningTracker.getInstance().onWorldChange();
            sbs.modid.client.skills.mining.logic.ColdTracker.getInstance().onWorldChange();
            // Fallen Star: a star belongs to the lobby it crashed in.
            sbs.modid.client.skills.mining.fallenstar.logic.FallenStarTracker.getInstance().onWorldChange();
            // Crystal Hollows lobby: the lobby being left is what the next join is checked against.
            sbs.modid.client.helper.reminder.logic.HollowsLobbyWatch.getInstance().onWorldChange();
            // Mining events: the running event and its countdown belong to the lobby they ran in.
            sbs.modid.client.skills.mining.events.logic.MiningEventTracker.getInstance().onWorldChange();
            // Gemstones: session counts, rates and every projection built on them.
            sbs.modid.client.skills.mining.logic.GemstoneTracker.getInstance().onWorldChange();
            // Treasure chests: claimed chests are coordinates in the old lobby, and the chest
            // session is reset with the rest of the Powder card.
            sbs.modid.client.skills.mining.treasurechest.logic.TreasureChestTracker.getInstance()
                    .onWorldChange();
            // Nucleus Run: an open reward block, a cost line waiting for its decrease and the
            // inventory baseline belong to one lobby; the run itself spans lobbies and only pauses.
            sbs.modid.client.skills.mining.nucleus.logic.NucleusRunTracker.getInstance().onWorldChange();
            // Jungle Temple cheese waypoint: the guardian anchor belongs to the lobby it was seen in.
            sbs.modid.client.skills.mining.nucleus.logic.TempleCheeseWaypoint.getInstance().onWorldChange();
            // Powder Ghast: drop the live "time since the last one" and start a new session, so no
            // sighting pair spanning the boundary is ever read as a measured interval. The
            // accumulated history is kept - that is what the derivation needs.
            sbs.modid.client.skills.mining.logic.GhastObserver.getInstance().onWorldChange();
            // Sweep: the live value describes the swing you just made on the instance you just
            // left, and the session max belongs to one island. Both are wrong the moment the
            // world changes, so neither survives it.
            sbs.modid.client.skills.foraging.logic.SweepTracker.getInstance().onWorldChange();
            // Honey trees: the PENDING clicks die with the instance they were made on - confirming
            // one against a line from the next server would attach it to the wrong smear. The
            // timers themselves deliberately survive: they describe a cooldown on a world object
            // that keeps running while the player is elsewhere, which is the whole feature.
            sbs.modid.client.skills.foraging.logic.HoneyTreeTimers.getInstance().onWorldChange();
            // Forge timers survive too - the forge runs while the player is elsewhere.
            sbs.modid.client.economy.forge.logic.ForgeTimers.getInstance().onWorldChange();
            sbs.modid.client.skills.foraging.logic.HoneyHologramReader.getInstance().onWorldChange();
            // Critter Finder: entity references belong to the level they were found in, and the
            // Safari is its own instance - a box surviving the hop would hang over nothing.
            sbs.modid.client.skills.hunting.logic.HidingCritterTracker.getInstance().onWorldChange();
            // Gemzie markers: same instance boundary, same reasoning. The waypoints carry the
            // dimension they were built for, so a set surviving the hop would silently stop being
            // drawn rather than visibly moving - which is the harder failure to diagnose.
            sbs.modid.client.skills.hunting.logic.GemzieWaypointPublisher.getInstance().onWorldChange();
            // Snoozle walls: the block reading belongs to the instance it was read on, so a new
            // Safari is read again rather than trusted from the last one.
            sbs.modid.client.skills.hunting.logic.SnoozleWallProbe.getInstance().onWorldChange();
            // Hideyho Finder: a round belongs to the instance it was started on, and the markers
            // carry that instance's dimension - a set surviving the hop would silently stop being
            // drawn rather than visibly moving.
            sbs.modid.client.skills.hunting.hideyho.logic.HideyhoTracker.getInstance().onWorldChange();
            // Floor Drops: the boxes point at entities belonging to the level they were found in,
            // and the particle buffer holds positions in a world that no longer exists. Neither
            // survives the hop.
            sbs.modid.client.helper.floordrop.logic.FloorDropTracker.getInstance().onWorldChange();
            // Croesus reminder: the "am I in the Dungeon Hub" edge. A hop lands you somewhere
            // new, so the next time the hub is seen it is an arrival and the line is owed again.
            sbs.modid.client.dungeons.croesus.logic.CroesusReminder.getInstance().onWorldChange();
            // Ping markers: a ping points at a place on the instance it was dropped on, and half of
            // them point at an entity that does not survive the hop at all. Nothing is worth
            // carrying over - they are seconds old by design.
            sbs.modid.client.helper.ping.PingManager.getInstance().onWorldChange();
            // Build Tools: a hologram mid-placement stops floating (it stays, pinned where it was),
            // and a pending edit preview or running edit belongs to the world that just closed.
            sbs.modid.client.helper.build.logic.BuildSession.onWorldLeave();
            // Diana: burrows belong to one Hub on one server. A store that survived the hop would
            // report the previous lobby's burrows with complete confidence, which is the failure
            // that is hardest to notice because the markers look exactly like real ones. The session
            // totals deliberately survive - hopping lobbies is part of doing the event.
            sbs.modid.client.combat.diana.logic.DianaGuard.run(sbs.modid.client.combat.diana.logic.DianaGuard.Hook.RESET, "world change", () -> {
                sbs.modid.client.combat.diana.logic.BurrowStore.getInstance().clear();
                sbs.modid.client.combat.diana.logic.ArrowGuess.getInstance().reset();
                sbs.modid.client.combat.diana.logic.SpadeGuess.getInstance().reset();
                sbs.modid.client.combat.diana.logic.ChainTracker.getInstance().reset();
                sbs.modid.client.combat.diana.logic.MythMobTracker.getInstance().reset();
                sbs.modid.client.combat.diana.logic.BurrowChat.getInstance().reset();
                sbs.modid.client.combat.diana.logic.SphinxAnswers.getInstance().reset();
                sbs.modid.client.combat.diana.logic.DianaPrompts.reset();
                sbs.modid.client.combat.diana.logic.BurrowDetector.getInstance().reset();
                sbs.modid.client.combat.diana.render.BurrowMarkers.clear();
            });
        }
        sbs$hadPlayer = has;
    }

    /** Locations change only when you travel, so once a second is plenty and costs nothing. */
    @org.spongepowered.asm.mixin.Unique
    private static long sbs$lastAreaCheck;

    @org.spongepowered.asm.mixin.Unique
    private void sbs$learnArea() {
        long now = System.currentTimeMillis();
        if (now - sbs$lastAreaCheck < 1000L) {
            return;
        }
        sbs$lastAreaCheck = now;
        sbs.modid.client.core.location.SkyBlockLocation.learn();
    }

    @org.spongepowered.asm.mixin.Unique
    private void sbs$autoSprint() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ConfigManager.getInstance().get().convenience.autoSprint) {
            return;
        }
        // No player = not in a world; a screen open means the player is not steering anyway, and
        // holding sprint through a menu would resume a sprint you never asked for on close.
        // The open screen is read through GuiStateManager because 26.2 moved it off Minecraft.
        if (minecraft.player == null
                || GuiStateManager.getInstance().getCurrentScreen() != null) {
            return;
        }
        minecraft.options.keySprint.setDown(true);
    }
}
