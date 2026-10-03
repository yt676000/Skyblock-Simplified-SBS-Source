/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.location.hollows.HollowsDetector;
import sbs.modid.client.helper.map.logic.HollowsTarget;
import sbs.modid.client.helper.map.model.MapViewport;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

/**
 * The direction card for the target picked on the Crystal Hollows map: an arrow pointing toward it
 * relative to where you are looking, its name, the distance and the height difference.
 *
 * <p>A straight-line heading, not a route - nothing turns the camera or moves the player.
 */
public final class HollowsTargetHud {

    private static final int PAD = 4;
    private static final int ARROW = 12;

    private HollowsTargetHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        HollowsTarget.Target target = HollowsTarget.getInstance().current();
        Player player = Minecraft.getInstance().player;
        if (target == null || player == null || HudLayout.isHidden(HudElement.CH_TARGET)
                || !HollowsDetector.getInstance().onHollows()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        double dx = target.x() + 0.5 - player.getX();
        double dz = target.z() + 0.5 - player.getZ();
        int dy = (int) Math.round(target.y() - player.getY());
        int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz + (double) dy * dy));
        String text = target.label() + "  §7" + distance + "m"
                + (Math.abs(dy) >= 3 ? (dy > 0 ? "  §a▲" : "  §c▼") + Math.abs(dy) : "");

        int width = PAD * 3 + ARROW + font.width(text);
        int height = Math.max(ARROW, font.lineHeight) + PAD * 2;
        HudElement.Bounds b = HudElement.CH_TARGET.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.CH_TARGET, x, y, width, height);
        HudLayout.begin(g, HudElement.CH_TARGET);
        HudCard.draw(g, x, y, width, height);

        // Up on the card is straight ahead: rotate the world heading so the view direction points up.
        double rotation = MapViewport.rotationFacingUp(player.getYRot());
        double length = Math.max(1e-6, Math.hypot(dx, dz));
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        double sx = (dx / length) * cos - (dz / length) * sin;
        double sy = (dx / length) * sin + (dz / length) * cos;
        HollowsMapPainter.playerArrow(g, x + PAD + ARROW / 2.0, y + height / 2.0, sx, sy, ARROW / 2.0 - 1, 0x57D977);

        g.text(font, Component.literal(text), x + PAD * 2 + ARROW, y + (height - font.lineHeight) / 2 + 1,
                SBSTheme.ACCENT_BRIGHT);
        HudLayout.end(g);
    }
}
