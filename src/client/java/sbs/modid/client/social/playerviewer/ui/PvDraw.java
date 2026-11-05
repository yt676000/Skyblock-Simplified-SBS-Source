/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.ui;

import java.util.function.IntConsumer;
import sbs.modid.client.ui.component.SciFiScrollbar;

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
        return iconRow(g, font, x, y, w, icon == null ? null : new ItemStack(icon), label, entry);
    }

    /** {@link #iconRow} with a ready stack, for icons that are not a plain item (a head). */
    public static int iconRow(GuiGraphicsExtractor g, Font font, int x, int y, int w,
                              ItemStack icon, String label, JsonArray entry) {
        int level = entry.get(0).getAsInt();
        int promille = entry.size() > 1 ? entry.get(1).getAsInt() : 1000;
        boolean maxed = promille >= 1000;
        if (icon != null) {
            g.item(icon, x, y);
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

    /**
     * The one scrollbar of the page on screen: a real, draggable {@link SciFiScrollbar}. Pages keep
     * calling {@link #scrollbar} where they always drew their bar; the shell offers it clicks, drags
     * and releases ahead of the page ({@link #scrollbarClick}...), so every page's bar can be grabbed
     * without each page routing input itself. Only one page renders per frame, so one bar suffices.
     */
    private static final SciFiScrollbar BAR = new SciFiScrollbar();
    private static boolean barShown;
    private static int barMouseX;
    private static int barMouseY;

    /** Called by the shell before the page renders: no bar until a page draws one this frame. */
    public static void beginFrame(int mouseX, int mouseY) {
        barShown = false;
        barMouseX = mouseX;
        barMouseY = mouseY;
    }

    /** The page's scrollbar over the track {@code x, y, h}, for {@code total} rows of which {@code visible} fit. */
    public static void scrollbar(GuiGraphicsExtractor g, int x, int y, int h,
                                 int total, int visible, int scroll) {
        // The old bar was 3px wide at x; the real one is a pixel wider, so it moves left by one.
        BAR.set(x - 1, y, h, total, visible);
        barShown = true;
        BAR.render(g, scroll, barMouseX, barMouseY);
    }

    public static boolean scrollbarClick(double mouseX, double mouseY, int scroll, IntConsumer apply) {
        return barShown && BAR.handleClick(mouseX, mouseY, scroll, apply);
    }

    public static boolean scrollbarDrag(double mouseY, IntConsumer apply) {
        return BAR.handleDrag(mouseY, apply);
    }

    public static boolean scrollbarRelease() {
        return BAR.release();
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
        // A skinned piece shows its own head texture, not the item's default one.
        ItemStack stack = slot.has("skull") ? skinnedHead(slot.get("skull").getAsString()).copyWithCount(Math.max(1, count))
                : SkyBlockItemIcons.getInstance().icon(str(slot, "id"), null, count);
        if (slot.has("color") && !slot.get("color").isJsonNull()) {
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(slot.get("color").getAsInt()));
        }
        return stack;
    }

    /** Stacks built by {@link #stack}, for the profile they were built from. */
    private static final java.util.IdentityHashMap<JsonObject, ItemStack> STACKS = new java.util.IdentityHashMap<>();
    private static JsonObject stacksOwner;

    /**
     * A real {@link ItemStack} for a slot: the icon, plus the item's own name, lore and SkyBlock
     * attributes ({@code custom_data}, from the slot's {@code ea}). That is what the rarity tint and
     * the offline appraisal read, so a viewed item is tinted and priced exactly like one in your own
     * storage - enchantments, stars and gems included. Built once per slot object and kept while the
     * same profile view is on screen; a barrier (icon not resolved yet) is never kept, so it is
     * replaced as soon as the icon arrives.
     */
    public static ItemStack stack(JsonObject profile, JsonObject slot) {
        if (profile != stacksOwner) {
            STACKS.clear();
            stacksOwner = profile;
        }
        ItemStack cached = STACKS.get(slot);
        if (cached != null) {
            return cached;
        }
        ItemStack stack = iconFor(slot);
        if (stack.is(net.minecraft.world.item.Items.BARRIER)) {
            return stack;
        }
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(str(slot, "name")));
        if (slot.has("lore") && slot.get("lore").isJsonArray()) {
            List<Component> lines = new ArrayList<>();
            for (JsonElement line : slot.getAsJsonArray("lore")) {
                lines.add(Component.literal(line.getAsString()));
            }
            stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lines));
        }
        if (slot.has("ea") && slot.get("ea").isJsonPrimitive()) {
            try {
                stack.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(
                        net.minecraft.nbt.TagParser.parseCompoundFully(slot.get("ea").getAsString())));
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException | RuntimeException e) {
                // Priced by its id alone; the icon, name and lore are still right.
                sbs.modid.client.social.playerviewer.logic.PvParser.logOnce("inventory",
                        "item attributes unreadable, priced by id only");
            }
        }
        STACKS.put(slot, stack);
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

    /**
     * The neutral "unavailable" state: a framed notice, visibly different from an empty page, for
     * a section whose data failed validation or could not be decoded.
     */
    public static void unavailable(GuiGraphicsExtractor g, PvContext ctx, String text) {
        int h = ctx.font.lineHeight + 12;
        SciFiRender.roundedRectWithBorder(g, ctx.x, ctx.y + 4, ctx.width, h, SBSTheme.CORNER_RADIUS,
                SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        g.centeredText(ctx.font, Component.literal("§7" + trim(ctx.font, text, ctx.width - 8)),
                ctx.x + ctx.width / 2, ctx.y + 10, SBSTheme.TEXT_MUTED);
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

    /**
     * {@code inventories} keys that were renamed, new -> old. The Bait Sack replaced the Fishing Bag
     * (2026-09-21): the server serves it as {@code bait_sack}, while cached and older payloads still
     * carry {@code fishing_bag}, so a page asking for the new key falls back to the old one.
     */
    private static final java.util.Map<String, String> LEGACY_CONTAINER_KEYS =
            java.util.Map.of("bait_sack", "fishing_bag");

    /** The key {@code invs} actually holds for container {@code key}: itself, else its legacy name. */
    public static String containerKey(JsonObject invs, String key) {
        if (invs == null || invs.has(key)) {
            return key;
        }
        String legacy = LEGACY_CONTAINER_KEYS.get(key);
        return legacy != null && invs.has(legacy) ? legacy : key;
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

    /** Heads built from a texture, by texture; bounded, built once each. */
    private static final Map<String, ItemStack> HEADS = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ItemStack> eldest) {
            return size() > 512;
        }
    };

    private static ItemStack skinnedHead(String texture) {
        return HEADS.computeIfAbsent(texture, t -> SkyBlockItemIcons.playerHead(t, "pv-skull:" + t.hashCode()));
    }

    /**
     * A pet's icon with its applied skin: the skin item ({@code PET_SKIN_<skin>}) carries the skinned
     * head; until it resolves, or with no skin, the pet's default head.
     */
    public static ItemStack petIcon(String type, String skin) {
        if (skin != null && !skin.isEmpty()) {
            ItemStack skinned = SkyBlockItemIcons.getInstance().iconShared("PET_SKIN_" + skin, null, 1);
            if (skinned != null && !skinned.isEmpty() && !skinned.is(net.minecraft.world.item.Items.BARRIER)) {
                return skinned;
            }
        }
        return petIcon(type);
    }

    /** The skin's display name ("GOLDEN_DRAGON_ANUBIS" -> "Anubis"), or "" without a skin. */
    public static String petSkinName(String type, String skin) {
        if (skin == null || skin.isEmpty()) {
            return "";
        }
        String rest = type != null && skin.startsWith(type + "_") ? skin.substring(type.length() + 1) : skin;
        return pretty(rest);
    }

    /** A pet's icon: the fetched skull texture, falling back to the repo icon and finally a bone. */
    public static ItemStack petIcon(String type) {
        return sbs.modid.client.core.player.PetIconCache.getInstance().iconOrFallback(type);
    }
}
