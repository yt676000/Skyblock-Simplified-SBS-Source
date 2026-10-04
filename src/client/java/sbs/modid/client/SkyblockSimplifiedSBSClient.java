/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client;

import net.fabricmc.api.ClientModInitializer;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.ApiServer;
import sbs.modid.client.economy.bazaar.logic.BazaarSyncService;
import sbs.modid.client.core.command.SBSCommands;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.ChatPriceCache;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.core.keybind.CommandKeybindManager;
import sbs.modid.client.core.module.ModuleManager;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.logic.SkyBlockRepoRecipeProvider;

/**
 * Client entry point for Skyblock Simplified.
 *
 * <p>{@link ClientModInitializer} is a Fabric Loader entry point and has no native
 * Minecraft replacement, so it stays. Everything below it is built on native
 * Minecraft / Brigadier classes:
 * <ul>
 *   <li>config via {@link ConfigManager} (Minecraft game directory, loaded lazily),</li>
 *   <li>the module registry,</li>
 *   <li>and the client commands, which are executed through a Mixin into
 *       {@code ClientPacketListener} rather than any Fabric command API.</li>
 * </ul>
 */
public class SkyblockSimplifiedSBSClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Create the clean config/sbs/ folder structure (repo/, economy/, gui/) and migrate any old
        // loose files into it. The hidden Development_Stuff/ folder is intentionally NOT created here.
        SBSFiles.prepareBaseDirs();

        // Prepare the config manager (file is read lazily on first use).
        ConfigManager.getInstance();

        // Block-break listeners. Lambdas, so each feature's class loads on the first break, not here.
        sbs.modid.client.core.player.BlockBreakEvents.register((pos, state) ->
                sbs.modid.client.dungeons.secretroutes.logic.SecretRoutesManager.getInstance()
                        .onBlockBroken(pos, state));
        // Mimic auto-announce watches for a broken trapped chest on a mimic floor (opt-in).
        sbs.modid.client.core.player.BlockBreakEvents.register((pos, state) ->
                sbs.modid.client.dungeons.run.logic.MimicDetector.getInstance().onBlockBroken(pos, state));
        sbs.modid.client.core.player.BlockBreakEvents.register((pos, state) ->
                sbs.modid.client.skills.farming.logic.FarmingSpeed.getInstance().onBlockBroken(pos, state));
        sbs.modid.client.core.player.BlockBreakEvents.register((pos, state) ->
                sbs.modid.client.skills.farming.logic.LaneEndWarning.getInstance().onBlockBroken(pos, state));
        // Farming Session Summary: a crop broken with a farming tool starts / keeps a session alive.
        sbs.modid.client.core.player.BlockBreakEvents.register((pos, state) ->
                sbs.modid.client.skills.farming.session.FarmingSessionTracker.getInstance()
                        .onBlockBroken(pos, state));
        // NB: the per-account-profile caches (StorageIndex / LoadoutsOverlay / BazaarOrderTracker)
        // register themselves with ProfileContext in their own constructors – doing it here would
        // force those classes to load before the item registry is bound (LoadoutsOverlay builds
        // ItemStack fallbacks in a static field → "Components not bound yet" crash).

        // Module registry (placeholder categories for now).
        ModuleManager moduleManager = ModuleManager.getInstance();

        // Command keybinds registry (config is read lazily on first use).
        CommandKeybindManager.getInstance();

        // Restore persisted developer mode (and generate the hidden dev structure if it was left on).
        // DEV-ONLY: loads the dev flag
        sbs.modid.client.core.dev.DevMode.init();

        // NB: the config option index is deliberately NOT built here. This entrypoint runs from
        // inside Minecraft's own constructor, where getInstance().options is still null - a module
        // whose settings mention the render distance throws, and an index built now would simply be
        // missing that module's options. It is built on the first client tick instead
        // (OptionIndex.auditOnce, driven from the tick hook).

        // Load the bundled dungeon room database (from mod resources) into memory.
        sbs.modid.client.dungeons.rooms.DungeonRoomDatabase.load();

        // Oruo's quiz answers. Ships EMPTY - every row has to come from a question seen in a real
        // run, because a wrong answer fails the room for the whole party. Backend-refreshed so a new
        // question does not need a release.
        sbs.modid.client.dungeons.puzzle.logic.QuizAnswers.load();

        // Load the bundled island maps (from mod resources) into memory.
        sbs.modid.client.helper.map.logic.MapDatabase.load();

        // Fairy Soul coordinates: bundled copy now, cached + backend refresh behind it.
        sbs.modid.client.helper.fairysouls.logic.FairySoulDatabase.load();

        // Shipped location presets (Galatea honey trees, hives, beacons). Bundled only for now -
        // there is no endpoint yet, and the store treats that as a normal state.
        sbs.modid.client.skills.foraging.logic.WaypointPresetDatabase.load();

        // Quest walkthroughs (steps, waypoints, item lists) - bundled, so no licence token.
        sbs.modid.client.helper.quest.logic.QuestDatabase.load();

        // The accessory catalogue behind "what am I missing" - generated from Hypixel's own keyless
        // item resource, so the bundled copy is authoritative and needs no licence either.
        sbs.modid.client.helper.inventory.logic.AccessoryCatalog.load();

        // Minion catalog + modifier tables (bundled-only until a backend route exists).
        sbs.modid.client.economy.minions.logic.MinionCatalogs.load();

        // The shard catalogue behind the missing-shards list. Generated from Hypixel's keyless
        // Bazaar snapshot, which is the only public source that lists all 320 - the items resource
        // carries none. Bundled, so "what shards exist" is answerable before the first frame and is
        // never a function of what the player happens to have opened.
        sbs.modid.client.skills.hunting.logic.ShardCatalog.load();

        // Heart of the Mountain perk catalogue. Ships with perk identities and NO costs - Hypixel
        // publishes none, so they are learned from the menu's own tooltips instead of guessed.
        sbs.modid.client.skills.mining.logic.HotmCatalog.load();
        sbs.modid.client.skills.mining.logic.HotmStrategies.load();

        // Rift per-area clock rules (drain multipliers, minimum-to-enter): same loader as the souls.
        sbs.modid.client.helper.rift.logic.RiftAreas.load();

        // Enigma Soul coordinates + instructions, and the chat pattern that reads the collection
        // count off "You have found 31/52 Souls in the Rift Dimension!".
        sbs.modid.client.helper.rift.logic.EnigmaSoulDatabase.load();
        sbs.modid.client.helper.rift.logic.EnigmaSoulTracker.getInstance().register();

        // Custom Scoreboard element patterns: bundled copy now, cached + backend refresh behind it,
        // so a Hypixel reword ships as a data bump rather than a mod release.
        sbs.modid.client.helper.scoreboard.ScoreboardElements.load();

        // Diana's readable constants: the particle signatures, the arrow bands, the Hub box and the
        // spade's curve. Bundled-only for now and every value in it unverified, which is exactly why
        // it is a document - a capture corrects it with a JSON edit rather than a release.
        sbs.modid.client.combat.diana.logic.DianaParticles.load();
        sbs.modid.client.skills.farming.logic.FarmDropTracker.load();

        // Load the player's personal route waypoints (config/sbs/Routes.json).
        sbs.modid.client.dungeons.run.logic.DungeonRouteStore.load();

        // Load the Secret Routes module's recorded routes (config/sbs/secretroutes.txt).
        sbs.modid.client.dungeons.secretroutes.logic.SecretRouteStore.load();

        // Load Mining Routes from config/sbs/miningroutes.txt (migrates legacy config routes once).
        sbs.modid.client.skills.mining.logic.MiningRouteStore.load();

        // Load the player's recorded Kuudra pearl throws (config/sbs/kuudrapearls.txt).
        sbs.modid.client.combat.kuudra.logic.PearlStore.load();

        // Restore the calibrated 32-grid origin from the config.
        sbs.modid.client.dungeons.run.model.DungeonGrid.loadFromConfig();

        // Build the native Brigadier command dispatcher (no Fabric command API).
        SBSCommands.init();

        // Start the local-only HTTP API (127.0.0.1:8765) for external dev tooling.
        // The GUI state itself is captured on the client thread via GuiTrackingMixin.
        ApiServer.getInstance().start();

        // Background Bazaar order sync (~5s), independent of the Bazaar GUI being open.
        // Orders are scanned on the client thread by BazaarOrderTracker (via GuiTrackingMixin).
        BazaarSyncService.getInstance().start();

        // Background price caches. Starting them costs nothing: LBIN fetches only while one of its
        // listed consumers is switched on, and the Bazaar cache only while something is actually
        // reading prices - see its class comment for why it stopped asking the config that question.
        LbinCache.getInstance().start();
        BazaarPriceCache.getInstance().start();

        // Persistent chat-fed price cache (loaded from disk so prices survive restarts).
        ChatPriceCache.getInstance().load();

        // SkyBlock item catalogue for the Recipe Viewer (official API; disk-cached, weekly refresh).
        SkyBlockItemCatalog.getInstance().start();
        // Supplemental viewer entries: NPCs (shift-click = locator) and mobs, searchable like items.
        sbs.modid.client.economy.recipe.logic.CatalogExtraEntries.register();

        // Pre-populated SkyBlock recipe database (one-time background download, then disk cache).
        SkyBlockRepoRecipeProvider.getInstance().start();

        // Bazaar enchantments: make them searchable in the viewer + expose their combine recipe.
        sbs.modid.client.economy.recipe.logic.EnchantmentRecipeProvider.getInstance().start();

        // SBS IRC chat: long-poll receiver (idles while the Chat Options toggle is off).
        sbs.modid.client.social.chat.logic.IrcClient.getInstance().start();

        // AH flip alerts: long-poll receiver (idles while the module toggle is off).
        sbs.modid.client.economy.auctions.logic.AhFlipClient.getInstance().start();

        // SBS Players presence: heartbeat + lookup for the nametag badge (idles while its toggle is off).
        sbs.modid.client.social.presence.SbsPresence.getInstance().start();

        // Theme: derive the whole SBS palette from the configured base colours once at startup.
        sbs.modid.client.ui.theme.SBSTheme.refreshFromConfig();

        // Texture Pack module: the classic item-model map (for the enforced-pack bypass). The fallback
        // pack is Minecraft's own cached server pack; only the copy old builds downloaded is removed.
        sbs.modid.client.helper.texture.logic.SkyblockItemModels.start();
        sbs.modid.client.helper.texture.logic.HypixelPackFallback.removeLegacyCopy();

        // Collection Tracker: tier requirements from the official resource (disk-cached weekly).
        sbs.modid.client.skills.collection.CollectionCatalog.start();

        // Borderless window: re-apply the persisted toggle once the window exists.
        sbs.modid.client.helper.visual.logic.BorderlessWindow.init();

        // Dungeon secrets: the one collected-secret record both waypoint renderers hide by.
        sbs.modid.client.dungeons.run.logic.CollectedSecrets.init();

        // Custom title bar: same deal - the caption colours go on as soon as there is a window to
        // put them on (the theme refresh above ran before it existed).
        sbs.modid.client.helper.visual.logic.WindowTitleBar.init();

        // Build hologram (Garden Blueprint, Build Tools): the translucent ghost block models are drawn
        // in the level render pass, which needs a subscription rather than the HUD hook the outlines use.
        sbs.modid.client.core.build.render.GhostModels.register();

        // Party highlight: drop a persisted roster older than the 5-min offline-kick window.
        sbs.modid.client.social.party.logic.PartyTracker.getInstance().clearIfStale();
        // Restore a persisted send channel, or leave the default. After the party roster above, so a
        // selection restored here is already sitting beside a roster that has had its own staleness
        // check - the two disagreeing is what a stale party channel looks like.
        sbs.modid.client.social.chat.logic.ActiveChannel.getInstance().load();

        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Client initialized – {} module categories registered.",
                moduleManager.getCategories().size());
    }
}
