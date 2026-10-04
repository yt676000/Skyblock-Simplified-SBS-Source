/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.command;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.AlertChannel;
import sbs.modid.client.core.alert.AlertChannels;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.ui.hud.logic.ServerStatsTracker;
import sbs.modid.client.ui.screen.SBSMainScreen;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Native client-side command handling – no Fabric API, no Brigadier.
 *
 * <p>The vanilla client never executes commands locally (it forwards them to the
 * server). {@code ChatScreenMixin} intercepts the chat input and routes it here via
 * {@link #tryExecute(String)}. Since our commands take no arguments, a direct name
 * match is all that is needed – this avoids depending on Brigadier's runtime
 * dispatcher (which behaved unreliably in this environment) and keeps the path to
 * opening the GUI dead simple. Brigadier can be reintroduced later for commands that
 * actually need argument parsing.
 *
 * <p>Adding a new no-arg command later is just another entry in {@link #ROOT_COMMANDS}
 * plus a branch in {@link #tryExecute(String)}.
 */
public final class SBSCommands {

    /** Root command literals owned by this mod (lower-case, without leading slash). */
    private static final Set<String> ROOT_COMMANDS =
            Set.of("sbs", "skyblocksimplified", "sbstest", "sbsdev", "pf", "pv",
                    "sendcoords", "sbssendcoords");

    private SBSCommands() {
    }

    /** Logs availability. Called from client init. */
    public static void init() {
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Client commands ready: /sbs, /skyblocksimplified");
    }

    /**
     * Attempts to handle a command typed by the player.
     *
     * @param rawCommand the command string <b>without</b> the leading slash
     * @return {@code true} if this is one of our commands (handled locally, so the
     *         caller should cancel forwarding it to the server); {@code false}
     *         otherwise, so the command is forwarded normally
     */
    public static boolean tryExecute(String rawCommand) {
        if (rawCommand == null) {
            return false;
        }
        String trimmed = rawCommand.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String[] parts = trimmed.split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        // Build Tools: //<verb> arrives here as "/<verb>" (the chat strips the first slash), and the
        // old "/.. <verb>" root still works as an alias. Handled and cancelled like every command in
        // this class, so it never reaches a server. A "//" verb is only claimed while Build Tools is
        // on: a server that declares the same name (see BuildCommandTree) keeps it otherwise.
        sbs.modid.client.helper.build.command.BuildCommand.Invocation build =
                sbs.modid.client.helper.build.command.BuildCommand.fromCommandLine(trimmed);
        // With Build Tools off, a //verb the server itself declares goes to the server; one it does not
        // know is still answered here ("Build Tools is off"), instead of the server's unknown-command error.
        if (build != null && (trimmed.startsWith("..")
                || sbs.modid.client.core.config.ConfigManager.getInstance().get().buildTools.enabled
                || !sbs.modid.client.helper.build.command.BuildCommandTree.serverDeclares("/" + build.command().word()))) {
            sbs.modid.client.helper.build.command.BuildCommands.execute(
                    (build.command().word() + " " + build.args()).trim());
            return true;
        }
        if (!ROOT_COMMANDS.contains(name)) {
            // A command shortcut typed as "/alias ..." – expand and run it locally.
            String expanded = sbs.modid.client.core.keybind.CommandShortcutManager.getInstance().expand(trimmed);
            if (!expanded.equals(trimmed)) {
                run(expanded.startsWith("/") ? expanded : "/" + expanded);
                return true;
            }
            // Built-in shortcuts (/pw, /pk, prefix-free warps) come last, so a user alias of the
            // same name always wins.
            if (ShortCommands.tryRun(name, parts.length > 1 ? parts[1] : "")) {
                return true;
            }
            // Everything that leaves for another server passes here: hold it back while Hypixel's
            // transfer cooldown is still running, rather than letting the warp be dropped.
            return TransferCooldown.getInstance().intercept(name, trimmed);
        }

        // /pf - the Party Finder shortcut (same as /sbs party without a message).
        if (name.equals("pf")) {
            handleParty("");
            return true;
        }

        // /pv [player] - the SBS profile viewer (own profile without an argument).
        if (name.equals("pv")) {
            openProfileViewer(parts.length > 1 ? parts[1].trim() : "");
            return true;
        }

        // /sendcoords [party|guild|coop|all] [note] - your block position into a chat channel.
        // Handled here and cancelled, so nothing reaches Hypixel, which has no such command.
        //
        // The alias is not decoration. The chat mixin that routes here injects at the HEAD of the
        // chat input, before anything hooking the outgoing packet, so if another client on the same
        // instance owned /sendcoords we would quietly take its command and it would look broken with
        // nothing anywhere to point at.
        // /sbssendcoords carries the mod's own name and cannot be claimed out from under the player.
        if (name.equals("sendcoords") || name.equals("sbssendcoords")) {
            sbs.modid.client.social.sendcoords.SendCoords.handle(parts.length > 1 ? parts[1] : "");
            return true;
        }

        // Developer-mode toggle + grid calibration: "/sbsdev" flips DevMode; "/sbsdev origin [x z]" reads
        // or sets the 32-grid world origin used to align the room boxes to the walls.
        if (name.equals("sbsdev")) {
            handleSbsDev(parts.length > 1 ? parts[1] : "");
            return true;
        }

        // Developer highlight test command: /sbstest [clear]. Only acts in dev mode; always consumed silently
        // otherwise (never forwarded to the server).
        if (name.equals("sbstest")) {
            handleSbsTest(parts.length > 1 ? parts[1] : "");
            return true;
        }

        // Subcommands: /sbs itemrename <new name> (persistent client-side rename of the held item)
        // and /sbs itemoriginalname (restore; the common misspelling is tolerated).
        if (parts.length > 1) {
            String[] sub = parts[1].split("\\s+", 2);

            // Hidden dev toggle: "/sbs developermode password:simplified" flips DevMode. This branch is
            // COMPLETELY SILENT – on a wrong password or any typo it prints nothing, opens no GUI and
            // offers no tab-completion (the whole command path is native routing, not Brigadier). It
            // just consumes the input so nothing leaks to chat or the server.
            if (sub[0].equalsIgnoreCase("developermode")) {
                String rest = sub.length > 1 ? sub[1].trim() : "";
                boolean accepted = rest.equals("password:simplified");
                // Log only (invisible in-game) so you can confirm the command path is reached without
                // breaking the "silent on wrong input" rule. Grep latest.log for this line.
                SkyblockSimplifiedSBS.LOGGER.info("[SBS] developermode command received (password {})",
                        accepted ? "accepted" : "rejected");
                if (accepted) {
                    sbs.modid.client.core.dev.DevMode.toggle();
                }
                return true; // handled locally, silently
            }

            // /sbs gui - the main overlay. A bare /sbs opens it too; the explicit name exists because
            // that is what people try first, and it keeps /sbs keybind from looking like the odd one.
            if (sub[0].equalsIgnoreCase("gui") || sub[0].equalsIgnoreCase("menu")) {
                openMainScreen();
                return true;
            }

            // /sbs help [command] - every command, from CommandRegistry (the same list the Commands
            // page and the Tab suggestions read). A click only fills in the chat box.
            if (sub[0].equalsIgnoreCase("help")) {
                CommandHelp.handle(sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs wizard [...] - the first-run overlay's dev tooling. Every state record resets
            // independently, and either flow can be re-triggered, because the alternative is deleting
            // a config file between every iteration.
            if (sub[0].equalsIgnoreCase("wizard")) {
                handleWizard(sub.length > 1 ? sub[1].trim() : "");
                return true;
            }

            // /sbs questcapture [...] - the dev questline recorder. Dev mode only: a capture writes
            // a file and nothing else, but it is tooling, not a player feature.
            if (sub[0].equalsIgnoreCase("questcapture") || sub[0].equalsIgnoreCase("qc")) {
                if (!sbs.modid.client.core.dev.DevMode.ACTIVE) {
                    return true;   // silent, like every other dev path
                }
                sbs.modid.client.core.dev.QuestCapture.getInstance()
                        .handleCommand(sub.length > 1 ? sub[1].trim() : "");
                return true;
            }

            // /sbs perf [dump [label]|reset|slow <ms>] (dev mode) - the performance KPI overlay, a report
            // to Development_Stuff/perf/, a reset of the rolling numbers, and the slow-frame simulator.
            if (sub[0].equalsIgnoreCase("perf")) {
                if (!sbs.modid.client.core.dev.DevMode.ACTIVE) {
                    return true;   // dev tooling; silent, like every other dev path
                }
                String[] args = sub.length > 1 ? sub[1].trim().split("\\s+", 2) : new String[] {""};
                String verb = args[0].toLowerCase(java.util.Locale.ROOT);
                switch (verb) {
                    case "dump" -> say("§bPerf report: " + sbs.modid.client.core.perf.PerfReport
                            .dump(args.length > 1 ? args[1] : "").getFileName() + ".json / .csv");
                    case "reset" -> {
                        sbs.modid.client.core.perf.Perf.reset();
                        sbs.modid.client.core.perf.PerfReport.resetCounters();
                        say("§bPerf numbers reset.");
                    }
                    case "slow" -> {
                        int ms = 0;
                        try {
                            ms = Math.max(0, Math.min(50, Integer.parseInt(args.length > 1 ? args[1].trim() : "0")));
                        } catch (NumberFormatException ignored) {
                            // 0 = off
                        }
                        sbs.modid.client.core.config.ConfigManager.getInstance().get().performance.simulateSlowMs = ms;
                        say("§bSimulated slow tick: " + (ms == 0 ? "off" : ms + " ms of SBS work per tick"));
                    }
                    default -> say("§bPerf overlay " + (sbs.modid.client.core.perf.PerfOverlay.toggle() ? "on" : "off")
                            + " §7(/sbs perf dump [label] | reset | slow <ms>)");
                }
                return true;
            }

            // /sbs keybind - straight into the keybind editor (key + command, conditions, shortcuts),
            // without going through the module list first.
            if (sub[0].equalsIgnoreCase("keybind") || sub[0].equalsIgnoreCase("keybinds")) {
                openKeybindsScreen();
                return true;
            }

            // /sbs irc <message> - sends into the SBS IRC chat (quotes optional, like itemrename).
            if (sub[0].equalsIgnoreCase("irc")) {
                String message = sub.length > 1
                        ? sub[1].trim().replaceAll("^\"|\"$", "") : "";
                sbs.modid.client.social.chat.logic.IrcClient.getInstance().sendFromCommand(message);
                return true;
            }

            // /sbs skycrypt [player] - opens SkyCrypt in the in-game browser (Player Viewer module).
            if (sub[0].equalsIgnoreCase("skycrypt")) {
                openSkycrypt(sub.length > 1 ? sub[1].trim() : "");
                return true;
            }

            // /sbs party [message] - message -> chat to your current party; bare -> open the finder.
            if (sub[0].equalsIgnoreCase("party")) {
                handleParty(sub.length > 1 ? sub[1].trim() : "");
                return true;
            }

            // /sbs mayor [item] - the read-only mayor window (active mayor + perks + election
            // candidates); with an item it also shows that item's price effect and mayor history.
            if (sub[0].equalsIgnoreCase("mayor")) {
                openMayorScreen(sub.length > 1 ? sub[1].trim() : "");
                return true;
            }

            // /sbs objective off|go - dismiss the objective route, or warp towards its target. "go"
            // is the explicit request the ambient reader never makes on its own.
            if (sub[0].equalsIgnoreCase("objective")) {
                String arg = sub.length > 1 ? sub[1].trim() : "";
                var tracker = sbs.modid.client.helper.npc.ObjectiveNpcTracker.getInstance();
                if (arg.equalsIgnoreCase("off")) {
                    tracker.dismiss();
                    sbs.modid.client.social.chat.logic.SBSChat.send(
                            "Objective route hidden until the objective changes.");
                } else if (arg.equalsIgnoreCase("go")) {
                    sbs.modid.client.social.chat.logic.SBSChat.send(tracker.travel());
                } else {
                    sbs.modid.client.social.chat.logic.SBSChat.send(
                            "Objective: " + tracker.statusLine() + "  (/sbs objective off | go)");
                }
                return true;
            }

            // /sbs cph reset - restart the Coins per Hour session.
            if (sub[0].equalsIgnoreCase("cph")) {
                if (sub.length > 1 && sub[1].trim().equalsIgnoreCase("reset")) {
                    sbs.modid.client.economy.coinsperhour.logic.CoinsPerHourTracker.getInstance().reset();
                    sbs.modid.client.social.chat.logic.SBSChat.send("Coins per Hour session reset.");
                } else {
                    sbs.modid.client.social.chat.logic.SBSChat.send("Usage: /sbs cph reset");
                }
                return true;
            }

            // /sbs visitors - the Garden visitor shopping list in chat, each item a /bz link.
            if (sub[0].equalsIgnoreCase("visitors")) {
                sbs.modid.client.skills.garden.ui.VisitorShoppingHud.printToChat();
                return true;
            }

            // /sbs minions - the minion calculator; /sbs minions plan - the budget optimizer.
            if (sub[0].equalsIgnoreCase("minions")) {
                boolean plan = sub.length > 1 && sub[1].trim().equalsIgnoreCase("plan");
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.execute(() -> minecraft.setScreenAndShow(plan
                        ? new sbs.modid.client.economy.minions.ui.MinionPlanScreen()
                        : new sbs.modid.client.economy.minions.ui.MinionCalcScreen()));
                return true;
            }

            // /sbs accessories - the accessory catalogue with what this profile is missing.
            if (sub[0].equalsIgnoreCase("accessories") || sub[0].equalsIgnoreCase("missing")) {
                sbs.modid.client.helper.inventory.ui.MissingAccessoriesScreen.open();
                return true;
            }

            // /sbs protect [list|clear] - the Item Protection list. Bare opens the manager.
            if (sub[0].equalsIgnoreCase("protect")) {
                handleProtect(sub.length > 1 ? sub[1].trim() : "");
                return true;
            }

            // /sbs map [place] - the island maps. With a name, jumps straight to travelling there,
            // which is the whole feature without the screen in between.
            if (sub[0].equalsIgnoreCase("map")) {
                handleMap(sub.length > 1 ? sub[1].trim() : "");
                return true;
            }

            // /sbs chmap [mark <label>|unmark [label]] - the schematic Crystal Hollows map, and the
            // player's own markers on it. Markers stay on this PC.
            if (sub[0].equalsIgnoreCase("chmap")) {
                handleHollowsMap(sub.length > 1 ? sub[1].trim() : "");
                return true;
            }

            // /sbs where - the location debug command: what the mod thinks the island and area are,
            // the raw rows it derived that from, and which preset groups are live because of it.
            if (sub[0].equalsIgnoreCase("where") || sub[0].equalsIgnoreCase("location")
                    || sub[0].equalsIgnoreCase("area")) {
                dumpLocation();
                return true;
            }

            // /sbs sidebar - every sidebar line with the element SBS classified it as and the raw
            // codepoints behind it. The way a missing pattern gets written from what Hypixel actually
            // sends rather than from what we assume it sends.
            if (sub[0].equalsIgnoreCase("sidebar")) {
                dumpSidebar();
                return true;
            }

            // /sbs terminals - the Terminal Simulator practice screen (Dungeons).
            if (sub[0].equalsIgnoreCase("terminals") || sub[0].equalsIgnoreCase("terminal")) {
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreenAndShow(
                        new sbs.modid.client.dungeons.terminal.TerminalSimulatorScreen()));
                return true;
            }

            // /sbs dragons [colour|clear] - the M7 statue positions the dragon boxes are drawn on.
            if (sub[0].equalsIgnoreCase("dragons") || sub[0].equalsIgnoreCase("dragon")) {
                handleDragons(sub.length > 1 ? sub[1].trim() : "");
                return true;
            }

            // /sbs terminaltest - runs the solver against a board per terminal and shows the verdicts.
            if (sub[0].equalsIgnoreCase("terminaltest")) {
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreenAndShow(
                        new sbs.modid.client.dungeons.terminal.TerminalTestScreen()));
                return true;
            }

            // /sbs kuudra mark|area|list|del|clear|reload|export|import - pearl setup management.
            if (sub[0].equalsIgnoreCase("kuudra")) {
                String result = sbs.modid.client.combat.kuudra.logic.PearlStore
                        .handleCommand(sub.length > 1 ? sub[1] : "");
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    for (String line : result.split("\n")) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
                    }
                }
                return true;
            }

            // /sbs posmsg add|party|list|clear - Positional Messages management.
            if (sub[0].equalsIgnoreCase("posmsg")) {
                String result = sbs.modid.client.dungeons.run.logic.PositionalMessages.getInstance()
                        .handleCommand(sub.length > 1 ? sub[1] : "");
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    for (String line : result.split("\n")) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
                    }
                }
                return true;
            }

            // /sbs lane start|end|repeat|area|farm|list - mark Lane End Warning farms and lanes by hand.
            if (sub[0].equalsIgnoreCase("lane")) {
                sbs.modid.client.social.chat.logic.SBSChat.send(sbs.modid.client.skills.farming.logic.LaneEndWarning.getInstance()
                        .handleCommand(sub.length > 1 ? sub[1] : ""));
                return true;
            }

            // /sbs hotm - the Heart of the Mountain perk advisor. Reads nothing but local data.
            if (sub[0].equalsIgnoreCase("hotm")) {
                sbs.modid.client.skills.mining.ui.HotmAdvisorScreen.open();
                return true;
            }

            // /sbs museum - the Missing Donations screen. Reads nothing but local data.
            if (sub[0].equalsIgnoreCase("museum")) {
                sbs.modid.client.helper.museum.ui.MissingDonationsScreen.open();
                return true;
            }

            // /sbs note|avoid|trust|notes - Player Notes. Private, local: nothing reaches the server.
            if (sbs.modid.client.social.notes.logic.PlayerNotesCommands.owns(sub[0])) {
                sbs.modid.client.social.notes.logic.PlayerNotesCommands
                        .handle(sub[0], sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs trackcarry <boss> <player> (alias /sbs carry) - Slayer Carry Counter. Also handles
            // +, -, list, done, clear, goal, auto. All feedback is client-side.
            if (sub[0].equalsIgnoreCase("trackcarry") || sub[0].equalsIgnoreCase("carry")) {
                sbs.modid.client.combat.carry.logic.CarryCounter.getInstance()
                        .handleCommand(sub.length > 1 ? sub[1].trim() : "");
                return true;
            }

            // /sbs rejoin [stop] - arm the Rejoin Timer by hand (kick auto-detect can miss the wording).
            if (sub[0].equalsIgnoreCase("rejoin")) {
                if (sub.length > 1 && sub[1].trim().equalsIgnoreCase("stop")) {
                    sbs.modid.client.helper.rejoin.RejoinTimer.getInstance().stop();
                } else {
                    sbs.modid.client.helper.rejoin.RejoinTimer.getInstance().armManually();
                }
                return true;
            }

            // /sbs waypoint [clear|list] - the coordinates picked up from chat.
            if (sub[0].equalsIgnoreCase("waypoint") || sub[0].equalsIgnoreCase("waypoints")) {
                String result = sbs.modid.client.social.chat.logic.ChatWaypoints.getInstance()
                        .handleCommand(sub.length > 1 ? sub[1] : "");
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    for (String line : result.split("\n")) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
                    }
                }
                return true;
            }

            // /sbs testnotify [pest|cooldown|reminder] - fire a test alert through the channels
            // that alert is actually configured for, and report per channel what took it.
            if (sub[0].equalsIgnoreCase("testnotify") || sub[0].equalsIgnoreCase("testnotification")
                    || sub[0].equalsIgnoreCase("testalert")) {
                testNotification(sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs ping and /sbs tps - the same two numbers !ping and !tps put in party chat, but
            // client-side only. Nothing is sent anywhere: this is the "how bad is it right now"
            // check you run before deciding whether it is worth telling the party.
            if (sub[0].equalsIgnoreCase("ping")) {
                ServerStatsTracker.getInstance().measurePing(
                        ping -> say(" Ping " + (ping < 0 ? "§7unknown" : "§b" + ping + "ms")));
                return true;
            }
            if (sub[0].equalsIgnoreCase("tps")) {
                double tps = ServerStatsTracker.getInstance().tps();
                say(" Tps " + (tps < 0 ? "§7measuring" : String.format(Locale.US, "§b%.1f", tps)));
                return true;
            }

            // /sbs mousemat - the saved crop angles, so they can be read (or copied to a friend)
            // without opening the Mousemat's sign to look at one of them.
            if (sub[0].equalsIgnoreCase("mousemat")) {
                sbs.modid.client.skills.farming.logic.MousematAngles.getInstance().listToChat();
                return true;
            }

            // /sbs beacon - which of the Beacon Tuning gates said no, answered against the live
            // client. A menu helper that draws nothing looks identical to a broken one from the
            // outside, and this one spent its whole life in that state; the answer belongs in chat
            // rather than only in a log file the player has to be told to go and find.
            if (sub[0].equalsIgnoreCase("beacon")) {
                say("§b beacon tuning §7- §f"
                        + sbs.modid.client.skills.foraging.logic.BeaconTuning.getInstance().status());
                return true;
            }

            // /sbs pad [here|confirm|list] (dev mode) - record a jump pad by hand (stand on it: here; where you
            // land: confirm) or list the pads known on this island. Pads are otherwise learned from
            // real launches; see JumpPads.
            if (sub[0].equalsIgnoreCase("pad")) {
                if (!sbs.modid.client.core.dev.DevMode.ACTIVE) {
                    return true;   // dev tooling; silent, like every other dev path
                }
                var player = net.minecraft.client.Minecraft.getInstance().player;
                String arg = sub.length > 1 ? sub[1].toLowerCase(java.util.Locale.ROOT) : "list";
                if (player == null) {
                    return true;
                }
                var pads = sbs.modid.client.core.pathfinding.JumpPads.getInstance();
                say("§b" + switch (arg) {
                    case "here" -> pads.armHere(player);
                    case "confirm" -> pads.confirmHere(player);
                    default -> pads.describeIsland();
                });
                return true;
            }

            // /sbs probe [arm|off|status] - write the open menu to a file, verbatim: every slot's
            // components, its raw LORE and the rendered tooltip beside it. Capture only; it is how a
            // menu parser gets written from what Hypixel sends instead of from an assumption.
            if (sub[0].equalsIgnoreCase("probe")) {
                sbs.modid.client.core.dev.MenuProbe.getInstance()
                        .handleCommand(sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs scan [start|stop|status] [channels] (dev mode) - Server Scanner: every menu the
            // server sends as a slot-level time series, plus the player's clicks and optionally chat,
            // action bar, scoreboard and tab list, as JSONL per session. Capture only.
            if (sub[0].equalsIgnoreCase("scan")) {
                if (!sbs.modid.client.core.dev.DevMode.ACTIVE) {
                    return true;   // dev tooling; silent, like every other dev path
                }
                sbs.modid.client.core.dev.scanner.ServerScanner.handleCommand(sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs logmenu (dev mode) - the open menu once, in full, into the running scan session or
            // a file of its own.
            if (sub[0].equalsIgnoreCase("logmenu")) {
                if (!sbs.modid.client.core.dev.DevMode.ACTIVE) {
                    return true;   // dev tooling; silent, like every other dev path
                }
                sbs.modid.client.core.dev.scanner.ServerScanner.logMenu();
                return true;
            }

            // /sbs priceprobe - for the item under the cursor: its NBT, its lore, its stack size, the
            // exact keys our resolver produces and what every price cache answers for each of them.
            // The command that separates "this item has no price" from "nothing has fetched the
            // Bazaar", which look identical on screen. Developer mode only; capture only.
            if (sub[0].equalsIgnoreCase("priceprobe")) {
                sbs.modid.client.core.dev.PriceProbe.handleCommand();
                return true;
            }

            // /sbs sharddump [arm|off] - for every slot of the last shard menu that was open:
            // its index, its display name, the canonical id our resolver produced and which
            // strategy produced it. The one command that separates "the title matched nothing",
            // "the strategy read nothing" and "the catalogue has no such shard", which look
            // identical on screen. Capture only; `arm` widens it to any container, which is what a
            // menu whose title stopped matching needs.
            if (sub[0].equalsIgnoreCase("sharddump")) {
                sbs.modid.client.core.dev.ShardDump.getInstance()
                        .handleCommand(sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs farming [end] - the Farming Session Summary, or end the running session now.
            if (sub[0].equalsIgnoreCase("farming")) {
                var sessions = sbs.modid.client.skills.farming.session.FarmingSessionTracker.getInstance();
                if (sub.length > 1 && sub[1].trim().equalsIgnoreCase("end")) {
                    if (!sessions.endNow()) {
                        sbs.modid.client.social.chat.logic.SBSChat.send("§7No farming session is running.");
                    }
                } else {
                    net.minecraft.client.Minecraft.getInstance().execute(() -> net.minecraft.client.Minecraft
                            .getInstance().setScreenAndShow(
                                    new sbs.modid.client.skills.farming.session.FarmingSessionScreen(null)));
                }
                return true;
            }

            // /sbs diana [status|clear|debug] - what the Mythological Ritual toolkit currently
            // believes and why it believes it. The bare form answers "why is nothing happening",
            // which is the question a feature that draws nothing always produces.
            if (sub[0].equalsIgnoreCase("diana")) {
                sbs.modid.client.combat.diana.command.DianaCommands
                        .handle(sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs devlog diana [status|mark <text>|rearm|export] - Diana Log Mode: every server signal
            // and player action around the Mythological Ritual, written to a file in order, so the
            // toolkit can be rebuilt from what Hypixel sends. Capture only; the bare form toggles.
            if (sub[0].equalsIgnoreCase("devlog")) {
                sbs.modid.client.combat.diana.command.DianaDevLogCommand
                        .handle(sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs chatprobe [arm|off|status] - the same capture-only idea for chat: every line the
            // client receives, verbatim, with the time since arming. It is how a chat parser gets
            // written from what Hypixel sends rather than from remembered wording.
            if (sub[0].equalsIgnoreCase("chatprobe")) {
                sbs.modid.client.core.dev.ChatProbe.getInstance()
                        .handleCommand(sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs particleprobe [arm|off|status] - write every particle packet the server sends to a
            // file: type, count, position, offsets, speed and colour, with the player's own position
            // beside each. Capture only; it is how a particle-driven feature gets written from what
            // Hypixel sends instead of from an assumption.
            if (sub[0].equalsIgnoreCase("particleprobe")) {
                sbs.modid.client.core.dev.ParticleProbe.getInstance()
                        .handleCommand(sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs soundprobe [arm|off|status] - write every sound the server plays near you to a
            // file: id, pitch, the note that pitch encodes, volume, position and the gap since the
            // previous sound of the same id. Capture only; it is how a sound-driven feature learns
            // whether a pitch is real data or a fixed cue instead of assuming.
            if (sub[0].equalsIgnoreCase("soundprobe")) {
                sbs.modid.client.core.dev.SoundProbe.getInstance()
                        .handleCommand(sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs m7probe [arm|off|status] - the M7 dragon phase, all four blocked questions in one
            // capture: what our phase gate says against the player's Y, every particle packet scored
            // against the spawn-effect criteria, every entity spawn, and every metadata index on a
            // dragon with its value. Armed through a whole boss fight; capture only.
            if (sub[0].equalsIgnoreCase("m7probe")) {
                sbs.modid.client.core.dev.M7DragonProbe.getInstance()
                        .handleCommand(sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs entityprobe [arm|off|status] - bare form snapshots every entity near you with its
            // nametag; armed, it records only the nametag CHANGES, which is where a timed mechanic's
            // states and durations come from. Capture only.
            // /sbs soulprobe - log every head (block or worn) within 3 blocks with its skin URL, and
            // the nearest curated soul. Settles what a Fairy Soul actually is. Capture only.
            if (sub[0].equalsIgnoreCase("soulprobe")) {
                sbs.modid.client.helper.fairysouls.logic.FairySoulTracker.getInstance().probe();
                return true;
            }

            if (sub[0].equalsIgnoreCase("entityprobe")) {
                sbs.modid.client.core.dev.EntityProbe.getInstance()
                        .handleCommand(sub.length > 1 ? sub[1] : "");
                return true;
            }

            // /sbs sweep capture [on|off|status] - record Hypixel's per-chop foraging lines to a
            // file, verbatim, with every non-ASCII character as its codepoint. Capture only; it is
            // how the Sweep parser gets pinned to what Hypixel really sends instead of to a guess.
            if (sub[0].equalsIgnoreCase("sweep")) {
                String rest = sub.length > 1 ? sub[1] : "";
                if (rest.equalsIgnoreCase("capture")) {
                    rest = sub.length > 2 ? sub[2] : "";
                }
                sbs.modid.client.skills.foraging.logic.SweepCapture.getInstance().handleCommand(rest);
                return true;
            }

            // /sbs honey [held|clear] - the honey tree timers. Bare form lists what is running;
            // "held" says whether the item in hand would arm a timer, which is how an unverified
            // Honeycomb id gets corrected without a new build.
            if (sub[0].equalsIgnoreCase("honey")) {
                var timers = sbs.modid.client.skills.foraging.logic.HoneyTreeTimers.getInstance();
                String arg = sub.length > 1 ? sub[1].toLowerCase(Locale.ROOT) : "";
                switch (arg) {
                    case "held" -> sbs.modid.client.social.chat.logic.SBSChat
                            .send(timers.describeHeld());
                    case "clear" -> {
                        int removed = sbs.modid.client.skills.foraging.logic.HoneyTimerStore
                                .getInstance().clear();
                        sbs.modid.client.social.chat.logic.SBSChat.send(
                                removed == 0 ? "No honey timers to clear."
                                        : "Cleared " + removed + " honey timer(s).");
                    }
                    default -> {
                        sbs.modid.client.social.chat.logic.SBSChat.send(timers.status());
                        for (var timer : timers.sorted()) {
                            sbs.modid.client.social.chat.logic.SBSChat.send("  "
                                    + timer.island + " · "
                                    + sbs.modid.client.skills.foraging.logic.HoneyTreeTimers
                                            .displayName(timer)
                                    + "  "
                                    + sbs.modid.client.skills.foraging.model.HoneyTimer.clock(
                                            timer.remainingMs(System.currentTimeMillis()))
                                    + (timer.confirmed ? "" : "  (unconfirmed)"));
                        }
                    }
                }
                return true;
            }

            // /sbs freecam - toggles cinematic freecam (not under //: it is not a build tool).
            // /sbs freecam packets (dev mode) - sent vs blocked packet types since freecam started,
            // the tester's proof of the "nothing extra is sent" rule.
            if (sub[0].equalsIgnoreCase("freecam")) {
                if (sub.length > 1 && sub[1].equalsIgnoreCase("packets")) {
                    if (sbs.modid.client.core.dev.DevMode.ACTIVE) {
                        say("§b freecam packets §7- §f"
                                + sbs.modid.client.helper.build.logic.FreecamPacketGuard.report());
                    }
                    return true;
                }
                sbs.modid.client.helper.build.logic.Freecam.toggle(
                        sbs.modid.client.helper.build.model.FreecamRules.Mode.CINEMATIC);
                return true;
            }

            // /sbs commission [blocks|reload] - what each running commission resolved to, the block
            // ids around you (the capture that fills the material list), and a reload after editing.
            if (sub[0].equalsIgnoreCase("commission") || sub[0].equalsIgnoreCase("commissions")) {
                var route = sbs.modid.client.skills.mining.logic.CommissionRoute.getInstance();
                String arg = sub.length > 1 ? sub[1].toLowerCase(Locale.ROOT) : "";
                switch (arg) {
                    case "blocks" -> route.blocksToChat();
                    case "reload" -> route.reloadBlocks();
                    default -> route.listToChat();
                }
                return true;
            }

            if (sub[0].equalsIgnoreCase("itemrename")) {
                renameHeldItem(sub.length > 1 ? sub[1] : "");
                return true;
            }
            if (sub[0].equalsIgnoreCase("itemoriginalname") || sub[0].equalsIgnoreCase("itemorginalname")) {
                restoreHeldItemName();
                return true;
            }
        }

        openMainScreen();
        return true; // ours – handled locally, do not send to server
    }

    /**
     * Fires a test alert and reports, per channel, what actually took it.
     *
     * <p>The diagnostic for the alert system: it answers "is the channel I picked working" without
     * waiting for a real pest to spawn. With no argument it exercises every channel; with one
     * ({@code /sbs testnotify pest}) it fires exactly what that alert is configured for, which is
     * what tells a player whether their own selection reaches them.
     */
    private static void testNotification(String argument) {
        var config = sbs.modid.client.core.config.ConfigManager.getInstance().get();
        String which = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);

        int mask;
        String what;
        switch (which) {
            case "pest", "spawn" -> {
                mask = config.garden.pestSpawnChannels;
                what = "the pest spawn alert";
            }
            case "cooldown", "ready" -> {
                mask = config.garden.pestReadyChannels;
                what = "the pest cooldown alert";
            }
            case "reminder", "reminders" -> {
                mask = config.reminders.extraChannels;
                what = "the reminder alert";
            }
            default -> {
                mask = AlertChannel.CHAT.bit() | AlertChannel.TITLE.bit()
                        | AlertChannel.SOUND.bit() | AlertChannel.NARRATOR.bit();
                what = "every channel";
            }
        }
        say(" Testing " + what + ": " + AlertChannels.describe(mask));
        if (!AlertChannels.any(mask)) {
            say(" That alert has no channels selected, so it would tell you nothing. Pick one on "
                    + "the feature's own settings page.");
            return;
        }

        Map<AlertChannel, Boolean> results = Alerts.send(
                new Alerts.Alert("SkyBlock Simplified", "Test alert - this is what it looks like.",
                        sbs.modid.client.core.audio.SbsAudio.Tone.CHIME, null),
                mask);
        for (AlertChannel channel : AlertChannel.values()) {
            Boolean delivered = results.get(channel);
            if (delivered == null) {
                continue;   // not selected for this alert - nothing to report
            }
            say((delivered ? " §a+ " : " §c- ") + "§r" + channel.displayName()
                    + (delivered ? "" : " could not deliver" + reason(channel)));
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] /sbs testnotify {} -> {}", which, results);
    }

    /** Why a channel could not take the test, in the words that point at the fix. */
    private static String reason(AlertChannel channel) {
        return switch (channel) {
            case SOUND -> " - " + sbs.modid.client.core.audio.SbsAudio.statusText();
            case NARRATOR -> " - text-to-speech is unavailable on this system";
            case HUD -> " - this alert has no HUD element of its own";
            default -> " - see the log";
        };
    }

    /**
     * Prints every sidebar line with the Custom Scoreboard element it classified as, and the
     * codepoints of anything that is not plain ASCII.
     *
     * <p>The point is the codepoints. A marker glyph is invisible in a bug report - it arrives as
     * whatever the reader's font draws, and two different characters look identical - so a pattern
     * written from a screenshot is a guess. This prints what Hypixel actually sent, which is what a
     * new pattern in the element data file has to match.
     */
    private static void dumpSidebar() {
        List<sbs.modid.client.helper.scoreboard.ScoreboardLine> lines =
                sbs.modid.client.helper.scoreboard.ScoreboardReader.lines();
        if (lines.isEmpty()) {
            say(" no sidebar - stand on SkyBlock");
            return;
        }
        java.util.List<String> ids =
                sbs.modid.client.helper.scoreboard.ScoreboardElements.idsFor(lines);
        say("§b sidebar: " + lines.size() + " lines, element data v"
                + sbs.modid.client.helper.scoreboard.ScoreboardElements.version() + " ("
                + sbs.modid.client.helper.scoreboard.ScoreboardElements.source() + ")");
        for (int i = 0; i < lines.size(); i++) {
            String stripped = lines.get(i).stripped();
            say(" §7" + i + " §f" + ids.get(i) + " §8| §7'" + stripped + "'" + codepoints(stripped));
        }
        say("§7 zone=' " + sbs.modid.client.core.location.SkyBlockLocation.zone()
                + "' island='" + sbs.modid.client.core.location.SkyBlockLocation.island() + "'");
    }

    /**
     * {@code /sbs protect [list|clear]} - the Item Protection list.
     *
     * <p>Bare opens the management screen, which is the same thing the module's settings button
     * does; {@code list} prints it into chat for when the player wants to read it without leaving
     * the menu they are in; {@code clear} empties both lists and says how much it removed.
     *
     * <p>Unrecognised arguments print the usage rather than being swallowed - this is a player
     * command, not one of the silent dev paths above.
     */
    private static void handleProtect(String argument) {
        var store = sbs.modid.client.helper.itemprotection.logic.ProtectedItems.getInstance();
        String action = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);

        if (action.isEmpty() || action.equals("gui") || action.equals("manage")) {
            Minecraft.getInstance().execute(
                    sbs.modid.client.helper.itemprotection.ItemProtectionModule::openManager);
            return;
        }
        if (action.equals("list")) {
            var items = store.uniqueRows();
            var types = store.typeRows();
            if (items.isEmpty() && types.isEmpty()) {
                say("Nothing is protected. Bind the Mark key in the Item Protection module, then "
                        + "press it while hovering an item.");
                return;
            }
            say("Protected: " + items.size() + " item(s), " + types.size() + " type(s).");
            for (var row : items) {
                say("  " + row.entry().label());
            }
            for (var row : types) {
                say("  every " + row.entry().label());
            }
            return;
        }
        if (action.equals("clear")) {
            int removed = store.clear();
            say(removed == 0 ? "Nothing was protected." : "Cleared " + removed + " protected entr"
                    + (removed == 1 ? "y" : "ies") + ".");
            return;
        }
        say("Usage: /sbs protect [list|clear]");
    }

    /**
     * {@code /sbs where} - the location debug command: the answer, the raw rows it was derived from,
     * and every gate that hangs off it.
     *
     * <p><b>There is no {@code locraw} in this mod and this is the command that shows why.</b>
     * Hypixel's {@code /locraw} is a chat round-trip; the location here is read straight off the two
     * things the server already pushes - the sidebar's {@code ⏣} line and the tab list's
     * {@code Area:} / {@code Dungeon:} rows. Both are printed verbatim, with the codepoints of the
     * zone, because "the row does not say what we read it as" is the single most common cause of a
     * location gate answering something nobody expected, and a paraphrase of the row cannot show it.
     *
     * <p>The Critter Safari gets its own block. It is the one instance whose entrance sits on another
     * island and carries its name, so "am I in the Safari" and "does the area contain the word
     * Safari" are different questions with different answers, and both are printed rather than
     * summarised into one verdict.
     */
    private static void dumpLocation() {
        String zone = sbs.modid.client.core.location.SkyBlockLocation.zone();
        String island = sbs.modid.client.core.location.SkyBlockLocation.island();
        String tabArea = sbs.modid.client.core.location.SkyBlockLocation.tabArea();
        String dungeon = sbs.modid.client.core.location.SkyBlockLocation.dungeonType();

        say("§b location");
        say(" §7detected §fisland§7='§f" + island + "§7' §fzone§7='§f" + zone + "'"
                + codepoints(zone));
        say(" §7          describe='§f"
                + sbs.modid.client.core.location.SkyBlockLocation.describe() + "§7'");

        // The raw halves. No locraw: these two rows ARE the source, so they are what gets shown.
        say(" §7raw tab   §fArea:§7='§f" + (tabArea.isEmpty() ? "§8(not served)" : tabArea) + "§7'"
                + " §fDungeon:§7='§f" + (dungeon.isEmpty() ? "§8(none)" : dungeon) + "§7'");
        String marker = "";
        for (String line : sbs.modid.client.core.location.SkyBlockLocation.sidebarLines()) {
            if (line.indexOf('⏣') >= 0) {
                marker = line;
                break;
            }
        }
        say(" §7raw board §f⏣ line§7='§f" + (marker.isEmpty() ? "§8(no sidebar)" : marker) + "§7'");
        // Which of the two the island actually came from, because that is the half a wrong answer
        // is usually hiding in.
        say(" §7island came from: §f"
                + (!tabArea.isEmpty() ? "the tab list's Area: row"
                : !dungeon.isEmpty() ? "the tab list's Dungeon: row"
                : zone.isEmpty() ? "nothing - nothing is readable"
                : "IslandCatalog resolving the zone (a seed-table guess)"));

        // The Critter Safari, the instance the island gates keep getting wrong.
        boolean inSafari = sbs.modid.client.core.location.SkyBlockLocation.inCritterSafari();
        boolean gate = sbs.modid.client.skills.hunting.logic.SafariTracker.inSafariArea();
        say("§b critter safari");
        say(" §7inCritterSafari (exact, waypoint scope)  §f" + (inSafari ? "§ayes" : "§cno"));
        say(" §7inSafariArea    (configured word, boxes) §f" + (gate ? "§ayes" : "§cno"));
        say(" §7at the entrance zone: §f"
                + (sbs.modid.client.core.location.SkyBlockLocation.CRITTER_SAFARI_ENTRANCE
                        .equalsIgnoreCase(zone.trim()) ? "§eyes - on Torrhus Canyon, not in the Safari"
                        : "§7no"));

        // The groups, and for each the reason it is or is not live.
        var here = sbs.modid.client.skills.foraging.logic.WaypointPresetDatabase.activeHere();
        var overrides = sbs.modid.client.skills.foraging.logic.WaypointPresetOverrides.getInstance();
        say("§b preset groups §7("
                + sbs.modid.client.skills.foraging.logic.WaypointPresetDatabase.status() + ")");
        for (var group : sbs.modid.client.skills.foraging.logic.WaypointPresetDatabase.groups()) {
            boolean belongs = here.contains(group);
            boolean on = overrides.groupEnabled(group.id, group.enabledByDefault);
            String state = !belongs ? "§8elsewhere" : on ? "§aACTIVE" : "§eoff";
            say(" " + state + " §f" + group.name + " §7[" + group.id + "] §8" + group.scope()
                    + " §7" + group.points.size() + "pt");
        }
        say(" §7publisher: §f"
                + sbs.modid.client.skills.foraging.logic.WaypointPresetPublisher.getInstance().status());
        say(" §7gemzie:    §f"
                + sbs.modid.client.skills.hunting.logic.GemzieWaypointPublisher.getInstance().status());
    }

    /** The non-ASCII characters of {@code text} as {@code U+XXXX}, or {@code ""} when there are none. */
    private static String codepoints(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().filter(cp -> cp > 0x7F).forEach(cp ->
                out.append(out.isEmpty() ? " §8[" : " ").append(String.format("U+%04X", cp)));
        return out.isEmpty() ? "" : out.append("]").toString();
    }

    /** One client-side chat line, safely dropped when there is no player yet. */
    private static void say(String text) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[SBS]" + text));
        }
    }

    /**
     * Renames the held item client-side and PERSISTENTLY: the new name is stored in the config
     * against the item's identity (SkyBlock uuid, else id) and re-applied live on every name read –
     * so it survives restarts and server swaps without ever touching the real item data.
     */
    private static void renameHeldItem(String rawName) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        var config = sbs.modid.client.core.config.ConfigManager.getInstance();
        if (!config.get().itemOverlay.itemRenamer) {
            minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[SBS] Item Renamer is disabled (Item Overlay module)."));
            return;
        }
        // Quotes are optional: /sbs itemrename "new name" and /sbs itemrename new name both work.
        String name = rawName.trim().replaceAll("^\"|\"$", "");
        var held = minecraft.player.getMainHandItem();
        if (held == null || held.isEmpty()) {
            minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[SBS] Hold the item you want to rename."));
            return;
        }
        String key = sbs.modid.client.core.item.SkyblockItem.uniqueKey(held);
        if (key == null) {
            minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[SBS] This item has no SkyBlock identity to remember the rename by."));
            return;
        }
        if (name.isEmpty()) {
            config.get().itemOverlay.itemRenames.remove(key);
        } else {
            config.get().itemOverlay.itemRenames.put(key, name);
        }
        config.save();
        minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                name.isEmpty() ? "[SBS] Item name restored."
                        : "[SBS] Item renamed to \"" + name + "\" (client-side, permanent)."));
    }

    /** Restores the held item's original name ({@code /sbs itemoriginalname}). */
    private static void restoreHeldItemName() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        var config = sbs.modid.client.core.config.ConfigManager.getInstance();
        var held = minecraft.player.getMainHandItem();
        String key = held == null || held.isEmpty()
                ? null : sbs.modid.client.core.item.SkyblockItem.uniqueKey(held);
        if (key == null || config.get().itemOverlay.itemRenames.remove(key) == null) {
            minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[SBS] The held item has no stored rename."));
            return;
        }
        config.save();
        minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[SBS] Item name restored to the original."));
    }

    private static void openMainScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        // Defer until after the chat screen closes, then show the overlay.
        // (This project's mappings use setScreenAndShow(Screen), not setScreen.)
        minecraft.execute(() -> minecraft.setScreenAndShow(new SBSMainScreen()));
    }

    /** {@code /sbs keybind} – the keybind / shortcut editor, deferred like every other screen open. */
    private static void openKeybindsScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreenAndShow(
                new sbs.modid.client.core.keybind.CommandKeybindsScreen()));
    }

    /**
     * {@code /sbs wizard ...} – dev tooling for the first-run overlay and the update showcase.
     *
     * <p>Each state record resets on its own, because they answer different questions and a reset
     * that cleared all three could not be used to test any one of them. Screen opens are deferred
     * through {@code minecraft.execute} like every other command that opens a screen - the chat
     * screen is still closing when this runs.
     */
    private static void handleWizard(String rest) {
        Minecraft minecraft = Minecraft.getInstance();
        String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        var state = sbs.modid.client.ui.wizard.logic.WizardAccount.state();
        String action = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "status";

        switch (action) {
            case "show", "onboarding" -> minecraft.execute(() -> {
                if (!sbs.modid.client.ui.wizard.logic.WizardTrigger.forceOpen(
                        sbs.modid.client.ui.wizard.WizardMode.ONBOARDING)) {
                    say("No onboarding pages are registered (or none resolve).");
                }
            });
            case "showcase" -> minecraft.execute(() -> {
                if (!sbs.modid.client.ui.wizard.logic.WizardTrigger.forceOpen(
                        sbs.modid.client.ui.wizard.WizardMode.SHOWCASE)) {
                    say("No showcase pages are registered yet.");
                }
            });
            case "page" -> {
                if (args.length < 2) {
                    say("Usage: /sbs wizard page <id>");
                    return;
                }
                minecraft.execute(() -> {
                    if (!sbs.modid.client.ui.wizard.logic.WizardTrigger.forcePage(args[1])) {
                        say("No page '" + args[1] + "'. Try /sbs wizard list.");
                    }
                });
            }
            case "list" -> {
                say("Registered wizard pages:");
                for (var page : sbs.modid.client.ui.wizard.WizardPages.all()) {
                    say(" - " + page.id() + "  [" + page.mode() + ", order " + page.order()
                            + ", since " + page.introducedIn() + "]"
                            + (state.seen(page.id()) ? " (seen)" : ""));
                }
            }
            case "reset" -> {
                String what = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
                switch (what) {
                    case "onboarding" -> {
                        state.resetOnboarding();
                        say("Onboarding reset. Showcase and opt-out untouched.");
                    }
                    case "showcase" -> {
                        state.resetShowcase();
                        say("Showcase version cleared. Onboarding untouched.");
                    }
                    case "optout" -> {
                        state.setShowcaseOptOut(false);
                        say("Update notices switched back on.");
                    }
                    default -> {
                        say("Usage: /sbs wizard reset <onboarding|showcase|optout|page>");
                        say("Each one resets only itself, on purpose.");
                        return;
                    }
                }
                sbs.modid.client.ui.wizard.logic.WizardTrigger.rearm();
            }
            case "forget" -> {
                if (args.length < 2) {
                    say("Usage: /sbs wizard forget <pageId>");
                    return;
                }
                state.forget(args[1]);
                sbs.modid.client.ui.wizard.logic.WizardTrigger.rearm();
                say("Forgot page '" + args[1] + "' - it is due again.");
            }
            default -> {
                say("Onboarding done: " + state.onboardingCompleted()
                        + " | pages seen: " + state.seenPages().size()
                        + " | showcase at: " + (state.storedShowcaseVersion() == null
                        ? "never recorded" : state.storedShowcaseVersion())
                        + " | opted out: " + state.showcaseOptOut());
                say("Mod version: " + sbs.modid.client.ui.wizard.ModVersionSource.current()
                        .map(Object::toString).orElse("not parseable (fabric.mod.json)"));
                say("/sbs wizard show | showcase | page <id> | list | reset <what> | forget <id>");
            }
        }
    }

    /**
     * {@code /sbs mayor [item]} – the read-only mayor window, deferred until the chat screen closes.
     * A blank argument opens the base window (active mayor + election); an item argument opens the
     * item variant (adds its price effect + mayor history). The typed text is normalised to a Hypixel
     * id (upper snake case) for the {@code ?item=} query; the server does the actual matching.
     */
    private static void openMayorScreen(String rawItem) {
        String display = rawItem == null ? "" : rawItem.trim();
        String itemId = normalizeItemId(display);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreenAndShow(itemId.isEmpty()
                ? new sbs.modid.client.economy.mayor.MayorScreen()
                : new sbs.modid.client.economy.mayor.MayorScreen(itemId, display)));
    }

    /**
     * {@code /sbs map} opens the island map; {@code /sbs map <place>} skips the screen and travels to
     * the best match by name.
     *
     * <p>The named form exists because it is the fastest path to what the map is actually for - and
     * because the search that backs it already ranks a place on your own island above one of the same
     * name elsewhere, which is nearly always the one meant.
     */
    private static void handleMap(String query) {
        Minecraft minecraft = Minecraft.getInstance();
        if (query == null || query.isBlank()) {
            minecraft.execute(() -> sbs.modid.client.helper.map.MapModule.openScreen());
            return;
        }
        var matches = sbs.modid.client.helper.map.logic.MapDatabase.search(query);
        if (matches.isEmpty()) {
            sbs.modid.client.social.chat.logic.SBSChat.send(
                    net.minecraft.network.chat.Component.literal(
                            " No place on the map matches \"" + query + "\".").withColor(0xFF6B6B));
            return;
        }
        var location = matches.getFirst();
        minecraft.execute(() -> sbs.modid.client.helper.map.logic.MapNavigation.getInstance()
                .travelTo(location.map, location));
    }

    private static void handleHollowsMap(String args) {
        Minecraft minecraft = Minecraft.getInstance();
        String[] parts = args.split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";
        var tracker = sbs.modid.client.helper.map.logic.HollowsMapTracker.getInstance();
        switch (verb) {
            case "" -> minecraft.execute(sbs.modid.client.helper.map.ui.HollowsMapScreen::open);
            case "mark" -> minecraft.execute(() -> sbs.modid.client.social.chat.logic.SBSChat.send(
                    " " + tracker.mark(rest)));
            case "unmark" -> minecraft.execute(() -> sbs.modid.client.social.chat.logic.SBSChat.send(
                    " " + tracker.unmark(rest)));
            default -> sbs.modid.client.social.chat.logic.SBSChat.send(
                    " Usage: /sbs chmap, /sbs chmap mark <label>, /sbs chmap unmark [label]");
        }
    }

    /** "necron chestplate" / "hyperion" -> "NECRON_CHESTPLATE" / "HYPERION"; "" stays "". */
    private static String normalizeItemId(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    /**
     * {@code /pv [player]} – opens the native SBS profile viewer (fed by the cloud's {@code /api/pv}
     * endpoint). Without an argument it shows the player's own profile.
     */
    private static void openProfileViewer(String player) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!sbs.modid.client.core.config.ConfigManager.getInstance().get().playerViewer.profileViewer) {
            sysMessage(minecraft, "[SBS] Profile Viewer is disabled (Overlays > Player Viewer).");
            return;
        }
        String name = player.replaceAll("[^A-Za-z0-9_]", "");
        if (name.isEmpty()) {
            name = minecraft.player != null ? minecraft.player.getGameProfile().name() : "";
        }
        if (name.isEmpty()) {
            return;
        }
        String target = name;
        minecraft.execute(() -> minecraft.setScreenAndShow(
                new sbs.modid.client.social.playerviewer.ui.PlayerViewerScreen(target)));
    }

    /**
     * {@code /sbs dragons} lists the M7 statue positions the dragon boxes are drawn on,
     * {@code /sbs dragons <power|apex|flame|ice|soul>} moves one to where you are standing, and
     * {@code /sbs dragons clear} forgets the lot.
     *
     * <p>The positions learn themselves from where each dragon comes up, so this exists for the one
     * case that cannot be automated away: a box that sits wrong. Standing at the statue and naming
     * the colour is a five-second fix that then holds for every run.
     */
    private static void handleDragons(String argument) {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null) {
            return;
        }
        var tracker = sbs.modid.client.dungeons.floorseven.logic.DragonTracker.getInstance();
        if (argument.equalsIgnoreCase("clear")) {
            sbs.modid.client.dungeons.floorseven.logic.DragonTracker.clearStatues();
            sysMessage(minecraft, "[SBS] Dragon statues forgotten - they will learn themselves again.");
            return;
        }
        if (!argument.isEmpty() && !argument.equalsIgnoreCase("list")) {
            var kind = sbs.modid.client.dungeons.floorseven.logic.DragonTracker.Kind.byWord(argument);
            if (kind == null) {
                sysMessage(minecraft, "[SBS] /sbs dragons <power|apex|flame|ice|soul|list|clear>");
                return;
            }
            sbs.modid.client.dungeons.floorseven.logic.DragonTracker.setStatue(kind, player.position());
            sysMessage(minecraft, "[SBS] " + kind.label() + " statue set to where you are standing.");
            return;
        }
        StringBuilder lines = new StringBuilder("[SBS] Dragon statues:");
        for (var kind : sbs.modid.client.dungeons.floorseven.logic.DragonTracker.Kind.values()) {
            if (kind == sbs.modid.client.dungeons.floorseven.logic.DragonTracker.Kind.UNKNOWN) {
                continue;
            }
            var statue = tracker.statueOf(kind);
            lines.append("\n  ").append(kind.label()).append(": ").append(statue == null ? "not yet seen"
                    : Math.round(statue.x) + " " + Math.round(statue.y) + " " + Math.round(statue.z));
        }
        for (String line : lines.toString().split("\n")) {
            sysMessage(minecraft, line);
        }
    }

    /**
     * {@code /sbs party} opens the Party Finder overlay; {@code /sbs party <message>} sends a line to
     * the player's current party chat (see {@link sbs.modid.client.social.party.logic.PartyFinderManager}).
     */
    private static void handleParty(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!sbs.modid.client.core.config.ConfigManager.getInstance().get().partyFinder.enabled) {
            sysMessage(minecraft, "[SBS] Party Finder is disabled (Overlays > SBS Party Finder).");
            return;
        }
        if (message.isEmpty()) {
            minecraft.execute(() -> minecraft.setScreenAndShow(
                    new sbs.modid.client.social.party.ui.PartyFinderScreen()));
            return;
        }
        sbs.modid.client.social.party.logic.PartyFinderManager.getInstance().sendChat(message);
    }

    /**
     * {@code /sbs skycrypt [player]} – opens SkyCrypt in one of the in-game browser windows
     * (the exact same floating windows the Item Price History uses). Without a player it
     * opens the landing page; with one it jumps straight to that player's profile.
     */
    private static void openSkycrypt(String player) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!sbs.modid.client.core.config.ConfigManager.getInstance().get().playerViewer.enabled) {
            sysMessage(minecraft, "[SBS] Player Viewer is disabled (Overlays > Player Viewer).");
            return;
        }
        String name = player.replaceAll("[^A-Za-z0-9_]", ""); // Minecraft-Namen sind [A-Za-z0-9_]
        String url = name.isEmpty() ? "https://sky.shiiyu.moe/"
                : "https://sky.shiiyu.moe/stats/" + name;
        String title = name.isEmpty() ? "SkyCrypt" : "SkyCrypt: " + name;
        // Defer until after the chat screen closes, like openMainScreen.
        minecraft.execute(() -> {
            if (!sbs.modid.client.economy.pricehistory.ui.PriceBrowserManager.getInstance().openNewUrl(url, title)) {
                sysMessage(minecraft, "[SBS] Too many browser windows open - close one first.");
            }
        });
    }

    /**
     * {@code /sbsdev} toggles developer mode; {@code /sbsdev origin} prints the current grid origin + the
     * player's cell (for calibration); {@code /sbsdev origin <x> <z>} sets and persists the grid origin;
     * {@code /sbsdev blood} prints the blood helper's view of the room it is standing in.
     */
    private static void handleSbsDev(String args) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty()) {
            sbs.modid.client.core.dev.DevMode.toggle();
            return;
        }
        String[] tokens = trimmed.split("\\s+");
        // "/sbsdev blood" - what the blood helper currently thinks the room is, and what the nametags
        // around you actually read. The one thing worth printing about a feature whose rules are
        // otherwise only visible as boxes appearing or not appearing.
        if (tokens[0].equalsIgnoreCase("blood")) {
            Minecraft client = Minecraft.getInstance();
            for (String line : sbs.modid.client.dungeons.blood.logic.BloodRoomTracker
                    .getInstance().debugLines()) {
                sysMessage(client, line);
            }
            // The camp call-out runs on its own clock and its own chat lines, so it answers for
            // itself - "no flash came" and "the feature is off" look identical from the outside.
            for (String line : sbs.modid.client.dungeons.blood.logic.BloodMoveTimer
                    .getInstance().debugLines()) {
                sysMessage(client, line);
            }
            return;
        }
        if (!tokens[0].equalsIgnoreCase("origin")) {
            sbs.modid.client.core.dev.DevMode.toggle();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        var config = sbs.modid.client.core.config.ConfigManager.getInstance();
        if (tokens.length >= 3) {
            try {
                int x = Integer.parseInt(tokens[1]);
                int z = Integer.parseInt(tokens[2]);
                config.get().dungeons.gridOriginX = x;
                config.get().dungeons.gridOriginZ = z;
                config.save();
                sbs.modid.client.dungeons.run.model.DungeonGrid.loadFromConfig();
                sysMessage(minecraft, "§a[SBS] Grid origin set to " + x + ", " + z);
            } catch (NumberFormatException e) {
                sysMessage(minecraft, "§c[SBS] Usage: /sbsdev origin <x> <z>");
            }
            return;
        }
        int ox = config.get().dungeons.gridOriginX;
        int oz = config.get().dungeons.gridOriginZ;
        String cell = "";
        if (minecraft.player != null) {
            var p = minecraft.player.blockPosition();
            cell = " §7| your cell NW "
                    + sbs.modid.client.dungeons.run.model.DungeonGrid.cellMinX(sbs.modid.client.dungeons.run.model.DungeonGrid.cellIndexX(p.getX()))
                    + "," + sbs.modid.client.dungeons.run.model.DungeonGrid.cellMinZ(sbs.modid.client.dungeons.run.model.DungeonGrid.cellIndexZ(p.getZ()));
        }
        sysMessage(minecraft, "§7[SBS] Grid origin " + ox + "," + oz + cell);
    }

    private static void sysMessage(Minecraft minecraft, String message) {
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        }
    }

    /**
     * {@code /sbstest [clear]} – a developer-only highlight test: drops a test waypoint on the player's current
     * block (independent of any room detection) so the render path can be verified in isolation. Does
     * nothing (and stays silent) unless developer mode is active.
     */
    private static void handleSbsTest(String argument) {
        if (!sbs.modid.client.core.dev.DevMode.ACTIVE) {
            return; // dev-only; consumed silently when off
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        sbs.modid.client.dungeons.run.render.DungeonHighlight highlight = sbs.modid.client.dungeons.run.render.DungeonHighlight.getInstance();
        if (argument.trim().equalsIgnoreCase("clear")) {
            highlight.clearTestWaypoints();
            sbs.modid.client.dungeons.run.logic.DungeonDebug.chat("§7Cleared all test waypoints.");
            return;
        }
        net.minecraft.core.BlockPos pos = minecraft.player.blockPosition();
        highlight.addTestWaypoint(pos);
        sbs.modid.client.dungeons.run.logic.DungeonDebug.chat(String.format(
                "§aTest waypoint at %d,%d,%d §7(total %d) — use §f/sbstest clear§7 to remove.",
                pos.getX(), pos.getY(), pos.getZ(), highlight.testWaypointCount()));
    }

    /**
     * Runs a command or chat message exactly the way submitting it in chat does – the
     * single entry point reused by anything that needs to "type" for the player (e.g.
     * the Command Keybinds module).
     *
     * <p>This mirrors {@code ChatScreen.handleChatInput}: our own commands ({@code /sbs},
     * {@code /skyblocksimplified}) are handled locally via {@link #tryExecute(String)};
     * everything else goes through the player's {@code ClientPacketListener} using the
     * official {@code sendCommand} / {@code sendChat} – no Fabric command API, so it stays
     * compatible with current Minecraft versions.
     *
     * @param rawInput the command/message, with or without a leading {@code /}
     */
    public static void run(String rawInput) {
        if (rawInput == null) {
            return;
        }
        String input = rawInput.trim();
        if (input.isEmpty()) {
            return;
        }
        // Expand a command shortcut (alias -> full command) once, so shortcuts work everywhere
        // the mod runs text: keybinds, the command-keybinds module, other shortcuts.
        input = sbs.modid.client.core.keybind.CommandShortcutManager.getInstance().expand(input);
        boolean isCommand = input.startsWith("/");
        String body = isCommand ? input.substring(1) : input;

        // Our own commands first (e.g. /sbs opens the overlay), exactly like typing them.
        if (isCommand && tryExecute(body)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.connection == null) {
            return; // not connected / not in a world
        }
        try {
            if (isCommand) {
                minecraft.player.connection.sendCommand(body);
            } else {
                minecraft.player.connection.sendChat(input);
            }
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to run '{}'", input, t);
        }
    }
}
