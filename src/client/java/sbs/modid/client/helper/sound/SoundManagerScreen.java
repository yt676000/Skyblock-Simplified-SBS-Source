/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.sound.SoundControl;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The Sound Manager's table: every sound in the game, searchable, each row with its state, its
 * volume and a preview.
 *
 * <p>Rows are drawn only for the visible slice of the (roughly fifteen-hundred entry) list, so the
 * screen costs the same whether the search matches five sounds or all of them. Nothing here runs on
 * the sound engine's path - {@link SoundControl} owns that and reads compiled maps.
 */
public final class SoundManagerScreen extends Screen {

    private static final int ROW_HEIGHT = 16;
    private static final int PANEL_W = 420;
    private static final int PANEL_H = 300;

    /** Columns, as offsets from the content's left edge. */
    private static final int COL_STATE = 0;
    private static final int COL_NAME = 26;
    private static final int COL_VOLUME = 300;
    private static final int COL_PREVIEW = 356;

    private String query = "";
    private int scroll;
    private List<SoundCatalog.Entry> visible = List.of();

    private int panelX;
    private int panelY;
    private int contentX;
    private int contentY;
    private int contentW;
    private int contentH;

    /** Two-step confirmation for the destructive switches, armed for this long. */
    private static final long CONFIRM_MS = 5_000L;
    private static long muteAllArmedAt;
    private static long whitelistArmedAt;

    private SoundManagerScreen() {
        super(Component.literal("Sound Manager"));
    }

    /** Opens the list. Called from the module page. */
    public static void open() {
        Minecraft.getInstance().setScreenAndShow(new SoundManagerScreen());
    }

    private static SBSConfig.SoundSettings cfg() {
        return ConfigManager.getInstance().get().sounds;
    }

    private static void save() {
        ConfigManager.getInstance().save();
        SoundControl.invalidate();
    }

    @Override
    protected void init() {
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;
        int pad = SBSTheme.PANEL_PADDING;
        contentX = panelX + pad;
        contentY = panelY + SBSTheme.HEADER_HEIGHT + SBSTheme.GAP_AFTER_HEADER + ROW_HEIGHT;
        contentW = PANEL_W - pad * 2;
        contentH = panelY + PANEL_H - pad - contentY;
        refresh();
        addRenderableOnly(new PanelRenderable());
    }

    private void refresh() {
        visible = SoundCatalog.search(query);
        int maxScroll = Math.max(0, visible.size() - rowsPerPage());
        scroll = Math.max(0, Math.min(maxScroll, scroll));
    }

    private int rowsPerPage() {
        return Math.max(1, contentH / ROW_HEIGHT);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (event.isAllowedChatCharacter()) {
            query += event.codepointAsString();
            scroll = 0;
            refresh();
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == GLFW_KEY_BACKSPACE && !query.isEmpty()) {
            query = query.substring(0, query.length() - 1);
            scroll = 0;
            refresh();
            return true;
        }
        return super.keyPressed(event);
    }

    /** GLFW's backspace, for trimming the search box. */
    private static final int GLFW_KEY_BACKSPACE = 259;

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int maxScroll = Math.max(0, visible.size() - rowsPerPage());
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(deltaY) * 3));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        int row = (int) ((event.y() - contentY) / ROW_HEIGHT);
        if (row < 0 || row >= rowsPerPage()) {
            return false;
        }
        int index = scroll + row;
        if (index >= visible.size()) {
            return false;
        }
        SoundCatalog.Entry entry = visible.get(index);
        double x = event.x() - contentX;

        if (x >= COL_PREVIEW) {
            preview(entry.id());
        } else if (x >= COL_VOLUME) {
            cycleVolume(entry.id());
        } else {
            toggleListed(entry.id());
        }
        return true;
    }

    /** Adds / removes the sound from the list, whichever way the current mode reads it. */
    private void toggleListed(Identifier id) {
        String key = id.toString();
        if (cfg().listed.contains(key)) {
            cfg().listed.remove(key);
        } else {
            cfg().listed.add(key);
        }
        save();
    }

    /** Steps a sound's volume through the useful stops rather than opening a slider per row. */
    private void cycleVolume(Identifier id) {
        String key = id.toString();
        int current = cfg().volumes.getOrDefault(key, 100);
        int next = switch (current) {
            case 100 -> 75;
            case 75 -> 50;
            case 50 -> 25;
            case 25 -> 10;
            default -> 100;
        };
        if (next == 100) {
            cfg().volumes.remove(key);   // absent means unchanged - keeps the map small
        } else {
            cfg().volumes.put(key, next);
        }
        save();
    }

    /**
     * Plays the sound once so the player can hear what they are muting.
     *
     * <p>Deliberately bypasses this module's own filter: previewing a muted sound has to work, or
     * there is no way to find out what you silenced.
     */
    private void preview(Identifier id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        BuiltInRegistries.SOUND_EVENT.getOptional(id).ifPresent(event ->
                minecraft.player.playSound(event, 1.0f, 1.0f));
    }

    /** The mode switch, with the confirmation whitelist mode needs. */
    public static void toggleModeWithConfirm() {
        SBSConfig.SoundSettings cfg = cfg();
        if (cfg.whitelistMode) {
            cfg.whitelistMode = false;   // leaving whitelist mode is always safe
            save();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - whitelistArmedAt > CONFIRM_MS) {
            whitelistArmedAt = now;
            SBSChat.send(Component.literal(
                    " Whitelist mode silences EVERY game sound except the ones you list - including "
                            + "interface clicks and warning cues. Click again within 5s to switch.")
                    .withColor(0xE0A14D));
            return;
        }
        whitelistArmedAt = 0;
        cfg.whitelistMode = true;
        save();
        SBSChat.send(Component.literal(" Whitelist mode on - only listed sounds play now.")
                .withColor(0xFFD65A));
    }

    /** Mutes everything, behind the same two-step confirm. */
    public static void muteAllWithConfirm() {
        long now = System.currentTimeMillis();
        if (now - muteAllArmedAt > CONFIRM_MS) {
            muteAllArmedAt = now;
            SBSChat.send(Component.literal(
                    " This mutes every game sound. Click again within 5s to confirm - Reset puts "
                            + "them all back.").withColor(0xE0A14D));
            return;
        }
        muteAllArmedAt = 0;
        SBSConfig.SoundSettings cfg = cfg();
        cfg.whitelistMode = false;   // a full mute is a blacklist of everything, not a whitelist
        cfg.listed.clear();
        for (SoundCatalog.Entry entry : SoundCatalog.all()) {
            cfg.listed.add(entry.id().toString());
        }
        save();
        SBSChat.send(Component.literal(" Muted " + cfg.listed.size() + " sounds.")
                .withColor(0xFFD65A));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            g.fill(0, 0, SoundManagerScreen.this.width, SoundManagerScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, PANEL_W, PANEL_H, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, PANEL_W, PANEL_H, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, PANEL_W - 2, PANEL_H - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.text(font, Component.literal("Sound Manager"), panelX + SBSTheme.PANEL_PADDING,
                    titleY, SBSTheme.ACCENT_BRIGHT);
            String mode = cfg().whitelistMode ? "Whitelist" : "Blacklist";
            String header = mode + "  •  " + visible.size() + " shown  •  type to search: "
                    + (query.isEmpty() ? "…" : query);
            g.text(font, Component.literal(header), panelX + SBSTheme.PANEL_PADDING,
                    titleY + font.lineHeight + 4, SBSTheme.TEXT_MUTED);

            int rows = Math.min(rowsPerPage(), visible.size() - scroll);
            for (int i = 0; i < rows; i++) {
                SoundCatalog.Entry entry = visible.get(scroll + i);
                int y = contentY + i * ROW_HEIGHT;
                String key = entry.id().toString();
                boolean isListed = cfg().listed.contains(key);
                // In whitelist mode the listed entries are the ones that PLAY, so the same flag
                // means the opposite thing - say which, rather than showing an ambiguous tick.
                boolean plays = cfg().whitelistMode == isListed;

                g.text(font, Component.literal(plays ? "§aon" : "§8off"), contentX + COL_STATE, y,
                        SBSTheme.TEXT);
                g.text(font, Component.literal(entry.displayName()), contentX + COL_NAME, y,
                        plays ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
                int volume = cfg().volumes.getOrDefault(key, 100);
                g.text(font, Component.literal(volume + "%"), contentX + COL_VOLUME, y,
                        volume == 100 ? SBSTheme.TEXT_MUTED : SBSTheme.ACCENT_BRIGHT);
                g.text(font, Component.literal("§7play"), contentX + COL_PREVIEW, y, SBSTheme.TEXT);
            }
            if (visible.isEmpty()) {
                g.text(font, Component.literal("Nothing matches that search"), contentX, contentY,
                        SBSTheme.TEXT_MUTED);
            }
        }
    }
}
