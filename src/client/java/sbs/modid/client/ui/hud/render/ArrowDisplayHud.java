/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "Show Remaining Arrows" chip: while a bow is held, Hypixel turns hotbar slot 9 into an arrow
 * display whose <b>name is the loaded arrow type</b> and whose lore carries {@code "Arrows Remaining: N"}.
 * This draws both beside the hotbar – the type (in Hypixel's own colours) over the count – so the
 * quiver state is readable without opening anything.
 *
 * <p>The display item itself is the trigger: it exists only while a bow is in hand, so no separate
 * "is the player holding a bow" test is needed (and none can go stale). The whole hotbar is scanned
 * for it rather than only index 8, so a slot change on Hypixel's side does not silently blank the
 * chip. Its own {@link HudElement#ARROW_DISPLAY}, hence movable / scalable / hideable like every
 * other SBS HUD element.
 */
public final class ArrowDisplayHud {

    /**
     * Hypixel's lore line. Tolerates a missing colon and thousands separators (same shape as the
     * fishing "Baits Remaining" parse), so a reworded "Arrows Remaining 1,234" still reads.
     */
    private static final Pattern ARROWS_REMAINING =
            Pattern.compile("Arrows?\\s+Remaining:?\\s*([\\d,.]+)");

    private static final int PAD = 4;
    private static final int ICON = 16;
    /** Gap between the icon and the text column. */
    private static final int GAP = 4;

    private ArrowDisplayHud() {
    }

    /** One reading of Hypixel's arrow display: the item to draw, its arrow type and the count. */
    private record ArrowInfo(ItemStack stack, Component type, int remaining) {
    }

    /** Called every HUD frame from the SBS extras pass; self-gating on the toggle and the display. */
    public static void render(GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().hypixelGui.showRemainingArrows
                || HudLayout.isHidden(HudElement.ARROW_DISPLAY)) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ArrowInfo info = read(player);
        if (info == null) {
            return;   // no bow in hand (or no display) - nothing to show
        }

        Font font = Minecraft.getInstance().font;
        String count = sbs.modid.client.core.util.NumberDisplay.format(info.remaining()) + " left";
        int textWidth = Math.max(font.width(info.type()), font.width(count));
        int width = PAD + ICON + GAP + textWidth + PAD;
        int height = PAD + Math.max(ICON, font.lineHeight * 2 + 1) + PAD;

        HudElement.Bounds bounds = HudElement.ARROW_DISPLAY.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(bounds.x());
        int y = Math.round(bounds.y());

        HudLayout.measure(HudElement.ARROW_DISPLAY, x, y, width, height);
        HudLayout.begin(g, HudElement.ARROW_DISPLAY);
        SciFiRender.roundedRect(g, x - 1, y - 1, width + 2, height + 2,
                SBSTheme.HUD_CORNER + 1, SBSTheme.HUD_CARD_BORDER);
        SciFiRender.roundedRect(g, x, y, width, height, SBSTheme.HUD_CORNER, SBSTheme.HUD_CARD_BG);

        g.item(info.stack(), x + PAD, y + (height - ICON) / 2);
        int textX = x + PAD + ICON + GAP;
        int textY = y + (height - (font.lineHeight * 2 + 1)) / 2;
        // The type keeps Hypixel's own formatting (rarity colour), the count uses the SBS text colour.
        g.text(font, info.type(), textX, textY, SBSTheme.TEXT, false);
        g.text(font, Component.literal(count), textX, textY + font.lineHeight + 1,
                SBSTheme.TEXT_MUTED, false);
        HudLayout.end(g);
    }

    /**
     * Hypixel's arrow display from the hotbar, or {@code null} while none is up. Identified purely by
     * its "Arrows Remaining" lore line – the dungeon map and the fishing bait display share slot 9 but
     * never carry it, so this cannot pick up the wrong item.
     */
    private static ArrowInfo read(Player player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Integer remaining = parseRemaining(stack);
            if (remaining != null) {
                return new ArrowInfo(stack, stack.getHoverName(), remaining);
            }
        }
        return null;
    }

    /** The "Arrows Remaining: N" number on an item's lore, or {@code null} when the line is absent. */
    private static Integer parseRemaining(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return null;
        }
        for (var line : lore.lines()) {
            Matcher matcher = ARROWS_REMAINING.matcher(strip(line.getString()));
            if (matcher.find()) {
                return parseCount(matcher.group(1));
            }
        }
        return null;
    }

    /** Allocation-light §-code strip (this runs per lore line per frame). */
    private static String strip(String text) {
        if (text.indexOf((char) 0x00A7) < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == (char) 0x00A7) {
                i++;    // skip the code character too
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** "1,234" / "1.234" → 1234; {@code null} when the group is not a number after all. */
    private static Integer parseCount(String raw) {
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            }
        }
        if (digits.isEmpty() || digits.length() > 9) {
            return null;
        }
        return Integer.parseInt(digits.toString());
    }
}
