/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.foraging.logic.BeaconTuningSolver.Mark;
import sbs.modid.client.skills.foraging.logic.BeaconTuningSolver.Solution;
import sbs.modid.client.skills.foraging.model.BeaconPanel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Beacon Tuning helper (Foraging): marks the option matching the beat in Galatea's beacon menus.
 *
 * <p>Both of Galatea's beacons - the Moonglade Beacon in the South Reaches and the Torrhus Canyon one
 * - ask for the same thing: reproduce a beat by matching its colour, its speed and its pitch. The menu
 * states the beat and offers the picks, so the whole helper is a read of the open container:
 * {@link BeaconTuningSolver} turns it into "this slot, for that trait" and this class rings the slot.
 *
 * <p><b>Read-only, like every menu helper here.</b> It scans on the client tick, draws on the frame,
 * and never clicks: a ringed slot is a suggestion the player acts on, which is the only shape a
 * helper in this mod is allowed to take. Nothing is vetoed either - a mistuned panel costs a beat,
 * not a run, and a guard built on an unverified menu would be a guard that locks the player out of
 * their own minigame.
 *
 * <p><b>Marks.</b> A bright ring with the trait's letter is the pick still to make; a quiet thin ring
 * is a trait the menu already has right, so "two down, one to go" reads at a glance. A trait the scan
 * cannot pin down gets no ring at all and says why in {@code [SBS][Beacon]}.
 *
 * <p><b>The layout is not confirmed.</b> Nobody here has stood at a beacon, so the menu is read by
 * wording rather than by slot index and the parser is deliberately the tolerant kind. Every state
 * the server sends for a beacon menu is logged once under {@code [SBS][Beacon]} - title, every
 * filled slot, every lore line - and {@code /sbs beacon} says which gate stopped a blank overlay.
 * Those two, or a {@code /sbs probe arm} capture at the beacon, are what turn the remaining guesses
 * into facts.
 *
 * <p><b>What is still a guess, stated plainly:</b> that the beat is <i>written out</i> in the menu
 * at all. If it is instead telegraphed by animating panes, it is a signal over time and no
 * single-frame read of the container can recover it - the speed least of all. In that case the beat
 * has to come from {@code skills.foraging.beacon.BeaconBeatReader}, which already measures one, and
 * this class becomes the half that matches options against it rather than the half that reads it.
 */
public final class BeaconTuning {

    private static final BeaconTuning INSTANCE = new BeaconTuning();

    /** Bright ring: this is the pick that still has to be made. */
    private static final int COLOR_TODO = 0xFF55FF55;

    /** Quiet ring: this trait already matches the beat, nothing to do here. */
    private static final int COLOR_DONE = 0x8055CCFF;

    private static final long HEARTBEAT_MS = 3_000L;

    private Solution solution = Solution.EMPTY;
    private Object lastScreen;
    private long lastHeartbeatAt;

    /** The menu state the dump below has already written, so one state is logged once. */
    private int dumpedState = Integer.MIN_VALUE;

    private BeaconTuning() {
    }

    public static BeaconTuning getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.BeaconTuningSettings cfg() {
        return ConfigManager.getInstance().get().beaconTuning;
    }

    // ------------------------------------------------------------------
    // Tick scan
    // ------------------------------------------------------------------

    public void tick(Minecraft minecraft) {
        if (minecraft == null || !cfg().enabled) {
            clear();
            return;
        }
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        // Screen-change detection on the tick path rather than a screen hook: this is the path the
        // other menu readers prove works for menus Hypixel opens on the server's say-so.
        if (screen != lastScreen) {
            lastScreen = screen;
            solution = Solution.EMPTY;
            dumpedState = Integer.MIN_VALUE;
        }
        if (!(screen instanceof AbstractContainerScreen<?> container)
                || !isBeaconMenu(titleOf(screen))
                || !SkillIslands.foragingAllowed()) {
            solution = Solution.EMPTY;
            return;
        }
        AbstractContainerMenu menu = container.getMenu();
        List<BeaconPanel> panels = panels(menu);
        dumpOnChange(screen, menu, panels);
        solution = BeaconTuningSolver.solve(panels, gridWidth(menu));
        heartbeat();
    }

    /**
     * The container's grid width, so the solver's "next to" means what it means on screen.
     *
     * <p>Read off the chest's own row count where there is one, rather than assumed: the slot count
     * alone cannot tell a nine-slot dispenser (three wide) from a one-row chest (nine wide). Every
     * beacon menu seen so far is a chest, and the fallback says so rather than pretending otherwise.
     */
    private static int gridWidth(AbstractContainerMenu menu) {
        int upper = upperSlots(menu);
        if (menu instanceof ChestMenu chest) {
            int rows = chest.getRowCount();
            if (rows > 0 && upper % rows == 0) {
                return upper / rows;
            }
        }
        return upper > 0 && upper % BeaconTuningSolver.CHEST_WIDTH == 0
                ? BeaconTuningSolver.CHEST_WIDTH
                : Math.max(1, upper);
    }

    /**
     * Why nothing is on screen, in one line, for {@code /sbs beacon}.
     *
     * <p>Every gate this helper has, in the order it applies them, answered against the live client.
     * A menu helper that draws nothing is indistinguishable from a broken one from the outside, and
     * this feature spent its whole life so far in exactly that state - so "which gate said no" is
     * something the player can now ask instead of something only a log file knows.
     */
    public String status() {
        if (!cfg().enabled) {
            return "switched off in the settings - nothing is scanned";
        }
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            return "no menu open - open a beacon menu at " + SkyBlockLocation.describe();
        }
        String title = titleOf(screen);
        if (!isBeaconMenu(title)) {
            return "the open menu \"" + title + "\" is not recognised as a beacon menu"
                    + " - run /sbs probe with it open and send the file back";
        }
        if (!SkillIslands.foragingAllowed()) {
            return "\"" + title + "\" is a beacon menu, but " + SkyBlockLocation.describe()
                    + " is not a foraging island - switch \"Only on Galatea\" off to read it anyway";
        }
        return "reading \"" + title + "\" at " + SkyBlockLocation.describe() + " - "
                + BeaconTuningSolver.describe(solution);
    }

    /**
     * The menu's own slots as plain text. Everything Minecraft-shaped ends here: the solver works on
     * these records, which is what lets its rules be exercised on a bench rather than only in Galatea.
     */
    private static List<BeaconPanel> panels(AbstractContainerMenu menu) {
        int upper = upperSlots(menu);
        List<BeaconPanel> panels = new ArrayList<>(upper);
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            panels.add(new BeaconPanel(i, stack.getHoverName().getString(), idPath(stack),
                    stack.hasFoil(), lore(stack)));
        }
        return panels;
    }

    private void clear() {
        solution = Solution.EMPTY;
        lastScreen = null;   // so switching the module back on re-reads the open menu
        dumpedState = Integer.MIN_VALUE;
    }

    /**
     * The words a beacon menu's title is built from.
     *
     * <p><b>{@code signal} is here because its absence was a confirmed bug.</b> The Signal Enhancer
     * variant is titled "Upgrade Signal Strength", which contains none of {@code beacon},
     * {@code tuning} or {@code frequency} - so the harder of the two menus failed the gate outright,
     * and because the diagnostic below is gated on the same test, it failed without even logging
     * that it had. That is the shape of failure this list exists to prevent, so a title is now
     * matched on any one of these rather than on the three the first guess happened to cover.
     */
    private static final String[] TITLE_WORDS = {
            "beacon", "tuning", "frequency", "signal"};

    /**
     * Whether the title names a beacon menu. Kept broad - the beacon's own name, plus the words the
     * minigame is built on - because the island gate is what makes it safe to be broad, and a title
     * we fail to recognise is a helper that silently does nothing.
     */
    private static boolean isBeaconMenu(String title) {
        String norm = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        for (String word : TITLE_WORDS) {
            if (norm.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Render (from OverlayRenderMixin, slot-relative like the other menu helpers)
    // ------------------------------------------------------------------

    public void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Solution current = solution;
        if (!cfg().enabled || current.isEmpty() || screen != lastScreen) {
            return;
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        AbstractContainerMenu menu = screen.getMenu();
        int slotCount = menu.getItems().size();
        Font font = Minecraft.getInstance().font;
        for (Map.Entry<Integer, Mark> entry : current.marks().entrySet()) {
            int index = entry.getKey();
            if (index < 0 || index >= slotCount) {
                continue;
            }
            Mark mark = entry.getValue();
            if (mark.satisfied() && !cfg().markMatching) {
                continue;
            }
            Slot slot = menu.getSlot(index);
            int x = left + slot.x;
            int y = top + slot.y;
            int color = mark.satisfied() ? COLOR_DONE : COLOR_TODO;
            if (mark.satisfied()) {
                thinOutline(g, x, y, color);
            } else {
                outline(g, x, y, color);
            }
            g.text(font, Component.literal(String.valueOf(mark.trait().letter())), x + 1, y + 1, color);
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    /**
     * Log of the whole menu, once per state the server sends. This is the artifact that finishes the
     * feature: it says what the title really is, which slots carry which wording, and therefore
     * whether a blank overlay means "no beat stated" or "title never recognised".
     *
     * <p><b>Keyed on {@link AbstractContainerMenu#getStateId()}, not on the screen opening</b>, and
     * that is a fix rather than a preference. The server fills a container's slots in <i>after</i>
     * the screen exists - the same fact {@code BazaarPrerender} is built around - so the previous
     * version, which logged once on the tick the screen changed, was dumping an empty chest and
     * calling it evidence. That is why this feature could be "run /sbs probe at the beacon" for its
     * whole life and never produce a usable line. Re-logging on every state change also captures a
     * menu that animates, which is exactly what a telegraphed beat would do.
     */
    private void dumpOnChange(Screen screen, AbstractContainerMenu menu, List<BeaconPanel> panels) {
        int stateId = menu.getStateId();
        if (stateId == dumpedState) {
            return;
        }
        dumpedState = stateId;
        String title = titleOf(screen);
        StringBuilder slots = new StringBuilder();
        for (BeaconPanel panel : panels) {
            slots.append("\n  [").append(panel.slot()).append("] ").append(panel.itemId())
                    .append(" \"").append(panel.name()).append('"')
                    .append(panel.glint() ? " *FOIL" : "");
            for (String line : panel.lore()) {
                if (!line.isEmpty()) {
                    slots.append("\n      | ").append(line);
                }
            }
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Beacon] menu title=\"{}\" at {}, {} filled slots{}",
                title, SkyBlockLocation.describe(), panels.size(), slots);
    }

    /** Throttled heartbeat while a beacon menu is open: what the scan currently understands. */
    private void heartbeat() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatAt < HEARTBEAT_MS) {
            return;
        }
        lastHeartbeatAt = now;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Beacon] {}", BeaconTuningSolver.describe(solution));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Slots belonging to the menu itself: everything above the 36 player-inventory slots. */
    private static int upperSlots(AbstractContainerMenu menu) {
        return Math.max(0, menu.getItems().size() - 36);
    }

    private static String titleOf(Screen screen) {
        return screen == null || screen.getTitle() == null ? ""
                : PlainText.strip(screen.getTitle().getString()).trim();
    }

    private static String idPath(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    /** The stack's lore lines; {@link BeaconPanel} strips their colour codes as it takes them. */
    private static List<String> lore(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(lore.lines().size());
        for (var line : lore.lines()) {
            lines.add(line.getString());
        }
        return lines;
    }

    /** 2px accent outline hugging a 16px slot (same as the terminal and experiment helpers). */
    private static void outline(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x - 1, y - 1, x + 17, y + 1, color);
        g.fill(x - 1, y + 15, x + 17, y + 17, color);
        g.fill(x - 1, y - 1, x + 1, y + 17, color);
        g.fill(x + 15, y - 1, x + 17, y + 17, color);
    }

    /** The same ring at 1px, for a trait that is already set and only wants confirming. */
    private static void thinOutline(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x, y, x + 16, y + 1, color);
        g.fill(x, y + 15, x + 16, y + 16, color);
        g.fill(x, y, x + 1, y + 16, color);
        g.fill(x + 15, y, x + 16, y + 16, color);
    }
}
