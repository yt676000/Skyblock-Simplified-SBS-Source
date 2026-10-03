/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The profile viewer's shared drawing vocabulary: headings, stat lines, XP rows, item
 * cells and tooltips. Every page draws through these, so a spacing or color change lands everywhere
 * at once and no page invents its own look.
 *
 * <p>The layout helpers all return the y below what they drew, so a page stacks calls instead of
 * hand-computing offsets – which is what kept text from overlapping as pages grew.
 */
public final class PvDraw {

    private PvDraw() {
    }

    /** One inventory cell / item icon, and the grid pitch. */
    public static final int CELL = 18;
    public static final int GRID_COLS = 9;
    /** The magenta skill/slayer progress bar. */
    public static final int SKILL_BAR = 0xFFE24BC4;

    public static final Map<String, String> TIER_COLOR = Map.of(
            "COMMON", "§f", "UNCOMMON", "§a", "RARE", "§9",
            "EPIC", "§5", "LEGENDARY", "§6", "MYTHIC", "§d");

    /** SkyBlock rarity order, weakest first – shared by the pet and accessory pages. */
    public static final List<String> RARITIES = List.of(
            "COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "SPECIAL", "VERY_SPECIAL");

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    /** A plain stat line. Returns the y below it. */
    public static int line(GuiGraphicsExtractor g, Font font, int x, int y, String text) {
        g.text(font, Component.literal(text), x, y, SBSTheme.TEXT);
        return y + font.lineHeight + 2;
    }

    /** A section heading with its accent underline. Returns the y below it. */
    public static int heading(GuiGraphicsExtractor g, Font font, int x, int y, int w, String text) {
        g.text(font, Component.literal(text), x, y, SBSTheme.ACCENT);
        int lineY = y + font.lineHeight + 1;
        g.fill(x, lineY, x + w - 4, lineY + 1, SBSTheme.ACCENT_SOFT);
        return lineY + 4;
    }

    /**
     * A label/value line where the value is right-aligned to {@code x + w}. The label is trimmed
     * against the value's real width, so the two can never overlap however long either gets.
     */
    public static int keyValue(GuiGraphicsExtractor g, Font font, int x, int y, int w,
                              String label, String value) {
        int valueW = font.width(value);
        g.text(font, Component.literal(value), x + w - valueW, y, SBSTheme.TEXT);
        int space = w - valueW - 4;
        if (space > 8) {
            g.text(font, Component.literal(trim(font, label, space)), x, y, SBSTheme.TEXT_MUTED);
        }
        return y + font.lineHeight + 2;
    }

    /** Trims a (possibly §-colored) string to {@code maxWidth} pixels, ellipsizing if it ran over. */
    public static String trim(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
    }

    // ------------------------------------------------------------------
    // Progress rows
    // ------------------------------------------------------------------

    /**
     * A skill/slayer row: item icon, name, right-aligned level (chroma "MAX" when maxed) and a
     * magenta XP bar beneath. {@code entry} is the backend's {@code [level, promille]}.
     */
    public static int iconRow(GuiGraphicsExtractor g, Font font, int x, int y, int w,
                              Item icon, String label, JsonArray entry) {
        int level = entry.get(0).getAsInt();
        int promille = entry.size() > 1 ? entry.get(1).getAsInt() : 1000;
        boolean maxed = promille >= 1000;
        if (icon != null) {
            g.item(new ItemStack(icon), x, y);
        }
        int textX = x + 18;
        String lvlText = maxed ? "MAX" : String.valueOf(level);
        int lvlW = font.width(lvlText);
        g.text(font, Component.literal(lvlText), x + w - lvlW, y + 1, maxed ? chroma(x) : SBSTheme.TEXT);
        int nameSpace = (x + w - lvlW - 4) - textX;
        if (nameSpace > 8) {
            g.text(font, Component.literal(trim(font, label, nameSpace)), textX, y + 1, SBSTheme.TEXT);
        }
        int barY = y + font.lineHeight + 2;
        int barW = x + w - textX;
        g.fill(textX, barY, textX + barW, barY + 2, SBSTheme.CARD_BG_DISABLED);
        if (maxed) {
            maxedBar(g, textX, barY, barW);
        } else if (promille > 0) {
            g.fill(textX, barY, textX + (int) (barW * (promille / 1000.0)), barY + 2, SKILL_BAR);
        }
        return Math.max(y + CELL, barY + 4);
    }

    /**
     * A maxed progress bar: the per-pixel chroma sweep that makes it shimmer, or a flat
     * accent fill when the Max Skills chroma is switched off (which also skips the loop).
     */
    public static void maxedBar(GuiGraphicsExtractor g, int x, int y, int w) {
        if (!chromaSkills()) {
            g.fill(x, y, x + w, y + 2, SBSTheme.ACCENT);
            return;
        }
        for (int px = 0; px < w; px += 2) {
            g.fill(x + px, y, x + Math.min(px + 2, w), y + 2, chroma(x + px));
        }
    }

    /** A bare progress bar with no row around it, for pages that label it themselves. */
    public static void bar(GuiGraphicsExtractor g, int x, int y, int w, double fraction, int color) {
        g.fill(x, y, x + w, y + 2, SBSTheme.CARD_BG_DISABLED);
        int filled = (int) (w * Math.max(0, Math.min(1, fraction)));
        if (filled > 0) {
            g.fill(x, y, x + filled, y + 2, color);
        }
    }

    /** Slim scrollbar: full-height track with a thumb sized and placed by the visible window. */
    public static void scrollbar(GuiGraphicsExtractor g, int x, int y, int h,
                                 int total, int visible, int scroll) {
        g.fill(x, y, x + 3, y + h, SBSTheme.CARD_BG_DISABLED);
        int thumbH = Math.max(8, h * visible / Math.max(1, total));
        int thumbY = y + (int) ((long) (h - thumbH) * scroll / Math.max(1, total - visible));
        g.fill(x, thumbY, x + 3, thumbY + thumbH, SBSTheme.ACCENT);
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    public static void cell(GuiGraphicsExtractor g, int x, int y) {
        SciFiRender.roundedRectWithBorder(g, x, y, CELL - 1, CELL - 1, 2,
                SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
    }

    /** The renderable icon for a backend slot object, re-dyed to its leather color when present. */
    public static ItemStack iconFor(JsonObject slot) {
        int count = slot.has("count") ? slot.get("count").getAsInt() : 1;
        ItemStack stack = SkyBlockItemIcons.getInstance().icon(str(slot, "id"), null, count);
        if (slot.has("color") && !slot.get("color").isJsonNull()) {
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(slot.get("color").getAsInt()));
        }
        return stack;
    }

    /** One inventory slot: cell, icon, stack size and the real in-game tooltip on hover. */
    public static void slot(GuiGraphicsExtractor g, Font font, JsonElement slotEl,
                            int x, int y, int mouseX, int mouseY) {
        cell(g, x, y);
        if (slotEl == null || !slotEl.isJsonObject()) {
            return;
        }
        JsonObject slot = slotEl.getAsJsonObject();
        ItemStack stack = iconFor(slot);
        g.item(stack, x + 1, y + 1);
        int count = slot.has("count") ? slot.get("count").getAsInt() : 1;
        if (count > 1) {
            g.itemDecorations(font, stack, x + 1, y + 1);
        }
        if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
            tooltip(g, font, slotTooltip(slot, null), mouseX, mouseY);
        }
    }

    /** The full in-game tooltip of a slot: colored name + the item's real lore lines. */
    public static List<Component> slotTooltip(JsonObject slot, String sourceLine) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(str(slot, "name")));
        if (slot.has("lore") && slot.get("lore").isJsonArray()) {
            for (JsonElement line : slot.getAsJsonArray("lore")) {
                tip.add(Component.literal(line.getAsString()));
            }
        } else {
            tip.add(Component.literal("§8" + str(slot, "id")));
        }
        if (sourceLine != null) {
            tip.add(Component.literal(sourceLine));
        }
        return tip;
    }

    public static void tooltip(GuiGraphicsExtractor g, Font font, List<Component> lines,
                               int mouseX, int mouseY) {
        g.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY,
                SBSTheme.tooltipStyle());
    }

    /**
     * A framed stat card: muted title, big value, optional footnote. The headline unit every
     * category page is built from, so a "Kuudra runs" card and a "Chocolate" card look identical.
     */
    public static void card(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                            String title, String value, String sub) {
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        int cx = x + w / 2;
        g.centeredText(font, Component.literal(trim(font, "§7" + title, w - 6)), cx, y + 4,
                SBSTheme.TEXT_MUTED);
        g.centeredText(font, Component.literal(trim(font, value, w - 6)), cx,
                y + 4 + font.lineHeight + 3, SBSTheme.ACCENT_BRIGHT);
        if (sub != null) {
            g.centeredText(font, Component.literal(trim(font, "§8" + sub, w - 6)), cx,
                    y + h - font.lineHeight - 3, SBSTheme.TEXT_MUTED);
        }
    }

    /**
     * A row of equal cards across {@code w}. Each entry is {title, value} or {title, value, sub}.
     * Returns the y below the row.
     */
    public static int cardRow(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                              String[][] cards) {
        if (cards.length == 0) {
            return y;
        }
        int gap = 6;
        int cardW = (w - gap * (cards.length - 1)) / cards.length;
        for (int i = 0; i < cards.length; i++) {
            card(g, font, x + i * (cardW + gap), y, cardW, h, cards[i][0], cards[i][1],
                    cards[i].length > 2 ? cards[i][2] : null);
        }
        return y + h;
    }

    /** Centered "nothing here" text, so an empty page still explains itself. */
    public static void empty(GuiGraphicsExtractor g, PvContext ctx, String text) {
        g.centeredText(ctx.font, Component.literal("§7" + text),
                ctx.x + ctx.width / 2, ctx.y + 12, SBSTheme.TEXT_MUTED);
    }

    // ------------------------------------------------------------------
    // Json / formatting
    // ------------------------------------------------------------------

    public static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    public static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    public static JsonArray arr(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    public static long num(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0;
    }

    public static boolean has(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull();
    }

    public static String pretty(String key) {
        String s = key.replace('_', ' ').toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        boolean up = true;
        for (char c : s.toCharArray()) {
            sb.append(up ? Character.toUpperCase(c) : c);
            up = c == ' ';
        }
        return sb.toString();
    }

    /** Short human number: 1.2K / 3.4M / 5.6B - or the full figure, per "Shorten Numbers". */
    public static String fmt(long value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }

    /** Milliseconds as a dungeon time: {@code 6:31.4} / {@code 1:02:11.0}. */
    public static String time(long ms) {
        long tenths = (ms % 1000) / 100;
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d.%d", h, m, s, tenths)
                : String.format(Locale.ROOT, "%d:%02d.%d", m, s, tenths);
    }

    // ------------------------------------------------------------------
    // Chroma (shared with the Item Overlay's Max Skills setting)
    // ------------------------------------------------------------------

    /** Whether maxed skills / slayers / classes shimmer, per the Item Overlay chroma setting. */
    public static boolean chromaSkills() {
        return sbs.modid.client.core.config.ConfigManager.getInstance().get().itemOverlay.chromaMaxedSkills;
    }

    /**
     * Animated chroma color: hue flows over time, offset by {@code x} so a run of pixels sweeps.
     * With the effect switched off this collapses to the plain accent, so a maxed row still reads
     * as maxed but sits still.
     */
    public static int chroma(int x) {
        if (!chromaSkills()) {
            return SBSTheme.ACCENT;
        }
        return sbs.modid.client.helper.visual.render.Chroma.color(x * 0.012,
                sbs.modid.client.helper.visual.render.Chroma.speed());
    }

    /** A pet's icon: the fetched skull texture, falling back to the repo icon and finally a bone. */
    public static ItemStack petIcon(String type) {
        return sbs.modid.client.core.player.PetIconCache.getInstance().iconOrFallback(type);
    }
}
