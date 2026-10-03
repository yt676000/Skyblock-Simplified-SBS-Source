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
import sbs.modid.client.core.item.Rarity;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.skills.hunting.model.ShardContext;
import sbs.modid.client.skills.hunting.model.HuntingBoxShard;
import sbs.modid.client.skills.hunting.model.ShardRarity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Hunting Box menu while it is open, into {@link HuntingBoxStore} and
 * {@link ShardOwnership}.
 *
 * <p><b>Here the display name is the shard's own name</b>, unlike the Attribute Menu one screen
 * over, so {@link ShardContext#HUNTING_BOX} selects the name strategy and gets the right answer.
 * That difference between two adjacent menus is the whole reason resolution is per-context - see
 * {@link ShardResolver}.
 *
 * <p><b>The amount is stated outright, and it is the most exact number this feature has.</b> The box
 * writes {@code "Owned: 1,234 Shards"} in each entry's lore, which a vanilla stack capped at 64
 * cannot express - so that line is read first and the stack size is only a fallback. Nothing here is
 * derived and nothing is estimated, which is why the box remains the ownership source even once the
 * Attribute Menu's amounts work.
 *
 * <p><b>Sort-order independent by construction.</b> The box can be sorted by id or by newest, so
 * nothing here depends on where a shard sits: slots are collected into a map keyed by the canonical
 * shard id and the same kind found twice is summed. A re-sort produces an identical snapshot.
 */
public final class HuntingBoxScanner {

    private static final HuntingBoxScanner INSTANCE = new HuntingBoxScanner();

    /** How often the open box is re-read. It only changes when the player fuses or syphons. */
    private static final long RESCAN_MS = 1_000L;

    /**
     * {@code "Owned: 1,234 Shards"} - the box's own statement of how many are held.
     *
     * <p>Anchored on the word and the noun, comma-tolerant and locale-independent. A stack stops at
     * 64, so for a box holding thousands this line is the only place the real total can be.
     */
    private static final Pattern OWNED = Pattern.compile(
            "(?i)\\bowned\\s*:?\\s*([0-9][0-9,]*)\\s*shards?\\b");

    private long lastScanAt;
    private Object lastScreen;
    private int lastCount = -1;

    private HuntingBoxScanner() {
    }

    public static HuntingBoxScanner getInstance() {
        return INSTANCE;
    }

    /** True while the given screen is the Hunting Box (drives the panel and the tooltip lines). */
    public static boolean isBox(AbstractContainerScreen<?> screen) {
        return screen != null
                && isBoxTitle(screen.getTitle() == null ? "" : screen.getTitle().getString());
    }

    /**
     * Whether a title names the Hunting Box, past an optional {@code (2/4)} page marker.
     *
     * <p>Through {@link ShardContext} rather than a local {@code contains}, so this screen and the
     * Attribute Menu can never both claim one title - which they could while each matched a fragment
     * of its own.
     */
    public static boolean isBoxTitle(String title) {
        return ShardContext.fromTitle(title) == ShardContext.HUNTING_BOX;
    }

    /** Called once per client tick from the tracking mixin. */
    public void tick(Minecraft minecraft) {
        try {
            if (minecraft == null || !ConfigManager.getInstance().get().hunting.boxValue) {
                return;
            }
            Screen screen = GuiStateManager.getInstance().getCurrentScreen();
            if (!(screen instanceof AbstractContainerScreen<?> container) || !isBox(container)) {
                lastScreen = null;
                return;
            }
            if (screen != lastScreen) {
                lastScreen = screen;
                lastCount = -1;
                lastScanAt = 0;   // a freshly opened box is read at once
            }
            long now = System.currentTimeMillis();
            if (now - lastScanAt < RESCAN_MS) {
                return;
            }
            lastScanAt = now;
            scan(container.getMenu());
        } catch (Throwable failed) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][HuntingBox] scan failed", failed);
        }
    }

    private void scan(AbstractContainerMenu menu) {
        Map<String, HuntingBoxShard> found = new LinkedHashMap<>();
        // The menu's own slots, which is the total minus the player's 36. Bounding on the total
        // instead would read the player's own inventory as box contents in any menu shorter than six
        // rows - slot 27 of a three-row chest is the first hotbar slot, not an entry.
        int containerSlots = Math.max(0, menu.getItems().size() - 36);
        int last = Math.min(ShardContext.LAST_CONTENT_SLOT, containerSlots - 1);
        for (int slot = ShardContext.FIRST_CONTENT_SLOT; slot <= last; slot++) {
            if (!ShardContext.isContentSlot(slot)) {
                continue;   // border panes
            }
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ShardResolver.Resolution resolution =
                    ShardResolver.resolve(stack, ShardContext.HUNTING_BOX);
            if (!resolution.resolved()) {
                continue;   // navigation, filler, the sort button - not a shard
            }
            String key = resolution.key();
            int owned = ownedIn(ItemPriceKey.lore(stack));
            int amount = owned >= 0 ? owned : Math.max(0, stack.getCount());

            HuntingBoxShard shard = found.get(key);
            if (shard != null) {
                shard.add(amount);
                continue;
            }
            found.put(key, new HuntingBoxShard(key, resolution.displayName(),
                    rarityOf(stack, resolution), amount, owned >= 0));
        }

        // The box is the authority even when it is empty - a player who syphoned everything has an
        // empty box, and refusing to store that would leave yesterday's shards on screen for ever.
        List<HuntingBoxShard> shards = new ArrayList<>(found.values());
        HuntingBoxStore.getInstance().replace(shards);

        ShardOwnership ownership = ShardOwnership.getInstance();
        ownership.forgetBoxAmounts();
        for (HuntingBoxShard shard : shards) {
            ownership.noteBox(shard.id(), shard.name(), shard.count());
        }

        if (shards.size() != lastCount) {
            lastCount = shards.size();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][HuntingBox] read {} shard kind(s) from the box",
                    shards.size());
        }
    }

    /** The rarity of a box entry: the item's own reading, else what the catalogue files it as. */
    private static ShardRarity rarityOf(ItemStack stack, ShardResolver.Resolution resolution) {
        ShardRarity rarity = ShardRarity.from(Rarity.detect(stack));
        if (rarity != ShardRarity.UNKNOWN) {
            return rarity;
        }
        return resolution.shard() == null ? ShardRarity.UNKNOWN : resolution.shard().rarity();
    }

    /** The {@code "Owned: N Shards"} figure, or {@code -1} when no line states one. */
    public static int ownedIn(List<String> lore) {
        for (String line : lore) {
            Matcher matcher = OWNED.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            try {
                return Integer.parseInt(matcher.group(1).replace(",", ""));
            } catch (NumberFormatException tooBig) {
                return -1;   // longer than an int is not an amount; nothing beats inventing one
            }
        }
        return -1;
    }


}
