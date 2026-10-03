/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.pets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.core.item.Rarity;
import sbs.modid.client.helper.loadouts.LoadoutsOverlay;
import sbs.modid.client.ui.render.MenuFrame;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SBS Pets: one card grid over Hypixel's Pets menu ("(1/3) Pets") holding <b>every page at once</b>.
 *
 * <p>The menu paginates, so finding a pet means flipping pages and reading tiny heads. This scans
 * each page you visit and keeps it, so after one pass through the pages the whole collection is on
 * screen in one grid: head icon, name, level, rarity colour, held item and the page it lives on.
 *
 * <p><b>Interaction</b> mirrors {@link LoadoutsOverlay} exactly, including the deliberate two-step
 * page flip: clicking a card whose page is open clicks that pet's real menu slot (summon / despawn);
 * clicking a card on another page clicks the page arrow one step towards it, and you click the card
 * again once the page has flipped. Never auto-chained – a click always maps to exactly one real
 * click, so nothing runs away with your mouse.
 *
 * <p>The cache is per session (it exists to survive page flips and reopening the menu, and a page is
 * re-read the moment you visit it again), so a levelled-up or newly bought pet is never shown stale
 * for longer than one visit.
 */
public final class PetsOverlay {

    private static final PetsOverlay INSTANCE = new PetsOverlay();

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);
    private static final Pattern PAGE = Pattern.compile("\\(([0-9]+)/([0-9]+)\\)");
    /** "[Lvl 100] Golden Dragon" – the level lives in the pet's own display name. */
    private static final Pattern PET_LEVEL = Pattern.compile("\\[Lvl\\s*([0-9]+)]");

    /** Cards per row in our grid. */
    private static final int GRID_COLS = 6;

    /** Rarity → the colour its card border and name get, keyed by the lore's rarity word. */
    private static final String[] RARITY_WORDS =
            {"MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON"};
    private static final int[] RARITY_COLORS =
            {0xFFFF55FF, 0xFFFFAA00, 0xFFAA00AA, 0xFF5555FF, 0xFF55FF55, 0xFFFFFFFF};

    /** One cached pet, as last seen in the menu. */
    private static final class Pet {
        ItemStack stack = ItemStack.EMPTY;
        String name = "";
        int level;
        int rarity = RARITY_WORDS.length - 1;
        String heldItem;
        boolean active;
        int page;
        int menuSlot = -1;
    }

    /** Signature ("name|rarity|#n") -> pet. Insertion-ordered so the grid keeps the menu's order. */
    private final Map<String, Pet> pets = new LinkedHashMap<>();

    /** Cards, rebuilt from {@link #pets} every frame (cheap: a few dozen entries). */
    private List<Pet> ordered = List.of();

    /**
     * "Edit" mode: the overlay stands aside so Hypixel's own menu can be used, until the player
     * leaves the Pets menu. Cleared in {@link #onClientTick()} and nowhere else - see the note
     * there for why the obvious place is wrong.
     */
    private boolean editMode;

    /** Rescan throttle (see {@link #cachePage}). */
    private static final long SCAN_INTERVAL_MS = 150L;
    private long lastScanAt;
    private Object lastScanScreen;

    // Layout, written by the renderer and read by the click handler (same frame ordering as the
    // loadouts overlay: render always runs before the click that follows it).
    private int gridLeft;
    private int gridTop;
    private int gridViewBottom;
    private int cardW;
    private int cardH;
    private int buttonsX;
    private int buttonsY;
    private int buttonW;
    private int buttonGap;
    private int maxScroll;
    private int scroll;

    private PetsOverlay() {
    }

    public static PetsOverlay getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().skyblockMenu.sbsPets;
    }

    private static String title(AbstractContainerScreen<?> screen) {
        // PlainText rather than a replaceAll: this is on the container draw path, and String's
        // replaceAll recompiles its pattern on every call.
        return PlainText.strip(MenuFrame.of(screen).title()).trim();
    }

    /**
     * The Pets menu carries its page counter FIRST: "(1/3) Pets" - which is why the marker has to
     * come out before the name can be read, and why a plain "starts with pets" test on the raw
     * title would answer no on every page.
     *
     * <p>Public because it is also this overlay's {@link sbs.modid.client.ui.render.RenderTier}: the
     * container overlay hook asks it, from the frame's cached title, before running the pass at all.
     * Takes the title in any form - it strips, trims and lowercases what it is given.
     */
    public static boolean isPetsMenu(String title) {
        return title.replaceAll("\\([0-9]+/[0-9]+\\)", "").trim()
                .toLowerCase(Locale.ROOT).startsWith("pets");
    }

    public boolean isActive(AbstractContainerScreen<?> screen) {
        return enabled() && !editMode && isPetsMenu(title(screen));
    }

    /**
     * Ends "Edit" mode once the player is no longer in the Pets menu.
     *
     * <p><b>Why not on a new screen instance.</b> That is what this used to do, and it made the
     * button almost unusable: Hypixel answers most clicks inside a menu by sending the menu again,
     * which builds a <i>new</i> {@code AbstractContainerScreen} for what the player experiences as
     * the same open menu. Edit mode therefore ended on the first click made in the menu it had just
     * revealed, the overlay slammed back over it, and every further click needed another press of
     * Edit first.
     *
     * <p>A screen swap and a player walking away are the same event to the object identity and
     * completely different events to the person: the swap keeps a Pets menu on screen throughout,
     * while leaving does not. So the question asked here is "is one of our menus still open", which
     * is true across any number of server-side refreshes and false the moment the player closes it
     * or opens something else. No timers - the swap replaces one screen with the next directly, so
     * there is never a tick in between with nothing open.
     */
    public void onClientTick() {
        if (!editMode) {
            return;
        }
        Screen screen = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        boolean stillInMenu = screen instanceof AbstractContainerScreen<?> container
                && isPetsMenu(title(container));
        if (!stillInMenu) {
            editMode = false;
        }
    }

    // ------------------------------------------------------------------
    // Scraping
    // ------------------------------------------------------------------

    /**
     * Reads every pet on the open page into the cache. The page's previous entries are dropped
     * first, so a pet that was sold / levelled / renamed cannot survive as a ghost card – the last
     * visit to a page is always the truth for that page.
     */
    private void cachePage(AbstractContainerScreen<?> screen) {
        // Throttled: the render pass calls this every frame, but re-reading ~54 stacks and their
        // lore that often is pure waste - the server fills a page in over several ticks, not frames.
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS && screen == lastScanScreen) {
            return;
        }
        lastScanAt = now;
        lastScanScreen = screen;
        AbstractContainerMenu menu = screen.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        if (upper == 0) {
            return;
        }
        int page = currentPage(title(screen));
        Map<String, Pet> fresh = new LinkedHashMap<>();
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = strip(stack.getHoverName().getString()).trim();
            Matcher level = PET_LEVEL.matcher(name);
            if (!level.find()) {
                continue;   // only "[Lvl N] <pet>" items are pets; chrome and arrows never match
            }
            List<String> lore = strippedLore(stack);
            Pet pet = new Pet();
            pet.stack = stack.copy();
            pet.level = Integer.parseInt(level.group(1));
            pet.name = name.substring(level.end()).trim();
            pet.rarity = rarityOf(lore);
            pet.heldItem = loreValue(lore, "Held Item");
            pet.active = isSummoned(lore);
            pet.page = page;
            pet.menuSlot = i;
            String key = pet.name.toLowerCase(Locale.ROOT) + "|" + pet.rarity;
            String unique = key;
            for (int n = 2; fresh.containsKey(unique); n++) {
                unique = key + "|#" + n;   // two identical pets (same name AND rarity) both stay
            }
            fresh.put(unique, pet);
        }
        if (fresh.isEmpty()) {
            return;   // the server has not filled the slots in yet - keep what we had
        }
        pets.entrySet().removeIf(e -> e.getValue().page == page);
        pets.putAll(fresh);
        ordered = new ArrayList<>(pets.values());
        ordered.sort((a, b) -> a.page != b.page ? Integer.compare(a.page, b.page)
                : Integer.compare(a.menuSlot, b.menuSlot));
    }

    /** The rarity index from the lore's rarity line (the loudest word wins; defaults to COMMON). */
    private static int rarityOf(List<String> lore) {
        for (int i = lore.size() - 1; i >= 0; i--) {
            String upper = lore.get(i).toUpperCase(Locale.ROOT);
            for (int r = 0; r < RARITY_WORDS.length; r++) {
                if (upper.contains(RARITY_WORDS[r])) {
                    return r;
                }
            }
        }
        return RARITY_WORDS.length - 1;
    }

    /** Whether this pet is the summoned one ("Click to despawn!" / "currently selected"). */
    private static boolean isSummoned(List<String> lore) {
        for (String line : lore) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("despawn") || lower.contains("currently selected")) {
                return true;
            }
        }
        return false;
    }

    /** The value of a "Key: value" lore line, or {@code null}. */
    private static String loreValue(List<String> lore, String key) {
        String needle = key.toLowerCase(Locale.ROOT) + ":";
        for (String line : lore) {
            String lower = line.toLowerCase(Locale.ROOT).trim();
            if (lower.startsWith(needle)) {
                return line.trim().substring(needle.length()).trim();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        String title = title(screen);
        if (!enabled() || !isPetsMenu(title)) {
            return;
        }
        cachePage(screen);   // refresh continuously while open (the server fills slots in)
        if (editMode || ordered.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        g.nextStratum();   // above the container AND its tooltips

        int count = ordered.size();
        int rows = (count + GRID_COLS - 1) / GRID_COLS;
        cardW = clamp((screen.width - 60) / GRID_COLS, 84, 130);
        cardH = 58;
        int gridW = GRID_COLS * cardW;
        int panelW = gridW + 16;
        int panelH = screen.height - 8;
        int x = (screen.width - panelW) / 2;
        int y = 4;
        int currentPage = currentPage(title);

        SciFiRender.glow(g, x, y, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, x, y, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, x + 1, y + 1, panelW - 2, panelH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
        g.centeredText(font, Component.literal("SBS Pets  •  " + count + " pets  •  Page "
                        + currentPage + " open"),
                x + panelW / 2, y + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2,
                SBSTheme.ACCENT_BRIGHT);

        gridLeft = x + 8;
        gridTop = y + SBSTheme.HEADER_HEIGHT;
        buttonsY = y + panelH - SBSTheme.SEARCH_HEIGHT - 8;
        int viewBottom = buttonsY - 6;
        gridViewBottom = viewBottom;
        maxScroll = Math.max(0, rows * cardH - (viewBottom - gridTop));
        scroll = clamp(scroll, 0, maxScroll);

        List<Component> tooltip = null;
        g.enableScissor(x, gridTop, x + panelW, viewBottom);
        for (int i = 0; i < count; i++) {
            int cx = gridLeft + (i % GRID_COLS) * cardW;
            int cy = gridTop - scroll + (i / GRID_COLS) * cardH;
            if (cy + cardH < gridTop || cy > viewBottom) {
                continue;
            }
            Pet pet = ordered.get(i);
            boolean hover = mouseX >= cx && mouseX < cx + cardW && mouseY >= cy && mouseY < cy + cardH
                    && mouseY >= gridTop && mouseY <= viewBottom;
            drawCard(g, font, pet, currentPage, cx, cy, hover);
            if (hover) {
                tooltip = petTooltip(pet, currentPage);
            }
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int trackH = viewBottom - gridTop;
            int thumbH = Math.max(12, trackH * trackH / (rows * cardH));
            int thumbY = gridTop + (int) ((long) (trackH - thumbH) * scroll / maxScroll);
            g.fill(x + panelW - 5, gridTop, x + panelW - 2, viewBottom, SBSTheme.CARD_BG_DISABLED);
            g.fill(x + panelW - 5, thumbY, x + panelW - 2, thumbY + thumbH, SBSTheme.ACCENT);
        }

        buttonW = 70;
        buttonGap = 8;
        int totalW = buttonW * 3 + buttonGap * 2;
        buttonsX = x + (panelW - totalW) / 2;
        drawButton(g, font, buttonsX, buttonsY, buttonW, "Back");
        drawButton(g, font, buttonsX + buttonW + buttonGap, buttonsY, buttonW, "Close");
        drawButton(g, font, buttonsX + (buttonW + buttonGap) * 2, buttonsY, buttonW, "Edit");

        if (tooltip != null) {
            // Drawn directly, not deferred: the deferred flush happens before this overlay's
            // stratum next frame, so a deferred tooltip would be painted over and never seen.
            List<net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent> parts =
                    new ArrayList<>(tooltip.size());
            for (Component line : tooltip) {
                parts.add(net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
                        .create(line.getVisualOrderText()));
            }
            g.tooltip(font, parts, mouseX, mouseY,
                    net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner.INSTANCE,
                    SBSTheme.tooltipStyle());
        }
    }

    /** One pet card: head icon left, name / level / held item right, page chip top-right. */
    private void drawCard(GuiGraphicsExtractor g, Font font, Pet pet, int currentPage,
                          int cx, int cy, boolean hover) {
        boolean onOpenPage = pet.page == currentPage;
        int border = pet.active ? SBSTheme.TOGGLE_ON : RARITY_COLORS[pet.rarity];
        SciFiRender.roundedRectWithBorder(g, cx + 2, cy + 2, cardW - 4, cardH - 4,
                SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, border);

        g.item(pet.stack, cx + 7, cy + 9);

        int textX = cx + 27;
        int textW = cardW - 27 - 6;
        g.text(font, Component.literal(fit(font, pet.name, textW)), textX, cy + 8,
                RARITY_COLORS[pet.rarity], false);
        String level = "Lvl " + pet.level + (pet.active ? "  •  active" : "");
        g.text(font, Component.literal(fit(font, level, textW)), textX, cy + 8 + font.lineHeight + 1,
                pet.active ? SBSTheme.TOGGLE_ON : SBSTheme.TEXT_MUTED, false);
        if (pet.heldItem != null) {
            g.text(font, Component.literal(fit(font, pet.heldItem, textW)), textX,
                    cy + 8 + (font.lineHeight + 1) * 2, SBSTheme.TEXT_MUTED, false);
        }

        // Page chip: green while that page is the open one (a single click then summons).
        String chip = "P" + pet.page;
        int chipW = font.width(chip) + 6;
        int chipX = cx + cardW - chipW - 6;
        SciFiRender.roundedRect(g, chipX, cy + 5, chipW, font.lineHeight + 2, 2,
                onOpenPage ? SBSTheme.TOGGLE_ON : SBSTheme.CARD_BG_DISABLED);
        g.centeredText(font, Component.literal(chip), chipX + chipW / 2, cy + 6,
                onOpenPage ? SBSTheme.CARD_BG : SBSTheme.TEXT_MUTED);
    }

    private static List<Component> petTooltip(Pet pet, int currentPage) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(SECTION_SIGN + "f[Lvl " + pet.level + "] " + pet.name));
        if (pet.heldItem != null) {
            lines.add(Component.literal(SECTION_SIGN + "7Held Item: " + SECTION_SIGN + "f" + pet.heldItem));
        }
        lines.add(Component.literal(SECTION_SIGN + "7Page " + pet.page
                + (pet.page == currentPage ? SECTION_SIGN + "a (open)" : SECTION_SIGN + "8 (closed)")));
        lines.add(Component.literal(pet.page == currentPage
                ? SECTION_SIGN + "eClick to " + (pet.active ? "despawn" : "summon")
                : SECTION_SIGN + "eClick to flip one page towards it"));
        return lines;
    }

    /** Clips a label to a pixel width, ellipsised. */
    private static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && font.width(cut + "…") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    /** Wheel scrolling over the card grid (wired from the container scroll hook). */
    public boolean handleScroll(AbstractContainerScreen<?> screen, double mouseY, double scrollY) {
        if (!isActive(screen) || maxScroll == 0 || scrollY == 0
                || mouseY < gridTop || mouseY > gridViewBottom) {
            return isActive(screen);   // still swallow scrolls over the overlay
        }
        scroll = clamp(scroll - (int) (scrollY * 24), 0, maxScroll);
        return true;
    }

    /** Card / button clicks. Returns true whenever the overlay is up (it swallows every click). */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!isActive(screen)) {
            return false;
        }
        double mx = event.x();
        double my = event.y();

        if (my >= buttonsY && my <= buttonsY + SBSTheme.SEARCH_HEIGHT) {
            for (int i = 0; i < 3; i++) {
                int bx = buttonsX + i * (buttonW + buttonGap);
                if (mx >= bx && mx <= bx + buttonW) {
                    if (i == 0) {
                        clickSlotNamed(screen, "go back");
                    } else if (i == 1) {
                        Minecraft.getInstance().setScreenAndShow(null);
                    } else {
                        editMode = true;   // reveal Hypixel's own menu for this visit
                    }
                    return true;
                }
            }
        }

        int col = (int) ((mx - gridLeft) / Math.max(1, cardW));
        int row = (int) ((my - gridTop + scroll) / Math.max(1, cardH));
        if (mx >= gridLeft && col >= 0 && col < GRID_COLS && row >= 0
                && my >= gridTop && my <= gridViewBottom) {
            int index = row * GRID_COLS + col;
            List<Pet> current = ordered;
            if (index >= 0 && index < current.size()) {
                Pet pet = current.get(index);
                int currentPage = currentPage(title(screen));
                if (pet.page == currentPage) {
                    clickMenuSlot(screen, pet.menuSlot, event.button() == 1 ? 1 : 0);
                } else {
                    // One step towards it; click the card again on the new page (never auto-chained).
                    clickSlotNamed(screen, pet.page > currentPage ? "next page" : "previous page");
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Shared helpers (kept local so the loadouts overlay stays untouched)
    // ------------------------------------------------------------------

    private static int currentPage(String title) {
        Matcher m = PAGE.matcher(title);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static void clickSlotNamed(AbstractContainerScreen<?> screen, String nameFragment) {
        AbstractContainerMenu menu = screen.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack != null && !stack.isEmpty() && strip(stack.getHoverName().getString())
                    .toLowerCase(Locale.ROOT).contains(nameFragment)) {
                clickMenuSlot(screen, i, 0);
                return;
            }
        }
    }

    private static void clickMenuSlot(AbstractContainerScreen<?> screen, int slot, int button) {
        Minecraft minecraft = Minecraft.getInstance();
        if (slot >= 0 && minecraft.gameMode != null && minecraft.player != null) {
            minecraft.gameMode.handleContainerInput(screen.getMenu().containerId, slot, button,
                    ContainerInput.PICKUP, minecraft.player);
        }
    }

    private static void drawButton(GuiGraphicsExtractor g, Font font, int x, int y, int w, String label) {
        SciFiRender.roundedRectWithBorder(g, x, y, w, SBSTheme.SEARCH_HEIGHT,
                SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        g.centeredText(font, Component.literal(label), x + w / 2,
                y + (SBSTheme.SEARCH_HEIGHT - font.lineHeight) / 2, SBSTheme.TEXT);
    }

    private static List<String> strippedLore(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) {
            lines.add(strip(line.getString()));
        }
        return lines;
    }

    private static String strip(String text) {
        return text == null ? "" : text.replaceAll(SECTION_SIGN + ".", "");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
