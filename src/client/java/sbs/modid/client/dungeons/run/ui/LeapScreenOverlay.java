/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.keybind.Keys;
import sbs.modid.client.core.render.CachedModel;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.dungeons.run.model.DungeonTeamClasses;
import sbs.modid.client.dungeons.run.render.DungeonMapRenderer;
import sbs.modid.client.dungeons.run.ui.LeapLayout.Entry;
import sbs.modid.client.dungeons.run.ui.LeapLayout.Rect;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * Custom Leap Menu: draws the open Spirit Leap / Infinileap chest as one SBS card with a big button
 * per teammate (head, name, class in its colour, the class hotkey if bound) and, optionally, the
 * dungeon map underneath. The vanilla chest stays open behind it; this only changes what it looks
 * like.
 *
 * <p><b>It never invents a click.</b> A click on a button - or on that teammate's marker on the map -
 * becomes one left-click {@code PICKUP} on the real head slot, the same primitive clicking the head
 * uses and the same one the class hotkeys ({@link LeapMenu#handleKey}, unchanged) send. Nothing
 * fires on its own; the server closes the menu after the leap as usual. Every other click inside the
 * screen is swallowed, because the chest underneath is hidden and a click that fell through would
 * land on a slot the player cannot see (the {@code TerminalBoardOverlay} rule).
 *
 * <p>Everything measured or concatenated - names, hint strings, the layout - is built into a
 * {@link Model} only when the menu contents, the class table, a leap setting or the viewport change.
 * Per frame this reads that model and draws.
 */
public final class LeapScreenOverlay {

    private static final LeapScreenOverlay INSTANCE = new LeapScreenOverlay();

    private static final int PAD = 10;
    private static final int GAP = 6;
    private static final int BASE_BUTTON_W = 132;
    private static final int BASE_BUTTON_H = 40;
    private static final int MIN_BUTTON_H = 24;
    /** Preferred map edge; it shrinks to the room left under the buttons. */
    private static final int MAP_PREFERRED = 200;
    /** Below this the map is dropped (the least important element) rather than drawn unreadably. */
    private static final int MAP_MIN = 64;
    private static final int TOGGLE_PAD = 6;
    private static final int MAX_MARKERS = 16;

    private static final String TITLE = "Spirit Leap";
    private static final String MAP_ON = "Map: ON";
    private static final String MAP_OFF = "Map: OFF";
    private static final String NO_MAP = "No map";
    private static final String NO_TEAM = "No teammates in this menu";
    private static final String NOT_AVAILABLE = "not available";

    /** Everything a frame draws, built on change. */
    private record Model(List<Entry> entries, Rect[] buttons, String[] names, String[] classLines,
                         String[] hints, int[] hintWidths, int[] colors, Rect panel, Rect toggle, Rect map, int headSize) {
    }

    /** Every input the model depends on; a record so {@link CachedModel} compares by value. */
    private record Key(int containerId, int stateId, int classRefresh, int scale, boolean showMap,
                       int guiW, int guiH, int keys) {
    }

    private final CachedModel<Key, Model> cache = new CachedModel<>("leap.screen", key -> build());

    /** The screen the model is being built for - only set across {@link CachedModel#get}. */
    private AbstractContainerScreen<?> building;

    // Render thread only: the inputs of the last key, so a key object is made on change only.
    private int lastContainer = Integer.MIN_VALUE;
    private int lastState = -1;
    private int lastRefresh = -1;
    private int lastScale = -1;
    private boolean lastShowMap;
    private int lastW = -1;
    private int lastH = -1;
    private int lastKeys;
    private Model model;

    /** The screen whose title was last tested, and the answer - titles do not change while open. */
    private AbstractContainerScreen<?> titleScreen;
    private boolean titleIsLeap;

    // Render thread only: map markers drawn last frame, for hover and click.
    private final String[] markerName = new String[MAX_MARKERS];
    private final int[] markerX = new int[MAX_MARKERS];
    private final int[] markerY = new int[MAX_MARKERS];
    private int markerCount;
    private int markerRadius = 6;
    private final DungeonMapRenderer.MarkerSink sink = this::recordMarker;

    private LeapScreenOverlay() {
    }

    public static LeapScreenOverlay getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** Whether the custom screen covers this container right now (input and tooltips are ours). */
    public boolean isActive(AbstractContainerScreen<?> screen) {
        if (screen == null) {
            return false;
        }
        SBSConfig.DungeonsSettings cfg = cfg();
        if (!cfg.leapMenuEnabled || !cfg.leapCustomMenu) {
            return false;
        }
        if (screen != titleScreen) {
            titleScreen = screen;
            titleIsLeap = LeapMenu.isLeapMenu(screen);
            markerCount = 0;
        }
        return titleIsLeap;
    }

    /** True while the screen is up, so the hidden chest's hovered-slot tooltip never draws. */
    public boolean coversForTooltip(AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
        return isActive(screen);
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    private Model model(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        SBSConfig.DungeonsSettings cfg = cfg();
        AbstractContainerMenu menu = screen.getMenu();
        int keys = cfg.leapMageKey * 31 + cfg.leapArcherKey * 17 + cfg.leapBerserkKey * 13
                + cfg.leapTankKey * 7 + cfg.leapHealerKey;
        if (model == null || menu.containerId != lastContainer || menu.getStateId() != lastState
                || DungeonTeamClasses.refreshCount() != lastRefresh || cfg.leapButtonScale != lastScale
                || cfg.leapShowMap != lastShowMap || g.guiWidth() != lastW || g.guiHeight() != lastH
                || keys != lastKeys) {
            lastContainer = menu.containerId;
            lastState = menu.getStateId();
            lastRefresh = DungeonTeamClasses.refreshCount();
            lastScale = cfg.leapButtonScale;
            lastShowMap = cfg.leapShowMap;
            lastW = g.guiWidth();
            lastH = g.guiHeight();
            lastKeys = keys;
            building = screen;
            model = cache.get(new Key(lastContainer, lastState, lastRefresh, lastScale, lastShowMap,
                    lastW, lastH, lastKeys));
            building = null;
        }
        return model;
    }

    private Model build() {
        AbstractContainerScreen<?> screen = building;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        String self = mc.player == null ? null : mc.player.getName().getString();
        List<Entry> entries = LeapLayout.entries(LeapMenu.getInstance().heads(screen.getMenu()),
                DungeonTeamClasses.runRoster(), self, DungeonTeamClasses::classOf);
        int count = entries.size();

        int scale = Math.max(60, Math.min(160, lastScale));
        int availW = Math.max(1, lastW - SBSTheme.SCREEN_MARGIN * 2);
        int availH = Math.max(1, lastH - SBSTheme.SCREEN_MARGIN * 2);
        int innerMax = Math.max(1, availW - PAD * 2);
        int buttonW = Math.min(innerMax, BASE_BUTTON_W * scale / 100);
        int buttonH = Math.max(MIN_BUTTON_H, BASE_BUTTON_H * scale / 100);
        int maxColumns = Math.max(1, (innerMax + GAP) / (buttonW + GAP));
        int columns = LeapLayout.columns(count, maxColumns);
        int rows = Math.max(1, LeapLayout.rows(count, columns));
        int buttonsW = count == 0 ? Math.min(innerMax, font.width(NO_TEAM) + 16)
                : columns * buttonW + (columns - 1) * GAP;
        int buttonsH = rows * buttonH + (rows - 1) * GAP;

        int toggleW = Math.max(font.width(MAP_ON), font.width(MAP_OFF)) + TOGGLE_PAD * 2;
        int toggleH = font.lineHeight + 6;
        int headerH = PAD + toggleH + GAP;
        int headerW = font.width(TITLE) + 12 + toggleW;

        int mapSize = 0;
        if (lastShowMap) {
            int room = availH - headerH - buttonsH - GAP - PAD;
            mapSize = Math.min(MAP_PREFERRED * scale / 100, Math.min(room, innerMax));
            if (mapSize < MAP_MIN) {
                mapSize = 0;
            }
        }
        int contentW = Math.min(innerMax, Math.max(buttonsW, Math.max(headerW, mapSize)));
        int panelW = contentW + PAD * 2;
        int panelH = headerH + buttonsH + (mapSize > 0 ? GAP + mapSize : 0) + PAD;
        int panelX = (lastW - panelW) / 2;
        int panelY = Math.max(0, (lastH - panelH) / 2);
        Rect panel = new Rect(panelX, panelY, panelW, panelH);
        Rect toggle = new Rect(panelX + panelW - PAD - toggleW, panelY + PAD, toggleW, toggleH);
        int buttonsTop = panelY + headerH;
        Rect[] buttons = LeapLayout.tile(count, columns, buttonW, buttonH, GAP, panelX + panelW / 2, buttonsTop);
        Rect map = mapSize > 0
                ? new Rect(panelX + (panelW - mapSize) / 2, buttonsTop + buttonsH + GAP, mapSize, mapSize)
                : null;

        int headSize = Math.max(8, buttonH - 12);
        String[] names = new String[count];
        String[] classLines = new String[count];
        String[] hints = new String[count];
        int[] hintWidths = new int[count];
        int[] colors = new int[count];
        for (int i = 0; i < count; i++) {
            Entry entry = entries.get(i);
            int key = entry.cls() == 0 ? 0 : LeapMenu.keyFor(entry.cls());
            hints[i] = key == 0 ? "" : "[" + Keys.displayName(key).getString() + "]";
            hintWidths[i] = font.width(hints[i]);
            int textRoom = buttonW - 6 - headSize - 6 - 6 - (hints[i].isEmpty() ? 0 : font.width(hints[i]) + 4);
            names[i] = fit(font, entry.name(), textRoom);
            String line = !entry.available() ? NOT_AVAILABLE : LeapLayout.className(entry.cls());
            if (entry.self()) {
                line = line + " - you";
            }
            classLines[i] = fit(font, line, buttonW - 6 - headSize - 6 - 6);
            colors[i] = entry.cls() == 0 ? SBSTheme.TEXT_MUTED : DungeonTeamClasses.colorOf(entry.name(), SBSTheme.TEXT_MUTED);
        }
        return new Model(entries, buttons, names, classLines, hints, hintWidths, colors, panel, toggle, map, headSize);
    }

    private static String fit(Font font, String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (font.width(text) <= width) {
            return text;
        }
        int dots = font.width("...");
        return width <= dots ? "" : font.plainSubstrByWidth(text, width - dots) + "...";
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /** The card, the buttons and (when on) the map. Called from {@code ScreenForeground}. */
    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!isActive(screen)) {
            return;
        }
        Model m = model(screen, g);
        Font font = Minecraft.getInstance().font;
        Rect p = m.panel();

        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0x88000000);
        SciFiRender.glow(g, p.x(), p.y(), p.w(), p.h(), SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, p.x(), p.y(), p.w(), p.h(), SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, p.x() + 1, p.y() + 1, p.w() - 2, p.h() - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
        Rect t = m.toggle();
        g.text(font, TITLE, p.x() + PAD, t.y() + (t.h() - font.lineHeight) / 2 + 1, SBSTheme.ACCENT_BRIGHT, false);
        drawToggle(g, font, t, cfg().leapShowMap, t.contains(mouseX, mouseY));

        int hovered = hoveredEntry(m, mouseX, mouseY);
        AbstractContainerMenu menu = screen.getMenu();
        if (m.entries().isEmpty()) {
            g.centeredText(font, NO_TEAM, p.x() + p.w() / 2, p.y() + PAD + t.h() + GAP + 4, SBSTheme.TEXT_MUTED);
        }
        for (int i = 0; i < m.buttons().length; i++) {
            drawButton(g, font, m, i, menu, i == hovered);
        }

        markerCount = 0;
        Rect map = m.map();
        if (map != null) {
            SciFiRender.roundedRectRaw(g, map.x(), map.y(), map.w(), map.h(), 4, SBSTheme.SLOT_BG);
            boolean boss = DungeonStateManager.getInstance().phase() == DungeonEvents.Phase.BOSS;
            String highlight = hovered >= 0 ? m.entries().get(hovered).name() : null;
            markerRadius = Math.max(6, Math.round(14f * map.w() / DungeonMapRenderer.contentSize()));
            if (boss || !DungeonMapRenderer.drawScaled(g, map.x(), map.y(), map.w(), highlight, sink)) {
                markerCount = 0;
                g.centeredText(font, NO_MAP, map.x() + map.w() / 2, map.y() + (map.h() - font.lineHeight) / 2,
                        SBSTheme.TEXT_MUTED);
            }
        }
    }

    private static void drawToggle(GuiGraphicsExtractor g, Font font, Rect t, boolean on, boolean hover) {
        int edge = on ? SBSTheme.TOGGLE_ON : SBSTheme.TEXT_MUTED;
        SciFiRender.roundedRectRaw(g, t.x() - 1, t.y() - 1, t.w() + 2, t.h() + 2, 4, edge);
        SciFiRender.roundedRectRaw(g, t.x(), t.y(), t.w(), t.h(), 3, hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
        g.centeredText(font, on ? MAP_ON : MAP_OFF, t.x() + t.w() / 2, t.y() + (t.h() - font.lineHeight) / 2 + 1, edge);
    }

    private static void drawButton(GuiGraphicsExtractor g, Font font, Model m, int i, AbstractContainerMenu menu,
                                   boolean hovered) {
        Rect r = m.buttons()[i];
        Entry entry = m.entries().get(i);
        boolean available = entry.available();
        int color = m.colors()[i];
        int edge = !available ? SBSTheme.CARD_BORDER : hovered ? color : (color & 0x00FFFFFF) | 0x90000000;
        SciFiRender.roundedRectRaw(g, r.x() - 1, r.y() - 1, r.w() + 2, r.h() + 2, 5, edge);
        int fill = !available ? SBSTheme.CARD_BG_DISABLED : hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG;
        SciFiRender.roundedRectRaw(g, r.x(), r.y(), r.w(), r.h(), 4, fill | 0xFF000000);
        g.fill(r.x() + 1, r.y() + 3, r.x() + 4, r.y() + r.h() - 3, available ? color : SBSTheme.TEXT_MUTED);

        int head = m.headSize();
        int hx = r.x() + 6;
        int hy = r.y() + (r.h() - head) / 2;
        PlayerSkin skin = DungeonTeamClasses.skinOf(entry.name());
        if (skin != null) {
            PlayerFaceExtractor.extractRenderState(g, skin, hx, hy, head);
        } else if (available) {
            ItemStack stack = menu.getSlot(entry.slot()).getItem();
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(hx + head / 2f, hy + head / 2f);
            pose.scale(head / 16f);
            g.item(stack, -8, -8);
            pose.popMatrix();
        }
        if (!available) {
            g.fill(hx, hy, hx + head, hy + head, 0xA0000000);
        }

        int tx = hx + head + 6;
        int lines = font.lineHeight * 2 + 2;
        int ty = r.y() + (r.h() - lines) / 2 + 1;
        g.text(font, m.names()[i], tx, ty, available ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED, false);
        g.text(font, m.classLines()[i], tx, ty + font.lineHeight + 2, available ? color : SBSTheme.TEXT_MUTED, false);
        String hint = m.hints()[i];
        if (!hint.isEmpty()) {
            g.text(font, hint, r.x() + r.w() - 5 - m.hintWidths()[i], r.y() + 4, SBSTheme.TEXT_MUTED, false);
        }
    }

    /** Called by the map renderer for every marker it draws this frame. Render thread only. */
    private void recordMarker(String name, boolean self, int x, int y) {
        if (markerCount < MAX_MARKERS && !self) {
            markerName[markerCount] = name;
            markerX[markerCount] = x;
            markerY[markerCount] = y;
            markerCount++;
        }
    }

    /** The button under the cursor, or the button of the map marker under it, or -1. */
    private int hoveredEntry(Model m, double mouseX, double mouseY) {
        int button = LeapLayout.hit(m.buttons(), mouseX, mouseY);
        if (button >= 0) {
            return button;
        }
        Rect map = m.map();
        if (map == null || !map.contains(mouseX, mouseY)) {
            return -1;
        }
        int best = -1;
        double bestDist = (double) markerRadius * markerRadius;
        for (int i = 0; i < markerCount; i++) {
            double dx = mouseX - markerX[i];
            double dy = mouseY - markerY[i];
            double dist = dx * dx + dy * dy;
            if (dist <= bestDist) {
                int entry = LeapLayout.entryForMarker(m.entries(), markerName[i]);
                if (entry >= 0) {
                    best = entry;
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * The map toggle, a button, or a teammate's map marker; every click inside the screen is
     * swallowed while it is up. Only a left click leaps.
     */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!isActive(screen) || model == null) {
            return false;
        }
        Model m = model;
        if (event.button() != 0) {
            return true;
        }
        if (m.toggle().contains(event.x(), event.y())) {
            SBSConfig.DungeonsSettings cfg = cfg();
            cfg.leapShowMap = !cfg.leapShowMap;
            ConfigManager.getInstance().save();
            return true;
        }
        int entry = hoveredEntry(m, event.x(), event.y());
        if (entry >= 0 && m.entries().get(entry).available()) {
            leap(screen, m.entries().get(entry).slot());
        }
        return true;
    }

    /** One left-click PICKUP on the head slot - exactly what clicking the head does. */
    private static void leap(AbstractContainerScreen<?> screen, int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) {
            return;
        }
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }
}
