/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.cooldowns;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SBS's own SkyBlock ability-cooldown clock – vanilla {@code ItemCooldowns} never fires on Hypixel,
 * so cooldowns are computed client-side and simply counted down by wall clock:
 * <ul>
 *   <li><b>Start</b>: when an item is used (right-click hook), its lore is scanned for
 *       "Cooldown: 30s" and a timer of that length starts for the item's id.</li>
 *   <li><b>Wither blades</b> (Hyperion, Astraea, Scylla, Valkyrie, Necron's Blade): their ability
 *       itself has no cooldown line, but the embedded <i>Wither Shield</i> always has 5s – so a
 *       fixed 5s timer starts on use.</li>
 *   <li><b>Chat correction</b>: "This ability is on cooldown for 29s." overrides the last-used
 *       item's remaining time (lag-proofing), also (re)starting the timer if the use hook missed.</li>
 * </ul>
 * Keys are the item's price-lookup identity (SkyBlock id preferred), timers are epoch-millis based
 * and thus tick down on their own.
 */
public final class AbilityCooldowns {

    private static final AbilityCooldowns INSTANCE = new AbilityCooldowns();

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);
    private static final Pattern LORE_COOLDOWN = Pattern.compile("(?i)Cooldown:\\s*([0-9]+)s");
    private static final Pattern CHAT_COOLDOWN =
            Pattern.compile("(?i)This ability is on cooldown for\\s*([0-9]+)s");

    /** Wither blades: using them triggers Wither Shield's fixed 5s cooldown (not in their lore). */
    private static final Set<String> WITHER_BLADES =
            Set.of("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE", "NECRON_BLADE");
    private static final int WITHER_SHIELD_SECONDS = 5;

    private record Entry(long endMillis, long durationMillis) {
    }

    private final Map<String, Entry> timers = new ConcurrentHashMap<>();
    private volatile String lastUsedKey;

    private AbilityCooldowns() {
    }

    public static AbilityCooldowns getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Starting timers
    // ------------------------------------------------------------------

    /** Called from the use hooks: derives the item's cooldown and starts its timer. */
    public void onItemUse(ItemStack stack) {
        String key = keyOf(stack);
        if (key == null) {
            return;
        }
        lastUsedKey = key;
        // Clicking again while the cooldown is running does NOT reset it in-game (the ability
        // simply doesn't fire) – so an active timer is left untouched.
        Entry running = timers.get(key);
        if (running != null && running.endMillis() > System.currentTimeMillis()) {
            return;
        }
        // Wither blades: the "Wither Impact" ability itself has no cooldown, but its bonus-hearts
        // component "Wither Shield" always has 5s – detected by id OR by the ability text in the
        // lore, so renamed/future blades are covered too.
        String skyblockId = SkyblockItem.id(stack);
        if ((skyblockId != null && WITHER_BLADES.contains(skyblockId.toUpperCase(Locale.ROOT)))
                || hasWitherAbility(stack)) {
            start(key, WITHER_SHIELD_SECONDS);
            return;
        }
        int seconds = loreCooldownSeconds(stack);
        if (seconds > 0) {
            start(key, seconds);
        }
    }

    /** True when the item's lore mentions the Wither Impact / Wither Shield ability. */
    private static boolean hasWitherAbility(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        for (Component line : lore.lines()) {
            String text = line.getString().replaceAll(SECTION_SIGN + ".", "");
            if (text.contains("Wither Impact") || text.contains("Wither Shield")) {
                return true;
            }
        }
        return false;
    }

    /** Chat lag-correction: overrides the remaining time of the most recently used item. */
    public void parseChat(String message) {
        if (message == null) {
            return;
        }
        Matcher m = CHAT_COOLDOWN.matcher(message.replaceAll(SECTION_SIGN + ".", ""));
        if (!m.find()) {
            return;
        }
        String key = lastUsedKey;
        if (key == null) {
            return;
        }
        int remaining = Integer.parseInt(m.group(1));
        Entry existing = timers.get(key);
        long duration = existing != null ? existing.durationMillis() : remaining * 1000L;
        timers.put(key, new Entry(System.currentTimeMillis() + remaining * 1000L,
                Math.max(duration, remaining * 1000L)));
        SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Cooldown] Chat correction: {} -> {}s", key, remaining);
    }

    /** The item most recently used, as its timer key, or {@code null}. */
    public String lastUsedKey() {
        return lastUsedKey;
    }

    /** Ends the item's timer at once - Hypixel said its ability is available again. */
    public void clear(String key) {
        if (key != null) {
            timers.remove(key);
        }
    }

    /** The timer key of {@code stack}, or {@code null} - what {@link #lastUsedKey()} is compared to. */
    public static String key(ItemStack stack) {
        return keyOf(stack);
    }

    private void start(String key, int seconds) {
        long duration = seconds * 1000L;
        timers.put(key, new Entry(System.currentTimeMillis() + duration, duration));
    }

    // ------------------------------------------------------------------
    // Reading timers
    // ------------------------------------------------------------------

    /** Remaining fraction of the stack's cooldown (1.0 = just started, 0 = none). */
    public float percent(ItemStack stack) {
        Entry entry = entryOf(stack);
        if (entry == null || entry.durationMillis() <= 0) {
            return 0.0F;
        }
        long remaining = entry.endMillis() - System.currentTimeMillis();
        return remaining <= 0 ? 0.0F : Math.min(1.0F, remaining / (float) entry.durationMillis());
    }

    /** Remaining cooldown of the stack in seconds, or {@code -1} when none. */
    public double remainingSeconds(ItemStack stack) {
        Entry entry = entryOf(stack);
        if (entry == null) {
            return -1;
        }
        long remaining = entry.endMillis() - System.currentTimeMillis();
        return remaining > 0 ? remaining / 1000.0 : -1;
    }

    private Entry entryOf(ItemStack stack) {
        String key = keyOf(stack);
        if (key == null) {
            return null;
        }
        Entry entry = timers.get(key);
        if (entry != null && entry.endMillis() <= System.currentTimeMillis()) {
            timers.remove(key);
            return null;
        }
        return entry;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String keyOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String id = SkyblockItem.id(stack);
        if (id != null) {
            return id.toUpperCase(Locale.ROOT);
        }
        String name = SkyblockItem.normalizeName(stack.getHoverName().getString());
        return name.isEmpty() ? null : name;
    }

    /** The "Cooldown: 30s" lore line, or {@code -1}. */
    private static int loreCooldownSeconds(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return -1;
        }
        for (Component line : lore.lines()) {
            Matcher m = LORE_COOLDOWN.matcher(line.getString().replaceAll(SECTION_SIGN + ".", ""));
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return -1;
    }
}
