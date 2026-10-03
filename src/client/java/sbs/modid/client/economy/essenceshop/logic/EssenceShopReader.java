/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.essenceshop.logic;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.economy.essenceshop.model.EssenceType;
import sbs.modid.client.economy.essenceshop.model.LevelSource;
import sbs.modid.client.economy.essenceshop.model.PerkRow;
import sbs.modid.client.economy.essenceshop.model.ShopOverview;
import sbs.modid.client.economy.essenceshop.model.ShopPerk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads an open essence shop: which essence it spends, what level each perk is at, and how much
 * essence the player has - then turns that into the overview the panel draws.
 *
 * <p><b>Detection is by content, not by title.</b> There is one shop per essence, spread over NPCs
 * across the map, and nobody here has read all of their title bars. A menu counts as a shop when the
 * essence it spends can be identified <i>and</i> the perk table knows that essence - a test that
 * needs no maintenance and works on the first day of a shop nobody has seen.
 *
 * <p><b>Perks are matched by name, never by slot.</b> Slot positions move whenever a shop gains a
 * perk; the table's own perk key is what any stored state is keyed on.
 *
 * <p><b>What is not verified.</b> The exact wording of the level line, the balance line and the
 * maxed marker has not been read off a live client, so reading them is {@link PerkLevelReader}'s
 * job alone and it fails to "not known" rather than to a number: a perk whose level cannot be read
 * is left out of the total and the panel says how many those were. {@code /sbs probe} in the open
 * shop settles the wording, and an unreadable perk is logged once per menu so a capture is not even
 * needed to notice one.
 *
 * <p>Computed once per menu revision ({@link AbstractContainerMenu#getStateId()}), never per frame.
 */
public final class EssenceShopReader {

    private static final EssenceShopReader INSTANCE = new EssenceShopReader();

    /** How long a computed overview may still be shown after leaving the perk list, in ms. */
    private static final long CARRY_OVER_MS = 30_000L;

    /**
     * Memo of the last read, invalidated by the menu's own revision counter.
     *
     * <p>It covers the <b>negative</b> answer too, and that is the important half: this runs for
     * every container screen in the game, several times a frame, and almost none of them are essence
     * shops. Without the memo every chest the player opens would walk its slots looking for a
     * currency icon on every frame.
     */
    private Object cachedScreen;
    private int cachedState = -1;
    private Result cached;

    /** The last overview with real content, kept so a confirmation menu does not blank the panel. */
    private ShopOverview lastFull;
    private long lastFullAt;

    /** Menu revision whose unreadable perks have already been logged, so the log is not a loop. */
    private int loggedState = -1;

    private EssenceShopReader() {
    }

    public static EssenceShopReader getInstance() {
        return INSTANCE;
    }

    /**
     * What to draw for this screen, or {@code null} when it is not an essence shop.
     *
     * @param overview    the computed shop
     * @param carriedOver whether this is the previous menu's overview, kept because the current one
     *                    (a purchase confirmation) has no perk list of its own
     */
    public record Result(ShopOverview overview, boolean carriedOver) {
    }

    /** The overview for the open screen, or {@code null}. Cheap to call per frame. */
    public Result read(AbstractContainerScreen<?> screen) {
        if (screen == null) {
            return null;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int stateId = menu.getStateId();
        if (cachedScreen != screen || cachedState != stateId) {
            cachedScreen = screen;
            cachedState = stateId;
            EssenceType type = detect(screen);
            ShopOverview overview = type == null ? null : parse(screen, type, stateId);
            cached = overview == null ? null : new Result(overview, false);
            if (overview != null && overview.unknownCount() < overview.rows().size()) {
                lastFull = overview;
                lastFullAt = System.currentTimeMillis();
            }
        }
        if (cached == null) {
            return null;
        }
        // Nothing on this screen was recognisable, but we were reading this very shop moments ago:
        // this is the purchase confirmation, so keep the numbers up instead of blanking them at the
        // exact moment the player is deciding whether to spend. Re-decided per call rather than
        // cached, because it stops being true purely with the passing of time.
        ShopOverview overview = cached.overview();
        if (overview.unknownCount() == overview.rows().size() && lastFull != null
                && lastFull.type().id().equals(overview.type().id())
                && System.currentTimeMillis() - lastFullAt < CARRY_OVER_MS) {
            return new Result(lastFull, true);
        }
        return cached;
    }

    // ------------------------------------------------------------------ detection

    /** Which essence this menu spends, or {@code null} when it is not a perk shop. */
    private static EssenceType detect(AbstractContainerScreen<?> screen) {
        EssencePerkData data = EssencePerkData.getInstance();
        if (!data.available()) {
            return null;   // no table, no shop - the panel has nothing it could stand behind
        }
        // The menu's own icons name the currency exactly; the title is only a fallback for a shop
        // that shows no essence item at all.
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            EssenceType type = EssenceType.fromId(SkyblockItem.id(stack));
            if (type != null && data.isShopCurrency(type.id())) {
                return type;
            }
        }
        String title = screen.getTitle() == null ? ""
                : PlainText.strip(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
        if (title.contains("essence")) {
            for (String essenceId : List.copyOf(EssencePerkData.getInstance().shopIds())) {
                EssenceType type = EssenceType.fromId(essenceId);
                if (type != null && title.contains(type.displayName().toLowerCase(Locale.ROOT))) {
                    return type;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ parsing

    private ShopOverview parse(AbstractContainerScreen<?> screen, EssenceType type, int stateId) {
        EssencePerkData data = EssencePerkData.getInstance();
        List<ShopPerk> perks = data.perks(type.id());
        Map<String, PerkRow> found = new HashMap<>();
        List<String> unreadable = new ArrayList<>();
        long balance = -1;

        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            List<String> lore = plainLore(stack);
            if (balance < 0) {
                balance = PerkLevelReader.balanceFrom(lore);
            }
            String name = PlainText.strip(stack.getHoverName().getString()).trim();
            ShopPerk perk = data.perkNamed(type.id(), name);
            int nameLevel = 0;
            if (perk == null) {
                // "Forbidden Strength III" - the table stores the name without the level.
                PerkLevelReader.NamedLevel trailing = PerkLevelReader.splitTrailingLevel(name);
                if (trailing != null) {
                    nameLevel = trailing.level();
                    perk = data.perkNamed(type.id(), trailing.base());
                }
            }
            if (perk == null || found.containsKey(perk.key())) {
                continue;   // not a perk, or a perk already read from an earlier slot
            }
            int fromLore = PerkLevelReader.fromLore(lore, perk.maxLevel());
            if (fromLore >= 0) {
                found.put(perk.key(), PerkRow.known(perk, fromLore, LevelSource.LORE));
            } else if (nameLevel > 0) {
                found.put(perk.key(),
                        PerkRow.known(perk, Math.min(nameLevel, perk.maxLevel()), LevelSource.NAME));
            } else {
                found.put(perk.key(), PerkRow.unknown(perk));
                unreadable.add(perk.name() + " → " + String.join(" | ", lore));
            }
        }

        List<PerkRow> rows = new ArrayList<>(perks.size());
        long total = 0;
        for (ShopPerk perk : perks) {
            PerkRow row = found.getOrDefault(perk.key(), PerkRow.unknown(perk));
            rows.add(row);
            if (row.counted()) {
                total += row.remaining();
            }
        }
        logUnreadable(type, stateId, rows, unreadable);
        return new ShopOverview(type, rows, total, balance, System.currentTimeMillis());
    }

    /** An item's lore, colour-stripped, empty when it has none. */
    private static List<String> plainLore(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) {
            lines.add(PlainText.strip(line.getString()).trim());
        }
        return lines;
    }

    /**
     * Reports perks whose level could not be read, once per menu revision.
     *
     * <p>The wording these patterns are written against is unconfirmed, so the failure mode that
     * matters is the silent one: a panel full of "?" with nothing anywhere saying why. The lore that
     * did not parse goes into the log with it, which is enough to fix the pattern without a capture.
     */
    private void logUnreadable(EssenceType type, int stateId, List<PerkRow> rows, List<String> lore) {
        if (stateId == loggedState || lore.isEmpty()) {
            return;
        }
        loggedState = stateId;
        SkyblockSimplifiedSBS.LOGGER.debug(
                "[SBS][Essence] {}: {} of {} perk level(s) unreadable. First: {}",
                type.displayName(), lore.size(), rows.size(), lore.get(0));
    }
}
