/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.skills.mining.model.HotmData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the Heart of the Mountain tree out of the menu's own item tooltips.
 *
 * <p><b>The tooltip is the authority.</b> Hypixel restates a perk's level and its next-level cost on
 * the perk itself, changes both between updates, and publishes neither anywhere else - so a shipped
 * cost table would be a stale number the ranking trusts, which is the failure mode this whole feature
 * has been designed around. Same arrangement as the Bits Shop catalogue: the menu says, this
 * remembers, {@link HotmTreeStore} keeps it per profile.
 *
 * <p><b>Parsing lives in {@link HotmMenuParser}</b>, which is pure and tested on the real logged
 * page. This class only turns slots into text, maps names to catalogue ids and hands each visible
 * page to {@link HotmTreeStore}, which merges pages: the menu scrolls, so tiers 6-10 and tiers 1-5
 * are different readings of the same tree. With developer mode on, every slot's full lore is still
 * logged under {@code [SBS][Hotm]} - it is what the parser's fixtures are made of.
 *
 * <p><b>Reads only.</b> No slot is clicked, no page is turned, nothing is spent. Opening the menu is
 * the player's action; this watches.
 */
public final class HotmTreeReader {

    private static final HotmTreeReader INSTANCE = new HotmTreeReader();

    /** The menu's title. Matched loosely - the real one may carry a tier or a page counter. */
    private static final Pattern MENU_TITLE =
            Pattern.compile("heart of the mountain", Pattern.CASE_INSENSITIVE);

    /** The dev slot dump stops after this many distinct slots; a full tree is well under it. */
    private static final int MAX_LOGGED_SLOTS = 600;

    /** Slot name + lore already logged this session, so the dump is one line per distinct slot. */
    private final Set<String> loggedSlots = new HashSet<>();

    /** Re-read only when the menu's contents actually change. */
    private Object lastScreen;
    private int lastStateId = Integer.MIN_VALUE;

    private volatile Report lastReport = Report.EMPTY;

    private HotmTreeReader() {
    }

    public static HotmTreeReader getInstance() {
        return INSTANCE;
    }

    /**
     * What one reading of the menu produced. This is the artifact the ranking work is waiting on -
     * specifically {@link #withCost}, because a ranking that silently omits every node whose cost it
     * could not read is a ranking with holes it does not admit to.
     *
     * @param slots      how many menu slots held an item at all
     * @param named      how many yielded a perk name
     * @param withLevel  how many yielded a current level
     * @param withCost   how many yielded a next-level cost
     * @param unmatched  names whose lore produced neither a level nor a cost
     */
    public record Report(int slots, int named, int withLevel, int withCost, List<String> unmatched) {

        static final Report EMPTY = new Report(0, 0, 0, 0, List.of());

        /** Whether the cost side is complete enough that a ranking would not be full of holes. */
        public boolean costsComplete() {
            return named > 0 && withCost >= withLevel && withLevel > 0;
        }

        public String describe() {
            if (slots == 0) {
                return "the Heart of the Mountain menu has not been read yet";
            }
            return slots + " slot(s): " + named + " named, " + withLevel + " with a level, "
                    + withCost + " with a next-level cost"
                    + (unmatched.isEmpty() ? "" : "; unmatched: " + unmatched);
        }
    }

    /** The last reading's summary, for the settings screen and for the pre-ranking report. */
    public Report lastReport() {
        return lastReport;
    }

    /**
     * Every chat line, raw from the funnel. Only used to notice a spend, so the cached tree can say
     * it may be out of date at the moment that becomes true rather than on a timer.
     *
     * <p>A spend while the menu is open is the normal case - perks are bought in it - and the server
     * redraws the slots right after the click. Marking the tree stale then would race that redraw
     * and ask the player to reopen the menu they are looking at, so the next tick re-reads instead.
     */
    public void onChat(String raw) {
        if (raw == null || !HotmTreeStore.getInstance().known()) {
            return;
        }
        String plain = StyledText.strip(raw);
        // The pattern wants a purchase verb and the word powder: "unlocked" alone is every other
        // SkyBlock message, and a line naming powder is often just a gain.
        if (!HotmChatLines.isSpend(plain)) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hotm] possible spend line: \"{}\"", plain);
        if (menuOpen()) {
            lastStateId = Integer.MIN_VALUE;
            return;
        }
        HotmTreeStore.getInstance().invalidate("chat: " + plain);
    }

    /** Whether the Heart of the Mountain menu is the open screen. */
    public static boolean menuOpen() {
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        return screen instanceof AbstractContainerScreen<?> container && isHotmMenu(container);
    }

    /**
     * The menu test, on a title already in hand (colour codes allowed). Public so the slot highlight
     * and the reminder gate on this exact test rather than a copy of it.
     */
    public static boolean isHotmTitle(String title) {
        return title != null && MENU_TITLE.matcher(StyledText.strip(title)).find();
    }

    /**
     * Called every client tick. Does nothing unless the Heart of the Mountain menu is open and its
     * contents changed since the last look.
     */
    public void onClientTick() {
        try {
            checkPowderSpend();
            Screen screen = GuiStateManager.getInstance().getCurrentScreen();
            if (!(screen instanceof AbstractContainerScreen<?> container) || !isHotmMenu(container)) {
                return;
            }
            int stateId = container.getMenu().getStateId();
            if (lastScreen == screen && lastStateId == stateId) {
                return;
            }
            lastScreen = screen;
            lastStateId = stateId;
            read(container);
        } catch (Throwable t) {
            // Narrow enough to be a bug rather than a swallow: the reader is off the render path, so
            // a failure here loses one reading and is worth seeing.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Hotm] tree read failed", t);
        }
    }

    /**
     * Notices a powder total that has fallen below what it was when the tree was read, which is a
     * spend whether or not any chat line was matched.
     *
     * <p>The more reliable of the two signals, and the reason it exists alongside the chat one: the
     * purchase wording is unverified, but a total going down is arithmetic. It cannot be confused
     * with a gain, and the only other thing that lowers it - switching to a profile with less powder -
     * already resets the tracker and reloads this store on the profile boundary.
     */
    private void checkPowderSpend() {
        HotmTreeStore store = HotmTreeStore.getInstance();
        if (!store.known() || store.stale()) {
            return;
        }
        Map<String, Long> atRead = store.powderAtRead();
        if (atRead.isEmpty()) {
            return;
        }
        Map<String, Long> before = new LinkedHashMap<>();
        atRead.forEach((kind, amount) -> before.put(kind.toUpperCase(Locale.ROOT), amount));
        for (Map.Entry<String, Long> entry : MiningTracker.getInstance().powder().entrySet()) {
            Long had = before.get(entry.getKey().toUpperCase(Locale.ROOT));
            if (had != null && entry.getValue() < had) {
                store.invalidate(entry.getKey() + " powder fell from " + had + " to "
                        + entry.getValue());
                return;
            }
        }
    }

    private static boolean isHotmMenu(AbstractContainerScreen<?> screen) {
        Component title = screen.getTitle();
        return title != null && MENU_TITLE.matcher(StyledText.strip(title.getString())).find();
    }

    private void read(AbstractContainerScreen<?> container) {
        List<HotmMenuParser.SlotText> texts = new ArrayList<>();
        for (Slot slot : container.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;   // the player's own items are not part of the tree
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String rawName = StyledText.strip(stack.getHoverName().getString()).trim();
            List<String> lore = lore(stack);
            // Dev capture: the whole lore, matched or not - it is what the parser's tests are made of.
            // Deduplicated by content, so scrolling back and forth logs each distinct slot once.
            if (DevMode.ACTIVE && loggedSlots.size() < MAX_LOGGED_SLOTS
                    && loggedSlots.add(rawName + '|' + lore)) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hotm] slot {} name=\"{}\" lore={}",
                        slot.index, rawName, lore);
            }
            if ("Crystal Hollows Crystals".equals(rawName)) {
                // Nucleus Run: the crystal progress lore, logged until its wording is captured.
                sbs.modid.client.skills.mining.nucleus.logic.NucleusRunTracker.getInstance()
                        .onHotmCrystals(lore);
            }
            texts.add(new HotmMenuParser.SlotText(slot.index, rawName, lore));
        }
        HotmMenuParser.Page page = HotmMenuParser.parse(texts);

        Map<String, HotmTreeStore.NodeState> nodes = new LinkedHashMap<>();
        List<String> unmatched = new ArrayList<>();
        int withCost = 0;
        int withLevel = 0;
        for (HotmMenuParser.Node node : page.nodes()) {
            HotmData.Perk perk = HotmCatalog.byName(node.name());
            if (perk == null) {
                // Not in the catalogue: a perk Hypixel added. Kept under its slug so the tree still
                // shows it, and logged so the catalogue can catch up.
                unmatched.add(node.name());
            }
            String id = perk != null ? perk.id : slug(node.name());
            HotmTreeStore.NodeState state = new HotmTreeStore.NodeState();
            state.level = node.level();
            state.maxLevel = node.maxLevel();
            state.nextCost = node.nextCost();
            state.nextPowder = node.nextPowder();
            state.requires = node.requires();
            state.tier = node.tier();
            state.column = node.column();
            nodes.put(id, state);
            withLevel++;
            if (node.nextCost() > 0) {
                withCost++;
                if (perk != null) {
                    perk.learn(node.level(), node.nextCost());
                }
            }
        }
        lastReport = new Report(texts.size(), page.nodes().size(), withLevel, withCost, List.copyOf(unmatched));
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Hotm] menu read - {} (tiers seen up to {}, unlocked {})",
                lastReport.describe(), page.highestTierSeen(), page.unlockedTier());
        if (!nodes.isEmpty()) {
            HotmTreeStore.getInstance().recordPage(nodes, page.powder(), page.tokens(),
                    page.unlockedTier(), powderNow(page.powder()));
        }
    }

    /**
     * The powder totals at this reading, so a later drop can be recognised as a spend. The menu
     * header when it is on the page - it is the same instant as the perks being read, whereas the
     * tab refreshes a moment later and would still show the pre-purchase total right after a buy,
     * which then "falls" and marks a tree stale that was read after the spend. The tab otherwise.
     */
    private static Map<String, Long> powderNow(Map<String, Long> header) {
        if (header != null && !header.isEmpty()) {
            return new LinkedHashMap<>(header);
        }
        return new LinkedHashMap<>(MiningTracker.getInstance().powder());
    }

    private static List<String> lore(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) {
            lines.add(StyledText.strip(line.getString()));
        }
        return lines;
    }

    /** A perk name reduced to an id, for a perk the bundled catalogue does not carry. */
    /**
     * The cache id for a perk slot's name: the catalogue id, or the slug the reader files an
     * uncatalogued perk under. The one mapping, so the watch key and the highlight find exactly the
     * entries the reader wrote.
     */
    public static String perkIdFor(String name) {
        HotmData.Perk perk = HotmCatalog.byName(name);
        return perk != null ? perk.id : slug(name);
    }

    private static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
