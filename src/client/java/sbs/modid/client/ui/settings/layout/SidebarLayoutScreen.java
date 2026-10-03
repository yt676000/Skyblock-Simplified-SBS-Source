/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings.layout;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.module.ModuleCategory;
import sbs.modid.client.core.module.ModuleManager;
import sbs.modid.client.ui.component.ReorderableList;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiDropdown;
import sbs.modid.client.ui.component.SciFiTextField;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.settings.ConfirmClick;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The sidebar layout editor: reorder categories, move pages within and between them, make, rename and
 * delete your own categories, or go back to the built-in layout.
 *
 * <p><b>Three columns, all {@link ReorderableList}.</b> Categories on the left (drag to reorder);
 * the pages of the chosen category in the middle (drag to reorder); the pages of a second category,
 * picked from the dropdown, on the right - dragging a page between the two middle columns moves it
 * to the other category. Every drag has the list's keyboard path too (Shift+arrows, Enter sends
 * across), and nothing changes on a plain click.
 *
 * <p><b>Nothing is saved until Save.</b> The screen edits a copy; Cancel or Escape throws it away.
 * The text field only stores what is typed - no widget rebuild on a keystroke, which is what would
 * throw the focus away mid-word - and a name is applied by the New / Rename buttons.
 *
 * <p>Sized for 1280x720 at GUI scale 4 (320x180 GUI pixels): compact constants of its own, since the
 * shared panel padding alone would eat a third of that height.
 */
public final class SidebarLayoutScreen extends Screen {

    private static final int MARGIN = 4;
    private static final int PAD = 6;
    private static final int HEADER_H = 16;
    private static final int CONTROL_H = 14;
    private static final int GAP = 4;
    private static final int LABEL_H = 10;

    private final Screen parent;
    private final Map<String, String> pageNames = new HashMap<>();
    private final SidebarLayout working;
    private final List<SidebarLayoutResolver.ModuleRef> live;

    private final ReorderableList<String> categories = new ReorderableList<>("Categories", new CategoryAdapter());
    private final ReorderableList<String> pages = new ReorderableList<>("Pages", new PageAdapter());
    private final ReorderableList<String> otherPages = new ReorderableList<>("Other", new PageAdapter());

    private final ConfirmClick resetConfirm = new ConfirmClick();
    private final ConfirmClick deleteConfirm = new ConfirmClick();

    private String shownId;
    private String otherId;
    private String typedName = "";
    private boolean wired;

    private SciFiDropdown otherPicker;
    private SciFiButton resetButton;
    private SciFiButton deleteButton;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listTop;
    private int[] columnX = new int[3];

    public SidebarLayoutScreen(Screen parent) {
        super(Component.literal("Sidebar Layout"));
        this.parent = parent;
        this.live = liveModules(pageNames);
        SidebarLayout stored = SidebarLayout.decode(ConfigManager.getInstance().get().gui.sidebarLayout);
        this.working = withEveryPage(stored == null ? SidebarLayoutResolver.defaults(live) : stored, live);
    }

    /** The live modules in default sidebar order, and their display names. */
    static List<SidebarLayoutResolver.ModuleRef> liveModules(Map<String, String> names) {
        List<SidebarLayoutResolver.ModuleRef> refs = new ArrayList<>();
        for (ModuleCategory category : ModuleManager.getInstance().getCategories()) {
            refs.add(new SidebarLayoutResolver.ModuleRef(category.id(), category.group(), category.subgroup()));
            if (names != null) {
                names.put(category.id(), category.displayName().getString());
            }
        }
        return refs;
    }

    /**
     * The stored layout with every live page in it and every dead id gone - so the editor shows
     * exactly what the sidebar does, and a save writes a complete layout (which is also what clears
     * the "new" marks).
     */
    static SidebarLayout withEveryPage(SidebarLayout layout, List<SidebarLayoutResolver.ModuleRef> live) {
        SidebarLayout out = new SidebarLayout();
        for (SidebarLayoutResolver.Resolved resolved : SidebarLayoutResolver.resolve(layout, live)) {
            SidebarLayout.Category copy = new SidebarLayout.Category(resolved.category().id,
                    resolved.category().builtin, resolved.category().name);
            for (SidebarLayoutResolver.ModuleRef ref : resolved.modules()) {
                copy.modules.add(ref.id());
            }
            out.categories.add(copy);
        }
        return out;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        int available = Math.max(1, this.width - MARGIN * 2);
        panelW = Math.min(available, 520);
        panelH = Math.min(Math.max(1, this.height - MARGIN * 2), 320);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int innerX = panelX + PAD;
        int innerW = panelW - PAD * 2;
        int buttonsY = panelY + panelH - PAD - CONTROL_H;
        int fieldY = buttonsY - GAP - CONTROL_H;
        listTop = panelY + HEADER_H + LABEL_H + CONTROL_H + GAP;
        int listH = Math.max(30, fieldY - GAP - listTop);
        int columnW = (innerW - GAP * 2) / 3;
        for (int i = 0; i < 3; i++) {
            columnX[i] = innerX + i * (columnW + GAP);
        }
        categories.setBounds(columnX[0], listTop, columnW, listH);
        pages.setBounds(columnX[1], listTop, columnW, listH);
        otherPages.setBounds(columnX[2], listTop, columnW, listH);

        if (!wired) {
            wired = true;
            pages.setPeer(otherPages);
            otherPages.setPeer(pages);
            categories.setOnChange(this::categoriesChanged);
            pages.setOnChange(this::pagesChanged);
            otherPages.setOnChange(this::pagesChanged);
            categories.setItems(categoryIds());
            categories.setFocused(true);
            shownId = working.categories.get(0).id;
            otherId = working.categories.size() > 1 ? working.categories.get(1).id : null;
            loadPages();
        }

        clearWidgets();
        addRenderableOnly(new Chrome());

        int pickerY = panelY + HEADER_H + LABEL_H;
        otherPicker = new SciFiDropdown(columnX[2], pickerY, columnW, CONTROL_H, "To",
                this::categoryLabels, () -> labelOf(otherId), this::pickOther);
        addRenderableWidget(otherPicker);

        int fieldW = innerW - (columnW / 2 + GAP) * 3;
        addRenderableWidget(SciFiTextField.forRow(innerX, fieldY, fieldW, CONTROL_H, "Name",
                "Category name", SidebarLayout.MAX_NAME, () -> typedName, text -> typedName = text));
        int small = columnW / 2;
        int bx = innerX + fieldW + GAP;
        addRenderableWidget(new SciFiButton(bx, fieldY, small, CONTROL_H,
                Component.literal("+ New"), this::onNew));
        addRenderableWidget(new SciFiButton(bx + small + GAP, fieldY, small, CONTROL_H,
                Component.literal("Rename"), this::onRename));
        deleteButton = new SciFiButton(bx + (small + GAP) * 2, fieldY, small, CONTROL_H,
                Component.literal("Delete"), this::onDelete);
        addRenderableWidget(deleteButton);

        int third = (innerW - GAP * 2) / 3;
        resetButton = new SciFiButton(innerX, buttonsY, third, CONTROL_H,
                Component.literal("Reset"), this::onReset);
        addRenderableWidget(resetButton);
        addRenderableWidget(new SciFiButton(innerX + third + GAP, buttonsY, third, CONTROL_H,
                Component.literal("Cancel"), this::onClose));
        addRenderableWidget(new SciFiButton(innerX + (third + GAP) * 2, buttonsY,
                innerW - (third + GAP) * 2, CONTROL_H, Component.literal("Save"), this::onSave));
        addRenderableOnly(new Overlay());
    }

    // ------------------------------------------------------------------ state

    private List<String> categoryIds() {
        List<String> ids = new ArrayList<>();
        for (SidebarLayout.Category category : working.categories) {
            ids.add(category.id);
        }
        return ids;
    }

    /** Dropdown labels: display names, numbered where two custom categories share one. */
    private List<String> categoryLabels() {
        List<String> labels = new ArrayList<>();
        for (SidebarLayout.Category category : working.categories) {
            labels.add(labelOf(category.id));
        }
        return labels;
    }

    private String labelOf(String id) {
        SidebarLayout.Category category = id == null ? null : working.find(id);
        if (category == null) {
            return "-";
        }
        int same = 0;
        for (SidebarLayout.Category other : working.categories) {
            if (other == category) {
                break;
            }
            if (other.displayName().equals(category.displayName())) {
                same++;
            }
        }
        return same == 0 ? category.displayName() : category.displayName() + " (" + (same + 1) + ")";
    }

    private void pickOther(String label) {
        for (SidebarLayout.Category category : working.categories) {
            if (labelOf(category.id).equals(label)) {
                otherId = category.id;
                loadPages();
                return;
            }
        }
    }

    private void loadPages() {
        SidebarLayout.Category shown = working.find(shownId);
        pages.setItems(shown == null ? List.of() : shown.modules);
        SidebarLayout.Category other = otherId == null || otherId.equals(shownId) ? null : working.find(otherId);
        otherPages.setItems(other == null ? List.of() : other.modules);
    }

    private void categoriesChanged() {
        List<SidebarLayout.Category> reordered = new ArrayList<>();
        for (String id : categories.items()) {
            SidebarLayout.Category category = working.find(id);
            if (category != null) {
                reordered.add(category);
            }
        }
        working.categories.clear();
        working.categories.addAll(reordered);
    }

    private void pagesChanged() {
        SidebarLayout.Category shown = working.find(shownId);
        if (shown != null) {
            shown.modules.clear();
            shown.modules.addAll(pages.items());
        }
        SidebarLayout.Category other = otherId == null || otherId.equals(shownId) ? null : working.find(otherId);
        if (other != null) {
            other.modules.clear();
            other.modules.addAll(otherPages.items());
        } else if (shown != null && !otherPages.items().isEmpty()) {
            // No second category chosen: a page dropped on the empty column goes straight back.
            shown.modules.addAll(otherPages.items());
            otherPages.setItems(List.of());
            pages.setItems(shown.modules);
        }
    }

    /** The category list's selection drives the middle column. Polled, as the list has no callback. */
    private void followSelection() {
        String picked = categories.selection();
        if (picked != null && !picked.equals(shownId)) {
            shownId = picked;
            loadPages();
        }
    }

    // ------------------------------------------------------------------ actions

    private void onNew() {
        String name = SidebarLayout.cleanName(typedName);
        SidebarLayout.Category category = SidebarLayout.newCustom(name.isEmpty() ? "New Category" : name);
        if (working.categories.size() >= SidebarLayout.MAX_CATEGORIES) {
            return;
        }
        working.categories.add(category);
        categories.setItems(categoryIds());
        otherId = category.id;
        loadPages();
    }

    private void onRename() {
        SidebarLayout.Category category = working.find(shownId);
        String name = SidebarLayout.cleanName(typedName);
        if (category != null && category.custom() && !name.isEmpty()) {
            category.name = name;
        }
    }

    /** Deleting a custom category sends its pages home to their default groups. Two clicks. */
    private void onDelete() {
        SidebarLayout.Category category = working.find(shownId);
        if (category == null || !category.custom() || !deleteConfirm.click()) {
            return;
        }
        working.categories.remove(category);
        SidebarLayout rebuilt = withEveryPage(working, live);
        working.categories.clear();
        working.categories.addAll(rebuilt.categories);
        categories.setItems(categoryIds());
        shownId = working.categories.get(0).id;
        if (category.id.equals(otherId)) {
            otherId = working.categories.size() > 1 ? working.categories.get(1).id : null;
        }
        loadPages();
    }

    /** Back to the built-in layout: clears the stored one. Two clicks, then the screen closes. */
    private void onReset() {
        if (!resetConfirm.click()) {
            return;
        }
        ConfigManager.getInstance().get().gui.sidebarLayout = "";
        ConfigManager.getInstance().save();
        onClose();
    }

    private void onSave() {
        pagesChanged();
        ConfigManager.getInstance().get().gui.sidebarLayout = working.encode();
        ConfigManager.getInstance().save();
        onClose();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (otherPicker != null && otherPicker.isOpen()) {
            return otherPicker.mouseClicked(event, doubled);
        }
        if (categories.mouseClicked(event.x(), event.y(), event.button())
                || pages.mouseClicked(event.x(), event.y(), event.button())
                || otherPages.mouseClicked(event.x(), event.y(), event.button())) {
            categories.setFocused(categories.contains(event.x(), event.y()));
            pages.setFocused(pages.contains(event.x(), event.y()));
            otherPages.setFocused(otherPages.contains(event.x(), event.y()));
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (categories.mouseDragged(event.x(), event.y()) || pages.mouseDragged(event.x(), event.y())
                || otherPages.mouseDragged(event.x(), event.y())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = categories.mouseReleased(event.x(), event.y());
        handled |= pages.mouseReleased(event.x(), event.y());
        handled |= otherPages.mouseReleased(event.x(), event.y());
        return handled || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (otherPicker != null && otherPicker.isOpen()) {
            return otherPicker.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (categories.mouseScrolled(mouseX, mouseY, scrollY) || pages.mouseScrolled(mouseX, mouseY, scrollY)
                || otherPages.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (otherPicker != null && otherPicker.isOpen() && event.key() == 256) {
            otherPicker.close();
            return true;
        }
        if (getFocused() instanceof SciFiTextField field && field.isFocused()) {
            return super.keyPressed(event);
        }
        if (categories.keyPressed(event.key(), event.modifiers())
                || pages.keyPressed(event.key(), event.modifiers())
                || otherPages.keyPressed(event.key(), event.modifiers())) {
            return true;
        }
        return super.keyPressed(event);
    }

    // ------------------------------------------------------------------ drawing

    private final class Chrome implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            followSelection();
            resetButton.setMessage(Component.literal(resetConfirm.state("§cClick again!", "Reset to default")));
            SidebarLayout.Category shown = working.find(shownId);
            deleteButton.active = shown != null && shown.custom();
            deleteButton.setMessage(Component.literal(deleteConfirm.state("§cSure?", "Delete")));

            var font = SidebarLayoutScreen.this.font;
            g.fill(0, 0, width, height, SBSTheme.BG_TINT);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            g.centeredText(font, Component.literal("Sidebar Layout"), panelX + panelW / 2,
                    panelY + (HEADER_H - font.lineHeight) / 2, SBSTheme.ACCENT_BRIGHT);

            int headingY = panelY + HEADER_H;
            int columnW = (panelW - PAD * 2 - GAP * 2) / 3;
            g.text(font, Component.literal(RowText.fit(font, "Categories", columnW)), columnX[0], headingY,
                    SBSTheme.TEXT_MUTED, false);
            g.text(font, Component.literal(RowText.fit(font, "In: " + labelOf(shownId), columnW)), columnX[1],
                    headingY, SBSTheme.TEXT_MUTED, false);
            g.text(font, Component.literal(RowText.fit(font, "Drag pages across", columnW)), columnX[2],
                    headingY, SBSTheme.TEXT_MUTED, false);
            g.text(font, Component.literal(RowText.fit(font, "Shift+↑↓ moves", columnW)), columnX[1],
                    headingY + LABEL_H, SBSTheme.TEXT_MUTED, false);

            categories.render(g, mouseX, mouseY);
            pages.render(g, mouseX, mouseY);
            otherPages.render(g, mouseX, mouseY);
        }
    }

    /** Drawn last: the dragged entry and the open dropdown list sit above everything else. */
    private final class Overlay implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            ReorderableList.renderDrag(g, mouseX, mouseY);
            if (otherPicker != null) {
                otherPicker.renderOverlay(g, mouseX, mouseY);
            }
        }
    }

    private final class CategoryAdapter implements ReorderableList.Adapter<String> {
        @Override
        public String label(String id) {
            return labelOf(id);
        }

        @Override
        public String note(String id) {
            SidebarLayout.Category category = working.find(id);
            return category == null ? "" : String.valueOf(category.modules.size());
        }

        @Override
        public int color(String id) {
            SidebarLayout.Category category = working.find(id);
            return category != null && category.custom() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT;
        }
    }

    private final class PageAdapter implements ReorderableList.Adapter<String> {
        @Override
        public String label(String id) {
            return pageNames.getOrDefault(id, id);
        }
    }
}
