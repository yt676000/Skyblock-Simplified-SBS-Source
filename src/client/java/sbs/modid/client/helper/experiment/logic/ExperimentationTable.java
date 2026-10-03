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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.ui.hud.logic.PetTracker;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;

import java.util.Locale;

/**
 * Coordinator for the three Experimentation Table minigame helpers (Enchanting area):
 * <ul>
 *   <li><b>Chronomatron</b> - remembers the flashed colour sequence and highlights it back in order;
 *   <li><b>Ultrasequencer</b> - records the order the tiles were revealed in and counts them back;
 *   <li><b>Superpairs</b> - keeps revealed icons visible after they flip back over.
 * </ul>
 *
 * <p>It is <b>read-only</b> against the real menu: it scans the open container each client tick (never
 * in render), draws its overlay through the shared {@code GuiGraphicsExtractor}, and - for Chronomatron
 * and Ultrasequencer only - can veto an out-of-order click so a slip never breaks the round. The logic
 * reads the board straight from the menu (the flashed slot carries the enchant glint; a sequencer tile
 * counts from the moment it is uncovered; a revealed pair is any non-cover item).
 *
 * <p>The exact board encoding can only be confirmed in-game, so a throttled diagnostic line
 * ({@code [SBS][Experiment] ...}) reports what each solver currently sees - read the instance log if a
 * helper looks off.
 */
public final class ExperimentationTable {

    private static final ExperimentationTable INSTANCE = new ExperimentationTable();

    /** Which minigame (if any) is open. NONE also covers the plain "Experimentation Table" menu. */
    public enum Game { NONE, CHRONOMATRON, ULTRASEQUENCER, SUPERPAIRS }

    private final ChronomatronSolver chronomatron = new ChronomatronSolver();
    private final UltrasequencerSolver ultrasequencer = new UltrasequencerSolver();
    private final SuperpairsSolver superpairs = new SuperpairsSolver();

    private Game game = Game.NONE;
    private Object lastScreen;
    private long lastAlertAt;
    private long lastDebugAt;

    private ExperimentationTable() {
    }

    public static ExperimentationTable getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.ExperimentationSettings cfg() {
        return ConfigManager.getInstance().get().experimentation;
    }

    // ------------------------------------------------------------------
    // Title detection
    // ------------------------------------------------------------------

    /**
     * The minigame a menu title names, or NONE. Titles carry a "(Round X)" suffix while playing, and
     * Hypixel spaces them inconsistently ("Ultra Sequencer", "Super Pairs"), so the title is reduced
     * to bare letters before matching.
     */
    private static Game gameOf(String title) {
        String norm = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (norm.contains("chronomatron")) {
            return Game.CHRONOMATRON;
        }
        if (norm.contains("ultrasequencer")) {
            return Game.ULTRASEQUENCER;
        }
        if (norm.contains("superpairs")) {
            return Game.SUPERPAIRS;
        }
        return Game.NONE;
    }

    /** Whether the title is any Experimentation Table screen (a game or the selection menu). */
    private static boolean isExperimentScreen(String title) {
        String norm = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return gameOf(title) != Game.NONE || norm.contains("experiment");
    }

    /** Broad match for the diagnostic log, so an unexpected title still surfaces its real name. */
    private static boolean looksExperimentish(String title) {
        String norm = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return norm.contains("experiment") || norm.contains("chrono") || norm.contains("matron")
                || norm.contains("ultra") || norm.contains("sequenc") || norm.contains("pairs")
                || norm.contains("superpair");
    }

    private static String titleOf(Screen screen) {
        return screen == null || screen.getTitle() == null ? ""
                : screen.getTitle().getString().replaceAll("§.", "").trim();
    }

    // ------------------------------------------------------------------
    // Screen change: reset + the Guardian-pet alert
    // ------------------------------------------------------------------

    /** Reset the solvers + fire the Guardian alert whenever the active screen changes. */
    private void onScreenChanged(Screen screen) {
        lastScreen = screen;
        chronomatron.reset();
        ultrasequencer.reset();
        superpairs.reset();
        game = Game.NONE;
        String title = titleOf(screen);
        if (screen != null && looksExperimentish(title)) {
            // Surface the exact title once per open - if a helper still does nothing, this line in
            // the instance log says whether the title simply was not recognised.
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Experiment] opened screen title=\"{}\"", title);
        }
        if (cfg().guardianPetAlert && screen != null && isExperimentScreen(title)) {
            guardianPetAlert();
        }
    }

    /** Warns (at most every 30s) when the active pet is not a Guardian - the Experimentation XP pet. */
    private void guardianPetAlert() {
        long now = System.currentTimeMillis();
        if (now - lastAlertAt < 30_000L) {
            return;   // debounce: opening the hub then a game must not warn twice
        }
        PetTracker pet = PetTracker.getInstance();
        boolean guardian = pet.hasPet() && pet.name().toLowerCase(Locale.ROOT).contains("guardian");
        if (!guardian) {
            lastAlertAt = now;
            SBSChat.send(net.minecraft.network.chat.Component.literal(
                            " No Guardian pet equipped - Experimentation XP is much lower without it.")
                    .withColor(0xFFE0605F));
        }
    }

    // ------------------------------------------------------------------
    // Per-tick scan
    // ------------------------------------------------------------------

    public void tick(Minecraft minecraft) {
        if (minecraft == null || !cfg().enabled) {
            game = Game.NONE;
            lastScreen = null;   // so toggling the module back on re-detects the open screen
            return;
        }
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        // Screen-change detection lives HERE, not in a setScreenAndShow hook: this tick path is the
        // one PetTracker already proves works for server-opened Hypixel menus.
        if (screen != lastScreen) {
            onScreenChanged(screen);
        }
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            game = Game.NONE;
            return;
        }
        String title = titleOf(screen);
        game = gameOf(title);
        AbstractContainerMenu menu = container.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        switch (game) {
            case CHRONOMATRON -> {
                if (cfg().chronomatron) {
                    chronomatron.scan(menu, upper, title);
                }
            }
            case ULTRASEQUENCER -> {
                if (cfg().ultrasequencer) {
                    ultrasequencer.scan(menu, upper, title);
                }
            }
            case SUPERPAIRS -> {
                if (cfg().superpairs) {
                    superpairs.scan(menu, upper, title);
                }
            }
            default -> { }
        }
        diagnostic();
    }

    /** Throttled heartbeat (~every 3s while a game is open): what each solver currently sees. */
    private void diagnostic() {
        if (game == Game.NONE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDebugAt < 3_000L) {
            return;
        }
        lastDebugAt = now;
        String state = switch (game) {
            case CHRONOMATRON -> chronomatron.debug();
            case ULTRASEQUENCER -> ultrasequencer.debug();
            case SUPERPAIRS -> superpairs.debug();
            default -> "";
        };
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Experiment] {} {}", game, state);
    }

    // ------------------------------------------------------------------
    // Render (from OverlayRenderMixin, before the tooltip flush)
    // ------------------------------------------------------------------

    public void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!cfg().enabled || game == Game.NONE) {
            return;
        }
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        Font font = Minecraft.getInstance().font;
        AbstractContainerMenu menu = screen.getMenu();
        switch (game) {
            case CHRONOMATRON -> {
                if (cfg().chronomatron) {
                    chronomatron.render(g, font, menu, left, top);
                }
            }
            case ULTRASEQUENCER -> {
                if (cfg().ultrasequencer) {
                    ultrasequencer.render(g, font, menu, left, top);
                }
            }
            case SUPERPAIRS -> {
                if (cfg().superpairs) {
                    superpairs.render(g, font, menu, left, top);
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------
    // Misclick prevention (Chronomatron / Ultrasequencer only)
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} to CANCEL a click that would be an out-of-order mistake. Only vetoes
     * left-clicks on a playable slot of Chronomatron / Ultrasequencer while block-misclicks is on;
     * a correct click is let through (and advances the solver). Never touches Superpairs.
     */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!cfg().enabled || event.button() != 0) {
            return false;
        }
        boolean chrono = game == Game.CHRONOMATRON && cfg().chronomatron;
        boolean ultra = game == Game.ULTRASEQUENCER && cfg().ultrasequencer;
        if (!chrono && !ultra) {
            return false;
        }
        AbstractContainerMenu menu = screen.getMenu();
        Slot slot = ((AbstractContainerScreenAccessor) screen).skyblockSimplified$hoveredSlot();
        if (slot == null) {
            return false;
        }
        int index = menu.slots.indexOf(slot);
        int upper = Math.max(0, menu.getItems().size() - 36);
        if (index < 0 || index >= upper) {
            return false;   // player inventory / outside the board is always free
        }
        boolean allowed = chrono ? chronomatron.allowsClick(index) : ultrasequencer.allowsClick(index);
        if (allowed) {
            if (chrono) {
                chronomatron.onClick(index);
            } else {
                ultrasequencer.onClick(index);
            }
            return false;   // correct click: let it through
        }
        return cfg().blockMisclicks;   // wrong slot: veto only when the guard is on
    }

    // ------------------------------------------------------------------
    // Shared helpers for the solvers
    // ------------------------------------------------------------------

    /** Border chrome that is never a playable tile: glass panes and empty slots. */
    static boolean isFiller(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains("glass_pane");
    }

    /** Screen-space rectangle helper: draws a 2px accent outline hugging a 16px slot. */
    static void outline(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x - 1, y - 1, x + 17, y + 1, color);        // top
        g.fill(x - 1, y + 15, x + 17, y + 17, color);      // bottom
        g.fill(x - 1, y - 1, x + 1, y + 17, color);        // left
        g.fill(x + 15, y - 1, x + 17, y + 17, color);      // right
    }
}
