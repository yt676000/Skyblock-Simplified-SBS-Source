/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.wishlist.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.economy.wishlist.logic.AuctionItemReader;
import sbs.modid.client.economy.wishlist.logic.WishlistSpecText;
import sbs.modid.client.economy.wishlist.logic.WishlistWatcher;
import sbs.modid.client.economy.wishlist.model.AuctionItem;
import sbs.modid.client.economy.wishlist.model.WishlistEntry;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The AH Wishlist overview: one row per entry - on/off toggle, a wide button that opens the
 * {@link WishlistEditScreen}, and a delete that asks once before it deletes. Above the list,
 * "Add" starts an empty entry and "From Held Item" starts one prefilled with everything the item in
 * the main hand carries.
 *
 * <p>Sized from the viewport (1280x720 at GUI scale 4 is a 320x180 canvas): the margin shrinks on
 * small screens, and the number of visible rows is derived from the space left, with the shared
 * {@link SciFiScrollbar} for the rest.
 */
public final class WishlistScreen extends Screen {

    static final int ROW_H = SBSTheme.SEARCH_HEIGHT;
    static final int ROW_GAP = 3;
    private static final int GAP = 4;

    private final Screen parent;
    private final SciFiScrollbar scrollbar = new SciFiScrollbar();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int listTop;
    private int listH;
    private int visibleRows;
    private int scroll;
    private String message = "";

    /** Entry whose delete button was pressed once; the second press deletes. */
    private String pendingDelete;

    public WishlistScreen(Screen parent) {
        super(Component.literal("AH Wishlist"));
        this.parent = parent;
    }

    private static SBSConfig.AhWishlistSettings cfg() {
        return ConfigManager.getInstance().get().ahWishlist;
    }

    @Override
    protected void init() {
        int margin = margin(this.width, this.height);
        panelW = Math.min(Math.max(1, this.width - margin * 2), SBSTheme.PANEL_MAX_WIDTH + 60);
        panelH = Math.min(Math.max(1, this.height - margin * 2), SBSTheme.PANEL_MAX_HEIGHT);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = pad(panelW);
        innerX = panelX + pad;
        contentW = panelW - pad * 2;
        int addY = panelY + headerH(font);
        listTop = addY + ROW_H + GAP;
        int backY = panelY + panelH - pad - ROW_H;
        int statusH = font.lineHeight + 2;
        listH = Math.max(ROW_H, backY - statusH - GAP - listTop);
        visibleRows = Math.max(1, (listH + ROW_GAP) / (ROW_H + ROW_GAP));
        rebuild();
    }

    /** Margin around the panel: the theme's, but never more than a twentieth of the short side. */
    static int margin(int width, int height) {
        return Math.min(SBSTheme.SCREEN_MARGIN, Math.max(4, Math.min(width, height) / 24));
    }

    static int pad(int panelW) {
        return Math.min(SBSTheme.PANEL_PADDING, Math.max(4, panelW / 40));
    }

    static int headerH(Font font) {
        return Math.min(SBSTheme.HEADER_HEIGHT, font.lineHeight + 12);
    }

    private void rebuild() {
        clearWidgets();
        List<WishlistEntry> entries = cfg().entries;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - visibleRows)));
        addRenderableOnly(new Panel());

        int half = (contentW - GAP) / 2;
        int addY = panelY + headerH(font);
        addRenderableWidget(new SciFiButton(innerX, addY, half, ROW_H,
                Component.literal("+ Add"), () -> open(new WishlistEntry())));
        addRenderableWidget(new SciFiButton(innerX + half + GAP, addY, contentW - half - GAP, ROW_H,
                Component.literal("+ From Held Item"), this::addFromHeld));

        boolean bar = entries.size() > visibleRows;
        int rowW = contentW - (bar ? SciFiScrollbar.WIDTH + 3 : 0);
        int toggleW = Math.min(36, rowW / 6);
        int deleteW = Math.max(ROW_H, font.width("Delete?") + 8);
        int editX = innerX + toggleW + GAP;
        int editW = Math.max(10, rowW - toggleW - deleteW - GAP * 2);
        int last = Math.min(entries.size(), scroll + visibleRows);
        for (int i = scroll; i < last; i++) {
            WishlistEntry entry = entries.get(i);
            int y = listTop + (i - scroll) * (ROW_H + ROW_GAP);
            addRenderableWidget(new SciFiToggleButton(innerX, y, toggleW, ROW_H, Component.literal(""),
                    () -> entry.enabled, () -> { entry.enabled = !entry.enabled; changed(); }));
            addRenderableWidget(new SciFiButton(editX, y, editW, ROW_H,
                    Component.literal(font.plainSubstrByWidth(summary(entry), editW - 8, false)),
                    () -> open(entry)));
            boolean confirm = entry.id.equals(pendingDelete);
            addRenderableWidget(new SciFiButton(innerX + rowW - deleteW, y, deleteW, ROW_H,
                    Component.literal(confirm ? "Delete?" : "X"), () -> delete(entry)));
        }

        int backY = panelY + panelH - pad(panelW) - ROW_H;
        addRenderableWidget(new SciFiButton(innerX, backY, contentW, ROW_H,
                Component.literal("Back"), this::onClose));
    }

    /** "Necron's Chestplate · max 400m · Growth 6, ..." - the row caption. */
    static String summary(WishlistEntry entry) {
        StringBuilder out = new StringBuilder(entry.itemName.isBlank() ? "(no item)" : entry.itemName);
        out.append(" · ").append(entry.maxPrice > 0 ? "max " + WishlistSpecText.formatCoins(entry.maxPrice) : "any price");
        String enchants = WishlistSpecText.formatEnchants(entry.enchants);
        if (!enchants.isEmpty()) {
            out.append(" · ").append(enchants);
        }
        if (entry.minStars > 0) {
            out.append(" · ").append(entry.minStars).append("+ stars");
        }
        return out.toString();
    }

    private void delete(WishlistEntry entry) {
        if (!entry.id.equals(pendingDelete)) {
            pendingDelete = entry.id;
            rebuild();
            return;
        }
        pendingDelete = null;
        cfg().entries.remove(entry);
        changed();
        rebuild();
    }

    private void changed() {
        ConfigManager.getInstance().save();
        WishlistWatcher.getInstance().entriesChanged();
    }

    private void open(WishlistEntry entry) {
        pendingDelete = null;
        Minecraft.getInstance().setScreenAndShow(new WishlistEditScreen(this, entry));
    }

    /**
     * Prefills an entry from the main-hand item: its name, id, and every criterion it carries as a
     * minimum, so the result matches this very item and anything better. Skin and dye are not
     * copied - the blacklist is the player's call.
     */
    private void addFromHeld() {
        var player = Minecraft.getInstance().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getMainHandItem();
        if (stack.isEmpty()) {
            message = "Hold the item in your hand first.";
            return;
        }
        AuctionItem item = AuctionItemReader.fromAttributes(SkyblockItem.extraAttributes(stack));
        if (item.itemId().isEmpty()) {
            message = "That is not a SkyBlock item.";
            return;
        }
        open(fromItem(stripName(stack.getHoverName().getString()), item));
    }

    static WishlistEntry fromItem(String name, AuctionItem item) {
        WishlistEntry entry = new WishlistEntry();
        entry.itemName = name;
        entry.itemId = item.itemId();
        entry.enchants = new java.util.LinkedHashMap<>(new java.util.TreeMap<>(item.enchants()));
        entry.reforge = item.reforge();
        entry.minStars = item.stars();
        entry.recomb = item.recombobulated();
        entry.minPotatoBooks = item.potatoBooks();
        java.util.Set<String> open = new java.util.HashSet<>(item.unlockedSlots());
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (AuctionItem.Gem gem : item.gems()) {
            open.add(gem.slot());
            counts.merge(gem.quality() + " " + gem.type(), 1, Integer::sum);
        }
        entry.minGemSlots = open.size();
        List<WishlistEntry.GemRequirement> gems = new ArrayList<>();
        counts.forEach((key, count) -> {
            String[] parts = key.split(" ", 2);
            gems.add(new WishlistEntry.GemRequirement(parts[1], parts[0], count));
        });
        entry.gems = gems;
        return entry;
    }

    /** The display name without its reforge word and star glyphs, which the match ignores anyway. */
    private static String stripName(String name) {
        String plain = name.replaceAll("[^\\p{L}\\p{N}' \\-]", "").trim();
        return plain.replaceAll("\\s+", " ");
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        syncBar();
        if (scrollbar.handleClick(event.x(), event.y(), scroll, this::scrollTo)) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return scrollbar.handleDrag(event.y(), this::scrollTo) || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return scrollbar.release() || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scrollTo(scroll + (scrollY > 0 ? -1 : 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void scrollTo(int next) {
        int max = Math.max(0, cfg().entries.size() - visibleRows);
        int clamped = Math.max(0, Math.min(max, next));
        if (clamped != scroll) {
            scroll = clamped;
            rebuild();
        }
    }

    private void syncBar() {
        scrollbar.set(innerX + contentW - SciFiScrollbar.WIDTH, listTop, listH, cfg().entries.size(), visibleRows);
    }

    @Override
    public void onClose() {
        ConfigManager.getInstance().save();
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Draws the panel frame and title. Shared with the editor so both look the same. */
    static void paintPanel(GuiGraphicsExtractor g, Font font, int screenW, int screenH,
                           int x, int y, int w, int h, String title) {
        g.fill(0, 0, screenW, screenH, SBSTheme.BG_TINT);
        SciFiRender.glow(g, x, y, w, h, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, x, y, w, h, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, x + 1, y + 1, w - 2, h - 2, SBSTheme.PANEL_CORNER - 1,
                SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
        int headerH = headerH(font);
        String fitted = font.plainSubstrByWidth(title, w - 8, false);
        g.centeredText(font, Component.literal(fitted), x + w / 2, y + (headerH - font.lineHeight) / 2,
                SBSTheme.ACCENT_BRIGHT);
        int pad = pad(w);
        g.fill(x + pad, y + headerH - 3, x + w - pad, y + headerH - 2, SBSTheme.ACCENT);
    }

    private final class Panel implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            paintPanel(g, font, width, height, panelX, panelY, panelW, panelH, "AH Wishlist");
            int backY = panelY + panelH - pad(panelW) - ROW_H;
            int statusY = backY - GAP - font.lineHeight;
            if (cfg().entries.isEmpty()) {
                drawWrapped(g, "Nothing on your wishlist yet - add an item, or hold one and use "
                        + "\"From Held Item\".", listTop + 2, SBSTheme.TEXT_MUTED);
            }
            String status = !message.isEmpty() ? message
                    : cfg().enabled ? "" : "AH Wishlist is switched off in its module settings.";
            if (!status.isEmpty()) {
                g.text(font, Component.literal(font.plainSubstrByWidth(status, contentW, false)),
                        innerX, statusY, message.isEmpty() ? SBSTheme.TEXT_MUTED : SBSTheme.WARN);
            }
            syncBar();
            scrollbar.render(g, scroll, mouseX, mouseY);
        }

        private void drawWrapped(GuiGraphicsExtractor g, String text, int y, int colour) {
            for (var line : font.split(Component.literal(text), contentW)) {
                g.text(font, line, innerX, y, colour);
                y += font.lineHeight + 1;
            }
        }
    }
}
