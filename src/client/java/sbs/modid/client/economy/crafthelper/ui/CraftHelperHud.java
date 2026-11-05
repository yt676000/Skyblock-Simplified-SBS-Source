/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.crafthelper.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import sbs.modid.client.core.api.ScreenAccess;
import sbs.modid.client.economy.crafthelper.logic.CraftHelper;
import sbs.modid.client.economy.crafthelper.logic.CraftPins;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Craft Helper card: the item the player put on the HUD, with its recipe tree and what is still
 * missing, so it can be read while playing.
 *
 * <p>Drawn on the bare HUD without a cursor, and again over every container screen, where it is
 * interactive: {@code - / +} for the quantity, the close cross, collapse per row, the recipe list,
 * scrolling and the scrollbar, and a strip of the other starred items to switch to. Clicks reach it
 * from {@code ContainerSearchBarMixin} before the floating windows, matching where it is drawn
 * ({@code ScreenForeground}, after the windows).
 *
 * <p>Display only. Nothing here clicks a slot, crafts, or opens a menu.
 */
public final class CraftHelperHud {

    private static final CraftHelperHud INSTANCE = new CraftHelperHud();

    private static final int PAD = 4;
    private static final int MIN_W = 170;
    private static final int MAX_W = 320;
    private static final int ICON = 18;
    private static final int STRIP = ICON + 4;

    private final CraftTreePanel panel = new CraftTreePanel(CraftTreePanel.Action.CLOSE);

    /** The other starred items, rebuilt when the pins change. */
    private int pinsRevision = Integer.MIN_VALUE;
    private List<String> pins = List.of();
    private List<ItemRef> pinIcons = List.of();
    private java.util.Map<String, Integer> quantities = java.util.Map.of();

    // Last drawn box, in the element's local space.
    private int boxX;
    private int boxY;
    private int boxW;
    private int boxH;
    private boolean drawn;

    private CraftHelperHud() {
    }

    public static CraftHelperHud getInstance() {
        return INSTANCE;
    }

    private static boolean active() {
        return CraftHelper.cfg().enabled && CraftPins.getInstance().shown() != null
                && !HudLayout.isHidden(HudElement.CRAFT_HELPER);
    }

    // ------------------------------------------------------------------ render

    /** HUD pass: only while no screen is open; the container pass draws it otherwise. */
    public void renderHud(GuiGraphicsExtractor g) {
        if (ScreenAccess.current() == null) {
            render(g, Integer.MIN_VALUE, Integer.MIN_VALUE, false);
        } else {
            drawn = false;
        }
    }

    /** Container pass, from {@code ScreenForeground}: interactive. */
    public void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        int localX = (int) Math.floor(HudLayout.localX(HudElement.CRAFT_HELPER, mouseX, gw, gh));
        int localY = (int) Math.floor(HudLayout.localY(HudElement.CRAFT_HELPER, mouseY, gw, gh));
        render(g, localX, localY, true);
    }

    private void render(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean interactive) {
        drawn = false;
        if (!active()) {
            return;
        }
        CraftPins store = CraftPins.getInstance();
        String shown = store.shown();
        refreshPins(store);
        CraftHelper.Model model = CraftHelper.getInstance().model(shown, quantities.getOrDefault(shown, 1));
        if (model == null) {
            return;
        }
        int gw = g.guiWidth();
        int rows = Math.max(3, Math.min(CraftHelper.cfg().hudRows, model.rows().size()));
        int maxW = Math.max(80, Math.min(MAX_W, gw - 8));
        boxW = Math.max(Math.min(MIN_W, maxW), Math.min(maxW, model.width() + PAD * 2 + 16));
        int panelH = CraftTreePanel.heightFor(rows);
        boolean strip = pins.size() > 1;
        boxH = panelH + (strip ? STRIP : 0);
        HudElement.Bounds b = HudElement.CRAFT_HELPER.defaultBounds(gw, g.guiHeight());
        boxX = Math.round(b.x());
        boxY = Math.round(b.y());
        HudLayout.measure(HudElement.CRAFT_HELPER, boxX, boxY, boxW, boxH);

        HudLayout.begin(g, HudElement.CRAFT_HELPER);
        HudCard.draw(g, boxX, boxY, boxW, boxH);
        panel.render(g, model, boxX, boxY, boxW, panelH, mouseX, mouseY, interactive);
        if (strip) {
            drawStrip(g, shown, boxY + panelH, mouseX, mouseY, interactive);
        }
        HudLayout.end(g);
        drawn = true;
    }

    private void refreshPins(CraftPins store) {
        int revision = store.revision();
        if (revision == pinsRevision) {
            return;
        }
        pinsRevision = revision;
        pins = store.pins();
        List<ItemRef> icons = new ArrayList<>(pins.size());
        for (String id : pins) {
            SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(id);
            icons.add(new ItemRef(id, entry != null ? entry.material : null, entry != null ? entry.name : id, 1));
        }
        pinIcons = List.copyOf(icons);
        java.util.Map<String, Integer> qty = new java.util.HashMap<>();
        for (String id : pins) {
            qty.put(id, store.quantity(id));
        }
        quantities = qty;
    }

    /** One icon per starred item; the shown one framed. As many as fit on one row. */
    private void drawStrip(GuiGraphicsExtractor g, String shown, int top, int mouseX, int mouseY,
                           boolean interactive) {
        int perRow = Math.max(1, (boxW - PAD * 2) / ICON);
        int count = Math.min(pins.size(), perRow);
        for (int i = 0; i < count; i++) {
            int ix = boxX + PAD + i * ICON;
            boolean current = pins.get(i).equals(shown);
            boolean hover = interactive && mouseX >= ix && mouseX < ix + ICON && mouseY >= top && mouseY < top + ICON;
            if (current || hover) {
                SciFiRender.roundedRectWithBorder(g, ix, top, ICON, ICON, SBSTheme.SLOT_CORNER, SBSTheme.CARD_BG,
                        current ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
            }
            g.item(pinIcons.get(i).displayStack(), ix + 1, top + 1);
        }
    }

    // ------------------------------------------------------------------ input (container screens)

    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!drawn || !active()) {
            return false;
        }
        double lx = localX(event.x());
        double ly = localY(event.y());
        if (panel.popupOpen()) {
            return panel.click(lx, ly, event.button());
        }
        if (lx < boxX || lx >= boxX + boxW || ly < boxY || ly >= boxY + boxH) {
            return false;
        }
        int stripTop = boxY + boxH - STRIP;
        if (pins.size() > 1 && ly >= stripTop) {
            int index = (int) ((lx - boxX - PAD) / ICON);
            int perRow = Math.max(1, (boxW - PAD * 2) / ICON);
            if (event.button() == 0 && index >= 0 && index < Math.min(pins.size(), perRow) && lx >= boxX + PAD) {
                CraftPins.getInstance().show(pins.get(index));
            }
            return true;
        }
        panel.click(lx, ly, event.button());
        return true;
    }

    public boolean handleScroll(double mouseX, double mouseY, double amount) {
        return drawn && active() && panel.scroll(localX(mouseX), localY(mouseY), amount);
    }

    public boolean handleDrag(double mouseY) {
        return drawn && panel.drag(localY(mouseY));
    }

    public boolean handleRelease() {
        return panel.release();
    }

    /** Whether a tooltip from something underneath should be held back (the recipe list is open). */
    public boolean popupOpen() {
        return drawn && panel.popupOpen();
    }

    /** Escape closes the open recipe list before it closes the screen. */
    public boolean handleEscape() {
        return drawn && panel.escape();
    }

    private static double localX(double screenX) {
        Minecraft mc = Minecraft.getInstance();
        return HudLayout.localX(HudElement.CRAFT_HELPER, screenX,
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
    }

    private static double localY(double screenY) {
        Minecraft mc = Minecraft.getInstance();
        return HudLayout.localY(HudElement.CRAFT_HELPER, screenY,
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
    }
}
