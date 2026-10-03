/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.casing.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.dungeons.casing.model.CaseItem;
import sbs.modid.client.dungeons.casing.render.CaseOpeningReel;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.core.item.Rarity;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.util.List;

/**
 * The CS:GO-style case-opening animation over a dark, elegant backdrop: a horizontal item reel that
 * scrolls fast and eases to a dead stop on the item the player already won, then shows it off in a
 * slowly spinning inspect view.
 *
 * <p>Purely cosmetic. The winning item is handed in – the server already decided it – and
 * {@link CaseOpeningReel} (proven by test to land exactly on the win) drives the motion. This class
 * only draws; it changes nothing about the drop.
 *
 * <p>Effects scale with the win's rarity: a quiet highlight for common, a glow and rising sparks for
 * rare, and for the top tiers a gold frame, a brief screen flash and little fireworks – loud enough
 * to feel special, never so loud it hides the item.
 */
public final class CaseOpeningScreen extends Screen {

    private static final int CELL = 24;
    private static final int VISIBLE_HALF = 9;        // cells drawn each side of the marker
    private static final long INSPECT_SPIN_MS = 4000; // one full showcase turn

    private final CaseOpeningReel reel;
    private final long startMs;
    /** When the reel finished, so the inspect phase and its effects can time from it. */
    private long landedAt;

    public CaseOpeningScreen(CaseOpeningReel reel) {
        super(Component.literal("Case Opening"));
        this.reel = reel;
        this.startMs = System.currentTimeMillis();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Any key closes the showcase once the reel has landed; before that, input is ignored. */
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (reel.finished(System.currentTimeMillis())) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected void init() {
        addRenderableOnly(new CasePanel());
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class CasePanel implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            long now = System.currentTimeMillis();
            Font font = CaseOpeningScreen.this.font;
            int w = CaseOpeningScreen.this.width;
            int h = CaseOpeningScreen.this.height;
            boolean landed = reel.finished(now);
            if (landed && landedAt == 0) {
                landedAt = now;
            }

            // Dark, slightly vignetted backdrop.
            g.fill(0, 0, w, h, 0xEE0A0A10);
            g.fill(0, h / 2 - 60, w, h / 2 + 60, 0x66000000);

            if (landed) {
                drawInspect(g, font, w, h, now);
            } else {
                drawReel(g, font, w, h, now);
            }
        }

        /** The scrolling strip, with a centre marker the winning cell will settle under. */
        private void drawReel(GuiGraphicsExtractor g, Font font, int w, int h, long now) {
            int markerX = w / 2;
            int rowY = h / 2 - CELL / 2;
            double t = reel.progress(now);
            double offset = reel.offsetAt(t, CELL, markerX);
            List<CaseItem> strip = reel.strip();

            int centreIndex = reel.indexUnderMarker(offset, CELL, markerX);
            for (int i = centreIndex - VISIBLE_HALF; i <= centreIndex + VISIBLE_HALF; i++) {
                if (i < 0 || i >= strip.size()) {
                    continue;
                }
                int cellX = (int) Math.round(i * (double) CELL - offset);
                drawCell(g, strip.get(i), cellX, rowY, false);
            }

            // Dim the strip toward the edges so the eye stays on the centre.
            g.fill(0, rowY, w / 2 - 70, rowY + CELL, 0x99000000);
            g.fill(w / 2 + 70, rowY, w, rowY + CELL, 0x99000000);
            // The marker: a bright vertical line through the centre cell.
            g.fill(markerX - 1, rowY - 8, markerX + 1, rowY + CELL + 8, 0xFFFFD24B);

            g.centeredText(font, Component.literal("§7Opening..."), w / 2, rowY - 24, 0xFFAAAAAA);
        }

        /** One reel cell: a rarity-tinted backdrop and the item icon. */
        private void drawCell(GuiGraphicsExtractor g, CaseItem item, int x, int y, boolean big) {
            int rgb = item.rarity().color() & 0xFFFFFF;
            SciFiRender.roundedRect(g, x + 1, y + 1, CELL - 2, CELL - 2, 3, 0x33000000 | (rgb & 0xFFFFFF));
            g.fill(x, y + CELL - 2, x + CELL, y + CELL, 0xFF000000 | rgb);   // rarity underline
            g.item(icon(item), x + (CELL - 16) / 2, y + (CELL - 16) / 2);
        }

        /**
         * The won item on show: enlarged, slowly spinning so you can look at it, with its name and
         * rarity, and rarity-scaled flourish (glow / sparks / gold frame / flash / fireworks).
         */
        private void drawInspect(GuiGraphicsExtractor g, Font font, int w, int h, long now) {
            CaseItem win = reel.winner();
            int rgb = win.rarity().color() & 0xFFFFFF;
            long sinceLand = now - landedAt;
            int cx = w / 2;
            int cy = h / 2 - 10;
            int tier = tier(win.rarity());
            // Respect the setting: with top-tier effects off, cap intensity below the flash /
            // fireworks / gold-frame threshold so the reveal stays calm.
            if (!sbs.modid.client.core.config.ConfigManager.getInstance().get().caseOpening.topTierEffects) {
                tier = Math.min(tier, 3);
            }

            // Brief white screen flash on the top tiers, decaying over ~350ms.
            if (tier >= 4 && sinceLand < 350) {
                int a = (int) (160 * (1 - sinceLand / 350.0));
                g.fill(0, 0, w, h, (a << 24) | 0xFFFFFF);
            }
            // Glow halo behind the item; brighter and larger the rarer it is.
            int haloR = 40 + tier * 8;
            for (int r = haloR; r > 0; r -= 4) {
                int a = (int) (60.0 * r / haloR * (0.4 + 0.15 * tier));
                SciFiRender.roundedRect(g, cx - r, cy - r, r * 2, r * 2, r, (Math.min(160, a) << 24) | rgb);
            }
            if (tier >= 2) {
                drawSparks(g, cx, cy, rgb, sinceLand, tier);
            }
            if (tier >= 4) {
                drawFireworks(g, cx, cy, rgb, sinceLand);
                drawGoldFrame(g, cx, cy);
            }

            // The item itself: spun about its centre so a flat icon still reads as "on display".
            float spin = (float) (Math.sin((sinceLand % INSPECT_SPIN_MS) / (double) INSPECT_SPIN_MS
                    * Math.PI * 2) * 0.35);
            float scale = 4.0f + Math.min(1.5f, sinceLand / 400.0f);   // grows in on arrival
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(cx, cy);
            pose.rotate(spin);
            pose.scale(scale, scale);
            g.item(icon(win), -8, -8);
            pose.popMatrix();

            String name = SkyBlockItemIcons.getInstance().icon(win.id(), null, 1)
                    .getHoverName().getString();
            g.centeredText(font, Component.literal(name), cx, cy + haloR + 10, 0xFF000000 | rgb);
            g.centeredText(font, Component.literal("§7" + win.rarity().name()),
                    cx, cy + haloR + 22, 0xFFAAAAAA);
            g.centeredText(font, Component.literal("§8Press any key to close"),
                    cx, h - 24, 0xFF888888);
        }

        /** Rising sparks around the item; count and reach scale with rarity. */
        private void drawSparks(GuiGraphicsExtractor g, int cx, int cy, int rgb, long since, int tier) {
            int count = 6 * tier;
            for (int i = 0; i < count; i++) {
                double ang = i * (Math.PI * 2 / count) + since / 900.0;
                double dist = 30 + (since % 1400) / 1400.0 * (40 + tier * 10);
                int px = cx + (int) (Math.cos(ang) * dist);
                int py = cy + (int) (Math.sin(ang) * dist) - (int) ((since % 1400) / 1400.0 * 20);
                int a = (int) (200 * (1 - (since % 1400) / 1400.0));
                g.fill(px, py, px + 2, py + 2, (Math.max(0, a) << 24) | rgb);
            }
        }

        /** Small looping fireworks bursts for the very best drops. */
        private void drawFireworks(GuiGraphicsExtractor g, int cx, int cy, int rgb, long since) {
            for (int burst = 0; burst < 3; burst++) {
                long phase = (since + burst * 500) % 1500;
                double p = phase / 1500.0;
                int bx = cx + (burst - 1) * 90;
                int by = cy - 50;
                for (int ray = 0; ray < 10; ray++) {
                    double ang = ray * (Math.PI * 2 / 10);
                    int px = bx + (int) (Math.cos(ang) * p * 30);
                    int py = by + (int) (Math.sin(ang) * p * 30);
                    int a = (int) (220 * (1 - p));
                    g.fill(px, py, px + 2, py + 2, (Math.max(0, a) << 24) | rgb);
                }
            }
        }

        /** The gold frame around the showcase for top-tier wins. */
        private void drawGoldFrame(GuiGraphicsExtractor g, int cx, int cy) {
            int r = 70;
            int gold = 0xFFFFD24B;
            g.fill(cx - r, cy - r, cx + r, cy - r + 2, gold);
            g.fill(cx - r, cy + r - 2, cx + r, cy + r, gold);
            g.fill(cx - r, cy - r, cx - r + 2, cy + r, gold);
            g.fill(cx + r - 2, cy - r, cx + r, cy + r, gold);
        }
    }

    /** A coarse rarity tier for effect intensity: 1 common … 5 mythic+. */
    private static int tier(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> 1;
            case UNCOMMON -> 2;
            case RARE -> 3;
            case EPIC -> 3;
            case LEGENDARY -> 4;
            case MYTHIC, SPECIAL, DIVINE -> 5;
        };
    }

    private static ItemStack icon(CaseItem item) {
        ItemStack stack = SkyBlockItemIcons.getInstance().icon(item.id(), null, 1);
        return stack.is(Items.BARRIER) ? new ItemStack(Items.CHEST) : stack;
    }
}
