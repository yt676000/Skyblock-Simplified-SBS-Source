/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.terminal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Terminal Simulator: a fully self-contained practice screen for the F7/M7 phase-3 terminals - it
 * generates the boards itself (no server data, no other-mod code), times each solve and tracks a
 * personal best per type. Practise mode is pure display + your own clicks; it never touches a real
 * dungeon or sends anything.
 *
 * <p>Five re-created terminals: <b>Numbers</b> (click 1→N in order), <b>Colours</b> (select every pane
 * of the named colour), <b>Starts With</b> (click every item whose name starts with the letter),
 * <b>Panes</b> (turn every red tile green) and <b>Rubix</b> (one colour for the whole board, left
 * click forwards through the cycle and right click back). Immediate-mode grid + buttons, same idiom
 * as the other SBS screens.
 */
public final class TerminalSimulatorScreen extends Screen {

    private static final int KEY_ESCAPE = 256;

    private enum Type {
        NUMBERS("Numbers", "Click the numbers in order"),
        COLORS("Colours", "Select every %s pane"),
        STARTS_WITH("Starts With", "Click every item starting with '%s'"),
        PANES("Panes", "Turn every red pane green"),
        RUBIX("Rubix", "One colour for every tile - left click forwards, right click back");

        final String label;
        final String prompt;

        Type(String label, String prompt) {
            this.label = label;
            this.prompt = prompt;
        }
    }

    /** One grid cell. {@code target} = part of the solution; {@code done} = correctly clicked. */
    private static final class Cell {
        int argb;          // fill colour of the tile
        String text;       // number / letter / short word shown on the tile
        int number;        // NUMBERS: the ordering number, else 0
        int cycle;         // RUBIX: index into the five-colour cycle, else 0
        boolean target;    // is this cell part of the solution
        boolean done;      // already correctly clicked
    }

    private static final int COLS = 6;
    private static final int ROWS = 5;
    private static final int CELL = 22;
    private static final int GAP = 3;

    private static final int[] PALETTE = {0xFFE0605F, 0xFF57D977, 0xFF3FB4FF, 0xFFFFC94D,
            0xFFB44DFF, 0xFFFF8A3F};
    private static final String[] PALETTE_NAMES = {"red", "green", "blue", "yellow", "purple", "orange"};

    /** The rubix cycle in its real order: a left click steps forwards, a right click backwards. */
    private static final int[] CYCLE = {0xFFE0605F, 0xFFFF8A3F, 0xFFFFC94D, 0xFF57D977, 0xFF3FB4FF};
    private static final int PANE_RED = 0xFFE0605F;
    private static final int PANE_GREEN = 0xFF57D977;
    private static final String[] WORDS = {"Sword", "Shield", "Apple", "Ender", "Spirit", "Aspect",
            "Bonzo", "Livid", "Necron", "Storm", "Goldor", "Maxor", "Wither", "Blade", "Terminator",
            "Hyperion", "Scylla", "Valkyrie", "Astraea", "Emerald", "Diamond", "Gold", "Prismarine"};

    private final Random random = new Random();

    private Type type = Type.NUMBERS;
    private final List<Cell> cells = new ArrayList<>();
    private String targetLabel = "";    // colour name / letter, for the prompt
    private int nextNumber = 1;          // NUMBERS: the number expected next
    private int remaining;               // solution cells not yet done

    private long startMs;
    private long finishMs;               // 0 while running
    private int misses;

    private int gridX;
    private int gridY;

    private final List<int[]> buttonRects = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();

    public TerminalSimulatorScreen() {
        super(Component.literal("Terminal Simulator"));
    }

    @Override
    protected void init() {
        int gridW = COLS * (CELL + GAP) - GAP;
        gridX = (this.width - gridW) / 2;
        gridY = this.height / 2 - (ROWS * (CELL + GAP) - GAP) / 2 + 6;
        addRenderableOnly(new PanelRenderable());
        if (cells.isEmpty()) {
            generate();
        }
    }

    // ------------------------------------------------------------------
    // Board generation (self-contained)
    // ------------------------------------------------------------------

    private void generate() {
        cells.clear();
        nextNumber = 1;
        misses = 0;
        finishMs = 0;
        startMs = System.currentTimeMillis();
        int total = COLS * ROWS;
        for (int i = 0; i < total; i++) {
            cells.add(new Cell());
        }
        switch (type) {
            case NUMBERS -> generateNumbers(total);
            case COLORS -> generateColors(total);
            case STARTS_WITH -> generateStartsWith(total);
            case PANES -> generatePanes();
            case RUBIX -> generateRubix();
        }
    }

    /** "Correct all the panes!": red tiles have to be flipped, green ones are already right. */
    private void generatePanes() {
        int red = 0;
        for (Cell cell : cells) {
            boolean isRed = random.nextInt(100) < 55;
            cell.argb = isRed ? PANE_RED : PANE_GREEN;
            cell.text = "";
            cell.target = isRed;
            if (isRed) {
                red++;
            }
        }
        if (red == 0) { // guarantee something to do
            Cell cell = cells.get(random.nextInt(cells.size()));
            cell.argb = PANE_RED;
            cell.target = true;
            red = 1;
        }
        remaining = red;
        targetLabel = String.valueOf(red);
    }

    /**
     * "Change all to same color!": every tile starts on a random step of the five-colour cycle and
     * the board is solved when they all show the same one. No target is marked - which colour costs
     * the fewest clicks is the whole puzzle, exactly as in the real terminal.
     */
    private void generateRubix() {
        for (Cell cell : cells) {
            cell.cycle = random.nextInt(CYCLE.length);
            cell.argb = CYCLE[cell.cycle];
            cell.text = "";
        }
        if (solvedRubix()) { // a board that starts solved is not practice
            Cell cell = cells.get(random.nextInt(cells.size()));
            cell.cycle = (cell.cycle + 1) % CYCLE.length;
            cell.argb = CYCLE[cell.cycle];
        }
        remaining = 1;
        targetLabel = "";
    }

    private boolean solvedRubix() {
        for (Cell cell : cells) {
            if (cell.cycle != cells.get(0).cycle) {
                return false;
            }
        }
        return true;
    }

    private void generateNumbers(int total) {
        int count = 10 + random.nextInt(5); // 10..14 numbers
        List<Integer> slots = shuffledSlots(total);
        for (int i = 0; i < count; i++) {
            Cell cell = cells.get(slots.get(i));
            cell.number = i + 1;
            cell.text = String.valueOf(i + 1);
            cell.argb = 0xFF394A5A;
            cell.target = true;
        }
        remaining = count;
        targetLabel = "1-" + count;
    }

    private void generateColors(int total) {
        int colour = random.nextInt(PALETTE.length);
        targetLabel = PALETTE_NAMES[colour];
        int targets = 0;
        for (Cell cell : cells) {
            int c = random.nextInt(PALETTE.length);
            // Bias a healthy share toward the target so a board is always solvable and not trivial.
            if (random.nextInt(100) < 30) {
                c = colour;
            }
            cell.argb = PALETTE[c];
            cell.text = "";
            if (c == colour) {
                cell.target = true;
                targets++;
            }
        }
        if (targets == 0) { // guarantee at least one
            Cell cell = cells.get(random.nextInt(total));
            cell.argb = PALETTE[colour];
            cell.target = true;
            targets = 1;
        }
        remaining = targets;
    }

    private void generateStartsWith(int total) {
        char letter = pickLetterWithWords();
        targetLabel = String.valueOf(letter);
        int targets = 0;
        for (Cell cell : cells) {
            String word = WORDS[random.nextInt(WORDS.length)];
            cell.text = word;
            cell.argb = 0xFF2E3B49;
            if (Character.toLowerCase(word.charAt(0)) == letter) {
                cell.target = true;
                targets++;
            }
        }
        if (targets == 0) { // guarantee at least one word with the letter
            for (String word : WORDS) {
                if (Character.toLowerCase(word.charAt(0)) == letter) {
                    Cell cell = cells.get(random.nextInt(total));
                    cell.text = word;
                    cell.target = true;
                    targets = 1;
                    break;
                }
            }
        }
        remaining = targets;
    }

    /** A letter that at least one word starts with, so the board is always solvable. */
    private char pickLetterWithWords() {
        while (true) {
            char letter = Character.toLowerCase(WORDS[random.nextInt(WORDS.length)].charAt(0));
            for (String word : WORDS) {
                if (Character.toLowerCase(word.charAt(0)) == letter) {
                    return letter;
                }
            }
        }
    }

    private List<Integer> shuffledSlots(int total) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            slots.add(i);
        }
        Collections.shuffle(slots, random);
        return slots;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();
        for (int i = 0; i < buttonRects.size(); i++) {
            int[] r = buttonRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                buttonActions.get(i).run();
                return true;
            }
        }
        if (finishMs == 0) {
            int index = cellAt(mx, my);
            if (index >= 0) {
                clickCell(index, event.button() == 1);
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    private void clickCell(int index, boolean rightClick) {
        Cell cell = cells.get(index);
        if (type == Type.RUBIX) {
            // The only terminal where a click is never "wrong", just longer: step the cycle either way.
            cell.cycle = Math.floorMod(cell.cycle + (rightClick ? -1 : 1), CYCLE.length);
            cell.argb = CYCLE[cell.cycle];
            if (solvedRubix()) {
                finish();
            }
            return;
        }
        if (type == Type.PANES) {
            // A pane flips either way, so a click on one that is already green undoes work - which
            // is the mistake this terminal actually punishes, and therefore worth practising.
            boolean wasRed = cell.target;
            cell.target = !wasRed;
            cell.argb = wasRed ? PANE_GREEN : PANE_RED;
            remaining += wasRed ? -1 : 1;
            if (!wasRed) {
                misses++;
            } else if (remaining <= 0) {
                finish();
            }
            return;
        }
        if (cell.done) {
            return;
        }
        boolean correct;
        if (type == Type.NUMBERS) {
            correct = cell.target && cell.number == nextNumber;
            if (correct) {
                nextNumber++;
            }
        } else {
            correct = cell.target;
        }
        if (correct) {
            cell.done = true;
            remaining--;
            if (remaining <= 0) {
                finish();
            }
        } else {
            misses++;
        }
    }

    private void finish() {
        finishMs = System.currentTimeMillis();
        long elapsed = finishMs - startMs;
        Map<String, Long> pb = ConfigManager.getInstance().get().dungeons.terminalPb;
        Long best = pb.get(type.name());
        if (best == null || elapsed < best) {
            pb.put(type.name(), elapsed);
            ConfigManager.getInstance().save();
        }
    }

    private int cellAt(double mx, double my) {
        for (int i = 0; i < cells.size(); i++) {
            int x = gridX + (i % COLS) * (CELL + GAP);
            int y = gridY + (i / COLS) * (CELL + GAP);
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = TerminalSimulatorScreen.this.font;
            buttonRects.clear();
            buttonActions.clear();

            int panelW = COLS * (CELL + GAP) - GAP + 40;
            int panelH = ROWS * (CELL + GAP) - GAP + 96;
            int px = gridX - 20;
            int py = gridY - 56;
            g.fill(0, 0, TerminalSimulatorScreen.this.width, TerminalSimulatorScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, px, py, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, px, py, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, px + 1, py + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            // Header: type + timer + PB.
            long elapsed = (finishMs == 0 ? System.currentTimeMillis() : finishMs) - startMs;
            Long pb = ConfigManager.getInstance().get().dungeons.terminalPb.get(type.name());
            g.centeredText(font, Component.literal("§bTerminal Simulator §7· §f" + type.label),
                    px + panelW / 2, py + 6, SBSTheme.ACCENT_BRIGHT);
            String prompt = type.prompt.contains("%s")
                    ? String.format(type.prompt, "§e" + targetLabel + "§7") : type.prompt;
            g.centeredText(font, Component.literal("§7" + prompt), px + panelW / 2, py + 18, SBSTheme.TEXT_MUTED);
            String timer = "§fTime §b" + secs(elapsed) + "s"
                    + (pb != null ? "   §7PB §a" + secs(pb) + "s" : "")
                    + (misses > 0 ? "   §cmiss " + misses : "");
            g.centeredText(font, Component.literal(timer), px + panelW / 2, py + 32, SBSTheme.TEXT);

            drawGrid(g, font, mouseX, mouseY);

            // Buttons row.
            int by = py + panelH - 22;
            int bw = (panelW - 30) / 3;
            button(g, font, px + 10, by, bw, 16, "§aNew", mouseX, mouseY, TerminalSimulatorScreen.this::generate);
            button(g, font, px + 15 + bw, by, bw, 16, "§bType: " + type.label, mouseX, mouseY, () -> {
                type = Type.values()[(type.ordinal() + 1) % Type.values().length];
                generate();
            });
            button(g, font, px + 20 + bw * 2, by, bw, 16, "Close", mouseX, mouseY,
                    TerminalSimulatorScreen.this::onClose);

            if (finishMs != 0) {
                g.centeredText(font, Component.literal("§a§lSolved! §7" + secs(elapsed) + "s"),
                        px + panelW / 2, py + panelH / 2, SBSTheme.ACCENT_BRIGHT);
            }
        }

        private void drawGrid(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            for (int i = 0; i < cells.size(); i++) {
                Cell cell = cells.get(i);
                int x = gridX + (i % COLS) * (CELL + GAP);
                int y = gridY + (i / COLS) * (CELL + GAP);
                int fill = cell.done ? dim(cell.argb) : cell.argb;
                SciFiRender.roundedRect(g, x, y, CELL, CELL, 3, fill);
                boolean hover = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
                if (hover && finishMs == 0) {
                    g.outline(x, y, CELL, CELL, SBSTheme.ACCENT_BRIGHT);
                }
                if (cell.done) {
                    g.centeredText(font, Component.literal("§a✔"), x + CELL / 2, y + CELL / 2 - 4, 0xFFFFFFFF);
                } else if (cell.text != null && !cell.text.isEmpty()) {
                    String shown = type == Type.STARTS_WITH ? cell.text.substring(0, 1) : cell.text;
                    g.centeredText(font, Component.literal(shown), x + CELL / 2, y + CELL / 2 - 4, 0xFFFFFFFF);
                }
            }
        }
    }

    private void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String label,
                        int mouseX, int mouseY, Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        g.centeredText(font, Component.literal(label), x + w / 2, y + (h - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
        buttonRects.add(new int[]{x, y, w, h});
        buttonActions.add(action);
    }

    private static int dim(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = ((argb >> 16) & 0xFF) / 3;
        int gg = ((argb >> 8) & 0xFF) / 3;
        int b = (argb & 0xFF) / 3;
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    private static String secs(long ms) {
        return String.format(Locale.US, "%.2f", ms / 1000.0);
    }
}
