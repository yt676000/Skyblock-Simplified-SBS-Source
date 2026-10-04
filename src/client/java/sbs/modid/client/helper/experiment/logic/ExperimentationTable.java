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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.client.core.perf.Perf;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.ui.hud.logic.PetTracker;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Coordinator for the three Experimentation Table minigame helpers (Enchanting area):
 * <ul>
 *   <li><b>Chronomatron</b> - remembers the flashed colour sequence and highlights it back in order;
 *   <li><b>Ultrasequencer</b> - remembers each dealt tile's number and highlights them back by number;
 *   <li><b>Superpairs</b> - remembers every card seen, ghosts it back, and outlines known pairs.
 * </ul>
 *
 * <p>It is <b>read-only</b> against the real menu: once per client tick (never in render) the open
 * container is reduced to plain {@link PlainItem}s and handed to one pure model per game
 * ({@link ChronomatronModel}, {@link UltrasequencerModel}, {@link SuperpairsModel}); the solvers are
 * thin adapters that draw the model's answer. The board is derived from the item pattern, never
 * from slot ranges, since the tiers differ in board size. Nothing is ever clicked; the optional
 * misclick guard (Chronomatron / Ultrasequencer only, default off) fails open.
 *
 * <p>The board encoding was confirmed from a Server Scanner recording (Metaphysical, 2026-10-04);
 * the models' class comments carry the details, and the JSONL fixtures under
 * {@code src/test/resources/experiment/} drive their tests. A throttled
 * {@code [SBS][Experiment] ...} line reports what the active model currently holds.
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
    private final ExperimentSession session = new ExperimentSession();
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
    static Game gameOf(String title) {
        String norm = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        // "<Game> ➜ Stakes" and "Superpairs Rewards" name a game but are not its board.
        if (norm.contains("stakes") || norm.contains("rewards")) {
            return Game.NONE;
        }
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
    // Screen change: the title log + the Guardian-pet alert (never a reset - see ExperimentSession)
    // ------------------------------------------------------------------

    /** Drops every solver's memory, logging why once - the cause a future report needs. */
    private void resetSolvers(String reason) {
        chronomatron.reset();
        ultrasequencer.reset();
        superpairs.reset();
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Experiment] reset reason={}", reason);
    }

    /** Fires the title log and the Guardian alert when the active screen object changes. */
    private void onScreenChanged(Screen screen) {
        lastScreen = screen;
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
            SBSChat.send(Component.literal(
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
            String reason = session.tick(-1, Game.NONE, "", Long.MAX_VALUE / 2);
            if (reason != null) {
                resetSolvers(reason);
            }
            return;
        }
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        // Screen-change detection lives HERE, not in a setScreenAndShow hook: this tick path is the
        // one PetTracker already proves works for server-opened Hypixel menus.
        if (screen != lastScreen) {
            onScreenChanged(screen);
        }
        long now = System.currentTimeMillis();
        String title = titleOf(screen);
        game = screen instanceof AbstractContainerScreen<?> ? gameOf(title) : Game.NONE;
        AbstractContainerMenu menu = screen instanceof AbstractContainerScreen<?> container ? container.getMenu() : null;
        String reason = session.tick(menu == null ? -1 : menu.containerId, game, ExperimentSession.tierOf(title), now);
        if (reason != null) {
            resetSolvers(reason);
        }
        if (menu == null || game == Game.NONE) {
            return;
        }
        boolean active = switch (game) {
            case CHRONOMATRON -> cfg().chronomatron;
            case ULTRASEQUENCER -> cfg().ultrasequencer;
            case SUPERPAIRS -> cfg().superpairs;
            default -> false;
        };
        if (active) {
            try (Perf.Section perf = Perf.tick("experiment.scan")) {
                PlainItem[] board = board(menu);
                switch (game) {
                    case CHRONOMATRON -> chronomatron.scan(board, now);
                    case ULTRASEQUENCER -> ultrasequencer.scan(menu, board);
                    case SUPERPAIRS -> superpairs.scan(menu, board);
                    default -> { }
                }
            }
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
        // Lift the overlay above the slots' own items: in the same stratum a ghost item can land
        // UNDER the cover item it is drawn over, leaving only its outline visible.
        g.nextStratum();
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
        // With the guard off the models only track the click: no veto, no valve, no stand-down.
        if (cfg().blockMisclicks
                && !(chrono ? chronomatron.allowsClick(index) : ultrasequencer.allowsClick(index))) {
            return true;    // wrong slot, guard on: veto (the valve lets the second refusal through)
        }
        if (chrono) {
            chronomatron.onClick(index);
        } else {
            ultrasequencer.onClick(index);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Shared helpers for the solvers
    // ------------------------------------------------------------------

    /** The menu's own slots (the player inventory excluded) as plain items, {@code null} = empty. */
    static PlainItem[] board(AbstractContainerMenu menu) {
        int size = Math.max(0, Math.min(menu.slots.size(), menu.getItems().size() - 36));
        PlainItem[] board = new PlainItem[size];
        for (int i = 0; i < size; i++) {
            board[i] = plain(menu.getSlot(i).getItem());
        }
        return board;
    }

    /** One stack as a {@link PlainItem}, or {@code null} for an empty slot. */
    static PlainItem plain(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> lines = new ArrayList<>();
        if (lore != null) {
            for (Component line : lore.lines()) {
                lines.add(strip(line.getString()));
            }
        }
        return new PlainItem(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),
                strip(stack.getHoverName().getString()), stack.getCount(), stack.hasFoil(), lines);
    }

    private static String strip(String text) {
        return text == null ? "" : text.replaceAll("§.", "").trim();
    }

    /** Border chrome that is never a playable tile: glass panes and empty slots. */
    static boolean isFiller(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains("glass_pane");
    }

    /**
     * A remembered item drawn over its cover: a dark backing first, so it reads as "remembered",
     * not as a real face-up card. Relies on the stratum lift in {@link #render}.
     */
    static void ghost(GuiGraphicsExtractor g, ItemStack icon, int x, int y) {
        g.fill(x, y, x + 16, y + 16, 0xD0101018);
        g.item(icon, x, y);
    }

    /** The chest screen's width, for labels at the right end of the title row. */
    static final int SCREEN_WIDTH = 176;

    /** A short label at the right end of the title row ("GO", "wait"). */
    static void titleLabel(GuiGraphicsExtractor g, Font font, int left, int top, String text, int color) {
        Component label = Component.literal(text);
        g.text(font, label, left + SCREEN_WIDTH - 8 - font.width(label), top + 6, color);
    }

    /** A 2px frame around the given slots (the board), for the start cue's flash. */
    static void frameAround(GuiGraphicsExtractor g, AbstractContainerMenu menu, Iterable<Integer> slots,
                            int left, int top, int color) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int y1 = Integer.MIN_VALUE;
        for (int index : slots) {
            if (index < 0 || index >= menu.slots.size()) {
                continue;
            }
            Slot s = menu.getSlot(index);
            x0 = Math.min(x0, s.x);
            y0 = Math.min(y0, s.y);
            x1 = Math.max(x1, s.x + 16);
            y1 = Math.max(y1, s.y + 16);
        }
        if (x0 > x1) {
            return;
        }
        x0 += left - 2;
        y0 += top - 2;
        x1 += left + 2;
        y1 += top + 2;
        g.fill(x0, y0, x1, y0 + 2, color);
        g.fill(x0, y1 - 2, x1, y1, color);
        g.fill(x0, y0, x0 + 2, y1, color);
        g.fill(x1 - 2, y0, x1, y1, color);
    }

    /** Draws the start cue while it runs: "GO" in the title row and, briefly, a flash of the board frame. */
    static void startCue(GuiGraphicsExtractor g, Font font, AbstractContainerMenu menu, Iterable<Integer> board,
                         StartCue cue, int left, int top) {
        if (!cfg().startCue) {
            return;
        }
        long now = System.currentTimeMillis();
        if (cue.labelShowing(now)) {
            titleLabel(g, font, left, top, "GO", 0xFF55FF55);
        }
        if (cue.flashShowing(now)) {
            frameAround(g, menu, board, left, top, 0xFF55FF55);
        }
    }

    /** Screen-space rectangle helper: draws a 2px accent outline hugging a 16px slot. */
    static void outline(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x - 1, y - 1, x + 17, y + 1, color);        // top
        g.fill(x - 1, y + 15, x + 17, y + 17, color);      // bottom
        g.fill(x - 1, y - 1, x + 1, y + 17, color);        // left
        g.fill(x + 15, y - 1, x + 17, y + 17, color);      // right
    }
}
