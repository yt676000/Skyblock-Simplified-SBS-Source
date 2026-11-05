/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.chat;

import sbs.modid.client.core.chat.ChatDispatch.Gate;
import sbs.modid.client.core.chat.ChatDispatch.Listener;
import sbs.modid.client.core.chat.ChatDispatch.Prefilter;
import sbs.modid.client.helper.reminder.model.ReminderChore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static sbs.modid.client.core.chat.ChatDispatch.ANY_ISLAND;
import static sbs.modid.client.core.chat.ChatDispatch.CRYSTAL_HOLLOWS;
import static sbs.modid.client.core.chat.ChatDispatch.EVERY_LINE;
import static sbs.modid.client.core.chat.ChatDispatch.any;
import static sbs.modid.client.core.chat.ChatDispatch.anyIgnoreCase;
import static sbs.modid.client.core.chat.ChatDispatch.or;

/**
 * Every chat parser, in the order they run. The order is the one the chat mixin always had, and some
 * of it matters (the Diana trio, the Hoppity log after its parser).
 *
 * <p><b>The prefilter rule.</b> A prefilter's literals come from that parser's own patterns and early
 * returns, and every pattern in its path needs at least one of them; the comment on each entry says
 * which. A parser that does something on lines matching none of its patterns - logs every line while
 * armed, times out a window, keeps a ring of recent lines - takes {@link ChatDispatch#EVERY_LINE},
 * and its own cheap boolean stays the gate. An island gate only replaces an island check the parser
 * already makes before it touches any state; everything else keeps {@link ChatDispatch#ANY_ISLAND},
 * because gating a parser that works everywhere today would be a behaviour change, not a speed-up.
 *
 * <p>Handlers are lambdas, not method references: building this list must not load a feature class
 * (a unit test builds it without Minecraft running).
 */
final class ChatListeners {

    private ChatListeners() {
    }

    static List<Listener> build() {
        List<Listener> l = new ArrayList<>();

        // Dev chat probe / server scanner: record every line while armed. One boolean read otherwise.
        add(l, "ChatProbe", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.core.dev.ChatProbe.getInstance().onChat(r, m));
        // SETUP: (?i).*\[Bazaar].*Setup!...
        add(l, "ChatPriceCache", ANY_ISLAND, anyIgnoreCase("[Bazaar]"), (p, r, m) ->
                sbs.modid.client.economy.prices.ChatPriceCache.getInstance().parseChat(r));
        // Its own first gate: lower.contains("[bazaar]"); every CHAT_* pattern starts with it.
        add(l, "BazaarOrderTracker", ANY_ISLAND, anyIgnoreCase("[Bazaar]"), (p, r, m) ->
                sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker.getInstance().onChat(r));
        // CoinLines: all 14 rules contain " coins" or " Coins!".
        add(l, "CoinsPerHourTracker", ANY_ISLAND, any("oins"), (p, r, m) ->
                sbs.modid.client.economy.coinsperhour.logic.CoinsPerHourTracker.getInstance().onChat(r));
        // DiceParser ROLL "Your ... Archfiend Dice rolled", DROP "... Archfiend Dice", and the probe's
        // candidate test (Archfiend / Dice rolled): every line it reads holds one of these.
        add(l, "DiceTracker", ANY_ISLAND, any("Archfiend", "Dice rolled"), (p, r, m) ->
                sbs.modid.client.economy.dice.logic.DiceTracker.getInstance().onChat(r));
        // The probe logs every line from the 8th eye to the end of the loot window, so EVERY_LINE;
        // outside that its own first test drops anything not DragonParser.candidate or "[Sacks]".
        add(l, "DragonProfitTracker", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.combat.dragonprofit.logic.DragonProfitTracker.getInstance().onChat(p, r, m));
        // SacrificeParser SACRIFICE "SACRIFICE!", BONUS "BONUS LOOT!", REFUSED and the probe's candidate
        // test both hold "sacrifice"/"SACRIFICE!": every line it reads holds one of these.
        add(l, "SacrificeTracker", ANY_ISLAND, any("SACRIFICE!", "BONUS LOOT!", "sacrifice"), (p, r, m) ->
                sbs.modid.client.economy.sacrifice.logic.SacrificeTracker.getInstance().onChat(r));
        // The probe logs every line for 3 s after a claim, so EVERY_LINE; outside that window its own
        // gate drops any line without "claimed the" (CLAIM) or "Experience Bottle" (BAZAAR_BOTTLE).
        add(l, "ExperimentProfit", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.helper.experiment.logic.ExperimentProfit.getInstance().onChat(r));
        // AUTOPET, SUMMON, and contains("You despawned your").
        add(l, "PetTracker", ANY_ISLAND,
                any("Autopet equipped your ", "You summoned your ", "You despawned your"), (p, r, m) ->
                sbs.modid.client.ui.hud.logic.PetTracker.getInstance().parseChat(r));
        // CHAT_VOTED: (?i)You cast\s+N votes for ...
        add(l, "MayorVoteTracker", ANY_ISLAND, anyIgnoreCase("you cast"), (p, r, m) ->
                sbs.modid.client.economy.mayor.MayorVoteTracker.getInstance().parseChat(r));
        // OTHERS / OURS / CANDIDATE, all (?i) and each needing "garden" or "island".
        add(l, "FarTerrainOwnership", ANY_ISLAND, anyIgnoreCase("garden", "island"), (p, r, m) ->
                sbs.modid.client.helper.terrain.FarTerrainOwnership.onChat(r));
        // CHAT_COOLDOWN: (?i)This ability is on cooldown for\s*Ns
        add(l, "AbilityCooldowns", ANY_ISLAND, anyIgnoreCase("This ability is on cooldown for"), (p, r, m) ->
                sbs.modid.client.combat.cooldowns.AbilityCooldowns.getInstance().parseChat(r));
        // Gyro Helper: cooldown correction for a wand click, and the probe's "Gravity Storm" lines.
        add(l, "GyroHelper", ANY_ISLAND, anyIgnoreCase("cooldown for", "Gravity Storm"), (p, r, m) ->
                sbs.modid.client.combat.gyro.logic.GyroState.getInstance().onChat(r));
        // Weather probe: a line naming a weather, or the word itself (the wording is not captured yet).
        add(l, "WeatherTracker", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.helper.timers.weather.WeatherTracker.getInstance().onChat(r));
        // Every line: the probe logs whatever arrives after a Tactical Insertion click. The handler
        // returns on a timestamp compare unless that click was in the last few seconds.
        add(l, "TacTimerTracker", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.combat.abilitytimers.logic.TacTimerTracker.getInstance().onChat(r));
        // USE "...Ability!", READY "...is now available!", and the axe capture's own word test.
        add(l, "AbilityReadyAlert", ANY_ISLAND, anyIgnoreCase("ability", "cooldown", "available"), (p, r, m) ->
                sbs.modid.client.combat.cooldowns.AbilityReadyAlert.getInstance().onChat(r));
        // PARTY_LINE "Party > ", and the downtime announcement on a run-end line.
        add(l, "PartyChatCommands", ANY_ISLAND, any("Party > ", "> EXTRA STATS <", "KUUDRA DOWN!"), (p, r, m) ->
                sbs.modid.client.social.chat.command.PartyChatCommands.getInstance().parseChat(r));
        // RunEndDetector: the floor title, the summary lines and the two Kuudra end lines; plus the
        // server's "to re-queue into" offer and its "You have been re-queued!" confirmation.
        add(l, "AutoRequeue", ANY_ISLAND, any("Catacombs - ", "EXTRA STATS", "Team Score:", "KUUDRA DOWN!",
                "DEFEAT", "re-queue"), (p, r, m) ->
                sbs.modid.client.dungeons.requeue.logic.AutoRequeue.getInstance().onChat(p, m));
        // Nothing without a sender colon (senderColon); the coordinate patterns have no literal.
        add(l, "ChatWaypoints", ANY_ISLAND, any(":"), (p, r, m) ->
                sbs.modid.client.social.chat.logic.ChatWaypoints.getInstance().onChat(r));
        // Its own two checks: "Hoppity's Hunt has begun" / the "HOPPITY'S HUNT" prefix.
        add(l, "HoppityChat", ANY_ISLAND, any("Hoppity's Hunt has begun", "HOPPITY'S HUNT"), (p, r, m) -> {
            var hoppity = sbs.modid.client.helper.hoppity.logic.HoppityChat.getInstance().onChat(r);
            if (hoppity != null) {
                // The evidence trail for the locator work still to be built: the position a
                // collection happened at is what a spot catalogue would learn from. Logged here and
                // not inside HoppityChat, which is unit tested and must not reach for Minecraft.
                var player = net.minecraft.client.Minecraft.getInstance().player;
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Hoppity] {} meal={} name={} rarity={} at {} on {}",
                        hoppity.kind(), hoppity.meal(), hoppity.name(), hoppity.rarity(),
                        player == null ? "no player" : player.blockPosition().toShortString(),
                        sbs.modid.client.core.location.SkyBlockLocation.island());
            }
        });
        // Its own gate: contains("has invited you to join their party").
        add(l, "ShortCommands", ANY_ISLAND, any("has invited you to join their party"), (p, r, m) ->
                sbs.modid.client.core.command.ShortCommands.onChat(r));
        // Every pattern and prefix holds "party" / "Party" / "'s party!".
        add(l, "PartyTracker", ANY_ISLAND, any("arty"), (p, r, m) ->
                sbs.modid.client.social.party.logic.PartyTracker.getInstance().parseChat(r));
        // COOKIE_REFUSAL, only inside its own 5 s window.
        add(l, "VisitorBazaarButtons", ANY_ISLAND, any("You need the Cookie Buff"), (p, r, m) ->
                sbs.modid.client.skills.garden.ui.VisitorBazaarButtons.getInstance().onChat(r));
        // ACCEPTED: ^OFFER ACCEPTED with ...
        add(l, "VisitorOfferStore", ANY_ISLAND, any("OFFER ACCEPTED with"), (p, r, m) ->
                sbs.modid.client.skills.garden.logic.VisitorOfferStore.getInstance()
                        .onChat(sbs.modid.client.skills.garden.logic.VisitorMenu.plain(r)));
        // Research capture: ~55 keywords, gated on its own toggle. Off = one boolean read.
        add(l, "GreenhouseCapture", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.skills.garden.logic.GreenhouseCapture.getInstance().onChat(r));
        // Logs every line while the analyzer is open and 10 s after.
        add(l, "CropAnalyzerCapture", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.skills.garden.logic.CropAnalyzerCapture.getInstance().onText("chat", r));
        // Step triggers only run on "[NPC] " lines.
        add(l, "QuestTracker", ANY_ISLAND, any("[NPC] "), (p, r, m) ->
                sbs.modid.client.helper.quest.logic.QuestTracker.getInstance().onChatLine(r));
        // Records while a session runs; its "+..." rule is too broad for a literal.
        add(l, "QuestCapture", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.core.dev.QuestCapture.getInstance().onChat(r, m));
        // Exact spawn sentences plus a reworded-spawn warning with a throttle shared with the
        // announcer: no literal set covers the warning's fragments, so both see every line.
        add(l, "FishingTracker", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.skills.fishing.logic.FishingTracker.getInstance().onChat(r, m));
        add(l, "SeaCreatureAnnouncer", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.skills.fishing.logic.SeaCreatureAnnouncer.getInstance().onChat(r));
        // START / ANITA are "[NPC] Jacob: ..."; the probe takes "Jacob" or upper-case "CONTEST".
        add(l, "JacobContestTracker", ANY_ISLAND, or(any("Jacob"), anyIgnoreCase("contest")), (p, r, m) ->
                sbs.modid.client.skills.farming.contest.JacobContestTracker.getInstance().onChat(r));
        // RareDropLine "... DROP!" and the "CROP!" diagnostic, on toUpperCase(ROOT).
        add(l, "FarmDropTracker", ANY_ISLAND, anyIgnoreCase("DROP!", "CROP!"), (p, r, m) ->
                sbs.modid.client.skills.farming.logic.FarmDropTracker.getInstance().onChat(p));
        // TrophyCatchParser: every line it looks at holds "TROPHY FISH!".
        add(l, "TrophyFishTracker", ANY_ISLAND, any("TROPHY FISH!"), (p, r, m) ->
                sbs.modid.client.skills.trophyfish.logic.TrophyFishTracker.getInstance().onChat(r));
        // Layout recorder: every line between two path steps.
        add(l, "ScreenOpeners", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.core.dev.ScreenOpeners.getInstance().onChat(p));
        // looksLikeCake: "Yum!" / "Big Yum!".
        add(l, "CakeBuffStore", ANY_ISLAND, any("Yum!"), (p, r, m) ->
                sbs.modid.client.helper.buffs.CakeBuffStore.getInstance().onChat(r));
        // GOD_POTION / MIXIN / looksRelated "grants you", COOKIE / CONSUMED_FOR "You consumed",
        // BUFF, EXPIRES_IN, EXPIRED.
        add(l, "ConsumableStore", ANY_ISLAND, any("grants you", "You consumed", "BUFF! You have gained",
                " expires in ", " has expired!"), (p, r, m) ->
                sbs.modid.client.helper.buffs.consumables.logic.ConsumableStore.getInstance().onChat(r));
        // Diana: the guard records every line in its event ring, so it runs on every line; the
        // three parsers inside it are prefiltered. Order kept: BurrowChat, RareCreatureShare, Sphinx.
        Prefilter burrow = anyIgnoreCase("griffin burrow", "\u2620", "dug", "drop!"); // + loot
        Prefilter shared = anyIgnoreCase("x:");                                // SHARED (?i)...x:\s*-?\d+
        add(l, "Diana", ANY_ISLAND, EVERY_LINE, (p, r, m) -> {
            ChatDispatch.Line line = ChatDispatch.current();
            sbs.modid.client.combat.diana.logic.DianaGuard.run(
                    sbs.modid.client.combat.diana.logic.DianaGuard.Hook.CHAT, r, () -> {
                        if (burrow.test(line)) {
                            sbs.modid.client.combat.diana.logic.BurrowChat.getInstance().onChat(r);
                        }
                        if (shared.test(line)) {
                            sbs.modid.client.combat.diana.logic.RareCreatureShare.getInstance().onChat(r);
                        }
                        // Riddles are matched after normalise() drops punctuation and folds case: no
                        // literal on the line is provable, so the Sphinx sees every line.
                        sbs.modid.client.combat.diana.logic.SphinxAnswers.getInstance().onChat(r);
                    });
        });
        // startsWith("[Sacks]") on the raw text.
        add(l, "CollectionTracker", ANY_ISLAND, any("[Sacks]"), (p, r, m) ->
                sbs.modid.client.skills.collection.CollectionTracker.getInstance().onChat(r, m));
        // RNG meter "Slayer LVL" / "Stored XP", the four "SLAYER QUEST/BOSS" lines, RareDropLine
        // "... DROP!", and the [Sacks] breakdown.
        add(l, "SlayerTracker", ANY_ISLAND, any("Slayer LVL", "Stored XP", "SLAYER ", " DROP!", "[Sacks]"),
                (p, r, m) -> sbs.modid.client.combat.slayer.logic.SlayerTracker.getInstance().onChat(r, m));
        // Riftstalker Mania probe (debug): every line, but only while your own Bloodfiend is up.
        add(l, "ManiaProbe", ANY_ISLAND,
                line -> sbs.modid.client.combat.vampire.logic.ManiaProbe.getInstance().armed(),
                (p, r, m) -> sbs.modid.client.combat.vampire.logic.ManiaProbe.getInstance().onChat(p));
        add(l, "CarryCounter", ANY_ISLAND, any("SLAYER QUEST COMPLETE"), (p, r, m) ->
                sbs.modid.client.combat.carry.logic.CarryCounter.getInstance().onChat(r));
        // Logs every line for 2 s after a harp click.
        add(l, "HarpHelper", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.helper.experiment.logic.HarpHelper.getInstance().onChat(r));
        // [Sacks] on every island: it notes the line before its own mining-island check.
        add(l, "GemstoneTracker", ANY_ISLAND, any("[Sacks]"), (p, r, m) ->
                sbs.modid.client.skills.mining.logic.GemstoneTracker.getInstance().onChat(r, m));
        // CHAT_MENTION (?i)powder\s+ghast
        add(l, "GhastObserver", ANY_ISLAND, anyIgnoreCase("ghast"), (p, r, m) ->
                sbs.modid.client.skills.mining.logic.GhastObserver.getInstance().onChat(r));
        // isSpend needs lower.contains("powder").
        add(l, "HotmTreeReader", ANY_ISLAND, anyIgnoreCase("powder"), (p, r, m) ->
                sbs.modid.client.skills.mining.logic.HotmTreeReader.getInstance().onChat(r));
        // CAPTURE (?i)heart of the mountain|hotm|token of the mountain|level up|upgraded|powder;
        // TIER_UP needs HOTM or heart of the mountain.
        add(l, "HotmReminder", ANY_ISLAND, anyIgnoreCase("hotm", "mountain", "level up", "upgraded", "powder"),
                (p, r, m) -> sbs.modid.client.skills.mining.logic.HotmReminder.getInstance().onChat(r));
        // CRASH: ^✯? ?A Fallen Star has crashed at ...
        add(l, "FallenStarTracker", ANY_ISLAND, any("A Fallen Star has crashed at"), (p, r, m) ->
                sbs.modid.client.skills.mining.fallenstar.logic.FallenStarTracker.getInstance().onChat(r));
        // WARP_LINE (?i): the warp / sending / evacuating / transfer / closed / full alternatives.
        // No island gate: it also reads the lines just after leaving the Hollows.
        add(l, "HollowsLobbyWatch", ANY_ISLAND, anyIgnoreCase("warp", "sending", "evacuating", "transfer",
                "no longer accepting", "closed", "is full"), (p, r, m) ->
                sbs.modid.client.helper.reminder.logic.HollowsLobbyWatch.getInstance().onChat(r));
        // A header waiting for its subtitle needs the next line whatever it says (and off-island it
        // is reset); otherwise only mining-island lines matching ANNOUNCE/HEADER/GHAST/CAPTURE.
        Prefilter miningWords = anyIgnoreCase("event", "started", "ended", "ghast", "powder",
                "gone with the wind", "better together", "goblin raid", "raffle", "gourmand", "ends in");
        add(l, "MiningEventTracker",
                line -> miningEvents().hasPendingLine()
                        || sbs.modid.client.skills.mining.events.logic.MiningEventTracker.miningIsland(
                                sbs.modid.client.core.location.SkyBlockLocation.island()),
                line -> miningEvents().hasPendingLine() || miningWords.test(line), (p, r, m) ->
                        miningEvents().onChat(r, m));
        // A reward block reads every line until its separator, and armed() keeps its own lobby
        // bookkeeping: every line (it returns on a cached boolean off the Hollows).
        add(l, "TreasureChestTracker", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.skills.mining.treasurechest.logic.TreasureChestTracker.getInstance().onChat(r));
        // Crystal Hollows only - its own first check, before any state. Every Hollows line: it
        // keeps the last line and the run ledger current.
        add(l, "NucleusRunTracker", CRYSTAL_HOLLOWS, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.skills.mining.nucleus.logic.NucleusRunTracker.getInstance().onChat(r, m));
        // Its own raw prefilter.
        add(l, "DivanTracker", ANY_ISLAND, any("Metal Detector", "Keeper of"), (p, r, m) ->
                sbs.modid.client.skills.mining.metaldetector.logic.DivanTracker.getInstance().onChat(r));
        // GUARDIAN_*: ^\[NPC] Kalhuiki Door Guardian: ...; Hollows is its own first check.
        add(l, "TempleCheeseWaypoint", CRYSTAL_HOLLOWS, any("Kalhuiki Door Guardian:"), (p, r, m) ->
                sbs.modid.client.skills.mining.nucleus.logic.TempleCheeseWaypoint.getInstance().onChat(r));
        // isRestartLine / isKickLine / looksRelevant, all lower-case contains.
        add(l, "RejoinTimer", ANY_ISLAND, anyIgnoreCase("kick", "disconnect", "limbo", "evacuat", "restart",
                "reboot", "seconds to warp out"), (p, r, m) ->
                sbs.modid.client.helper.rejoin.RejoinTimer.getInstance().onChat(r));
        // Refusal words: unlock, too fast, slow down, wait a few, warp, in combat.
        add(l, "MapNavigation", ANY_ISLAND, anyIgnoreCase("unlock", "too fast", "slow down", "wait a few",
                "warp", "in combat"), (p, r, m) ->
                sbs.modid.client.helper.map.logic.MapNavigation.getInstance().onChat(r));
        // Every check after its own lower-case "fairy soul" test.
        add(l, "FairySoulTracker", ANY_ISLAND, anyIgnoreCase("fairy soul"), (p, r, m) ->
                sbs.modid.client.helper.fairysouls.logic.FairySoulTracker.getInstance().onChat(r));
        // Built from the chores' own triggers, so a new chore cannot be filtered out.
        add(l, "ReminderTracker", ANY_ISLAND, anyIgnoreCase(reminderTriggers()), (p, r, m) ->
                sbs.modid.client.helper.reminder.logic.ReminderTracker.getInstance().onChat(r));
        // DoorKeyChat: "... Key!", "Wither Key", "RIGHT CLICK on a ... door", "... door!", "BLOOD DOOR".
        add(l, "WitherDoorTracker", ANY_ISLAND, anyIgnoreCase("key", "door"), (p, r, m) ->
                sbs.modid.client.dungeons.run.logic.WitherDoorTracker.getInstance().onChat(r));
        // ORB_CHARGED / ORB_LOOTED "SHINY!", ORB_EXPIRED, PIG_TAKEN.
        add(l, "ShinyOrbTracker", ANY_ISLAND, any("SHINY!", "Shiny Orb", "already been clicked"), (p, r, m) ->
                sbs.modid.client.helper.yearofthepig.logic.ShinyOrbTracker.getInstance().onChat(r));
        // ^Your ... hit N enemies for ... damage
        add(l, "AbilityDamageTracker", ANY_ISLAND, any("damage"), (p, r, m) ->
                sbs.modid.client.combat.damage.logic.AbilityDamageTracker.getInstance().onChat(r));
        // Phillip's bonus needs "farming fortune"; SPAWN is behind contains("plot").
        add(l, "PestTracker", ANY_ISLAND, anyIgnoreCase("plot", "farming fortune"), (p, r, m) ->
                sbs.modid.client.skills.garden.logic.PestTracker.getInstance().onChat(r));
        // Capture windows log every line; otherwise trap / kill words, PET/RARE "DROP!", [Sacks].
        Prefilter pestWords = or(anyIgnoreCase("trap", "kill", "slain", "squashed", "vacuumed", "exterminated"),
                any("DROP!", "[Sacks]"));
        add(l, "PestProfitTracker", ANY_ISLAND,
                line -> pestProfit().isCapturing(System.currentTimeMillis()) || pestWords.test(line), (p, r, m) ->
                        pestProfit().onChat(r, m));
        // SweepParser.isCandidate: "∮" or (?i)"sweep".
        add(l, "SweepTracker", ANY_ISLAND, or(any("\u222E"), anyIgnoreCase("sweep")), (p, r, m) ->
                sbs.modid.client.skills.foraging.logic.SweepTracker.getInstance().onChat(r));
        // Writes (nearly) every line to its file while armed.
        add(l, "SweepCapture", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.skills.foraging.logic.SweepCapture.getInstance().onChat(r));
        // User-configurable words and a near-miss log while a click is pending; empty = one check.
        add(l, "HoneyTreeTimers", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.skills.foraging.logic.HoneyTreeTimers.getInstance().onChat(r));
        // Teleport refusals, within its own send window.
        add(l, "InfestedPlotWarp", ANY_ISLAND, anyIgnoreCase("teleport", "wait a moment", "slow down",
                "not unlocked"), (p, r, m) ->
                sbs.modid.client.skills.garden.logic.InfestedPlotWarp.onChat(r));
        // Every contains / startsWith / equals in the router.
        add(l, "KuudraChat", ANY_ISLAND, any("Sending to server", "Starting in ", "[NPC] Elle:",
                "has been eaten by Kuudra!", "destroyed one of Kuudra's pods!", "KUUDRA DOWN!", "DEFEAT",
                " recovered", "dropped Elle's supplies", "slipped out of your hands",
                "retrieved some of Elle's supplies", "Fresh Tools Perk", "Casting Spell:", "Extreme Focus!",
                "Party >"), (p, r, m) ->
                sbs.modid.client.combat.kuudra.logic.KuudraChat.onChat(r));
        // QUEST_START / TREVOR_LINE "Trevor", PELTS_GAINED "pelt".
        add(l, "PeltTracker", ANY_ISLAND, anyIgnoreCase("trevor", "pelt"), (p, r, m) ->
                sbs.modid.client.skills.hunting.logic.PeltTracker.getInstance().onChat(r));
        // SHARD_* " Shard" and the (?i)shard near-miss log.
        add(l, "SafariTracker", ANY_ISLAND, anyIgnoreCase("shard"), (p, r, m) ->
                sbs.modid.client.skills.hunting.logic.SafariTracker.getInstance().onChat(r));
        // SPEAKS (?i)\bhideyho\b\s*: gates everything.
        add(l, "HideyhoTracker", ANY_ISLAND, anyIgnoreCase("hideyho"), (p, r, m) ->
                sbs.modid.client.skills.hunting.hideyho.logic.HideyhoTracker.getInstance().onChat(r));
        // ShardCatchParser patterns and SHARD_ISH.
        add(l, "HuntingSessionTracker", ANY_ISLAND, anyIgnoreCase("shard"), (p, r, m) ->
                sbs.modid.client.skills.hunting.logic.HuntingSessionTracker.getInstance().onChat(r));
        // LEVEL_UP: both alternatives need "level".
        add(l, "HoeLevels", ANY_ISLAND, anyIgnoreCase("level"), (p, r, m) ->
                sbs.modid.client.skills.farming.model.HoeLevels.getInstance().onChat(r));
        // The dungeon registry has a (?s).* puzzle entry and a raw-text fallback: every line.
        add(l, "ChatPatternRegistry", ANY_ISLAND, EVERY_LINE, (p, r, m) ->
                sbs.modid.client.dungeons.events.ChatPatternRegistry.getInstance().dispatch(r, m));
        // DungeonLoot.candidate: indented reward-block lines, the empty line that ends a block, and
        // the literals of the essence / rare / unlocked / Kismet / score / potion patterns.
        add(l, "DungeonProfitTracker", ANY_ISLAND, line -> line.plain().isEmpty() || line.plain().charAt(0) == ' '
                || line.has("unlocked ") || line.has("ESSENCE!") || line.has("Everyone gains")
                || line.has("RARE REWARD!") || line.has("Kismet") || line.has("Team Score:") || line.has("otion"),
                (p, r, m) ->
                sbs.modid.client.dungeons.profit.logic.DungeonProfitTracker.getInstance().onChat(p));
        // KuudraLoot.candidate (reward-block lines, key purchases, the two run ends), plus every line
        // for 10 s after a chest or a run end while the probe is open.
        add(l, "KuudraProfitTracker", ANY_ISLAND, line -> line.plain().isEmpty() || line.plain().charAt(0) == ' '
                || line.has("Kuudra Key") || line.has("KUUDRA DOWN!") || line.has("DEFEAT")
                || sbs.modid.client.combat.kuudraprofit.logic.KuudraProfitTracker.probing(), (p, r, m) ->
                sbs.modid.client.combat.kuudraprofit.logic.KuudraProfitTracker.getInstance().onChat(p));
        return List.copyOf(l);
    }

    private static void add(List<Listener> list, String name, Gate gate, Prefilter prefilter,
                            ChatDispatch.Handler handler) {
        list.add(new Listener(name, gate, prefilter, handler));
    }

    private static sbs.modid.client.skills.mining.events.logic.MiningEventTracker miningEvents() {
        return sbs.modid.client.skills.mining.events.logic.MiningEventTracker.getInstance();
    }

    private static sbs.modid.client.skills.garden.pests.PestProfitTracker pestProfit() {
        return sbs.modid.client.skills.garden.pests.PestProfitTracker.getInstance();
    }

    static String[] reminderTriggers() {
        List<String> out = new ArrayList<>();
        for (ReminderChore chore : ReminderChore.BUILT_IN) {
            for (String t : chore.doneTriggers()) {
                out.add(t.toLowerCase(Locale.ROOT));
            }
        }
        return out.toArray(new String[0]);
    }
}
