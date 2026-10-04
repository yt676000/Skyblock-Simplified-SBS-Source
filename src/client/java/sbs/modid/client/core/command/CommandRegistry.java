/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Every command the mod answers to, written down once.
 *
 * <p><b>Why this exists.</b> The commands used to be known in three places that did not agree: the
 * dispatch in {@link SBSCommands}, a hand-kept suggestion table in {@code ClientCommandsMixin} (which
 * had drifted to about half of what the dispatch handles), and nowhere a player could read them. The
 * suggestions, the Commands page and {@code /sbs help} now all read this list, and
 * {@code CommandRegistryTest} fails the build when the dispatch answers to a name this list does not
 * know, or this list names something the dispatch does not answer to.
 *
 * <p><b>Visibility.</b> {@link Visibility#PUBLIC} commands are suggested on Tab, listed on the page
 * and in help. {@link Visibility#DEV_ONLY} ones are listed only while developer mode is on, in their
 * own section, and never suggested - so Tab looks exactly as it did for every player. {@link
 * Visibility#HIDDEN} is {@code /sbs developermode}: never listed, never suggested, anywhere; its whole
 * contract is to be silent.
 *
 * <p>Pure data plus lambdas: loading this class touches no Minecraft type, so the drift test runs
 * without a game. A module gate is a lambda and only reads the config when asked.
 */
public final class CommandRegistry {

    public enum Visibility {
        PUBLIC, DEV_ONLY, HIDDEN
    }

    /**
     * One command.
     *
     * @param root        the typed root without its slash: {@code "sbs"}, {@code "pv"}, {@code "pw"}
     * @param name        the {@code /sbs} subcommand, or {@code ""} for a root command
     * @param aliases     other names the dispatch accepts for the same thing
     * @param args        what follows, as the player reads it: {@code "clear|list"}, {@code "<player>"}
     * @param tab         what the suggestion tree registers after the name: {@code ""} nothing,
     *                    {@code "<word>"}, {@code "<text>"}, {@code "a|b"} fixed choices, or
     *                    {@code null} when the suggestions are built by hand (live name lists)
     * @param description one line in the player's words
     * @param category    the page section: a module group's name, or "General"
     * @param gateName    the setting that must be on for it to do anything, as the player finds it;
     *                    {@code null} when it always works
     * @param gate        whether that setting is on; {@code null} when ungated
     * @param example     a filled-in example, or {@code ""}
     */
    public record Command(String root, String name, List<String> aliases, String args, String tab,
                          String description, String category, String gateName, BooleanSupplier gate,
                          Visibility visibility, String example) {

        /** "/sbs waypoint", "/pv" - what is typed before the arguments. */
        public String typed() {
            return "/" + root + (name.isEmpty() ? "" : " " + name);
        }

        /** "/sbs waypoint clear|list" - the command as the page and help show it. */
        public String syntax() {
            return typed() + (args.isEmpty() ? "" : " " + args);
        }

        /** The names a player is shown and offered on Tab: the primary, then the aliases. */
        public List<String> shownNames() {
            List<String> all = new ArrayList<>(aliases.size() + 1);
            all.add(name.isEmpty() ? root : name);
            all.addAll(aliases);
            return all;
        }

        /** Every name the dispatch accepts, including tolerated misspellings nobody is shown. */
        public List<String> names() {
            List<String> all = shownNames();
            all.addAll(TOLERATED.getOrDefault(name.isEmpty() ? root : name, List.of()));
            return all;
        }

        /** The argument's name for the suggestion strip: "player" when the syntax says so. */
        public String argumentName() {
            return args.contains("player") ? "player" : "value";
        }

        /** Whether its gate is on (always true when it has none). */
        public boolean available() {
            return gate == null || gate.getAsBoolean();
        }

        /** Stable id for the page's rows: from the command, never from display text. */
        public String id() {
            return "cmd_" + root + (name.isEmpty() ? "" : "_" + name);
        }

        /** Search: name, aliases and description. */
        public boolean matches(String query) {
            if (query == null || query.isBlank()) {
                return true;
            }
            String q = query.trim().toLowerCase(Locale.ROOT);
            if (syntax().toLowerCase(Locale.ROOT).contains(q) || description.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
            for (String alias : aliases) {
                if (alias.toLowerCase(Locale.ROOT).contains(q)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final String GENERAL = "General";
    private static final String ECONOMY = "Economy";
    private static final String SKILLS = "Skills";
    private static final String COMBAT = "Combat";
    private static final String DUNGEONS = "Dungeons";
    private static final String PARTY = "Party & Chat";
    private static final String ITEMS = "Inventory & Items";
    private static final String QOL = "Quality of Life";
    private static final String DEVELOPER = "Developer";

    /**
     * Misspellings the dispatch accepts so a typo still works, but that nobody is shown or offered:
     * suggesting a misspelling would teach it. Counted by the drift test like any other name.
     */
    private static final java.util.Map<String, List<String>> TOLERATED =
            java.util.Map.of("itemoriginalname", List.of("itemorginalname"));

    private static final List<Command> ALL = build();

    private CommandRegistry() {
    }

    /** Every command, in page order, hidden ones included. */
    public static List<Command> all() {
        return ALL;
    }

    /** The commands for a given visibility level: PUBLIC, plus DEV_ONLY when {@code dev}. Never HIDDEN. */
    public static List<Command> visible(boolean dev) {
        List<Command> out = new ArrayList<>();
        for (Command c : ALL) {
            if (c.visibility() == Visibility.PUBLIC || (dev && c.visibility() == Visibility.DEV_ONLY)) {
                out.add(c);
            }
        }
        return out;
    }

    /** The command a typed name refers to ("waypoints", "/sbs map", "pv"), or {@code null}. HIDDEN never. */
    public static Command find(String typed) {
        if (typed == null) {
            return null;
        }
        String t = typed.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", "").replaceFirst("^sbs\\s+", "");
        for (Command c : ALL) {
            if (c.visibility() == Visibility.HIDDEN) {
                continue;
            }
            for (String n : c.names()) {
                if (n.equalsIgnoreCase(t)) {
                    return c;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the list

    private static List<Command> build() {
        List<Command> l = new ArrayList<>();

        // --- General
        sbs(l, "gui", List.of("menu"), "", "", "Opens the SBS settings menu. A bare /sbs does the same.", GENERAL);
        sbs(l, "help", List.of(), "[command]", "<word>",
                "Lists every command in chat, click one to fill it in; with a name, shows its details.", GENERAL,
                "/sbs help map");
        root(l, "skyblocksimplified", "", "", "The long name of /sbs.", GENERAL);
        sbs(l, "keybind", List.of("keybinds"), "", "", "Opens the keybind editor: keys that run commands.", GENERAL);
        sbs(l, "map", List.of(), "[place]", "<text>",
                "Opens the island maps; with a place name, travels straight there.", QOL, "/sbs map bazaar");
        sbs(l, "chmap", List.of(), "[mark <label>|unmark [label]]", "mark|unmark",
                "Opens the Crystal Hollows map; mark/unmark place or remove your own markers (kept on this PC).",
                QOL, "/sbs chmap mark Fairy");
        sbs(l, "where", List.of("location", "area"), "", "",
                "Says which island and area the mod thinks you are in, and why.", GENERAL);
        sbs(l, "sidebar", List.of(), "", "",
                "Lists every sidebar line and what the custom scoreboard read it as.", GENERAL);
        sbs(l, "testnotify", List.of("testnotification", "testalert"), "pest|cooldown|reminder",
                "pest|cooldown|reminder", "Fires a test alert through its channels and says which ones took it.",
                GENERAL, "/sbs testnotify pest");
        sbs(l, "ping", List.of(), "", "", "Shows your ping, only to you.", GENERAL);
        sbs(l, "tps", List.of(), "", "", "Shows the server's ticks per second, only to you.", GENERAL);

        // --- Economy
        sbs(l, "mayor", List.of(), "[item]", "<text>",
                "Shows the mayor, their perks and the election; with an item, its price history by mayor.",
                ECONOMY, "/sbs mayor hyperion");
        sbs(l, "cph", List.of(), "reset", "reset", "Restarts the Coins per Hour session.", ECONOMY);
        sbs(l, "minions", List.of(), "[plan]", "plan",
                "Opens the minion calculator; \"plan\" opens the budget planner.", ECONOMY);

        // --- Skills
        sbs(l, "visitors", List.of(), "", "",
                "Lists what your Garden visitors want in chat, each item a Bazaar link.", SKILLS);
        sbs(l, "mousemat", List.of(), "", "", "Shows your saved Mousemat crop angles.", SKILLS);
        sbs(l, "farming", List.of(), "[end]", "end",
                "Opens your farming session summary and history; end finishes the running session.", SKILLS);
        sbs(l, "beacon", List.of(), "", "", "Says why the Beacon Tuning helper is or is not drawing.", SKILLS);
        sbs(l, "lane", List.of(), "<start|end|repeat|area|undo|clear|width|axis|farm|list>",
                "start|end|repeat|area|undo|clear|width|axis|farm|list",
                "Marks farms and lanes for the Lane End Warning: start and end of each lane, repeat it "
                        + "sideways, a whole rectangle, farm new/select/delete, the Farms screen.", SKILLS,
                "/sbs lane repeat 9 3");
        sbs(l, "hotm", List.of(), "", "", "Opens the Heart of the Mountain perk advisor: the next perk level worth taking for your goal.", SKILLS);
        sbs(l, "honey", List.of(), "[held|clear]", "held|clear",
                "Lists your running honey tree timers; \"held\" checks the item in your hand.", SKILLS);
        sbs(l, "commission", List.of("commissions"), "[blocks|reload]", "blocks|reload",
                "Shows what each mining commission is looking for.", SKILLS);
        sbs(l, "sweep", List.of(), "capture on|off|status", null,
                "Records the foraging chop lines to a file, for fixing the Sweep parser.", DEVELOPER);

        // --- Combat
        sbs(l, "trackcarry", List.of("carry"), "<boss> <player>", "<text>",
                "Counts slayer boss kills for a carry; also +, -, list, done, clear, goal, auto.", COMBAT,
                "/sbs trackcarry revenant PlayerName");
        sbs(l, "kuudra", List.of(), "mark|area|list|del|clear|reload|export|import",
                "mark|area|list|del|clear|reload|export|import", "Manages your Kuudra pearl setups.", COMBAT);
        sbs(l, "diana", List.of(), "[status|clear|debug]", "status|clear|debug",
                "Says what the Diana helper currently believes about burrows and guesses, and why.", COMBAT);
        sbs(l, "devlog", List.of(), "diana [status|mark <text>|rearm|export]", "diana",
                "Records everything around the Diana ritual to a file, so the Diana helper can be fixed "
                        + "from what really happens. Run it again to stop.", COMBAT,
                "/sbs devlog diana mark dug the second burrow");

        // --- Dungeons
        sbs(l, "terminals", List.of("terminal"), "", "", "Opens the terminal practice screen.", DUNGEONS);
        sbs(l, "dragons", List.of("dragon"), "clear|list", "clear|list",
                "Manages the M7 dragon statue positions the boxes are drawn on.", DUNGEONS);
        sbs(l, "posmsg", List.of(), "add|party|list|clear", "add|party|list|clear",
                "Manages Positional Messages: party lines sent when you reach a spot.", DUNGEONS);

        // --- Party & Chat
        sbs(l, "party", List.of(), "[message]", "<text>",
                "With a message, sends it to your party; bare, opens the Party Finder.", PARTY);
        root(l, "pf", "", "", "Opens the Party Finder (same as /sbs party).", PARTY);
        sbs(l, "irc", List.of(), "<message>", "<text>", "Sends a message into the SBS IRC chat.", PARTY);
        root(l, "sendcoords", "[party|guild|coop|all] [note]", null,
                "Posts your coordinates into a chat channel, with an optional note.", PARTY,
                List.of("sbssendcoords"), "/sendcoords party at the chest");
        sbs(l, "note", List.of(), "<player> [text] | remove <player>", null,
                "Writes a private note about a player (only you see it).", PARTY);
        sbs(l, "avoid", List.of(), "<player> [text]", null, "Marks a player to avoid, privately.", PARTY);
        sbs(l, "trust", List.of(), "<player> [text]", null, "Marks a player as trusted, privately.", PARTY);
        sbs(l, "notes", List.of(), "[search]", null, "Lists your player notes.", PARTY);
        shortcut(l, "pw", "", "Warps your party to you (/party warp).");
        shortcut(l, "pd", "", "Disbands your party (/party disband).");
        shortcut(l, "pko", "", "Kicks offline party members (/party kickoffline).");
        shortcut(l, "pt", "<player>", "Transfers the party to a player.");
        shortcut(l, "pp", "<player>", "Promotes a party member.");
        shortcut(l, "pdm", "<player>", "Demotes a party member.");
        shortcut(l, "pi", "<player>", "Invites a player to your party.");
        shortcut(l, "pk", "<player> [reason]", "Kicks a party member, telling the party why when you give a reason.");
        shortcut(l, "pa", "[player]", "Accepts a party invite - without a name, the last one you got.");

        // --- Inventory & Items
        root(l, "pv", "[player]", "<word>", "Opens the Player Viewer - your own profile without a name.", ITEMS,
                List.of(), "/pv PlayerName");
        sbs(l, "skycrypt", List.of(), "[player]", "<word>", "Opens SkyCrypt in the in-game browser.", ITEMS);
        sbs(l, "museum", List.of(), "", "", "Lists what your museum is still missing, cheapest XP first.", ITEMS);
        sbs(l, "accessories", List.of("missing"), "", "",
                "Lists the accessories this profile is missing.", ITEMS);
        sbs(l, "protect", List.of(), "[list|clear]", "list|clear", "Opens the Item Protection list.", ITEMS);
        l.add(new Command("sbs", "itemrename", List.of(), "<new name>", "<text>",
                "Renames the item in your hand - only you see the new name.", ITEMS,
                "Item Renamer (Item Overlay)", () -> sbs.modid.client.core.config.ConfigManager.getInstance()
                        .get().itemOverlay.itemRenamer, Visibility.PUBLIC, "/sbs itemrename My Sword"));
        sbs(l, "itemoriginalname", List.of(), "", "",
                "Gives the item in your hand its own name back.", ITEMS);
        sbs(l, "objective", List.of(), "[off|go]", "off|go",
                "Shows where the route to your current objective goes; off hides it, go travels there.", QOL);
        sbs(l, "rejoin", List.of(), "[stop]", "stop",
                "Starts the Rejoin Timer by hand, for a kick the mod did not notice.", QOL);
        sbs(l, "waypoint", List.of("waypoints"), "clear|list", "clear|list",
                "Lists or clears the waypoints picked up from chat coordinates.", QOL);
        l.add(new Command("sbs", "freecam", List.of(), "", "",
                "Toggles cinematic freecam - a smooth camera for videos that never acts or selects.", QOL,
                "Cinematic Camera", () -> sbs.modid.client.core.config.ConfigManager.getInstance()
                        .get().cinematicCamera.enabled, Visibility.PUBLIC, "/sbs freecam"));

        // --- Build Tools: typed //<verb>, so each verb is a root named "/<verb>". Their suggestion
        // nodes are built by BuildCommandTree (tab = null here); the verb table is BuildCommand.
        for (sbs.modid.client.helper.build.command.BuildCommand verb
                : sbs.modid.client.helper.build.command.BuildCommand.values()) {
            boolean singleplayer = verb.reach() == sbs.modid.client.helper.build.command.BuildCommand.Reach.SINGLEPLAYER;
            l.add(new Command("/" + verb.word(), "", List.of(), verb.usage(), null,
                    capitalise(verb.help()) + (singleplayer ? " (singleplayer only)" : ""), QOL, "Build Tools",
                    () -> sbs.modid.client.core.config.ConfigManager.getInstance().get().buildTools.enabled,
                    Visibility.PUBLIC, ""));
        }

        // --- Developer (listed only in developer mode, never suggested)
        dev(l, "sbs", "pad", List.of(), "[here|confirm|list]",
                "Records a jump pad by hand (stand on it, then confirm where you land) or lists this island's pads.");
        dev(l, "sbs", "wizard", List.of(), "[show|showcase|page|list|reset]",
                "Re-runs or resets the first-run wizard and the update showcase.");
        dev(l, "sbs", "questcapture", List.of("qc"), "[...]", "Records questline dialogue to a file.");
        dev(l, "sbs", "perf", List.of(), "[dump [label]|reset|slow <ms>]",
                "Performance KPI overlay, report to Development_Stuff/perf, reset, slow-tick test.");
        dev(l, "sbs", "terminaltest", List.of(), "", "Runs the terminal solvers against test boards.");
        dev(l, "sbs", "probe", List.of(), "[arm|off|status]", "Writes the open menu to a file, verbatim.");
        dev(l, "sbs", "priceprobe", List.of(), "", "Shows every price lookup for the item under the cursor.");
        dev(l, "sbs", "sharddump", List.of(), "[arm|off]", "Dumps how the last shard menu was read.");
        dev(l, "sbs", "chatprobe", List.of(), "[arm|off|status]", "Writes every chat line to a file.");
        dev(l, "sbs", "scan", List.of(), "[start|stop|status] [chat|actionbar|scoreboard|tablist|all]",
                "Server Scanner: records every menu, slot change and click (and optional text channels) as JSONL.");
        dev(l, "sbs", "logmenu", List.of(), "", "Logs the open menu once, in full, into the scan session or its own file.");
        dev(l, "sbs", "particleprobe", List.of(), "[arm|off|status]", "Writes every particle packet to a file.");
        dev(l, "sbs", "soundprobe", List.of(), "[arm|off|status]", "Writes every nearby sound to a file.");
        dev(l, "sbs", "m7probe", List.of(), "[arm|off|status]", "Captures the M7 dragon phase for a whole fight.");
        dev(l, "sbs", "soulprobe", List.of(), "", "Logs every head near you, to identify a Fairy Soul.");
        dev(l, "sbs", "entityprobe", List.of(), "[arm|off|status]", "Snapshots nearby entities and nametag changes.");
        dev(l, "sbsdev", "", List.of(), "[origin [x z]]", "Toggles developer mode; sets the dungeon grid origin.");
        dev(l, "sbstest", "", List.of(), "[clear]", "Developer highlight test.");

        // --- Hidden: never listed, never suggested.
        l.add(new Command("sbs", "developermode", List.of(), "", null, "", DEVELOPER, null, null,
                Visibility.HIDDEN, ""));
        return Collections.unmodifiableList(l);
    }

    private static void sbs(List<Command> l, String name, List<String> aliases, String args, String tab,
                            String description, String category) {
        sbs(l, name, aliases, args, tab, description, category, "");
    }

    private static void sbs(List<Command> l, String name, List<String> aliases, String args, String tab,
                            String description, String category, String example) {
        Visibility visibility = DEVELOPER.equals(category) ? Visibility.DEV_ONLY : Visibility.PUBLIC;
        l.add(new Command("sbs", name, aliases, args, tab, description, category, null, null, visibility, example));
    }

    private static void root(List<Command> l, String root, String args, String tab, String description,
                             String category) {
        root(l, root, args, tab, description, category, List.of(), "");
    }

    private static void root(List<Command> l, String root, String args, String tab, String description,
                             String category, List<String> aliases, String example) {
        l.add(new Command(root, "", aliases, args, tab, description, category, null, null, Visibility.PUBLIC,
                example));
    }

    /**
     * A built-in short command. Never suggested ({@code tab} null): they are Hypixel-shaped names the
     * server may know too, and they were never in the suggestion tree.
     */
    private static void shortcut(List<Command> l, String root, String args, String description) {
        l.add(new Command(root, "", List.of(), args, null, description, PARTY,
                "Built-in Short Commands (Command Keybinds)",
                () -> sbs.modid.client.core.config.ConfigManager.getInstance().get().shortCommands.enabled,
                Visibility.PUBLIC, ""));
    }

    private static void dev(List<Command> l, String root, String name, List<String> aliases, String args,
                            String description) {
        l.add(new Command(root, name, aliases, args, null, description, DEVELOPER, null, null,
                Visibility.DEV_ONLY, ""));
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
