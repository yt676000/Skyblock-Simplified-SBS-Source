/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.input.MouseButtonEvent;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.core.mixin.AbstractRecipeBookScreenAccessor;
import sbs.modid.client.helper.inventory.logic.InventoryWindowGeometry;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.RecipeBookButtonHolder;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.window.EdgeSnap;
import sbs.modid.client.ui.window.WindowMemory;

/**
 * Inventory Window: the inventory screen - or every plain container, per the scope setting - gets a
 * title bar above its panel to drag it anywhere and fold it away, like the mod's floating windows.
 *
 * <p>The position <b>is</b> the screen's own {@code leftPos}/{@code topPos}. Vanilla hit-tests every
 * slot, tooltip, drag-split and shift-click against those two fields, so writing them moves all of it
 * at once and nothing about slot interaction is re-implemented here. They are written again every
 * frame and after {@code init}, because vanilla rewrites them itself on a resize and whenever the
 * recipe book is toggled.
 *
 * <p>Folding moves the panel far off-screen: vanilla then draws nothing of it and hovers no slot, so
 * no tooltip, number-key swap or drop can act on an invisible item. The clicks it would still see -
 * all of them "outside the panel", which vanilla turns into a throw - are swallowed by
 * {@link #swallowWhileFolded}. Folding is refused while an item is on the cursor.
 *
 * <p>Scale is not implemented yet; see {@code docs/features/inventory-window.md}. Client thread only.
 */
public final class InventoryWindow {

    /** Where a folded panel goes: far enough that no GUI scale ever brings it on screen. */
    private static final int FOLDED = -10_000;

    /** Inset from the viewport edge that the border snap also offers. */
    private static final int MARGIN = 4;

    /** Two clicks on the bar within this many ms reset the window. */
    private static final long DOUBLE_CLICK_MS = 300;

    /** How long the "put the held item down" hint stays up. */
    private static final long HINT_MS = 2000;

    /** Width of the fold button at the bar's right end. */
    private static final int BUTTON_W = 12;

    private static final WindowMemory INVENTORY_MEMORY = new WindowMemory(InventoryWindowGeometry.KEY_INVENTORY);
    private static final WindowMemory MENUS_MEMORY = new WindowMemory(InventoryWindowGeometry.KEY_MENUS);

    /** The screen the state below belongs to; a different screen starts from its memory entry. */
    private static AbstractContainerScreen<?> screen;
    /** True while this screen's position is ours, so turning the feature off can hand it back. */
    private static boolean applied;
    /** Top-left corner of the title bar. */
    private static int barX;
    private static int barY;
    private static boolean folded;

    private static boolean dragging;
    private static int dragOffX;
    private static int dragOffY;
    private static long lastBarClick;
    private static long hintUntil;

    private InventoryWindow() {
    }

    private static SBSConfig.InventoryOverlaySettings cfg() {
        return ConfigManager.getInstance().get().inventoryOverlay;
    }

    // ------------------------------------------------------------------
    // Which screens
    // ------------------------------------------------------------------

    /**
     * Whether this screen takes part: the feature is on, the screen is in scope and on the plain-grid
     * allowlist the container theme uses, nothing exclusive has taken the screen over, and the vanilla
     * recipe book is closed (the book places itself from the screen width, not from {@code leftPos},
     * so a moved panel and an open book would drift apart).
     */
    static boolean wanted(AbstractContainerScreen<?> s) {
        SBSConfig.InventoryOverlaySettings cfg = cfg();
        if (!cfg.windowed) {
            return false;
        }
        boolean inventory = s instanceof InventoryScreen;
        boolean menu = s instanceof ContainerScreen || s instanceof ShulkerBoxScreen
                || s instanceof HopperScreen || s instanceof DispenserScreen;
        if (!inventory && !(menu && cfg.windowScope == SBSConfig.InventoryOverlaySettings.SCOPE_ALL_CONTAINERS)) {
            return false;
        }
        if (s instanceof AbstractRecipeBookScreen<?> && ((AbstractRecipeBookScreenAccessor) s)
                .skyblockSimplified$recipeBookComponent().isVisible()) {
            return false;
        }
        return !sbs.modid.client.helper.storage.StorageOverviewOverlay.getInstance().isWorkspaceActive(s);
    }

    private static boolean active(AbstractContainerScreen<?> s) {
        return applied && s == screen;
    }

    private static WindowMemory memory(AbstractContainerScreen<?> s) {
        return s instanceof InventoryScreen ? INVENTORY_MEMORY : MENUS_MEMORY;
    }

    // ------------------------------------------------------------------
    // Placement
    // ------------------------------------------------------------------

    /** Vanilla's own {@code leftPos}, recomputed - it is overwritten while the window is applied. */
    private static int vanillaLeft(AbstractContainerScreen<?> s, int imageWidth) {
        if (s instanceof AbstractRecipeBookScreen<?>) {
            return ((AbstractRecipeBookScreenAccessor) s).skyblockSimplified$recipeBookComponent()
                    .updateScreenPosition(s.width, imageWidth);
        }
        return (s.width - imageWidth) / 2;
    }

    /**
     * Writes the window's position into the screen, or hands vanilla's back when the window no
     * longer applies. Called every frame and after {@code init}; cheap when the feature is off (one
     * config read and nothing else).
     */
    public static void apply(Screen any) {
        if (!(any instanceof AbstractContainerScreen<?> s)) {
            return;
        }
        boolean wanted = wanted(s);
        if (!wanted) {
            if (active(s)) {
                restoreVanilla(s);
            }
            return;
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) s;
        int w = bounds.skyblockSimplified$imageWidth();
        int h = bounds.skyblockSimplified$imageHeight();
        if (s != screen) {
            screen = s;
            dragging = false;
            hintUntil = 0;
            WindowMemory.State saved = memory(s).current();
            if (saved != null) {
                barX = saved.x;
                barY = saved.y;
                folded = saved.minimized;
            } else {
                placeAtVanilla(s, w, h);
            }
        }
        applied = true;
        int[] clamped = InventoryWindowGeometry.clamp(barX, barY, w, windowHeight(h), s.width, s.height);
        barX = clamped[0];
        barY = clamped[1];
        int left = folded ? FOLDED : barX;
        int top = folded ? FOLDED : barY + InventoryWindowGeometry.BAR_HEIGHT;
        bounds.skyblockSimplified$setLeftPos(left);
        bounds.skyblockSimplified$setTopPos(top);
        moveRecipeButton(s, left, top);
    }

    /** The panel exactly where vanilla puts it, the bar directly above. */
    private static void placeAtVanilla(AbstractContainerScreen<?> s, int w, int h) {
        barX = vanillaLeft(s, w);
        barY = (s.height - h) / 2 - InventoryWindowGeometry.BAR_HEIGHT;
        folded = false;
    }

    private static int windowHeight(int imageHeight) {
        return InventoryWindowGeometry.BAR_HEIGHT + (folded ? 0 : imageHeight);
    }

    private static void restoreVanilla(AbstractContainerScreen<?> s) {
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) s;
        int left = vanillaLeft(s, bounds.skyblockSimplified$imageWidth());
        int top = (s.height - bounds.skyblockSimplified$imageHeight()) / 2;
        bounds.skyblockSimplified$setLeftPos(left);
        bounds.skyblockSimplified$setTopPos(top);
        if (s instanceof RecipeBookButtonHolder holder) {
            AbstractWidget button = holder.skyblockSimplified$recipeBookButton();
            if (button != null) {
                // Vanilla's own formula (InventoryScreen.getRecipeBookButtonPosition).
                button.setPosition(left + 104, s.height / 2 - 22);
            }
        }
        applied = false;
        dragging = false;
    }

    /**
     * The recipe-book toggle is placed once, in {@code init}, at {@code height / 2 - 22} - which is
     * {@code topPos + 61} for the 166 px inventory. Kept on the panel with that offset.
     */
    private static void moveRecipeButton(AbstractContainerScreen<?> s, int left, int top) {
        if (s instanceof InventoryScreen && s instanceof RecipeBookButtonHolder holder) {
            AbstractWidget button = holder.skyblockSimplified$recipeBookButton();
            if (button != null) {
                button.setPosition(left + 104, top + 61);
            }
        }
    }

    private static void remember(AbstractContainerScreen<?> s) {
        memory(s).remember(barX, barY, folded);
    }

    /** "Reset Window Positions": forgets both entries and puts an open window back at vanilla's spot. */
    public static void resetAll() {
        INVENTORY_MEMORY.forget();
        MENUS_MEMORY.forget();
        if (screen != null && applied) {
            AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
            placeAtVanilla(screen, bounds.skyblockSimplified$imageWidth(), bounds.skyblockSimplified$imageHeight());
            apply(screen);
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private static boolean onBar(AbstractContainerScreen<?> s, double mx, double my) {
        int w = ((AbstractContainerScreenAccessor) s).skyblockSimplified$imageWidth();
        return mx >= barX && mx < barX + w && my >= barY && my < barY + InventoryWindowGeometry.BAR_HEIGHT;
    }

    private static boolean onFoldButton(AbstractContainerScreen<?> s, double mx, double my) {
        int w = ((AbstractContainerScreenAccessor) s).skyblockSimplified$imageWidth();
        return onBar(s, mx, my) && mx >= barX + w - BUTTON_W;
    }

    /** A press on the title bar: fold button, double-click reset, or the start of a drag. */
    public static boolean handleClick(AbstractContainerScreen<?> s, MouseButtonEvent event) {
        if (!active(s) || !onBar(s, event.x(), event.y())) {
            return false;
        }
        if (event.button() != 0) {
            return true; // the bar is ours; a right-click on it must not reach the menu
        }
        if (onFoldButton(s, event.x(), event.y())) {
            toggleFold(s);
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastBarClick <= DOUBLE_CLICK_MS) {
            lastBarClick = 0;
            memory(s).forget();
            AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) s;
            placeAtVanilla(s, bounds.skyblockSimplified$imageWidth(), bounds.skyblockSimplified$imageHeight());
            apply(s);
            return true;
        }
        lastBarClick = now;
        dragging = true;
        dragOffX = (int) event.x() - barX;
        dragOffY = (int) event.y() - barY;
        return true;
    }

    private static void toggleFold(AbstractContainerScreen<?> s) {
        if (!folded && !s.getMenu().getCarried().isEmpty()) {
            hintUntil = System.currentTimeMillis() + HINT_MS;
            return;
        }
        folded = !folded;
        apply(s);
        remember(s);
    }

    /** Moves the window while its bar is being dragged. */
    public static boolean handleDrag(AbstractContainerScreen<?> s, MouseButtonEvent event) {
        if (!dragging || !active(s)) {
            return false;
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) s;
        int w = bounds.skyblockSimplified$imageWidth();
        int h = windowHeight(bounds.skyblockSimplified$imageHeight());
        int[] snapped = EdgeSnap.toBorders((int) event.x() - dragOffX, (int) event.y() - dragOffY,
                w, h, s.width, s.height, MARGIN);
        barX = snapped[0];
        barY = snapped[1];
        apply(s);
        return true;
    }

    /** Ends a drag and remembers where it ended. */
    public static boolean handleRelease(AbstractContainerScreen<?> s) {
        if (!dragging) {
            return false;
        }
        dragging = false;
        if (active(s)) {
            remember(s);
        }
        return true;
    }

    /**
     * While folded, every mouse event nothing of ours took is swallowed: the panel is off-screen, so
     * vanilla would read any click as "outside the panel" and send a throw.
     */
    public static boolean swallowWhileFolded(AbstractContainerScreen<?> s) {
        return active(s) && folded;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /** The title bar (and the fold hint), in absolute GUI coordinates. */
    public static void render(AbstractContainerScreen<?> s, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!active(s)) {
            return;
        }
        var font = Minecraft.getInstance().font;
        int w = ((AbstractContainerScreenAccessor) s).skyblockSimplified$imageWidth();
        int bh = InventoryWindowGeometry.BAR_HEIGHT;
        boolean barHover = dragging || onBar(s, mouseX, mouseY);
        SciFiRender.roundedRectWithBorder(g, barX, barY, w, bh, SBSTheme.SLOT_CORNER,
                barHover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);

        String title = s instanceof InventoryScreen
                ? net.minecraft.network.chat.Component.translatable("container.inventory").getString()
                : MenuFrame.of(s).title();
        title = font.plainSubstrByWidth(title, w - BUTTON_W - 8);
        int textY = barY + (bh - font.lineHeight) / 2 + 1;
        g.text(font, title, barX + 4, textY, SBSTheme.TEXT, false);

        boolean buttonHover = onFoldButton(s, mouseX, mouseY);
        int bx = barX + w - BUTTON_W;
        g.text(font, folded ? "+" : "-", bx + (BUTTON_W - font.width(folded ? "+" : "-")) / 2, textY,
                buttonHover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED, false);

        if (System.currentTimeMillis() < hintUntil) {
            String hint = font.plainSubstrByWidth("Put the held item down first", Math.max(40, s.width - barX - 4));
            SciFiRender.roundedRectWithBorder(g, barX, barY + bh + 2, font.width(hint) + 8, bh,
                    SBSTheme.SLOT_CORNER, SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
            g.text(font, hint, barX + 4, barY + bh + 2 + (bh - font.lineHeight) / 2 + 1, SBSTheme.TEXT, false);
        }
    }
}
