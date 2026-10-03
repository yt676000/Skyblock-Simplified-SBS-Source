/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.render;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.core.mixin.AbstractRecipeBookScreenAccessor;
import sbs.modid.client.helper.inventory.logic.EquipmentMenu.Piece;
import sbs.modid.client.helper.inventory.logic.EquipmentStore;
import sbs.modid.client.helper.loadouts.LoadoutsOverlay;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The four equipment pieces as a column of slot frames just left of the player inventory, level
 * with the armor slots. <b>Display only</b>: these are pictures of the last capture, not slots. A
 * click never moves an item and never sends a container packet - it runs {@code /equipment}, the
 * command the Loadouts quick-nav already uses, and nothing else.
 *
 * <p><b>Placement.</b> Outside the inventory's left edge, so it cannot cover the armor slots, the
 * player preview or the recipe-book button (all inside the GUI), and 4 px clear of the themed panel,
 * which paints 2 px past that edge. While the recipe book is open it occupies exactly this space, so
 * the column steps aside; with too little room left of the inventory it is not drawn at all rather
 * than drawn off screen. Measured: 1280x720 at GUI scale 4 is 320 px wide, leftPos 72, the column
 * spans x 50-68; 1920x1080 at scale 2 is 960 px wide, leftPos 392, spans 370-388.
 */
public final class EquipmentColumn {

    private static final int CELL = 18;
    /** Gap between the column and the inventory's left edge (the themed panel overhangs by 2). */
    private static final int GAP = 4;
    /** Armor slots sit at y 8 inside the inventory; frames are drawn one pixel outside the item. */
    private static final int FIRST_Y = 7;
    private static final long HOUR_MS = 3_600_000L;

    private EquipmentColumn() {
    }

    private static SBSConfig.EquipmentDisplaySettings cfg() {
        return ConfigManager.getInstance().get().equipmentDisplay;
    }

    /** The column's left edge, or {@code Integer.MIN_VALUE} when it must not be drawn here. */
    private static int columnX(AbstractContainerScreen<?> screen) {
        if (!cfg().enabled || !(screen instanceof InventoryScreen)) {
            return Integer.MIN_VALUE;
        }
        var book = ((AbstractRecipeBookScreenAccessor) screen).skyblockSimplified$recipeBookComponent();
        if (book != null && book.isVisible()) {
            return Integer.MIN_VALUE;
        }
        int x = ((AbstractContainerScreenAccessor) screen).skyblockSimplified$leftPos() - GAP - CELL;
        return x < 0 ? Integer.MIN_VALUE : x;
    }

    private static int cellY(AbstractContainerScreen<?> screen, int index) {
        return ((AbstractContainerScreenAccessor) screen).skyblockSimplified$topPos() + FIRST_Y
                + index * CELL;
    }

    /** Which piece's frame is under the point, or {@code null}. */
    private static Piece hit(AbstractContainerScreen<?> screen, double mx, double my) {
        int x = columnX(screen);
        if (x == Integer.MIN_VALUE || mx < x || mx >= x + CELL) {
            return null;
        }
        for (Piece piece : Piece.values()) {
            int y = cellY(screen, piece.ordinal());
            if (my >= y && my < y + CELL) {
                return piece;
            }
        }
        return null;
    }

    /** Whether the capture is stale: too old, or a different loadout is on the body since. */
    private static boolean stale(EquipmentStore store) {
        long age = System.currentTimeMillis() - store.capturedAt();
        if (age > Math.max(1, cfg().staleHours) * HOUR_MS) {
            return true;
        }
        int then = store.loadoutSlot();
        int now = LoadoutsOverlay.getInstance().provenSlot();
        return then > 0 && now > 0 && then != now;
    }

    /** Draws the column. Called from the container top layer, in absolute screen coordinates. */
    public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        int x = columnX(screen);
        if (x == Integer.MIN_VALUE) {
            return;
        }
        boolean themed = ConfigManager.getInstance().get().minecraftOverlay.enabled;
        EquipmentStore store = EquipmentStore.getInstance();
        boolean captured = store.capturedAt() > 0;
        boolean stale = captured && stale(store);
        Font font = Minecraft.getInstance().font;
        int top = cellY(screen, 0);
        // Backing strip, so the column reads as part of the inventory in either look.
        if (themed) {
            SciFiRender.roundedRectWithBorder(g, x - 2, top - 2, CELL + 4, CELL * 4 + 4,
                    SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        } else {
            g.fill(x - 2, top - 2, x + CELL + 2, top + CELL * 4 + 2, 0xFFC6C6C6);
        }
        Piece hovered = null;
        for (Piece piece : Piece.values()) {
            int y = cellY(screen, piece.ordinal());
            boolean over = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
            if (over) {
                hovered = piece;
            }
            drawFrame(g, x, y, themed);
            ItemStack stack = captured ? store.piece(piece) : null;
            if (stack == null) {
                g.centeredText(font, Component.literal("?"), x + CELL / 2, y + (CELL - font.lineHeight) / 2 + 1,
                        themed ? SBSTheme.TEXT_MUTED : 0xFF555555);
            } else if (!stack.isEmpty()) {
                g.item(stack, x + 1, y + 1);
            }
            if (over) {
                g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0x80FFFFFF);
            }
        }
        if (stale) {
            // One marker for the column, not four: the capture is stale as a whole.
            g.fill(x + CELL - 4, top - 1, x + CELL, top + 3, 0xFFFFAA00);
        }
        if (hovered != null) {
            drawTooltip(g, font, store, hovered, captured, stale, mouseX, mouseY);
        }
    }

    private static void drawFrame(GuiGraphicsExtractor g, int x, int y, boolean themed) {
        if (themed) {
            g.fill(x, y, x + CELL, y + CELL, SBSTheme.CARD_BORDER);
            g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, SBSTheme.SLOT_BG);
            return;
        }
        // The vanilla slot bevel: dark top-left, light bottom-right, grey well.
        g.fill(x, y, x + CELL, y + CELL, 0xFF373737);
        g.fill(x + 1, y + 1, x + CELL, y + CELL, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0xFF8B8B8B);
    }

    /**
     * The piece's own tooltip plus its age. Drawn directly, not deferred: this layer is painted
     * after the deferred tooltip flush, so a deferred one would sit underneath the column.
     */
    private static void drawTooltip(GuiGraphicsExtractor g, Font font, EquipmentStore store, Piece piece,
                                    boolean captured, boolean stale, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        ItemStack stack = captured ? store.piece(piece) : null;
        if (!captured) {
            lines.add(Component.literal(piece.label()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Nothing captured yet").withStyle(ChatFormatting.YELLOW));
            lines.add(Component.literal("Open Stats & Equipment to capture").withStyle(ChatFormatting.YELLOW));
        } else {
            if (stack == null || stack.isEmpty()) {
                lines.add(Component.literal("No " + piece.label()).withStyle(ChatFormatting.GRAY));
            } else {
                lines.addAll(tooltipOf(stack));
            }
            lines.add(Component.empty());
            lines.add(Component.literal("Captured " + age(System.currentTimeMillis() - store.capturedAt()))
                    .withStyle(stale ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY));
            if (stale) {
                lines.add(Component.literal("May be out of date - open /equipment to refresh")
                        .withStyle(ChatFormatting.GOLD));
            }
        }
        lines.add(Component.literal("Click to open /equipment").withStyle(ChatFormatting.DARK_GRAY));
        List<ClientTooltipComponent> parts = new ArrayList<>(lines.size());
        for (Component line : lines) {
            parts.add(ClientTooltipComponent.create(line.getVisualOrderText()));
        }
        g.tooltip(font, parts, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, SBSTheme.tooltipStyle());
    }

    private static List<Component> tooltipOf(ItemStack stack) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            return stack.getTooltipLines(Item.TooltipContext.of(minecraft.level), minecraft.player,
                    TooltipFlag.NORMAL);
        } catch (Throwable t) {
            return List.of(stack.getHoverName());
        }
    }

    /** "just now", "12 min ago", "5 h ago", "3 d ago". */
    static String age(long ms) {
        long minutes = Math.max(0, ms) / 60_000L;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + " min ago";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + " h ago" : hours / 24 + " d ago";
    }

    /**
     * A click on a frame runs {@code /equipment} and is consumed; anything else falls through. The
     * command is the only thing a click can do - no slot is touched and no container packet sent.
     */
    public static boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (hit(screen, event.x(), event.y()) == null) {
            return false;
        }
        var player = Minecraft.getInstance().player;
        if (player != null && event.button() == 0) {
            player.connection.sendCommand("equipment");
        }
        return true;
    }
}
