/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker;
import sbs.modid.client.economy.recipe.logic.ScrapedRecipeStore;
import sbs.modid.client.economy.recipe.logic.SearchHighlightState;
import sbs.modid.client.economy.recipe.logic.SupercraftHelper;

/**
 * Feeds the {@link GuiStateManager} from the client thread:
 * <ul>
 *   <li>{@code setScreenAndShow} – records the active screen whenever it changes
 *       (this version has no public {@code screen} field, so we track it here).</li>
 *   <li>{@code tick} – rebuilds the GUI state snapshot once per client tick.</li>
 * </ul>
 *
 * <p>Both run on the client thread, which is exactly where Minecraft objects may be
 * read; the captured snapshot is then served to the HTTP layer without any further
 * Minecraft access.
 */
@Mixin(Minecraft.class)
public class GuiTrackingMixin {

    @Inject(method = "setScreenAndShow", at = @At("HEAD"))
    private void skyblockSimplified$trackScreen(Screen screen, CallbackInfo ci) {
        GuiStateManager.getInstance().setCurrentScreen(screen);
        // A screen change always releases the overlay search bar's keyboard focus.
        SearchHighlightState.getInstance().setSearchFocused(false);
        // ...and always closes an open "click again to confirm" window. A confirmation is consent to
        // one action in one menu; letting it survive into the next menu would turn a refusal the
        // player already answered into a free pass somewhere they never saw the question.
        sbs.modid.client.helper.itemprotection.logic.ProtectionConfirm.clear();
        // A storage page just closed (or changed): write its last change now, not after the throttle.
        sbs.modid.client.helper.storage.StorageIndex.getInstance().flushPending();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void skyblockSimplified$tickGuiState(CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        // Index the config options (and audit their ids) on the first tick - the earliest moment the
        // client is complete enough for every module to be able to describe its own settings.
        sbs.modid.client.ui.settings.OptionIndex.auditOnce();
        // Account/profile detection first, so the per-profile stores point at the right folder before
        // anything reads or writes them this tick.
        sbs.modid.client.core.config.ProfileContext.getInstance().tick(minecraft);
        // Alert upkeep: releases deferred speech once its gap has passed, expires the shared title
        // and hands the audio device back when it has been idle.
        sbs.modid.client.core.alert.Alerts.tick();
        sbs.modid.client.core.alert.AlertNotice.tick();
        GuiStateManager.getInstance().tick(minecraft);
        sbs.modid.client.core.dev.EntityProbe.getInstance().tick(minecraft);
        // Builds the item icons the catalogue asked for, once a level means components are bound.
        sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons.getInstance().tick(minecraft);
        sbs.modid.client.skills.foraging.beacon.logic.BeaconBeatReader.getInstance()
                .tick(minecraft);
        // Floating windows: write a moved, resized or minimized window to gui/windows.json once the
        // change has settled - a drag costs one file write, not one per frame.
        sbs.modid.client.ui.window.WindowMemory.tick();
        // Throttled caches: write a change deferred inside the save interval once it has passed.
        sbs.modid.client.helper.storage.StorageIndex.getInstance().tick();
        sbs.modid.client.helper.inventory.logic.AccessoryIndex.getInstance().tick();
        sbs.modid.client.skills.hunting.logic.ShardOwnership.getInstance().tick();
        // Item Protection: write a settled change to the protected-items list. Same deal - marking a
        // dozen items costs one file write, and on almost every tick this does nothing at all.
        sbs.modid.client.helper.itemprotection.logic.ProtectedItems.getInstance().tick();
        // Hide Nearby Players: who is not drawn this tick, so the per-frame render check is a lookup.
        sbs.modid.client.helper.hideplayers.logic.PlayerHiding.tick(minecraft);
        // Museum Helper: read the open museum category page (read-only) when its contents change.
        sbs.modid.client.helper.museum.logic.MuseumMenuReader.getInstance().tick(minecraft);
        // Equipment in the inventory: read the four pieces off the Equipment menu (read-only).
        sbs.modid.client.helper.inventory.logic.EquipmentStore.getInstance().tick(minecraft);
        // Bingo Card Overlay: the Bingo marker on your tab row (2s) and the card menu when it changes.
        sbs.modid.client.helper.bingo.logic.BingoMenuReader.getInstance().tick(minecraft);
        // Trophy Fish: read Odger's Trophy Fishing menu (read-only) when its contents change.
        sbs.modid.client.skills.trophyfish.logic.TrophyFishTracker.getInstance().tick(minecraft);
        // Player Notes: write a settled note edit, learn noted players' account ids from the world
        // and tab list, and warn about noted dungeon teammates / nearby players. Scan is 1/s.
        sbs.modid.client.social.notes.logic.PlayerNotesWarner.getInstance().tick(minecraft);
        // Custom Scoreboard: the one-time "your old line order was migrated" notice, held until
        // there is a player to send it to.
        sbs.modid.client.helper.scoreboard.ScoreboardLayoutMigration.tickNotice(minecraft);
        // Privacy: the one-time "everything now starts switched off" notice, same deal - it waits
        // for a player to talk to, then never runs again for this account.
        sbs.modid.client.core.licence.privacy.PrivacyNotice.tick();
        // First-run wizard / update showcase: waits for a settled world with no other screen up, and
        // defers rather than drops when the moment is wrong.
        sbs.modid.client.ui.wizard.logic.WizardTrigger.tick();
        BazaarOrderTracker.getInstance().tick(minecraft);
        ScrapedRecipeStore.getInstance().tick(minecraft);
        // Bits Shop: record the offers on whichever category page is open, so the coins-per-bit
        // comparison can reach the items sitting in the sub-menus that are not.
        sbs.modid.client.economy.bitsshop.BitsShop.learn(minecraft);
        // NPC flips: learn what an open NPC shop charges (read-only), and write a settled change.
        sbs.modid.client.economy.npcshop.logic.NpcShopReader.tick(minecraft);
        // Minion Calculator: crafted tiers + slot limit from the Crafted Minions menu, fuel and
        // upgrades from any open minion GUI, and the island's placed minions by stand skin.
        sbs.modid.client.economy.minions.logic.MinionStateCapture.tick(minecraft);
        SupercraftHelper.getInstance().tick(minecraft);
        sbs.modid.client.ui.hud.logic.PetTracker.getInstance().tick(minecraft);
        // Mayor election: is one running, has this profile voted, and is a reminder due.
        sbs.modid.client.economy.mayor.MayorVoteTracker.getInstance().tick(minecraft);
        // Chocolate Factory: read the open factory menu (read-only) for the payback ranking, the
        // strays and the barn. Gated on the menu's state id, so a redraw costs a comparison.
        sbs.modid.client.helper.chocolate.logic.ChocolateFactory.getInstance().tick(minecraft);
        // Experimentation Table: scan the open minigame board (read-only) for the helper overlays.
        sbs.modid.client.helper.experiment.logic.ExperimentationTable.getInstance().tick(minecraft);
        // Beacon Tuning: read Galatea's beacon menu (read-only) and work out which option fits the beat.
        sbs.modid.client.skills.foraging.logic.BeaconTuning.getInstance().tick(minecraft);
        // Commission Route: resolve where each running commission is done and publish the waypoints
        // the existing pathfinder routes over. Costs one comparison per tick with none running.
        sbs.modid.client.skills.mining.logic.CommissionRoute.getInstance().tick(minecraft);
        // Bestiary Tracker: read the open Bestiary menu (baseline) + self-count kills between opens.
        sbs.modid.client.combat.bestiary.BestiaryTracker.getInstance().onClientTick();
        // Full Inventory Warning: count the empty main-inventory slots. The count itself is on a
        // 300ms throttle, so all but every sixth tick costs a config read and a clock comparison.
        sbs.modid.client.helper.inventory.logic.FreeSlotWatcher.getInstance().tick(minecraft);
        // Loadouts: after equipping a loadout, bind the armor that lands on the body to THAT slot.
        // Has to run outside the menu - equipping closes it, and the armor arrives afterwards.
        sbs.modid.client.helper.loadouts.LoadoutsOverlay.getInstance().onClientTick();
        // Pets: only to end "Edit" mode when the menu is gone - the menu itself is read while drawn.
        sbs.modid.client.helper.pets.PetsOverlay.getInstance().onClientTick();
        // SBS Wardrobe: same - only ends "Show Hypixel menu" once the Armor Sets menu is gone.
        sbs.modid.client.helper.wardrobe.ArmorSetsOverlay.getInstance().onClientTick();
        // Dungeon terminal solver: scans the open terminal menu and marks the slots to click.
        sbs.modid.client.dungeons.terminal.TerminalSolver.getInstance().tick(minecraft);
        // Dungeon puzzle solver: reads the puzzle state (e.g. blaze health) and marks the solution.
        sbs.modid.client.dungeons.puzzle.PuzzleSolver.getInstance().tick(minecraft);
        sbs.modid.client.dungeons.puzzle.logic.PuzzleCoordinator.getInstance().tick(minecraft);
        // Hunting Box: re-reads the box while it is open (throttled), does nothing anywhere else.
        sbs.modid.client.skills.hunting.logic.HuntingBoxScanner.getInstance().tick(minecraft);
        // Attribute Menu: records what the open page says about each shard, so the missing list has
        // something to be the complement of. Gated on its own setting and on the menu's title.
        sbs.modid.client.skills.hunting.logic.AttributeMenuReader.getInstance().tick(minecraft);
        // Dungeon room detection: cell-change gated, cached – never touches the render loop.
        sbs.modid.client.dungeons.run.logic.DungeonRoomTracker.getInstance().tick(minecraft);
        sbs.modid.client.dungeons.run.logic.WitherDoorTracker.getInstance().tick(minecraft);
        // Dungeon run state (floor/phase/class/secrets/rooms): single source for every dungeon feature.
        sbs.modid.client.dungeons.run.logic.DungeonStateManager.getInstance().tick(minecraft);
        // Trap Highlighter: fills the tripwire/dispenser index against a per-tick budget on entering a
        // run, then does nothing at all - the chunk and block hooks keep it current from there.
        // Deliberately after the state manager, which is the gate it reads.
        sbs.modid.client.dungeons.traps.logic.TrapIndex.getInstance().tick(minecraft);
        // Mimic announce: resets its once-per-run guard when the run ends (no per-tick work otherwise).
        sbs.modid.client.dungeons.run.logic.MimicDetector.getInstance().tick(minecraft);
        // Positional Messages: fire a message when near a saved coordinate (off by default).
        sbs.modid.client.dungeons.run.logic.PositionalMessages.getInstance().tick(minecraft);
        // Secret Routes: sample the walk trail while a scan runs + track walked-past points (light).
        sbs.modid.client.dungeons.secretroutes.logic.SecretRoutesManager.getInstance().tick(minecraft);
        // Immediately after it and before the pathfinder below: the manager decides this tick which
        // secrets are still uncollected, and the router must publish that answer before the search
        // reads it, or every collected secret is routed to for one more tick.
        sbs.modid.client.dungeons.secretroutes.logic.SecretRouting.getInstance().tick(minecraft);
        // Dev secret recorder: tracks room bats while its scan is armed (no-op otherwise).
        sbs.modid.client.core.dev.SecretScanner.getInstance().tick(minecraft);
        // Menu probe: writes a capture when the open menu changes, but only while armed by hand.
        sbs.modid.client.core.dev.MenuProbe.getInstance().tick(minecraft);
        // Layout Recorder (dev, default off): one boolean check when off.
        sbs.modid.client.core.dev.LayoutRecorder.getInstance().tick(minecraft);
        // Shard dump: snapshots a recognised shard menu (or any container, once armed) so
        // /sbs sharddump can print what the resolver made of every slot after it is closed - chat
        // cannot be opened inside the menus it exists to capture.
        sbs.modid.client.core.dev.ShardDump.getInstance().tick(minecraft);
        // Pathfinding (dev): spends a bounded node budget per tick, so block reads stay on this
        // thread and a long search costs ticks instead of freezing one.
        // Jump pads: learn launches (slime block -> landing) before the pathfinder reads them.
        sbs.modid.client.core.pathfinding.JumpPads.getInstance().tick(minecraft);
        sbs.modid.client.core.pathfinding.PathfindingManager.getInstance().tick(minecraft);
        // Quest Guide: resume a quest after a restart, and keep its waypoint on the current step.
        sbs.modid.client.helper.quest.logic.QuestGuideTicker.tick(minecraft);

        // SkyBlock Map: drives the warp/waypoint trip a map click started (no work when idle).
        sbs.modid.client.helper.map.logic.MapNavigation.getInstance().tick(minecraft);

        // Fairy Souls: notices island changes and keeps the routing candidate set current.
        sbs.modid.client.helper.fairysouls.logic.FairySoulTracker.getInstance().tick(minecraft);
        sbs.modid.client.helper.fairysouls.logic.FairySoulRouting.getInstance().tick(minecraft);
        // Location presets: publish the island's marker groups, clear them on leaving. Compares the
        // island name and one generation counter while nothing has changed, which is the usual case.
        sbs.modid.client.skills.foraging.logic.WaypointPresetPublisher.getInstance().onClientTick();
        // Gemzie spots: the same shape one area over - publish the Critter Safari's markers while
        // inside it, clear them on the way out. Two field reads and a return while nothing moved.
        sbs.modid.client.skills.hunting.logic.GemzieWaypointPublisher.getInstance().onClientTick();
        // Ping markers: age the temporary markers out and keep the ones stuck to a mob on their mob.
        // Returns on the first line while no ping is out, which is nearly every tick.
        sbs.modid.client.helper.ping.PingManager.getInstance().onClientTick();
        // See-through window: polled rather than event-driven, so a key released while the game was
        // not the focused window is still noticed. Costs two field reads while unbound.
        sbs.modid.client.helper.visual.logic.WindowOpacity.tick(minecraft);
        // Far Terrain: swap island stores, widen the chunk cache, serve remembered chunks.
        sbs.modid.client.helper.terrain.FarTerrainManager.getInstance().tick(minecraft);
        // Last: it hands memory back, and everything above should have had its say about what it
        // still needs before anything is taken away.
        sbs.modid.client.core.memory.MemoryGuard.tick(minecraft);
    }
}
