/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tab;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.mixin.PlayerTabOverlayAccessor;
import sbs.modid.client.skills.farming.model.FarmingText;
import sbs.modid.client.skills.garden.logic.FarmingTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * The tab-list widget lines Hypixel publishes ("Garden Level 12", "Pests: 3", "Plot 4: 2"), read
 * live and colour-stripped.
 *
 * <p>Hypixel serves its side widgets as fake tab-list entries: an entry with a styled display name
 * and no real player behind it. Filtering on "has a display name" is what separates a widget line
 * from an actual player, and it is the same test {@link FarmingTracker} has always used – this
 * class simply makes it available to every Garden feature instead of one.
 *
 * <p>Nothing is cached: the list is tiny, the callers are already throttled, and a cache here would
 * only add a way for two features to disagree about what the tab currently says.
 *
 * <p>The tab list's <b>footer</b> ({@link #footerLines()}) is a different thing entirely: not a fake
 * entry but the block of text Hypixel draws below the player columns ("Active Effects", "Cookie
 * Buff", the store advert). It is read separately on purpose - a caller wanting widget lines would
 * only be confused by advertising text.
 */
public final class TabWidgets {

    private TabWidgets() {
    }

    /**
     * Whether a tab entry is a <b>real player</b> rather than one of Hypixel's widget rows.
     *
     * <p>The same test the readers below run, inverted and given a name, because it is not only
     * widget readers that need it: the SBS Tab-List asks it to decide which rows get a skin face and
     * a connection icon. "Area: Garden" is not a player, has no face worth drawing, and a
     * signal-strength icon beside it measures nothing.
     *
     * <p>It holds because of how Hypixel builds the list: a widget row is an entry whose display
     * name <i>is</i> the text, while a real player carries no display name at all - their rank and
     * level prefix comes from the scoreboard team, which {@code getNameForDisplay} applies on top of
     * the profile name. So "has a display name" separates the two exactly.
     */
    public static boolean isPlayer(PlayerInfo info) {
        return info != null && info.getTabListDisplayName() == null;
    }

    /**
     * Every widget line currently in the tab list, colour-stripped and trimmed, <b>in the order the
     * tab actually displays them</b>.
     *
     * <p>The order is the whole point. {@code getOnlinePlayers()} hands back the raw entry map, whose
     * iteration order has nothing to do with the tab's layout - so a caller reading a section
     * ("{@code Commissions:}" and the rows beneath it) would get the rows shuffled around their own
     * header and silently lose the ones that came out first. That is exactly what made the commission
     * card show one row out of four, differently each session. Vanilla's own
     * {@code getPlayerInfos()} is the sorted, listed, display-order list, so reading that instead
     * makes the line order match what is on screen.
     */
    public static List<String> lines() {
        Minecraft mc = Minecraft.getInstance();
        // Null client is the offline-tooling case, null player the main-menu case: no tab list
        // either way, which is what an empty list already means to every caller.
        if (mc == null || mc.player == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(32);
        for (PlayerInfo info : orderedEntries(mc)) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;   // a real player - not a widget line
            }
            String line = FarmingText.strip(display.getString()).trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * The same widget lines as {@link #lines()}, but as the components the tab draws - styles and
     * all - and without the trim, so an index into one list means the same row in the other.
     *
     * <p>For the callers that want to <i>show</i> a line rather than read a value out of it. A
     * SkyBlock level is written in a colour that changes as the level does, and the only way to keep
     * that right for a colour nobody has seen yet is to hand the server's own component through
     * instead of picking one.
     */
    public static List<Component> components() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return List.of();
        }
        List<Component> out = new ArrayList<>(32);
        for (PlayerInfo info : orderedEntries(mc)) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;   // a real player - not a widget line
            }
            if (!FarmingText.strip(display.getString()).trim().isEmpty()) {
                out.add(display);
            }
        }
        return out;
    }

    /**
     * Every tab entry, in the order the tab displays them.
     *
     * <p>The full connection list sorted with vanilla's own comparator, rather than vanilla's
     * {@code getPlayerInfos()}: that method sorts identically but also truncates to the 80 rows it
     * can draw, and this list feeds island detection as well as the widget readers - silently losing
     * an entry in a busy lobby would be a far worse bug than the one being fixed. If the comparator
     * cannot be reached the entries are returned unsorted, which is what every caller used to get.
     */
    private static List<PlayerInfo> orderedEntries(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return List.of();
        }
        List<PlayerInfo> entries = new ArrayList<>(connection.getOnlinePlayers());
        try {
            entries.sort(PlayerTabOverlayAccessor.skyblockSimplified$playerComparator());
        } catch (Throwable ignored) {
            // No ordering available - better an unsorted read than none at all.
        }
        return entries;
    }

    /**
     * The tab list's footer, one entry per text line, colour-stripped and trimmed with the blank
     * separator lines dropped - so a header ("Cookie Buff") is always directly followed by its value.
     */
    public static List<String> footerLines() {
        Component footer = footer();
        if (footer == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(8);
        // One Component holding every line, newline-separated - splitting it is what turns it back
        // into the block of text the player sees.
        for (String raw : footer.getString().split("\n")) {
            String line = FarmingText.strip(raw).trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * The footer's text as sent, newlines and any {@code §} codes embedded in it kept - for captures
     * that need the exact string. {@code ""} when there is no footer.
     */
    public static String footerRaw() {
        Component footer = footer();
        return footer == null ? "" : footer.getString();
    }

    private static Component footer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) {
            return null;
        }
        PlayerTabOverlay tab = mc.gui.hud.getTabList();
        return tab == null ? null : ((PlayerTabOverlayAccessor) tab).skyblockSimplified$footer();
    }
}
