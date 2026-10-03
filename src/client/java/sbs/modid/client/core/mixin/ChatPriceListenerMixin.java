/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.social.chat.logic.ChatAccess;
import sbs.modid.client.economy.prices.ChatPriceCache;

/**
 * Feeds every chat line the client displays through the {@link ChatPriceCache} so Bazaar order-setup
 * messages instantly update the shared local price cache.
 *
 * <p>Hooks all three {@link ChatComponent} entry points – server system, client system and player
 * messages – so it fires no matter which channel Hypixel sends "[Bazaar] … Setup!" through (the
 * earlier single-channel hook missed it). Read-only: it never cancels or alters the message.
 *
 * <p>As a side effect each hook registers the live {@link ChatComponent} instance in
 * {@link ChatAccess} – the version-independent chat handle used by the chat-copy feature (the
 * {@code Gui} accessor for it moved between 26.1.2 and 26.2).
 */
@Mixin(ChatComponent.class)
public class ChatPriceListenerMixin {

    @Inject(method = "addServerSystemMessage", at = @At("HEAD"))
    private void skyblockSimplified$serverSystem(Component message, CallbackInfo ci) {
        skyblockSimplified$capture(message);
    }

    @Inject(method = "addClientSystemMessage", at = @At("HEAD"))
    private void skyblockSimplified$clientSystem(Component message, CallbackInfo ci) {
        skyblockSimplified$capture(message);
    }

    @Inject(method = "addPlayerMessage", at = @At("HEAD"))
    private void skyblockSimplified$player(Component message, MessageSignature signature, GuiMessageTag tag,
                                           CallbackInfo ci) {
        skyblockSimplified$capture(message);
    }

    private void skyblockSimplified$capture(Component message) {
        ChatAccess.set((ChatComponent) (Object) this);
        if (message != null) {
            String text = message.getString();
            // Chat probe (dev): while armed, records every line verbatim - first, so a capture is
            // never missing a line an exception further down would have skipped. One boolean read
            // the rest of the time.
            sbs.modid.client.core.dev.ChatProbe.getInstance().onChat(text, message);
            ChatPriceCache.getInstance().parseChat(text);
            // Bazaar orders: Setup!/filled/Claimed/Cancelled lines keep the tracked orders current
            // without the orders menu ever being reopened.
            sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker.getInstance().onChat(text);
            // Coins per Hour: the lines that explain a purse change (bank, Bazaar, NPC, auction).
            sbs.modid.client.economy.coinsperhour.logic.CoinsPerHourTracker.getInstance().onChat(text);
            sbs.modid.client.ui.hud.logic.PetTracker.getInstance().parseChat(text);
            // Mayor election: a vote confirmed in chat, for votes not cast through this client.
            sbs.modid.client.economy.mayor.MayorVoteTracker.getInstance().parseChat(text);
            // Far Terrain: whose garden this is decides whether it may be written to disk.
            sbs.modid.client.helper.terrain.FarTerrainOwnership.onChat(text);
            sbs.modid.client.combat.cooldowns.AbilityCooldowns.getInstance().parseChat(text);
            // Ability Ready Alert: "X is now available!" and the use line that teaches its cooldown.
            sbs.modid.client.combat.cooldowns.AbilityReadyAlert.getInstance().onChat(text);
            sbs.modid.client.social.chat.command.PartyChatCommands.getInstance().parseChat(text);
            // Coordinates someone posted ("x: 187, y: 120, z: -430") become world waypoints.
            sbs.modid.client.social.chat.logic.ChatWaypoints.getInstance().onChat(text);
            // Hoppity's Hunt: which of the day's eggs are up, and the [SBS][Hoppity] trail
            // the locator work still to be built needs. Every pattern it matches came out of
            // real logs, including the guard against another player quoting the line.
            sbs.modid.client.helper.hoppity.logic.HoppityChat.Event hoppity =
                    sbs.modid.client.helper.hoppity.logic.HoppityChat.getInstance().onChat(text);
            if (hoppity != null) {
                // The evidence trail for the locator work still to be built: the position a
                // collection happened at is what a spot catalogue would learn from, so logging it
                // now means the next Spring produces that data whether the catalogue exists or not.
                // Logged HERE and not inside HoppityChat: the parser and its state machine are unit
                // tested, and reaching for Minecraft.getInstance() in them made that impossible.
                var player = net.minecraft.client.Minecraft.getInstance().player;
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Hoppity] {} meal={} name={} rarity={} at {} on {}",
                        hoppity.kind(), hoppity.meal(), hoppity.name(), hoppity.rarity(),
                        player == null ? "no player" : player.blockPosition().toShortString(),
                        sbs.modid.client.core.location.SkyBlockLocation.island());
            }
            // Short Commands: remember the last inviter so a bare /pa can accept without a name.
            sbs.modid.client.core.command.ShortCommands.onChat(text);
            // Party highlight: maintain the Hypixel party roster from the join/left/roster lines.
            sbs.modid.client.social.party.logic.PartyTracker.getInstance().parseChat(text);
            // Garden visitor Bazaar buttons: explain a Cookie Buff refusal right after their click.
            sbs.modid.client.skills.garden.ui.VisitorBazaarButtons.getInstance().onChat(text);
            // Visitor shopping list: the accept line drops that visitor's offer at once.
            sbs.modid.client.skills.garden.logic.VisitorOfferStore.getInstance()
                    .onChat(sbs.modid.client.skills.garden.logic.VisitorMenu.plain(text));
            // Greenhouse capture (research): greenhouse / mutation lines. Off = one boolean read.
            sbs.modid.client.skills.garden.logic.GreenhouseCapture.getInstance().onChat(text);
            // Crop Analyzer capture: every line while the analyzer is open and 10 s after.
            sbs.modid.client.skills.garden.logic.CropAnalyzerCapture.getInstance().onText("chat", text);
            // Quest Guide: NPC dialogue is what advances a quest step (all client-side).
            sbs.modid.client.helper.quest.logic.QuestTracker.getInstance().onChatLine(text);
            // Quest capture (dev): records NPC dialogue and quest bookkeeping while a session is
            // running. Returns on a boolean the rest of the time.
            sbs.modid.client.core.dev.QuestCapture.getInstance().onChat(text, message);
            // Fishing: catches, shards, drops and the sack breakdown (whose items are in the
            // hover text, hence the full component).
            sbs.modid.client.skills.fishing.logic.FishingTracker.getInstance().onChat(text, message);
            // Sea Creature Announcer: the same spawn sentence, restated in the player's own words.
            // Its own module and its own toggle, so it is hooked here rather than off the tracker.
            sbs.modid.client.skills.fishing.logic.SeaCreatureAnnouncer.getInstance().onChat(text);
            // Jacob's Contest: the start and Anita lines, plus the probe for the result lines.
            sbs.modid.client.skills.farming.contest.JacobContestTracker.getInstance().onChat(text);
            // Rare farming drops: the RARE DROP! family while farming, counted and announced.
            sbs.modid.client.skills.farming.logic.FarmDropTracker.getInstance()
                    .onChat(sbs.modid.client.core.util.PlainText.strip(text));
            // Trophy Fish: the catch line, counted on top of the last Odger menu sync.
            sbs.modid.client.skills.trophyfish.logic.TrophyFishTracker.getInstance().onChat(text);
            // Layout Recorder (dev): a chat line between two path steps ("[Harp] Selected song: ...").
            sbs.modid.client.core.dev.ScreenOpeners.getInstance()
                    .onChat(sbs.modid.client.core.util.PlainText.strip(text));
            // Cake buff timers: the "Yum! You gain +N <Stat> for 48 hours!" eat / refresh lines.
            sbs.modid.client.helper.buffs.CakeBuffStore.getInstance().onChat(text);
            // Consumable Timers: consume / "expires in" / "has expired!" / BUFF! lines.
            sbs.modid.client.helper.buffs.consumables.logic.ConsumableStore.getInstance().onChat(text);
            // Diana: chat is the authority on what happened to a burrow - the particles only ever
            // said one was there. Also the chain bookkeeping, the loot tallies, and a rare creature
            // somebody in the party has just posted the coordinates of.
            // Fenced: a Diana parser that throws is logged once and stands down instead of taking
            // the chat packet handler - and the client - with it.
            final String dianaLine = text;
            sbs.modid.client.combat.diana.logic.DianaGuard.run(sbs.modid.client.combat.diana.logic.DianaGuard.Hook.CHAT, dianaLine, () -> {
                sbs.modid.client.combat.diana.logic.BurrowChat.getInstance().onChat(dianaLine);
                sbs.modid.client.combat.diana.logic.RareCreatureShare.getInstance().onChat(dianaLine);
                sbs.modid.client.combat.diana.logic.SphinxAnswers.getInstance().onChat(dianaLine);
            });
            // Collection Tracker: count-along from the "[Sacks]" breakdown (hover text).
            sbs.modid.client.skills.collection.CollectionTracker.getInstance().onChat(text, message);
            // Slayer: quest events (started/slain/failed), rare drops and the sack breakdown.
            sbs.modid.client.combat.slayer.logic.SlayerTracker.getInstance().onChat(text, message);
            // Carry Counter: the quest-complete line is the exact kill signal for your own boss.
            sbs.modid.client.combat.carry.logic.CarryCounter.getInstance().onChat(text);
            // Melody's Harp: chat right after a board click, for the hit/miss timing log.
            sbs.modid.client.helper.experiment.logic.HarpHelper.getInstance().onChat(text);
            // Gemstones: the "[Sacks]" breakdown is the passive per-drop feed the profit card runs
            // on, so the full component is needed - the amounts live in the hover text.
            sbs.modid.client.skills.mining.logic.GemstoneTracker.getInstance().onChat(text, message);
            // Powder Ghast: record any line naming the mob, to settle whether a spawn is announced
            // and in what words. Broad on purpose; it only ever logs and records.
            sbs.modid.client.skills.mining.logic.GhastObserver.getInstance().onChat(text);
            // Heart of the Mountain: a perk purchase makes the cached tree out of date, so the
            // re-read prompt appears when it becomes true rather than on a timer.
            sbs.modid.client.skills.mining.logic.HotmTreeReader.getInstance().onChat(text);
            // HotM Upgrade Reminder: a tier-up marks the tree stale and reminds about the new token.
            sbs.modid.client.skills.mining.logic.HotmReminder.getInstance().onChat(text);
            // Fallen Star: the crash line names the Dwarven Mines zone it landed in.
            sbs.modid.client.skills.mining.fallenstar.logic.FallenStarTracker.getInstance().onChat(text);
            // Crystal Hollows lobby: warp-related lines logged with the lobby day (capture only).
            sbs.modid.client.helper.reminder.logic.HollowsLobbyWatch.getInstance().onChat(text);
            // Mining Event Timer: event start / end / announcement lines and the Powder Ghast, in the
            // Dwarven Mines and Crystal Hollows only. The component is read for hover text when capturing.
            sbs.modid.client.skills.mining.events.logic.MiningEventTracker.getInstance().onChat(text, message);
            // Treasure chests: the spawn line that claims a chest, and the reward block the
            // session counter reads. Crystal Hollows only; returns on a cached boolean elsewhere.
            sbs.modid.client.skills.mining.treasurechest.logic.TreasureChestTracker.getInstance()
                    .onChat(text);
            // Nucleus Run: crystal, bundle, chest, cost and [Sacks] lines (the gains are in the hover).
            // Crystal Hollows only; returns on the master switch elsewhere.
            sbs.modid.client.skills.mining.nucleus.logic.NucleusRunTracker.getInstance().onChat(text, message);
            // Jungle Temple cheese waypoint: the Kalhuiki Door Guardian's lines mean the player is at the door.
            sbs.modid.client.skills.mining.nucleus.logic.TempleCheeseWaypoint.getInstance().onChat(text);
            // Rejoin Timer: arm the countdown when a SkyBlock kick / limbo line shows up.
            sbs.modid.client.helper.rejoin.RejoinTimer.getInstance().onChat(text);
            // SkyBlock Map: Hypixel's answer to a warp it sent (refused, rate-limited, not unlocked).
            sbs.modid.client.helper.map.logic.MapNavigation.getInstance().onChat(text);
            // Fairy Souls: the collection line is the only signal that a soul was picked up.
            sbs.modid.client.helper.fairysouls.logic.FairySoulTracker.getInstance().onChat(text);
            // Reminders: an NPC's "done" line is what restarts that chore's cooldown.
            sbs.modid.client.helper.reminder.logic.ReminderTracker.getInstance().onChat(text);
            // Wither Door: a wither-key pickup / prompt turns the current room's door green.
            sbs.modid.client.dungeons.run.logic.WitherDoorTracker.getInstance().onChat(text);
            // Year of the Pig: the orb charge / expiry lines and the piglet's orb payout.
            sbs.modid.client.helper.yearofthepig.logic.ShinyOrbTracker.getInstance().onChat(text);
            // Ability Damage: record the hit here, BEFORE the display funnel may hide the line.
            sbs.modid.client.combat.damage.logic.AbilityDamageTracker.getInstance().onChat(text);
            // Pests: the spawn line is what arms the timer and raises the title.
            sbs.modid.client.skills.garden.logic.PestTracker.getInstance().onChat(text);
            // Pest Profit: kill lines, RARE DROPs and the "[Sacks]" hover near a kill. Needs the
            // component - the sack amounts live in the hover text.
            sbs.modid.client.skills.garden.pests.PestProfitTracker.getInstance().onChat(text, message);
            // Foraging: the per-chop detail line carries the EFFECTIVE sweep, which is the only
            // place that number is published - the stat menu leaves the conditional bonuses out.
            // Recorded here, BEFORE the display funnel may hide the line.
            sbs.modid.client.skills.foraging.logic.SweepTracker.getInstance().onChat(text);
            // ... and, while a capture is armed, written to a file verbatim: the wording is not
            // confirmed yet, so one chopping session is what turns the guesses into facts.
            sbs.modid.client.skills.foraging.logic.SweepCapture.getInstance().onChat(text);
            // Honey trees: the line that says a smear WORKED. It never identifies a tree - the
            // click already did that - so this only confirms a click that is already waiting, and
            // costs one list check on every other line in the game.
            sbs.modid.client.skills.foraging.logic.HoneyTreeTimers.getInstance().onChat(text);
            // Infested-plot hotkey: notice Hypixel refusing a teleport we just asked for, so the
            // next press reports the cooldown instead of firing into it again.
            sbs.modid.client.skills.garden.logic.InfestedPlotWarp.onChat(text);
            // Kuudra: the phase lines, the supply counter, fresh, and the party call-outs. The run
            // is driven almost entirely by chat, so this is the module's main input.
            sbs.modid.client.combat.kuudra.logic.KuudraChat.onChat(text);
            // Pelt hunt: Trevor's announcement arms the search, the pelt payout ends it.
            sbs.modid.client.skills.hunting.logic.PeltTracker.getInstance().onChat(text);
            // Safari trip: the shard catch lines, counted only while inside the zone. The RAW text
            // is passed on purpose - the colour code before a shard name is its rarity.
            sbs.modid.client.skills.hunting.logic.SafariTracker.getInstance().onChat(text);
            // Hideyho round: the critter's own hide-and-seek lines start and end the search the
            // spot markers belong to. Raw text on purpose - the listener strips the codes itself.
            sbs.modid.client.skills.hunting.hideyho.logic.HideyhoTracker.getInstance().onChat(text);
            // Hunting session: the same catches, counted on every hunting island rather than
            // only inside a Safari. Raw text on purpose - the colour before a shard name is its
            // rarity, and that is the only place it is readable.
            sbs.modid.client.skills.hunting.logic.HuntingSessionTracker.getInstance().onChat(text);
            // Hoe levels: a level-up line opens the short mute window around its jingle.
            sbs.modid.client.skills.farming.model.HoeLevels.getInstance().onChat(text);
            // Dungeon features register their chat patterns centrally instead of adding a line here.
            // The line goes in exactly as it arrived - the registry strips the colour codes itself,
            // so its "patterns see stripped text" contract cannot drift away from this call again.
            sbs.modid.client.dungeons.events.ChatPatternRegistry.getInstance().dispatch(text, message);
        }
    }
}
