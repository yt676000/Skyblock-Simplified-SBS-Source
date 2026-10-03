/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.window.FloatingWindows;
import sbs.modid.client.ui.window.WindowMemory;
import sbs.modid.client.economy.forge.logic.ForgeFlipFeed;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.DevNotice;
import sbs.modid.client.ui.render.LocalRankingNotice;
import sbs.modid.client.ui.render.SourceSwitch;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.Locale;

/**
 * The Forge Flips window beside Hypixel's Forge menu: a real floating window – draggable by its
 * header, minimizable to a small bar, and scrollable – rather than a fixed panel pinned to the
 * screen edge.
 *
 * <p>It renders the very same table {@link ForgeFlipsScreen} shows; the drawing lives there and is
 * called from here, so the two can never drift apart.
 *
 * <p>The window is never closed, only collapsed, so it is always one click away without re-opening
 * the menu. Where it sits and whether it is collapsed is remembered across restarts through
 * {@link WindowMemory}.
 */
public final class ForgeFlipsOverlay {

    private static final ForgeFlipsOverlay INSTANCE = new ForgeFlipsOverlay();

    private static final int HEADER_H = 15;
    private static final int PAD = 5;
    private static final int MARGIN = 2;
    private static final int WIDTH = 250;
    private static final int HEIGHT = 260;
    private static final int MIN_BAR_W = 92;

    /** Window position; {@link Integer#MIN_VALUE} until first placed. */
    private int posX = Integer.MIN_VALUE;
    private int posY;
    private int panelW = WIDTH;
    private int panelH = HEIGHT;

    private boolean minimized;
    private boolean dragging;
    private double grabDX;
    private double grabDY;
    private int scroll;

    /** The list's scrollbar - the mod's shared one, so it drags like every other SBS list. */
    private final SciFiScrollbar bar = new SciFiScrollbar();

    /** Collapsed-bar rectangle from the last frame, so clicks hit exactly what was drawn. */
    private int minBarX;
    private int minBarY;
    private int minBarW;
    private int minBarH;

    /** Where the player left this window. The size is fixed, so only the spot is remembered. */
    private final WindowMemory memory = new WindowMemory(FloatingWindows.Layer.FORGE);

    private ForgeFlipsOverlay() {
    }

    public static ForgeFlipsOverlay getInstance() {
        return INSTANCE;
    }

    /** Hypixel's forge menu is titled "The Forge" (and "Forge" on some sub-screens). */
    private static boolean isForgeGui(String title) {
        return title != null && title.trim().toLowerCase(Locale.ROOT).contains("forge");
    }

    /** Whether the window should be up at all: toggle on, and the Forge menu open. */
    private boolean open(AbstractContainerScreen<?> container) {
        if (!ConfigManager.getInstance().get().forge.showFlips) {
            return false;
        }
        String title = container.getTitle() != null ? container.getTitle().getString() : "";
        return isForgeGui(title);
    }

    private boolean inPanel(double mx, double my) {
        return mx >= posX && mx < posX + panelW && my >= posY && my < posY + panelH;
    }

    private boolean inHeader(double mx, double my) {
        return mx >= posX && mx < posX + panelW && my >= posY && my < posY + HEADER_H;
    }

    private int minimizeGlyphX() {
        return posX + panelW - 14;
    }

    private boolean inMinimizeBox(double mx, double my) {
        return mx >= minimizeGlyphX() && mx < minimizeGlyphX() + 12
                && my >= posY + 2 && my < posY + HEADER_H;
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /** Drawn on the floating-window pass, above the container's own slots. */
    public void renderTopMost(AbstractContainerScreen<?> container, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        if (!open(container)) {
            return;
        }
        ForgeFlipFeed.getInstance().request(false);   // no-op unless the cached ranking is stale
        var font = Minecraft.getInstance().font;

        memory.restore(state -> {
            posX = state.x;
            posY = state.y;
            minimized = state.minimized;
        });
        // Default spot: right of the centred container, clamped into the window.
        if (posX == Integer.MIN_VALUE) {
            posX = Math.max(MARGIN, container.width - WIDTH - 8);
            posY = MARGIN * 2;
        }
        if (minimized) {
            drawMinimizedBar(container, g, font, mouseX, mouseY);
            return;
        }
        panelW = Math.min(WIDTH, Math.max(120, container.width - MARGIN * 2));
        panelH = Math.min(HEIGHT, Math.max(60, container.height - MARGIN * 2));
        posX = clamp(posX, MARGIN, Math.max(MARGIN, container.width - panelW - MARGIN));
        posY = clamp(posY, MARGIN, Math.max(MARGIN, container.height - panelH - MARGIN));

        SciFiRender.glow(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, posX + 1, posY + 1, panelW - 2, panelH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int textY = posY + (HEADER_H - font.lineHeight) / 2 + 1;
        // The title carries the mode too, so a reader who has scrolled the list still has "these are
        // local estimates" on screen. Tag dropped rather than cut when the window is narrow; the full
        // notice is on the screen this list is shared with.
        boolean local = ForgeFlipFeed.getInstance().state().isLocal();
        g.text(font, Component.literal(DevNotice.tagged(font,
                        local ? "Forge Flips (local)" : "Forge Flips",
                        minimizeGlyphX() - 4 - (posX + PAD))),
                posX + PAD, textY, local ? SBSTheme.WARN : SBSTheme.ACCENT_BRIGHT);
        boolean minHover = inMinimizeBox(mouseX, mouseY);
        g.text(font, Component.literal("-"), minimizeGlyphX() + 4, textY,
                minHover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED);
        g.fill(posX + PAD, posY + HEADER_H, posX + panelW - PAD, posY + HEADER_H + 1, SBSTheme.ACCENT_SOFT);

        // Server / Local, on its own row under the header. This window has no budget row to share,
        // so it gets the strip to itself and the rows start below whatever it and the banner used.
        boolean preferLocal = ConfigManager.getInstance().get().forge.flipSource.preferLocal;
        int switchY = posY + HEADER_H + 3;
        SourceSwitch.draw(g, font, posX + PAD, switchY, preferLocal, mouseX, mouseY);
        if (mouseX >= posX + PAD && mouseX < posX + PAD + SourceSwitch.width(font)
                && mouseY >= switchY && mouseY < switchY + SourceSwitch.HEIGHT) {
            java.util.List<Component> tip = new java.util.ArrayList<>();
            for (String line : SourceSwitch.tooltip(preferLocal)) {
                tip.add(Component.literal(line));
            }
            g.setTooltipForNextFrame(font, tip, java.util.Optional.empty(), mouseX, mouseY,
                    SBSTheme.tooltipStyle());
        }

        // The offline banner is pinned between the switch and the rows rather than scrolled with
        // them: this window is small and its content scrolls, and a warning that scrolls away is one
        // most readers see once and then forget while reading numbers from the weaker engine.
        LocalRankingNotice.draw(g, font, posX + PAD, noticeTop(), noticeWidth(), noticeFailure());

        syncBar();
        int listWidth = panelW - PAD * 2 - (bar.needed() ? SciFiScrollbar.WIDTH + 3 : 0);
        ForgeFlipsScreen.drawList(g, font, posX + PAD, listTop(font), listWidth,
                posY + panelH - PAD, scroll, mouseX, mouseY);
        bar.render(g, scroll, mouseX, mouseY);
    }

    /** The failure to explain in the pinned banner, or {@code null} while the server ranking shows. */
    private static sbs.modid.client.core.api.ApiFailure noticeFailure() {
        ForgeFlipFeed.State state = ForgeFlipFeed.getInstance().state();
        return state.isLocal() ? state.failure() : null;
    }

    /** Width the banner wraps to: the panel's text column, never the scrollbar's strip. */
    private int noticeWidth() {
        return Math.max(1, panelW - PAD * 2);
    }

    /** Top of the pinned banner: under the header and the source switch. */
    private int noticeTop() {
        return posY + HEADER_H + 3 + SourceSwitch.HEIGHT + 2;
    }

    /**
     * Top of the first row, measured from what the banner actually drew. Measured rather than
     * assumed: the banner is one line on a wide window and two on a narrow one, and a constant here
     * would overlap the first row at exactly the sizes players drag this window to.
     */
    private int listTop(net.minecraft.client.gui.Font font) {
        return noticeTop() + LocalRankingNotice.height(font, noticeWidth(), noticeFailure());
    }

    /** The list's track box; the rows run from under the banner to the bottom padding. */
    private void syncBar() {
        int top = listTop(Minecraft.getInstance().font);
        int bottom = posY + panelH - PAD;
        bar.set(posX + panelW - PAD - SciFiScrollbar.WIDTH, top, bottom - top,
                ForgeFlipsScreen.rowCount(),
                Math.max(1, (bottom - top) / ForgeFlipsScreen.ROW_H));
    }

    /** The collapsed bar: click it to bring the window back. */
    private void drawMinimizedBar(AbstractContainerScreen<?> container, GuiGraphicsExtractor g,
                                  net.minecraft.client.gui.Font font, int mouseX, int mouseY) {
        minBarW = MIN_BAR_W;
        minBarH = HEADER_H;
        minBarX = clamp(posX, MARGIN, Math.max(MARGIN, container.width - minBarW - MARGIN));
        minBarY = clamp(posY, MARGIN, Math.max(MARGIN, container.height - minBarH - MARGIN));
        boolean hovered = mouseX >= minBarX && mouseX < minBarX + minBarW
                && mouseY >= minBarY && mouseY < minBarY + minBarH;
        SciFiRender.roundedRectWithBorder(g, minBarX, minBarY, minBarW, minBarH,
                SBSTheme.CORNER_RADIUS, hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        g.text(font, Component.literal("Forge Flips +"), minBarX + PAD,
                minBarY + (minBarH - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
    }

    // ------------------------------------------------------------------
    // Input – forwarded from ContainerSearchBarMixin
    // ------------------------------------------------------------------

    /** @return whether the click was consumed (and must not reach the container's slots) */
    public boolean handleClick(AbstractContainerScreen<?> container, MouseButtonEvent event) {
        if (!open(container)) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (minimized) {
            if (event.button() == 0 && mx >= minBarX && mx < minBarX + minBarW
                    && my >= minBarY && my < minBarY + minBarH) {
                minimized = false;
                rememberWindow();
                FloatingWindows.raise(FloatingWindows.Layer.FORGE);
                return true;
            }
            return false;   // everything else passes through to the menu
        }
        if (!inPanel(mx, my)) {
            return false;
        }
        if (event.button() == 0 && inMinimizeBox(mx, my)) {
            minimized = true;
            rememberWindow();
            return true;
        }
        if (event.button() == 0 && inHeader(mx, my)) {
            dragging = true;
            grabDX = mx - posX;
            grabDY = my - posY;
            return true;
        }
        // The source switch, before anything else in the body.
        var font = Minecraft.getInstance().font;
        if (event.button() == 0) {
            Boolean picked = SourceSwitch.hit(font, posX + PAD, posY + HEADER_H + 3, mx, my);
            if (picked != null) {
                sbs.modid.client.core.api.RankingSource.choose(
                        ConfigManager.getInstance().get().forge.flipSource, picked);
                ForgeFlipFeed.getInstance().request(true);
                return true;
            }
        }
        // The scrollbar before anything else inside the body, or a click on the bar also lands on
        // the row painted under it.
        syncBar();
        if (event.button() == 0 && bar.handleClick(mx, my, scroll, value -> scroll = value)) {
            return true;
        }
        // Swallow clicks inside the window: they are ours, not the forge slots' underneath.
        return true;
    }

    public boolean handleDrag(AbstractContainerScreen<?> container, MouseButtonEvent event) {
        if (!open(container) || minimized) {
            return false;
        }
        syncBar();
        if (bar.handleDrag(event.y(), value -> scroll = value)) {
            return true;
        }
        if (!dragging) {
            return false;
        }
        posX = clamp((int) (event.x() - grabDX), MARGIN,
                Math.max(MARGIN, container.width - panelW - MARGIN));
        posY = clamp((int) (event.y() - grabDY), MARGIN,
                Math.max(MARGIN, container.height - panelH - MARGIN));
        return true;
    }

    public boolean handleRelease(MouseButtonEvent event) {
        if (bar.release()) {
            return true;
        }
        if (!dragging) {
            return false;
        }
        dragging = false;
        rememberWindow();
        return true;
    }

    /** Persists the window's spot so it comes back where it was left. */
    private void rememberWindow() {
        memory.remember(posX, posY, minimized);
    }

    public boolean handleScroll(AbstractContainerScreen<?> container, double mouseX, double mouseY,
                                double scrollY) {
        if (!open(container) || minimized || !inPanel(mouseX, mouseY) || scrollY == 0) {
            return false;
        }
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }
}
