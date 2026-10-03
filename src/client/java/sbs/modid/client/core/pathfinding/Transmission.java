/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.etherwarp.EtherWarp;

/**
 * The teleports the player can currently make – the second half of "what can the player do" that the
 * pathfinder routes within (the first being {@link PlayerMobility}).
 *
 * <p>SkyBlock's two Aspect weapons carry moves no amount of walking can imitate:
 * <ul>
 *   <li><b>Instant Transmission</b> (every Aspect of the End / of the Void) – a short dash along the
 *       look direction, eight blocks plus one per Tuned Transmission level.</li>
 *   <li><b>Ether Transmission</b> (only with an Ethermerge Prism) – lands on any block in line of
 *       sight, fifty-seven blocks plus Tuned Transmission.</li>
 * </ul>
 *
 * <p><b>Inventory, not hand.</b> Both are resolved from the whole inventory rather than the held
 * item: a route is a plan for the next minute, and swapping to the Aspect is part of following it.
 * Requiring it to already be in hand would make the route flicker between two completely different
 * shapes every time the player switched weapons.
 *
 * <p><b>Cached briefly.</b> Reading the SkyBlock id of a stack copies its NBT, so scanning all
 * forty-one slots every tick would allocate for nothing – the answer only changes when the player
 * moves an item. Half a second is well below noticing, and {@link #refresh()} makes a settings change
 * take effect at once.
 */
public record Transmission(int instantRange, int etherRange) {

    /** No Aspect in the inventory, or the feature switched off. */
    public static final Transmission NONE = new Transmission(0, 0);

    private static final long CACHE_MS = 500L;

    private static Transmission cached = NONE;
    private static long cachedAt;

    /** Whether any teleport is available at all – the gate on every teleport move in the search. */
    public boolean any() {
        return instantRange > 0 || etherRange > 0;
    }

    /** The furthest a single teleport can carry the player, for the search's vertical reach. */
    public int longestRange() {
        return Math.max(instantRange, etherRange);
    }

    /** Drops the cache, so the next {@link #of} re-reads the inventory and the config. */
    public static void refresh() {
        cachedAt = 0;
    }

    /** What the player can teleport with right now, honouring the module toggle. */
    public static Transmission of(Player player) {
        long now = System.currentTimeMillis();
        if (cachedAt != 0 && now - cachedAt < CACHE_MS) {
            return cached;
        }
        cachedAt = now;
        cached = resolve(player);
        return cached;
    }

    /** Scans the inventory, taking the best of each range in case more than one Aspect is carried. */
    private static Transmission resolve(Player player) {
        if (player == null || !ConfigManager.getInstance().get().pathfinding.useTransmission) {
            return NONE;
        }
        Inventory inventory = player.getInventory();
        int instant = 0;
        int ether = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !EtherWarp.isTransmissionItem(stack)) {
                continue;
            }
            instant = Math.max(instant, EtherWarp.instantRange(stack));
            ether = Math.max(ether, EtherWarp.etherRange(stack));
        }
        return instant == 0 && ether == 0 ? NONE : new Transmission(instant, ether);
    }
}
