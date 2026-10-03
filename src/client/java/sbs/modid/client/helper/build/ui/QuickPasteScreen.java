/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import sbs.modid.client.core.build.io.SchematicShareCode;
import sbs.modid.client.core.build.io.SchematicStore;
import sbs.modid.client.core.build.logic.BuildLibrary;
import sbs.modid.client.core.build.logic.Clipboard;
import sbs.modid.client.core.build.logic.Thumbnails;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.build.logic.BuildChat;
import sbs.modid.client.helper.build.logic.BuildGate;
import sbs.modid.client.helper.build.logic.Placement;
import sbs.modid.client.helper.build.model.LibrarySearch;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.component.SciFiTextField;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Quick Paste: every saved build as a card - thumbnail, name, size, block count, date - in a grid
 * that is searched as you type (by name, {@code #tag} or folder), favourites first.
 *
 * <p>Click a card and it becomes the floating hologram at your look point, ready to nudge and place
 * (singleplayer) or pin and build along with (server). Right-click for the rest: favourite, rename,
 * tags, folder, copy a share code, delete.
 *
 * <p>Follows {@code ui/AGENTS.md}: the cards are real widgets (focusable, Enter presses them), the
 * list scrolls with the shared {@link SciFiScrollbar}, the column count and card size are derived
 * from the panel measured against the viewport, and the search field's responder never rebuilds the
 * screen - it swaps the card widgets only, so the field keeps its focus and text.
 *
 * <p>The right-click menu is a popup and satisfies that file's four rules: drawn after everything,
 * offered clicks and Escape first, opaque, and nothing queues a tooltip underneath it.
 */
public final class QuickPasteScreen extends Screen {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final int GAP = 4;
    private static final int CARD_MIN_WIDTH = 92;
    private static final int SCROLLBAR_SPACE = SciFiScrollbar.WIDTH + 2;

    private final SciFiScrollbar scrollbar = new SciFiScrollbar();
    private final List<AbstractButton> cardWidgets = new ArrayList<>();
    private final Set<String> thumbnailsRequested = new HashSet<>();

    private List<SchematicStore.Entry> all = List.of();
    private List<SchematicStore.Entry> shown = List.of();
    private boolean loading = true;
    private String loadError;
    private String query = "";

    // Layout, measured in init().
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int searchY;
    private int gridTop;
    private int gridHeight;
    private int footerY;
    private int columns;
    private int cardW;
    private int cardH;
    private int thumbSize;
    private int textLines;
    private int visibleRows;
    private int scrollRow;

    // Popup (right-click menu) and the inline edit bar it can open.
    private SchematicStore.Entry popupEntry;
    private int popupX;
    private int popupY;
    private boolean confirmDelete;
    private SchematicStore.Entry editEntry;
    private EditKind editKind;
    private SciFiTextField editField;
    private final List<SciFiButton> editButtons = new ArrayList<>();

    private enum EditKind { RENAME, TAGS, FOLDER }

    private static final String[] POPUP_ACTIONS = {"favourite", "Rename", "Tags", "Folder", "Copy share code", "delete"};

    public QuickPasteScreen() {
        super(Component.literal("Quick Paste"));
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        int availableW = Math.max(1, this.width - SBSTheme.SCREEN_MARGIN * 2);
        int availableH = Math.max(1, this.height - SBSTheme.SCREEN_MARGIN * 2);
        panelW = Math.min(availableW, 520);
        panelH = Math.min(availableH, 360);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentWidth = panelW - pad * 2;
        searchY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        gridTop = searchY + SBSTheme.SEARCH_HEIGHT + SBSTheme.GAP_AFTER_SEARCH;
        // The footer band holds the hint line, or the rename / tags / folder bar when one is open.
        footerY = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
        gridHeight = Math.max(20, footerY - 4 - gridTop);

        // Columns from the measured width; the scrollbar strip is reserved unconditionally.
        int gridW = contentWidth - SCROLLBAR_SPACE;
        columns = Math.max(1, (gridW + GAP) / (CARD_MIN_WIDTH + GAP));
        cardW = (gridW - GAP * (columns - 1)) / columns;
        int lineH = this.font.lineHeight + 1;
        // Thumbnail and text lines shrink to what one row of the grid can hold, so a card is never
        // taller than the grid at 1280x720 on GUI scale 4.
        textLines = 3;
        thumbSize = Math.min(cardW - 8, 72);
        while (textLines > 1 && 6 + thumbSize + textLines * lineH > gridHeight) {
            if (thumbSize > 32) {
                thumbSize = Math.max(32, gridHeight - 6 - textLines * lineH);
            } else {
                textLines--;
            }
        }
        thumbSize = Math.max(12, Math.min(thumbSize, gridHeight - 6 - textLines * lineH));
        cardH = 6 + thumbSize + textLines * lineH;
        visibleRows = Math.max(1, (gridHeight + GAP) / (cardH + GAP));

        clearWidgets();
        cardWidgets.clear();
        addRenderableOnly(new PanelRenderable());
        // "Manage..." opens the Build Library; measured from its label, the search field takes the rest.
        int manageW = this.font.width("Manage...") + 14;
        addRenderableWidget(new SciFiButton(innerX + contentWidth - manageW, searchY, manageW, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Manage..."), () -> Minecraft.getInstance().setScreenAndShow(new BuildLibraryScreen())));
        SciFiTextField search = SciFiTextField.forRow(innerX, searchY, contentWidth - manageW - GAP, SBSTheme.SEARCH_HEIGHT,
                "Search", "Search: name, #tag or folder", 64, () -> query, text -> {
                    // Recompute and swap the cards only; never rebuild the screen from a text responder.
                    query = text;
                    scrollRow = 0;
                    refilter();
                });
        addRenderableWidget(search);
        setInitialFocus(search);
        if (editEntry != null) {
            openEditBar(editEntry, editKind);
        }
        if (loading && all.isEmpty()) {
            reload();
        } else {
            refilter();
        }
    }

    private void reload() {
        loading = true;
        // A rename or delete moves thumbnail files; drop the loaded textures so none is stale.
        Thumbnails.releaseAll();
        thumbnailsRequested.clear();
        BuildLibrary.runAsync(SchematicStore::list, result -> {
            loading = false;
            if (!result.ok()) {
                loadError = result.error();
                all = List.of();
            } else {
                loadError = null;
                all = result.value().entries();
            }
            refilter();
        });
    }

    /** Filters and sorts {@link #all} into {@link #shown}, then swaps in the visible cards. */
    private void refilter() {
        List<SchematicStore.Entry> out = new ArrayList<>();
        for (SchematicStore.Entry entry : all) {
            if (LibrarySearch.matches(entry.displayName(), entry.summary().header(), query)) {
                out.add(entry);
            }
        }
        out.sort(Comparator.comparing((SchematicStore.Entry e) -> !e.summary().header().favourite())
                .thenComparing(e -> e.summary().header().folder().toLowerCase(Locale.ROOT))
                .thenComparing(Comparator.comparingLong((SchematicStore.Entry e) -> e.summary().header().createdAt()).reversed()));
        shown = out;
        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll()));
        swapCards();
    }

    private int rowsTotal() {
        return (shown.size() + columns - 1) / columns;
    }

    private int maxScroll() {
        return Math.max(0, rowsTotal() - visibleRows);
    }

    private void swapCards() {
        for (AbstractButton card : cardWidgets) {
            removeWidget(card);
        }
        cardWidgets.clear();
        int first = scrollRow * columns;
        int last = Math.min(shown.size(), (scrollRow + visibleRows) * columns);
        for (int i = first; i < last; i++) {
            int slot = i - first;
            int x = innerX + (slot % columns) * (cardW + GAP);
            int y = gridTop + (slot / columns) * (cardH + GAP);
            SchematicStore.Entry entry = shown.get(i);
            BuildCard card = new BuildCard(x, y, cardW, cardH, entry, thumbSize, textLines, () -> pick(entry),
                    () -> false, () -> popupEntry != null, thumbnailsRequested);
            cardWidgets.add(card);
            addRenderableWidget(card);
        }
    }

    private void setScroll(int row) {
        int next = Math.max(0, Math.min(maxScroll(), row));
        if (next != scrollRow) {
            scrollRow = next;
            swapCards();
        }
    }

    // ---------------------------------------------------------------- actions

    /** Left click: load and hand to placement, then close. */
    private void pick(SchematicStore.Entry entry) {
        if (!ConfigManager.getInstance().get().buildTools.enabled) {
            BuildChat.warn("Build Tools is off - switch it on in /sbs, Quality of Life > Build Tools");
            return;
        }
        onClose();
        BuildLibrary.loadAsync(entry.slug(), result -> {
            if (!result.ok()) {
                BuildChat.warn("Could not load \"" + entry.displayName() + "\" - " + result.error());
                return;
            }
            Clipboard.set(result.value());
            if (Placement.start(result.value())) {
                BuildChat.info("Placing \"" + entry.displayName() + "\" - arrows/wheel move it, R turns, F flips, "
                        + "Enter " + (BuildGate.singleplayer() ? "places it" : "pins it") + ", Esc cancels");
            }
        });
    }

    private void openPopup(SchematicStore.Entry entry, double mouseX, double mouseY) {
        popupEntry = entry;
        confirmDelete = false;
        int w = popupWidth();
        int h = POPUP_ACTIONS.length * popupRowHeight() + 4;
        // Kept inside the screen: flipped left/up when it would run off an edge.
        popupX = (int) Math.min(mouseX, this.width - w - 2);
        popupY = (int) Math.min(mouseY, this.height - h - 2);
    }

    private int popupWidth() {
        int widest = 0;
        for (String action : POPUP_ACTIONS) {
            widest = Math.max(widest, this.font.width(popupLabel(action)));
        }
        return widest + 16;
    }

    private int popupRowHeight() {
        return this.font.lineHeight + 6;
    }

    private String popupLabel(String action) {
        if (action.equals("favourite")) {
            return popupEntry != null && popupEntry.summary().header().favourite() ? "Unfavourite" : "Favourite";
        }
        if (action.equals("delete")) {
            return confirmDelete ? "Click again to delete" : "Delete";
        }
        return action;
    }

    private void runPopup(String action) {
        SchematicStore.Entry entry = popupEntry;
        switch (action) {
            case "favourite" -> {
                popupEntry = null;
                SchematicHeader header = entry.summary().header();
                BuildLibrary.runAsync(store -> {
                    store.updateHeader(entry.slug(), header.withFavourite(!header.favourite()));
                    return null;
                }, result -> afterChange(result, null));
            }
            case "Rename" -> openEditBar(entry, EditKind.RENAME);
            case "Tags" -> openEditBar(entry, EditKind.TAGS);
            case "Folder" -> openEditBar(entry, EditKind.FOLDER);
            case "Copy share code" -> {
                popupEntry = null;
                BuildLibrary.loadAsync(entry.slug(), result -> {
                    if (!result.ok()) {
                        BuildChat.warn("Could not load \"" + entry.displayName() + "\" - " + result.error());
                        return;
                    }
                    try {
                        String code = SchematicShareCode.encode(result.value());
                        Minecraft.getInstance().keyboardHandler.setClipboard(code);
                        BuildChat.info("Share code for \"" + entry.displayName() + "\" copied (" + (code.length() / 1024 + 1)
                                + " KB) - anyone with Build Tools can //import it");
                    } catch (SchematicShareCode.ShareException tooBig) {
                        BuildChat.warn(tooBig.getMessage());
                    }
                });
            }
            case "delete" -> {
                if (!confirmDelete) {
                    confirmDelete = true;   // a destructive action asks twice (ui/AGENTS.md)
                    return;
                }
                popupEntry = null;
                BuildLibrary.runAsync(store -> store.delete(entry.slug()),
                        result -> afterChange(result, "Moved \"" + entry.displayName()
                                + "\" to the library's .deleted folder - move it back to restore it"));
            }
            default -> popupEntry = null;
        }
    }

    private void afterChange(BuildLibrary.Result<?> result, String success) {
        if (!result.ok()) {
            BuildChat.warn("Could not change the build - " + result.error());
        } else if (success != null) {
            BuildChat.info(success);
        }
        reload();
    }

    /** The inline text bar at the bottom for rename / tags / folder. Opened by a click, not a keystroke. */
    private void openEditBar(SchematicStore.Entry entry, EditKind kind) {
        popupEntry = null;
        closeEditBar();
        editEntry = entry;
        editKind = kind;
        SchematicHeader header = entry.summary().header();
        String initial = switch (kind) {
            case RENAME -> entry.displayName();
            case TAGS -> String.join(", ", header.tags());
            case FOLDER -> header.folder();
        };
        String hint = switch (kind) {
            case RENAME -> "New name";
            case TAGS -> "Tags, separated by commas";
            case FOLDER -> "Folder (empty = none)";
        };
        int buttonW = Math.max(this.font.width("Cancel") + 12, 44);
        int fieldW = Math.max(40, contentWidth - 2 * (buttonW + GAP));
        String[] value = {initial};
        int y = footerY;
        editField = SciFiTextField.forRow(innerX, y, fieldW, SBSTheme.SEARCH_HEIGHT, hint, hint, 64,
                () -> value[0], text -> value[0] = text);
        SciFiButton ok = new SciFiButton(innerX + fieldW + GAP, y, buttonW, SBSTheme.SEARCH_HEIGHT,
                Component.literal("OK"), () -> confirmEdit(value[0]));
        SciFiButton cancel = new SciFiButton(innerX + fieldW + GAP + buttonW + GAP, y, buttonW, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Cancel"), this::closeEditBar);
        addRenderableWidget(editField);
        addRenderableWidget(ok);
        addRenderableWidget(cancel);
        editButtons.add(ok);
        editButtons.add(cancel);
        setFocused(editField);
    }

    private void closeEditBar() {
        if (editField != null) {
            removeWidget(editField);
        }
        for (SciFiButton button : editButtons) {
            removeWidget(button);
        }
        editButtons.clear();
        editField = null;
        editEntry = null;
        editKind = null;
    }

    private void confirmEdit(String value) {
        SchematicStore.Entry entry = editEntry;
        EditKind kind = editKind;
        closeEditBar();
        if (entry == null) {
            return;
        }
        SchematicHeader header = entry.summary().header();
        switch (kind) {
            case RENAME -> BuildLibrary.runAsync(store -> {
                store.rename(entry.slug(), value);
                return null;
            }, result -> afterChange(result, "Renamed to \"" + value.trim() + "\""));
            case TAGS -> {
                List<String> tags = Arrays.stream(value.split(","))
                        .map(tag -> tag.trim().toLowerCase(Locale.ROOT).replace("#", ""))
                        .filter(tag -> !tag.isEmpty()).distinct().toList();
                BuildLibrary.runAsync(store -> {
                    store.updateHeader(entry.slug(), header.withTags(tags));
                    return null;
                }, result -> afterChange(result, null));
            }
            case FOLDER -> BuildLibrary.runAsync(store -> {
                store.updateHeader(entry.slug(), header.withFolder(value.trim()));
                return null;
            }, result -> afterChange(result, null));
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // An open popup gets the click first; a click outside it closes it and does nothing else.
        if (popupEntry != null) {
            int w = popupWidth();
            int rowH = popupRowHeight();
            if (event.x() >= popupX && event.x() < popupX + w && event.y() >= popupY + 2
                    && event.y() < popupY + 2 + POPUP_ACTIONS.length * rowH) {
                runPopup(POPUP_ACTIONS[(int) ((event.y() - popupY - 2) / rowH)]);
            } else {
                popupEntry = null;
            }
            return true;
        }
        if (scrollbar.handleClick(event.x(), event.y(), scrollRow, this::setScroll)) {
            return true;
        }
        if (event.button() == 1) {
            for (AbstractButton widget : cardWidgets) {
                if (widget instanceof BuildCard card && card.isMouseOver(event.x(), event.y())) {
                    openPopup(card.entry(), event.x(), event.y());
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (scrollbar.handleDrag(event.y(), this::setScroll)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (scrollbar.release()) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (popupEntry != null) {
            return true;
        }
        if (maxScroll() > 0 && scrollY != 0) {
            setScroll(scrollRow + (scrollY > 0 ? -1 : 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {   // Escape: the popup, then the edit bar, then the screen
            if (popupEntry != null) {
                popupEntry = null;
                return true;
            }
            if (editEntry != null) {
                closeEditBar();
                return true;
            }
        }
        if ((event.key() == 257 || event.key() == 335) && editField != null && getFocused() == editField) {
            confirmEdit(editField.getValue());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        Thumbnails.releaseAll();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        // The popup is drawn after every widget, every frame - drawn last is on top.
        if (popupEntry != null) {
            int w = popupWidth();
            int rowH = popupRowHeight();
            int h = POPUP_ACTIONS.length * rowH + 4;
            g.fill(popupX, popupY, popupX + w, popupY + h, 0xFF000000 | (SBSTheme.CARD_BG & 0xFFFFFF));
            SciFiRender.roundedRect(g, popupX, popupY, w, h, 2, SBSTheme.ACCENT);
            for (int i = 0; i < POPUP_ACTIONS.length; i++) {
                int rowY = popupY + 2 + i * rowH;
                boolean hover = mouseX >= popupX && mouseX < popupX + w && mouseY >= rowY && mouseY < rowY + rowH;
                if (hover) {
                    g.fill(popupX + 1, rowY, popupX + w - 1, rowY + rowH, 0xFF000000 | (SBSTheme.CARD_BG_HOVER & 0xFFFFFF));
                }
                String label = popupLabel(POPUP_ACTIONS[i]);
                int color = POPUP_ACTIONS[i].equals("delete") ? SBSTheme.WARN : SBSTheme.TEXT;
                g.text(this.font, Component.literal(label), popupX + 8, rowY + 3, color);
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = QuickPasteScreen.this.font;
            g.fill(0, 0, QuickPasteScreen.this.width, QuickPasteScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            String title = "Quick Paste" + (all.isEmpty() ? "" : "  •  " + shown.size() + " / " + all.size());
            g.centeredText(font, Component.literal(RowText.fit(font, title, contentWidth)), panelX + panelW / 2,
                    panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            String empty = null;
            if (loading) {
                empty = "Loading the library...";
            } else if (loadError != null) {
                empty = "The library could not be read: " + loadError;
            } else if (all.isEmpty()) {
                empty = "No saved builds yet - //copy, then //save <name>";
            } else if (shown.isEmpty()) {
                empty = "Nothing matches \"" + query + "\"";
            }
            if (empty != null) {
                g.centeredText(font, Component.literal(RowText.fit(font, empty, contentWidth)), panelX + panelW / 2,
                        gridTop + gridHeight / 2 - font.lineHeight / 2, SBSTheme.TEXT_MUTED);
            }
            if (editEntry == null) {
                String footer = "Click: " + (BuildGate.singleplayer() ? "place" : "show as hologram")
                        + "  •  Right-click: favourite, rename, tags, folder, share, delete";
                g.text(font, Component.literal(RowText.fit(font, footer, contentWidth)), innerX,
                        footerY + (SBSTheme.SEARCH_HEIGHT - font.lineHeight) / 2, SBSTheme.TEXT_MUTED);
            }
            scrollbar.set(innerX + contentWidth - SciFiScrollbar.WIDTH, gridTop, gridHeight, rowsTotal(), visibleRows);
            scrollbar.render(g, scrollRow, mouseX, mouseY);
        }
    }
}
