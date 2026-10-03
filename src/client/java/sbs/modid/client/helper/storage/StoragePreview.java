/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.economy.recipe.model.ItemRef;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skyblock Menu module: Ender Chest &amp; Backpack hover preview.
 *
 * <p>Contents are cached as <b>live ItemStacks</b> whenever the corresponding menu is open – an
 * Ender Chest page or an opened Backpack. Live stacks carry their real material, skull skin, name
 * and count, which is what finally kills the barrier placeholders (the catalogue-based resolution
 * could never cover every decorated item). The embedded {@code ExtraAttributes.*_data} NBT blob is
 * kept as a best-effort fallback for backpacks that were never opened this session.
 *
 * <p>Hovering the matching item (in the Storage menu etc.) shows the cached contents as an SBS
 * panel top-centre. Menu chrome (filler panes, back/next arrows) is filtered out, and fully empty
 * edge rows are trimmed.
 */
public final class StoragePreview {

    private static final StoragePreview INSTANCE = new StoragePreview();

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);
    private static final Pattern FIRST_NUMBER = Pattern.compile("([0-9]+)");
    private static final int COLUMNS = 9;
    private static final int CELL = 18;

    /** Ender-chest page number -> cached live contents. */
    private final Map<Integer, List<ItemStack>> enderChestPages = new ConcurrentHashMap<>();

    /** Normalised backpack menu title -> cached live contents. */
    private final Map<String, List<ItemStack>> backpacks = new ConcurrentHashMap<>();

    /** One-entry decode cache so hovering doesn't re-resolve every frame. */
    private ItemStack lastHovered;
    private List<ItemStack> lastContents;

    /** Last backpack key a hover-miss diagnostic was logged for (avoids log spam). */
    private String lastMissLogged;

    private StoragePreview() {
    }

    public static StoragePreview getInstance() {
        return INSTANCE;
    }

    /** Whether the hover preview draws – true for both Preview and Full UI mode. */
    private static boolean enabled() {
        return ConfigManager.getInstance().get().skyblockMenu.previewMode.showsPreview();
    }

    // ------------------------------------------------------------------
    // Rendering (called from the overlay render hook, absolute coordinates)
    // ------------------------------------------------------------------

    /** Renders the preview on a fresh stratum – above everything, including the item's tooltip. */
    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        // Full UI mode indexes every storage for the central search. Done here because this hook
        // already runs whenever a container screen is open; it is a no-op in the other modes.
        sbs.modid.client.helper.storage.StorageIndex.getInstance().capture(screen);

        if (!enabled()) {
            return;
        }
        cacheOpenStorage(screen);

        Slot hovered = ((AbstractContainerScreenAccessor) screen).skyblockSimplified$hoveredSlot();
        if (hovered == null) {
            return;
        }
        ItemStack stack = hovered.getItem();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        List<ItemStack> contents = contentsFor(stack);
        if (contents == null || contents.isEmpty()) {
            return;
        }
        g.nextStratum(); // lift above the already-flushed tooltip
        drawPreview(screen, g, stripCodes(stack.getHoverName().getString()).trim(), contents);
    }

    /** Contents for a hovered stack: session caches first, embedded backpack NBT as fallback. */
    private List<ItemStack> contentsFor(ItemStack stack) {
        if (stack == lastHovered) {
            return lastContents;
        }
        String name = stripCodes(stack.getHoverName().getString()).trim();
        String lower = name.toLowerCase(Locale.ROOT);
        List<ItemStack> contents = null;

        if (lower.contains("ender chest")) {
            Matcher m = FIRST_NUMBER.matcher(lower);
            contents = enderChestPages.get(m.find() ? Integer.parseInt(m.group(1)) : 1);
        } else if (lower.contains("backpack")) {
            String key = SkyblockItem.normalizeName(name);
            contents = backpacks.get(key);
            if (contents == null) {
                // Item names / menu titles may differ by suffixes ("(Slot #4)") – match loosely.
                for (Map.Entry<String, List<ItemStack>> entry : backpacks.entrySet()) {
                    if (key.contains(entry.getKey()) || entry.getKey().contains(key)) {
                        contents = entry.getValue();
                        break;
                    }
                }
            }
            if (contents == null) {
                contents = decodeEmbedded(stack); // never-opened backpack: NBT best effort
            }
            if (contents == null && !key.equals(lastMissLogged)) {
                lastMissLogged = key;
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Preview] No contents for backpack '{}'. Cached backpack keys={}",
                        key, backpacks.keySet());
            }
        }

        lastHovered = stack;
        lastContents = contents;
        return contents;
    }

    // ------------------------------------------------------------------
    // Session caches from open menus (live stacks – real icons, skins, counts)
    // ------------------------------------------------------------------

    private void cacheOpenStorage(AbstractContainerScreen<?> screen) {
        String title = stripCodes(screen.getTitle() != null ? screen.getTitle().getString() : "").trim();
        String lower = title.toLowerCase(Locale.ROOT);
        boolean enderChest = lower.contains("ender chest");
        boolean backpack = lower.contains("backpack");
        if (!enderChest && !backpack) {
            return;
        }

        AbstractContainerMenu menu = screen.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36); // container part only
        // The Ender Chest menu's top row holds only navigation buttons – skip it there.
        int start = enderChest ? Math.min(COLUMNS, upper) : 0;
        List<ItemStack> contents = new ArrayList<>(upper - start);
        for (int i = start; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty() || isMenuChrome(stack)) {
                contents.add(null);
                continue;
            }
            contents.add(stack.copy());
        }

        if (enderChest) {
            Matcher m = FIRST_NUMBER.matcher(lower);
            enderChestPages.put(m.find() ? Integer.parseInt(m.group(1)) : 1, contents);
        } else {
            String key = SkyblockItem.normalizeName(title);
            if (!backpacks.containsKey(key)) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Preview] Cached backpack '{}' ({} slot(s)) from menu '{}'.",
                        key, contents.size(), title);
            }
            backpacks.put(key, contents);
        }
        lastHovered = null; // invalidate the hover cache – fresh data may be available
    }

    /** Menu chrome that must not appear in the preview: filler panes and navigation items. */
    private static boolean isMenuChrome(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.contains("glass_pane")) {
            return true;
        }
        String lower = stripCodes(stack.getHoverName().getString()).trim().toLowerCase(Locale.ROOT);
        return lower.isEmpty() || lower.contains("previous page") || lower.contains("next page")
                || lower.contains("go back") || lower.contains("close") || lower.startsWith("page ");
    }

    // ------------------------------------------------------------------
    // Embedded backpack NBT fallback (ExtraAttributes.*_data -> gzipped NBT -> "i" list)
    // ------------------------------------------------------------------

    /** Last item name a "no blob found" diagnostic was logged for (avoids log spam). */
    private static String lastDiagnosed;

    private static List<ItemStack> decodeEmbedded(ItemStack stack) {
        try {
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            if (data == null || data.isEmpty()) {
                diagnose(stack, new CompoundTag(), new CompoundTag()); // "no custom data at all"
                return null;
            }
            CompoundTag tag = data.copyTag();
            CompoundTag extra = tag.getCompoundOrEmpty("ExtraAttributes");
            if (extra.isEmpty()) {
                for (String key : tag.keySet()) {
                    if (key.equalsIgnoreCase("extraattributes")) {
                        extra = tag.getCompoundOrEmpty(key);
                        break;
                    }
                }
            }
            List<ItemStack> decoded = decodeFrom(extra);
            if (decoded == null) {
                decoded = decodeFrom(tag);
            }
            if (decoded == null) {
                diagnose(stack, tag, extra);
            }
            return decoded;
        } catch (Exception ignored) {
            return null; // malformed blob – simply no preview
        }
    }

    private static List<ItemStack> decodeFrom(CompoundTag holder) throws Exception {
        for (String key : holder.keySet()) {
            if (!key.toLowerCase(Locale.ROOT).endsWith("_data")) {
                continue;
            }
            byte[] blob = holder.getByteArray(key).orElse(null);
            if (blob == null || blob.length == 0) {
                continue;
            }
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(blob), NbtAccounter.unlimitedHeap());
            ListTag items = root.getListOrEmpty("i");
            List<ItemStack> contents = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                contents.add(blobEntryToStack(items.getCompoundOrEmpty(i)));
            }
            return contents;
        }
        return null;
    }

    /** One blob slot compound -> best-effort icon stack ({@code null} for an empty slot). */
    private static ItemStack blobEntryToStack(CompoundTag entry) {
        if (entry.isEmpty()) {
            return null;
        }
        CompoundTag tag = entry.getCompoundOrEmpty("tag");
        String skyblockId = tag.getCompoundOrEmpty("ExtraAttributes").getStringOr("id", "");
        String name = stripCodes(tag.getCompoundOrEmpty("display").getStringOr("Name", "")).trim();
        int count = entry.getByteOr("Count", (byte) 1);
        if (skyblockId.isEmpty() && name.isEmpty()) {
            return null;
        }
        sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog catalog = sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog.getInstance();
        sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog.Entry catEntry =
                skyblockId.isEmpty() ? null : catalog.byId(skyblockId);
        if (catEntry == null && !name.isEmpty()) {
            catEntry = catalog.byName(name);
            if (catEntry == null) {
                catEntry = catalog.byNormalizedName(name);
            }
        }
        String resolvedId = !skyblockId.isEmpty() ? skyblockId : (catEntry != null ? catEntry.id : null);
        return new ItemRef(resolvedId, catEntry != null ? catEntry.material : null,
                name.isEmpty() ? skyblockId : name, Math.max(1, count)).iconStack();
    }

    private static void diagnose(ItemStack stack, CompoundTag tag, CompoundTag extra) {
        String name = stripCodes(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
        if (!name.contains("backpack") || name.equals(lastDiagnosed)) {
            return;
        }
        lastDiagnosed = name;
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Preview] No contents blob found for '{}'. custom-data keys={}, ExtraAttributes keys={}",
                name, tag.keySet(), extra.keySet());
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    private static void drawPreview(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                                    String title, List<ItemStack> rawContents) {
        List<ItemStack> contents = trimEmptyRows(rawContents);
        if (contents.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int rows = Math.max(1, (contents.size() + COLUMNS - 1) / COLUMNS);
        int panelW = COLUMNS * CELL + 12;
        int panelH = rows * CELL + font.lineHeight + 16;
        int x = (screen.width - panelW) / 2;
        int y = 8; // top-centre, away from the cursor tooltip

        SciFiRender.glow(g, x, y, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, x, y, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, x + 1, y + 1, panelW - 2, panelH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
        g.centeredText(font, Component.literal(title), x + panelW / 2, y + 5, SBSTheme.ACCENT_BRIGHT);

        int gridX = x + 6;
        int gridY = y + font.lineHeight + 10;
        for (int i = 0; i < contents.size(); i++) {
            int cx = gridX + (i % COLUMNS) * CELL;
            int cy = gridY + (i / COLUMNS) * CELL;
            SciFiRender.roundedRectWithBorder(g, cx, cy, 17, 17, SBSTheme.SLOT_CORNER,
                    SBSTheme.SLOT_BG, SBSTheme.CARD_BORDER);
            ItemStack stack = contents.get(i);
            if (stack != null && !stack.isEmpty()) {
                g.item(stack, cx + 1, cy + 1);
                g.itemDecorations(font, stack, cx + 1, cy + 1);
            }
        }
    }

    /** Removes fully-empty rows at the start and end (leftover chrome rows, unused capacity). */
    private static List<ItemStack> trimEmptyRows(List<ItemStack> contents) {
        int start = 0;
        int end = contents.size();
        while (start + COLUMNS <= end && allNull(contents, start, start + COLUMNS)) {
            start += COLUMNS;
        }
        while (end - COLUMNS >= start && allNull(contents, Math.max(start, end - COLUMNS), end)) {
            end -= COLUMNS;
        }
        return contents.subList(start, Math.max(start, end));
    }

    private static boolean allNull(List<ItemStack> list, int from, int to) {
        for (int i = from; i < to && i < list.size(); i++) {
            if (list.get(i) != null) {
                return false;
            }
        }
        return true;
    }

    private static String stripCodes(String text) {
        return text == null ? "" : text.replaceAll(SECTION_SIGN + ".", "");
    }
}
