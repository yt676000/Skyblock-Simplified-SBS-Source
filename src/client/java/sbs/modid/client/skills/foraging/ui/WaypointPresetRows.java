/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.ui;

import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.skills.foraging.logic.WaypointPresetDatabase;
import sbs.modid.client.skills.foraging.logic.WaypointPresetOverrides;
import sbs.modid.client.skills.foraging.logic.WaypointPresetPublisher;
import sbs.modid.client.skills.foraging.model.WaypointPresetData;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.theme.ThemeColorPickerScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The settings rows every shipped-preset module shows, built from the data file.
 *
 * <p><b>One builder, however many pages.</b> Preset groups are split across modules and a page that
 * drew its own rows would drift from its sibling the first time either was touched. So this file
 * emits everything - the divider, the toggle, the colour, the range, the status block, the reset -
 * and a module says only which page it is.
 *
 * <p><b>Which page a group belongs to is the group's own {@code module} field</b>, read through
 * {@link WaypointPresetData.Group#ownedBy}, never inferred from what the group <i>is</i>. Those are
 * two different questions and answering the second with the first was the bug: the Snoozle walls are
 * a {@code LANDMARK} like the beacons are, and routing by kind put them on the Galatea page, which is
 * the one page they must not appear on. {@link WaypointPresetData.Group#kind()} still says what a
 * group is - it is what {@link #describe} writes and what a later per-point timer binds to - and it
 * no longer decides where the rows land.
 *
 * <p><b>Two entry points, because there are two shapes of page.</b> {@link #page} builds a whole
 * settings page for every group a module owns, which is what a dedicated preset page wants. A page
 * that is mostly something else and embeds one group - Critter Finder, which scans for critters and
 * happens to also carry the Snoozle walls - calls {@link #group} for that one group and keeps the
 * rest of its page to itself.
 *
 * <p><b>There is no per-group code anywhere, and that is the point.</b> A new preset group is a group
 * appended to {@code presets.json}: its rows appear because this loop found it, and it renders
 * because {@link WaypointPresetPublisher} publishes whatever is switched on and belongs where the
 * player is standing. Hard-coding even one group's row would quietly destroy that property.
 *
 * <p><b>Reset is scoped to the page.</b> The override file is shared with every other page that owns
 * preset groups, so a button here resets the groups this page lists and nothing else - one that also
 * forgot the player's corrections on another page would be a destructive surprise.
 *
 * <p>Every write goes through {@link WaypointPresetOverrides} and is followed by a publisher refresh,
 * so the world follows a toggle immediately rather than at the next island change.
 */
public final class WaypointPresetRows {

    /**
     * The range slider's ceiling. Past this a limit is not one: the marker is a few pixels tall at
     * that distance and its label stopped being drawn at 96m.
     */
    private static final int MAX_RANGE = 256;

    private WaypointPresetRows() {
    }

    private static WaypointPresetOverrides overrides() {
        return WaypointPresetOverrides.getInstance();
    }

    /**
     * The whole page for every group {@code moduleId} owns.
     *
     * @param title    what the colour picker's header names this page ("Galatea", "Critter Safari")
     * @param intro    the line above the groups, saying what this set is
     * @param moduleId the owning module's {@code id()}; a group naming another module is not ours
     */
    public static List<SettingRow> page(String title, String intro, String moduleId) {
        List<SettingRow> rows = new ArrayList<>();
        rows.add(SettingRow.label("§8" + intro));

        // This page's own groups, never the whole file: a preset group belonging to another
        // feature's page (the Snoozle walls on Critter Finder) must not surface here as well.
        List<WaypointPresetData.Group> groups = WaypointPresetDatabase.forModule(moduleId);
        if (groups.isEmpty()) {
            rows.add(SettingRow.label("§eNo preset data loaded - nothing to show."));
            rows.add(SettingRow.label("§8" + WaypointPresetDatabase.status()));
            return rows;
        }

        for (WaypointPresetData.Group group : groups) {
            String heading = group.name + (group.island == null || group.island.isBlank()
                    ? "" : "  •  " + group.island);
            rows.addAll(group(group, heading,
                    group.name + " (" + shortIsland(title, group.island) + ")", describe(group)));
        }

        rows.add(SettingRow.label("§8—— Status ——"));
        rows.add(SettingRow.label("§8Now: " + WaypointPresetPublisher.getInstance().status()));
        rows.add(SettingRow.label("§8Data: " + WaypointPresetDatabase.status()));
        rows.add(SettingRow.label("§8" + changed(groups) + " group(s) on this page changed by you"));
        rows.add(SettingRow.button("Reset All To Defaults", () -> {
            // This page's groups one by one, not resetAll(): the override file is shared with every
            // other page that owns preset groups, and a button on this page must not silently
            // discard what the player set on that one.
            for (WaypointPresetData.Group group : WaypointPresetDatabase.forModule(moduleId)) {
                overrides().reset(group.id);
            }
            WaypointPresetPublisher.getInstance().refresh();
        }).describe("Forgets every change you made to the groups on this page - the shipped sets are "
                + "restored exactly, because they were never overwritten in the first place. Other "
                + "pages' groups are left alone."));

        rows.add(SettingRow.label("§8—— Accuracy ——"));
        rows.add(SettingRow.label("§eThese coordinates have not been confirmed in game yet."));
        rows.add(SettingRow.label("§8They are off by default for that reason. If a marker is in the "
                + "wrong place,"));
        rows.add(SettingRow.label("§8it is the data being wrong, not the feature - corrections you "
                + "make are kept"));
        rows.add(SettingRow.label("§8when the shipped set is updated."));
        return rows;
    }

    /**
     * One group's block of rows: an optional divider, its toggle, its colour and its render range.
     *
     * <p><b>The row label is the caller's</b>, because two groups can share a name: the Galatea page
     * has "Honey Trees" on two islands and has to say which is which, while a page with one group
     * does not.
     *
     * @param heading  divider text above the block, or {@code null} for none
     * @param label    what to call the group on this page; also the colour picker's title
     * @param describe the toggle's help text - the one part that is genuinely per-feature
     */
    public static List<SettingRow> group(WaypointPresetData.Group group, String heading,
                                         String label, String describe) {
        List<SettingRow> rows = new ArrayList<>(4);
        if (heading != null && !heading.isBlank()) {
            rows.add(SettingRow.label("§8—— " + heading + " ——"));
        }
        rows.add(SettingRow.toggle(label,
                        () -> overrides().groupEnabled(group.id, group.enabledByDefault),
                        () -> {
                            boolean now = overrides().groupEnabled(group.id, group.enabledByDefault);
                            overrides().setGroupEnabled(group.id, !now, group.enabledByDefault);
                            WaypointPresetPublisher.getInstance().refresh();
                        })
                .describe(describe));
        rows.add(SettingRow.color(label + " Colour",
                () -> currentColor(group),
                () -> SBSTheme.ACCENT,
                () -> openPicker(group, label)));
        rows.add(SettingRow.rangeSlider(label + " Distance", 0, MAX_RANGE,
                        () -> Math.min(MAX_RANGE,
                                overrides().groupMaxDistance(group.id, group.maxDistance)),
                        value -> {
                            overrides().setGroupMaxDistance(group.id, value, group.maxDistance);
                            WaypointPresetPublisher.getInstance().refresh();
                        }, "m")
                .describe("How far away these markers are still drawn. 0 means no limit, which is "
                        + "what every marker did before this slider existed. The name and the "
                        + "distance stop being drawn past 96m either way - that limit belongs to the "
                        + "renderer and keeps a dense set from becoming a wall of text."));
        return rows;
    }

    /**
     * What this group is, how big it is, where it draws and how far to trust it.
     *
     * <p>The kind switch is exhaustive on purpose: a kind added without a line here is a compile
     * error rather than a group that describes itself as nothing.
     */
    public static String describe(WaypointPresetData.Group group) {
        String kind = switch (group.kind()) {
            case TREE -> "Trees that can be lathered.";
            case HIVE -> "Hives harvested for honeycomb.";
            case LANDMARK -> "A fixed landmark; it carries no timer.";
            case CRITTER -> "Places this critter is known to spawn; the marker is the place, not the "
                    + "critter, so it stays put whether one is standing there or not.";
            case UNKNOWN -> "This build does not know what this group is, so nothing attaches to it.";
        };
        String certainty = group.certainty() == Certainty.CONFIRMED
                ? "Coordinates confirmed in game."
                : "§eCoordinates are " + group.certainty().name().toLowerCase(Locale.ROOT)
                        + " and may be wrong.";
        return kind + " " + group.points.size() + " point(s), drawn only on " + group.scope()
                + ". " + certainty;
    }

    /** The colour actually drawn: the player's if set, else the group's shipped one. */
    public static String currentColor(WaypointPresetData.Group group) {
        WaypointPresetOverrides.GroupOverride record = overrides().group(group.id);
        if (record != null && record.colorHex != null && !record.colorHex.isBlank()) {
            return record.colorHex;
        }
        return group.colorHex == null ? "" : group.colorHex;
    }

    /** Opens the shared colour picker on the group's current colour, saving whatever comes back. */
    public static void openPicker(WaypointPresetData.Group group, String title) {
        String current = currentColor(group);
        if (current.isEmpty()) {
            current = String.format(Locale.ROOT, "%06X", SBSTheme.ACCENT & 0xFFFFFF);
        }
        GuiStateManager state = GuiStateManager.getInstance();
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new ThemeColorPickerScreen(
                title, current,
                value -> {
                    overrides().setGroupColor(group.id, value);
                    WaypointPresetPublisher.getInstance().refresh();
                },
                state.getCurrentScreen()));
    }

    /** How many of this page's groups the player has changed anything about. */
    private static int changed(List<WaypointPresetData.Group> groups) {
        int count = 0;
        for (WaypointPresetData.Group group : groups) {
            if (overrides().group(group.id) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * The island in one word, so a toggle label stays readable at the row's width - "Torrhus Canyon"
     * becomes "Torrhus".
     *
     * <p>The parenthetical is not decoration: Galatea ships two groups both called "Honey Trees" on
     * two islands, and without it their toggles would be indistinguishable. So it has to keep the
     * <i>distinctive</i> word, which is not always the first one - on a page titled "Critter Safari",
     * "Critter Safari" shortened to "Critter" says nothing the page has not already said. A leading
     * word the title already carries is therefore dropped before the first word is taken, which
     * leaves "Safari".
     */
    private static String shortIsland(String title, String island) {
        if (island == null || island.isBlank()) {
            return "everywhere";
        }
        String[] words = island.trim().split("\\s+");
        int from = 0;
        String lowerTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
        while (from < words.length - 1
                && lowerTitle.contains(words[from].toLowerCase(Locale.ROOT))) {
            from++;
        }
        return words[from];
    }
}
