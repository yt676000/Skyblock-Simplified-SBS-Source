/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.MiningHelpersSettings;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.skills.mining.logic.HotmMenuParser;
import sbs.modid.client.skills.mining.logic.HotmTreeReader;
import sbs.modid.client.skills.mining.logic.HotmTreeStore;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.render.SlotDecorations;
import sbs.modid.client.ui.render.SlotDecorator;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The HotM Upgrade Reminder's menu highlight: a green outline on every perk the Heart of the
 * Mountain menu itself offers to upgrade or unlock right now, and a gold corner mark on watched
 * perks. Shape and colour both, so neither state relies on colour alone.
 *
 * <p><b>Display only.</b> Nothing is clicked, hovered or moved; this draws over slots the server
 * already sent.
 *
 * <p><b>Cost.</b> Gated on the menu title before any slot is walked. A slot's reading is cached per
 * {@link ItemStack} instance - the server replaces the stack whenever the slot changes, so the cache
 * cannot go stale - and only the watched lookup runs per frame.
 */
public final class HotmMenuDecorator implements SlotDecorator {

    private static final int AFFORDABLE = 0xFF55FF55;
    private static final int WATCHED = 0xFFFFC12E;
    private static final int WATCHED_EDGE = 0xFF3A2A00;
    /** Well above one page of slots; the map is dropped with the screen anyway. */
    private static final int CACHE_LIMIT = 256;

    /** What one slot's lore said: whether the menu offers its next step, and which perk it is. */
    private record Mark(boolean offered, String perkId) {
    }

    private final Map<ItemStack, Mark> marks = new IdentityHashMap<>();
    private Object markedScreen;
    /** The cache reading the marks were made against; a new page read can turn a pane into a perk. */
    private long markedReadAt;

    @Override
    public String id() {
        return "hotm_reminder";
    }

    @Override
    public int order() {
        return 620;
    }

    @Override
    public RenderTier tier(MenuFrame frame) {
        return RenderTier.when(frame == null || HotmTreeReader.isHotmTitle(frame.title()));
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        MiningHelpersSettings cfg = ConfigManager.getInstance().get().miningHelpers;
        if (!cfg.enabled || !cfg.hotmReminderHighlight
                || !HotmTreeReader.isHotmTitle(MenuFrame.of(screen).title())) {
            return;
        }
        HotmTreeStore store = HotmTreeStore.getInstance();
        if (markedScreen != screen || markedReadAt != store.readAt() || marks.size() > CACHE_LIMIT) {
            marks.clear();
            markedScreen = screen;
            markedReadAt = store.readAt();
        }
        var watched = store.watched();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || !slot.hasItem()) {
                continue;
            }
            Mark mark = marks.computeIfAbsent(slot.getItem(), stack -> mark(stack, store));
            if (mark.perkId() == null) {
                continue;
            }
            if (mark.offered()) {
                SlotDecorations.box(g, slot, 0, AFFORDABLE);
            }
            if (watched.contains(mark.perkId())) {
                g.fill(slot.x + 11, slot.y, slot.x + 16, slot.y + 5, WATCHED_EDGE);
                g.fill(slot.x + 12, slot.y, slot.x + 16, slot.y + 4, WATCHED);
            }
        }
    }

    private static Mark mark(ItemStack stack, HotmTreeStore store) {
        String name = StyledText.strip(stack.getHoverName().getString()).trim();
        String id = HotmTreeReader.perkIdFor(name);
        HotmTreeStore.NodeState node = store.nodes().get(id);
        if (node == null) {
            return new Mark(false, null);   // a tier label, a button, a pane: not a perk the reader filed
        }
        List<String> lore = lore(stack);
        boolean offered = HotmMenuParser.offersPowderUpgrade(lore)
                // The unlock reading is ESTIMATED, so it also needs the perk's tier to be unlocked.
                || (node.level == 0 && node.tier > 0 && node.tier <= store.unlockedTier()
                        && HotmMenuParser.offersTokenUnlock(lore));
        return new Mark(offered, id);
    }

    private static List<String> lore(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) {
            lines.add(StyledText.strip(line.getString()));
        }
        return lines;
    }
}
