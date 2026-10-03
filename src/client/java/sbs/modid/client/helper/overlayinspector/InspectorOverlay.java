/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.overlayinspector;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Overlay Inspector puts on screen: the outline around the element under the pointer, the
 * card naming the mod that drew it, the optional list of every mod drawing this frame, and the
 * pointer itself.
 *
 * <p>Drawn from the very end of the HUD pass and one stratum up, so it sits above everything it is
 * describing. Its own rectangles are excluded from the capture while it draws – otherwise the
 * inspector would end up inspecting itself, and every element would report SBS as its owner.
 */
public final class InspectorOverlay {

    private static final int PAD = 5;
    private static final int OUTLINE = 2;
    private static final int POINTER_GAP = 12;

    /** Cursor art: {@code X} outline, {@code .} fill, space transparent. */
    private static final String[] POINTER = {
            "X        ",
            "XX       ",
            "X.X      ",
            "X..X     ",
            "X...X    ",
            "X....X   ",
            "X.....X  ",
            "X......X ",
            "X.......X",
            "X....XXXX",
            "X..X.X   ",
            "X.X X.X  ",
            "XX  X.X  ",
            "X    X.X ",
            "     XXX ",
    };

    private static final int POINTER_OUTLINE = 0xFF000000;
    private static final int POINTER_FILL = 0xFFFFFFFF;

    private InspectorOverlay() {
    }

    private static SBSConfig.OverlayInspectorSettings cfg() {
        return ConfigManager.getInstance().get().overlayInspector;
    }

    /** Draws the inspector, if it is on. Called once per frame at the end of the HUD pass. */
    public static void render(GuiGraphicsExtractor g) {
        OverlayInspector inspector = OverlayInspector.getInstance();
        if (!inspector.isActive()) {
            return;
        }
        // Leaving the world (or landing in a menu) with the pointer still up would strand a frozen
        // camera behind a screen nobody connects to this feature.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || sbs.modid.client.core.api.ScreenAccess.current() != null) {
            inspector.deactivate();
            return;
        }

        int width = g.guiWidth();
        int height = g.guiHeight();
        OverlayInspector.Hovered hovered = inspector.resolveHover(width, height);
        List<OverlayInspector.Contributor> contributors =
                cfg().showModList ? inspector.contributors() : List.of();

        // Above everything drawn so far this frame, including whatever the inspected mods drew.
        g.nextStratum();
        inspector.beginSelfDraw();
        try {
            if (hovered != null) {
                drawOutline(g, hovered);
            }
            if (!contributors.isEmpty()) {
                drawModList(g, contributors);
            }
            drawCard(g, hovered, width, height);
            drawPointer(g, (int) inspector.cursorX(), (int) inspector.cursorY());
        } finally {
            inspector.endSelfDraw();
            inspector.endFrame();
        }
    }

    /** The frame around the element under the pointer. */
    private static void drawOutline(GuiGraphicsExtractor g, OverlayInspector.Hovered hovered) {
        int color = SBSTheme.ACCENT_BRIGHT;
        int left = hovered.left();
        int top = hovered.top();
        int right = hovered.right();
        int bottom = hovered.bottom();
        g.fill(left - OUTLINE, top - OUTLINE, right + OUTLINE, top, color);
        g.fill(left - OUTLINE, bottom, right + OUTLINE, bottom + OUTLINE, color);
        g.fill(left - OUTLINE, top, left, bottom, color);
        g.fill(right, top, right + OUTLINE, bottom, color);
    }

    /** The card next to the pointer: which mod, and what a click will do. */
    private static void drawCard(GuiGraphicsExtractor g, OverlayInspector.Hovered hovered,
                                 int screenWidth, int screenHeight) {
        Font font = Minecraft.getInstance().font;
        List<String> lines = new ArrayList<>(4);
        int accent;
        if (hovered == null) {
            lines.add("Nothing under the pointer");
            lines.add("§7Point at a bar, counter or panel");
            accent = SBSTheme.TEXT_MUTED;
        } else if (ModIndex.MINECRAFT.equals(hovered.modId())) {
            lines.add("Minecraft");
            lines.add("§7Drawn by the game itself, not by a mod");
            lines.add("§8" + hovered.width() + " x " + hovered.height()
                    + " at " + hovered.left() + ", " + hovered.top());
            accent = SBSTheme.TEXT_MUTED;
        } else {
            ModIndex.ModInfo info = ModIndex.info(hovered.modId());
            if (info == null) {
                lines.add("Unknown source");
                lines.add("§7This element could not be traced to a mod");
                accent = SBSTheme.TEXT_MUTED;
            } else {
                lines.add(info.name());
                lines.add("§7" + info.id() + " §8" + info.version());
                lines.add("§8" + hovered.width() + " x " + hovered.height()
                        + " at " + hovered.left() + ", " + hovered.top());
                lines.add("§bClick §7settings  §b·  §bRight-click §7copy");
                accent = SBSTheme.ACCENT_BRIGHT;
            }
        }
        String source = hovered == null ? null : hovered.source();
        if (source != null && cfg().showSource) {
            lines.add("§8via " + source);
        }

        int lineHeight = font.lineHeight + 2;
        int contentWidth = 0;
        for (String line : lines) {
            contentWidth = Math.max(contentWidth, font.width(line));
        }
        int cardWidth = contentWidth + PAD * 2;
        int cardHeight = lines.size() * lineHeight - 2 + PAD * 2;

        OverlayInspector inspector = OverlayInspector.getInstance();
        int x = (int) inspector.cursorX() + POINTER_GAP;
        int y = (int) inspector.cursorY() + POINTER_GAP;
        // Flip to the other side of the pointer rather than let the card run off screen.
        if (x + cardWidth > screenWidth - 2) {
            x = Math.max(2, (int) inspector.cursorX() - POINTER_GAP - cardWidth);
        }
        if (y + cardHeight > screenHeight - 2) {
            y = Math.max(2, (int) inspector.cursorY() - POINTER_GAP - cardHeight);
        }

        panel(g, x, y, cardWidth, cardHeight);
        int textY = y + PAD;
        boolean first = true;
        for (String line : lines) {
            g.text(font, Component.literal(line), x + PAD, textY, first ? accent : SBSTheme.TEXT);
            textY += lineHeight;
            first = false;
        }
    }

    /**
     * The legend: every mod drawing anything this frame, busiest first.
     *
     * <p>Answers the other half of the question – not "what is this?" but "what is even on my
     * screen?" – for overlays too small or too transparent to aim at.
     */
    private static void drawModList(GuiGraphicsExtractor g, List<OverlayInspector.Contributor> contributors) {
        Font font = Minecraft.getInstance().font;
        String header = "Drawing this frame";
        int lineHeight = font.lineHeight + 2;
        int rows = Math.min(contributors.size(), 12);

        List<String> labels = new ArrayList<>(rows);
        int contentWidth = font.width(header);
        for (int i = 0; i < rows; i++) {
            OverlayInspector.Contributor contributor = contributors.get(i);
            String label = ModIndex.MINECRAFT.equals(contributor.modId())
                    ? "Minecraft"
                    : ModIndex.displayName(contributor.modId());
            labels.add(label);
            contentWidth = Math.max(contentWidth, font.width(label) + 14
                    + font.width(String.valueOf(contributor.rectangles())));
        }

        int width = contentWidth + PAD * 2;
        int height = (rows + 1) * lineHeight - 2 + PAD * 2;
        int x = 4;
        int y = 4;
        panel(g, x, y, width, height);

        int textY = y + PAD;
        g.text(font, Component.literal(header), x + PAD, textY, SBSTheme.ACCENT_BRIGHT);
        textY += lineHeight;
        for (int i = 0; i < rows; i++) {
            OverlayInspector.Contributor contributor = contributors.get(i);
            String count = String.valueOf(contributor.rectangles());
            g.text(font, Component.literal(labels.get(i)), x + PAD, textY, SBSTheme.TEXT);
            g.text(font, Component.literal(count), x + width - PAD - font.width(count), textY,
                    SBSTheme.TEXT_MUTED);
            textY += lineHeight;
        }
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        SciFiRender.glow(g, x, y, width, height, SBSTheme.HUD_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, x, y, width, height, SBSTheme.HUD_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, x + 1, y + 1, width - 2, height - 2,
                SBSTheme.HUD_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
    }

    /** The pointer, drawn a pixel at a time so it reads as a cursor at any GUI scale. */
    private static void drawPointer(GuiGraphicsExtractor g, int x, int y) {
        for (int row = 0; row < POINTER.length; row++) {
            String line = POINTER[row];
            for (int column = 0; column < line.length(); column++) {
                char pixel = line.charAt(column);
                if (pixel == ' ') {
                    continue;
                }
                int color = pixel == 'X' ? POINTER_OUTLINE : POINTER_FILL;
                g.fill(x + column, y + row, x + column + 1, y + row + 1, color);
            }
        }
    }
}
