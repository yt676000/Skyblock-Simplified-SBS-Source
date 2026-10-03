/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.skills.mining.logic.HotmAdvice;
import sbs.modid.client.skills.mining.logic.HotmAdvisor;
import sbs.modid.client.skills.mining.logic.HotmTreeStore;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;
import java.util.Locale;

/**
 * The advisor's top five next steps beside the Heart of the Mountain menu. Display only: it draws
 * and never clicks - picking the perk stays the player's action. The goal is chosen on
 * {@code /sbs hotm}; this panel shows whichever is selected.
 *
 * <p>Width comes from the room beside the menu, never a fixed minimum: when neither side has
 * {@link #MIN_W} pixels the panel is not drawn rather than drawn over the menu.
 */
public final class HotmAdvisorPanel {

    private static final HotmAdvisorPanel INSTANCE = new HotmAdvisorPanel();

    private static final int PREFERRED_W = 200;
    private static final int MIN_W = 110;
    private static final int PAD = 6;
    private static final int GAP = 8;
    private static final int OK_COLOR = 0xFF57D977;
    private static final int WARN_COLOR = 0xFFE0A14D;

    private HotmAdvisorPanel() {
    }

    public static HotmAdvisorPanel getInstance() {
        return INSTANCE;
    }

    public static boolean isHotmMenu(String title) {
        return title != null && StyledText.strip(title).trim().toLowerCase(Locale.ROOT)
                .startsWith("heart of the mountain");
    }

    public void render(AbstractContainerScreen<?> container, GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().miningHelpers;
        if (!cfg.enabled || !cfg.hotmAdvisorPanel || !isHotmMenu(container.getTitle().getString())) {
            return;
        }
        HotmAdvisor.Advice advice = HotmAdvice.current();
        var goal = HotmAdvice.goal();
        if (advice == null || goal == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) container;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        int right = left + bounds.skyblockSimplified$imageWidth();
        int screenW = container.width;

        int roomRight = screenW - right - GAP - 4;
        int roomLeft = left - GAP - 4;
        int w;
        int x;
        if (roomRight >= MIN_W) {
            w = Math.min(PREFERRED_W, roomRight);
            x = right + GAP;
        } else if (roomLeft >= MIN_W) {
            w = Math.min(PREFERRED_W, roomLeft);
            x = left - GAP - w;
        } else {
            return;
        }
        int inner = w - PAD * 2;
        int lineH = font.lineHeight + 2;
        List<HotmAdvisor.Step> steps = advice.top(5);
        int lines = 2 + Math.max(1, steps.size() * 2) + 1;
        HudCard.draw(g, x, top, w, PAD * 2 + lines * lineH);

        int ix = x + PAD;
        int iy = top + PAD;
        g.text(font, Component.literal(RowText.fit(font, "HotM advisor · " + goal.label(), inner)),
                ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        String progress = Math.round(advice.progress() * 100) + "% of the recommended tree"
                + (HotmTreeStore.getInstance().stale() ? " (re-read)" : "");
        g.text(font, Component.literal(RowText.fit(font, progress, inner)), ix, iy, SBSTheme.TEXT_MUTED);
        iy += lineH;
        if (steps.isEmpty()) {
            g.text(font, Component.literal(RowText.fit(font, "Nothing left to take for this goal", inner)),
                    ix, iy, SBSTheme.TEXT_MUTED);
            iy += lineH;
        }
        for (HotmAdvisor.Step step : steps) {
            g.text(font, Component.literal(RowText.fit(font, HotmAdvice.title(step), inner)), ix, iy,
                    step.core() ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
            iy += lineH;
            int color = !step.costKnown() && !step.unlock() ? SBSTheme.TEXT_MUTED
                    : step.affordable() ? OK_COLOR : WARN_COLOR;
            g.text(font, Component.literal(RowText.fit(font, "  " + HotmAdvice.cost(step), inner)), ix, iy,
                    color);
            iy += lineH;
        }
        g.text(font, Component.literal(RowText.fit(font, "/sbs hotm for the full plan", inner)), ix, iy,
                SBSTheme.TEXT_MUTED);
    }
}
