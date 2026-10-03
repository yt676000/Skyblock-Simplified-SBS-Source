/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bitsshop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Bits Shop helper: what each offer is actually worth per bit, and which offers are the best
 * buys across the <b>whole</b> shop rather than just the page you happen to have open.
 *
 * <p><b>Detection is by content, not by title.</b> The shop's offers are spread over category
 * sub-menus whose titles this mod has no reliable list of, and a title whitelist would silently miss
 * any page Hypixel renames or adds. Instead a menu counts as a shop page when several of its items
 * carry a "{@code Cost … Bits}" line in their own lore - which is exactly what makes them offers.
 * That test needs no maintenance and works on the first day of any new category.
 *
 * <p><b>Value is the sell side.</b> "Worth it" means what the item fetches if you turn it back into
 * coins, so {@link ItemAppraisal.Side#SELL} - lowest BIN for auctionables, Bazaar instant-sell for
 * bazaar goods - read from the caches the mod already keeps warm, so ranking a page costs no
 * network. Offers no market prices (cosmetics, soulbound convenience items) are left unranked
 * rather than counted as worthless.
 */
public final class BitsShop {

    /** "Cost", then "1,350 Bits" - Hypixel splits it over two lines, but has also inlined it. */
    private static final Pattern BITS = Pattern.compile("([\\d,.]+)\\s*Bits?\\b", Pattern.CASE_INSENSITIVE);

    private static final char SECTION_SIGN = (char) 0x00A7;

    /** How many priced offers a menu needs before it is treated as a shop page. */
    private static final int PAGE_THRESHOLD = 2;

    /** Ranking is rebuilt at most this often - prices move on a crawl, not per frame. */
    private static final long RANK_INTERVAL_MS = 1_000L;

    /** One ranked offer: the catalogue entry, what it is worth now, and its coins per bit. */
    public record Ranked(BitsShopCatalog.Entry entry, long coins, double coinsPerBit, int rank) {
    }

    private static long rankedAt;
    private static boolean rankedOnce;
    private static List<Ranked> ranked = List.of();
    private static Map<String, Ranked> rankedById = Map.of();

    /** Per-screen memo for {@link #isShopPage}, invalidated by the menu's own revision counter. */
    private static AbstractContainerScreen<?> pageCacheScreen;
    private static int pageCacheState = -1;
    private static boolean pageCacheResult;

    /** Menu revision the learner last recorded, so a static page is not re-scanned every tick. */
    private static int learnedState = -1;
    private static Object learnedScreen;

    private BitsShop() {
    }

    private static SBSConfig.BitsShopSettings cfg() {
        return ConfigManager.getInstance().get().bitsShop;
    }

    // ------------------------------------------------------------------ reading the menu

    /** Strips Hypixel's colour codes so the lore can be pattern-matched. */
    private static String plain(String text) {
        return text == null ? "" : text.replaceAll(SECTION_SIGN + ".", "");
    }

    /**
     * The bits price written in an item's own lore, or {@code null} when it carries none.
     *
     * <p>The whole feature hangs off this line, and it is the one piece of data that is always
     * correct: it is Hypixel's own statement of the price, right there on the item.
     */
    public static Long bitsCost(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore == null) {
            return null;
        }
        for (Component line : lore.lines()) {
            Matcher matcher = BITS.matcher(plain(line.getString()));
            if (matcher.find()) {
                try {
                    long bits = Long.parseLong(matcher.group(1).replaceAll("[,.]", ""));
                    if (bits > 0) {
                        return bits;
                    }
                } catch (NumberFormatException ignored) {
                    // A malformed number is simply not a price; keep scanning the rest of the lore.
                }
            }
        }
        return null;
    }

    /**
     * Whether this container is one of the Bits Shop's pages (see the class note on detection).
     *
     * <p>Cached per screen and per menu revision. The test itself walks every slot's lore with a
     * regex, and the callers are a per-frame slot render and a per-frame tooltip build on
     * <i>every</i> container in the game - so run unguarded it would cost a few hundred regex
     * matches a frame while standing in any chest. {@code getStateId} is the server's own "the
     * contents changed" counter, which is exactly when the answer could differ.
     */
    /**
     * The memoised answer for {@code screen}, <b>without recomputing it</b> - "no" only when this
     * screen has already been examined and was not a shop page.
     *
     * <p>{@link #isShopPage} classifies by contents, which is right (an NPC shop is titled with the
     * NPC's name, so no title can identify one) and not cheap: on a miss it reads the lore of every
     * container slot. Its memo is keyed on the menu's revision, and a Bazaar page revises itself
     * constantly, so on the very screen this mod is slowest the memo almost never hits.
     *
     * <p>So the decorator's {@link sbs.modid.client.ui.render.RenderTier} asks this instead. An
     * unseen screen counts as "maybe" and the pass runs, which is what fills the memo in the first
     * place; a screen already known not to be a shop is background, and the frame budget's heartbeat
     * still re-asks it several times a second. A menu that only becomes a shop page later is
     * therefore noticed within a quarter of a second at worst, and immediately whenever the mod is
     * inside its frame budget.
     */
    public static boolean maybeShopPage(AbstractContainerScreen<?> screen) {
        return pageCacheScreen != screen || pageCacheResult;
    }

    public static boolean isShopPage(AbstractContainerScreen<?> screen) {
        if (screen == null) {
            return false;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int stateId = menu.getStateId();
        if (pageCacheScreen == screen && pageCacheState == stateId) {
            return pageCacheResult;
        }
        int priced = 0;
        boolean result = false;
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            if (bitsCost(slot.getItem()) != null && ++priced >= PAGE_THRESHOLD) {
                result = true;
                break;
            }
        }
        pageCacheScreen = screen;
        pageCacheState = stateId;
        pageCacheResult = result;
        return result;
    }

    // ------------------------------------------------------------------ learning

    /**
     * Records every offer on the open page. Called from the client tick, so a page the player merely
     * walks past is still learned - the comparison is only as good as the pages it has seen.
     */
    public static void learn(Minecraft minecraft) {
        try {
            if (!cfg().enabled || !cfg().learnOffers) {
                return;
            }
            Screen screen = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
            if (!(screen instanceof AbstractContainerScreen<?> container) || !isShopPage(container)) {
                return;
            }
            // A page sitting open is the same page every tick; only re-read it when the server says
            // its contents changed (or the player moved to a different menu entirely).
            int stateId = container.getMenu().getStateId();
            if (learnedScreen == screen && learnedState == stateId) {
                return;
            }
            learnedScreen = screen;
            learnedState = stateId;

            String menu = plain(screen.getTitle() == null ? "" : screen.getTitle().getString()).trim();
            for (Slot slot : container.getMenu().slots) {
                if (slot.container instanceof Inventory) {
                    continue;
                }
                ItemStack stack = slot.getItem();
                Long bits = bitsCost(stack);
                String id = stack == null ? "" : SkyblockItem.id(stack);
                if (bits != null && id != null && !id.isEmpty()) {
                    BitsShopCatalog.getInstance().record(id, plain(stack.getHoverName().getString()).trim(),
                            bits, Math.max(1, stack.getCount()), menu);
                }
            }
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Bits] learn failed: {}", t.toString());
        }
    }

    // ------------------------------------------------------------------ ranking

    /**
     * Every priceable offer, best coins-per-bit first. Rebuilt at most once a second: the inputs are
     * cached market snapshots that move on a crawl, and a menu redraws far more often than that.
     */
    public static List<Ranked> ranking() {
        long now = System.currentTimeMillis();
        // The "once" flag matters: an empty result is a legitimate answer (nothing learned yet, or
        // no offer priced), and without it every call would rebuild and the throttle would be moot
        // exactly in the case where the work is most pointless.
        if (rankedOnce && now - rankedAt < RANK_INTERVAL_MS) {
            return ranked;
        }
        rankedAt = now;
        rankedOnce = true;

        List<Ranked> out = new ArrayList<>();
        for (BitsShopCatalog.Entry entry : BitsShopCatalog.getInstance().all()) {
            if (entry.bits <= 0) {
                continue;
            }
            long coins = coinsFor(entry);
            if (coins <= 0) {
                continue;   // nothing a market knows - unranked rather than worthless
            }
            out.add(new Ranked(entry, coins, coins / (double) entry.bits, 0));
        }
        out.sort(Comparator.comparingDouble(Ranked::coinsPerBit).reversed());

        List<Ranked> numbered = new ArrayList<>(out.size());
        Map<String, Ranked> byId = new HashMap<>();
        for (int i = 0; i < out.size(); i++) {
            Ranked r = out.get(i);
            Ranked withRank = new Ranked(r.entry(), r.coins(), r.coinsPerBit(), i + 1);
            numbered.add(withRank);
            byId.put(r.entry().id, withRank);
        }
        ranked = List.copyOf(numbered);
        rankedById = Map.copyOf(byId);
        return ranked;
    }

    /** The ranked view of one item id, or {@code null} when it is not a priceable offer. */
    public static Ranked rankOf(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ranking();
        return rankedById.get(id);
    }

    /**
     * What one purchase of an offer sells for, in coins - the unit price times however many the
     * offer hands over, so a bulk deal is compared on what it actually gives you.
     *
     * <p>Priced <b>by id</b> rather than by appraising a rebuilt stack: an id the icon service does
     * not know comes back as a placeholder item, and appraising that would silently price the
     * placeholder (a barrier, a gold nugget) instead of the offer. A straight cache lookup either
     * knows the id or admits it does not.
     */
    private static long coinsFor(BitsShopCatalog.Entry entry) {
        Long unit = priceOf(entry.id);
        return unit == null ? 0 : unit * Math.max(1, entry.amount);
    }

    /**
     * The sell-side unit price of an id from the warm caches, or {@code null} when no market knows
     * it. Lowest BIN first (auctionables have only the one price), then the Bazaar instant-sell,
     * then whatever the chat/menu scraper has captured.
     */
    private static Long priceOf(String id) {
        Long lbin = sbs.modid.client.economy.prices.LbinCache.getInstance().getLbin(id);
        if (lbin != null && lbin > 0) {
            return lbin;
        }
        var bazaar = sbs.modid.client.economy.prices.BazaarPriceCache.getInstance().get(id);
        if (bazaar != null && bazaar.sell() > 0) {
            return bazaar.sell();
        }
        Long chat = sbs.modid.client.economy.prices.ChatPriceCache.getInstance().getUnitPrice(id);
        return chat != null && chat > 0 ? chat : null;
    }

    /** Human-readable coins-per-bit ("12.4"), the number the whole feature exists to show. */
    public static String formatPerBit(double coinsPerBit) {
        if (coinsPerBit >= 100) {
            return String.valueOf(Math.round(coinsPerBit));
        }
        return String.format(Locale.ROOT, "%.1f", coinsPerBit);
    }

    /** Convenience for the highlighter: the id an offer slot is for, or empty. */
    public static String offerId(ItemStack stack) {
        if (stack == null || stack.isEmpty() || bitsCost(stack) == null) {
            return "";
        }
        String id = SkyblockItem.id(stack);
        return id == null ? "" : id;
    }

    /** Whether the given container is worth drawing highlights on at all. */
    public static boolean shouldDecorate(AbstractContainerScreen<?> screen) {
        return cfg().enabled && isShopPage(screen);
    }

    /** Whether a bits-shop page is open right now - the gate for the tooltip line. */
    public static boolean shopOpen() {
        if (!cfg().enabled) {
            return false;
        }
        Screen screen = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        return screen instanceof AbstractContainerScreen<?> container && isShopPage(container);
    }
}
