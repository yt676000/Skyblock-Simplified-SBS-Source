/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.skills.fishing.logic.FishingTracker;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

/**
 * A small, movable "baits left" chip: the icon of the bait you are currently fishing with, plus how
 * many of it you have left (loose in the inventory + the last-seen Bait Sack reserve). Its own
 * {@link HudElement#BAIT_COUNTER}, so it can sit wherever the eye wants it, independent of the main
 * {@link FishingHud} card. Switched on by the "Show Remaining Baits" setting and shown under the
 * shared "Tracker Visibility" rule; the bait it shows follows {@link FishingTracker#currentBaitId()}.
 */
public final class BaitHud {

    private static final int PAD = 4;
    private static final int ICON = 16;
    /** Gap between the icon and the count. */
    private static final int GAP = 4;

    private BaitHud() {
    }

    private static SBSConfig.FishingSettings cfg() {
        return ConfigManager.getInstance().get().fishing;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.FishingSettings cfg = cfg();
        if (!cfg.enabled || !cfg.showRemainingBaits || HudLayout.isHidden(HudElement.BAIT_COUNTER)) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        FishingTracker tracker = FishingTracker.getInstance();
        String baitId = tracker.currentBaitId();
        if (!cfg.hudVisibility().shouldRender(player, baitId != null)) {
            return;
        }
        if (baitId == null) {
            return;   // no bait in use, in the inventory or in the sack - nothing to show
        }

        Font font = Minecraft.getInstance().font;
        ItemStack icon = SkyBlockItemIcons.getInstance().icon(baitId, null, 1);
        String text = sbs.modid.client.core.util.NumberDisplay.format(tracker.remainingOf(baitId));

        int width = ICON + GAP + font.width(text) + PAD * 2;
        int height = ICON + PAD * 2;

        HudElement.Bounds b = HudElement.BAIT_COUNTER.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        int alpha = FishingHudPanel.alpha(cfg.hudOpacity);

        HudLayout.measure(HudElement.BAIT_COUNTER, x, y, width, height);
        HudLayout.begin(g, HudElement.BAIT_COUNTER);
        FishingHudPanel.panel(g, x, y, width, height, alpha);

        g.item(icon, x + PAD, y + PAD);
        int textY = y + PAD + (ICON - font.lineHeight) / 2;
        g.text(font, Component.literal(text), x + PAD + ICON + GAP, textY, SBSTheme.TEXT, false);
        HudLayout.end(g);
    }
}
