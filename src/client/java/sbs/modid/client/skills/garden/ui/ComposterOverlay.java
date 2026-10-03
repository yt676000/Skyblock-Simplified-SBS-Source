/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Composter status card (Garden Helpers): everything the Composter menu knows – organic matter,
 * fuel, stored compost and the time to the next one – as a movable HUD card that keeps showing after
 * the menu closes (with an age stamp, since the values only refresh by opening the menu again).
 *
 * <p>Fed by scanning the open Composter menu on the client tick (throttled): items whose names carry
 * the known labels are parsed for their numbers out of name + lore. The exact wording is a
 * best-guess against Hypixel's menu, so every capture logs a {@code [SBS][Composter]} line with the
 * raw rows for live tuning.
 */
public final class ComposterOverlay {

    private static final ComposterOverlay INSTANCE = new ComposterOverlay();

    private static final long SCAN_INTERVAL_MS = 250L;
    /** Hide the card once the data is this stale - a day-old composter reading is noise. */
    private static final long HIDE_AFTER_MS = 60 * 60_000L;

    /** The menu labels worth a row on the card, matched as name fragments (lower-case). */
    private static final String[][] LABELS = {
            {"organic matter", "Matter"},
            {"fuel", "Fuel"},
            {"compost", "Compost"},
    };

    /** "600k/700k", "1,234 / 40,000", "17m 3s" – the first number-ish run in a lore line. */
    private static final Pattern VALUE = Pattern.compile(
            "([0-9][0-9.,]*[kKmMbB]?(?:\\s*/\\s*[0-9][0-9.,]*[kKmMbB]?)?|[0-9]+m\\s*[0-9]+s|[0-9]+[hms])");

    /** label -> shown value, in LABELS order. */
    private volatile List<String[]> rows = List.of();
    private volatile long capturedAt;
    private long lastScanAt;
    private long lastLogAt;

    private ComposterOverlay() {
    }

    public static ComposterOverlay getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().gardenHelpers.composterOverlay;
    }

    // ------------------------------------------------------------------ capture

    /** Called every client tick; reads the open Composter menu (throttled). */
    public void onClientTick() {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        if (!(sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen()
                instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        String title = screen.getTitle() == null ? ""
                : strip(screen.getTitle().getString()).trim().toLowerCase(Locale.ROOT);
        if (!title.contains("composter")) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int containerSlots = Math.max(0, menu.getItems().size() - 36);
        List<String[]> found = new ArrayList<>();
        for (String[] label : LABELS) {
            String value = null;
            for (int i = 0; i < containerSlots && value == null; i++) {
                ItemStack stack = menu.getSlot(i).getItem();
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                String name = strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
                if (!name.contains(label[0])) {
                    continue;
                }
                value = firstValue(stack);
            }
            if (value != null) {
                found.add(new String[]{label[1], value});
            }
        }
        if (!found.isEmpty()) {
            rows = List.copyOf(found);
            capturedAt = now;
            if (now - lastLogAt > 5_000L) {
                lastLogAt = now;
                StringBuilder sb = new StringBuilder();
                for (String[] row : found) {
                    sb.append(row[0]).append('=').append(row[1]).append(' ');
                }
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Composter] {}", sb.toString().trim());
            }
        }
    }

    /** The first number-ish value in the stack's name, then its lore lines. */
    private static String firstValue(ItemStack stack) {
        Matcher inName = VALUE.matcher(strip(stack.getHoverName().getString()));
        if (inName.find()) {
            return inName.group(1);
        }
        var lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                Matcher m = VALUE.matcher(strip(line.getString()));
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ render

    /** Called from the HUD render hook once per frame; self-hiding without fresh data. */
    public void render(GuiGraphicsExtractor g) {
        if (!enabled() || rows.isEmpty() || HudLayout.isHidden(HudElement.COMPOSTER)
                || Minecraft.getInstance().player == null) {
            return;
        }
        long age = System.currentTimeMillis() - capturedAt;
        if (age > HIDE_AFTER_MS) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        List<String[]> shown = rows;

        int lineH = font.lineHeight + 2;
        int contentW = font.width("Composter");
        for (String[] row : shown) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        String ageText = age > 60_000L ? "as of " + (age / 60_000L) + "m ago" : null;
        if (ageText != null) {
            contentW = Math.max(contentW, font.width(ageText));
        }
        int pad = 5;
        int width = Math.max(110, contentW + pad * 2);
        int height = pad * 2 + lineH * (1 + shown.size() + (ageText != null ? 1 : 0)) - 2;

        HudElement.Bounds b = HudElement.COMPOSTER.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.COMPOSTER, x, y, width, height);

        HudLayout.begin(g, HudElement.COMPOSTER);
        HudCard.draw(g, x, y, width, height);

        int ix = x + pad;
        int right = x + width - pad;
        int iy = y + pad;
        g.text(font, Component.literal("Composter"), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (String[] row : shown) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, SBSTheme.TEXT);
            iy += lineH;
        }
        if (ageText != null) {
            g.text(font, Component.literal(ageText), ix, iy, SBSTheme.TEXT_MUTED);
        }
        HudLayout.end(g);
    }

    private static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == (char) 0x00A7 && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
