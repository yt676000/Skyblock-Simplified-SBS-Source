/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.world;

/**
 * Every piece of world-bound state, registered with {@link WorldTransitions} in the order it is
 * reset. A class that declares {@code onWorldChange()} belongs here - {@code WorldScopedTrackersTest}
 * fails the build when one is missing.
 *
 * <p>Each entry resolves its singleton inside the lambda, so registering loads no feature class.
 */
public final class WorldScopedTrackers {

    private WorldScopedTrackers() {}

    /** Called once from the client entry point. */
    public static void registerAll() {
        // Chunk revisions: the old world's chunks are gone; a stale revision must never vouch for one.
        WorldTransitions.register(kind -> ChunkRevisions.clear());
        // Entity index: every bucket holds entities of the world being left.
        // BOOKMARK(merge 2026-10-05): moved here from the old AutoSprintMixin world-leave block (entity-index, item-analysis).
        WorldTransitions.register(kind -> sbs.modid.client.core.entity.EntityIndex.clear());
        // Item analyses belong to the old instance's stacks.
        WorldTransitions.register(kind -> sbs.modid.client.core.item.ItemAnalysis.clear());
        // World-bound storage snapshots (island chests) die with the world they were seen in.
        WorldTransitions.register(kind -> sbs.modid.client.helper.storage.StorageIndex.getInstance().onWorldChange());
        // Craft Helper counts: the next instance is read afresh, island chests included.
        WorldTransitions.register(kind -> sbs.modid.client.economy.crafthelper.logic.CraftHelper.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.economy.prices.BackupPrices.getInstance().onWorldChange());
        // Coins per Hour: the next purse reading is a new baseline, not a gain or a loss.
        WorldTransitions.register(kind -> sbs.modid.client.economy.coinsperhour.logic.CoinsPerHourTracker.getInstance().onWorldChange());
        // The inventory on the other side of a hop is a new reading, not a continuation. Without
        // this the warning could still be owed from the previous server, or suppressed by a
        // cooldown started there.
        WorldTransitions.register(kind -> sbs.modid.client.helper.inventory.logic.FreeSlotWatcher.getInstance().onWorldChange());
        // The send channel dies with the instance. A party selected on one server is not a
        // party on the next, and the first message after a hop is the one nobody checks.
        WorldTransitions.register(kind -> sbs.modid.client.social.chat.logic.ActiveChannel.getInstance().onWorldChange());
        // Persist any pending lifetime drop tallies before the world (and its data) goes away.
        WorldTransitions.register(kind -> sbs.modid.client.core.tracker.TrackerStore.flushAll());
        // A Rift-to-Rift hop never changes the island, so the location poll cannot see it - but
        // it is exactly when the clock restarts. Ending the visit here is what stops the next
        // one inheriting the previous one's maximum and session totals.
        WorldTransitions.register(kind -> sbs.modid.client.helper.rift.logic.RiftState.getInstance().onWorldChange());
        // Mining: the powder baseline is what the session's gain is measured against, so it
        // belongs to one instance and one profile. Carried across a hop it reports the previous
        // server's numbers, and carried across a profile switch it reports a gain that never
        // happened - the tracker's own guard only catches a total that went DOWN.
        WorldTransitions.register(kind -> sbs.modid.client.skills.mining.logic.MiningTracker.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.skills.mining.logic.ColdTracker.getInstance().onWorldChange());
        // Precision Mining: the mined block and its target are coordinates in the old instance.
        WorldTransitions.register(kind -> sbs.modid.client.skills.mining.precision.logic.PrecisionMiningTracker.getInstance().onWorldChange());
        // Fallen Star: a star belongs to the lobby it crashed in.
        WorldTransitions.register(kind -> sbs.modid.client.skills.mining.fallenstar.logic.FallenStarTracker.getInstance().onWorldChange());
        // Crystal Hollows lobby: the lobby being left is what the next join is checked against.
        WorldTransitions.register(kind -> sbs.modid.client.helper.reminder.logic.HollowsLobbyWatch.getInstance().onWorldChange());
        // Mining events: the running event and its countdown belong to the lobby they ran in.
        WorldTransitions.register(kind -> sbs.modid.client.skills.mining.events.logic.MiningEventTracker.getInstance().onWorldChange());
        // Gemstones: session counts, rates and every projection built on them.
        WorldTransitions.register(kind -> sbs.modid.client.skills.mining.logic.GemstoneTracker.getInstance().onWorldChange());
        // Treasure chests: claimed chests are coordinates in the old lobby, and the chest
        // session is reset with the rest of the Powder card.
        WorldTransitions.register(kind -> sbs.modid.client.skills.mining.treasurechest.logic.TreasureChestTracker.getInstance().onWorldChange());
        // Nucleus Run: an open reward block, a cost line waiting for its decrease and the
        // inventory baseline belong to one lobby; the run itself spans lobbies and only pauses.
        // Also resets its MoleReminder, which it owns.
        WorldTransitions.register(kind -> sbs.modid.client.skills.mining.nucleus.logic.NucleusRunTracker.getInstance().onWorldChange());
        // Dungeon Profit: a half-read reward block is finished and the file written; runs end from
        // the dungeon state on the next tick, not here.
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.profit.logic.DungeonProfitTracker.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.combat.kuudraprofit.logic.KuudraProfitTracker.getInstance().onWorldChange());
        // Jungle Temple cheese waypoint: the guardian anchor belongs to the lobby it was seen in.
        WorldTransitions.register(kind -> sbs.modid.client.skills.mining.nucleus.logic.TempleCheeseWaypoint.getInstance().onWorldChange());
        // Powder Ghast: drop the live "time since the last one" and start a new session, so no
        // sighting pair spanning the boundary is ever read as a measured interval. The
        // accumulated history is kept - that is what the derivation needs.
        WorldTransitions.register(kind -> sbs.modid.client.skills.mining.logic.GhastObserver.getInstance().onWorldChange());
        // Sweep: the live value describes the swing you just made on the instance you just
        // left, and the session max belongs to one island. Both are wrong the moment the
        // world changes, so neither survives it.
        WorldTransitions.register(kind -> sbs.modid.client.skills.foraging.logic.SweepTracker.getInstance().onWorldChange());
        // Honey trees: the PENDING clicks die with the instance they were made on - confirming
        // one against a line from the next server would attach it to the wrong smear. The
        // timers themselves deliberately survive: they describe a cooldown on a world object
        // that keeps running while the player is elsewhere, which is the whole feature.
        WorldTransitions.register(kind -> sbs.modid.client.skills.foraging.logic.HoneyTreeTimers.getInstance().onWorldChange());
        // Forge timers survive too - the forge runs while the player is elsewhere.
        WorldTransitions.register(kind -> sbs.modid.client.economy.forge.logic.ForgeTimers.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.skills.foraging.logic.HoneyHologramReader.getInstance().onWorldChange());
        // Critter Finder: entity references belong to the level they were found in, and the
        // Safari is its own instance - a box surviving the hop would hang over nothing.
        WorldTransitions.register(kind -> sbs.modid.client.skills.hunting.logic.HidingCritterTracker.getInstance().onWorldChange());
        // Gemzie markers: same instance boundary, same reasoning. The waypoints carry the
        // dimension they were built for, so a set surviving the hop would silently stop being
        // drawn rather than visibly moving - which is the harder failure to diagnose.
        WorldTransitions.register(kind -> sbs.modid.client.skills.hunting.logic.GemzieWaypointPublisher.getInstance().onWorldChange());
        // Snoozle walls: the block reading belongs to the instance it was read on, so a new
        // Safari is read again rather than trusted from the last one.
        WorldTransitions.register(kind -> sbs.modid.client.skills.hunting.logic.SnoozleWallProbe.getInstance().onWorldChange());
        // Hideyho Finder: a round belongs to the instance it was started on, and the markers
        // carry that instance's dimension - a set surviving the hop would silently stop being
        // drawn rather than visibly moving.
        WorldTransitions.register(kind -> sbs.modid.client.skills.hunting.hideyho.logic.HideyhoTracker.getInstance().onWorldChange());
        // Floor Drops: the boxes point at entities belonging to the level they were found in,
        // and the particle buffer holds positions in a world that no longer exists. Neither
        // survives the hop.
        WorldTransitions.register(kind -> sbs.modid.client.helper.floordrop.logic.FloorDropTracker.getInstance().onWorldChange());
        // Croesus reminder: the "am I in the Dungeon Hub" edge. A hop lands you somewhere
        // new, so the next time the hub is seen it is an arrival and the line is owed again.
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.croesus.logic.CroesusReminder.getInstance().onWorldChange());
        // Ping markers: a ping points at a place on the instance it was dropped on, and half of
        // them point at an entity that does not survive the hop at all. Nothing is worth
        // carrying over - they are seconds old by design.
        WorldTransitions.register(kind -> sbs.modid.client.helper.ping.PingManager.getInstance().onWorldChange());
        // Fairy souls: the log-once set of implausible heads is keyed by position and only grows;
        // a world is the scope it is deduplicated over.
        WorldTransitions.register(kind -> sbs.modid.client.helper.fairysouls.logic.FairySoulLearned.getInstance().onWorldChange());
        // Entity-id keyed combat state. Ids restart with every world, so an entry carried across
        // the hop describes a different entity in the next one - a stranger boxed, an alert
        // suppressed, a hit count or a kill credited to the wrong mob. Each method clears only
        // the id-keyed maps; session totals, calibrations and the carries themselves survive.
        WorldTransitions.register(kind -> sbs.modid.client.combat.damage.logic.DamageEstimator.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.combat.damage.logic.DamageAttribution.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.combat.bestiary.BestiaryTracker.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.combat.carry.logic.CarryCounter.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.combat.slayer.logic.SlayerTracker.getInstance().onWorldChange());
        // Dungeons: a rejoin puts the player back into the same run, so run memory that is a
        // position in the instance (rooms, doors, traps, reward chests, the blood room's clock)
        // survives and is cleared by the run's own end. What is keyed by entity id does not -
        // the rejoin is a new connection with new ids.
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.blood.logic.BloodRoomTracker.getInstance().onWorldChange());
        // The camp clock is seconds long and cannot span a reconnect, so unlike the room it clears.
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.blood.logic.BloodMoveTimer.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.blood.logic.BloodTimingProbe.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.floorseven.logic.M7TickProbe.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.floorseven.logic.M7TickTimers.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.floorseven.logic.DragonTracker.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.floorseven.logic.RelicProbe.getInstance().reset());
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.floorseven.logic.RelicTracker.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.floorthree.logic.GuardianHpTracker.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.bossbar.logic.BossHealthModel.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.livid.logic.LividTracker.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.run.logic.DungeonRoomTracker.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.dungeons.run.logic.PrinceTracker.getInstance().onWorldChange());
        // Shiny orbs: a rejoin never resumes a stale orb. Runs with the module off too.
        WorldTransitions.register(kind -> sbs.modid.client.helper.yearofthepig.logic.ShinyOrbTracker.getInstance().onWorldChange());
        // Sea creatures: a spawn from the last lobby is not announced, boxed or deduped in the next.
        WorldTransitions.register(kind -> sbs.modid.client.skills.fishing.logic.SeaCreatureAnnouncer.getInstance().onWorldChange());
        WorldTransitions.register(kind -> sbs.modid.client.skills.fishing.logic.SeaCreatureDrops.getInstance().onWorldChange());
        // Effective blocks: candidates are positions in the old level.
        WorldTransitions.register(kind -> sbs.modid.client.skills.mining.logic.EffectiveBlockScanner.getInstance().onWorldChange());
        // Frozen corpses: a Mineshaft-to-Mineshaft hop never leaves the area gate.
        WorldTransitions.register(kind -> sbs.modid.client.skills.mining.render.FrozenCorpseHighlight.onWorldChange());
        // Build Tools: a hologram mid-placement stops floating (it stays, pinned where it was),
        // and a pending edit preview or running edit belongs to the world that just closed.
        WorldTransitions.register(kind -> sbs.modid.client.helper.build.logic.BuildSession.onWorldLeave());
        // Diana: burrows belong to one Hub on one server. A store that survived the hop would
        // report the previous lobby's burrows with complete confidence, which is the failure
        // that is hardest to notice because the markers look exactly like real ones. The session
        // totals deliberately survive - hopping lobbies is part of doing the event.
        WorldTransitions.register(kind -> sbs.modid.client.combat.diana.logic.DianaGuard.run(sbs.modid.client.combat.diana.logic.DianaGuard.Hook.RESET, "world change", () -> {
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
        }));
        // Sound spam limit: recorded starts are positions in the world that just closed, and a
        // burst counted there must not cost the first sounds of the next one.
        WorldTransitions.register(kind -> sbs.modid.client.core.sound.SoundControl.onWorldChange());
        // Gyro Helper: the aim, the ring and an open cast probe belong to the old world.
        WorldTransitions.register(kind -> sbs.modid.client.combat.gyro.logic.GyroState.getInstance().onWorldChange());
        // Player Viewer cache: other players' data, held in memory for this session only.
        WorldTransitions.register(kind -> {
            if (kind == WorldTransitions.Transition.DISCONNECT) {
                sbs.modid.client.core.player.PlayerProfileApi.getInstance().onWorldLeave();
            }
        });
    }
}
