/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.OverlayColor;
import sbs.modid.client.helper.visual.render.Chroma;
import sbs.modid.client.ui.hud.logic.HypixelHudState;
import sbs.modid.client.ui.hud.logic.PetTracker;
import sbs.modid.client.ui.hud.logic.ServerStatsTracker;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.combat.cooldowns.CooldownOverlay;

/**
 * Draws the SBS rounded HUD bars, reusing the shared {@link SciFiRender} rounded-rectangle
 * primitive so both bars share one cohesive style.
 *
 * <p>Positions mirror vanilla: the health bar sits exactly where the hearts are (left of centre)
 * and the mana bar exactly where the hunger bar is (right of centre). All drawing is done through
 * the {@code GuiGraphicsExtractor} the HUD already provides, so nothing leaves the render pass.
 *
 * <p>Each bar has an optional {@code outlined} variant that adds a subtle 1-pixel darkened border
 * one pixel outside the bar (drawn behind it) for better visibility. Size, position, colors and
 * fill logic are otherwise identical.
 */
public final class SBSHudRenderer {

    /** ~ one full row of vanilla hearts wide; ~ the vanilla XP-bar thickness; small corner radius. */
    private static final int BAR_WIDTH = 81;
    private static final int BAR_HEIGHT = 5;
    private static final int BAR_RADIUS = 2;

    /** The hearts/hunger row baseline (guiHeight - 39), nudged so the thin bar sits in the band. */
    private static final int ROW_FROM_BOTTOM = 39;
    private static final int ROW_INSET = 2;

    /** Stock overflow-mana fill colour (#00A9A9), drawn left-aligned inside the mana bar. */
    private static final int HUD_OVERFLOW_MANA = 0xFF00A9A9;

    private SBSHudRenderer() {
    }

    // ------------------------------------------------------------------
    // Bar colours: the theme's own, unless the player picked one in the module settings
    // ------------------------------------------------------------------

    private static SBSConfig.BarColorSettings colors() {
        return ConfigManager.getInstance().get().hypixelGui.barColors;
    }

    /**
     * The stock overflow-mana colour. Unlike the other segments this one is not an {@code SBSTheme}
     * constant (nothing else in the UI draws overflow mana), so the settings page asks for it here
     * instead of duplicating the literal.
     */
    public static int stockOverflowMana() {
        return HUD_OVERFLOW_MANA;
    }

    /**
     * The colour to paint a segment in: the player's {@code RRGGBB} when they set one, otherwise the
     * stock colour untouched.
     *
     * <p>The stock colour's <b>alpha is kept</b> rather than the picked colour being forced opaque -
     * the track is translucent by design and the fills are solid, and that distinction is not
     * something a colour picker (which only speaks RGB) should be able to destroy.
     */
    private static int barColor(String hex, int stock) {
        Integer rgb = OverlayColor.parseHex(hex);
        return rgb == null ? stock : (stock & 0xFF000000) | rgb;
    }

    /** The empty track behind every bar - themed, or the player's own. */
    private static int trackColor() {
        return barColor(colors().trackHex, SBSTheme.HUD_TRACK);
    }

    /**
     * The outline colour: a darker version of whatever the track currently is. Derived per call, not
     * cached - the track follows both the live theme and the player's override, and an outline
     * frozen at class-load would keep drawing the colour scheme the game started with.
     */
    private static int outlineColor() {
        return darken(trackColor(), 0.5F);
    }

    /**
     * Rounded health bar: a red segment for normal health and a seamlessly connected yellow segment
     * for overheal (absorption). Capacity = max health + current overheal.
     *
     * @param outlined draw the subtle 1-pixel darkened border around the bar.
     */
    public static void renderHealthBar(GuiGraphicsExtractor g, Player player, boolean outlined) {
        int x = g.guiWidth() / 2 - 91;
        int y = g.guiHeight() - ROW_FROM_BOTTOM + ROW_INSET;

        float maxHealth = Math.max(1.0F, player.getMaxHealth());
        float health = clamp(player.getHealth(), 0.0F, maxHealth);
        float overheal = Math.max(0.0F, player.getAbsorptionAmount());
        float capacity = maxHealth + overheal;

        int healthWidth = Math.round(health / capacity * BAR_WIDTH);
        int filledWidth = Math.round((health + overheal) / capacity * BAR_WIDTH);

        if (outlined) {
            drawOutline(g, x, y);
        }
        SciFiRender.roundedRect(g, x, y, BAR_WIDTH, BAR_HEIGHT, BAR_RADIUS, trackColor());
        // Yellow (overheal) is drawn for the whole filled span, then red (health) overlays the left
        // part – so the red→yellow transition is seamless and both bar ends stay rounded.
        if (filledWidth > 0) {
            SciFiRender.roundedRect(g, x, y, filledWidth, BAR_HEIGHT, BAR_RADIUS,
                    barColor(colors().overhealHex, SBSTheme.HUD_OVERHEAL));
        }
        if (healthWidth > 0) {
            SciFiRender.roundedRect(g, x, y, healthWidth, BAR_HEIGHT, BAR_RADIUS,
                    barColor(colors().healthHex, SBSTheme.HUD_HEALTH));
        }
    }

    /**
     * Rounded mana bar (current / max), drawn where the hunger bar normally is.
     *
     * @param outlined draw the subtle 1-pixel darkened border around the bar.
     */
    public static void renderManaBar(GuiGraphicsExtractor g, boolean outlined) {
        int x = g.guiWidth() / 2 + 10;
        int y = g.guiHeight() - ROW_FROM_BOTTOM + ROW_INSET;

        HypixelHudState state = HypixelHudState.getInstance();
        int max = Math.max(1, state.manaMax());
        int current = clamp(state.manaCurrent(), 0, max);
        int overflow = Math.max(0, state.overflowMana());

        // One shared bar for normal + overflow mana: 100% of the width = current mana + overflow
        // (capacity grows with the overflow, mirroring the health bar's overheal model). The teal
        // overflow segment stays anchored LEFT (it is spent last), blue mana fills right after it.
        int capacity = max + overflow;
        int overflowWidth = Math.round((float) overflow / capacity * BAR_WIDTH);
        int manaWidth = Math.round((float) current / capacity * BAR_WIDTH);
        int filledWidth = Math.min(BAR_WIDTH, overflowWidth + manaWidth);

        if (outlined) {
            drawOutline(g, x, y);
        }
        SciFiRender.roundedRect(g, x, y, BAR_WIDTH, BAR_HEIGHT, BAR_RADIUS, trackColor());
        // Whole filled span in blue first (both rounded ends), then the teal overflow overlaid on
        // the left – the teal→blue transition stays seamless, exactly like the health bar's overheal.
        if (filledWidth > 0) {
            SciFiRender.roundedRect(g, x, y, filledWidth, BAR_HEIGHT, BAR_RADIUS,
                    barColor(colors().manaHex, SBSTheme.HUD_MANA));
        }
        if (overflowWidth > 0) {
            SciFiRender.roundedRect(g, x, y, overflowWidth, BAR_HEIGHT, BAR_RADIUS,
                    barColor(colors().overflowManaHex, HUD_OVERFLOW_MANA));
        }
        drawStaleManaFlag(g, x, y, state);
    }

    /** Blink period of the stale-mana flag, in milliseconds per half cycle. */
    private static final int STALE_BLINK_MS = 250;

    /** The stale-mana flag's colour. */
    private static final int STALE_MANA_FLAG = 0xFFFF3B30;

    /**
     * A blinking red pixel on the left edge of the mana bar while Hypixel's "NOT ENOUGH MANA" notice
     * is up.
     *
     * <p>That notice takes the action bar over, so the mana ratio the bar is fed simply is not sent
     * for as long as it shows – the bar keeps the last numbers it saw and happily reads FULL at the
     * exact moment mana ran out. The real value cannot be recovered (the notice says only that there
     * was not enough for the ability, never how much is left), so instead of drawing a made-up
     * number the bar marks itself as not-to-be-trusted for those few frames.
     */
    private static void drawStaleManaFlag(GuiGraphicsExtractor g, int x, int y, HypixelHudState state) {
        if (!state.notEnoughMana() || (System.currentTimeMillis() / STALE_BLINK_MS) % 2 != 0) {
            return;
        }
        g.fill(x, y, x + 1, y + BAR_HEIGHT, STALE_MANA_FLAG);
    }

    /**
     * Rounded vitality bar (current / max), drawn one bar-row directly above the health bar. Vitality
     * is Hypixel's new "healing pool" stat; it has no vanilla HUD element, so this is an extra bar
     * gated purely on its own mode. Positioned to mirror the health bar (same x/width) so the two read
     * as a stacked pair.
     *
     * @param outlined draw the subtle 1-pixel darkened border around the bar.
     */
    public static void renderVitalityBar(GuiGraphicsExtractor g, boolean outlined) {
        int x = g.guiWidth() / 2 - 91;
        int y = g.guiHeight() - ROW_FROM_BOTTOM + ROW_INSET - (BAR_HEIGHT + 2);

        HypixelHudState state = HypixelHudState.getInstance();
        int max = Math.max(1, state.vitalityMax());
        int current = clamp(state.vitalityCurrent(), 0, max);
        int filledWidth = Math.round((float) current / max * BAR_WIDTH);

        if (outlined) {
            drawOutline(g, x, y);
        }
        SciFiRender.roundedRect(g, x, y, BAR_WIDTH, BAR_HEIGHT, BAR_RADIUS, trackColor());
        if (filledWidth > 0) {
            SciFiRender.roundedRect(g, x, y, filledWidth, BAR_HEIGHT, BAR_RADIUS,
                    barColor(colors().vitalityHex, SBSTheme.HUD_VITALITY));
        }
    }

    /** Vanilla experience bar geometry (182 wide, at guiHeight - 29, centred). */
    private static final int XP_BAR_WIDTH = 182;
    private static final int XP_ROW_FROM_BOTTOM = 29;

    /**
     * Rounded XP bar (level progress), drawn exactly where the vanilla experience bar sits. The
     * green level number above it stays vanilla – on Hypixel it carries the skill level, which
     * must remain readable.
     *
     * @param outlined draw the subtle 1-pixel darkened border around the bar.
     */
    public static void renderXpBar(GuiGraphicsExtractor g, Player player, boolean outlined) {
        int x = g.guiWidth() / 2 - XP_BAR_WIDTH / 2;
        int y = g.guiHeight() - XP_ROW_FROM_BOTTOM;

        float progress = clamp(player.experienceProgress, 0.0F, 1.0F);
        int filledWidth = Math.round(progress * XP_BAR_WIDTH);

        if (outlined) {
            SciFiRender.roundedRect(g, x - 1, y - 1, XP_BAR_WIDTH + 2, BAR_HEIGHT + 2,
                    BAR_RADIUS + 1, outlineColor());
        }
        SciFiRender.roundedRect(g, x, y, XP_BAR_WIDTH, BAR_HEIGHT, BAR_RADIUS, trackColor());
        if (filledWidth > 0) {
            SciFiRender.roundedRect(g, x, y, filledWidth, BAR_HEIGHT, BAR_RADIUS,
                    barColor(colors().xpHex, SBSTheme.HUD_XP));
        }
    }

    /**
     * SBS HUD extras rendered after the hotbar: the Active Pet card and the held-item cooldown
     * text. Both are GUI-editor elements (movable / scalable / hideable) gated on their toggles.
     */
    public static void renderHudExtras(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().hypixelGui;
        // Vitality bar: a new stat with no vanilla element to replace, so it is drawn here (above the
        // health bar) gated on its mode + fresh vitality data + the editor's hide toggle.
        if (cfg.vitalityBar.rendersBar() && HypixelHudState.getInstance().hasVitality()
                && !HudLayout.isHidden(HudElement.SBS_VITALITY_BAR)) {
            HudLayout.begin(g, HudElement.SBS_VITALITY_BAR);
            renderVitalityBar(g, cfg.vitalityBar.outlined());
            HudLayout.end(g);
        }
        if (cfg.showActivePet && !HudLayout.isHidden(HudElement.ACTIVE_PET)) {
            renderActivePet(g);
        }
        if (cfg.showCooldownHud && !HudLayout.isHidden(HudElement.ITEM_COOLDOWN)) {
            renderCooldownHud(g);
        }
        if (cfg.showServerStats && !HudLayout.isHidden(HudElement.SERVER_STATS)) {
            renderServerStats(g);
        }
        // Arrow type + arrows left beside the hotbar, while a bow is held (self-gating).
        ArrowDisplayHud.render(g);
        // Fishing spawn alert: gates itself on the fishing toggles and on whether one is live.
        sbs.modid.client.skills.fishing.render.FishingAlert.getInstance().render(g);
        // Fishing tracker panels (catches / shards / profit): self-gating like the alert.
        sbs.modid.client.skills.fishing.render.FishingHud.render(g);
        // Sea creature kill list (left edge) and the "Reel in now!" bite indicator: both self-gating.
        sbs.modid.client.skills.fishing.render.SeaCreatureListHud.render(g);
        // Movable "baits left" chip: current bait's icon + how many remain (self-gating).
        sbs.modid.client.skills.fishing.render.BaitHud.render(g);
        sbs.modid.client.skills.fishing.render.GoldenFishHud.render(g);
        // Trophy Fish grid + session cards (self-gating: off by default, Crimson Isle only).
        sbs.modid.client.skills.trophyfish.render.TrophyFishHud.render(g);
        sbs.modid.client.skills.fishing.logic.BiteIndicator.getInstance().render(g);
        // Equipped-loadout card: the SBS Loadouts card for whatever is on your body (self-gating).
        sbs.modid.client.helper.loadouts.LoadoutHud.render(g);
        // Chocolate Factory card: the last menu capture, always labelled with its age (self-gating).
        sbs.modid.client.helper.chocolate.render.ChocolateHud.render(g);
        // Bingo card: only on a Bingo profile with this month's card read (self-gating, default off).
        sbs.modid.client.helper.bingo.render.BingoCardHud.render(g);
        // "Free slots: N" chip: drawn only at or under the warning threshold (self-gating).
        sbs.modid.client.helper.inventory.render.FreeSlotsHud.render(g);
        // Dungeon Score card: self-gating on the toggle + being in a dungeon (default off).
        sbs.modid.client.dungeons.run.render.DungeonScoreHud.render(g);
        // AH flip popup cards (in-world pass; over containers OverlayRenderMixin draws them instead).
        sbs.modid.client.economy.auctions.ui.AhFlipPopups.getInstance().renderHud(g);
        // Web Browser: keeps a page (e.g. a video) drawn over the world while no screen is open.
        sbs.modid.client.helper.browser.WebBrowserManager.getInstance().renderHud(g);
    }

    /** Server-stats value colours: healthy green, strained yellow, bad red. */
    private static final int STAT_GOOD = SBSTheme.TOGGLE_ON;
    private static final int STAT_MID = SBSTheme.HUD_OVERHEAL;
    private static final int STAT_BAD = SBSTheme.WARN;

    /**
     * "Show Server Stats" card: {@code Ping 45ms · TPS 19.6 · FPS 240} in one SBS card, top-left
     * by default (movable / scalable via the GUI editor). Values are colour-coded (green / yellow /
     * red); unknown values (not on a server, TPS still sampling) render as muted "-".
     */
    private static void renderServerStats(GuiGraphicsExtractor g) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        ServerStatsTracker stats = ServerStatsTracker.getInstance();

        int ping = stats.ping(minecraft);
        double tps = stats.tps();
        int fps = minecraft.getFps();

        String pingText = ping >= 0 ? ping + "ms" : "-";
        String tpsText = tps >= 0 ? String.format(java.util.Locale.US, "%.1f", tps) : "-";
        String fpsText = String.valueOf(Math.max(0, fps));

        int pingColor = ping < 0 ? SBSTheme.TEXT_MUTED
                : ping <= 90 ? STAT_GOOD : ping <= 180 ? STAT_MID : STAT_BAD;
        int tpsColor = tps < 0 ? SBSTheme.TEXT_MUTED
                : tps >= 18 ? STAT_GOOD : tps >= 14 ? STAT_MID : STAT_BAD;
        int fpsColor = fps >= 60 ? STAT_GOOD : fps >= 30 ? STAT_MID : STAT_BAD;

        // label (muted), value (coloured), separator (muted), ... – width fits the content.
        String[] texts = {"Ping ", pingText, " · ", "TPS ", tpsText, " · ", "FPS ", fpsText};
        int[] colors = {SBSTheme.TEXT_MUTED, pingColor, SBSTheme.TEXT_MUTED,
                SBSTheme.TEXT_MUTED, tpsColor, SBSTheme.TEXT_MUTED,
                SBSTheme.TEXT_MUTED, fpsColor};
        int textW = 0;
        for (String text : texts) {
            textW += font.width(text);
        }

        HudElement.Bounds b = HudElement.SERVER_STATS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        int w = textW + 10;
        int h = font.lineHeight + 7;

        HudLayout.measure(HudElement.SERVER_STATS, x, y, w, h);
        HudLayout.begin(g, HudElement.SERVER_STATS);
        HudCard.draw(g, x, y, w, h);
        int tx = x + 5;
        int ty = y + 4;
        for (int i = 0; i < texts.length; i++) {
            g.text(font, Component.literal(texts[i]), tx, ty, colors[i]);
            tx += font.width(texts[i]);
        }
        HudLayout.end(g);
    }

    /**
     * Active Pet card: [icon] Name [Lvl] / XP bar + percent + absolute XP / held item. The card
     * sizes itself to its content, so long names or XP texts never overflow the frame.
     */
    private static void renderActivePet(GuiGraphicsExtractor g) {
        PetTracker pet = PetTracker.getInstance();
        if (!pet.hasPet()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        HudElement.Bounds b = HudElement.ACTIVE_PET.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());

        String title = pet.name() + "  [Lvl " + pet.level() + "]";
        double percent = pet.xpPercent();
        String secondLine;
        if (pet.isMaxLevel()) {
            secondLine = "MAX LEVEL";
            percent = 100;
        } else {
            String percentText = percent >= 0
                    ? String.format(java.util.Locale.US, "%.1f%%", percent) : "?%";
            String xpText = pet.xpText();
            secondLine = xpText.isEmpty() ? percentText : percentText + "  " + xpText;
        }
        String held = pet.heldItem();

        // Dynamic card size: width fits the widest line, height fits 2 or 3 text rows.
        int barW = 60;
        int textX = 26;
        int lineH = font.lineHeight + 2;
        int w = textX + Math.max(Math.max(font.width(title), barW + 6 + font.width(secondLine)),
                held.isEmpty() ? 0 : font.width("Held: " + held)) + 8;
        int h = 6 + lineH * (held.isEmpty() ? 2 : 3) + 2;

        HudLayout.measure(HudElement.ACTIVE_PET, x, y, w, h);
        HudLayout.begin(g, HudElement.ACTIVE_PET);
        HudCard.draw(g, x, y, w, h);
        g.item(pet.icon(), x + 4, y + (h - 16) / 2);

        int tx = x + textX;
        int ty = y + 4;
        // Hypixel writes the pet's rarity into the name as colour codes. Kept by default (the colour
        // is a fact about the pet), but strippable for a card that matches every other card's title.
        String titleText = ConfigManager.getInstance().get().hypixelGui.petNameServerColors
                ? title : title.replaceAll("§.", "");
        g.text(font, Component.literal(titleText), tx, ty, SBSTheme.ACCENT_BRIGHT);
        ty += lineH;

        // XP bar + percent (+ absolute XP when known).
        int barY = ty + (font.lineHeight - 4) / 2;
        SciFiRender.roundedRect(g, tx, barY, barW, 4, 2, SBSTheme.HUD_TRACK);
        if (pet.isMaxLevel() && ConfigManager.getInstance().get().hypixelGui.chromaMaxPetBar) {
            // Nothing left to fill, so the full bar becomes the "maxed" shimmer instead.
            chromaBar(g, tx, barY, barW, 4, 2);
        } else if (percent >= 0) {
            int fill = (int) Math.round(Math.min(100, percent) / 100.0 * barW);
            if (fill > 0) {
                SciFiRender.roundedRect(g, tx, barY, fill, 4, 2, SBSTheme.ACCENT);
            }
        }
        g.text(font, Component.literal(secondLine), tx + barW + 6, ty, SBSTheme.TEXT_MUTED);
        ty += lineH;

        if (!held.isEmpty()) {
            g.text(font, Component.literal("Held: " + held), tx, ty, SBSTheme.TEXT_MUTED);
        }
        HudLayout.end(g);
    }

    /**
     * Hue turns per pixel across a chroma sweep - the profile viewer's own figure, reused so the
     * pet bar and a maxed skill row are visibly the same effect rather than two rainbows at
     * different pitches.
     */
    private static final double CHROMA_STEP = 0.012;

    /**
     * A bar painted as the travelling rainbow that marks "maxed" everywhere else in the mod.
     *
     * <p>A rounded bar cannot take a gradient in one call, so it is built from three passes:
     * <ol>
     *   <li>a rounded rect over the whole bar in the hue at its <b>left</b> edge - this is what
     *       gives the left cap its shape and the active UI style's material;</li>
     *   <li>a second rounded rect over the right half in the hue at its <b>right</b> edge, for the
     *       right cap. <b>Both caps have to be coloured separately:</b> a single base rect paints the
     *       right cap in the left edge's hue, which after a full rainbow's worth of sweep is a
     *       visibly wrong blob on the end of the bar;</li>
     *   <li>2px columns across the straight middle, which overpaint the inner rounded corners the
     *       second rect leaves behind - hence its left edge sits at the half-way point, well inside
     *       the column range, rather than near the end where a notch would show.</li>
     * </ol>
     *
     * <p>The phase comes from {@link Chroma}, i.e. from the wall clock, so this stays in step with
     * every other chroma element on screen instead of animating on its own clock.
     */
    private static void chromaBar(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius) {
        int speed = Chroma.speed();
        SciFiRender.roundedRect(g, x, y, w, h, radius, Chroma.color(x * CHROMA_STEP, speed));
        int mid = w / 2;
        if (w > radius * 2 && mid > radius) {
            SciFiRender.roundedRect(g, x + mid, y, w - mid, h, radius,
                    Chroma.color((x + w - radius) * CHROMA_STEP, speed));
        }
        for (int px = radius; px < w - radius; px += 2) {
            g.fill(x + px, y, x + Math.min(px + 2, w - radius), y + h,
                    Chroma.color((x + px) * CHROMA_STEP, speed));
        }
    }

    /**
     * Minecraft Overlay module: repaints the vanilla hotbar texture in the SBS design (rounded
     * blue panel + one cell per slot). Called at the head of the hotbar item pass – after the
     * vanilla background sprite, below the items.
     */
    public static void renderHotbarTheme(GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().minecraftOverlay.enabled) {
            return;
        }
        int x = g.guiWidth() / 2 - 91;
        int y = g.guiHeight() - 22;
        // 3px past the sprite on every side: the vanilla hotbar texture AND the 24x24 selection
        // frame (which sticks out 1px) are fully covered – no half-themed edges left.
        SciFiRender.roundedRect(g, x - 3, y - 3, 182 + 6, 22 + 5, SBSTheme.HUD_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRect(g, x - 2, y - 2, 182 + 4, 22 + 3, SBSTheme.HUD_CORNER - 1, SBSTheme.PANEL_BASE);
        SciFiRender.roundedRectGradient(g, x - 2, y - 2, 182 + 4, 22 + 3, SBSTheme.HUD_CORNER - 1,
                SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
        // SBS selection highlight: a full accent block drawn UNDER the cells – the cell fill
        // covers its centre, leaving a crisp 1px accent ring around the selected slot.
        Minecraft minecraft = Minecraft.getInstance();
        int selected = minecraft.player != null ? minecraft.player.getInventory().getSelectedSlot() : -1;
        if (selected >= 0) {
            SciFiRender.roundedRect(g, x + 1 + selected * 20, y + 1, 20, 20,
                    SBSTheme.SLOT_CORNER, SBSTheme.ACCENT);
        }
        for (int slot = 0; slot < 9; slot++) {
            SciFiRender.roundedRectWithBorder(g, x + 2 + slot * 20, y + 2, 18, 18,
                    SBSTheme.SLOT_CORNER, SBSTheme.SLOT_BG, SBSTheme.CARD_BORDER);
        }
    }

    /** Held-item cooldown as text ("10.1s"), right of the crosshair by default. */
    private static void renderCooldownHud(GuiGraphicsExtractor g) {
        double seconds = CooldownOverlay.remainingSeconds();
        if (seconds <= 0) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        HudElement.Bounds b = HudElement.ITEM_COOLDOWN.defaultBounds(g.guiWidth(), g.guiHeight());
        String text = String.format(java.util.Locale.US, "%.1fs", seconds);
        HudLayout.measure(HudElement.ITEM_COOLDOWN, b.x(), b.y(), font.width(text), font.lineHeight);
        HudLayout.begin(g, HudElement.ITEM_COOLDOWN);
        g.text(font, Component.literal(text),
                Math.round(b.x()), Math.round(b.y()), SBSTheme.ACCENT_BRIGHT);
        HudLayout.end(g);
    }

    /**
     * Draws the subtle 1-pixel darkened border one pixel outside the bar. Rendered before the bar so
     * it forms an outer ring without covering the fill – size, position and fill logic are unchanged.
     */
    private static void drawOutline(GuiGraphicsExtractor g, int x, int y) {
        SciFiRender.roundedRect(g, x - 1, y - 1, BAR_WIDTH + 2, BAR_HEIGHT + 2, BAR_RADIUS + 1,
                outlineColor());
    }

    /** A darker, near-opaque version of the given ARGB colour (RGB scaled by {@code factor}). */
    private static int darken(int argb, float factor) {
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int gg = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return (0xE0 << 24) | (r << 16) | (gg << 8) | b;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
