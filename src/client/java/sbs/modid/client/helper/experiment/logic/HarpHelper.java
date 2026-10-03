/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.ui.hud.logic.ServerStatsTracker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Melody's Harp helper: which lane to click and when, with the cue moved earlier by ping + reaction.
 *
 * <p><b>Display only.</b> Nothing here clicks, sends, queues, delays or cancels a click: the click
 * hook only <i>records</i> the player's own clicks for the timing log, and always lets them through.
 *
 * <p>The board is read once per rendered frame, so a board change is timed at most one frame after
 * its packets were handled (they are handled on the client thread before the frame).
 */
public final class HarpHelper {

    private static final HarpHelper INSTANCE = new HarpHelper();

    private static final String TITLE_PREFIX = "Harp - ";
    private static final long CHAT_WINDOW_MS = 2_000L;
    private static final long PING_EVERY_MS = 2_000L;
    private static final int PING_SAMPLES = 10;

    private final HarpModel model = new HarpModel();
    private final Deque<Integer> pings = new ArrayDeque<>();
    private String song = "";
    private String lastCompact = "";
    /** Step 1 log: the one song logged this session (the cap), empty until the first. */
    private String loggedSong = "";
    private long lastClickAt;
    private long lastPingAt;

    private HarpHelper() {
    }

    public static HarpHelper getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.ExperimentationSettings cfg() {
        return ConfigManager.getInstance().get().experimentation;
    }

    /** "La Vie en Rose" when {@code title} is a Harp board, else {@code null}. */
    static String songOf(String title) {
        return title != null && title.startsWith(TITLE_PREFIX) ? title.substring(TITLE_PREFIX.length()).trim() : null;
    }

    private static String titleOf(AbstractContainerScreen<?> screen) {
        return screen.getTitle() == null ? "" : screen.getTitle().getString().replaceAll("§.", "").trim();
    }

    private boolean logging() {
        return cfg().enabled && (loggedSong.isEmpty() || loggedSong.equals(song));
    }

    // ------------------------------------------------------------------ render (every frame)

    public void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        String current = songOf(titleOf(screen));
        if (current == null || !cfg().enabled) {
            if (!song.isEmpty()) {
                model.reset();
                song = "";
                lastCompact = "";
            }
            return;
        }
        if (!current.equals(song)) {
            model.reset();
            song = current;
            lastCompact = "";
            pings.clear();
        }
        long now = System.currentTimeMillis();
        AbstractContainerMenu menu = screen.getMenu();
        List<String> ids = new ArrayList<>(54);
        for (int i = 0; i < 54 && i < menu.slots.size(); i++) {
            ids.add(BuiltInRegistries.ITEM.getKey(menu.slots.get(i).getItem().getItem()).toString());
        }
        char[][] grid = HarpModel.grid(ids);
        if (grid == null) {
            return;
        }
        String compact = HarpModel.compact(grid);
        if (!compact.equals(lastCompact)) {
            lastCompact = compact;
            boolean step = model.onFrame(grid, now);
            if (logging()) {
                loggedSong = song;
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Harp] t={} song='{}' grid={} step={} stepMs={}",
                        now, song, compact, step, model.stepMs());
            }
        }
        samplePing(now);
        if (cfg().harp) {
            draw(screen, g, menu, now);
        }
    }

    private void samplePing(long now) {
        if (!cfg().harp || !cfg().harpPingAuto || now - lastPingAt < PING_EVERY_MS) {
            return;
        }
        lastPingAt = now;
        ServerStatsTracker.getInstance().measurePing(ms -> {
            if (ms > 0) {
                pings.addLast(ms);
                if (pings.size() > PING_SAMPLES) {
                    pings.removeFirst();
                }
            }
        });
    }

    /** The ping in use: the rolling average in Auto (-1 while unmeasured), else the manual value. */
    int pingMs() {
        if (!cfg().harpPingAuto) {
            return cfg().harpManualPingMs;
        }
        if (pings.isEmpty()) {
            return -1;
        }
        return (int) Math.round(pings.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    private void draw(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, AbstractContainerMenu menu,
                      long now) {
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        int color = color();
        int hitRow = Math.max(0, Math.min(HarpModel.ROWS - 1, cfg().harpHitRow));
        int ping = pingMs();
        int reaction = cfg().harpReactionMs;
        long lead = HarpModel.leadMs(Math.max(0, ping), reaction);
        long step = model.stepMs();

        // Next-notes preview: every note above the hit row, brighter the closer it is.
        for (HarpModel.Note note : model.notes()) {
            if (note.row() > hitRow) {
                continue;
            }
            int distance = hitRow - note.row();
            int alpha = Math.max(0x30, 0xD0 - distance * 0x30);
            Slot slot = menu.slots.get(note.row() * 9 + note.lane() + 1);
            ExperimentationTable.outline(g, left + slot.x, top + slot.y, (alpha << 24) | (color & 0xFFFFFF));
        }

        // Timing cue: light the lane's hit slot from (arrival - lead) until the step after arrival.
        Font font = Minecraft.getInstance().font;
        for (HarpModel.Arrival arrival : model.nextArrivals(hitRow)) {
            long cueAt = arrival.atMs() - lead;
            if (now < cueAt || now > arrival.atMs() + Math.max(step, 0)) {
                continue;
            }
            Slot slot = menu.slots.get(hitRow * 9 + arrival.note().lane() + 1);
            int x = left + slot.x;
            int y = top + slot.y;
            g.fill(x, y, x + 16, y + 16, (0x90 << 24) | (color & 0xFFFFFF));
            ExperimentationTable.outline(g, x, y, color);
            if (cfg().harpFlash) {
                g.text(font, Component.literal("NOW"), x + 8 - font.width("NOW") / 2, y + 4, 0xFFFFFFFF);
            }
        }

        // The lead in use, in the corner, so the player can see what the cue is shifted by.
        String pingText = ping < 0 ? "?" : String.valueOf(ping);
        String line = step <= 0
                ? "Harp: measuring the note speed..."
                : "lead " + lead + " ms = " + pingText + " ping + " + reaction + " reaction";
        g.text(font, Component.literal(line), left + 8, top - 10, 0xFFB0C4DE);
    }

    private static int color() {
        String hex = cfg().harpColorHex;
        try {
            return 0xFF000000 | Integer.parseInt(hex == null || hex.isBlank() ? "5DE0A0" : hex, 16);
        } catch (NumberFormatException e) {
            return 0xFF5DE0A0;
        }
    }

    // ------------------------------------------------------------------ Step 1 log: clicks + chat

    /** Records a click on the Harp board for the log. Never consumes it - always returns nothing. */
    public void onClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (song.isEmpty() || !logging() || songOf(titleOf(screen)) == null) {
            return;
        }
        Slot slot = ((AbstractContainerScreenAccessor) screen).skyblockSimplified$hoveredSlot();
        int index = slot == null ? -1 : screen.getMenu().slots.indexOf(slot);
        lastClickAt = System.currentTimeMillis();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Harp] click t={} slot={} button={} grid={}", lastClickAt,
                index, event.button(), lastCompact);
    }

    /** Chat within 2 s of a logged click: whatever Hypixel says about a hit or a miss. */
    public void onChat(String text) {
        if (text == null || song.isEmpty() || System.currentTimeMillis() - lastClickAt > CHAT_WINDOW_MS) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Harp] chat t={} '{}'", System.currentTimeMillis(),
                text.replaceAll("§.", ""));
    }
}
