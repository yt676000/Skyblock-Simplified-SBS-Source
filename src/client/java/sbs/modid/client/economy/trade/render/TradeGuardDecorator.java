/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.trade.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.economy.trade.logic.TradeDiff;
import sbs.modid.client.economy.trade.logic.TradeGuard;
import sbs.modid.client.economy.trade.logic.TradeGuardRuntime;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.RenderTier;
import sbs.modid.client.ui.render.SlotDecorations;
import sbs.modid.client.ui.render.SlotDecorator;

/**
 * Trade Guard's drawing: a red frame on every flagged slot of the partner's side, and a banner above
 * the menu - "You give ~X | You get ~Y (-Z%)" - plus the accept-lock countdown. Draws only; every
 * decision was made in {@link TradeGuard} when the menu last changed.
 */
public final class TradeGuardDecorator implements SlotDecorator {

    private static final int RED_FILL = 0x55FF3030;
    private static final int RED_FRAME = 0xFFFF3030;
    private static final int ORANGE_FILL = 0x44FF9A30;
    private static final int ORANGE_FRAME = 0xFFFF9A30;

    @Override
    public String id() {
        return "trade_guard";
    }

    @Override
    public int order() {
        return 890;   // over the feature boxes; only the slot-lock padlock (900) draws later
    }

    @Override
    public RenderTier tier(MenuFrame frame) {
        return RenderTier.when(frame == null || TradeGuardRuntime.getInstance().active(frame.screen()));
    }

    @Override
    public void decorate(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        TradeGuardRuntime rt = TradeGuardRuntime.getInstance();
        if (!rt.active(screen)) {
            return;
        }
        TradeGuard guard = rt.guard();
        var slots = screen.getMenu().slots;
        for (TradeGuard.Flag f : guard.flags().values()) {
            if (f.slot() >= slots.size()) {
                continue;
            }
            Slot slot = slots.get(f.slot());
            boolean red = f.kind() != TradeGuard.Kind.CHANGED_AFTER_CONFIRM;
            SlotDecorations.box(g, slot, red ? RED_FILL : ORANGE_FILL, red ? RED_FRAME : ORANGE_FRAME);
        }
        banner(screen, g, guard);
    }

    private static void banner(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, TradeGuard guard) {
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        Font font = Minecraft.getInstance().font;
        TradeGuard.Totals t = guard.totals();
        Double pct = t.pct();
        String give = (t.giveUnknown() ? "≥" : "~") + TradeDiff.compact(t.give());
        String get = (t.getUnknown() ? "≥" : "~") + TradeDiff.compact(t.get());
        String colour = pct == null ? "§f" : pct * 100 < -TradeGuardRuntime.settings().thresholdPct() ? "§c" : "§a";
        String line = "§7You give §f" + give + " §8| §7You get " + colour + get
                + (pct == null ? "" : String.format(java.util.Locale.ROOT, " (%+.0f%%)", pct * 100));
        if (t.giveUnknown() || t.getUnknown()) {
            line += " §6⚠ Value unknown";
        }
        long left = guard.lockRemaining(System.currentTimeMillis());
        String lock = left > 0 ? String.format(java.util.Locale.ROOT,
                "§cAccept locked %.1fs §7(Shift-click overrides)", left / 1000.0) : null;
        if (!guard.flags().isEmpty() && lock == null) {
            lock = "§c⚠ " + guard.flags().size() + " suspicious item(s) - hover the red slots";
        }
        int lines = lock == null ? 1 : 2;
        // Coordinates are relative to the menu's corner. Above the menu, clamped to the screen top:
        // at 1280x720 / scale 4 a five-row menu starts above the screen edge.
        int y = Math.max(-11 * lines - 2, -acc.skyblockSimplified$topPos() + 2);
        int cx = acc.skyblockSimplified$imageWidth() / 2;
        int lineW = font.width(Component.literal(line));
        int lockW = lock == null ? 0 : font.width(Component.literal(lock));
        int w = Math.max(lineW, lockW);
        g.fill(cx - w / 2 - 3, y - 2, cx + w / 2 + 3, y + 11 * lines, 0xCC000000);
        g.text(font, Component.literal(line), cx - lineW / 2, y, 0xFFFFFFFF);
        if (lock != null) {
            g.text(font, Component.literal(lock), cx - lockW / 2, y + 11, 0xFFFFFFFF);
        }
    }
}
