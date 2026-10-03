/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.ChatFormatting;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Magic Stick Thingy: the selection tool, a golden pickaxe carrying our custom-data marker.
 *
 * <p><b>Recognised by the marker, never by the name</b> - an anvil rename keeps it working, and a
 * golden pickaxe someone happened to call "Magic Stick Thingy" is not mistaken for it.
 *
 * <p>It exists only in singleplayer: {@code //stick} adds it on the integrated server's thread. On a
 * server no item can be given, and the same selection is made with the corner keys instead.
 */
public final class MagicStick {

    /** Custom-data key; its value names which build tool the item is. */
    public static final String MARKER_KEY = "sbs_build_tool";
    public static final String MARKER_VALUE = "magic_stick";
    public static final String NAME = "Magic Stick Thingy";

    private MagicStick() {
    }

    /** True for any stack carrying the Magic Stick Thingy marker. */
    public static boolean is(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && MARKER_VALUE.equals(data.copyTag().getStringOr(MARKER_KEY, ""));
    }

    /** A fresh Magic Stick Thingy. */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.GOLDEN_PICKAXE);
        CompoundTag tag = new CompoundTag();
        tag.putString(MARKER_KEY, MARKER_VALUE);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(NAME)
                .withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GOLD)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                lore("Left-click a block: corner 1"),
                lore("Right-click a block: corner 2"),
                lore("Build Tools - //help"))));
        return stack;
    }

    private static Component lore(String text) {
        return Component.literal(text).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY));
    }

    /**
     * Adds a stick to the player's inventory on the integrated server's thread, then reports back on
     * the client thread. Does nothing without an integrated server.
     */
    public static void give(UUID player, Consumer<Boolean> done) {
        IntegratedServer server = BuildGate.server();
        if (server == null) {
            done.accept(false);
            return;
        }
        server.execute(() -> {
            // Re-checked on the server thread: this is the one place an item is created, and only an
            // integrated server may run it.
            ServerPlayer target = server.getPlayerList().getPlayer(player);
            boolean added = target != null && target.getInventory().add(create());
            net.minecraft.client.Minecraft.getInstance().execute(() -> done.accept(added));
        });
    }
}
