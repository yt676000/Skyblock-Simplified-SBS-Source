/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.streamer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.helper.streamer.logic.StreamerNames;
import sbs.modid.client.helper.streamer.model.FakeLevel;
import sbs.modid.client.helper.streamer.model.FakeRank;
import sbs.modid.client.helper.streamer.model.NameMode;
import sbs.modid.client.helper.streamer.model.PlayerAlias;
import sbs.modid.client.helper.streamer.ui.StreamerAliasScreen;
import sbs.modid.client.helper.timers.ServerWorldTime;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Streamer Mode (Quality of Life): takes your name, other players' names and the Hypixel instance id
 * off the screen, so a recording or a stream does not carry them. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p>Client-side and display-only throughout. Nothing here changes what is sent to the server, what
 * other people see, or what any other SBS feature reads - the party list still knows who is in your
 * party while the screen has stopped saying so.
 */
public final class StreamerModule implements SbsModule {

    /** Longest an alias or a stand-in name may be - a nametag is not the place for an essay. */
    private static final int MAX_NAME_LENGTH = 32;

    /** ServiceLoader needs a public no-arg constructor. */
    public StreamerModule() {
    }

    @Override
    public String id() {
        return "streamer_mode";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.QUALITY_OF_LIFE;
    }

    @Override
    public String displayName() {
        return "Streamer Mode";
    }

    @Override
    public String description() {
        return "Hide your name, other players' names and the server id from everything on screen, "
                + "for recording and streaming";
    }

    @Override
    public int accentColor() {
        return 0xFFB44DFF;
    }

    private static SBSConfig.StreamerSettings cfg() {
        return ConfigManager.getInstance().get().streamer;
    }

    /**
     * Saves and drops the compiled redactions, so a change shows on the next frame instead of
     * waiting out the rebuild throttle. Every writer on this page goes through here.
     */
    private static void save() {
        ConfigManager.getInstance().save();
        StreamerNames.getInstance().invalidate();
    }

    private static void open(Screen screen) {
        Minecraft.getInstance().setScreenAndShow(screen);
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>(20);

        rows.add(SettingRow.toggle("Streamer Mode", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Master switch. While it is off nothing is scanned and nothing is "
                        + "hidden, so the feature costs nothing when you are not recording. Off by "
                        + "default."));
        rows.add(SettingRow.label("Everything below only changes what is drawn on your screen"));
        rows.add(SettingRow.label("§8Nothing is sent anywhere, and other players see you unchanged"));

        rows.add(SettingRow.label("— Your Name —"));
        rows.add(modeRow("Your Name", () -> cfg().ownMode, mode -> cfg().ownMode = mode)
                .describe("What is shown where your own name would be: Off leaves it alone, Blank "
                        + "removes it, Replace shows the text below instead, Blur draws it "
                        + "scrambled at the same length. Covers chat, the tab list, the nametag "
                        + "over your head and every SBS panel that names you. Off by default."));
        rows.add(SettingRow.text("Your Name Shown As", "e.g. Player", MAX_NAME_LENGTH,
                        () -> cfg().ownName, value -> { cfg().ownName = value; save(); })
                .describe("The name drawn in place of yours while Your Name is set to Replace. "
                        + "Leaving it empty is the same as Blank."));

        rows.add(SettingRow.label("— Your Rank & Level —"));
        rows.add(SettingRow.options("Show My Rank As", StreamerModule::rankLabels,
                        () -> FakeRank.parse(cfg().ownRank).displayName(),
                        picked -> {
                            for (FakeRank rank : FakeRank.values()) {
                                if (rank.displayName().equals(picked)) {
                                    cfg().ownRank = rank.name();
                                }
                            }
                            save();
                        })
                .describe("The rank bracket drawn in front of your own name - in your chat lines, "
                        + "the tab list and your nametag. Real leaves it alone; None shows no "
                        + "bracket and a grey name. Only a bracket that is really there is "
                        + "swapped, so a rank is not added where the server shows none. Only on "
                        + "your screen. Real by default."));
        rows.add(SettingRow.options("Plus Colour", StreamerModule::plusLabels,
                        StreamerModule::plusLabel,
                        picked -> {
                            for (String[] colour : FakeRank.PLUS_COLOURS) {
                                if (colour[1].equals(picked)) {
                                    cfg().rankPlusColour = colour[0];
                                }
                            }
                            save();
                        })
                .describe("The colour of the + in a fake MVP+ or MVP++, from the thirteen Hypixel "
                        + "offers. Ignored for every other rank. Red by default."));
        rows.add(SettingRow.segmented("MVP++ Style", List.of("Gold", "Aqua"),
                        () -> cfg().rankAquaMvpPlusPlus ? 1 : 0,
                        index -> { cfg().rankAquaMvpPlusPlus = index == 1; save(); })
                .describe("Whether a fake MVP++ is drawn gold (Hypixel's default) or aqua."));
        rows.add(SettingRow.intField("Show My Level As", 0, FakeLevel.MAX, () -> cfg().fakeLevel,
                        value -> { cfg().fakeLevel = value; save(); }, "")
                .describe("The SkyBlock level drawn in the [312] in front of your name. Its colour "
                        + "follows the level like Hypixel's does - it steps up every 40 levels, "
                        + "grey through gold to dark red. 0 leaves your real level. Only on your "
                        + "screen."));

        rows.add(SettingRow.label("— Other Players —"));
        rows.add(modeRow("Other Players", () -> cfg().othersMode, mode -> cfg().othersMode = mode)
                .describe("The same four choices for everybody else currently on the server. The "
                        + "names come from the player list, so it is exact - it hides names, not "
                        + "words that happen to look like names. Off by default."));
        rows.add(SettingRow.text("Others Shown As", "e.g. Player", MAX_NAME_LENGTH,
                        () -> cfg().othersName, value -> { cfg().othersName = value; save(); })
                .describe("One name drawn in place of every other player's while Other Players is "
                        + "set to Replace. Everyone reads as the same name - use the list below to "
                        + "tell particular people apart."));
        rows.add(SettingRow.button("Custom Names For Certain Players",
                        () -> open(new StreamerAliasScreen()))
                .describe("Opens the list where you give one player a name of your own. An entry "
                        + "here wins over both settings above, so you can blank the lobby and still "
                        + "have your friends readable."));
        rows.add(SettingRow.label(aliasSummary()));

        rows.add(SettingRow.label("— Server —"));
        rows.add(SettingRow.toggle("Hide Server ID", () -> cfg().hideServerId,
                        () -> { cfg().hideServerId = !cfg().hideServerId; save(); })
                .describe("Takes the instance id Hypixel gives your lobby (\"mini24CD\") out of "
                        + "everything that shows it, including the sidebar's date line. It is what "
                        + "somebody watching would need to join your lobby. Off by default."));
        rows.add(SettingRow.label(serverSummary()));

        rows.add(SettingRow.button("Show What Is Hidden", StreamerModule::printRedactions)
                .describe("Prints to your own chat every name and id the feature is replacing right "
                        + "now, and what each becomes. The way to check it is working without "
                        + "asking somebody to watch your stream."));
        rows.add(SettingRow.toggle("Log Redactions", () -> cfg().debugLog,
                        () -> {
                            cfg().debugLog = !cfg().debugLog;
                            StreamerNames.getInstance().resetLog();
                            save();
                        })
                .describe("Writes each distinct name and id to latest.log the first time it is "
                        + "hidden. For working out why something was missed. Off by default."));
        return List.copyOf(rows);
    }

    /**
     * A name-mode row: four choices, so a segmented switch (ui/AGENTS.md). Measured against the
     * 259 px config row: "Off" "Blank" "Replace" "Blur" come to about 152 px with padding
     * (ESTIMATED from vanilla glyph widths), leaving ~87 px for the label - enough for "Other
     * Players", the longer of the two.
     */
    private static SettingRow modeRow(String label, java.util.function.Supplier<String> stored,
                                      java.util.function.Consumer<String> store) {
        NameMode[] modes = NameMode.values();
        List<String> labels = new ArrayList<>(modes.length);
        for (NameMode mode : modes) {
            labels.add(mode.displayName());
        }
        return SettingRow.segmented(label, labels, () -> NameMode.parse(stored.get()).ordinal(),
                index -> { store.accept(modes[index].name()); save(); });
    }

    private static List<String> rankLabels() {
        List<String> out = new ArrayList<>();
        for (FakeRank rank : FakeRank.values()) {
            out.add(rank.displayName());
        }
        return out;
    }

    private static List<String> plusLabels() {
        List<String> out = new ArrayList<>();
        for (String[] colour : FakeRank.PLUS_COLOURS) {
            out.add(colour[1]);
        }
        return out;
    }

    private static String plusLabel() {
        for (String[] colour : FakeRank.PLUS_COLOURS) {
            if (colour[0].equalsIgnoreCase(cfg().rankPlusColour)) {
                return colour[1];
            }
        }
        return "Red";
    }

    /** The alias-list row's subtitle: how many entries there are, so the button says something. */
    private static String aliasSummary() {
        int total = 0;
        for (PlayerAlias alias : cfg().aliases) {
            if (alias != null && alias.usable()) {
                total++;
            }
        }
        return total == 0 ? "§8No custom names yet" : "§8" + total + " custom name"
                + (total == 1 ? "" : "s") + " set";
    }

    /** Whether the instance id can be read at all right now - it only exists on a real server. */
    private static String serverSummary() {
        String id = ServerWorldTime.serverName();
        if (id == null) {
            return "§8No server id visible here - Hypixel publishes it in the tab list";
        }
        return cfg().enabled && cfg().hideServerId
                ? "§8This lobby's id is being hidden"
                : "§8This lobby is " + id;
    }

    /** Prints the live redaction table to the player's own chat. Nothing leaves the client. */
    private static void printRedactions() {
        if (!cfg().enabled) {
            SBSChat.send("§eStreamer Mode is off - nothing is being hidden.");
            return;
        }
        Map<String, String> table = StreamerNames.getInstance().preview();
        if (table.isEmpty()) {
            SBSChat.send("§eStreamer Mode is on but has nothing to hide yet. Pick a mode above, or "
                    + "wait until the player list has loaded.");
            return;
        }
        SBSChat.send("§bStreamer Mode is hiding " + table.size() + ":");
        table.forEach((name, replacement) -> SBSChat.send("§7  " + name + " §8-> "
                + (replacement.isEmpty() ? "§8(blank)" : "§7" + replacement)));
        FakeRank rank = FakeRank.parse(cfg().ownRank);
        if (rank.active()) {
            SBSChat.send("§7  your rank §8-> §7" + rank.displayName());
        }
        if (cfg().fakeLevel > 0) {
            SBSChat.send("§7  your level §8-> " + FakeLevel.render(cfg().fakeLevel));
        }
    }
}
