/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.economy.prices.ItemPriceKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Frame-by-frame capture of the Crop Analyzer, under {@code [SBS][Analyzer]}. The research half of
 * {@link GreenhouseCapture}, on the same switch (dev mode or Garden Helpers -> Greenhouse Capture).
 *
 * <p><b>Why a second class and not more of GreenhouseCapture.</b> That one is gated on the Garden;
 * the analyzer is in the Underground Lab or opened through Jake on the Abiphone, so from anywhere.
 * This one is gated on the menu instead.
 *
 * <p><b>Why not the Layout Recorder.</b> It stores each layout once after the menu has settled, which
 * is right for a static menu and wrong here: if the analysis is a minigame, the frames in between and
 * their timing ARE the data. So every state-id change is logged with a timestamp, and only a frame
 * identical to the one before it is skipped ({@link #isNewFrame}).
 *
 * <p><b>What it logs</b> while an analyzer menu is open, and for {@link #TRAIL_MS} after it closes
 * (a result may arrive after the menu shuts):
 * <ul>
 *   <li>{@code frame #n +Tms title='…' state=S} then one {@code slot i id=… x… glint name='…' lore=[…]}
 *       line per non-empty menu slot;</li>
 *   <li>every chat line, title, subtitle and action-bar text ({@code chat|title|subtitle|actionbar}).</li>
 * </ul>
 * Capped at {@link #MAX_FRAMES} frames per session so a menu left open cannot flood the log.
 *
 * <p><b>The title is unknown</b>, so the match is broad ({@link #isAnalyzerTitle}) and any menu
 * opened straight after an analyzer menu (a sub-screen with a different title) is captured too
 * while the trail window runs. Reads only - nothing is clicked.
 */
public final class CropAnalyzerCapture {

    private static final CropAnalyzerCapture INSTANCE = new CropAnalyzerCapture();

    /** How long after the analyzer closes chat and follow-on menus are still captured. */
    static final long TRAIL_MS = 10_000L;

    /** Frames per session. A long minigame is maybe a few hundred state changes. */
    static final int MAX_FRAMES = 600;

    /** Title words that probably mean the analyzer. Lower case, substrings. UNVERIFIED guesses. */
    static final String[] TITLE_WORDS = {"crop analyzer", "analyzer", "analysis", "analyze", "dna"};

    private Screen lastScreen;
    private int lastState = Integer.MIN_VALUE;
    private String lastFrame = "";
    private int frames;
    private long sessionStart;
    /** When the last analyzer (or follow-on) frame was seen; 0 = not capturing. */
    private long activeUntil;
    private boolean capWarned;

    private CropAnalyzerCapture() {
    }

    public static CropAnalyzerCapture getInstance() {
        return INSTANCE;
    }

    /** Whether a (colour-stripped) menu title looks like the Crop Analyzer. Pure, for the tests. */
    static boolean isAnalyzerTitle(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        for (String word : TITLE_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this frame differs from the previous one. The state id alone is not enough: Hypixel
     * bumps it on re-sends that change nothing, and those would double the log. Pure.
     */
    static boolean isNewFrame(String previous, String frame) {
        return frame != null && !frame.equals(previous);
    }

    private static boolean enabled() {
        return GreenhouseCapture.enabledForCapture();
    }

    private boolean capturing(long now) {
        return activeUntil != 0L && now <= activeUntil;
    }

    /** Client tick: log a frame when the menu changed. */
    public void tick() {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            lastScreen = null;
            return;
        }
        String title = StyledText.strip(container.getTitle().getString()).trim();
        boolean analyzer = isAnalyzerTitle(title);
        if (!analyzer && !capturing(now)) {
            return;
        }
        if (analyzer) {
            if (activeUntil == 0L || now > activeUntil) {
                sessionStart = now;
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Analyzer] session start title='{}'", title);
            }
            activeUntil = now + TRAIL_MS;
        }
        int state = container.getMenu().getStateId();
        if (screen == lastScreen && state == lastState) {
            return;
        }
        lastScreen = screen;
        lastState = state;
        List<String> lines = slotLines(container);
        String frame = title + "\n" + String.join("\n", lines);
        if (!isNewFrame(lastFrame, frame)) {
            return;
        }
        lastFrame = frame;
        if (frames >= MAX_FRAMES) {
            if (!capWarned) {
                capWarned = true;
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Analyzer] frame cap {} reached - no more frames "
                        + "this session", MAX_FRAMES);
            }
            return;
        }
        frames++;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Analyzer] frame #{} +{}ms title='{}' state={} slots={}",
                frames, now - sessionStart, title, state, lines.size());
        for (String line : lines) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Analyzer]   {}", line);
        }
    }

    private static List<String> slotLines(AbstractContainerScreen<?> container) {
        List<String> out = new ArrayList<>();
        for (Slot slot : container.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String id = SkyblockItem.id(stack);
            if (id == null || id.isEmpty()) {
                id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            }
            out.add("slot " + slot.index + " id=" + id + " x" + stack.getCount()
                    + (stack.hasFoil() ? " glint" : "")
                    + " name='" + StyledText.strip(stack.getHoverName().getString()) + "'"
                    + " lore=" + ItemPriceKey.lore(stack));
        }
        return out;
    }

    /** Chat, title, subtitle or action bar text; logged only while capturing. */
    public void onText(String kind, String raw) {
        if (raw == null || raw.isBlank() || !enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!capturing(now)) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Analyzer] {} +{}ms '{}'", kind, now - sessionStart,
                StyledText.strip(raw));
    }
}
