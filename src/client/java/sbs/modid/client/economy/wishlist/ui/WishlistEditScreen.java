/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.wishlist.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.wishlist.logic.WishlistSpecText;
import sbs.modid.client.economy.wishlist.logic.WishlistWatcher;
import sbs.modid.client.economy.wishlist.model.WishlistEntry;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Edits one wishlist entry. Every criterion is a labelled text field - the exact value matters, so
 * per {@code ui/AGENTS.md} it is typed, not slid or clicked through - except "Recombobulated", which
 * is a plain on/off toggle. The fields' hints show the syntax while they are empty.
 *
 * <p>Edits go to a draft; "Save" writes it into the list and "Cancel" (or Escape) throws it away, so
 * a half-typed spec never reaches the watcher. Text responders only store text and re-parse; they
 * never rebuild the widgets, which would take the focus mid-word. Rebuilds come from scrolling and
 * buttons alone.
 *
 * <p>Rows: label column measured from the longest label, capped at 40% of the panel so the field
 * keeps the larger part on a 320-wide canvas; visible rows derived from the height, the shared
 * {@link SciFiScrollbar} for the rest.
 */
public final class WishlistEditScreen extends Screen {

    private static final int ROW_H = WishlistScreen.ROW_H;
    private static final int ROW_GAP = WishlistScreen.ROW_GAP;
    private static final int GAP = 4;

    /** One editable row: its label, its hint, and how it reads and writes the draft as text. */
    private record Field(String label, String hint, Supplier<String> read, Consumer<String> write) {
    }

    private final WishlistScreen parent;
    private final WishlistEntry original;
    private final WishlistEntry draft;
    private final List<Field> fields = new ArrayList<>();
    /** The text in each field, kept across rebuilds (scrolling recreates the boxes). */
    private final List<String> texts = new ArrayList<>();
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
    private int labelW;
    private int scroll;
    private String error = "";

    public WishlistEditScreen(WishlistScreen parent, WishlistEntry entry) {
        super(Component.literal("Wishlist Entry"));
        this.parent = parent;
        this.original = entry;
        this.draft = copy(entry);
        defineFields();
        for (Field field : fields) {
            texts.add(field == null ? "" : field.read().get()); // null = the Recombobulated toggle row
        }
    }

    private void defineFields() {
        fields.add(new Field("Item", "Necron's Chestplate",
                () -> draft.itemName, text -> {
                    if (!text.trim().equalsIgnoreCase(draft.itemName.trim())) {
                        draft.itemId = ""; // a renamed entry is no longer the item it was made from
                    }
                    draft.itemName = text.trim();
                }));
        fields.add(new Field("Max Price", "400m (empty = any)",
                () -> WishlistSpecText.formatCoins(draft.maxPrice), text -> {
                    long coins = WishlistSpecText.parseCoins(text);
                    require(coins >= 0, "Max Price: write coins like 400m or 1.5b");
                    draft.maxPrice = Math.max(0, coins);
                }));
        fields.add(new Field("Enchants (min)", "growth 6, protection 6",
                () -> WishlistSpecText.formatEnchants(draft.enchants), text -> {
                    var parsed = WishlistSpecText.parseEnchants(text);
                    require(parsed.ok(), "Enchants: write 'name level', not '" + first(parsed.errors()) + "'");
                    draft.enchants = new LinkedHashMap<>(parsed.value());
                }));
        fields.add(new Field("Reforge", "ancient (empty = any)",
                () -> draft.reforge, text -> draft.reforge = text.trim().toLowerCase(Locale.ROOT)));
        fields.add(new Field("Stars (min)", "5",
                () -> number(draft.minStars), text -> draft.minStars = intIn(text, 0, 10, "Stars")));
        fields.add(new Field("Potato Books (min)", "10 hot, 15 = +5 fuming",
                () -> number(draft.minPotatoBooks), text -> draft.minPotatoBooks = intIn(text, 0, 15, "Potato Books")));
        fields.add(null); // the Recombobulated toggle sits here
        fields.add(new Field("Gem Slots (min)", "2",
                () -> number(draft.minGemSlots), text -> draft.minGemSlots = intIn(text, 0, 10, "Gem Slots")));
        fields.add(new Field("Gems (min)", "2x perfect jasper",
                () -> WishlistSpecText.formatGems(draft.gems), text -> {
                    var parsed = WishlistSpecText.parseGems(text);
                    require(parsed.ok(), "Gems: write '2x perfect jasper', not '" + first(parsed.errors()) + "'");
                    draft.gems = parsed.value();
                }));
        fields.add(new Field("Blacklist", "skin, dye, reforge:wise",
                () -> WishlistSpecText.formatBlacklist(draft.blacklist), text -> {
                    var parsed = WishlistSpecText.parseBlacklist(text);
                    require(parsed.ok(), "Blacklist: skin, dye, skin:x, dye:x, reforge:x, enchant:x, gem:x - not '"
                            + first(parsed.errors()) + "'");
                    draft.blacklist = parsed.value();
                }));
    }

    @Override
    protected void init() {
        int margin = WishlistScreen.margin(this.width, this.height);
        panelW = Math.min(Math.max(1, this.width - margin * 2), SBSTheme.PANEL_MAX_WIDTH + 60);
        panelH = Math.min(Math.max(1, this.height - margin * 2), SBSTheme.PANEL_MAX_HEIGHT + 40);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = WishlistScreen.pad(panelW);
        innerX = panelX + pad;
        contentW = panelW - pad * 2;
        listTop = panelY + WishlistScreen.headerH(font);
        int buttonsY = panelY + panelH - pad - ROW_H;
        int statusH = font.lineHeight + 2;
        listH = Math.max(ROW_H, buttonsY - statusH - GAP - listTop);
        visibleRows = Math.max(1, (listH + ROW_GAP) / (ROW_H + ROW_GAP));
        int widest = 0;
        for (Field field : fields) {
            widest = Math.max(widest, font.width(field == null ? "Recombobulated" : field.label()));
        }
        labelW = Math.min(widest + 6, contentW * 2 / 5);
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, fields.size() - visibleRows)));
        addRenderableOnly(new Panel());

        int rowW = rowW();
        int fieldX = innerX + labelW + GAP;
        int fieldW = Math.max(10, rowW - labelW - GAP);
        int last = Math.min(fields.size(), scroll + visibleRows);
        for (int i = scroll; i < last; i++) {
            int y = listTop + (i - scroll) * (ROW_H + ROW_GAP);
            Field field = fields.get(i);
            if (field == null) {
                addRenderableWidget(new SciFiToggleButton(fieldX, y, Math.min(fieldW, 44), ROW_H,
                        Component.literal(""), () -> draft.recomb, () -> draft.recomb = !draft.recomb));
                continue;
            }
            int index = i;
            EditBox box = new EditBox(font, fieldX + 5, y + (ROW_H - font.lineHeight) / 2, fieldW - 10,
                    font.lineHeight, Component.literal(field.label()));
            box.setBordered(false);
            box.setMaxLength(256);
            box.setTextColor(SBSTheme.TEXT);
            box.setHint(Component.literal(field.hint()));
            box.setValue(texts.get(index));
            box.setResponder(text -> {
                texts.set(index, text);
                validate();
            });
            addRenderableWidget(box);
        }

        int pad = WishlistScreen.pad(panelW);
        int buttonsY = panelY + panelH - pad - ROW_H;
        int half = (contentW - GAP) / 2;
        addRenderableWidget(new SciFiButton(innerX, buttonsY, half, ROW_H,
                Component.literal("Save"), this::save));
        addRenderableWidget(new SciFiButton(innerX + half + GAP, buttonsY, contentW - half - GAP, ROW_H,
                Component.literal("Cancel"), this::onClose));
        validate();
    }

    private int rowW() {
        return contentW - (fields.size() > visibleRows ? SciFiScrollbar.WIDTH + 3 : 0);
    }

    /** Applies every field to the draft and keeps the first complaint for the status line. */
    private boolean validate() {
        error = "";
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if (field == null) {
                continue;
            }
            try {
                field.write().accept(texts.get(i));
            } catch (IllegalArgumentException bad) {
                if (error.isEmpty()) {
                    error = bad.getMessage();
                }
            }
        }
        if (error.isEmpty() && draft.itemName.isBlank()) {
            error = "Name the item you want.";
        }
        return error.isEmpty();
    }

    private void save() {
        if (!validate()) {
            return;
        }
        var entries = ConfigManager.getInstance().get().ahWishlist.entries;
        int at = entries.indexOf(original);
        if (at >= 0) {
            entries.set(at, draft);
        } else {
            entries.add(draft);
        }
        ConfigManager.getInstance().save();
        WishlistWatcher.getInstance().entriesChanged();
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
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
        int clamped = Math.max(0, Math.min(Math.max(0, fields.size() - visibleRows), next));
        if (clamped != scroll) {
            scroll = clamped;
            rebuild();
        }
    }

    private void syncBar() {
        scrollbar.set(innerX + contentW - SciFiScrollbar.WIDTH, listTop, listH, fields.size(), visibleRows);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    private static int intIn(String text, int min, int max, String what) {
        String t = text.trim();
        if (t.isEmpty()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(t);
            require(value >= min && value <= max, what + ": a number from " + min + " to " + max);
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + ": a number from " + min + " to " + max);
        }
    }

    private static String number(int value) {
        return value <= 0 ? "" : Integer.toString(value);
    }

    private static String first(List<String> errors) {
        return errors.isEmpty() ? "" : errors.get(0);
    }

    private static WishlistEntry copy(WishlistEntry from) {
        WishlistEntry to = new WishlistEntry();
        to.id = from.id;
        to.enabled = from.enabled;
        to.itemName = from.itemName;
        to.itemId = from.itemId;
        to.maxPrice = from.maxPrice;
        to.enchants = new LinkedHashMap<>(from.enchants);
        to.reforge = from.reforge;
        to.minStars = from.minStars;
        to.recomb = from.recomb;
        to.minPotatoBooks = from.minPotatoBooks;
        to.minGemSlots = from.minGemSlots;
        to.gems = new ArrayList<>(from.gems);
        to.blacklist = new ArrayList<>(from.blacklist);
        return to;
    }

    private final class Panel implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            WishlistScreen.paintPanel(g, font, width, height, panelX, panelY, panelW, panelH,
                    draft.itemName.isBlank() ? "New Wishlist Entry" : draft.itemName);
            int rowW = rowW();
            int fieldX = innerX + labelW + GAP;
            int fieldW = Math.max(10, rowW - labelW - GAP);
            int last = Math.min(fields.size(), scroll + visibleRows);
            for (int i = scroll; i < last; i++) {
                int y = listTop + (i - scroll) * (ROW_H + ROW_GAP);
                Field field = fields.get(i);
                String label = field == null ? "Recombobulated" : field.label();
                // The label yields, never the field.
                g.text(font, Component.literal(font.plainSubstrByWidth(label, labelW, false)),
                        innerX, y + (ROW_H - font.lineHeight) / 2, SBSTheme.TEXT_MUTED);
                if (field != null) {
                    SciFiRender.roundedRectWithBorder(g, fieldX, y, fieldW, ROW_H, SBSTheme.CORNER_RADIUS,
                            SBSTheme.SEARCH_FILL, SBSTheme.CARD_BORDER);
                }
            }
            int pad = WishlistScreen.pad(panelW);
            int statusY = panelY + panelH - pad - ROW_H - GAP - font.lineHeight;
            String status = error.isEmpty() ? "Every value is a minimum - more still matches." : error;
            g.text(font, Component.literal(font.plainSubstrByWidth(status, contentW, false)),
                    innerX, statusY, error.isEmpty() ? SBSTheme.TEXT_MUTED : SBSTheme.WARN);
            syncBar();
            scrollbar.render(g, scroll, mouseX, mouseY);
        }
    }
}
