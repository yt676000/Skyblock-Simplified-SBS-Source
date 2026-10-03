/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.carry.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.carry.logic.CarryApi;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The SBS Carry-Ticket screen: open / my / claimed tickets with a per-ticket chat, ticket creation,
 * and - for the OWNER - carrier management.
 *
 * <p><b>Identity model</b> (mirrors the server): every carrier / owner chat tag shown here is the
 * server-pinned IGN resolved from the sender's auth token - the mod neither sends nor can influence
 * it. User messages carry a self-reported name and are marked unverified; the server-provided
 * disclaimer is pinned above every chat.
 */
public final class CarryScreen extends Screen {

    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_NUMPAD_ENTER = 335;
    private static final int KEY_BACKSPACE = 259;

    private enum Tab { OPEN, MINE, CLAIMED, CARRIERS }

    /** A hand-rolled text field (EditBox repositions poorly in this fluid layout). */
    private static final class Field {
        String value = "";
        boolean focused;
        int x;
        int y;
        int w;
        int h;
        final int max;
        final String hint;

        Field(int max, String hint) {
            this.max = max;
            this.hint = hint;
        }

        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final Screen parent;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listW;
    private int listTop;
    private int listBottom;
    private int detailX;
    private int detailW;

    private Tab tab = Tab.OPEN;
    private int listScroll;
    private int chatScroll;

    /** Server meta: categories (order preserved), owner flag, my carrier record, disclaimer. */
    private final Map<String, String> categoryLabels = new LinkedHashMap<>();
    private final Map<String, List<String>> categoryTiers = new LinkedHashMap<>();
    private boolean owner;
    private String myCarrierIgn = "";
    private String disclaimer = "";

    private final List<JsonObject> tickets = new ArrayList<>();
    private final List<JsonObject> carriers = new ArrayList<>();
    private String status = "Loading...";

    /** Selected ticket + its accumulated chat. */
    private String selectedId;
    private final List<JsonObject> chat = new ArrayList<>();
    private int chatSince = -1;
    private int pollGeneration;
    private JsonObject selectedTicket;

    /** Create form state. */
    private int createCategory;
    private int createTier;
    private final Field noteField = new Field(200, "note (optional)");
    private final Field chatField = new Field(300, "message...");
    private final Field adminToken = new Field(120, "licence token of the carrier");
    private final Field adminIgn = new Field(16, "in-game name (pinned tag)");
    private final Field adminTiers = new Field(80, "tiers: all or F5,F6,F7");
    private int adminCategory;

    /** Ticket search (category / tier / note / creator / carrier). */
    private final Field searchField = new Field(40, "search tickets...");

    /** Per-frame action buttons: {x, y, w, h} -> runnable. */
    private final List<int[]> buttonRects = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();

    public CarryScreen(Screen parent) {
        super(Component.literal("SBS Carry Tickets"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 420, 640);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 280, 480);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = SBSTheme.PANEL_PADDING;
        listX = panelX + pad;
        listW = (panelW - pad * 3) * 2 / 5;
        detailX = listX + listW + pad;
        detailW = panelX + panelW - pad - detailX;
        // Room for the tab row AND the search row above the list.
        listTop = panelY + SBSTheme.HEADER_HEIGHT + SBSTheme.SEARCH_HEIGHT * 2 + 14;
        listBottom = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT * 2 - 10;
        addRenderableOnly(new PanelRenderable());
        refresh();
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private void refresh() {
        status = "Loading...";
        CarryApi.getInstance().meta((result, error) -> {
            if (error != null) {
                status = "Error: " + error;
                return;
            }
            categoryLabels.clear();
            categoryTiers.clear();
            JsonObject categories = result.getAsJsonObject("categories");
            for (String key : categories.keySet()) {
                JsonObject category = categories.getAsJsonObject(key);
                categoryLabels.put(key, category.get("label").getAsString());
                List<String> tiers = new ArrayList<>();
                category.getAsJsonArray("tiers").forEach(t -> tiers.add(t.getAsString()));
                categoryTiers.put(key, tiers);
            }
            owner = result.get("owner").getAsBoolean();
            myCarrierIgn = result.has("carrier") && result.get("carrier").isJsonObject()
                    ? result.getAsJsonObject("carrier").get("ign").getAsString() : "";
            disclaimer = result.get("disclaimer").getAsString();
            status = "";
        });
        refreshTickets();
    }

    private void refreshTickets() {
        CarryApi.getInstance().tickets((result, error) -> {
            if (error != null) {
                status = "Error: " + error;
                return;
            }
            tickets.clear();
            JsonArray array = result.getAsJsonArray("tickets");
            array.forEach(t -> tickets.add(t.getAsJsonObject()));
            status = "";
            // Keep the selected ticket's header fresh (status may have changed elsewhere).
            if (selectedId != null) {
                for (JsonObject t : tickets) {
                    if (t.get("id").getAsString().equals(selectedId)) {
                        selectedTicket = t;
                    }
                }
            }
        });
        if (owner) {
            CarryApi.getInstance().adminCarriers((result, error) -> {
                if (error == null) {
                    carriers.clear();
                    result.getAsJsonArray("carriers").forEach(c -> carriers.add(c.getAsJsonObject()));
                }
            });
        }
    }

    private void select(JsonObject ticket) {
        selectedId = ticket.get("id").getAsString();
        selectedTicket = ticket;
        chat.clear();
        chatSince = 0;
        chatScroll = 0;
        pollGeneration++;
        pollLoop(pollGeneration);
    }

    /** One long-poll round; re-arms itself while this ticket stays selected. */
    private void pollLoop(int generation) {
        String id = selectedId;
        if (id == null || generation != pollGeneration) {
            return;
        }
        CarryApi.getInstance().poll(id, chatSince, (result, error) -> {
            if (generation != pollGeneration || !id.equals(selectedId)) {
                return;   // stale round - a different ticket is open now
            }
            if (error != null) {
                // Only give up on permanent errors; a timeout / hiccup RE-ARMS the loop so a
                // claimed ticket's chat keeps updating instead of silently dying on one bad poll.
                if (error.contains("no_such_ticket") || error.contains("not_yours")
                        || error.contains("ticket_deleted")) {
                    status = "Chat: " + error;
                    return;
                }
                pollLoop(generation);   // transient - the 12s connect timeout throttles a down server
                return;
            }
            selectedTicket = result.getAsJsonObject("ticket");
            chatSince = selectedTicket.get("chat_seq").getAsInt();
            result.getAsJsonArray("messages").forEach(m -> chat.add(m.getAsJsonObject()));
            pollLoop(generation);
        });
    }

    private List<JsonObject> ticketsFor(Tab which) {
        String needle = searchField.value.trim().toLowerCase(Locale.ROOT);
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject t : tickets) {
            String state = t.get("status").getAsString();
            boolean mine = t.get("mine").getAsBoolean();
            boolean claimedByMe = t.get("claimed_by_me").getAsBoolean();
            if (!needle.isEmpty() && !matchesSearch(t, needle)) {
                continue;
            }
            switch (which) {
                case OPEN -> {
                    if (state.equals("open")) {
                        out.add(t);
                    }
                }
                case MINE -> {
                    if (mine) {
                        out.add(t);
                    }
                }
                case CLAIMED -> {
                    if (state.equals("claimed") && (claimedByMe || owner || mine)) {
                        out.add(t);
                    }
                }
                default -> { }
            }
        }
        return out;
    }

    /** Free-text ticket match over id, category label, tier, note, creator and carrier. */
    private boolean matchesSearch(JsonObject t, String needle) {
        String category = t.get("category").getAsString();
        String haystack = ("#" + t.get("id").getAsString() + " "
                + categoryLabels.getOrDefault(category, category) + " " + category + " "
                + t.get("tier").getAsString() + " " + t.get("note").getAsString() + " "
                + t.get("creator_name").getAsString() + " " + t.get("carrier_ign").getAsString())
                .toLowerCase(Locale.ROOT);
        return haystack.contains(needle);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void createTicket() {
        List<String> keys = new ArrayList<>(categoryLabels.keySet());
        if (keys.isEmpty()) {
            return;
        }
        String category = keys.get(createCategory % keys.size());
        List<String> tiers = categoryTiers.get(category);
        String tier = tiers.get(createTier % tiers.size());
        CarryApi.getInstance().create(category, tier, noteField.value, (result, error) -> {
            status = error != null ? "Error: " + error : "Ticket created.";
            noteField.value = "";
            refreshTickets();
            if (error == null && result.has("ticket")) {
                tab = Tab.MINE;
                select(result.getAsJsonObject("ticket"));
            }
        });
    }

    private void sendChat() {
        String id = selectedId;
        String msg = chatField.value.trim();
        if (id == null || msg.isEmpty()) {
            return;
        }
        chatField.value = "";
        CarryApi.getInstance().chat(id, msg, (result, error) -> {
            if (error != null) {
                status = "Error: " + error;
            }
        });
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private List<Field> activeFields() {
        List<Field> fields = new ArrayList<>();
        if (tab == Tab.CARRIERS) {
            fields.add(adminToken);
            fields.add(adminIgn);
            fields.add(adminTiers);
        } else {
            fields.add(searchField);
            fields.add(noteField);
            if (selectedId != null) {
                fields.add(chatField);
            }
        }
        return fields;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        double mx = event.x();
        double my = event.y();
        for (Field field : activeFields()) {
            field.focused = field.hit(mx, my);
        }
        for (int i = 0; i < buttonRects.size(); i++) {
            int[] r = buttonRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                buttonActions.get(i).run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseX >= listX && mouseX < listX + listW) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(scrollY));
            return true;
        }
        if (scrollY != 0 && mouseX >= detailX) {
            chatScroll = Math.max(0, chatScroll + (int) Math.signum(scrollY) * 2);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        for (Field field : activeFields()) {
            if (!field.focused) {
                continue;
            }
            if (key == KEY_ESCAPE) {
                field.focused = false;
            } else if ((key == KEY_ENTER || key == KEY_NUMPAD_ENTER)) {
                if (field == chatField) {
                    sendChat();
                } else {
                    field.focused = false;
                }
            } else if (key == KEY_BACKSPACE && !field.value.isEmpty()) {
                field.value = field.value.substring(0, field.value.length() - 1);
            } else if (event.isPaste()) {
                String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                if (clip != null) {
                    field.value = (field.value + clip.trim());
                    if (field.value.length() > field.max) {
                        field.value = field.value.substring(0, field.max);
                    }
                }
            }
            return true;   // typing must never trigger inventory keys
        }
        if (key == KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        for (Field field : activeFields()) {
            if (field.focused) {
                if (event.isAllowedChatCharacter() && field.value.length() < field.max) {
                    field.value += event.codepointAsString();
                }
                return true;
            }
        }
        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        pollGeneration++;   // stops the chat poll loop
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                        String label, int mouseX, int mouseY, Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        g.centeredText(g == null ? font : font, Component.literal(label), x + w / 2,
                y + (h - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
        buttonRects.add(new int[]{x, y, w, h});
        buttonActions.add(action);
    }

    private void drawField(GuiGraphicsExtractor g, Font font, Field field,
                           int x, int y, int w, int h) {
        field.x = x;
        field.y = y;
        field.w = w;
        field.h = h;
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                SBSTheme.SEARCH_FILL, field.focused ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        String shown = field.value.isEmpty() && !field.focused ? "§8" + field.hint : field.value;
        // Show the tail when the text overflows - the caret sits at the end.
        String fitted = shown;
        while (font.width(fitted) > w - 8 && fitted.length() > 1) {
            fitted = fitted.substring(1);
        }
        g.text(font, Component.literal(fitted), x + 4, y + (h - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
        if (field.focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caretX = x + 4 + font.width(fitted);
            g.fill(caretX, y + 2, caretX + 1, y + h - 2, SBSTheme.ACCENT_BRIGHT);
        }
    }

    /** Manual word-wrap (no FormattedCharSequence dependency). */
    private static List<String> wrap(Font font, String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate) > width && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = CarryScreen.this.font;
            buttonRects.clear();
            buttonActions.clear();

            g.fill(0, 0, CarryScreen.this.width, CarryScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            String header = "SBS Carry Tickets" + (myCarrierIgn.isEmpty() ? ""
                    : "  §a✔ Carrier: " + myCarrierIgn) + (owner ? "  §6[OWNER]" : "");
            g.centeredText(font, Component.literal(header), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            int dividerY = panelY + SBSTheme.HEADER_HEIGHT;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            // Tabs.
            int tx = listX;
            int tabY = dividerY + 4;
            for (Tab t : Tab.values()) {
                if (t == Tab.CARRIERS && !owner) {
                    continue;
                }
                String label = (t == tab ? "§b" : "§7") + t.name();
                int w = font.width(label) + 12;
                Tab target = t;
                button(g, font, tx, tabY, w, SBSTheme.SEARCH_HEIGHT, label, mouseX, mouseY, () -> {
                    tab = target;
                    listScroll = 0;
                });
                tx += w + 4;
            }
            button(g, font, panelX + panelW - pad - 58, tabY, 58, SBSTheme.SEARCH_HEIGHT,
                    "Refresh", mouseX, mouseY, CarryScreen.this::refresh);

            if (tab == Tab.CARRIERS) {
                drawCarriersTab(g, font, mouseX, mouseY);
            } else {
                // Search row above the ticket list.
                drawField(g, font, searchField, listX, listTop - SBSTheme.SEARCH_HEIGHT - 4,
                        listW, SBSTheme.SEARCH_HEIGHT);
                drawTicketList(g, font, mouseX, mouseY);
                drawDetail(g, font, mouseX, mouseY);
                drawCreateRow(g, font, mouseX, mouseY);
            }
            if (!status.isEmpty()) {
                g.text(font, Component.literal("§7" + status), listX,
                        panelY + panelH - pad - font.lineHeight, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawTicketList(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            List<JsonObject> shown = ticketsFor(tab);
            int rowH = 26;
            int visible = Math.max(1, (listBottom - listTop) / rowH);
            listScroll = clamp(listScroll, 0, Math.max(0, shown.size() - visible));
            int y = listTop;
            for (int i = listScroll; i < shown.size() && i < listScroll + visible; i++) {
                JsonObject t = shown.get(i);
                boolean selected = t.get("id").getAsString().equals(selectedId);
                boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + rowH - 2;
                SciFiRender.roundedRectWithBorder(g, listX, y, listW, rowH - 2, SBSTheme.CORNER_RADIUS,
                        hover || selected ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                        selected ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
                String category = t.get("category").getAsString();
                String label = "#" + t.get("id").getAsString() + " §f"
                        + categoryLabels.getOrDefault(category, category)
                        + " §b" + t.get("tier").getAsString();
                g.text(font, Component.literal(label), listX + 4, y + 3, SBSTheme.TEXT);
                String state = t.get("status").getAsString();
                String sub = switch (state) {
                    case "claimed" -> "§a✔ " + t.get("carrier_ign").getAsString();
                    case "closed" -> "§8closed";
                    default -> "§7by " + t.get("creator_name").getAsString();
                };
                g.text(font, Component.literal(sub), listX + 4, y + 3 + font.lineHeight + 1,
                        SBSTheme.TEXT_MUTED);
                JsonObject target = t;
                buttonRects.add(new int[]{listX, y, listW, rowH - 2});
                buttonActions.add(() -> select(target));
                y += rowH;
            }
            if (shown.isEmpty()) {
                g.text(font, Component.literal("§8no tickets here"), listX, listTop, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawDetail(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            JsonObject t = selectedTicket;
            int x = detailX;
            int y = listTop;
            if (t == null) {
                g.text(font, Component.literal("§8select a ticket"), x, y, SBSTheme.TEXT_MUTED);
                return;
            }
            String state = t.get("status").getAsString();
            String category = t.get("category").getAsString();
            g.text(font, Component.literal("§f#" + t.get("id").getAsString() + " "
                    + categoryLabels.getOrDefault(category, category) + " §b" + t.get("tier").getAsString()
                    + "  §7" + state), x, y, SBSTheme.TEXT);
            y += font.lineHeight + 2;
            if (state.equals("claimed")) {
                g.text(font, Component.literal("§aCarrier (verified): ✔ "
                        + t.get("carrier_ign").getAsString()), x, y, SBSTheme.TEXT);
                y += font.lineHeight + 2;
            }
            // The server's anti-impersonation disclaimer - always visible over the chat.
            for (String line : wrap(font, disclaimer, detailW)) {
                g.text(font, Component.literal("§c" + line), x, y, SBSTheme.TEXT_MUTED);
                y += font.lineHeight;
            }
            y += 2;

            // Action buttons for this ticket.
            int bx = x;
            boolean mine = t.get("mine").getAsBoolean();
            boolean claimedByMe = t.get("claimed_by_me").getAsBoolean();
            String id = t.get("id").getAsString();
            if (state.equals("open") && (owner || (!myCarrierIgn.isEmpty() && !mine))) {
                button(g, font, bx, y, 50, SBSTheme.SEARCH_HEIGHT, "§aClaim", mouseX, mouseY,
                        () -> CarryApi.getInstance().claim(id, (r, e) -> {
                            status = e != null ? "Error: " + e : "Claimed.";
                            refreshTickets();
                        }));
                bx += 54;
            }
            if (state.equals("claimed") && (claimedByMe || owner)) {
                button(g, font, bx, y, 56, SBSTheme.SEARCH_HEIGHT, "Unclaim", mouseX, mouseY,
                        () -> CarryApi.getInstance().unclaim(id, (r, e) -> {
                            status = e != null ? "Error: " + e : "Unclaimed.";
                            refreshTickets();
                        }));
                bx += 60;
            }
            if (!state.equals("closed") && (mine || claimedByMe || owner)) {
                button(g, font, bx, y, 50, SBSTheme.SEARCH_HEIGHT, "§cClose", mouseX, mouseY,
                        () -> CarryApi.getInstance().close(id, (r, e) -> {
                            status = e != null ? "Error: " + e : "Closed.";
                            refreshTickets();
                        }));
            }
            y += SBSTheme.SEARCH_HEIGHT + 4;

            // Chat history (bottom-anchored, scrollable) + input.
            int chatBottom = listBottom;
            int inputH = SBSTheme.SEARCH_HEIGHT;
            int historyBottom = chatBottom - inputH - 4;
            List<String> lines = new ArrayList<>();
            for (JsonObject m : chat) {
                String from = m.get("from").getAsString();
                String tag = m.get("tag").getAsString();
                boolean verified = m.get("verified").getAsBoolean();
                String prefix = switch (from) {
                    case "CARRIER" -> "§a✔ " + tag + "§f: ";
                    case "OWNER" -> "§6[OWNER]§f: ";
                    case "SYSTEM" -> "§7» ";
                    default -> "§7" + tag + (verified ? "" : " §8(unverified)") + "§f: ";
                };
                for (String line : wrap(font, m.get("msg").getAsString(), detailW - 10)) {
                    lines.add(prefix.isEmpty() ? line : prefix + line);
                    prefix = "  ";   // continuation lines indent instead of repeating the tag
                }
            }
            int maxLines = Math.max(1, (historyBottom - y) / (font.lineHeight + 1));
            chatScroll = clamp(chatScroll, 0, Math.max(0, lines.size() - maxLines));
            int first = Math.max(0, lines.size() - maxLines - chatScroll);
            int ly = y;
            for (int i = first; i < lines.size() - chatScroll && ly + font.lineHeight <= historyBottom; i++) {
                g.text(font, Component.literal(lines.get(i)), x, ly, SBSTheme.TEXT);
                ly += font.lineHeight + 1;
            }
            drawField(g, font, chatField, x, chatBottom - inputH, detailW - 46, inputH);
            button(g, font, x + detailW - 42, chatBottom - inputH, 42, inputH, "Send",
                    mouseX, mouseY, CarryScreen.this::sendChat);
        }

        private void drawCreateRow(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            List<String> keys = new ArrayList<>(categoryLabels.keySet());
            if (keys.isEmpty()) {
                return;
            }
            int y = panelY + panelH - SBSTheme.PANEL_PADDING - SBSTheme.SEARCH_HEIGHT * 2 - 4;
            String category = keys.get(createCategory % keys.size());
            List<String> tiers = categoryTiers.get(category);
            String tier = tiers.get(createTier % tiers.size());

            int x = listX;
            button(g, font, x, y, 108, SBSTheme.SEARCH_HEIGHT,
                    categoryLabels.get(category), mouseX, mouseY, () -> {
                        createCategory++;
                        createTier = 0;
                    });
            x += 112;
            button(g, font, x, y, 62, SBSTheme.SEARCH_HEIGHT, "§b" + tier, mouseX, mouseY,
                    () -> createTier++);
            x += 66;
            int noteW = panelX + panelW - SBSTheme.PANEL_PADDING - x - 62;
            drawField(g, font, noteField, x, y, Math.max(60, noteW), SBSTheme.SEARCH_HEIGHT);
            button(g, font, panelX + panelW - SBSTheme.PANEL_PADDING - 58, y, 58,
                    SBSTheme.SEARCH_HEIGHT, "§aCreate", mouseX, mouseY, CarryScreen.this::createTicket);
        }

        private void drawCarriersTab(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
            // Left: the current carriers with their roles; right: the assignment form.
            int rowH = 24;
            int y = listTop;
            for (JsonObject c : carriers) {
                if (y + rowH > listBottom) {
                    break;
                }
                SciFiRender.roundedRectWithBorder(g, listX, y, listW, rowH - 2, SBSTheme.CORNER_RADIUS,
                        SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
                StringBuilder roles = new StringBuilder();
                JsonObject roleMap = c.getAsJsonObject("roles");
                for (String key : roleMap.keySet()) {
                    JsonArray tiers = roleMap.getAsJsonArray(key);
                    roles.append(key).append("(").append(tiers.size()).append(") ");
                }
                g.text(font, Component.literal("§a✔ " + c.get("ign").getAsString()), listX + 4, y + 3,
                        SBSTheme.TEXT);
                g.text(font, Component.literal("§8" + roles), listX + 4, y + 3 + font.lineHeight,
                        SBSTheme.TEXT_MUTED);
                String ign = c.get("ign").getAsString();
                button(g, font, listX + listW - 46, y + 3, 42, rowH - 8, "§cRemove", mouseX, mouseY,
                        () -> CarryApi.getInstance().adminRemoveCarrier(ign, (r, e) -> {
                            status = e != null ? "Error: " + e : "Removed " + ign;
                            refreshTickets();
                        }));
                y += rowH;
            }
            if (carriers.isEmpty()) {
                g.text(font, Component.literal("§8no carriers yet"), listX, listTop, SBSTheme.TEXT_MUTED);
            }

            // Assignment form (right column).
            int x = detailX;
            int fy = listTop;
            g.text(font, Component.literal("§fAssign carrier role"), x, fy, SBSTheme.TEXT);
            fy += font.lineHeight + 4;
            drawField(g, font, adminToken, x, fy, detailW, SBSTheme.SEARCH_HEIGHT);
            fy += SBSTheme.SEARCH_HEIGHT + 4;
            drawField(g, font, adminIgn, x, fy, detailW, SBSTheme.SEARCH_HEIGHT);
            fy += SBSTheme.SEARCH_HEIGHT + 4;
            List<String> keys = new ArrayList<>(categoryLabels.keySet());
            if (!keys.isEmpty()) {
                String category = keys.get(adminCategory % keys.size());
                button(g, font, x, fy, 120, SBSTheme.SEARCH_HEIGHT, categoryLabels.get(category),
                        mouseX, mouseY, () -> adminCategory++);
                drawField(g, font, adminTiers, x + 124, fy, detailW - 124, SBSTheme.SEARCH_HEIGHT);
                fy += SBSTheme.SEARCH_HEIGHT + 6;
                button(g, font, x, fy, 100, SBSTheme.SEARCH_HEIGHT, "§aSet Carrier", mouseX, mouseY, () -> {
                    String target = adminToken.value.trim();
                    String ign = adminIgn.value.trim();
                    CarryApi.getInstance().adminSetCarrier(target, ign, category,
                            adminTiers.value, (r, e) -> {
                                status = e != null ? "Error: " + e : "Carrier set: " + ign;
                                adminToken.value = "";
                                refreshTickets();
                            });
                });
                fy += SBSTheme.SEARCH_HEIGHT + 8;
            }
            for (String line : wrap(font, "The IGN is PINNED server-side to this token and becomes "
                    + "the verified chat tag - the mod cannot fake it. Tiers: 'all' or a comma list "
                    + "(e.g. F5,F6,F7). Repeat per category to grant more.", detailW)) {
                g.text(font, Component.literal("§8" + line), x, fy, SBSTheme.TEXT_MUTED);
                fy += font.lineHeight;
            }
        }
    }
}
