/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.party.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.command.SBSCommands;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.social.party.logic.PartyFinderApi;
import sbs.modid.client.social.party.logic.PartyFinderManager;
import sbs.modid.client.social.party.model.PartyTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The universal SBS Party Finder: a type sidebar on the left (All / Diana /
 * Fishing / Mining / Combat / Dungeons / Kuudra / Custom + "My Party"), a free-text search that the
 * server matches against note, type, location/mob/mode and member names, and the live party list –
 * every row shows leader, note and whether YOU provably meet the requirements (server-checked
 * against your SkyBlock profile). Click a row to join.
 *
 * <p>"My Party" switches to the own-party panel: live roster (leader can kick / invite in game via
 * {@code /party invite}), the party chat with its own input field (Enter sends – same channel as
 * {@code /sbs party <msg>}), Invite All and Leave.
 */
public final class PartyFinderScreen extends Screen {

    private static final int SIDEBAR_W = 86;
    private static final int ROW_H = 24;
    private static final int SEARCH_INSET = 13;

    /** Selected sidebar tab: "" = All, a PartyTypes key, or "my" = My Party. Survives reopen. */
    private static String tab = "";

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int sidebarX;
    private int sidebarTop;
    private int contentX;
    private int contentW;
    private int listTop;
    private int listBottom;
    private int buttonY;
    private int scroll;

    private EditBox searchBox;
    private EditBox chatBox;
    private String query = "";

    private volatile List<JsonObject> parties = List.of();
    private volatile String status = "Loading...";

    public PartyFinderScreen() {
        super(Component.literal("SBS Party Finder"));
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 400, 640);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 240, 420);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        sidebarX = panelX + pad;
        sidebarTop = dividerY + SBSTheme.GAP_AFTER_HEADER;
        contentX = sidebarX + SIDEBAR_W + pad;
        contentW = panelX + panelW - pad - contentX;
        buttonY = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
        listTop = sidebarTop + SBSTheme.SEARCH_HEIGHT + SBSTheme.GAP_AFTER_SEARCH;
        listBottom = buttonY - 6;

        addRenderableOnly(new PanelRenderable());

        if (isMyPartyTab()) {
            initMyParty();
        } else {
            initFinder();
        }
    }

    private boolean isMyPartyTab() {
        return "my".equals(tab) && PartyFinderManager.getInstance().inParty();
    }

    private void initFinder() {
        int textH = this.font.lineHeight;
        searchBox = new EditBox(this.font, contentX + SEARCH_INSET,
                sidebarTop + (SBSTheme.SEARCH_HEIGHT - textH) / 2,
                contentW - SEARCH_INSET - 4, textH, Component.literal("Search"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(64);
        searchBox.setTextColor(SBSTheme.TEXT);
        searchBox.setHint(Component.literal("Search note, player, mob..."));
        searchBox.setValue(query);
        searchBox.setResponder(value -> {
            query = value;
            refresh();
        });
        addRenderableWidget(searchBox);

        int bw = (contentW - 12) / 3;
        addRenderableWidget(new SciFiButton(contentX, buttonY, bw, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Create"), () ->
                Minecraft.getInstance().setScreenAndShow(new PartyCreateScreen())));
        addRenderableWidget(new SciFiButton(contentX + bw + 6, buttonY, bw, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Refresh"), this::refresh));
        addRenderableWidget(new SciFiButton(contentX + (bw + 6) * 2, buttonY,
                contentW - (bw + 6) * 2, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), () ->
                Minecraft.getInstance().setScreenAndShow(new SBSMainScreen())));

        refresh();
    }

    private void initMyParty() {
        // Chat input at the bottom of the content column (Enter sends).
        int textH = this.font.lineHeight;
        chatBox = new EditBox(this.font, contentX + 6,
                buttonY - SBSTheme.SEARCH_HEIGHT - 4 + (SBSTheme.SEARCH_HEIGHT - textH) / 2,
                contentW - 12, textH, Component.literal("Party chat"));
        chatBox.setBordered(false);
        chatBox.setMaxLength(256);
        chatBox.setTextColor(SBSTheme.TEXT);
        chatBox.setHint(Component.literal("Message your party... (Enter)"));
        addRenderableWidget(chatBox);

        // Leader toggle: auto-fire /party invite for everyone who joins the SBS party.
        addRenderableWidget(new sbs.modid.client.ui.component.SciFiToggleButton(
                sidebarX, buttonY, SIDEBAR_W, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Auto Inv"),
                () -> sbs.modid.client.core.config.ConfigManager.getInstance().get().partyFinder.autoInvite,
                () -> {
                    var cfg = sbs.modid.client.core.config.ConfigManager.getInstance();
                    cfg.get().partyFinder.autoInvite = !cfg.get().partyFinder.autoInvite;
                    cfg.save();
                }));

        int bw = (contentW - 12) / 3;
        addRenderableWidget(new SciFiButton(contentX, buttonY, bw, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Invite All"), this::inviteAll));
        addRenderableWidget(new SciFiButton(contentX + bw + 6, buttonY, bw, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Leave"), () -> {
                    PartyFinderManager.getInstance().leave();
                    tab = "";
                    Minecraft.getInstance().setScreenAndShow(new PartyFinderScreen());
                }));
        addRenderableWidget(new SciFiButton(contentX + (bw + 6) * 2, buttonY,
                contentW - (bw + 6) * 2, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Back"), () ->
                Minecraft.getInstance().setScreenAndShow(new SBSMainScreen())));
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void refresh() {
        if (!PartyFinderApi.ready()) {
            status = "Need a world + licence token (Licence Token module).";
            return;
        }
        status = "Loading...";
        String type = tab.isEmpty() || "my".equals(tab) ? null : tab;
        PartyFinderApi.getInstance().list(type, query, false, (result, error) -> {
            if (error != null) {
                status = "Failed to load: " + error;
                parties = List.of();
                return;
            }
            List<JsonObject> list = new ArrayList<>();
            for (var element : result.getAsJsonArray("parties")) {
                list.add(element.getAsJsonObject());
            }
            parties = list;
            status = list.isEmpty() ? "No parties found" : "";
        });
    }

    private void joinAt(int index) {
        List<JsonObject> current = parties;
        if (index < 0 || index >= current.size()) {
            return;
        }
        JsonObject party = current.get(index);
        String id = party.get("id").getAsString();
        if (id.equals(PartyFinderManager.getInstance().currentPartyId())) {
            tab = "my";
            Minecraft.getInstance().setScreenAndShow(new PartyFinderScreen());
            return;
        }
        if (party.has("you_meet") && !party.get("you_meet").getAsBoolean()) {
            status = "You do not meet this party's requirements.";
            return;
        }
        status = "Joining...";
        PartyFinderApi.getInstance().join(id, (result, error) -> {
            if (error != null) {
                status = "Join failed: " + error;
                return;
            }
            PartyFinderManager.getInstance().enter(id);
            tab = "my";
            Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().setScreenAndShow(new PartyFinderScreen()));
        });
    }

    /** Fires the real Hypixel invite for every member (leader convenience). */
    private void inviteAll() {
        JsonObject party = PartyFinderManager.getInstance().partySnapshot();
        if (party == null) {
            return;
        }
        StringBuilder names = new StringBuilder();
        String self = PartyFinderApi.selfName();
        for (var element : party.getAsJsonArray("members")) {
            String name = element.getAsJsonObject().get("name").getAsString();
            if (!name.equalsIgnoreCase(self)) {
                names.append(' ').append(name);
            }
        }
        if (!names.isEmpty()) {
            SBSCommands.run("/party invite" + names);
        }
    }

    private void sendChat() {
        if (chatBox == null || chatBox.getValue().isBlank()) {
            return;
        }
        PartyFinderManager.getInstance().sendChat(chatBox.getValue().trim());
        chatBox.setValue("");
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (chatBox != null && chatBox.isFocused()
                && (event.key() == 257 || event.key() == 335)) {  // Enter / keypad Enter
            sendChat();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        double mx = event.x();
        double my = event.y();

        // Sidebar tabs.
        if (mx >= sidebarX && mx <= sidebarX + SIDEBAR_W && my >= sidebarTop) {
            int index = (int) ((my - sidebarTop) / 16);
            List<String> tabs = sidebarTabs();
            if (index >= 0 && index < tabs.size()) {
                tab = tabs.get(index).equals("all") ? "" : tabs.get(index);
                scroll = 0;
                Minecraft.getInstance().setScreenAndShow(new PartyFinderScreen());
                return true;
            }
        }

        if (isMyPartyTab()) {
            return myPartyClick(mx, my);
        }
        if (mx >= contentX && mx <= contentX + contentW && my >= listTop && my <= listBottom) {
            joinAt(scroll + (int) ((my - listTop) / ROW_H));
            return true;
        }
        return false;
    }

    /** Kick buttons in the roster (leader only): a small [x] at the row's right edge. */
    private boolean myPartyClick(double mx, double my) {
        JsonObject party = PartyFinderManager.getInstance().partySnapshot();
        if (party == null) {
            return false;
        }
        String selfUuid = normalize(PartyFinderApi.selfUuid());
        boolean leader = party.getAsJsonObject("leader").get("uuid").getAsString().equals(selfUuid);
        if (!leader) {
            return false;
        }
        JsonArray members = party.getAsJsonArray("members");
        for (int i = 0; i < members.size(); i++) {
            int y = listTop + i * 14;
            if (my >= y && my < y + 12 && mx >= contentX + contentW - 16 && mx <= contentX + contentW - 4) {
                String target = members.get(i).getAsJsonObject().get("uuid").getAsString();
                if (!target.equals(selfUuid)) {
                    PartyFinderApi.getInstance().kick(party.get("id").getAsString(), target, (r, e) -> { });
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseX >= contentX && mouseY >= listTop && mouseY <= listBottom
                && !isMyPartyTab()) {
            int visible = Math.max(1, (listBottom - listTop) / ROW_H);
            scroll = clamp(scroll - (int) Math.signum(scrollY), 0,
                    Math.max(0, parties.size() - visible));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private List<String> sidebarTabs() {
        List<String> tabs = new ArrayList<>();
        tabs.add("all");
        tabs.addAll(PartyTypes.KEYS);
        if (PartyFinderManager.getInstance().inParty()) {
            tabs.add("my");
        }
        return tabs;
    }

    private static String normalize(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = PartyFinderScreen.this.font;
            g.fill(0, 0, PartyFinderScreen.this.width, PartyFinderScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("SBS Party Finder"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            int colX = sidebarX + SIDEBAR_W + pad / 2;
            g.fill(colX, sidebarTop, colX + 1, panelY + panelH - pad, SBSTheme.ACCENT_SOFT);

            drawSidebar(g, mouseX, mouseY);
            if (isMyPartyTab()) {
                drawMyParty(g);
            } else {
                drawFinder(g);
            }
        }

        private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = PartyFinderScreen.this.font;
            List<String> tabs = sidebarTabs();
            for (int i = 0; i < tabs.size(); i++) {
                String key = tabs.get(i);
                int y = sidebarTop + i * 16;
                boolean selected = key.equals("all") ? tab.isEmpty() : key.equals(tab);
                boolean hovered = mouseX >= sidebarX && mouseX <= sidebarX + SIDEBAR_W
                        && mouseY >= y && mouseY < y + 16;
                if (selected || hovered) {
                    SciFiRender.roundedRect(g, sidebarX, y, SIDEBAR_W, 14, SBSTheme.CORNER_RADIUS,
                            selected ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
                }
                if (selected) {
                    g.fill(sidebarX + 1, y + 2, sidebarX + 3, y + 12, SBSTheme.ACCENT);
                }
                String label = key.equals("my") ? "My Party" : PartyTypes.label(key.equals("all") ? "" : key);
                g.text(font, Component.literal(label), sidebarX + 7, y + 3,
                        selected ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            }
        }

        private void drawFinder(GuiGraphicsExtractor g) {
            var font = PartyFinderScreen.this.font;
            // Search field card + magnifier (same look as the main screen).
            int searchBorder = (searchBox != null && searchBox.isFocused())
                    ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
            SciFiRender.roundedRectWithBorder(g, contentX, sidebarTop, contentW, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL, searchBorder);
            int iconX = contentX + 4;
            int iconY = sidebarTop + (SBSTheme.SEARCH_HEIGHT - 5) / 2;
            g.outline(iconX, iconY, 5, 5, SBSTheme.ACCENT);
            g.fill(iconX + 4, iconY + 4, iconX + 6, iconY + 6, SBSTheme.ACCENT);

            if (!status.isEmpty()) {
                g.centeredText(font, Component.literal(status),
                        contentX + contentW / 2, listTop + 8, SBSTheme.TEXT_MUTED);
            }
            List<JsonObject> current = parties;
            int visible = Math.max(1, (listBottom - listTop) / ROW_H);
            for (int row = 0; row < visible; row++) {
                int index = scroll + row;
                if (index >= current.size()) {
                    break;
                }
                JsonObject party = current.get(index);
                int y = listTop + row * ROW_H;
                boolean meet = !party.has("you_meet") || party.get("you_meet").getAsBoolean();
                boolean own = party.get("id").getAsString()
                        .equals(PartyFinderManager.getInstance().currentPartyId());
                SciFiRender.roundedRectWithBorder(g, contentX, y, contentW, ROW_H - 3, SBSTheme.CORNER_RADIUS,
                        SBSTheme.CARD_BG, own ? SBSTheme.ACCENT_BRIGHT
                                : meet ? SBSTheme.CARD_BORDER : SBSTheme.BAZAAR_OUTDATED_FRAME);

                String leader = party.getAsJsonObject("leader").get("name").getAsString();
                String extra = str(party, "location", str(party, "mob", ""));
                String head = "§f" + PartyTypes.label(str(party, "type", "generic"))
                        + (extra.isEmpty() ? "" : " §b" + extra) + " §7• " + leader;
                g.text(font, Component.literal(head), contentX + 5, y + 3, SBSTheme.TEXT);
                String right = party.get("member_count").getAsInt() + "/" + party.get("size").getAsInt()
                        + (own ? " §byou" : meet ? " §a✔" : " §c✘");
                g.text(font, Component.literal(right),
                        contentX + contentW - 5 - font.width(right.replaceAll("§.", "")),
                        y + 3, SBSTheme.TEXT_MUTED);
                String note = str(party, "note", "");
                String mode = str(party, "mode", "");
                String second = (note + (mode.isEmpty() ? "" : "  §8[" + mode + "]")).trim();
                if (!second.isEmpty()) {
                    g.text(font, Component.literal("§7" + second),
                            contentX + 5, y + 3 + font.lineHeight + 1, SBSTheme.TEXT_MUTED);
                }
            }
        }

        private void drawMyParty(GuiGraphicsExtractor g) {
            var font = PartyFinderScreen.this.font;
            JsonObject party = PartyFinderManager.getInstance().partySnapshot();
            if (party == null) {
                g.centeredText(font, Component.literal("Connecting to your party..."),
                        contentX + contentW / 2, listTop + 8, SBSTheme.TEXT_MUTED);
                return;
            }
            String selfUuid = normalize(PartyFinderApi.selfUuid());
            String leaderUuid = party.getAsJsonObject("leader").get("uuid").getAsString();
            boolean leader = leaderUuid.equals(selfUuid);

            // Header line: type + size + note.
            String head = "§f" + PartyTypes.label(str(party, "type", "generic"))
                    + " §7" + party.get("member_count").getAsInt() + "/" + party.get("size").getAsInt()
                    + (str(party, "note", "").isEmpty() ? "" : " §7- " + str(party, "note", ""));
            g.text(font, Component.literal(head), contentX, sidebarTop + 2, SBSTheme.TEXT);

            // Roster: one line per member, leader crown, kick [x] for the leader.
            JsonArray members = party.getAsJsonArray("members");
            for (int i = 0; i < members.size(); i++) {
                JsonObject member = members.get(i).getAsJsonObject();
                int y = listTop + i * 14;
                String name = member.get("name").getAsString();
                boolean isLeader = member.get("uuid").getAsString().equals(leaderUuid);
                g.text(font, Component.literal((isLeader ? "§6♛ " : "§7• ") + "§f" + name),
                        contentX + 2, y, SBSTheme.TEXT);
                if (leader && !member.get("uuid").getAsString().equals(selfUuid)) {
                    g.text(font, Component.literal("§c[x]"), contentX + contentW - 16, y, SBSTheme.TEXT);
                }
            }

            // Chat: the last lines that fit between roster and the input field.
            int chatTop = listTop + members.size() * 14 + 6;
            int chatBottom = buttonY - SBSTheme.SEARCH_HEIGHT - 8;
            g.fill(contentX, chatTop - 3, contentX + contentW, chatTop - 2, SBSTheme.ACCENT_SOFT);
            List<String> lines = PartyFinderManager.getInstance().chatLines();
            int fit = Math.max(0, (chatBottom - chatTop) / (font.lineHeight + 1));
            int start = Math.max(0, lines.size() - fit);
            int y = chatTop;
            for (int i = start; i < lines.size(); i++) {
                g.text(font, Component.literal(lines.get(i)), contentX + 2, y, SBSTheme.TEXT);
                y += font.lineHeight + 1;
            }
            // Chat input card behind the EditBox.
            int inputY = buttonY - SBSTheme.SEARCH_HEIGHT - 4;
            int border = (chatBox != null && chatBox.isFocused())
                    ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
            SciFiRender.roundedRectWithBorder(g, contentX, inputY, contentW, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL, border);
        }
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() && !o.get(key).getAsString().isEmpty()
                ? o.get(key).getAsString() : def;
    }
}
