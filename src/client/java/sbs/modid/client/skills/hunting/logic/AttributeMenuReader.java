/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.Rarity;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.economy.essenceshop.logic.PerkLevelReader;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.skills.hunting.model.ShardContext;
import sbs.modid.client.skills.hunting.model.ShardDefinition;
import sbs.modid.client.skills.hunting.model.ShardRarity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Attribute Menu: which shard feeds each attribute, and how many of it has gone in.
 *
 * <p><b>The entries are attributes, not shards, and that is what broke this feature.</b> An entry is
 * named for the attribute plus a roman tier - "Berry Eater IX" - so matching the display name
 * against a shard catalogue matches almost nothing, and the few accidental hits are wrong. The shard
 * is stated in the entry's own lore instead: {@code "Source: Toxic Shard (C12)"}. That line is the
 * only per-shard identity this menu carries, {@link ShardResolver} is where it is parsed, and
 * <b>the display name is never consulted here</b> - not as a fallback, not as a last resort. A
 * fallback would re-introduce exactly the wrong answers this class exists to stop, and they do not
 * look like failures from outside.
 *
 * <p><b>The amount is derived, not guessed.</b> The name carries the tier and the lore carries
 * "Syphon N shards to level up!", which together with the rarity's levelling row give the exact
 * number already syphoned - see {@link ShardLevelling}. Where the levelling row is absent the amount
 * is reported unknown rather than estimated: the Hunting Box states its own totals outright and is
 * the better source when this one cannot answer.
 *
 * <p><b>Timing is packet-driven, and it has to be.</b> Hypixel opens the menu and fills its slots
 * afterwards, so a scan on open reads a half-empty container. The first scan therefore happens a
 * tick after the screen appears, and every container packet marks the menu for a re-scan
 * ({@link #onContainerUpdated}, fed by {@code ContainerContentMixin}). What the menu turned out to
 * hold is <i>reported</i>, never required - a fetched or partial answer must never gate a detection.
 * See {@code docs/issues/skills.md}.
 *
 * <p><b>It never clicks anything.</b> The menu is read, never driven: no page is turned, no category
 * is selected, no slot is touched. Paging is the player's, exactly as they do it.
 */
public final class AttributeMenuReader {

    private static final AttributeMenuReader INSTANCE = new AttributeMenuReader();

    /**
     * "Syphon 12 shards to level up!" / "… to unlock!" - the remainder, stated in words.
     *
     * <p>Deliberately looser than the sentence it came from: the count, the noun and the verb are
     * anchored and nothing else is, so a reworded line of the same shape still reads.
     */
    private static final Pattern SYPHON_NEED = Pattern.compile(
            "(?i)syphon\\s+([0-9][0-9,]*)\\s+shards?\\s+to\\s+(level\\s*up|unlock)");

    /** How many unmatched entry names go into the log line. Enough to fix a matcher, short enough to read. */
    private static final int SAMPLE = 6;

    /**
     * What the last scan found, for the panel's footer and for the log.
     *
     * <p><b>There is no page count and no completeness flag any more.</b> The list of shards that
     * exist is now a bundled file rather than something assembled from what the menu had shown, so
     * "have all the pages been seen" stopped being a question the missing list depends on. It was
     * also unanswerable in practice - a menu whose numbering was never read might be one page or
     * five recorded on top of one another - and a success check nothing can satisfy is worse than
     * none.
     *
     * @param title     the menu's title as matched, page marker stripped
     * @param filled    slots inside the content window holding an item - the denominator
     * @param entries   of those, the ones whose lore named a shard
     * @param unmatched of those, the ones that named nothing. Reported, because a menu that is
     *                  understood nowhere looks exactly like one holding nothing you need
     * @param derived   entries whose syphoned amount could actually be computed
     */
    public record Facts(String title, int filled, int entries, int unmatched, int derived) {
    }

    private Object memoScreen;
    private int memoState = Integer.MIN_VALUE;
    private boolean memoIsMenu;

    /** The screen the current scan cycle belongs to. */
    private Object openScreen;

    /** Ticks since {@link #openScreen} appeared; the first scan waits for one to pass. */
    private int ticksOpen;

    /**
     * Set by the container packets and cleared by the scan.
     *
     * <p>Volatile because it is written on the network thread and read on the client thread. Nothing
     * else crosses that boundary here - the slots themselves are only ever walked on the client
     * thread, which is what makes a plain flag sufficient.
     */
    private volatile boolean dirty;

    /** Menu revision already reported, so one open menu is one log line rather than one per scan. */
    private int loggedState = Integer.MIN_VALUE;

    private volatile Facts lastFacts;

    private AttributeMenuReader() {
    }

    public static AttributeMenuReader getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.HuntingSettings cfg() {
        return ConfigManager.getInstance().get().hunting;
    }

    /** What the last scan found, or {@code null} when nothing has been scanned this session. */
    public Facts lastFacts() {
        return lastFacts;
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    /**
     * Whether this screen is the Attribute Menu. Memoised per menu revision, so the render path may
     * call it every frame - the negative answer is memoised too, and that is the important half:
     * without it every chest in the game would walk its slots looking for shards.
     */
    public boolean isOpen(AbstractContainerScreen<?> screen) {
        if (screen == null || !cfg().missingShards) {
            return false;
        }
        int stateId = screen.getMenu().getStateId();
        if (memoScreen != screen || memoState != stateId) {
            memoScreen = screen;
            memoState = stateId;
            memoIsMenu = detect(screen);
        }
        return memoIsMenu;
    }

    /**
     * The title test: {@link ShardContext} past an optional {@code (11/13)} page marker, plus the
     * configured fragment as an alternative so a Hypixel rename is fixable without an update.
     */
    private static boolean detect(AbstractContainerScreen<?> screen) {
        String raw = screen.getTitle() == null ? "" : screen.getTitle().getString();
        if (ShardContext.fromTitle(raw) == ShardContext.ATTRIBUTE_MENU) {
            return true;
        }
        String configured = cfg().missingShardsTitle;
        if (configured == null || configured.isBlank()) {
            return false;
        }
        String title = ShardContext.normalise(raw);
        return !title.isEmpty()
                && title.contains(configured.trim().toLowerCase(Locale.ROOT))
                && ShardContext.fromTitle(raw) != ShardContext.HUNTING_BOX;
    }

    // ------------------------------------------------------------------
    // Timing
    // ------------------------------------------------------------------

    /**
     * A container packet arrived. Marks the open menu for a re-scan and returns.
     *
     * <p>Called on the network thread for <b>every</b> container packet in the game, so it does one
     * field write and nothing else. The scan itself happens on the next client tick, which both
     * keeps slot reading on the main thread and collapses a burst of packets into one scan.
     */
    public void onContainerUpdated() {
        dirty = true;
    }

    /**
     * Client tick. Scans a tick after the menu appears and again whenever its contents changed.
     *
     * <p>The one-tick delay is not a cosmetic throttle: Hypixel sends the open packet and the
     * contents separately, so a scan on the same tick as the screen reads an empty container and
     * records a menu full of nothing.
     */
    public void tick(Minecraft minecraft) {
        try {
            if (minecraft == null || !cfg().missingShards) {
                return;
            }
            Screen screen = GuiStateManager.getInstance().getCurrentScreen();
            if (!(screen instanceof AbstractContainerScreen<?> container) || !isOpen(container)) {
                openScreen = null;
                ticksOpen = 0;
                return;
            }
            if (openScreen != container) {
                openScreen = container;
                ticksOpen = 0;
                dirty = true;
                return;   // the contents are still arriving; read them next tick
            }
            ticksOpen++;
            if (!dirty || ticksOpen < 1) {
                return;
            }
            dirty = false;
            scan(container);
        } catch (Throwable failed) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Shards] reading the attribute menu failed", failed);
        }
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    private void scan(AbstractContainerScreen<?> container) {
        AbstractContainerMenu menu = container.getMenu();
        String title = ShardContext.normalise(
                container.getTitle() == null ? "" : container.getTitle().getString());

        ShardOwnership ownership = ShardOwnership.getInstance();
        List<String> unmatched = new ArrayList<>();
        int filled = 0;
        int entries = 0;
        int derived = 0;

        // The menu's own slots, which is the total minus the player's 36. Bounding on the total
        // instead would read the player's own inventory as menu entries in any menu shorter than six
        // rows - slot 27 of a three-row chest is the first hotbar slot, not an attribute.
        int containerSlots = Math.max(0, menu.getItems().size() - 36);
        int last = Math.min(ShardContext.LAST_CONTENT_SLOT, containerSlots - 1);
        for (int slot = ShardContext.FIRST_CONTENT_SLOT; slot <= last; slot++) {
            if (!ShardContext.isContentSlot(slot)) {
                continue;   // the filler panes down each side of every Hypixel menu
            }
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            filled++;
            ShardResolver.Resolution resolution =
                    ShardResolver.resolve(stack, ShardContext.ATTRIBUTE_MENU);
            if (!resolution.resolved()) {
                // No "Source:" line: a control, a filler, or an entry shaped in a way nothing here
                // has been shown. Which of those cannot be decided from inside, so the name is kept
                // for the log rather than guessed at.
                unmatched.add(PlainText.strip(stack.getHoverName().getString()).trim());
                continue;
            }
            entries++;
            if (record(ownership, stack, resolution)) {
                derived++;
            }
        }

        lastFacts = new Facts(title, filled, entries, unmatched.size(), derived);
        report(menu.getStateId(), title, filled, entries, unmatched, derived);
    }

    /**
     * Writes one entry into the ownership store.
     *
     * @return whether an exact syphoned amount could be derived for it
     */
    private static boolean record(ShardOwnership ownership, ItemStack stack,
                                  ShardResolver.Resolution resolution) {
        List<String> lore = ItemPriceKey.lore(stack);
        Syphon syphon = syphon(lore);

        // The tier is written after the attribute's name ("Berry Eater IX"). An attribute that is
        // not started yet has no tier on its name at all, and its lore says "to unlock" rather than
        // "to level up" - which is what separates "tier 0" from "the name simply did not say".
        String name = PlainText.strip(stack.getHoverName().getString()).trim();
        PerkLevelReader.NamedLevel named = PerkLevelReader.splitTrailingLevel(name);
        int tier = named != null ? named.level() : syphon != null && syphon.unlock ? 0 : -1;

        ShardRarity rarity = rarityOf(stack, resolution.shard());
        int syphoned = syphon == null || tier < 0
                ? ShardLevelling.UNKNOWN
                : ShardLevelling.ownedFrom(rarity, tier, syphon.remaining);

        ownership.noteMenu(resolution.canonicalId(), resolution.displayName(), tier, syphoned);
        return syphoned >= 0;
    }

    /** The rarity of the shard an entry names: the item's own reading, else the catalogue's. */
    private static ShardRarity rarityOf(ItemStack stack, ShardDefinition shard) {
        ShardRarity rarity = ShardRarity.from(Rarity.detect(stack));
        if (rarity != ShardRarity.UNKNOWN) {
            return rarity;
        }
        return shard == null ? ShardRarity.UNKNOWN : shard.rarity();
    }

    /** A parsed "Syphon N shards to …" line. */
    private record Syphon(int remaining, boolean unlock) {
    }

    private static Syphon syphon(List<String> lore) {
        for (String line : lore) {
            Matcher matcher = SYPHON_NEED.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            int remaining = parseInt(matcher.group(1).replace(",", ""), -1);
            if (remaining < 0) {
                continue;
            }
            return new Syphon(remaining,
                    matcher.group(2).toLowerCase(Locale.ROOT).startsWith("unlock"));
        }
        return null;
    }

    /**
     * Says what this scan saw, once per menu revision.
     *
     * <p><b>At {@code info}, and that is the fix rather than a detail.</b> This ran at {@code debug}
     * for its whole first life, which is off in every client anybody plays on - so a feature built
     * entirely against unverified wording had its one diagnostic switched off in exactly the
     * situation it was written for.
     */
    private void report(int stateId, String title, int filled, int entries, List<String> unmatched,
                        int derived) {
        if (stateId == loggedState) {
            return;
        }
        loggedState = stateId;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Shards] \"{}\": {} filled slot(s), {} named a shard, {} did not, {} amount(s) "
                        + "derived", title, filled, entries, unmatched.size(), derived);
        if (!unmatched.isEmpty()) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Shards] entries with no \"Source:\" line: {}",
                    sample(unmatched));
        }
        if (entries > 0 && derived == 0) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Shards] no amount could be derived - shards.json carries no levelling "
                            + "table, so the Hunting Box is the only exact source of owned counts");
        }
    }

    /** The first few of a list, with a count of what was left out. */
    private static String sample(List<String> values) {
        List<String> few = values.subList(0, Math.min(SAMPLE, values.size()));
        String joined = String.join(", ", few);
        return values.size() <= SAMPLE ? joined
                : joined + " (+" + (values.size() - SAMPLE) + " more)";
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException | NullPointerException notANumber) {
            return fallback;
        }
    }
}
