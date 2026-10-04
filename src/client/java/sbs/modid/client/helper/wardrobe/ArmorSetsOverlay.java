/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.wardrobe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SaveThrottle;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SBS Wardrobe: one grid over Hypixel's {@code (N/M) Armor Sets} menu - every set of every page at
 * once, nine to a row, each on an armour stand, the four pieces beside it when the cell has room.
 *
 * <p><b>Data</b>: each open page is read through {@link ArmorSetsPage} (pieces from rows 0-3, the set
 * number and status from the dye in row 4) and kept per profile in {@code armor_sets_cache.json}
 * with the time each page was seen, so pages not opened this session still show, marked by age.
 *
 * <p><b>Interaction</b>: a click on a set on the open page is one click on that set's dye in the real
 * menu (equip, or unequip on the worn set). A click on a set on another page is one click on the page
 * arrow towards it, and a hint asks for a second click once the page is there - never chained. This
 * is the same rule the Loadouts overlay's card click follows.
 *
 * <p>Plain grid only: the overlay covers {@link ContainerScreen} (a chest menu) and nothing else, per
 * the container reskin allowlist.
 */
public final class ArmorSetsOverlay implements sbs.modid.client.core.config.ProfileScopedStore {

    private static final ArmorSetsOverlay INSTANCE = new ArmorSetsOverlay();

    private static final int CACHE_VERSION = 1;
    private static final long HINT_MS = 6_000L;
    /** Largest cell; the grid shrinks from here to fit the viewport. */
    private static final int MAX_CELL_W = 72;
    private static final int MAX_CELL_H = 96;
    private static final int PIECE = 17;

    /** One cached set. Pieces are copies of the menu's own stacks (dyes, skins and all). */
    private static final class SetEntry {
        final ItemStack[] pieces = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
        ArmorSetsPage.Status status = ArmorSetsPage.Status.UNKNOWN;
        /** Raw JSON of pieces that did not decode yet; written back unchanged so nothing is lost. */
        final com.google.gson.JsonElement[] raw = new com.google.gson.JsonElement[4];

        boolean hasArmor() {
            for (ItemStack piece : pieces) {
                if (!piece.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Map<Integer, SetEntry> sets = new ConcurrentHashMap<>();
    /** When each page was last read, epoch ms. Persisted. */
    private final Map<Integer, Long> pageSeenAt = new ConcurrentHashMap<>();
    /** Pages read since the game started - the others are drawn as cached. */
    private final Set<Integer> pagesThisSession = ConcurrentHashMap.newKeySet();
    private volatile int pages;

    private final SaveThrottle saveThrottle = new SaveThrottle();
    private boolean cacheLoaded;

    /** "Show Hypixel menu": the overlay stands aside until no Armor Sets page is open any more. */
    private boolean editMode;

    /** After a page-arrow click: which set was asked for, until when the hint shows. */
    private int hintSet = -1;
    private long hintUntil;

    private ArmorStand stand;

    private ArmorSetsOverlay() {
        sbs.modid.client.core.config.ProfileContext.getInstance().register(this);
    }

    public static ArmorSetsOverlay getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().skyblockMenu.sbsWardrobeView;
    }

    private static String title(AbstractContainerScreen<?> screen) {
        return screen.getTitle() == null ? "" : PlainText.strip(screen.getTitle().getString()).trim();
    }

    /** The feature's own screen test, on a normalised (stripped) title. */
    public static boolean isArmorSetsMenu(String title) {
        return ArmorSetsPage.isArmorSets(title);
    }

    /** True while the overlay covers the menu and takes its input. */
    public boolean isActive(AbstractContainerScreen<?> screen) {
        return enabled() && !editMode && screen instanceof ContainerScreen && isArmorSetsMenu(title(screen));
    }

    /** Ends "Show Hypixel menu" once no Armor Sets page is open (a server refresh keeps it on). */
    public void onClientTick() {
        if (!editMode) {
            return;
        }
        Screen screen = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container && isArmorSetsMenu(title(container)))) {
            editMode = false;
        }
    }

    // ------------------------------------------------------------------
    // Reading the open page
    // ------------------------------------------------------------------

    private void readPage(AbstractContainerScreen<?> screen, int page, int pageCount) {
        loadCache();
        AbstractContainerMenu menu = screen.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        List<ArmorSetsPage.SlotText> text = new ArrayList<>(upper);
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            text.add(i < ArmorSetsPage.DYE_ROW ? null : slotText(stack));
        }
        List<ArmorSetsPage.Column> columns = ArmorSetsPage.columns(text);
        if (columns.isEmpty()) {
            return;   // the dye row has not arrived yet
        }
        boolean changed = pages != pageCount;
        pages = pageCount;
        boolean equippedHere = false;
        for (ArmorSetsPage.Column column : columns) {
            SetEntry entry = sets.computeIfAbsent(column.setNumber(), n -> new SetEntry());
            if (entry.status != column.status()) {
                entry.status = column.status();
                changed = true;
            }
            equippedHere |= column.status() == ArmorSetsPage.Status.EQUIPPED;
            for (int p = 0; p < 4; p++) {
                ItemStack piece = menu.getSlot(column.pieceSlot(p)).getItem();
                piece = piece == null ? ItemStack.EMPTY : piece;
                if (!ItemStack.isSameItemSameComponents(piece, entry.pieces[p])) {
                    entry.pieces[p] = piece.copy();
                    entry.raw[p] = null;
                    changed = true;
                }
            }
        }
        if (equippedHere) {
            // One set is worn at a time: a cached "equipped" on another page is from before.
            for (var e : sets.entrySet()) {
                if (ArmorSetsPage.pageOf(e.getKey()) != page && e.getValue().status == ArmorSetsPage.Status.EQUIPPED) {
                    e.getValue().status = ArmorSetsPage.Status.READY;
                    changed = true;
                }
            }
        }
        if (pagesThisSession.add(page)) {
            changed = true;   // first read this session: persist the new "seen" time
        }
        pageSeenAt.put(page, System.currentTimeMillis());
        if (changed) {
            saveCache(true);
        } else if (saveThrottle.pending(System.currentTimeMillis()) && writeCache()) {
            saveThrottle.written(System.currentTimeMillis());
        }
    }

    private static ArmorSetsPage.SlotText slotText(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new ArmorSetsPage.SlotText("", "", List.of());
        }
        List<String> lore = new ArrayList<>();
        var component = stack.get(DataComponents.LORE);
        if (component != null) {
            for (Component line : component.lines()) {
                lore.add(PlainText.strip(line.getString()));
            }
        }
        return new ArmorSetsPage.SlotText(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                PlainText.strip(stack.getHoverName().getString()), lore);
    }

    // ------------------------------------------------------------------
    // Rendering (top-most, from ScreenForeground)
    // ------------------------------------------------------------------

    private int gridLeft;
    private int gridTop;
    private int cellW;
    private int cellH;
    private int buttonsX;
    private int buttonsY;
    private int buttonW;
    private int buttonGap;
    private String[] buttonLabels = {"Back", "Close", "Show Hypixel menu"};
    private List<Component> tooltip;

    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!enabled() || !(screen instanceof ContainerScreen)) {
            return;
        }
        int[] page = ArmorSetsPage.parseTitle(title(screen));
        if (page == null) {
            return;
        }
        readPage(screen, page[0], page[1]);
        if (editMode || sets.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        g.nextStratum();   // above the container and its tooltips
        tooltip = null;

        int rows = Math.max(1, Math.max(pages, highestSet() == 0 ? 1 : ArmorSetsPage.pageOf(highestSet())));
        int panelW = Math.min(Math.max(1, screen.width - 8), ArmorSetsPage.COLUMNS * MAX_CELL_W + 16);
        int panelH = Math.max(1, screen.height - 8);
        int x = (screen.width - panelW) / 2;
        int y = 4;
        HudCard.draw(g, x, y, panelW, panelH, SBSTheme.PANEL_CORNER);

        // Header + one hint line, both measured and cut to the panel.
        String header = "SBS Wardrobe  •  Page " + page[0] + "/" + page[1] + " open";
        if (font.width(header) > panelW - 16) {
            header = "Page " + page[0] + "/" + page[1];
        }
        g.centeredText(font, Component.literal(header), x + panelW / 2,
                y + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2, SBSTheme.ACCENT_BRIGHT);
        int hintY = y + SBSTheme.HEADER_HEIGHT;
        String hint = hintText(page[0]);
        if (hint != null) {
            g.centeredText(font, Component.literal(font.plainSubstrByWidth(hint, panelW - 16, false)),
                    x + panelW / 2, hintY, 0xFFFFD866);
        }

        // Footer buttons, sized from their labels; the long one shortens before anything overlaps.
        buttonGap = 6;
        buttonLabels = new String[] {"Back", "Close", "Show Hypixel menu"};
        buttonW = buttonWidth(font, buttonLabels);
        if (buttonW * 3 + buttonGap * 2 > panelW - 16) {
            buttonLabels[2] = "Hypixel menu";
            buttonW = Math.min(buttonWidth(font, buttonLabels), (panelW - 16 - buttonGap * 2) / 3);
        }
        buttonsY = y + panelH - SBSTheme.SEARCH_HEIGHT - 6;
        buttonsX = x + (panelW - (buttonW * 3 + buttonGap * 2)) / 2;
        for (int i = 0; i < 3; i++) {
            int bx = buttonsX + i * (buttonW + buttonGap);
            boolean over = mouseX >= bx && mouseX < bx + buttonW && mouseY >= buttonsY
                    && mouseY < buttonsY + SBSTheme.SEARCH_HEIGHT;
            drawButton(g, font, bx, buttonsY, buttonW, buttonLabels[i], over);
        }

        // Grid: one row per page, nine cells; sized from what is left.
        int top = hintY + font.lineHeight + 3;
        int bottom = buttonsY - 4;
        cellW = Math.max(20, Math.min(MAX_CELL_W, (panelW - 16) / ArmorSetsPage.COLUMNS));
        cellH = Math.max(20, Math.min(MAX_CELL_H, (bottom - top) / rows));
        gridLeft = x + (panelW - cellW * ArmorSetsPage.COLUMNS) / 2;
        gridTop = top + Math.max(0, (bottom - top - cellH * rows) / 2);

        ensureStand(minecraft);
        long now = System.currentTimeMillis();
        for (int n = 1; n <= rows * ArmorSetsPage.COLUMNS; n++) {
            int cx = gridLeft + ((n - 1) % ArmorSetsPage.COLUMNS) * cellW;
            int cy = gridTop + ((n - 1) / ArmorSetsPage.COLUMNS) * cellH;
            drawCell(g, font, n, sets.get(n), page[0], cx, cy, mouseX, mouseY, now);
        }

        if (tooltip != null) {
            List<ClientTooltipComponent> parts = new ArrayList<>(tooltip.size());
            for (Component line : tooltip) {
                parts.add(ClientTooltipComponent.create(line.getVisualOrderText()));
            }
            g.tooltip(font, parts, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, SBSTheme.tooltipStyle());
        }
    }

    private void drawCell(GuiGraphicsExtractor g, Font font, int n, SetEntry entry, int openPage,
                          int cx, int cy, int mouseX, int mouseY, long now) {
        ArmorSetsPage.Status status = entry == null ? ArmorSetsPage.Status.UNKNOWN : entry.status;
        int page = ArmorSetsPage.pageOf(n);
        boolean equipped = status == ArmorSetsPage.Status.EQUIPPED;
        boolean clickable = ArmorSetsPage.clickable(status);
        boolean stale = !pagesThisSession.contains(page);
        boolean hover = mouseX >= cx && mouseX < cx + cellW && mouseY >= cy && mouseY < cy + cellH;

        int bg = equipped ? 0x8033AA44
                : !clickable ? SBSTheme.CARD_BG_DISABLED
                : hover ? SBSTheme.CARD_BG_HOVER : 0xA0102640;
        int border = equipped ? 0xFF55FF55 : hover && clickable ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
        SciFiRender.roundedRectWithBorder(g, cx + 1, cy + 1, cellW - 2, cellH - 2, SBSTheme.CORNER_RADIUS, bg, border);
        // The worn set is bracketed as well as green: state is never carried by colour alone.
        String number = equipped ? "[" + n + "]" : String.valueOf(n);
        g.text(font, Component.literal(number), cx + 3, cy + 3,
                equipped ? 0xFF55FF55 : page == openPage ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);

        boolean showPieces = cellH >= PIECE * 4 + 6 && cellW >= PIECE + 26;
        if (entry != null && entry.hasArmor()) {
            int standRight = showPieces ? cx + cellW - PIECE - 4 : cx + cellW - 2;
            if (stand != null) {
                dress(entry);
                int scale = Math.max(6, (cellH - 12) * 22 / 54);
                InventoryScreen.extractEntityInInventoryFollowsMouse(g, cx + 2, cy + 10, standRight, cy + cellH - 2,
                        scale, 0.0625F, mouseX, mouseY, stand);
            }
            if (showPieces) {
                int px = cx + cellW - PIECE - 3;
                int py = cy + (cellH - PIECE * 4) / 2;
                for (int p = 0; p < 4; p++) {
                    int by = py + p * PIECE;
                    boolean over = mouseX >= px && mouseX < px + PIECE && mouseY >= by && mouseY < by + PIECE;
                    SciFiRender.roundedRectWithBorder(g, px, by, PIECE - 1, PIECE - 1, 2, SBSTheme.CARD_BG,
                            over ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
                    if (!entry.pieces[p].isEmpty()) {
                        g.item(entry.pieces[p], px, by);
                        if (over) {
                            g.fill(px, by, px + 16, by + 16, 0x80FFFFFF);
                            tooltip = stackTooltip(entry.pieces[p]);
                        }
                    }
                }
            }
        } else {
            String word = switch (status) {
                case EMPTY -> "empty";
                case LOCKED -> "locked";
                default -> entry == null ? "?" : "";
            };
            g.centeredText(font, Component.literal("§8" + font.plainSubstrByWidth(word, cellW - 4, false)),
                    cx + cellW / 2, cy + cellH / 2 - font.lineHeight / 2, SBSTheme.TEXT_MUTED);
        }
        if (hover && tooltip == null) {
            tooltip = cellTooltip(n, entry, status, page, openPage, stale, now);
        }
    }

    private List<Component> cellTooltip(int n, SetEntry entry, ArmorSetsPage.Status status, int page,
                                        int openPage, boolean stale, long now) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal("§bSet " + n));
        if (entry != null) {
            for (ItemStack piece : entry.pieces) {
                if (!piece.isEmpty()) {
                    tip.add(piece.getHoverName());
                }
            }
        }
        switch (status) {
            case EQUIPPED -> tip.add(Component.literal(page == openPage ? "§aWearing this set - click to unequip"
                    : "§aWearing this set"));
            case READY -> tip.add(Component.literal(page == openPage ? "§eClick to equip"
                    : "§7On page " + page + " - click to turn the menu there, then click again"));
            case EMPTY -> tip.add(Component.literal("§8Empty"));
            case LOCKED -> tip.add(Component.literal("§8Locked"));
            default -> tip.add(Component.literal("§8Not seen yet - open page " + page));
        }
        Long seen = pageSeenAt.get(page);
        if (stale && seen != null) {
            tip.add(Component.literal("§8Seen " + age(now - seen) + " ago"));
        }
        return tip;
    }

    private String hintText(int openPage) {
        if (hintSet < 0 || System.currentTimeMillis() > hintUntil) {
            hintSet = -1;
            return null;
        }
        return ArmorSetsPage.pageOf(hintSet) == openPage
                ? "Page " + openPage + " is open - click set " + hintSet + " again to equip it"
                : "Turning to page " + ArmorSetsPage.pageOf(hintSet) + "...";
    }

    private static int buttonWidth(Font font, String[] labels) {
        int w = 50;
        for (String label : labels) {
            w = Math.max(w, font.width(label) + 12);
        }
        return w;
    }

    private static void drawButton(GuiGraphicsExtractor g, Font font, int x, int y, int w, String label, boolean hover) {
        SciFiRender.roundedRectWithBorder(g, x, y, w, SBSTheme.SEARCH_HEIGHT, SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        g.centeredText(font, Component.literal(font.plainSubstrByWidth(label, w - 6, false)), x + w / 2,
                y + (SBSTheme.SEARCH_HEIGHT - font.lineHeight) / 2, SBSTheme.TEXT);
    }

    private static List<Component> stackTooltip(ItemStack stack) {
        List<Component> tip = new ArrayList<>();
        tip.add(stack.getHoverName());
        var lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            tip.addAll(lore.lines());
        }
        return tip;
    }

    static String age(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) {
            return s + "s";
        }
        long m = s / 60;
        if (m < 60) {
            return m + "m";
        }
        long h = m / 60;
        return h < 48 ? h + "h" : (h / 24) + "d";
    }

    private int highestSet() {
        int highest = 0;
        for (int n : sets.keySet()) {
            highest = Math.max(highest, n);
        }
        return highest;
    }

    /** Client-only fake entity id: the renderer reads one, and a never-spawned stand has none. */
    private static final int PREVIEW_STAND_ID = sbs.modid.client.helper.loadouts.PreviewEntities.ARMOR_STAND_ID;

    private void ensureStand(Minecraft minecraft) {
        if (minecraft.level == null) {
            stand = null;
            return;
        }
        if (stand == null || stand.level() != minecraft.level) {
            stand = new ArmorStand(minecraft.level, 0, 0, 0);
            stand.setId(PREVIEW_STAND_ID);
        }
    }

    private void dress(SetEntry entry) {
        stand.setItemSlot(EquipmentSlot.HEAD, entry.pieces[0]);
        stand.setItemSlot(EquipmentSlot.CHEST, entry.pieces[1]);
        stand.setItemSlot(EquipmentSlot.LEGS, entry.pieces[2]);
        stand.setItemSlot(EquipmentSlot.FEET, entry.pieces[3]);
    }

    // ------------------------------------------------------------------
    // Input (from ContainerSearchBarMixin, before the container sees it)
    // ------------------------------------------------------------------

    public boolean handleScroll(AbstractContainerScreen<?> screen) {
        return isActive(screen);   // nothing scrolls; the wheel must not reach the menu underneath
    }

    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!isActive(screen) || sets.isEmpty()) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (my >= buttonsY && my < buttonsY + SBSTheme.SEARCH_HEIGHT) {
            for (int i = 0; i < 3; i++) {
                int bx = buttonsX + i * (buttonW + buttonGap);
                if (mx >= bx && mx < bx + buttonW) {
                    switch (i) {
                        case 0 -> clickSlotNamed(screen, "go back");
                        case 1 -> Minecraft.getInstance().setScreenAndShow(null);
                        default -> editMode = true;
                    }
                    return true;
                }
            }
        }
        if (event.button() != 0 || cellW <= 0 || cellH <= 0 || mx < gridLeft || my < gridTop) {
            return true;
        }
        int col = (int) ((mx - gridLeft) / cellW);
        int row = (int) ((my - gridTop) / cellH);
        int rows = Math.max(1, pages);
        if (col >= ArmorSetsPage.COLUMNS || row >= rows) {
            return true;
        }
        int n = row * ArmorSetsPage.COLUMNS + col + 1;
        SetEntry entry = sets.get(n);
        if (entry == null || !ArmorSetsPage.clickable(entry.status)) {
            return true;
        }
        int[] page = ArmorSetsPage.parseTitle(title(screen));
        int open = page == null ? 1 : page[0];
        int target = ArmorSetsPage.pageOf(n);
        if (target == open) {
            clickMenuSlot(screen, ArmorSetsPage.DYE_ROW + (n - 1) % ArmorSetsPage.COLUMNS);
            hintSet = -1;
        } else {
            // One step towards the set, and a hint for the second click - never a chained hop.
            clickSlotNamed(screen, target > open ? "next page" : "previous page");
            hintSet = n;
            hintUntil = System.currentTimeMillis() + HINT_MS;
        }
        return true;
    }

    private static void clickSlotNamed(AbstractContainerScreen<?> screen, String fragment) {
        AbstractContainerMenu menu = screen.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack != null && !stack.isEmpty()
                    && PlainText.strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT).contains(fragment)) {
                clickMenuSlot(screen, i);
                return;
            }
        }
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Wardrobe] no '{}' slot on this page", fragment);
    }

    private static void clickMenuSlot(AbstractContainerScreen<?> screen, int slot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode != null && minecraft.player != null) {
            minecraft.gameMode.handleContainerInput(screen.getMenu().containerId, slot, 0,
                    ContainerInput.PICKUP, minecraft.player);
        }
    }

    // ------------------------------------------------------------------
    // Profile-scoped cache
    // ------------------------------------------------------------------

    @Override
    public void flushProfile() {
        writeCache();
    }

    @Override
    public void reloadProfile() {
        synchronized (this) {
            sets.clear();
            pageSeenAt.clear();
            pagesThisSession.clear();
            pages = 0;
            hintSet = -1;
            cacheLoaded = false;
        }
        loadCache();
    }

    private void saveCache(boolean oneShot) {
        long now = System.currentTimeMillis();
        if (saveThrottle.request(oneShot, now) && writeCache()) {
            saveThrottle.written(now);
        }
    }

    private static com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops() {
        var level = Minecraft.getInstance().level;
        return level == null ? null
                : net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,
                        level.registryAccess());
    }

    /** Writes the cache; false when it cannot yet (no world, or the file has not been read). */
    private synchronized boolean writeCache() {
        var ops = ops();
        if (ops == null || !cacheLoaded) {
            return false;   // a write before the read would replace the file with what little is in memory
        }
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty("_version", CACHE_VERSION);
        root.addProperty("pages", pages);
        com.google.gson.JsonObject seen = new com.google.gson.JsonObject();
        pageSeenAt.forEach((p, t) -> seen.addProperty(String.valueOf(p), t));
        root.add("seen", seen);
        com.google.gson.JsonObject setsJson = new com.google.gson.JsonObject();
        int failures = 0;
        for (var e : sets.entrySet()) {
            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("status", e.getValue().status.name());
            com.google.gson.JsonArray pieces = new com.google.gson.JsonArray();
            for (int p = 0; p < 4; p++) {
                ItemStack piece = e.getValue().pieces[p];
                com.google.gson.JsonElement encoded = com.google.gson.JsonNull.INSTANCE;
                if (!piece.isEmpty()) {
                    var result = ItemStack.CODEC.encodeStart(ops, piece).result();
                    if (result.isPresent()) {
                        encoded = result.get();
                    } else {
                        failures++;
                    }
                }
                if (encoded.isJsonNull() && e.getValue().raw[p] != null) {
                    encoded = e.getValue().raw[p];   // never write null over data that did not decode
                }
                pieces.add(encoded);
            }
            json.add("pieces", pieces);
            setsJson.add(String.valueOf(e.getKey()), json);
        }
        root.add("sets", setsJson);
        if (failures > 0) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Wardrobe] {} piece(s) failed to encode", failures);
        }
        java.nio.file.Path path = sbs.modid.client.core.config.SBSFiles.armorSetsCacheFile();
        sbs.modid.client.core.async.SbsExecutors.io().execute(() -> {
            try {
                sbs.modid.client.core.config.SBSFiles.ensureParent(path);
                try (var writer = java.nio.file.Files.newBufferedWriter(path)) {
                    sbs.modid.client.core.config.SBSFiles.GSON.toJson(root, writer);
                }
                sbs.modid.client.core.perf.Perf.countDiskWrite();
            } catch (Exception ex) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Wardrobe] cache write failed: {}", ex.toString());
            }
        });
        return true;
    }

    /** Reads the cache once a world (and its registries) exists. */
    private synchronized void loadCache() {
        if (cacheLoaded) {
            return;
        }
        var ops = ops();
        if (ops == null) {
            return;
        }
        cacheLoaded = true;
        java.nio.file.Path path = sbs.modid.client.core.config.SBSFiles.armorSetsCacheFile();
        if (!java.nio.file.Files.exists(path)) {
            return;
        }
        int undecoded = 0;
        try (var reader = java.nio.file.Files.newBufferedReader(path)) {
            com.google.gson.JsonObject root = sbs.modid.client.core.config.SBSFiles.GSON.fromJson(reader,
                    com.google.gson.JsonObject.class);
            if (root == null || !root.has("_version") || root.get("_version").getAsInt() > CACHE_VERSION) {
                return;   // empty, or written by a newer build this one cannot read
            }
            pages = root.has("pages") ? root.get("pages").getAsInt() : 0;
            if (root.has("seen")) {
                for (var e : root.getAsJsonObject("seen").entrySet()) {
                    pageSeenAt.put(Integer.parseInt(e.getKey()), e.getValue().getAsLong());
                }
            }
            if (root.has("sets")) {
                for (var e : root.getAsJsonObject("sets").entrySet()) {
                    com.google.gson.JsonObject json = e.getValue().getAsJsonObject();
                    SetEntry entry = new SetEntry();
                    try {
                        entry.status = ArmorSetsPage.Status.valueOf(json.get("status").getAsString());
                    } catch (IllegalArgumentException | NullPointerException bad) {
                        entry.status = ArmorSetsPage.Status.UNKNOWN;
                    }
                    var pieces = json.getAsJsonArray("pieces");
                    for (int p = 0; p < 4 && pieces != null && p < pieces.size(); p++) {
                        var pj = pieces.get(p);
                        if (pj == null || pj.isJsonNull()) {
                            continue;
                        }
                        var decoded = ItemStack.CODEC.parse(ops, pj).result();
                        if (decoded.isPresent() && !decoded.get().isEmpty()) {
                            entry.pieces[p] = decoded.get();
                        } else {
                            entry.raw[p] = pj;
                            undecoded++;
                        }
                    }
                    sets.put(Integer.parseInt(e.getKey()), entry);
                }
            }
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Wardrobe] loaded {} set(s) over {} page(s){}",
                    sets.size(), pages, undecoded == 0 ? "" : ", " + undecoded + " piece(s) not decoded");
        } catch (Exception ex) {
            // A corrupt cache is expected now and then; the next menu visit rebuilds it.
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Wardrobe] cache read failed: {}", ex.toString());
        }
    }
}
