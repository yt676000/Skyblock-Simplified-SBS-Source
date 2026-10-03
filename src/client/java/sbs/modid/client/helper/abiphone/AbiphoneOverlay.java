/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.abiphone;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders every Abiphone menu as a fancy phone: a dark rounded body with a bezel, a status bar
 * (carrier + clock + signal + battery), a home indicator and app-style slot cells drawn over the
 * vanilla chest texture. Because it is a generic container reskin, it covers <b>all</b> Abiphone
 * screens automatically - the main flip menu, Minigames (Tic Tac Toe / Snake), Ringtones, Speed Dial,
 * the Contacts Directory - without any per-menu logic. The real slots + items render on top and stay
 * fully clickable.
 *
 * <p>Invoked from {@code SbsContainerThemeMixin} at the head of {@code extractSlots} (slot-relative
 * space, over the vanilla texture); when it draws it takes precedence over the generic SBS theme.
 */
public final class AbiphoneOverlay {

    private static final AbiphoneOverlay INSTANCE = new AbiphoneOverlay();

    // Phone palette.
    private static final int BODY = 0xFF14141C;
    private static final int BEZEL = 0xFF2B2C3A;
    private static final int SCREEN = 0xFF0C1420;
    private static final int SLOT_CELL = 0x66203044;
    private static final int SLOT_BORDER = 0x552B3B54;
    private static final int STATUS_TEXT = 0xFFDCE6F2;
    private static final int HOME_BAR = 0xFF3A4658;

    private static final int PAD = 6;        // bezel around the screen
    private static final int STATUS_H = 12;  // status bar height above the content
    private static final int HOME_H = 9;     // home-indicator strip below the content
    private static final int CORNER = 10;    // phone body corner radius

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private AbiphoneOverlay() {
    }

    public static AbiphoneOverlay getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.AbiphoneSettings cfg() {
        return ConfigManager.getInstance().get().abiphone;
    }

    /** Whether the phone UI is active for this screen (toggle on + an Abiphone menu). */
    public boolean active(AbstractContainerScreen<?> screen) {
        return cfg().enabled && isPhoneScreen(titleOf(screen));
    }

    /**
     * Draws the phone body over the vanilla texture, in the slot-relative space the caller is in.
     * Returns {@code true} when it drew (so the generic container theme skips this screen).
     */
    public boolean tryRender(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        if (!active(screen)) {
            return false;
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        int w = bounds.skyblockSimplified$imageWidth();
        int h = bounds.skyblockSimplified$imageHeight();
        Font font = Minecraft.getInstance().font;

        int bodyX = -PAD;
        int bodyY = -PAD - STATUS_H;
        int bodyW = w + PAD * 2;
        int bodyH = h + PAD * 2 + STATUS_H + HOME_H;

        // Phone body + bezel + screen.
        SciFiRender.roundedRect(g, bodyX - 1, bodyY - 1, bodyW + 2, bodyH + 2, CORNER + 1, BEZEL);
        SciFiRender.roundedRect(g, bodyX, bodyY, bodyW, bodyH, CORNER, BODY);
        SciFiRender.roundedRect(g, -2, -2, w + 4, h + 4, CORNER - 4, SCREEN);

        if (cfg().statusBar) {
            drawStatusBar(g, font, w, -STATUS_H + 1);
        }
        // Home indicator bar centred below the content.
        int homeW = Math.max(24, w / 3);
        SciFiRender.roundedRect(g, w / 2 - homeW / 2, h + HOME_H / 2 - 1, homeW, 2, 1, HOME_BAR);

        // App-style rounded cell behind every slot.
        AbstractContainerMenu menu = screen.getMenu();
        int slotCount = menu.getItems().size();
        for (int i = 0; i < slotCount; i++) {
            Slot slot = menu.getSlot(i);
            SciFiRender.roundedRectWithBorder(g, slot.x - 1, slot.y - 1, 18, 18,
                    SBSTheme.SLOT_CORNER, SLOT_CELL, SLOT_BORDER);
        }
        return true;
    }

    /** Carrier name + clock + signal bars + battery, like a real phone status bar. */
    private static void drawStatusBar(GuiGraphicsExtractor g, Font font, int w, int y) {
        g.text(font, Component.literal("Abiphone"), 2, y, STATUS_TEXT);

        String clock = LocalTime.now().format(CLOCK);
        g.text(font, Component.literal(clock), (w - font.width(clock)) / 2, y, STATUS_TEXT);

        // Signal bars (4, rising) then a battery at the far right.
        int right = w - 2;
        int batW = 12;
        int batX = right - batW;
        // Battery body + terminal + fill.
        g.fill(batX, y + 1, batX + batW, y + 8, 0xFF3A4658);
        g.fill(batX + batW, y + 3, batX + batW + 1, y + 6, 0xFF3A4658);
        g.fill(batX + 1, y + 2, batX + batW - 2, y + 7, SBSTheme.TOGGLE_ON);

        int sx = batX - 3;
        for (int i = 0; i < 4; i++) {
            int barH = 2 + i * 2;
            int bx = sx - (4 - i) * 3;
            g.fill(bx, y + 8 - barH, bx + 2, y + 8, SBSTheme.ACCENT);
        }
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    /**
     * Whether a container title is an Abiphone menu. Anything containing "abiphone" (the main flip
     * menu, "Abiphone Minigames", model variants) plus the specific sub-screens opened from it whose
     * titles do not repeat the word. Broad but title-anchored, so ordinary menus are never restyled.
     */
    public static boolean isPhoneScreen(String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("abiphone") || t.contains("abicall")) {
            return true;
        }
        return switch (t) {
            case "contacts directory", "ringtones", "speed dial", "tic tac toe", "snake" -> true;
            default -> false;
        };
    }

    private static String titleOf(AbstractContainerScreen<?> screen) {
        return screen.getTitle() == null ? "" : screen.getTitle().getString().replaceAll("§.", "").trim();
    }
}
