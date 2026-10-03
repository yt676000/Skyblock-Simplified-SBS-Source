/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorsix.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.floorsix.logic.GiantHpTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The giant health readout: a card listing the giants that are still up, weakest first, and - when
 * the world toggle is on - the same number drawn low on each giant, where it can be read without
 * looking up past the top of the screen.
 *
 * <p>The colour is the fraction left: green while it is somebody else's problem, amber once it is
 * worth committing to, red when it is nearly down. Sorting by health puts the giant being focused on
 * the top line, so the card answers "who am I hitting" rather than just listing four names.
 */
public final class GiantHpHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 138;
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private static final int COLOR_HIGH = 0xFF7CFF6A;
    private static final int COLOR_MID = 0xFFFFC24A;
    private static final int COLOR_LOW = 0xFFFF5555;

    private GiantHpHud() {
    }

    /** Called from the HUD render hook once per frame: the world numbers, then the card. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().dungeons;
        Minecraft minecraft = Minecraft.getInstance();
        if (!cfg.giantHp || minecraft.player == null || minecraft.level == null) {
            return;
        }
        List<GiantHpTracker.GiantInfo> giants = GiantHpTracker.getInstance().giants();
        if (giants.isEmpty()) {
            return;
        }
        if (cfg.giantHpWorld) {
            drawInWorld(g, minecraft, giants);
        }
        if (HudLayout.isHidden(HudElement.GIANT_HP)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.GIANT_HP.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.GIANT_HP);
        drawCard(g, giants, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void drawInWorld(GuiGraphicsExtractor g, Minecraft minecraft,
                                    List<GiantHpTracker.GiantInfo> giants) {
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;
        for (GiantHpTracker.GiantInfo giant : giants) {
            int[] screen = WorldRender.projectToScreen(viewProjection, camPos, giant.anchor(),
                    g.guiWidth(), g.guiHeight());
            if (screen == null) {
                continue;
            }
            int color = color(giant.fraction());
            g.centeredText(font, Component.literal(giant.name()), screen[0], screen[1] - 10, color);
            g.centeredText(font, Component.literal(giant.health()), screen[0], screen[1], color);
        }
    }

    private static void drawCard(GuiGraphicsExtractor g, List<GiantHpTracker.GiantInfo> giants,
                                 int x, int y) {
        Font font = Minecraft.getInstance().font;

        List<String> lines = new ArrayList<>();
        lines.add("Giants  §7" + giants.size() + " up");
        for (GiantHpTracker.GiantInfo giant : giants) {
            lines.add("§7" + giant.name() + " §f" + giant.health());
        }

        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.width(strip(line)));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        HudLayout.measure(HudElement.GIANT_HP, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(lines.get(0)), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (int i = 0; i < giants.size(); i++) {
            g.text(font, Component.literal(lines.get(i + 1)), ix, iy, color(giants.get(i).fraction()));
            iy += lineH;
        }
    }

    /** Green while it is healthy, amber once it is worth committing to, red when it is nearly down. */
    private static int color(double fraction) {
        if (fraction < 0) {
            return SBSTheme.TEXT;
        }
        return fraction > 0.5 ? COLOR_HIGH : fraction > 0.2 ? COLOR_MID : COLOR_LOW;
    }

    private static String strip(String text) {
        return text.replaceAll(SECTION_SIGN + ".", "");
    }
}
