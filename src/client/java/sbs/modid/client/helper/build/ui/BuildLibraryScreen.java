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
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import sbs.modid.client.core.build.io.SchematicShareCode;
import sbs.modid.client.core.build.io.SchematicStore;
import sbs.modid.client.core.build.io.ThumbnailRaster;
import sbs.modid.client.core.build.logic.BuildLibrary;
import sbs.modid.client.core.build.logic.Clipboard;
import sbs.modid.client.core.build.logic.Thumbnails;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.build.model.SchematicTransform;
import sbs.modid.client.core.build.model.StateStrings;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.build.logic.BuildChat;
import sbs.modid.client.helper.build.logic.BuildGate;
import sbs.modid.client.helper.build.logic.Placement;
import sbs.modid.client.helper.build.model.ConfirmState;
import sbs.modid.client.helper.build.model.LibrarySearch;
import sbs.modid.client.helper.build.model.MaterialList;
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
 * The Build Library: every saved build on the left (the same cards as Quick Paste), and a details
 * pane for the selected one - a large preview that turns in 90° steps, what it is, what it is made of,
 * and every action as a visible button: Paste, Rename, Duplicate, Delete, Share code, Favourite, Tags,
 * Folder. Right-click is not needed for anything here.
 *
 * <p><b>The preview is the thumbnail, scaled up, re-drawn from four sides</b> rather than a live 3D
 * model: the ghost renderer draws into the world, not into a GUI rectangle, and the CPU raster already
 * makes a readable picture at any size (see {@code ThumbnailRaster}).
 *
 * <p>{@code ui/AGENTS.md} throughout: cards and buttons are real widgets, the list scrolls with the
 * shared scrollbar, and the layout is measured. When the details pane does not fit beside the list
 * (1280x720 at GUI scale 4 is that case) the details become a second page with a Back button. The
 * search and rename fields never rebuild the screen from their responders.
 */
public final class BuildLibraryScreen extends Screen {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int GAP = 4;
    private static final int CARD_MIN_WIDTH = 92;
    private static final int SCROLLBAR_SPACE = SciFiScrollbar.WIDTH + 2;

    private final SciFiScrollbar scrollbar = new SciFiScrollbar();
    private final Set<String> thumbnailsRequested = new HashSet<>();
    private final List<AbstractWidget> cardWidgets = new ArrayList<>();
    private final List<AbstractWidget> detailWidgets = new ArrayList<>();
    private final ConfirmState deleteConfirm = new ConfirmState();

    private List<SchematicStore.Entry> all = List.of();
    private List<SchematicStore.Entry> shown = List.of();
    private boolean loading = true;
    private String loadError;
    private String query = "";

    private String selectedSlug;
    private Schematic selected;
    private List<MaterialList.Line> materials = List.of();
    private int turns;
    private Identifier previewTexture;
    private boolean detailsPage;

    private enum Edit { RENAME, TAGS, FOLDER }

    private Edit editing;
    private SciFiTextField editField;
    private SciFiButton deleteButton;

    // Layout.
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int contentTop;
    private int contentBottom;
    private boolean sideBySide;
    private int listX;
    private int listW;
    private int gridTop;
    private int gridHeight;
    private int detailsX;
    private int detailsW;
    private int columns;
    private int cardW;
    private int cardH;
    private int pictureSize;
    private int textLines;
    private int visibleRows;
    private int scrollRow;

    public BuildLibraryScreen() {
        super(Component.literal("Build Library"));
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        int availableW = Math.max(1, this.width - SBSTheme.SCREEN_MARGIN * 2);
        int availableH = Math.max(1, this.height - SBSTheme.SCREEN_MARGIN * 2);
        panelW = Math.min(availableW, 660);
        panelH = Math.min(availableH, 380);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentWidth = panelW - pad * 2;
        contentTop = dividerY + SBSTheme.GAP_AFTER_HEADER;
        contentBottom = panelY + panelH - pad;

        // Side by side only when both halves get a usable width and the details their buttons.
        sideBySide = contentWidth >= 2 * 200 + GAP && contentBottom - contentTop >= 190;
        listX = innerX;
        listW = sideBySide ? (contentWidth - GAP) * 45 / 100 : contentWidth;
        detailsX = sideBySide ? listX + listW + GAP : innerX;
        detailsW = sideBySide ? contentWidth - listW - GAP : contentWidth;

        gridTop = contentTop + SBSTheme.SEARCH_HEIGHT + SBSTheme.GAP_AFTER_SEARCH;
        gridHeight = Math.max(20, contentBottom - gridTop);
        int gridW = listW - SCROLLBAR_SPACE;
        columns = Math.max(1, (gridW + GAP) / (CARD_MIN_WIDTH + GAP));
        cardW = (gridW - GAP * (columns - 1)) / columns;
        int lineH = this.font.lineHeight + 1;
        textLines = 3;
        pictureSize = Math.min(cardW - 8, 64);
        while (textLines > 1 && 6 + pictureSize + textLines * lineH > gridHeight) {
            if (pictureSize > 32) {
                pictureSize = Math.max(32, gridHeight - 6 - textLines * lineH);
            } else {
                textLines--;
            }
        }
        pictureSize = Math.max(12, Math.min(pictureSize, gridHeight - 6 - textLines * lineH));
        cardH = 6 + pictureSize + textLines * lineH;
        visibleRows = Math.max(1, (gridHeight + GAP) / (cardH + GAP));

        clearWidgets();
        cardWidgets.clear();
        detailWidgets.clear();
        addRenderableOnly(new PanelRenderable());
        if (!onDetailsPage()) {
            SciFiTextField search = SciFiTextField.forRow(listX, contentTop, listW, SBSTheme.SEARCH_HEIGHT,
                    "Search", "Search: name, #tag or folder", 64, () -> query, text -> {
                        // Swap the cards only; never rebuild the screen from a text responder.
                        query = text;
                        scrollRow = 0;
                        refilter();
                    });
            addRenderableWidget(search);
        }
        if (loading && all.isEmpty()) {
            reload(null);
        } else {
            refilter();
        }
        buildDetails();
    }

    /** The paged layout's second page is showing (never in side-by-side). */
    private boolean onDetailsPage() {
        return !sideBySide && detailsPage && selectedSlug != null;
    }

    private void reload(String select) {
        loading = true;
        Thumbnails.releaseAll();
        thumbnailsRequested.clear();
        BuildLibrary.runAsync(SchematicStore::list, result -> {
            loading = false;
            loadError = result.ok() ? null : result.error();
            all = result.ok() ? result.value().entries() : List.of();
            if (select != null) {
                select(select);
            } else if (selectedSlug != null && all.stream().noneMatch(e -> e.slug().equals(selectedSlug))) {
                clearSelection();
            }
            refilter();
            buildDetails();
        });
    }

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
        for (AbstractWidget card : cardWidgets) {
            removeWidget(card);
        }
        cardWidgets.clear();
        if (onDetailsPage()) {
            return;
        }
        int first = scrollRow * columns;
        int last = Math.min(shown.size(), (scrollRow + visibleRows) * columns);
        for (int i = first; i < last; i++) {
            int slot = i - first;
            SchematicStore.Entry entry = shown.get(i);
            BuildCard card = new BuildCard(listX + (slot % columns) * (cardW + GAP), gridTop + (slot / columns) * (cardH + GAP),
                    cardW, cardH, entry, pictureSize, textLines, () -> {
                        select(entry.slug());
                        if (!sideBySide) {
                            detailsPage = true;
                            rebuildWidgets();   // a click, not a keystroke: the page changes
                        }
                    }, () -> entry.slug().equals(selectedSlug), () -> false, thumbnailsRequested);
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

    // ---------------------------------------------------------------- selection

    private SchematicStore.Entry selectedEntry() {
        for (SchematicStore.Entry entry : all) {
            if (entry.slug().equals(selectedSlug)) {
                return entry;
            }
        }
        return null;
    }

    private void select(String slug) {
        if (slug.equals(selectedSlug) && selected != null) {
            return;
        }
        selectedSlug = slug;
        selected = null;
        materials = List.of();
        turns = 0;
        previewTexture = null;
        deleteConfirm.reset();
        closeEdit();
        BuildLibrary.loadAsync(slug, result -> {
            if (!slug.equals(selectedSlug)) {
                return;   // another build was picked meanwhile
            }
            if (!result.ok()) {
                BuildChat.warn("Could not load \"" + slug + "\" - " + result.error());
                return;
            }
            selected = result.value();
            materials = MaterialList.of(selected.countByState());
            buildDetails();
        });
        buildDetails();
    }

    private void clearSelection() {
        selectedSlug = null;
        selected = null;
        materials = List.of();
        previewTexture = null;
        detailsPage = false;
    }

    /** Re-draws the preview after a quarter turn: the raster of the turned build, off the render thread. */
    private void turn() {
        Schematic build = selected;
        if (build == null) {
            return;
        }
        turns = (turns + 1) % 4;
        if (turns == 0) {
            previewTexture = null;   // back to the saved thumbnail
            return;
        }
        int turn = turns;
        String slug = selectedSlug;
        BuildLibrary.runAsync(store -> {
            Schematic turned = SchematicTransform.rotate(build, turn, SchematicTransform.StateMapper.IDENTITY);
            return ThumbnailRaster.render(turned, Thumbnails.colours(turned.palette()));
        }, result -> {
            if (result.ok() && slug.equals(selectedSlug) && turn == turns) {
                previewTexture = Thumbnails.uploadPreview(result.value());
            }
        });
    }

    // ---------------------------------------------------------------- details

    /** The details pane's widgets. Rebuilt from discrete actions only - never from a text responder. */
    private void buildDetails() {
        for (AbstractWidget widget : detailWidgets) {
            removeWidget(widget);
        }
        detailWidgets.clear();
        deleteButton = null;
        if (!sideBySide && !onDetailsPage()) {
            return;
        }
        SchematicStore.Entry entry = selectedEntry();
        int buttonH = SBSTheme.SEARCH_HEIGHT;
        if (onDetailsPage()) {
            addDetail(new SciFiButton(detailsX, contentTop, font.width("< Back") + 14, buttonH,
                    Component.literal("< Back"), () -> {
                        detailsPage = false;
                        closeEdit();
                        rebuildWidgets();
                    }));
        }
        if (entry == null) {
            return;
        }
        if (editing != null) {
            // The edit bar replaces the buttons: field + OK + Cancel, measured from their labels.
            int okW = font.width("Cancel") + 14;
            int fieldW = Math.max(40, detailsW - 2 * (okW + GAP));
            int y = contentBottom - buttonH;
            String[] value = {editInitial(entry)};
            editField = SciFiTextField.forRow(detailsX, y, fieldW, buttonH, editHint(), editHint(), 64,
                    () -> value[0], text -> value[0] = text);
            addDetail(editField);
            addDetail(new SciFiButton(detailsX + fieldW + GAP, y, okW, buttonH, Component.literal("OK"),
                    () -> confirmEdit(value[0])));
            addDetail(new SciFiButton(detailsX + fieldW + GAP + okW + GAP, y, okW, buttonH, Component.literal("Cancel"),
                    () -> {
                        closeEdit();
                        buildDetails();
                    }));
            setFocused(editField);
            return;
        }
        String[] labels = {"Paste", "Rename", "Duplicate", "Delete", "Share code",
                entry.summary().header().favourite() ? "Unfavourite" : "Favourite", "Tags", "Folder", "Turn 90°"};
        Runnable[] actions = {() -> paste(entry), () -> startEdit(Edit.RENAME), () -> duplicate(entry),
                () -> delete(entry), () -> share(entry), () -> favourite(entry), () -> startEdit(Edit.TAGS),
                () -> startEdit(Edit.FOLDER), this::turn};
        int widest = 0;
        for (String label : labels) {
            widest = Math.max(widest, font.width(label));
        }
        widest = Math.max(widest, font.width("Really delete?"));
        int cols = Math.max(2, Math.min(5, (detailsW + GAP) / (widest + 12 + GAP)));
        int rows = (labels.length + cols - 1) / cols;
        int bw = (detailsW - GAP * (cols - 1)) / cols;
        int top = contentBottom - rows * buttonH - (rows - 1) * 3;
        for (int i = 0; i < labels.length; i++) {
            int x = detailsX + (i % cols) * (bw + GAP);
            int y = top + (i / cols) * (buttonH + 3);
            SciFiButton button = new SciFiButton(x, y, bw, buttonH, Component.literal(RowText.fit(font, labels[i], bw - 6)),
                    actions[i]);
            if (i == 3) {
                deleteButton = button;
            }
            if (i == 8) {
                button.active = selected != null;
            }
            addDetail(button);
        }
    }

    private void addDetail(AbstractWidget widget) {
        detailWidgets.add(widget);
        addRenderableWidget(widget);
    }

    /** Top of the button block, so the preview and info know how far they may reach. */
    private int detailsButtonsTop() {
        int top = contentBottom;
        for (AbstractWidget widget : detailWidgets) {
            if (widget.getY() > contentTop + SBSTheme.SEARCH_HEIGHT) {
                top = Math.min(top, widget.getY());
            }
        }
        return top;
    }

    private String editInitial(SchematicStore.Entry entry) {
        SchematicHeader header = entry.summary().header();
        return switch (editing) {
            case RENAME -> entry.displayName();
            case TAGS -> String.join(", ", header.tags());
            case FOLDER -> header.folder();
        };
    }

    private String editHint() {
        return switch (editing) {
            case RENAME -> "New name";
            case TAGS -> "Tags, separated by commas";
            case FOLDER -> "Folder (empty = none)";
        };
    }

    private void startEdit(Edit what) {
        editing = what;
        buildDetails();
    }

    private void closeEdit() {
        editing = null;
        editField = null;
    }

    private void confirmEdit(String value) {
        SchematicStore.Entry entry = selectedEntry();
        Edit what = editing;
        closeEdit();
        if (entry == null || what == null) {
            buildDetails();
            return;
        }
        SchematicHeader header = entry.summary().header();
        switch (what) {
            case RENAME -> BuildLibrary.runAsync(store -> store.renameUnique(entry.slug(), value), result -> {
                if (result.ok()) {
                    BuildChat.info("Renamed to \"" + result.value() + "\"");
                    selectedSlug = null;
                    reload(SchematicStore.slug(result.value()));
                } else {
                    BuildChat.warn("Could not rename - " + result.error());
                    buildDetails();
                }
            });
            case TAGS -> {
                List<String> tags = Arrays.stream(value.split(","))
                        .map(tag -> tag.trim().toLowerCase(Locale.ROOT).replace("#", ""))
                        .filter(tag -> !tag.isEmpty()).distinct().toList();
                change(store -> store.updateHeader(entry.slug(), header.withTags(tags)), null, entry.slug());
            }
            case FOLDER -> change(store -> store.updateHeader(entry.slug(), header.withFolder(value.trim())), null,
                    entry.slug());
        }
    }

    private interface Change {
        void run(SchematicStore store) throws java.io.IOException;
    }

    private void change(Change change, String success, String reselect) {
        BuildLibrary.runAsync(store -> {
            change.run(store);
            return null;
        }, result -> {
            if (!result.ok()) {
                BuildChat.warn("Could not change the build - " + result.error());
            } else if (success != null) {
                BuildChat.info(success);
            }
            selectedSlug = null;
            reload(reselect);
        });
    }

    private void paste(SchematicStore.Entry entry) {
        if (!ConfigManager.getInstance().get().buildTools.enabled) {
            BuildChat.warn("Build Tools is off - switch it on in /sbs, Quality of Life > Build Tools");
            return;
        }
        Schematic build = selected;
        onClose();
        if (build != null) {
            Clipboard.set(build);
            if (Placement.start(build)) {
                BuildChat.info("Placing \"" + entry.displayName() + "\" - Enter " + (BuildGate.singleplayer() ? "places it"
                        : "pins it") + ", Esc cancels");
            }
        }
    }

    private void duplicate(SchematicStore.Entry entry) {
        BuildLibrary.runAsync(store -> store.duplicate(entry.slug()), result -> {
            if (result.ok()) {
                BuildChat.info("Duplicated as \"" + result.value() + "\"");
                selectedSlug = null;
                reload(SchematicStore.slug(result.value()));
            } else {
                BuildChat.warn("Could not duplicate - " + result.error());
            }
        });
    }

    private void delete(SchematicStore.Entry entry) {
        if (!deleteConfirm.press(System.currentTimeMillis())) {
            return;   // armed: the button now reads "Really delete?" for three seconds
        }
        change(store -> store.delete(entry.slug()), "Deleted \"" + entry.displayName()
                + "\" (kept in the library's .deleted folder)", null);
        clearSelection();
        rebuildWidgets();
    }

    private void share(SchematicStore.Entry entry) {
        Schematic build = selected;
        if (build == null) {
            return;
        }
        try {
            String code = SchematicShareCode.encode(build);
            Minecraft.getInstance().keyboardHandler.setClipboard(code);
            BuildChat.info("Share code for \"" + entry.displayName() + "\" copied (" + (code.length() / 1024 + 1) + " KB)");
        } catch (SchematicShareCode.ShareException tooBig) {
            BuildChat.warn(tooBig.getMessage());
        }
    }

    private void favourite(SchematicStore.Entry entry) {
        SchematicHeader header = entry.summary().header();
        change(store -> store.updateHeader(entry.slug(), header.withFavourite(!header.favourite())), null, entry.slug());
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (!onDetailsPage() && scrollbar.handleClick(event.x(), event.y(), scrollRow, this::setScroll)) {
            return true;
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
        if (!onDetailsPage() && maxScroll() > 0 && scrollY != 0 && mouseX < listX + listW) {
            setScroll(scrollRow + (scrollY > 0 ? -1 : 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256 && editing != null) {
            closeEdit();
            buildDetails();
            return true;
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
        Thumbnails.releasePreview();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- drawing

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = BuildLibraryScreen.this.font;
            g.fill(0, 0, BuildLibraryScreen.this.width, BuildLibraryScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            String title = "Build Library" + (all.isEmpty() ? "" : "  •  " + shown.size() + " / " + all.size());
            g.centeredText(font, Component.literal(RowText.fit(font, title, contentWidth)), panelX + panelW / 2,
                    panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            if (deleteButton != null) {
                // The two-step delete: the caption says which step it is on, and disarms after 3 s.
                deleteButton.setMessage(Component.literal(RowText.fit(font,
                        deleteConfirm.label("Delete", System.currentTimeMillis()), deleteButton.getWidth() - 6)));
            }
            if (!onDetailsPage()) {
                drawListState(g, font);
                scrollbar.set(listX + listW - SciFiScrollbar.WIDTH, gridTop, gridHeight, rowsTotal(), visibleRows);
                scrollbar.render(g, scrollRow, mouseX, mouseY);
            }
            if (sideBySide || onDetailsPage()) {
                drawDetails(g, font);
            }
        }

        private void drawListState(GuiGraphicsExtractor g, Font font) {
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
                g.text(font, Component.literal(RowText.fit(font, empty, listW)), listX, gridTop + 4, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawDetails(GuiGraphicsExtractor g, Font font) {
            SchematicStore.Entry entry = selectedEntry();
            int top = onDetailsPage() ? contentTop + SBSTheme.SEARCH_HEIGHT + 4 : contentTop;
            int bottom = detailsButtonsTop() - 4;
            if (entry == null) {
                g.text(font, Component.literal(RowText.fit(font, "Pick a build on the left to see it here", detailsW)),
                        detailsX, top + 4, SBSTheme.TEXT_MUTED);
                return;
            }
            // The preview: as large as the space above the buttons allows, square; the info beside it
            // when at least 110 px are left, else below it.
            int lineH = font.lineHeight + 2;
            int space = Math.max(16, bottom - top);
            int preview = Math.min(Math.min(space, 160), Math.max(16, detailsW - 110));
            boolean infoBeside = detailsW - preview - GAP >= 110;
            if (!infoBeside) {
                preview = Math.min(preview, Math.max(16, space - 4 * lineH));
            }
            Identifier texture = previewTexture != null ? previewTexture
                    : Thumbnails.texture(entry.slug(), entry.file().resolveSibling(entry.slug() + SchematicStore.THUMBNAIL_EXTENSION));
            g.fill(detailsX, top, detailsX + preview, top + preview, 0x30FFFFFF);
            if (texture != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, texture, detailsX, top, 0F, 0F, preview, preview, 128, 128, 128, 128,
                        0xFFFFFFFF);
            }
            if (turns != 0) {
                g.text(font, Component.literal(turns * 90 + "°"), detailsX + 2, top + 2, SBSTheme.TEXT_MUTED);
            }
            int infoX = infoBeside ? detailsX + preview + GAP + 2 : detailsX;
            int infoY = infoBeside ? top : top + preview + 4;
            int infoW = infoBeside ? detailsW - preview - GAP - 2 : detailsW;
            SchematicHeader header = entry.summary().header();
            List<String> lines = new ArrayList<>();
            lines.add((header.favourite() ? "★ " : "") + entry.displayName());
            lines.add(entry.summary().sizeLabel() + String.format(Locale.ROOT, "  •  %,d blocks", entry.summary().blocks()));
            lines.add(header.source().label() + (header.folder().isEmpty() ? "" : "  •  folder " + header.folder()));
            if (!header.tags().isEmpty()) {
                lines.add("#" + String.join(" #", header.tags()));
            }
            lines.add("Created " + DATE.format(Instant.ofEpochMilli(header.createdAt())));
            lines.add("Modified " + DATE.format(Instant.ofEpochMilli(entry.modifiedAt())));
            if (!materials.isEmpty()) {
                lines.add("Made of:");
                for (MaterialList.Line line : materials.subList(0, Math.min(5, materials.size()))) {
                    lines.add("  " + StateStrings.displayName(line.item()) + String.format(Locale.ROOT, " ×%,d", line.count()));
                }
            } else if (selected == null) {
                lines.add("Loading...");
            }
            int y = infoY;
            for (int i = 0; i < lines.size() && y + font.lineHeight <= bottom; i++) {
                int color = i == 0 ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT;
                g.text(font, Component.literal(RowText.fit(font, lines.get(i), infoW)), infoX, y, color);
                y += lineH;
            }
        }
    }
}
