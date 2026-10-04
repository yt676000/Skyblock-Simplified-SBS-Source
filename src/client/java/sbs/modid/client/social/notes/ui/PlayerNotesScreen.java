/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.notes.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.social.notes.logic.PlayerLookup;
import sbs.modid.client.social.notes.logic.PlayerNotesStore;
import sbs.modid.client.social.notes.model.NoteTag;
import sbs.modid.client.social.notes.model.PlayerNote;
import sbs.modid.client.social.notes.model.PlayerNoteBook;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiSegmentedSwitch;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.settings.SettingRowList;
import sbs.modid.client.ui.theme.SBSTheme;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /sbs notes}: every player note, searchable by name or text and filterable by tag, each one
 * editable in place.
 *
 * <p><b>Layout.</b> A filter switch (All / Trusted / Neutral / Avoid - four values, so a segmented
 * switch) and the search field share a line when both fit and stack when they do not. The search
 * field doubles as the add field: type a name nobody has a note for and the Add button beside it
 * notes that player. Below is a {@link SettingRowList}, the shared row list with the shared
 * scrollbar, three rows per note: the tag switch labelled with the player's name, the note text,
 * and a delete button that asks twice. At 1280x720 on GUI scale 4 (320x180) that is one note at a
 * time, which is why the search and the filter exist; the panel is sized from the viewport, never to
 * a minimum.
 *
 * <p><b>No rebuild from a text field.</b> The note field's responder saves and nothing else; the
 * search field sits outside the row list, so the rows it rebuilds never include the field being
 * typed in. Rows are rebuilt only by discrete actions: the filter, the tag switch, add and delete.
 */
public final class PlayerNotesScreen extends Screen {

    private static final int PREFERRED_W = 460;
    private static final int PREFERRED_H = 340;
    private static final int GAP = 6;
    private static final int MIN_SEARCH_W = 90;
    private static final List<String> FILTERS = List.of("All", "Trusted", "Neutral", "Avoid");
    private static final List<String> TAGS = List.of(
            NoteTag.TRUSTED.label(), NoteTag.NEUTRAL.label(), NoteTag.AVOID.label());
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final String initialQuery;

    private final SettingRowList rowList = new SettingRowList(new SettingRowList.WidgetSink() {
        @Override
        public void add(AbstractWidget widget) {
            addRenderableWidget(widget);
        }

        @Override
        public void remove(AbstractWidget widget) {
            removeWidget(widget);
        }
    });

    private EditBox search;
    private SciFiButton addButton;
    /** 0 = all, otherwise {@code NoteTag.values()} order + 1 (Trusted, Neutral, Avoid). */
    private int filter;
    /** The entry whose delete button has been pressed once; the second press deletes it. */
    private PlayerNote pendingDelete;
    private String status = "";

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int dividerY;
    private int searchX;
    private int searchY;
    private int searchW;
    private int footerY;

    public PlayerNotesScreen(String query) {
        super(Component.literal("Player Notes"));
        this.initialQuery = query == null ? "" : query.trim();
    }

    @Override
    protected void init() {
        // A small margin on a small window: at 320x180 the default 20 px would cost two rows.
        int margin = Math.min(SBSTheme.SCREEN_MARGIN, Math.max(4, Math.min(this.width, this.height) / 24));
        int availableW = Math.max(1, this.width - margin * 2);
        int availableH = Math.max(1, this.height - margin * 2);
        panelW = Math.min(availableW, PREFERRED_W);
        panelH = Math.min(availableH, PREFERRED_H);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = Math.min(SBSTheme.PANEL_PADDING, Math.max(4, panelW / 30));
        innerX = panelX + pad;
        contentW = Math.max(1, panelW - pad * 2);
        int headerH = Math.min(SBSTheme.HEADER_HEIGHT, this.font.lineHeight + 10);
        dividerY = panelY + headerH;

        // Backdrop first: registered after the widgets it would be a lid, not a background.
        addRenderableOnly(new PanelRenderable());

        int rowH = SBSTheme.SEARCH_HEIGHT;
        int rowY = dividerY + Math.min(SBSTheme.GAP_AFTER_HEADER, 4);
        int filterW = SciFiSegmentedSwitch.widthFor(FILTERS);
        addRenderableWidget(new SciFiSegmentedSwitch(innerX, rowY, rowH, FILTERS,
                () -> filter, index -> {
                    filter = index;
                    pendingDelete = null;
                    rebuildRows();
                }));

        String addLabel = "Add";
        int addW = this.font.width(addLabel) + 16;
        int beside = contentW - filterW - GAP;
        if (beside >= MIN_SEARCH_W + GAP + addW) {
            searchX = innerX + filterW + GAP;
            searchY = rowY;
            searchW = beside - GAP - addW;
        } else {
            rowY += rowH + 4;
            searchX = innerX;
            searchY = rowY;
            searchW = Math.max(1, contentW - GAP - addW);
        }
        search = new EditBox(this.font, searchX + GAP, searchY + (rowH - this.font.lineHeight) / 2,
                Math.max(1, searchW - GAP * 2), this.font.lineHeight, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(40);
        search.setTextColor(SBSTheme.TEXT);
        search.setHint(Component.literal("Search, or a name to add..."));
        search.setValue(initialQuery);
        // Rebuilds the row list, never this field: the field is not one of the list's widgets.
        search.setResponder(query -> {
            pendingDelete = null;
            status = "";
            rebuildRows();
        });
        addRenderableWidget(search);
        setInitialFocus(search);

        addButton = new SciFiButton(searchX + searchW + GAP, searchY, addW, rowH,
                Component.literal(addLabel), this::addFromSearch);
        addRenderableWidget(addButton);

        footerY = panelY + panelH - pad - this.font.lineHeight;
        int listTop = searchY + rowH + 5;
        rowList.setBounds(innerX, listTop, contentW, Math.max(listTop + 1, footerY - 4));
        rowList.setRightGutter(0);   // no favourite stars on this screen
        rebuildRows();
    }

    // ------------------------------------------------------------------ rows

    private void rebuildRows() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<SettingRow> rows = new ArrayList<>();
        int noteColumns = Math.max(6, (contentW - this.font.width("Note") - 40) / Math.max(1,
                this.font.width("0")));
        for (PlayerNote entry : PlayerNotesStore.getInstance().book().all()) {
            if (!passesFilter(entry) || !matches(entry, query)) {
                continue;
            }
            rows.add(tagRow(entry));
            rows.add(SettingRow.valueField("Note", "what happened?", PlayerNoteBook.MAX_NOTE_LENGTH,
                            noteColumns, () -> entry.note,
                            text -> PlayerNotesStore.getInstance().edit(entry, null, text))
                    .describe("Your note about " + entry.name + ". Saved as you type. Stored only on "
                            + "this computer."));
            rows.add(deleteRow(entry));
        }
        if (rows.isEmpty()) {
            rows.add(SettingRow.label(PlayerNotesStore.getInstance().book().size() == 0
                    ? "No notes yet - type a name above and press Add, or /sbs note <name> <text>"
                    : "No note matches this search or filter."));
        }
        rowList.rebuild(rows);
        updateAddButton();
    }

    private SettingRow tagRow(PlayerNote entry) {
        StringBuilder label = new StringBuilder(entry.name);
        if (entry.nameStale) {
            label.append(" (old name)");
        } else if (!entry.formerNames.isEmpty()) {
            label.append(" (was ").append(entry.formerNames.get(entry.formerNames.size() - 1)).append(')');
        }
        String detail = "Tag for " + entry.name + ": Avoid warns you and can offer a kick button, "
                + "Trusted is a reminder you would take them again, Neutral is just a note. Noted "
                + DATE.format(Instant.ofEpochMilli(entry.created)) + ", changed "
                + DATE.format(Instant.ofEpochMilli(entry.updated)) + "."
                + (entry.uuid == null ? " Matched by name until they are seen in game." : "")
                + (entry.formerNames.isEmpty() ? "" : " Earlier names: "
                + String.join(", ", entry.formerNames) + ".");
        return SettingRow.segmented(label.toString(), TAGS,
                        () -> entry.tag.ordinal(),
                        index -> {
                            PlayerNotesStore.getInstance().edit(entry, NoteTag.values()[index], null);
                            // A tag change can take the entry out of the current filter.
                            if (filter != 0) {
                                rebuildRows();
                            }
                        })
                .describe(detail);
    }

    private SettingRow deleteRow(PlayerNote entry) {
        boolean armed = pendingDelete == entry;
        return SettingRow.button(armed ? "Click again to delete " + entry.name : "Delete " + entry.name,
                        () -> {
                            if (pendingDelete == entry) {
                                PlayerNotesStore.getInstance().remove(entry);
                                pendingDelete = null;
                                status = "Deleted the note about " + entry.name + ".";
                            } else {
                                pendingDelete = entry;
                            }
                            rebuildRows();
                        })
                .describe("Deletes the note about " + entry.name + ". Asks for a second click first.");
    }

    private boolean passesFilter(PlayerNote entry) {
        return filter == 0 || entry.tag.ordinal() == filter - 1;
    }

    private static boolean matches(PlayerNote entry, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (entry.name.toLowerCase(Locale.ROOT).contains(query)
                || entry.note.toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (String former : entry.formerNames) {
            if (former.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ add

    /** The search text as a name to add, or {@code null} when it is not one or already noted. */
    private String candidateName() {
        String text = search == null ? "" : search.getValue().trim();
        if (!PlayerNoteBook.isValidName(text)) {
            return null;
        }
        return PlayerNotesStore.getInstance().book().byName(text) == null ? text : null;
    }

    private void updateAddButton() {
        if (addButton != null) {
            addButton.active = candidateName() != null;
        }
    }

    private void addFromSearch() {
        String name = candidateName();
        if (name == null) {
            status = PlayerNoteBook.isValidName(search.getValue().trim())
                    ? "That player already has a note." : "Type a Minecraft name (1-16 letters, digits or _).";
            return;
        }
        PlayerNotesStore.getInstance().put(name, PlayerLookup.uuidOf(name), null, "");
        filter = 0;
        pendingDelete = null;
        status = "Added " + name + " - set the tag and write the note below.";
        rebuildRows();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return rowList.mouseClicked(event, doubled) || super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return rowList.mouseDragged(event.y()) || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return rowList.mouseReleased() || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return rowList.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        rowList.syncAvailability();
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        // After super, not as a registered renderable: a rebuild appends its rows after anything
        // registered at init, so only this is reliably drawn over them.
        rowList.renderScrollbar(g, mouseX, mouseY);
        rowList.renderDropdownOverlay(g, mouseX, mouseY);
        rowList.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    public void removed() {
        PlayerNotesStore.getInstance().flush();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ drawing

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = PlayerNotesScreen.this.font;
            g.fill(0, 0, PlayerNotesScreen.this.width, PlayerNotesScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (dividerY - panelY - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Player Notes"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            g.fill(innerX, dividerY, innerX + contentW, dividerY + 1, SBSTheme.ACCENT);

            SciFiRender.roundedRectWithBorder(g, searchX, searchY, searchW, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    search.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            // Footer: the status of the last action, else the privacy line. Cut to the width.
            String footer = !status.isEmpty() ? status
                    : PlayerNotesStore.getInstance().book().size() + " note(s) - private, stored only "
                    + "on this computer, never sent or shared";
            g.text(font, Component.literal(RowText.fit(font, footer, contentW)), innerX, footerY,
                    SBSTheme.TEXT_MUTED);
        }
    }
}
