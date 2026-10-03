/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.customskin.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.Equippable;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;
import sbs.modid.client.helper.customskin.logic.CustomSkinStore;
import sbs.modid.client.helper.customskin.model.CustomSkin;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Custom Skin picker: opened with the module's hotkey while holding an item, it chooses what
 * that item should <b>look</b> like.
 *
 * <p>Layout is the shared SBS screen design (panel, header, divider, back button): the item you are
 * skinning sits top-left with a live preview of the result beside it, a search box filters every
 * Hypixel <i>and</i> vanilla item into a scrollable icon grid, and a colour palette plus a hex field
 * tint the choice. Selecting a grid cell applies immediately, so the preview – and the item in your
 * hand behind the screen – updates as you browse.
 *
 * <p>The search is the live Hypixel item catalogue ({@link SkyBlockItemCatalog}, the same source the
 * Recipe Viewer uses, so a skin keeps the real skull texture / custom model / glint of the item you
 * picked) merged with the vanilla item registry. With an empty query the SkyBlock catalogue leads,
 * because that is what a SkyBlock player is looking for.
 */
public final class CustomSkinScreen extends Screen {

    /** One selectable skin: an icon plus the id that gets stored. */
    private record Choice(String id, String name, ItemStack icon) {
    }

    private static final int CELL = 20;
    private static final int GRID_GAP = 2;
    private static final int SEARCH_LIMIT = 400;

    /** The palette: the 16 dye colours, then a few common metal/gem tones. */
    private static final int[] PALETTE = {
            0xFFFFFF, 0xD8D8D8, 0xA0A0A0, 0x404040, 0xFF6B6B, 0xFFA23F, 0xFFE14D, 0x8BE04E,
            0x3FD16B, 0x3FD1C4, 0x3FB4FF, 0x4B6BFF, 0x9B5BFF, 0xE85BD0, 0xFF8FB8, 0x8B5A2B,
            0x1E1E28, 0xC0392B, 0x2ECC71, 0x00CED1, 0xFFD700, 0xB87333, 0xC0C0C0, 0x50C878,
    };

    /** The item being skinned – captured on open, so a hotbar change cannot retarget it midway. */
    private final ItemStack target;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentW;
    private int gridTop;
    private int gridBottom;
    private int paletteY;
    private int previewY;
    private int cols;

    private EditBox search;
    private EditBox hexField;
    private List<Choice> results = List.of();
    private int scroll;
    private int scrollMax;

    /** Rebuilt on every apply so the preview shows exactly what will render. */
    private ItemStack preview = ItemStack.EMPTY;

    public CustomSkinScreen(ItemStack target) {
        super(Component.literal("Custom Skin"));
        this.target = target == null ? ItemStack.EMPTY : target.copy();
    }

    private CustomSkin current() {
        return CustomSkinStore.getInstance().get(target);
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 380, 560);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 300, 480);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentW = panelW - pad * 2;

        previewY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        int searchY = previewY + 24;
        int textH = this.font.lineHeight;
        int btnW = 106; // "Reset to Default" needs the room; the search field takes what is left

        gridTop = searchY + SBSTheme.SEARCH_HEIGHT + 6;
        // Bottom block, measured upwards: back button, hex row, palette, then the grid gets the rest.
        int backY = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
        int hexY = backY - SBSTheme.SEARCH_HEIGHT - 6;
        paletteY = hexY - 30;
        gridBottom = paletteY - 6;
        cols = Math.max(1, (contentW - 6) / (CELL + GRID_GAP));

        addRenderableOnly(new PanelRenderable());

        search = new EditBox(this.font, innerX + 6, searchY + (SBSTheme.SEARCH_HEIGHT - textH) / 2,
                contentW - btnW - 24, textH, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(48);
        search.setTextColor(SBSTheme.TEXT);
        search.setHint(Component.literal("Search any SkyBlock or vanilla item..."));
        search.setResponder(query -> refresh());
        addRenderableWidget(search);
        setInitialFocus(search);

        // Reset to Default: drops the skin entirely, so the item goes back to its real look.
        addRenderableWidget(new SciFiButton(innerX + contentW - btnW, searchY, btnW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Reset to Default"), this::clearSkin));

        // Hex entry: an explicit colour for anything the palette does not cover.
        hexField = new EditBox(this.font, innerX + 46, hexY + (SBSTheme.SEARCH_HEIGHT - textH) / 2,
                70, textH, Component.literal("Hex"));
        hexField.setBordered(false);
        hexField.setMaxLength(7);
        hexField.setTextColor(SBSTheme.TEXT);
        hexField.setHint(Component.literal("RRGGBB"));
        CustomSkin skin = current();
        if (skin != null && skin.color >= 0) {
            hexField.setValue(String.format("%06X", skin.color));
        }
        hexField.setResponder(this::onHexTyped);
        addRenderableWidget(hexField);

        addRenderableWidget(new SciFiButton(innerX + 124, hexY, 76, SBSTheme.SEARCH_HEIGHT,
                Component.literal("No Color"), () -> applyColor(-1)));

        addRenderableWidget(new SciFiButton(innerX, backY, contentW, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onClose));

        refresh();
        rebuildPreview();
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /**
     * The filtered choice list: SkyBlock catalogue entries first, then vanilla registry items. With
     * an empty query the whole catalogue is offered (capped), which is also what makes the grid
     * useful before the player has typed anything.
     */
    private void refresh() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Choice> list = new ArrayList<>();

        SkyBlockItemCatalog catalog = SkyBlockItemCatalog.getInstance();
        List<SkyBlockItemCatalog.Entry> entries = query.isEmpty()
                ? catalog.allSorted() : catalog.search(query, SEARCH_LIMIT);
        for (SkyBlockItemCatalog.Entry entry : entries) {
            if (list.size() >= SEARCH_LIMIT) {
                break;
            }
            ItemStack icon = SkyBlockItemIcons.getInstance().iconShared(entry.id, entry.material, 1);
            list.add(new Choice(entry.id, stripFormatting(entry.name), icon));
        }

        for (Item item : BuiltInRegistries.ITEM) {
            if (list.size() >= SEARCH_LIMIT * 2) {
                break;
            }
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) {
                continue;
            }
            String id = CustomSkinStore.vanillaId(item);
            String name = stack.getHoverName().getString();
            if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query)
                    && !id.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            list.add(new Choice(id, name, stack));
        }

        results = list;
        scroll = 0;
    }

    private void select(Choice choice) {
        CustomSkin skin = current();
        CustomSkinStore.getInstance().set(target, choice.id(), skin != null ? skin.color : -1);
        rebuildPreview();
    }

    private void applyColor(int rgb) {
        CustomSkin skin = current();
        if (skin == null || !skin.isSet()) {
            return; // nothing to tint yet – pick a skin first
        }
        CustomSkinStore.getInstance().set(target, skin.skinItem, rgb);
        if (rgb < 0 && hexField != null && !hexField.getValue().isEmpty()) {
            hexField.setValue("");
        }
        rebuildPreview();
    }

    private void onHexTyped(String value) {
        if (value.isBlank()) {
            return;
        }
        int rgb = CustomSkinStore.parseHex(value);
        if (rgb >= 0) {
            applyColor(rgb);
        }
    }

    private void clearSkin() {
        CustomSkinStore.getInstance().clear(target);
        if (hexField != null) {
            hexField.setValue("");
        }
        rebuildPreview();
    }

    /** Rebuilds the preview stack from the saved skin (empty when the item has none). */
    private void rebuildPreview() {
        ItemStack skinned = CustomSkinStore.getInstance().skinFor(target);
        preview = skinned != null ? skinned : ItemStack.EMPTY;
    }

    /**
     * Why the current choice will not show on the player model, or {@code null} when it will (or
     * when the item is not armour at all, where the question does not arise).
     */
    private String wornWarning() {
        Equippable original = target.get(DataComponents.EQUIPPABLE);
        if (original == null || preview.isEmpty()) {
            return null;
        }
        Equippable replacement = preview.get(DataComponents.EQUIPPABLE);
        if (replacement != null && replacement.slot() == original.slot()
                && replacement.assetId().isPresent()) {
            return null;
        }
        return "Icon + hand only: worn armor can only look like armor for the same slot.";
    }

    private static String stripFormatting(String name) {
        return name.replaceAll("%%[a-z_]+%%", "")
                .replaceAll(String.valueOf((char) 0x00A7) + ".", "").trim();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        // Palette swatch?
        int swatch = swatchAt(event.x(), event.y());
        if (swatch >= 0) {
            applyColor(PALETTE[swatch]);
            if (hexField != null) {
                hexField.setValue(String.format("%06X", PALETTE[swatch]));
            }
            return true;
        }
        int index = cellAt(event.x(), event.y());
        if (index >= 0 && index < results.size()) {
            select(results.get(index));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseY >= gridTop && mouseY < gridBottom) {
            scroll = clamp(scroll - (int) Math.signum(scrollY), 0, scrollMax);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { // Escape closes even while a text field is focused
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /** The grid index under the cursor, or -1. */
    private int cellAt(double mx, double my) {
        if (mx < innerX || mx >= innerX + cols * (CELL + GRID_GAP)
                || my < gridTop || my >= gridBottom) {
            return -1;
        }
        int col = (int) ((mx - innerX) / (CELL + GRID_GAP));
        int row = (int) ((my - gridTop) / (CELL + GRID_GAP));
        if (col < 0 || col >= cols) {
            return -1;
        }
        return (scroll + row) * cols + col;
    }

    /** The palette swatch index under the cursor, or -1. */
    private int swatchAt(double mx, double my) {
        int size = 12;
        int perRow = Math.max(1, contentW / (size + 2));
        for (int i = 0; i < PALETTE.length; i++) {
            int x = innerX + (i % perRow) * (size + 2);
            int y = paletteY + (i / perRow) * (size + 2);
            if (mx >= x && mx < x + size && my >= y && my < y + size) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = CustomSkinScreen.this.font;
            g.fill(0, 0, CustomSkinScreen.this.width, CustomSkinScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Custom Skin"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            drawHeaderRow(g, font);

            int searchY = previewY + 24;
            SciFiRender.roundedRectWithBorder(g, innerX, searchY, contentW - 106 - 6,
                    SBSTheme.SEARCH_HEIGHT, SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    search.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            drawGrid(g, mouseX, mouseY);
            drawPalette(g, font);
        }

        /** The item being skinned, an arrow, and what it will look like. */
        private void drawHeaderRow(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font) {
            g.item(target, innerX, previewY);
            int textX = innerX + 22;
            String name = stripFormatting(target.getHoverName().getString());
            g.text(font, Component.literal(name), textX, previewY + 1, SBSTheme.TEXT);

            String hint;
            CustomSkin skin = current();
            if (skin != null && skin.isSet()) {
                hint = "looks like:";
            } else {
                hint = "no skin - pick one below";
            }
            g.text(font, Component.literal(hint), textX, previewY + 11, SBSTheme.TEXT_MUTED);

            if (!preview.isEmpty()) {
                int previewX = innerX + contentW - 20;
                SciFiRender.roundedRect(g, previewX - 2, previewY - 2, 20, 20, 3, SBSTheme.ACCENT_SOFT);
                g.item(preview, previewX, previewY);
            }
        }

        private void drawGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = CustomSkinScreen.this.font;
            int rowsVisible = Math.max(1, (gridBottom - gridTop) / (CELL + GRID_GAP));
            int rowsTotal = (results.size() + cols - 1) / cols;
            scrollMax = Math.max(0, rowsTotal - rowsVisible);
            scroll = clamp(scroll, 0, scrollMax);

            if (results.isEmpty()) {
                g.centeredText(font, Component.literal("No matching items."),
                        panelX + panelW / 2, gridTop + 10, SBSTheme.TEXT_MUTED);
                return;
            }

            CustomSkin skin = current();
            String selected = skin != null ? skin.skinItem : null;
            Choice hovered = null;
            for (int row = 0; row < rowsVisible; row++) {
                for (int col = 0; col < cols; col++) {
                    int index = (scroll + row) * cols + col;
                    if (index >= results.size()) {
                        break;
                    }
                    Choice choice = results.get(index);
                    int x = innerX + col * (CELL + GRID_GAP);
                    int y = gridTop + row * (CELL + GRID_GAP);
                    boolean isHovered = mouseX >= x && mouseX < x + CELL
                            && mouseY >= y && mouseY < y + CELL;
                    boolean isSelected = choice.id().equals(selected);
                    SciFiRender.roundedRectWithBorder(g, x, y, CELL, CELL, 3,
                            isHovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                            isSelected ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
                    g.item(choice.icon(), x + 2, y + 2);
                    if (isHovered) {
                        hovered = choice;
                    }
                }
            }

            // Scrollbar, same shape as the other SBS lists.
            if (scrollMax > 0) {
                int trackX = innerX + cols * (CELL + GRID_GAP) + 1;
                int trackH = rowsVisible * (CELL + GRID_GAP);
                g.fill(trackX, gridTop, trackX + 3, gridTop + trackH, SBSTheme.CARD_BG_DISABLED);
                int thumbH = Math.max(8, trackH * rowsVisible / Math.max(1, rowsTotal));
                int thumbY = gridTop + (int) ((long) (trackH - thumbH) * scroll / scrollMax);
                g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, SBSTheme.ACCENT);
            }

            if (hovered != null) {
                g.setTooltipForNextFrame(font, List.of(Component.literal(hovered.name()),
                                Component.literal("§8" + hovered.id())),
                        java.util.Optional.empty(), mouseX, mouseY);
            }
        }

        private void drawPalette(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font) {
            int size = 12;
            int perRow = Math.max(1, contentW / (size + 2));
            CustomSkin skin = current();
            int active = skin != null ? skin.color : -1;
            for (int i = 0; i < PALETTE.length; i++) {
                int x = innerX + (i % perRow) * (size + 2);
                int y = paletteY + (i / perRow) * (size + 2);
                SciFiRender.roundedRectWithBorder(g, x, y, size, size, 2, 0xFF000000 | PALETTE[i],
                        PALETTE[i] == active ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            }

            int hexY = paletteY + 30;
            SciFiRender.roundedRectWithBorder(g, innerX + 40, hexY, 80, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    hexField.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            g.text(font, Component.literal("Color"), innerX,
                    hexY + (SBSTheme.SEARCH_HEIGHT - font.lineHeight) / 2, SBSTheme.TEXT_MUTED);

            // Two honest notes, only when they apply: tint support and the worn-armor limit.
            int noteY = hexY + SBSTheme.SEARCH_HEIGHT + 2;
            String note = null;
            if (!preview.isEmpty() && active >= 0 && !CustomSkinStore.isTintable(preview)) {
                note = "This model has no dyeable layer - the color will not show.";
            } else {
                String worn = wornWarning();
                if (worn != null) {
                    note = worn;
                }
            }
            if (note != null) {
                g.text(font, Component.literal(note), innerX, noteY, SBSTheme.TEXT_MUTED);
            }
        }
    }

    /** Kept for readability of the tint note above; mirrors the store's own check. */
    @SuppressWarnings("unused")
    private static int dyedOf(ItemStack stack) {
        return DyedItemColor.getOrDefault(stack, -1);
    }
}
