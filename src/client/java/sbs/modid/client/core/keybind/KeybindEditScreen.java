/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.keybind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.KeyCaptureWidget;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiCycleButton;
import sbs.modid.client.ui.component.SciFiDropdown;
import sbs.modid.client.ui.component.SciFiKeybindButton;
import sbs.modid.client.ui.component.SciFiTextField;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.keybind.CommandKeybind;
import sbs.modid.client.core.keybind.CommandKeybindManager;
import sbs.modid.client.core.keybind.IslandCatalog;
import sbs.modid.client.core.keybind.KeybindAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Full editor for one {@link CommandKeybind}: key + modifiers, the conditions (island-name filter +
 * an optional world-area box), and the ordered list of {@link KeybindAction}s the key fires – each
 * with its own <b>Delay (ms)</b> before it runs.
 *
 * <p>Reached from the {@link CommandKeybindsScreen} overview via the row's Edit button.
 *
 * <p>Built row by row from the shared SBS controls, rebuilding whenever the shape changes (an
 * action added / removed / retyped). The action list scrolls by whole actions rather than by pixel,
 * which keeps every row aligned to the same grid the fixed rows use.
 */
public final class KeybindEditScreen extends Screen {

    /** Modifier presets in cycle order, with their display labels. */
    private static final int[] MOD_ORDER = {
            0, CommandKeybind.MOD_SHIFT, CommandKeybind.MOD_CTRL, CommandKeybind.MOD_ALT,
            CommandKeybind.MOD_SHIFT | CommandKeybind.MOD_CTRL,
            CommandKeybind.MOD_SHIFT | CommandKeybind.MOD_ALT,
            CommandKeybind.MOD_CTRL | CommandKeybind.MOD_ALT,
            CommandKeybind.MOD_SHIFT | CommandKeybind.MOD_CTRL | CommandKeybind.MOD_ALT};

    /** Width of the small ▲ / ▼ / ✕ buttons on an action's control row. */
    private static final int ICON_W = 16;
    /** Width of the "+ Add Action" button on the section header row. */
    private static final int ADD_W = 84;

    private final CommandKeybind keybind;
    private final CommandKeybindManager manager = CommandKeybindManager.getInstance();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int rowsTop;
    private int backY;
    private int rowStep;

    /** Top of the scrolling action list, and how many actions fit under it. */
    private int actionsTop;
    private int visibleActions;
    /** Index of the first action shown; the list scrolls by whole actions. */
    private int scroll;

    private final List<AbstractWidget> rowWidgets = new ArrayList<>();

    /**
     * The island / area dropdowns, kept so their open list can be drawn last and so a click or a
     * scroll can be routed to an open one before anything underneath it sees the event. Rebuilt
     * (and so replaced) by every {@link #buildRows()}.
     */
    private final List<SciFiDropdown> dropdowns = new ArrayList<>();

    /** The top-most render pass that draws whichever dropdown list is open. */
    private DropdownOverlay dropdownOverlay;

    /**
     * The island picked in this editing session, for area names that belong to more than one island.
     * Display only – the keybind still stores the single filter string the runtime matches – so it
     * is deliberately not persisted. See {@link #selectedIsland()}.
     */
    private String islandOverride;

    public KeybindEditScreen(CommandKeybind keybind) {
        super(Component.literal("Edit Keybind"));
        this.keybind = keybind;
    }

    @Override
    protected void init() {
        // Taller/wider than before: the action list needs room, and a cramped panel would hide the
        // very rows this screen exists to edit.
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 360, 480);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 300, 460);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentWidth = panelW - pad * 2;
        rowsTop = dividerY + SBSTheme.GAP_AFTER_HEADER;
        backY = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
        rowStep = SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;

        addRenderableOnly(new PanelRenderable());
        dropdownOverlay = new DropdownOverlay();
        buildRows();

        addRenderableWidget(new SciFiButton(innerX, backY, contentWidth, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), this::onBack));
        // Back is added after the rows, so the overlay has to be lifted past it as well.
        raiseDropdownOverlay();
    }

    /**
     * Moves the dropdown overlay to the end of the render order.
     *
     * <p>An open dropdown list is drawn <b>over</b> the rows beneath it, and render order here is
     * insertion order – so the pass that draws it has to be the last one registered. Every
     * {@link #buildRows()} appends fresh row widgets after it, which is why this runs again on each
     * rebuild instead of once at init.
     */
    private void raiseDropdownOverlay() {
        if (dropdownOverlay != null) {
            removeWidget(dropdownOverlay);
            addRenderableWidget(dropdownOverlay);
        }
    }

    private void buildRows() {
        for (AbstractWidget widget : rowWidgets) {
            removeWidget(widget);
        }
        rowWidgets.clear();

        // A keybind always has at least one action to edit – older configs and hand-edited json
        // could otherwise leave this screen with nothing on it.
        if (keybind.actions().isEmpty()) {
            keybind.addAction();
            manager.save();
        }

        int y = rowsTop;
        int half = (contentWidth - 6) / 2;

        // Label
        add(SciFiTextField.forRow(innerX, y, contentWidth, SBSTheme.ENTRY_HEIGHT, "Name", "Optional name...", 48,
                keybind::label, keybind::setLabel));
        y += rowStep;

        // Key (left) + Modifiers (right)
        add(new SciFiKeybindButton(innerX, y, half, SBSTheme.ENTRY_HEIGHT, keybind,
                code -> manager.assignKey(keybind, code)));
        add(new SciFiCycleButton(innerX + half + 6, y, contentWidth - half - 6, SBSTheme.ENTRY_HEIGHT,
                Component.literal("Modifier"),
                () -> Component.literal(modLabel(keybind.modifiers())),
                () -> { keybind.setModifiers(nextMod(keybind.modifiers())); manager.save(); }));
        y += rowStep;

        // Island + area: two dropdowns instead of a free-text field. The island list is seeded
        // and then LEARNED from the areas actually visited, so it cannot drift out of date; the
        // area list narrows to that island. Both are stored as the one filter string the runtime
        // already matches against, so nothing downstream changes.
        int half2 = (contentWidth - 6) / 2;
        dropdowns.clear();
        SciFiDropdown island = new SciFiDropdown(innerX, y, half2, SBSTheme.ENTRY_HEIGHT, "Island",
                IslandCatalog::islands, this::selectedIsland, this::pickIsland);
        SciFiDropdown area = new SciFiDropdown(innerX + half2 + 6, y, contentWidth - half2 - 6,
                SBSTheme.ENTRY_HEIGHT, "Area", () -> IslandCatalog.areasOf(selectedIsland()),
                this::selectedArea, this::pickArea);
        // Only one list may be open at a time – two overlapping popups would be unreadable and the
        // click routing below could not tell which one an option belonged to.
        island.setOnOpen(area::close);
        area.setOnOpen(island::close);
        dropdowns.add(island);
        dropdowns.add(area);
        add(island);
        add(area);
        y += rowStep;

        // Area filter: toggle | set here | radius
        int third = (contentWidth - 12) / 3;
        add(new SciFiToggleButton(innerX, y, third, SBSTheme.ENTRY_HEIGHT, Component.literal("Area"),
                () -> keybind.area().enabled(),
                () -> { keybind.area().setEnabled(!keybind.area().enabled()); manager.save(); }));
        add(new SciFiButton(innerX + third + 6, y, third, SBSTheme.ENTRY_HEIGHT,
                Component.literal("Set here"),
                () -> { keybind.area().captureFromPlayer(); manager.save(); }));
        add(SciFiTextField.forIntRow(innerX + (third + 6) * 2, y, contentWidth - (third + 6) * 2,
                SBSTheme.ENTRY_HEIGHT, "±", 0, 500,
                () -> keybind.area().radius(), r -> { keybind.area().setRadius(r); manager.save(); }, ""));
        y += rowStep;

        // "Add Action" sits on the section header row, right-aligned; the header text itself is
        // drawn by the panel so it does not need a widget.
        add(new SciFiButton(innerX + contentWidth - ADD_W, y, ADD_W, SBSTheme.ENTRY_HEIGHT,
                Component.literal("+ Add Action"),
                () -> {
                    keybind.addAction();
                    manager.save();
                    scroll = Math.max(0, keybind.actions().size() - visibleActions);  // reveal it
                    buildRows();
                }));
        y += rowStep;

        actionsTop = y;
        buildActionRows();
        raiseDropdownOverlay();
    }

    /** The scrolling action list: two rows per action (controls, then its command / messages). */
    private void buildActionRows() {
        List<KeybindAction> actions = keybind.actions();
        int blockH = rowStep * 2;
        int available = backY - actionsTop - 6;
        visibleActions = Math.max(1, available / blockH);
        scroll = clamp(scroll, 0, Math.max(0, actions.size() - visibleActions));

        int y = actionsTop;
        for (int i = scroll; i < actions.size() && i < scroll + visibleActions; i++) {
            buildActionBlock(actions.get(i), i, y);
            y += blockH;
        }
    }

    /**
     * One action: a control row (type | delay | reorder + remove) over its value row.
     *
     * <p>The type button carries the action's 1-based index as its label, which is what states the
     * run order – there is no room for a separate number column inside the panel padding.
     *
     * <p>The delay field is the numeric row control the area radius already uses, so it is
     * digits-only and clamped to {@link KeybindAction#DELAY_MIN}..{@link KeybindAction#DELAY_MAX}
     * by the widget itself – there is no way to type a value the runtime would have to reject.
     */
    private void buildActionBlock(KeybindAction action, int index, int y) {
        int third = (contentWidth - 12) / 3;
        int iconsW = ICON_W * 3 + 6;
        int delayW = contentWidth - third - 6 - iconsW - 6;

        // Type, labelled with the step number
        add(new SciFiCycleButton(innerX, y, third, SBSTheme.ENTRY_HEIGHT,
                Component.literal("#" + (index + 1)),
                () -> Component.literal(action.isCycle() ? "Cycle Messages" : "Command"),
                () -> {
                    action.setType(action.isCycle()
                            ? KeybindAction.TYPE_COMMAND : KeybindAction.TYPE_CYCLE);
                    manager.save();
                    buildRows();   // the value row's meaning changes with the type
                }));

        // Delay (ms)
        add(SciFiTextField.forIntRow(innerX + third + 6, y, delayW, SBSTheme.ENTRY_HEIGHT,
                "Delay", KeybindAction.DELAY_MIN, KeybindAction.DELAY_MAX,
                action::delayMs, v -> { action.setDelayMs(v); manager.save(); }, "ms"));

        // Reorder + remove
        int iconX = innerX + contentWidth - iconsW;
        add(new SciFiButton(iconX, y, ICON_W, SBSTheme.ENTRY_HEIGHT, Component.literal("▲"),
                () -> { keybind.moveAction(action, -1); manager.save(); buildRows(); }));
        add(new SciFiButton(iconX + ICON_W + 3, y, ICON_W, SBSTheme.ENTRY_HEIGHT, Component.literal("▼"),
                () -> { keybind.moveAction(action, 1); manager.save(); buildRows(); }));
        add(new SciFiButton(iconX + (ICON_W + 3) * 2, y, ICON_W, SBSTheme.ENTRY_HEIGHT,
                Component.literal("✕"),
                () -> { keybind.removeAction(action); manager.save(); buildRows(); }));

        // Value: the command, or the "|"-separated message list
        int valueY = y + rowStep;
        if (action.isCycle()) {
            add(SciFiTextField.forRow(innerX, valueY, contentWidth, SBSTheme.ENTRY_HEIGHT,
                    "Messages", "msg one | msg two | ...", 256,
                    action::messagesLine, v -> { action.setMessagesLine(v); manager.save(); }));
        } else {
            add(SciFiTextField.forRow(innerX, valueY, contentWidth, SBSTheme.ENTRY_HEIGHT,
                    "Command", "/command or message", 256,
                    action::command, v -> { action.setCommand(v); manager.save(); }));
        }
    }

    /**
     * The island the stored filter belongs to. The filter is a single string (that is what the
     * runtime matches), so the island is derived from it rather than stored twice and able to
     * disagree with itself – the zone table resolves a zone filter ("Village") back to its
     * island (Hub).
     */
    private String selectedIsland() {
        String filter = keybind.islandFilter();
        if (filter.isEmpty()) {
            return IslandCatalog.ANY_ISLAND;
        }
        // Some area names sit on more than one island ("Colosseum" on both Hub and The Rift,
        // "The Bastion" on Crimson Isle and The Rift). islandForArea can only answer with the first
        // match, so a Rift area would flip the island box back to Hub the moment it was picked.
        // While the editor is open the island the player actually chose is remembered and wins.
        if (islandOverride != null && IslandCatalog.areasOf(islandOverride).contains(filter)) {
            return islandOverride;
        }
        String island = IslandCatalog.islandForArea(filter);
        return island != null ? island
                : filter;   // an area this build does not know an island for: it IS the island
    }

    /** The area of the stored filter, or "(any area)" when the filter names only an island. */
    private String selectedArea() {
        String filter = keybind.islandFilter();
        return filter.isEmpty() || filter.equals(selectedIsland())
                ? IslandCatalog.ANY_AREA : filter;
    }

    private void pickIsland(String island) {
        // Changing island drops the old area: it belonged to the island you just left.
        islandOverride = IslandCatalog.ANY_ISLAND.equals(island) ? null : island;
        keybind.setIslandFilter(IslandCatalog.toFilter(island, IslandCatalog.ANY_AREA));
        manager.save();
        buildRows();
    }

    private void pickArea(String area) {
        keybind.setIslandFilter(IslandCatalog.toFilter(selectedIsland(), area));
        manager.save();
        buildRows();
    }

    private void add(AbstractWidget widget) {
        addRenderableWidget(widget);
        rowWidgets.add(widget);
    }

    /**
     * An open dropdown owns the next click wherever it lands.
     *
     * <p>Widgets are offered a click in insertion order, so without this the row that happens to sit
     * <i>under</i> an open option list – the Name field, an action's controls – would swallow a click
     * aimed at an option, and the list would look unresponsive. Routing open dropdowns first matches
     * what the player sees: that list is painted on top of everything.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // Over a copy: picking an option rebuilds the rows, which replaces the very list being
        // iterated here.
        KeyCaptureWidget.cancelOnOutsideClick(rowWidgets, event.x(), event.y());
        for (SciFiDropdown dropdown : List.copyOf(dropdowns)) {
            if (dropdown.isOpen() && dropdown.mouseClicked(event, doubled)) {
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    /** Escape closes an open list instead of the whole editor – the list is the innermost thing open. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            for (SciFiDropdown dropdown : dropdowns) {
                if (dropdown.isOpen()) {
                    dropdown.close();
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // The key field, while it is listening, owns the wheel wherever the cursor is: the player is
        // binding Wheel Up, and scrolling the action list instead would rebuild the very field that
        // is waiting for the input.
        if (KeyCaptureWidget.claimScroll(rowWidgets, scrollY)) {
            return true;
        }
        // An open list scrolls itself: the action list underneath must not steal the wheel, and
        // rebuilding the rows (which is what that scroll does) would destroy the open dropdown.
        for (SciFiDropdown dropdown : dropdowns) {
            if (dropdown.isOpen() && dropdown.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
                return true;
            }
        }
        int max = Math.max(0, keybind.actions().size() - visibleActions);
        if (scrollY != 0 && max > 0 && mouseY >= actionsTop && mouseY < backY) {
            int next = clamp(scroll - (int) Math.signum(scrollY), 0, max);
            if (next != scroll) {
                scroll = next;
                buildRows();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static int nextMod(int current) {
        for (int i = 0; i < MOD_ORDER.length; i++) {
            if (MOD_ORDER[i] == current) {
                return MOD_ORDER[(i + 1) % MOD_ORDER.length];
            }
        }
        return 0;
    }

    private static String modLabel(int mod) {
        if (mod == 0) {
            return "None";
        }
        StringBuilder sb = new StringBuilder();
        if ((mod & CommandKeybind.MOD_CTRL) != 0) {
            sb.append("Ctrl+");
        }
        if ((mod & CommandKeybind.MOD_SHIFT) != 0) {
            sb.append("Shift+");
        }
        if ((mod & CommandKeybind.MOD_ALT) != 0) {
            sb.append("Alt+");
        }
        return sb.substring(0, sb.length() - 1);
    }

    private void onBack() {
        manager.save();
        Minecraft.getInstance().setScreenAndShow(new CommandKeybindsScreen());
    }

    @Override
    public void removed() {
        manager.save();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * The last render pass on the screen: whichever dropdown list is open, drawn over every row.
     * {@link SciFiDropdown} deliberately does not draw its list in its own pass, because the rows
     * registered after it would paint straight over the top of it.
     *
     * <p>A zero-size widget rather than a plain {@code Renderable} for one reason: {@code Screen}
     * keeps its renderable list private, so the only way to lift this pass back to the end after a
     * rebuild is {@code removeWidget} + re-add, and that is widgets-only. It is inert – no size to
     * hit, and {@code active = false} keeps it out of the focus cycle.
     */
    private final class DropdownOverlay extends AbstractWidget {

        DropdownOverlay() {
            super(0, 0, 0, 0, Component.empty());
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                                float partialTick) {
            for (SciFiDropdown dropdown : dropdowns) {
                dropdown.renderOverlay(g, mouseX, mouseY);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            // Nothing to narrate: the dropdowns themselves carry their own narration.
        }
    }

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = KeybindEditScreen.this.font;
            g.fill(0, 0, KeybindEditScreen.this.width, KeybindEditScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Edit Keybind"), panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            drawActionsHeader(g, font);
            drawActionSeparators(g);

            // Live area/condition hint above the Back button.
            String area = keybind.area().enabled() ? "Area: " + keybind.area().describe() : "";
            if (!area.isEmpty()) {
                g.centeredText(font, Component.literal("§8" + area),
                        panelX + panelW / 2, backY - font.lineHeight - 2, SBSTheme.TEXT_MUTED);
            }
        }

        /** The section header above the list: the count, the total run time, and the scroll state. */
        private void drawActionsHeader(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font) {
            int count = keybind.actions().size();
            int headerY = actionsTop - rowStep + (SBSTheme.ENTRY_HEIGHT - font.lineHeight) / 2;
            g.text(font, Component.literal("§bActions §8" + count), innerX, headerY, SBSTheme.ACCENT);

            // Spelling out the total makes a chain of delays readable at a glance ("runs over 4.5s")
            // instead of forcing the player to add the fields up themselves.
            int total = keybind.totalDelayMs();
            String hint = total > 0 ? "§8runs over " + formatMs(total) : "§8no delay";
            if (count > visibleActions) {
                hint += " §8• " + (scroll + 1) + "-"
                        + Math.min(count, scroll + visibleActions) + " of " + count;
            }
            // Only draw the hint when it cannot collide with the "Actions n" text on its left.
            int hintX = innerX + contentWidth - ADD_W - 6 - font.width(hint);
            if (hintX > innerX + font.width("Actions " + count) + 8) {
                g.text(font, Component.literal(hint), hintX, headerY, SBSTheme.TEXT_MUTED);
            }
        }

        /** A hairline between consecutive actions, so the two rows of one action read as a unit. */
        private void drawActionSeparators(GuiGraphicsExtractor g) {
            int blockH = rowStep * 2;
            int count = keybind.actions().size();
            for (int i = scroll + 1; i < count && i < scroll + visibleActions; i++) {
                int y = actionsTop + (i - scroll) * blockH;
                g.fill(innerX, y - 3, innerX + contentWidth, y - 2, SBSTheme.ACCENT_SOFT);
            }
        }

        /** 250 -> "250ms", 4500 -> "4.5s" – whichever reads faster at that magnitude. */
        private String formatMs(int ms) {
            return ms < 1000 ? ms + "ms"
                    : String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0);
        }
    }
}
