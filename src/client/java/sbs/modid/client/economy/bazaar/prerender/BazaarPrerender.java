/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.prerender;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.economy.bazaar.logic.BazaarOrderTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 1 of the Bazaar pre-render cache: watches Bazaar screens open, records what they looked
 * like, and measures whether a cached layout could have stood in for the one that arrived.
 *
 * <p><b>This phase renders nothing.</b> Its output is the diagnostics — match rate, which slots
 * moved, click-to-arrival latency, and the raw shape of the price lore. Those numbers are what
 * decide whether previewing is safe at all, and none of them can be guessed from the code.
 *
 * <p><b>Off unless the dev flag is on and this is switched on as well</b>, and a no-op when off:
 * disabled means the store is never loaded, never written, and no capture is taken. Two switches
 * rather than one because the dev flag is on for whole sessions of unrelated work, and a feature
 * that silently starts writing a megabyte of item lore because someone was debugging a HUD element
 * is not off by default in any sense that matters.
 *
 * <h2>What is captured, and what is deliberately not</h2>
 *
 * <p>Only screens that are pure layout: the Bazaar's own menus and category pages. <b>Product pages,
 * the orders menus and every confirm flow are refused</b>, because they carry the player's own
 * orders and amounts. Those are not layout, and a global cache holding them would show one account's
 * order state inside another profile's preview. Making them cacheable is a phase-2 question that
 * needs profile scoping, not a wider filter here.
 *
 * <p>Paginated screens whose page cannot be identified are refused by {@link BazaarScreenKey}, and
 * the refusal is counted — see {@link BazaarPrerenderDiagnostics}.
 *
 * <h2>Boundary</h2>
 *
 * <p>Purely local. Nothing is sent, no request is anticipated or duplicated, and the click that
 * opens a menu is the player's own — this only reads what the client already has, after the fact.
 */
public final class BazaarPrerender {

    /**
     * The second switch. Off unconditionally; there is no config row for it, by design — this phase
     * is a measurement tool, not a feature, and the only way to it is a dev build turning it on
     * deliberately.
     */
    public static volatile boolean ENABLED = false;

    /** Re-capturing the open screen more often than this is wasted work; the server fills slots in. */
    private static final long RECAPTURE_INTERVAL_MS = 500L;

    /** After this long with nothing arriving, a pending click is written off rather than left open. */
    private static final long CLICK_TIMEOUT_MS = 10_000L;

    /** Lore lines logged per screen while establishing the price format. Bounded so the log stays readable. */
    private static final int LORE_SAMPLE_SLOTS = 3;

    private static final BazaarPrerender INSTANCE = new BazaarPrerender();

    /** The key of the screen currently open, so a change of key is what "a new screen arrived" means. */
    private String currentKey;

    /** When the open screen was last written to the store. */
    private long lastCaptureMs;

    /** The screen and slot of the last click, waiting for something to arrive. */
    private String pendingFromKey;
    private int pendingFromSlot = -1;
    private long pendingClickAtMs;

    /** Screens whose lore has already been sampled into the log, so it is said once and not per frame. */
    private final List<String> loreSampled = new ArrayList<>();

    private BazaarPrerender() {
    }

    public static BazaarPrerender getInstance() {
        return INSTANCE;
    }

    /** Both switches. Everything in this class returns immediately when this is false. */
    public static boolean enabled() {
        return DevMode.ACTIVE && ENABLED;
    }

    /**
     * Called every frame a container screen is open.
     *
     * <p>Captures on a throttle rather than once on open, because the server fills a container's
     * slots in <i>after</i> the screen exists — capturing once would store an empty chest. The same
     * reason {@code helper/pets/PetsOverlay} rescans while its menu is open and bails when it finds
     * nothing.
     */
    public void onContainerFrame(AbstractContainerScreen<?> screen) {
        if (!enabled() || screen == null) {
            return;
        }
        try {
            frame(screen);
        } catch (Throwable t) {
            // A measurement tool must never be the reason a menu stops working.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][BzCache] capture failed", t);
        }
    }

    private void frame(AbstractContainerScreen<?> screen) {
        String rawTitle = screen.getTitle().getString();
        if (!BazaarOrderTracker.isBazaarGui(rawTitle)) {
            currentKey = null;
            return;
        }
        List<ItemStack> stacks = menuStacks(screen);
        if (isEmpty(stacks)) {
            return;   // the server has not filled the slots in yet
        }
        if (!cacheable(rawTitle)) {
            BazaarPrerenderDiagnostics.getInstance().countRefusedPlayerSpecific();
            return;
        }
        String key = BazaarScreenKey.of(rawTitle, stacks.size(), stacks);
        if (key == null) {
            BazaarPrerenderDiagnostics.getInstance().countRefusedPaging(rawTitle);
            return;
        }

        BazaarScreenStore store = BazaarScreenStore.getInstance();
        store.load();

        boolean isNewScreen = !key.equals(currentKey);
        CapturedScreen arriving = capture(key, rawTitle, stacks);

        if (isNewScreen) {
            onArrival(key, arriving, store);
            currentKey = key;
            lastCaptureMs = 0L;
        }
        if (System.currentTimeMillis() - lastCaptureMs < RECAPTURE_INTERVAL_MS) {
            return;
        }
        lastCaptureMs = System.currentTimeMillis();
        sampleLore(key, stacks);
        store.put(arriving);
        store.save();
    }

    /**
     * A Bazaar screen just became the open one: the moment every phase-1 number is taken.
     *
     * <p>The comparison is made against what was stored <i>before</i> this visit overwrote it, which
     * is the only point at which "would the cache have been right" is answerable.
     */
    private void onArrival(String key, CapturedScreen arriving, BazaarScreenStore store) {
        BazaarPrerenderDiagnostics diagnostics = BazaarPrerenderDiagnostics.getInstance();
        CapturedScreen cached = store.get(key);
        diagnostics.recordArrival(key, cached, arriving);

        long now = System.currentTimeMillis();
        if (pendingFromKey != null && now - pendingClickAtMs <= CLICK_TIMEOUT_MS) {
            diagnostics.recordLatency(pendingFromKey, key, now - pendingClickAtMs);
            CapturedScreen from = store.get(pendingFromKey);
            if (from != null && pendingFromSlot >= 0) {
                // The click-to-screen edge: without this there is nothing to look a cache up by at
                // click time, because the arriving title does not exist yet. See CapturedScreen.
                from.recordOpens(pendingFromSlot, key);
                store.put(from);
            }
        }
        pendingFromKey = null;
        pendingFromSlot = -1;
    }

    /**
     * Called when the player clicks a slot in a container screen.
     *
     * <p>Observation only — the click is not intercepted, delayed or altered in any way. It is
     * recorded so the next arrival can be attributed to it, which is what produces both the latency
     * measurement and the click-to-screen edge.
     */
    public void onSlotClick(AbstractContainerScreen<?> screen, int slotIndex) {
        if (!enabled() || screen == null || slotIndex < 0) {
            return;
        }
        try {
            String rawTitle = screen.getTitle().getString();
            if (!BazaarOrderTracker.isBazaarGui(rawTitle) || !cacheable(rawTitle)) {
                return;
            }
            List<ItemStack> stacks = menuStacks(screen);
            String key = BazaarScreenKey.of(rawTitle, stacks.size(), stacks);
            if (key == null) {
                return;
            }
            pendingFromKey = key;
            pendingFromSlot = slotIndex;
            pendingClickAtMs = System.currentTimeMillis();
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][BzCache] click observation failed", t);
        }
    }

    /**
     * Whether this title is pure layout and therefore safe to hold in a global cache.
     *
     * <p>An allowlist in effect: a Bazaar screen has to be a category or navigation menu to pass.
     * Product pages ({@code ➜ <item>}), the orders menus and the confirm flows all carry the
     * player's own state and are refused. Getting this wrong writes one account's orders into a file
     * shared by every profile, so it is written as "prove it is a category" rather than "exclude the
     * ones I thought of".
     */
    private static boolean cacheable(String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.trim().toLowerCase(Locale.ROOT);
        if (title.isEmpty() || !title.contains("bazaar")) {
            return false;   // excludes "➜ <product>" and every confirm flow, which never say "bazaar"
        }
        return !BazaarOrderTracker.isOrdersMenu(title)
                && !title.startsWith("confirm")
                && !title.contains("order options");
    }

    private static CapturedScreen capture(String key, String rawTitle, List<ItemStack> stacks) {
        CapturedScreen screen = new CapturedScreen(key, rawTitle, stacks.size(),
                System.currentTimeMillis());
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack != null && !stack.isEmpty()) {
                screen.putSlot(i, encodeStack(stack));
            }
        }
        return screen;
    }

    /**
     * The menu's own slots, excluding the player's inventory.
     *
     * <p>{@code AbstractContainerMenu.slots} runs the container's slots first and the player
     * inventory last, so the container is everything whose backing container is not the player's.
     * Taking the whole list instead would key a screen on the contents of the player's own bags.
     */
    private static List<ItemStack> menuStacks(AbstractContainerScreen<?> screen) {
        List<ItemStack> stacks = new ArrayList<>();
        AbstractContainerMenu menu = screen.getMenu();
        if (menu == null) {
            return stacks;
        }
        var inventory = Minecraft.getInstance().player == null
                ? null : Minecraft.getInstance().player.getInventory();
        for (Slot slot : menu.slots) {
            if (inventory != null && slot.container == inventory) {
                break;
            }
            stacks.add(slot.getItem());
        }
        return stacks;
    }

    private static boolean isEmpty(List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Logs the raw lore of the first few filled slots, once per screen key per session.
     *
     * <p>This is the diagnostic the price modes depend on. Nothing in this mod has ever parsed a
     * Bazaar category or product price line — the only lore parsing that exists reads the orders
     * menu — so there is no format on record to substitute live figures into. Until these lines have
     * been read, live substitution is writing numbers into a shape nobody has checked, and the
     * feature's own rule says the stale-marking mode is the default instead.
     */
    private void sampleLore(String key, List<ItemStack> stacks) {
        if (loreSampled.contains(key)) {
            return;
        }
        loreSampled.add(key);
        int sampled = 0;
        for (int i = 0; i < stacks.size() && sampled < LORE_SAMPLE_SLOTS; i++) {
            ItemStack stack = stacks.get(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            List<String> lore = BazaarPrerenderDiagnostics.loreOf(stack);
            if (lore.isEmpty()) {
                continue;
            }
            sampled++;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][BzCache] lore sample '{}' slot {} name='{}'",
                    key, i, stack.getHoverName().getString());
            for (String line : lore) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][BzCache]     | {}", line);
            }
        }
    }

    /** SNBT with every component, the round trip {@code StorageIndex} uses. */
    private static String encodeStack(ItemStack stack) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return "";
            }
            var ops = minecraft.level.registryAccess()
                    .createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
            return ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result()
                    .map(Object::toString).orElse("");
        } catch (Throwable t) {
            return "";
        }
    }
}
