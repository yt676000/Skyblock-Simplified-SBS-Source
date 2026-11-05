/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.requeue.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.dungeons.requeue.logic.AutoRequeue;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Auto Requeue card: countdown and target ("Next: M6 in 4s"), the cancel hint, why a requeue
 * stopped, and pending downtime.
 *
 * <p>Not island-bound: it shows in the hub / on the Crimson Isle, about the instance just left.
 * The lines come prepared from the tick ({@link AutoRequeue#hudLines}); the {@link Component}s and
 * the width are rebuilt only when those lines change, so a frame only draws.
 */
public final class RequeueHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 120;

    // render thread only
    private static List<String> builtFrom = List.of();
    private static List<Component> components = List.of();
    private static int width;

    private RequeueHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        List<String> lines = AutoRequeue.getInstance().hudLines();
        if (lines.isEmpty() || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.AUTO_REQUEUE)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        if (lines != builtFrom) {
            rebuild(lines, font);
        }
        HudElement.Bounds bounds = HudElement.AUTO_REQUEUE.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = (int) bounds.x();
        int y = (int) bounds.y();
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * components.size() - LINE_GAP;
        HudLayout.begin(g, HudElement.AUTO_REQUEUE);
        HudLayout.measure(HudElement.AUTO_REQUEUE, x, y, width, height);
        HudCard.draw(g, x, y, width, height);
        int iy = y + PAD;
        for (int i = 0; i < components.size(); i++) {
            g.text(font, components.get(i), x + PAD, iy, i == 0 ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /** The tick publishes a new list only when it rebuilt the lines; identity is the change signal. */
    private static void rebuild(List<String> lines, Font font) {
        List<Component> built = new ArrayList<>(lines.size());
        int contentW = 0;
        for (String line : lines) {
            Component component = Component.literal(line);
            built.add(component);
            contentW = Math.max(contentW, font.width(component));
        }
        components = built;
        width = Math.max(MIN_W, PAD * 2 + contentW);
        builtFrom = lines;
    }
}
