/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.social.chat.logic.ChatTabs;
import sbs.modid.client.social.chat.model.ChatTab;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The strip of chat channel tabs, and the hit test that goes with it.
 *
 * <p>Drawing and clicking come out of the same {@link #layout} call, for the reason
 * {@code ChatGeometry} gives: two copies of this arithmetic would be two chances to drift, and the
 * symptom would be a tab that lights up under the cursor and a different one being selected.
 *
 * <p><b>It is a HUD element</b> ({@link HudElement#CHAT_TABS}), not a fixture of the chat screen.
 * That is what makes it draggable, scalable and removable with everything else the GUI editor knows
 * about, instead of growing its own three settings that do the same job worse - and it is why the
 * click has to be mapped back through {@link HudLayout#localX} before it means anything: the tabs
 * are drawn wherever the player has put them.
 *
 * <p>Its default place is the 26px band vanilla leaves between the last chat line ({@code height -
 * 40}) and the input box, clear of the command-usage line vanilla draws at {@code height - 27}. The
 * layout is computed in that untransformed space; the HUD transform moves it from there.
 *
 * <p>Widths are measured, never assumed: the tabs take their natural width when the viewport has
 * room and an equal, clipped share of it when it does not, so the strip fits a 1280x720 screen at
 * GUI scale 4 the same as an ultrawide at scale 1.
 */
public final class ChatTabBar {

    /** Height of the strip, and of every tab in it. */
    public static final int HEIGHT = 12;

    /** Distance from the bottom of the screen to the chat's own bottom edge (vanilla constant). */
    private static final int CHAT_BOTTOM_MARGIN = 40;

    private static final int MARGIN = 4;
    private static final int GAP = 2;
    private static final int PAD = 5;
    private static final int CORNER = 2;

    /** Fill of the tab showing right now, of the one under the cursor, and of the rest. */
    private static final int ACTIVE_FILL = 0xE0143A5E;
    private static final int HOVER_FILL = 0xD0102A45;
    private static final int IDLE_FILL = 0xB00B2138;

    /** One tab and the box it occupies, in the strip's own (untransformed) coordinates. */
    public record Slot(ChatTab tab, int x, int y, int width, int height) {

        public boolean contains(double x2, double y2) {
            return x2 >= x && x2 < x + width && y2 >= y && y2 < y + height;
        }
    }

    private ChatTabBar() {
    }

    /** Whether the strip should be on screen at all, before asking where the player put it. */
    public static boolean visible() {
        var cfg = ConfigManager.getInstance().get().chatTabs;
        return cfg.enabled && cfg.showBar && ChatTabs.getInstance().tabs().size() > 1;
    }

    /**
     * Where every tab sits, left to right, in the strip's own coordinates. Empty when the strip is
     * off or the viewport has no room for it.
     */
    public static List<Slot> layout(Font font, int screenWidth, int screenHeight) {
        if (!visible() || font == null) {
            return List.of();
        }
        List<ChatTab> tabs = ChatTabs.getInstance().tabs();
        int available = screenWidth - MARGIN * 2 - GAP * (tabs.size() - 1);
        if (available <= 0) {
            return List.of();
        }

        int[] natural = new int[tabs.size()];
        int total = 0;
        for (int i = 0; i < tabs.size(); i++) {
            natural[i] = font.width(caption(tabs.get(i))) + PAD * 2;
            total += natural[i];
        }
        // Too many tabs for the viewport: every tab gets the same share of what there is and its
        // caption is clipped to fit. Dropping tabs instead would hide the very thing the strip is
        // for - knowing which channels exist and which of them have something waiting.
        boolean shrink = total > available;
        int share = available / tabs.size();
        if (shrink && share < PAD * 2 + 4) {
            return List.of();   // below this a tab is a coloured box with no readable name
        }

        int y = screenHeight - CHAT_BOTTOM_MARGIN;
        int x = MARGIN;
        List<Slot> slots = new ArrayList<>(tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            int width = shrink ? share : natural[i];
            slots.add(new Slot(tabs.get(i), x, y, width, HEIGHT));
            x += width + GAP;
        }
        return slots;
    }

    /**
     * The tab under a screen position, or {@code null}. The position is mapped back through the HUD
     * transform first, so a strip the player has dragged or scaled is still hit where it is drawn.
     */
    public static ChatTab tabAt(double screenX, double screenY) {
        if (HudLayout.isHidden(HudElement.CHAT_TABS)) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        double localX = HudLayout.localX(HudElement.CHAT_TABS, screenX, width, height);
        double localY = HudLayout.localY(HudElement.CHAT_TABS, screenY, width, height);
        for (Slot slot : layout(minecraft.font, width, height)) {
            if (slot.contains(localX, localY)) {
                return slot.tab();
            }
        }
        return null;
    }

    /**
     * Paints the strip, from the HUD render so it is there whether or not the chat is open.
     *
     * <p>Drawn early in the HUD (with the hotbar and the decorations), so the chat lines, the input
     * box and any screen on top all land above it. That matters now that the strip can be moved:
     * park it under the chat and vanilla still wins, which is the same rule it followed when it
     * could only ever sit in the free band.
     */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().chatTabs;
        if (!visible() || HudLayout.isHidden(HudElement.CHAT_TABS)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean chatOpen = GuiStateManager.getInstance().getCurrentScreen() instanceof ChatScreen;
        if (!chatOpen && !cfg.barWhileClosed) {
            return;
        }
        // Better Chat's "hide when unfocused" hides the chat; the tabs belong to the chat.
        var chat = sbs.modid.client.social.chat.logic.ChatAccess.get();
        if (!chatOpen && chat != null
                && sbs.modid.client.social.chat.logic.BetterChat.getInstance().shouldHideRender(chat)) {
            return;
        }

        int width = g.guiWidth();
        int height = g.guiHeight();
        List<Slot> slots = layout(minecraft.font, width, height);
        if (slots.isEmpty()) {
            return;
        }
        Slot first = slots.get(0);
        Slot last = slots.get(slots.size() - 1);
        // The editor's box is the strip's real extent, not the nominal default: a player with two
        // tabs should not be dragging a rectangle sized for six.
        HudLayout.measure(HudElement.CHAT_TABS, first.x(), first.y(),
                last.x() + last.width() - first.x(), HEIGHT);

        // Hovering only means anything while there is a cursor to hover with.
        double localX = -1;
        double localY = -1;
        if (chatOpen) {
            var window = minecraft.getWindow();
            localX = HudLayout.localX(HudElement.CHAT_TABS,
                    minecraft.mouseHandler.getScaledXPos(window), width, height);
            localY = HudLayout.localY(HudElement.CHAT_TABS,
                    minecraft.mouseHandler.getScaledYPos(window), width, height);
        }

        HudLayout.begin(g, HudElement.CHAT_TABS);
        ChatTab active = ChatTabs.getInstance().active();
        for (Slot slot : slots) {
            boolean isActive = slot.tab() == active;
            boolean hovered = slot.contains(localX, localY);
            int fill = isActive ? ACTIVE_FILL : hovered ? HOVER_FILL : IDLE_FILL;
            int border = isActive ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER;
            SciFiRender.roundedRectWithBorder(g, slot.x(), slot.y(), slot.width(), slot.height(),
                    CORNER, fill, border);
            if (isActive) {
                // Shape as well as colour: the strip has to read for a player who cannot separate
                // the accent from the idle fill (ui/AGENTS.md).
                g.fill(slot.x() + 2, slot.y() + slot.height() - 1,
                        slot.x() + slot.width() - 2, slot.y() + slot.height(), SBSTheme.ACCENT_BRIGHT);
            }
            String caption = minecraft.font.plainSubstrByWidth(caption(slot.tab()),
                    slot.width() - PAD * 2);
            g.centeredText(minecraft.font, Component.literal(caption),
                    slot.x() + slot.width() / 2,
                    slot.y() + (slot.height() - minecraft.font.lineHeight) / 2 + 1,
                    isActive ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
        }
        HudLayout.end(g);
    }

    /**
     * The tab's name, with its unread count appended when it has one. The count is a number rather
     * than a coloured dot so it says how much is waiting, and so it survives being read by somebody
     * who cannot tell the two fills apart.
     */
    private static String caption(ChatTab tab) {
        int unread = ChatTabs.getInstance().unread(tab);
        if (unread <= 0) {
            return tab.label();
        }
        return tab.label() + " " + (unread > ChatTabs.UNREAD_CAP ? ChatTabs.UNREAD_CAP + "+" : unread);
    }
}
