/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import sbs.modid.client.combat.kuudra.logic.FreshTracker;
import sbs.modid.client.combat.kuudra.logic.KuudraTracker;
import sbs.modid.client.combat.kuudra.logic.SupplyTracker;
import sbs.modid.client.combat.kuudra.model.KuudraPhase;
import sbs.modid.client.combat.kuudra.model.KuudraSide;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Kuudra card: what the run is doing, and how long it has taken to do it.
 *
 * <p><b>The rows change with the phase, rather than the card showing everything at once.</b> A supply
 * count means nothing during the boss fight and Kuudra's health means nothing during the build - a
 * card carrying both is a card where the one number you need is somewhere in the middle of five you
 * do not. So each phase contributes its own two or three rows and the rest are simply absent.
 *
 * <p>The splits are the exception: once a phase is over, its time stays. That is the part read
 * <i>after</i> the run, and it is the only record of it - Hypixel reports nothing but the total.
 */
public final class KuudraHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 132;
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    /** How many delivery lines the supply list shows before it is cut off. */
    private static final int MAX_DELIVERY_ROWS = 6;

    private KuudraHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.KuudraSettings cfg = ConfigManager.getInstance().get().kuudra;
        KuudraTracker tracker = KuudraTracker.getInstance();
        if (!cfg.enabled || !cfg.showHud || Minecraft.getInstance().player == null
                || !tracker.running() || HudLayout.isHidden(HudElement.KUUDRA)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.KUUDRA.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.KUUDRA);
        draw(g, cfg, tracker, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, SBSConfig.KuudraSettings cfg,
                             KuudraTracker tracker, int x, int y) {
        Font font = Minecraft.getInstance().font;
        List<String> lines = rows(cfg, tracker);

        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.width(strip(line)));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        HudLayout.measure(HudElement.KUUDRA, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, Component.literal(lines.get(i)), ix, iy,
                    i == 0 ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            iy += lineH;
        }
    }

    private static List<String> rows(SBSConfig.KuudraSettings cfg, KuudraTracker tracker) {
        List<String> lines = new ArrayList<>();
        KuudraPhase phase = tracker.phase();
        int tier = tracker.tier();

        lines.add("Kuudra" + (tier > 0 ? " T" + tier : "") + "  §7" + phase.displayName());
        lines.add("§7Run §f" + KuudraTracker.clock(tracker.runMs())
                + "  §8" + KuudraTracker.clock(tracker.phaseMs()));

        switch (phase) {
            case SUPPLIES -> supplies(cfg, lines);
            case BUILD -> build(tracker, lines);
            case STUN, DPS, SKIP, BOSS -> boss(tracker, lines);
            default -> {
                // Ballista and the post-kill phases have nothing of their own to add.
            }
        }
        if (cfg.showSplits) {
            splits(tracker, lines);
        }
        return lines;
    }

    private static void supplies(SBSConfig.KuudraSettings cfg, List<String> lines) {
        SupplyTracker supplies = SupplyTracker.getInstance();
        lines.add("§7Supplies §f" + supplies.delivered() + "§7/6");
        if (supplies.myPre() != null) {
            lines.add("§7Pre §f" + supplies.myPre().displayName());
        }
        if (!cfg.showSupplyTimes) {
            return;
        }
        List<SupplyTracker.Delivery> deliveries = supplies.deliveries();
        int from = Math.max(0, deliveries.size() - MAX_DELIVERY_ROWS);
        for (int i = from; i < deliveries.size(); i++) {
            SupplyTracker.Delivery delivery = deliveries.get(i);
            lines.add("§8" + delivery.number() + ". §7" + delivery.player()
                    + " §f" + KuudraTracker.clock(delivery.atMs()));
        }
    }

    private static void build(KuudraTracker tracker, List<String> lines) {
        lines.add("§7Build §f" + tracker.buildPercent() + "%");
        long own = FreshTracker.getInstance().ownRemaining();
        if (own > 0) {
            lines.add(String.format(Locale.US, "§aFRESH §f%.1fs", own / 1000.0));
        } else if (FreshTracker.getInstance().anyFresh()) {
            lines.add("§7Someone is §afresh");
        }
    }

    private static void boss(KuudraTracker tracker, List<String> lines) {
        MagmaCube boss = tracker.boss();
        if (boss != null && boss.getMaxHealth() > 0) {
            int percent = Math.round(boss.getHealth() / boss.getMaxHealth() * 100f);
            lines.add("§7HP §f" + percent + "% §8" + NumberDisplay.format(boss.getHealth()));
        }
        KuudraSide side = tracker.side();
        if (side != KuudraSide.UNKNOWN) {
            lines.add("§7Side §f" + side.displayName());
        }
    }

    /** One row per finished phase. Absent until at least one is finished, so it never shows dashes. */
    private static void splits(KuudraTracker tracker, List<String> lines) {
        for (KuudraPhase phase : KuudraPhase.values()) {
            long split = tracker.split(phase);
            if (split >= 0) {
                lines.add("§8" + phase.displayName() + " §7" + KuudraTracker.clock(split));
            }
        }
    }

    private static String strip(String text) {
        return text.replaceAll(SECTION_SIGN + ".", "");
    }
}
