/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.fairysouls.logic;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.keybind.IslandCatalog;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the <b>Fairy Souls Guide</b> in the Quest Log - the one place Hypixel states per-island
 * progress - and settles what the client could otherwise only guess.
 *
 * <p><b>Why this matters more than everything else in the module.</b> A collected soul can only be
 * identified by standing on it, so a profile played before this mod existed has hundreds of souls
 * the client never witnessed and can never attribute individually. The menu does not fix that
 * either - it still reports a <i>count</i> per island, not identities. But a count is enough for the
 * one answer that matters: <b>{@code found == total} means the island is finished</b>, and every
 * marker on it can be retired without inventing a single per-soul record.
 *
 * <p><b>This is a fact, not a guess</b>, which is why it applies itself rather than asking. The
 * corollary runs the other way too: an island the menu reports as <i>unfinished</i> is un-marked, so
 * a stale "done" flag - from a hand-press, or from souls added by a later Hypixel update - is
 * corrected the next time the menu is opened. Only the server's own number ever moves the flag.
 *
 * <p><b>Islands are recognised, never assumed.</b> Menu entries are matched through
 * {@link IslandCatalog}, so the "Miscellaneous" tile (Dungeons, Fishing, Garden, Placeable and
 * Glacite Mineshaft souls, which have no island and no coordinates) resolves to nothing and is
 * skipped without needing to be named here - as would any tile Hypixel adds that is not a place.
 */
public final class FairySoulMenu {

    private static final FairySoulMenu INSTANCE = new FairySoulMenu();

    /** The menu is titled "Fairy Souls Guide"; matched loosely so a rename does not silently end it. */
    private static final String TITLE_MARKER = "fairy soul";

    /** An island tile's lore: "Fairy Souls: 0/80". */
    private static final Pattern PROGRESS = Pattern.compile(
            "(?i)fairy\\s+souls?\\s*:\\s*([0-9,]+)\\s*/\\s*([0-9,]+)");

    private static final char SECTION_SIGN = (char) 0x00A7;

    /** The menu is static while open; half a second is plenty and keeps this off the tick budget. */
    private static final long SCAN_INTERVAL_MS = 500L;

    private long lastScanAt;

    /** The last scan's island count, purely so the settings page can say the menu has been seen. */
    private int lastIslandsRead;

    private FairySoulMenu() {
    }

    public static FairySoulMenu getInstance() {
        return INSTANCE;
    }

    /** Called from {@link FairySoulTracker#tick} while the module is on. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        scan();
    }

    private void scan() {
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        String title = strip(screen.getTitle() == null ? "" : screen.getTitle().getString());
        if (!title.toLowerCase(Locale.ROOT).contains(TITLE_MARKER)) {
            return;
        }
        AbstractContainerMenu menu = container.getMenu();
        FairySoulStore store = FairySoulStore.getInstance();
        List<String> finished = new ArrayList<>();
        int islands = 0;

        for (int slot = 0; slot < menu.slots.size(); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            List<String> covered = islandsOf(strip(stack.getHoverName().getString()));
            if (covered.isEmpty()) {
                continue;
            }
            int[] counts = progressIn(stack);
            if (counts == null) {
                continue;
            }
            islands++;
            boolean done = counts[1] > 0 && counts[0] >= counts[1];
            // A tile covering several islands states one number for all of them, so a partial count
            // cannot be attributed to either - only "all of them are finished" is knowable, and a
            // partial simply leaves both showing. Splitting it would be the guess this refuses.
            boolean grouped = covered.size() > 1;
            for (String island : covered) {
                if (!grouped) {
                    store.recordMenuProgress(island, counts[0], counts[1]);
                }
                // Both directions are the server's word: complete retires the island's markers, and
                // incomplete takes back a "done" that a Hypixel update or mis-press has outdated.
                if (store.markIslandDone(island, done) && done) {
                    finished.add(island);   // changed AND now done, so this island just flipped
                }
            }
        }

        if (islands == 0) {
            return;   // a fairy-soul-titled screen that is not the guide - nothing read, nothing said
        }
        lastIslandsRead = islands;
        if (!finished.isEmpty()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][FairySouls] Quest Log: {} island(s) complete {}",
                    finished.size(), finished);
            SBSChat.send(Component.literal(" Quest Log read: " + String.join(", ", finished)
                    + (finished.size() == 1 ? " is" : " are") + " fully collected - markers retired.")
                    .withColor(SBSChat.PREFIX_COLOR));
            FairySoulRouting.getInstance().onIslandChanged();
        }
    }

    /**
     * The islands a tile named {@code tile} stands for, or empty when it is not a place.
     *
     * <p>The data file is asked first because only it knows that one tile can group two islands.
     * {@link IslandCatalog} is the fallback, so an island Hypixel lists but this build has no
     * coordinates for can still be marked finished. Neither answering is what keeps the
     * "Miscellaneous" tile and the navigation buttons out without having to name them here.
     */
    private static List<String> islandsOf(String tile) {
        List<String> declared = FairySoulDatabase.islandsForQuestLogTile(tile);
        if (!declared.isEmpty()) {
            return declared;
        }
        String known = IslandCatalog.islandForArea(tile);
        return known == null ? List.of() : List.of(known);
    }

    /**
     * The tile's "found/total" pair, or {@code null} when its lore has none.
     *
     * <p>Deliberately the <i>first</i> matching line: an island tile carries exactly one, and taking
     * the first means a tile that later grows extra numbered lore does not start reporting one of
     * them as the island's progress.
     */
    private static int[] progressIn(ItemStack stack) {
        if (!(stack.get(DataComponents.LORE) instanceof ItemLore lore)) {
            return null;
        }
        for (Object line : lore.lines()) {
            if (!(line instanceof Component text)) {
                continue;
            }
            Matcher matcher = PROGRESS.matcher(strip(text.getString()));
            if (matcher.find()) {
                return new int[]{number(matcher.group(1)), number(matcher.group(2))};
            }
        }
        return null;
    }

    /** How many islands the last successful read covered - {@code 0} when the menu has never been seen. */
    public int lastIslandsRead() {
        return lastIslandsRead;
    }

    private static int number(String raw) {
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION_SIGN && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
