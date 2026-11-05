/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.render;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.item.StackCache;
import sbs.modid.client.skills.hunting.logic.PeltTracker;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Trapper Crest's collected-pelt count, drawn over the item in the hotbar.
 *
 * <p>The crest tracks progress toward its own upgrade and states it in its tooltip
 * ({@code Collected: 137}). That number is Hypixel's, so it is <b>read</b> rather than counted: a
 * tally this mod kept would drift the moment a pelt was earned on another client, and the whole
 * value of the readout is trusting it. Nothing is parsed unless a crest is actually in the hotbar.
 *
 * <p>It is drawn where a stack count goes - bottom-right of the slot, with the same shadowed text -
 * because that corner is the one place a number over an item already means "how many", so it reads
 * without a legend. Vanilla draws no count on a single item, so there is nothing to collide with.
 */
public final class PeltCrestOverlay {

    /** Hotbar slot pitch and icon box, matching the vanilla hotbar layout. */
    private static final int SLOT_PITCH = 20;
    private static final int ITEM_SIZE = 16;

    /** The tooltip line the count comes from. */
    private static final Pattern COLLECTED = Pattern.compile("(?i)collected\\s*:\\s*([\\d,.]+)");

    private static final int COUNT_COLOR = 0xFFFF9A2E;

    /** The count as drawn, built once per crest stack rather than twice per frame. */
    private record Label(String text, Component component) {
    }

    /**
     * Per hotbar stack: its label, or {@code null} for "not a crest" / "no count line". Inputs are
     * everything {@link #label} reads - the lore, plus the id and names the crest check uses.
     */
    private static final StackCache<Label> LABELS = new StackCache<>(64, PeltCrestOverlay::label,
            DataComponents.LORE, DataComponents.CUSTOM_DATA, DataComponents.CUSTOM_NAME,
            DataComponents.ITEM_NAME);

    private PeltCrestOverlay() {
    }

    /** Called from the HUD render hook once per frame, on the render thread. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().pelt;
        Minecraft minecraft = Minecraft.getInstance();
        if (!cfg.enabled || !cfg.crestCounter || minecraft.player == null
                || !PeltTracker.onFarmingIslands()) {
            return;
        }
        Inventory inventory = minecraft.player.getInventory();
        Font font = minecraft.font;
        int leftEdge = g.guiWidth() / 2 - 91;
        int y = g.guiHeight() - ITEM_SIZE - 3;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Label label = LABELS.get(stack);
            if (label == null) {
                continue;
            }
            int x = leftEdge + 3 + slot * SLOT_PITCH;
            // Bottom-right of the icon, like a stack count, nudged in so it never clips the slot.
            int textX = x + ITEM_SIZE - font.width(label.text()) + 1;
            int textY = y + ITEM_SIZE - font.lineHeight + 2;
            g.text(font, label.component(), textX + 1, textY + 1, 0xFF000000);
            g.text(font, label.component(), textX, textY, COUNT_COLOR);
        }
    }

    private static Label label(ItemStack stack) {
        if (!isTrapperCrest(stack)) {
            return null;
        }
        String collected = collectedCount(stack);
        return collected == null ? null : new Label(collected, Component.literal(collected));
    }

    /** Whether the stack is a Trapper Crest - by SkyBlock id, with the display name as fallback. */
    private static boolean isTrapperCrest(ItemStack stack) {
        String id = SkyblockItem.id(stack);
        if (id != null && id.toUpperCase(Locale.ROOT).contains("TRAPPER_CREST")) {
            return true;
        }
        return stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains("trapper crest");
    }

    /**
     * The crest's {@code Collected:} number as it is written, thousands separators and all - the
     * tooltip's own formatting is what the player recognises. {@code null} when the crest carries no
     * such line, in which case nothing is drawn rather than a zero that would look like real data.
     */
    private static String collectedCount(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return null;
        }
        for (Component line : lore.lines()) {
            String text = ChatFormatting.stripFormatting(line.getString());
            if (text == null) {
                continue;
            }
            Matcher m = COLLECTED.matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }
}
