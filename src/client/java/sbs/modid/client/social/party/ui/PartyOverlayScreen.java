/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.party.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiTextField;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.social.party.logic.PartyTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * The Party Overlay: a keybindable panel that covers Hypixel's party commands as buttons, so
 * {@code /p warp}, {@code /p transfer}, {@code /p invite} and friends never have to be typed.
 *
 * <p>Four sections:
 * <ul>
 *   <li><b>Global actions</b> – every party-wide command: warp, transfer-to-me, mute, allinvite,
 *       kickoffline, list, private game, leave, disband.</li>
 *   <li><b>Members</b> – the live roster from {@link PartyTracker}, each with transfer / promote /
 *       demote / kick.</li>
 *   <li><b>Favourites</b> – saved names (config) for one-click invites, plus a name field that can
 *       invite directly or save the name.</li>
 *   <li><b>SBS</b> – the mod's own party features: Party Finder, Carry Tickets, and the master
 *       toggle for the {@code !command} party-chat commands.</li>
 * </ul>
 *
 * <p>Every action is a plain {@code player.connection.sendCommand("p ...")}; nothing here talks to a
 * backend. The panel stays open after an action (so several invites / transfers are quick) and
 * closes on Escape.
 */
public final class PartyOverlayScreen extends Screen {

    private static final int ROW_H = 20;
    private static final int BTN_H = 16;
    private static final int SECTION_GAP = 8;
    private static final int GAP = 4;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    /** Text drawn by the panel renderable (labels / headings), rebuilt each {@link #init()}. */
    private final List<Label> labels = new ArrayList<>();

    /** The draft name in the invite / favourite field. */
    private String favInput = "";

    private record Label(String text, int x, int y, int color) {
    }

    public PartyOverlayScreen() {
        super(Component.literal("Party"));
    }

    private static SBSConfig.PartyOverlaySettings cfg() {
        return ConfigManager.getInstance().get().partyOverlay;
    }

    /** Sends a Hypixel command (no leading slash), the same path the chat box uses. */
    private static void send(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand(command);
        }
    }

    private static String selfName() {
        var player = Minecraft.getInstance().player;
        return player == null ? "" : player.getGameProfile().name();
    }

    @Override
    protected void init() {
        labels.clear();
        List<String> members = new ArrayList<>(PartyTracker.getInstance().members());
        List<String> favorites = cfg().favorites;

        int pad = SBSTheme.PANEL_PADDING;
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 320, 400);
        int contentWidth = panelW - pad * 2;

        // Measure the content height so the panel is exactly as tall as it needs to be.
        int heading = font.lineHeight + 4;
        int content = 3 * (BTN_H + GAP)                                  // global actions (3 rows)
                + SECTION_GAP + heading + Math.max(1, members.size()) * ROW_H
                + SECTION_GAP + heading + favorites.size() * ROW_H + BTN_H + GAP
                + SECTION_GAP + heading + 2 * (BTN_H + GAP)              // SBS section
                + SECTION_GAP + BTN_H;                                   // close button
        panelH = Math.min(this.height - SBSTheme.SCREEN_MARGIN * 2,
                SBSTheme.HEADER_HEIGHT + SBSTheme.GAP_AFTER_HEADER + content + pad);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        addRenderableOnly(new PanelRenderable());

        int innerX = panelX + pad;
        int y = panelY + SBSTheme.HEADER_HEIGHT + SBSTheme.GAP_AFTER_HEADER;

        // ---- Global actions: three rows of three (every party-wide Hypixel command) ----
        int bw = (contentWidth - 2 * GAP) / 3;
        int lastW = contentWidth - (bw + GAP) * 2;
        String me = selfName();
        addButton(innerX, y, bw, "Warp", () -> send("p warp"));
        addButton(innerX + bw + GAP, y, bw, "To Me", () -> { if (!me.isEmpty()) send("p transfer " + me); });
        addButton(innerX + (bw + GAP) * 2, y, lastW, "Mute", () -> send("p mute"));
        y += BTN_H + GAP;
        addButton(innerX, y, bw, "All Invite", () -> send("p settings allinvite"));
        addButton(innerX + bw + GAP, y, bw, "Kick Offl.", () -> send("p kickoffline"));
        addButton(innerX + (bw + GAP) * 2, y, lastW, "List", () -> send("p list"));
        y += BTN_H + GAP;
        addButton(innerX, y, bw, "Private", () -> send("p private"));
        addButton(innerX + bw + GAP, y, bw, "Leave", () -> { send("p leave"); onClose(); });
        addButton(innerX + (bw + GAP) * 2, y, lastW, "Disband", () -> { send("p disband"); onClose(); });
        y += BTN_H + GAP;

        // ---- Members ----
        y += SECTION_GAP;
        labels.add(new Label("Members", innerX, y, SBSTheme.ACCENT));
        y += font.lineHeight + 4;
        if (members.isEmpty()) {
            labels.add(new Label("No party members tracked yet", innerX, y + 4, SBSTheme.TEXT_MUTED));
            y += ROW_H;
        } else {
            int abw = 42;
            for (String name : members) {
                int by = y + (ROW_H - BTN_H) / 2;
                int kickX = innerX + contentWidth - abw;
                int demoX = kickX - GAP - abw;
                int promoX = demoX - GAP - abw;
                int leadX = promoX - GAP - abw;
                labels.add(new Label(name, innerX, y + (ROW_H - font.lineHeight) / 2, SBSTheme.TEXT));
                addButton(leadX, by, abw, "Lead", () -> send("p transfer " + name));
                addButton(promoX, by, abw, "Promo", () -> send("p promote " + name));
                addButton(demoX, by, abw, "Demo", () -> send("p demote " + name));
                addButton(kickX, by, abw, "Kick", () -> { send("p kick " + name); rebuildWidgets(); });
                y += ROW_H;
            }
        }

        // ---- Favourites ----
        y += SECTION_GAP;
        labels.add(new Label("Favourites", innerX, y, SBSTheme.ACCENT));
        y += font.lineHeight + 4;
        int inviteW = 54;
        int removeW = 20;
        for (String name : favorites) {
            int by = y + (ROW_H - BTN_H) / 2;
            int removeX = innerX + contentWidth - removeW;
            int inviteX = removeX - GAP - inviteW;
            labels.add(new Label(name, innerX, y + (ROW_H - font.lineHeight) / 2, SBSTheme.TEXT));
            addButton(inviteX, by, inviteW, "Invite", () -> send("p invite " + name));
            addButton(removeX, by, removeW, "X", () -> { favorites.remove(name); save(); rebuildWidgets(); });
            y += ROW_H;
        }
        // Name field + Invite (one-off) + +Fav (save it for the list).
        int addW = 40;
        int invW = 40;
        SciFiTextField field = SciFiTextField.forRow(innerX, y, contentWidth - addW - invW - GAP * 2, BTN_H,
                "Player", "name...", 16, () -> favInput, v -> favInput = v);
        addRenderableWidget(field);
        addButton(innerX + contentWidth - addW - invW - GAP, y, invW, "Invite", () -> {
            String name = cleanName();
            if (!name.isEmpty()) {
                send("p invite " + name);
            }
        });
        addButton(innerX + contentWidth - addW, y, addW, "+Fav", this::addFavorite);
        y += BTN_H + GAP;

        // ---- SBS party features ----
        y += SECTION_GAP;
        labels.add(new Label("SBS", innerX, y, SBSTheme.ACCENT));
        y += font.lineHeight + 4;
        int half = (contentWidth - GAP) / 2;
        addButton(innerX, y, half, "Party Finder", () ->
                Minecraft.getInstance().setScreenAndShow(new PartyFinderScreen()));
        addButton(innerX + half + GAP, y, contentWidth - half - GAP, "Carry Tickets", () ->
                sbs.modid.client.combat.carry.CarryModule.openScreen());
        y += BTN_H + GAP;
        addRenderableWidget(new SciFiToggleButton(innerX, y, contentWidth, BTN_H,
                Component.literal("Party Commands (!warp, !ptme, ...)"),
                () -> ConfigManager.getInstance().get().partyCommands.enabled,
                () -> {
                    var pc = ConfigManager.getInstance().get().partyCommands;
                    pc.enabled = !pc.enabled;
                    save();
                }));
        y += BTN_H + GAP;

        // ---- Close ----
        y += SECTION_GAP;
        addButton(innerX, y, contentWidth, "Close", this::onClose);
    }

    /** The trimmed, validated name from the input field, or empty when it is not a Minecraft name. */
    private String cleanName() {
        String name = favInput == null ? "" : favInput.trim();
        return name.matches("[A-Za-z0-9_]{1,16}") ? name : "";
    }

    private void addFavorite() {
        String name = cleanName();
        if (name.isEmpty()) {
            return;
        }
        List<String> favorites = cfg().favorites;
        for (String existing : favorites) {
            if (existing.equalsIgnoreCase(name)) {
                favInput = "";
                rebuildWidgets();
                return;
            }
        }
        favorites.add(name);
        save();
        favInput = "";
        rebuildWidgets();
    }

    private void addButton(int x, int y, int w, String text, Runnable action) {
        addRenderableWidget(new SciFiButton(x, y, w, BTN_H, Component.literal(text), action));
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = PartyOverlayScreen.this.font;
            g.fill(0, 0, PartyOverlayScreen.this.width, PartyOverlayScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Party"), panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, panelY + SBSTheme.HEADER_HEIGHT,
                    panelX + panelW - pad, panelY + SBSTheme.HEADER_HEIGHT + 1, SBSTheme.ACCENT);
            for (Label label : labels) {
                g.text(font, Component.literal(label.text()), label.x(), label.y(), label.color());
            }
        }
    }
}
