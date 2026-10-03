/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.prerender;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The identity of one cached Bazaar screen, and the rules for refusing to give one.
 *
 * <p><b>Refusing is the important half.</b> A key that is wrong does not fail loudly — it serves the
 * wrong layout for the screen the player just opened, which on a Bazaar is a layout whose slots mean
 * prices. Every branch below that cannot prove which screen it is looking at returns {@code null},
 * and a {@code null} key means the screen is neither captured nor previewed. Behaving exactly as
 * today is always an available answer; guessing is not.
 *
 * <p><b>Pagination is the case this exists for.</b> A category holding more items than one chest can
 * show paginates, and if those pages share a title then page 2 keys the same as page 1 — so a click
 * to page 2 would render page 1's items. Hypixel does put counters in some menu titles (the Pets
 * menu is {@code "(1/3) Pets"}), so where one is present it becomes part of the key. Where the
 * container shows page arrows and the title carries no counter, the screen is <b>refused</b>: that
 * is the ambiguous case, and it is not known from this codebase whether the Bazaar produces it. The
 * diagnostics log it so the question can be settled from real data rather than assumed either way.
 *
 * <p>Whether a title is a Bazaar screen at all is not decided here — it is
 * {@link sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker#isBazaarGui}, which already owns
 * that answer for the whole mod and lists the real container titles Hypixel uses.
 */
public final class BazaarScreenKey {

    /**
     * Bumped when the shape of what is stored changes. An on-disk cache written by an older build is
     * discarded rather than read, because a layout decoded under the wrong assumptions is exactly the
     * silently-wrong render this feature has to avoid.
     */
    public static final int FORMAT_VERSION = 1;

    /** A page counter anywhere in the title: {@code (1/3)}. Hypixel puts it first where it uses one. */
    private static final Pattern PAGE_COUNTER = Pattern.compile("\\((\\d{1,3})\\s*/\\s*(\\d{1,3})\\)");

    /** Minecraft formatting codes, stripped so a recolour never changes an identity. */
    private static final Pattern FORMATTING = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    /** Names of the paging controls, lowercased. Their presence is what makes a missing counter fatal. */
    private static final List<String> PAGE_ARROW_NAMES = List.of("next page", "previous page");

    private BazaarScreenKey() {
    }

    /**
     * The stored identity of a screen, or {@code null} when it cannot be established.
     *
     * @param rawTitle   the container title, formatting codes and all
     * @param slotCount  the menu's own slot count (excluding the player inventory), part of the key
     *                   because two screens can share a title and differ in size
     * @param menuStacks the menu's slots, read only to detect paging controls
     */
    public static String of(String rawTitle, int slotCount, List<ItemStack> menuStacks) {
        String title = normalize(rawTitle);
        if (title.isEmpty()) {
            return null;
        }
        Matcher counter = PAGE_COUNTER.matcher(title);
        if (counter.find()) {
            // A counter present is the easy case: it disambiguates the page and the rest of the title
            // is the same on every page, so the counter is removed from the body and re-added as a
            // field. "(2/3) bazaar > farming" and "(1/3) bazaar > farming" key apart and read alike.
            String body = title.substring(0, counter.start()) + title.substring(counter.end());
            return FORMAT_VERSION + "|" + squash(body) + "|" + slotCount + "|p" + counter.group(1);
        }
        if (hasPageArrows(menuStacks)) {
            return null;   // paginated with nothing to distinguish the page - see the class doc
        }
        return FORMAT_VERSION + "|" + squash(title) + "|" + slotCount + "|p0";
    }

    /**
     * Whether this screen pages without saying which page it is on — the one shape that is refused.
     *
     * <p>Exposed so the diagnostics can count how often it happens. If it never fires on a real
     * Bazaar, the refusal costs nothing and the title alone was sufficient after all; if it fires
     * constantly, category pages need a different key before any of this is useful.
     */
    public static boolean refusedForPaging(String rawTitle, List<ItemStack> menuStacks) {
        return !PAGE_COUNTER.matcher(normalize(rawTitle)).find() && hasPageArrows(menuStacks);
    }

    private static boolean hasPageArrows(List<ItemStack> menuStacks) {
        if (menuStacks == null) {
            return false;
        }
        for (ItemStack stack : menuStacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = normalize(stack.getHoverName().getString());
            for (String arrow : PAGE_ARROW_NAMES) {
                if (name.contains(arrow)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Lower-cased, formatting-stripped, whitespace-collapsed. The form every comparison here uses. */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return FORMATTING.matcher(raw).replaceAll("").trim().toLowerCase(Locale.ROOT);
    }

    /** Collapses internal whitespace so a title that gains a space does not become a second key. */
    private static String squash(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }
}
