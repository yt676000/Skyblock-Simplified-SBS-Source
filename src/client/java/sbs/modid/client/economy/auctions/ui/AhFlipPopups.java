/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.auctions.logic.AhFlipClient;
import sbs.modid.client.economy.auctions.logic.AhFlipFeed;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The AH flip popup cards: every flip the client receives becomes a small SBS card in a stack
 * anchored at the {@link HudElement#AH_FLIP_POPUPS} GUI-editor element (movable / scalable /
 * hideable like every HUD element).
 *
 * <p>The cards render in-world <b>and</b> over open container screens – deliberately, because
 * in-world the mouse is grabbed: to click a flip you open your inventory, where the same cards are
 * still standing. There, a left-click on a card opens its auction ({@code /viewauction}, exactly
 * like the Similar Auctions rows) and the {@code ✕} dismisses it – a dismissed flip disappears
 * from every output through the shared {@link AhFlipFeed}. Cards time out after the configured
 * popup duration or when their flip expires.
 *
 * <p>Rendering happens in two passes that never overlap: the HUD pass only while no screen is
 * open, the container pass from {@code OverlayRenderMixin} (topmost, above the floating windows –
 * matching its input hook in {@code ContainerSearchBarMixin}, which runs before the window loop).
 */
public final class AhFlipPopups {

    private static final AhFlipPopups INSTANCE = new AhFlipPopups();

    /** Card geometry in the element's DEFAULT coordinate space (the HUD transform scales it). */
    private static final int CARD_W = 186;
    private static final int CARD_H = 44;
    private static final int CARD_GAP = 4;
    private static final int PAD = 5;
    private static final int MAX_CARDS = 4;

    private record Card(AhFlipFeed.Flip flip, long addedMs) {
    }

    /** Newest first; the stack renders top-down in this order. */
    private final Deque<Card> cards = new ArrayDeque<>();

    private AhFlipPopups() {
    }

    public static AhFlipPopups getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.AhFlipAlertSettings cfg() {
        return ConfigManager.getInstance().get().ahFlips;
    }

    /** Adds a new card on top (called by {@link AhFlipClient} for every received flip). */
    public synchronized void push(AhFlipFeed.Flip flip) {
        cards.addFirst(new Card(flip, System.currentTimeMillis()));
        while (cards.size() > MAX_CARDS) {
            cards.removeLast();
        }
    }

    /** Cards still alive: not timed out, flip not expired, not dismissed elsewhere. */
    private synchronized List<Card> alive() {
        long now = System.currentTimeMillis();
        long ttl = Math.max(15, cfg().popupSeconds) * 1000L;
        AhFlipFeed feed = AhFlipFeed.getInstance();
        cards.removeIf(card -> now - card.addedMs() > ttl
                || card.flip().expired(now)
                || feed.isDismissed(card.flip().auctionId()));
        return new ArrayList<>(cards);
    }

    private synchronized void remove(Card card) {
        cards.remove(card);
    }

    private static boolean active() {
        SBSConfig.AhFlipAlertSettings cfg = cfg();
        return cfg.enabled && cfg.popupAlerts && !HudLayout.isHidden(HudElement.AH_FLIP_POPUPS);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** HUD pass (from {@code SBSHudRenderer.renderHudExtras}): only while no screen is open. */
    public void renderHud(GuiGraphicsExtractor g) {
        if (sbs.modid.client.core.api.ScreenAccess.current() == null) {
            render(g, Integer.MIN_VALUE, Integer.MIN_VALUE);
        }
    }

    /** Container pass (from {@code OverlayRenderMixin}, above the floating windows). */
    public void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                       int mouseX, int mouseY) {
        render(g, mouseX, mouseY);
    }

    private void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!active()) {
            return;
        }
        List<Card> list = alive();
        if (list.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        HudElement.Bounds b = bounds(g.guiWidth(), g.guiHeight());
        double localX = toLocalX(mouseX, g.guiWidth(), g.guiHeight());
        double localY = toLocalY(mouseY, g.guiWidth(), g.guiHeight());

        // The stack is exactly the case HudGrowth exists for: every card added grows it a row further
        // down, over whatever the player parked underneath it. Reporting the real height lets the
        // stack take a free row when there is one and step aside when there is not.
        HudLayout.measure(HudElement.AH_FLIP_POPUPS, b.x(), b.y(), CARD_W, stackHeight(list.size()));

        HudLayout.begin(g, HudElement.AH_FLIP_POPUPS);
        int y = Math.round(b.y());
        int x = Math.round(b.x());
        for (Card card : list) {
            boolean hoverCard = localX >= x && localX < x + CARD_W
                    && localY >= y && localY < y + CARD_H;
            boolean hoverClose = inCloseBox(localX, localY, x, y);
            drawCard(g, font, card.flip(), x, y, hoverCard && !hoverClose, hoverClose);
            y += CARD_H + CARD_GAP;
        }
        HudLayout.end(g);
    }

    private static void drawCard(GuiGraphicsExtractor g, Font font, AhFlipFeed.Flip flip,
                                 int x, int y, boolean hover, boolean hoverClose) {
        SciFiRender.glow(g, x, y, CARD_W, CARD_H, SBSTheme.HUD_CORNER, SBSTheme.PANEL_GLOW, 1);
        SciFiRender.roundedRectWithBorder(g, x, y, CARD_W, CARD_H, SBSTheme.HUD_CORNER,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.HUD_CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.HUD_CARD_BORDER);

        int lineH = font.lineHeight + 3;
        int tx = x + PAD;
        int ty = y + PAD;
        // Line 1: [FLIP] + name, ✕ on the right.
        g.text(font, Component.literal("[FLIP]"), tx, ty, 0xFFFFC94D);
        int nameX = tx + font.width("[FLIP] ");
        g.text(font, Component.literal(font.plainSubstrByWidth(flip.displayName(),
                CARD_W - PAD * 2 - font.width("[FLIP] ") - 12, false)), nameX, ty, SBSTheme.TEXT);
        g.text(font, Component.literal("✕"), x + CARD_W - PAD - font.width("✕"), ty,
                hoverClose ? SBSTheme.WARN : SBSTheme.TEXT_MUTED);
        ty += lineH;
        // Line 2: price → target value.
        g.text(font, Component.literal(coins(flip.price()) + " → " + coins(flip.target())),
                tx, ty, 0xFFFFC94D);
        ty += lineH;
        // Line 3: profit + discount + weekly sales (the hint doubles as "this is clickable").
        String meta = " · " + Math.round(flip.discountPct()) + "% off · " + flip.volumeLabel();
        g.text(font, Component.literal("+" + coins(flip.profit())), tx, ty, SBSTheme.TOGGLE_ON);
        g.text(font, Component.literal(meta + (hover ? " · click!" : "")),
                tx + font.width("+" + coins(flip.profit())), ty, SBSTheme.TEXT_MUTED);
    }

    // ------------------------------------------------------------------
    // Input (container screens only; in-world the mouse is grabbed)
    // ------------------------------------------------------------------

    /**
     * Routes a container-screen click into the card stack: {@code ✕} dismisses, anywhere else on
     * a card opens its auction. Runs BEFORE the floating-window z-order loop, because the cards
     * render above every window.
     */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!active() || event.button() != 0) {
            return false;
        }
        List<Card> list = alive();
        if (list.isEmpty()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int gw = minecraft.getWindow().getGuiScaledWidth();
        int gh = minecraft.getWindow().getGuiScaledHeight();
        HudElement.Bounds b = bounds(gw, gh);
        double localX = toLocalX(event.x(), gw, gh);
        double localY = toLocalY(event.y(), gw, gh);
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        for (Card card : list) {
            if (localX >= x && localX < x + CARD_W && localY >= y && localY < y + CARD_H) {
                if (inCloseBox(localX, localY, x, y)) {
                    AhFlipFeed.getInstance().dismiss(card.flip().auctionId());
                } else {
                    AhFlipClient.openAuction(card.flip());
                }
                remove(card);
                return true;
            }
            y += CARD_H + CARD_GAP;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Geometry: the cards draw in DEFAULT-bounds space under the HUD transform,
    // so hit tests map the mouse back into that space (inverse transform).
    // ------------------------------------------------------------------

    private static HudElement.Bounds bounds(int gw, int gh) {
        return HudElement.AH_FLIP_POPUPS.defaultBounds(gw, gh);
    }

    /** The whole stack's height: one card per flip, with a gap between but not after them. */
    private static int stackHeight(int cards) {
        return Math.max(CARD_H, cards * (CARD_H + CARD_GAP) - CARD_GAP);
    }

    private static boolean inCloseBox(double localX, double localY, int cardX, int cardY) {
        return localX >= cardX + CARD_W - 14 && localX < cardX + CARD_W
                && localY >= cardY && localY < cardY + 12;
    }

    private static double toLocalX(double screenX, int gw, int gh) {
        return HudLayout.localX(HudElement.AH_FLIP_POPUPS, screenX, gw, gh);
    }

    private static double toLocalY(double screenY, int gw, int gh) {
        return HudLayout.localY(HudElement.AH_FLIP_POPUPS, screenY, gw, gh);
    }

    /** Compact coin format shared with the chat line ("8.5M", "1.2B"). */
    private static String coins(long value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }
}
