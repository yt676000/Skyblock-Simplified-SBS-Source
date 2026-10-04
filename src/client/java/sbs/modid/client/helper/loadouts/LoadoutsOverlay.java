/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.loadouts;

import sbs.modid.client.core.config.SaveThrottle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemIcons;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SBS Loadouts: a full-screen card grid over Hypixel's Loadouts menu (titled "(1/3) Loadouts";
 * the legacy "Wardrobe (1/2)" column menu is detected too).
 *
 * <p><b>Data</b>: the modern menu carries one <b>loadout item</b> per loadout ("Loadout 3", with the
 * whole loadout in its lore: Helmet/Chestplate/Leggings/Boots, Necklace/Cloak/Belt/Gloves, Pet).
 * Every open page is scanned for those items; the armor pieces are resolved from the lore text to
 * renderable icons through the SkyBlock item catalogue ({@code normalizeName} strips the reforge, so
 * "Ancient Diamond Necron Head" finds DIAMOND_NECRON_HEAD). Entries are cached per session with the
 * page and menu slot they were seen at.
 *
 * <p><b>Visuals</b>: {@value #GRID_COLS} cards per row. Each card renders the armor on <b>your own
 * player model</b> (a never-ticked {@link RemotePlayer} clone – no armor stand), the <b>pet bobbing
 * beside the head with its nametag</b>, a vertical equipment bar (N/C/B/G) on the right, and a page
 * chip - green when that loadout's page is the one currently open (single click equips).
 *
 * <p><b>Interaction</b>: clicking a card on the open page clicks its loadout item in the REAL menu.
 * Clicking a card on another page clicks the page arrow towards it - one step - and you click the
 * card again once the page has flipped (deliberately two-step, never auto-chained).
 */
public final class LoadoutsOverlay implements sbs.modid.client.core.config.ProfileScopedStore {

    private static final LoadoutsOverlay INSTANCE = new LoadoutsOverlay();

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);
    private static final Pattern PAGE = Pattern.compile("\\(([0-9]+)/([0-9]+)\\)");
    private static final Pattern LOADOUT_NUMBER = Pattern.compile("(?:Loadout|Slot)\\s*([0-9]+)");

    /** Loadouts per menu page in the modern Loadouts menu. */
    private static final int PER_PAGE = 12;
    /** Our display grid is 6 cards per row. */
    private static final int GRID_COLS = 6;

    private static final String[] EQUIP_KEYS = {"Necklace", "Cloak", "Belt", "Gloves"};
    private static final String[] EQUIP_LETTERS = {"N", "C", "B", "G"};

    /** The extra loadout parts listed in the lore, shown left of the player. */
    private static final String[] EXTRA_KEYS = {"HOTF", "HOTM", "Power Stone", "Tuning"};
    private static final String[] EXTRA_LETTERS = {"F", "M", "P", "T"};
    private static final String[] EXTRA_NAMES = {"Heart of the Forest", "Heart of the Mountain",
            "Power Stone", "Stat Tuning"};

    private static final String[] ARMOR_KEYS = {"Helmet", "Chestplate", "Leggings", "Boots"};

    /**
     * Quick-nav buttons along the bottom: label + the SkyBlock command each runs (no leading slash).
     * Stat Tuning and Power Stone both live inside the Accessory Bag, so both open it; Heart of the
     * Forest lives in the Rift, so its button teleports there.
     */
    private static final String[][] NAV_BUTTONS = {
        {"Armor", "wardrobe"},
        {"Equip", "equipment"},
        {"Tuning", "accessorybag"},
        {"Power", "accessorybag"},
        {"HOTM", "hotm"},
        {"HOTF", "rift"},
        {"Pets", "pets"},
    };

    /** The Loadouts menu slot that opens "(1/3) Armor Sets" (worn chestplate; CONFIRMED on page 1). */
    private static final int ARMOR_SETS_SLOT = 20;

    /** Width of the per-card rename pencil button. */
    private static final int PENCIL_W = 12;

    private enum Status { READY, EMPTY, EQUIPPED }

    /**
     * One cached loadout. Mutable on purpose: entries are MERGED across visits with a quality rule -
     * a REAL stack (harvested from the menu or your worn gear, carrying dyes / applied skins) is
     * never downgraded to a name-resolved catalogue icon again. That is what keeps a loadout's skins
     * correct after you switch to another one, and the whole thing is persisted to disk.
     */
    private static final class Entry {
        final ItemStack[] pieces = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
        final boolean[] realPieces = new boolean[4];
        Status status = Status.READY;
        ItemStack loadoutItem = ItemStack.EMPTY;
        String pet;
        boolean[] equipment = new boolean[EQUIP_KEYS.length];
        int page = 1;
        int menuSlot = -1;
        /**
         * A helmet taken from the body when the menu's loadout item was lost (see
         * {@link #repairHelmetFromBody}). Only ever drawn - never used to identify a loadout, so a
         * body-sourced helmet cannot fake the menu's identity. Cleared once the menu item is back.
         */
        ItemStack bodyHelmet = ItemStack.EMPTY;
        /**
         * The file's JSON for a piece / the loadout item that did not decode yet (registries not
         * ready, or from another world). Retried on later refreshes, and written back unchanged
         * meanwhile, so a failed read can never turn into a {@code null} on disk.
         */
        final com.google.gson.JsonElement[] rawPieces = new com.google.gson.JsonElement[4];
        com.google.gson.JsonElement rawLoadoutItem;

        ItemStack helmet() { return pieces[0]; }
        ItemStack chest() { return pieces[1]; }
        ItemStack legs() { return pieces[2]; }
        ItemStack boots() { return pieces[3]; }
        Status status() { return status; }
        ItemStack loadoutItem() { return loadoutItem; }
        String pet() { return pet; }
        boolean[] equipment() { return equipment; }
        int page() { return page; }
        int menuSlot() { return menuSlot; }

        /** This loadout's stripped lore (armor / equipment / HOTM… values live here per loadout). */
        List<String> lore() {
            return strippedLore(loadoutItem);
        }

        boolean hasArmor() {
            return !pieces[0].isEmpty() || !pieces[1].isEmpty()
                    || !pieces[2].isEmpty() || !pieces[3].isEmpty();
        }
    }

    /**
     * Bumped whenever a stored field's MEANING changes and the old value has to be thrown away.
     * v2: armor is bound to the loadout slot, so column-bound armor from older builds is dropped.
     * v3: the menu-column source is gone entirely, and a status bug briefly wrote the worn armor
     * into UNUSED loadouts - both leave wrong "real" stacks that would never be overwritten.
     */
    private static final int CACHE_VERSION = 3;

    /** Pages whose layout has already been logged this session (dev mode only). */
    private final java.util.Set<Integer> loggedLayoutPages = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Global loadout number -> cached entry (survives page flips; session-scoped). */
    private final Map<Integer, Entry> slots = new ConcurrentHashMap<>();
    private volatile int maxSlot;

    /** Resolved pet icons by pet lore line, so the catalogue lookup runs once per pet. */
    private final Map<String, ItemStack> petIcons = new ConcurrentHashMap<>();

    /**
     * "Edit" mode: the overlay stands aside so Hypixel's own menu can be used, until the player
     * leaves the Loadouts menu. Cleared in {@link #onClientTick()} and nowhere else - see the note
     * there for why the obvious place is wrong.
     */
    private boolean editMode;

    /** Preview model: your own player (skin included), re-dressed per card - never an armor stand. */
    private AbstractClientPlayer preview;

    /** Whether {@link PreviewMirror} has written to {@link #preview} since it was last cleared. */
    private boolean previewMirrored;

    private LoadoutsOverlay() {
        sbs.modid.client.core.config.ProfileContext.getInstance().register(this);
    }

    public static LoadoutsOverlay getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().skyblockMenu.sbsWardrobe;
    }

    private static String title(AbstractContainerScreen<?> screen) {
        return screen.getTitle() != null
                ? screen.getTitle().getString().replaceAll(SECTION_SIGN + ".", "").trim() : "";
    }

    /** The modern menu is "(1/3) Loadouts"; the legacy one "Wardrobe (1/2)". Both are ours. */
    static boolean isLoadoutsMenu(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.contains("loadout") || lower.startsWith("wardrobe");
    }

    /** True while the overlay is actively covering the menu (input should go to us). */
    public boolean isActive(AbstractContainerScreen<?> screen) {
        return enabled() && !editMode && isLoadoutsMenu(title(screen));
    }

    /**
     * The <b>real menu slot</b> behind the loadout card at {@code (mx, my)}, or {@code -1} when the
     * overlay is not active, no card sits there, or the card's loadout lives on a page the menu does
     * not have open. Lets the Slot Hotkeys scan bind a hotkey by clicking a CARD: the overlay is
     * fullscreen while the vanilla menu underneath is small, so the vanilla hovered-slot lookup the
     * scan normally uses points at nothing (or the wrong slot) here.
     */
    public int menuSlotAt(AbstractContainerScreen<?> screen, double mx, double my) {
        if (!isActive(screen) || cardW <= 0 || cardH <= 0) {
            return -1;
        }
        int col = (int) ((mx - gridLeft) / cardW);
        int row = (int) ((my - gridTop + scroll) / cardH);
        if (mx < gridLeft || col < 0 || col >= GRID_COLS || row < 0
                || my < gridTop || my > gridViewBottom) {
            return -1;
        }
        int slotNumber = row * GRID_COLS + col + 1;
        if (slotNumber < 1 || slotNumber > maxSlot) {
            return -1;
        }
        Entry entry = slots.get(slotNumber);
        if (entry == null || entry.page() != currentPage(title(screen))) {
            return -1;   // only cards of the OPEN page map onto live menu slots
        }
        return entry.menuSlot();
    }

    // ------------------------------------------------------------------
    // Scraping: one loadout item per loadout, everything read from its lore
    // ------------------------------------------------------------------

    private void cachePage(AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        if (upper == 0) {
            return;
        }
        loadCache();
        int page = currentPage(title(screen));
        // The page carries the REAL armor stacks in the columns (rows 1-4) - exact dyes / applied
        // skins - and the generic slot icons for HOTF / HOTM / Power Stone / Tuning (the same icon
        // for every loadout; the per-loadout VALUE lives in each loadout's own lore).
        harvestExtraSlotIcons(menu, upper);
        logMenuLayout(menu, upper, page);

        int positional = 0;
        boolean changed = false;
        int markedOnPage = -1;
        boolean storedOnPage = false;
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = strip(stack.getHoverName().getString()).trim();
            List<String> lore = strippedLore(stack);
            String allText = name + " " + String.join(" ", lore);
            if (!isLoadoutItem(name, allText)) {
                continue;
            }
            positional++;
            // The item's own number wins ("Loadout 3"); renamed loadouts fall back to their
            // position on the page (12 per page), which survives any custom name.
            int number;
            Matcher m = LOADOUT_NUMBER.matcher(allText);
            if (m.find()) {
                number = Integer.parseInt(m.group(1));
            } else {
                number = (page - 1) * PER_PAGE + positional;
            }
            Entry entry = slots.computeIfAbsent(number, n -> new Entry());
            entry.status = statusOf(allText, lore);
            entry.loadoutItem = stack.copy();
            entry.page = page;
            entry.menuSlot = i;
            String pet = loreValue(lore, "Pet");
            if (pet != null) {
                entry.pet = pet;
            }
            entry.equipment = equipmentOf(lore);

            // Armor: real column stack > real remembered stack > lore-resolved icon. A real stack
            // is never downgraded - that is what keeps skins after switching loadouts.
            var player = Minecraft.getInstance().player;
            // The loadout's OWN item is its helmet - the menu draws each loadout as the head it
            // wears, applied skin and all ("Loadout 2" is a player_head whose id is
            // skyblock:WISE_WITHER_HELMET). It belongs to this loadout by construction, so it needs
            // no matching and cannot be confused with another loadout's.
            ItemStack ownHelmet = helmetFromLoadoutItem(stack, lore);

            /*
             * Reading the armor off your body is only valid for the loadout you are ACTUALLY wearing,
             * and the lore status is not proof of that: it told us loadout 9 was equipped while the
             * body wore something else, so slot 9 swallowed that armor - helmet included - and ended
             * up storing a head belonging to neither loadout (verified in the real cache: slot 9's
             * stored helmet matched no loadout item at all).
             *
             * The helmet settles it. It is bound to the slot by construction, so if the head on the
             * body is not this loadout's head, this is not the loadout on the body - full stop, no
             * status line needed. Same check the tick capture already used; the menu path was the
             * hole. The item-derived helmet is therefore never overwritten by worn gear either: at
             * best the worn head is identical, at worst it belongs to someone else.
             */
            boolean wearing = player != null && !ownHelmet.isEmpty()
                    && sameSkyblockPiece(player.getItemBySlot(EquipmentSlot.HEAD), ownHelmet);
            // ...and it decides the green "this is the one you are wearing" card too. The lore rule
            // that used to (no "Left-click to equip!" hint = worn) no longer holds: the menu dump of
            // 2026-08-08 has that hint on the WORN loadout as well, so nothing was ever EQUIPPED and
            // no card lit up. EMPTY is left to statusOf - that is about the loadout, not the body.
            if (wearing) {
                entry.status = Status.EQUIPPED;
                markedOnPage = number;
            } else if (entry.status == Status.EQUIPPED) {
                entry.status = Status.READY;
            }
            if (wearing && equippedSlot != number) {
                // Proven to be the worn one, so it overrules the anchor an equip click left behind
                // and cancels a capture that is still aimed at the slot you left.
                captureUntil = 0;
                equippedSlot = number;
            }
            for (int p = 1; p < 4 && wearing; p++) {
                ItemStack worn = player.getItemBySlot(switch (p) {
                    case 1 -> EquipmentSlot.CHEST;
                    case 2 -> EquipmentSlot.LEGS;
                    default -> EquipmentSlot.FEET;
                });
                if (worn != null && !worn.isEmpty()
                        && !ItemStack.isSameItemSameComponents(worn, entry.pieces[p])) {
                    entry.pieces[p] = worn.copy();
                    entry.realPieces[p] = true;
                    changed = true;
                }
            }
            for (int p = 0; p < 4; p++) {
                // Piece 0 always comes from the loadout's own item. Nothing may override it - that
                // is the whole point of having one source that cannot be mismatched.
                if (p == 0 && !ownHelmet.isEmpty()) {
                    if (!ItemStack.isSameItemSameComponents(ownHelmet, entry.pieces[0])) {
                        entry.pieces[0] = ownHelmet.copy();
                        entry.realPieces[0] = true;
                        changed = true;
                    }
                    continue;
                }
                // No real stack for this piece: fall back to the icon its OWN lore names. Re-derived
                // on every visit rather than only when the box is empty - the old "fill once" rule
                // meant a piece resolved from an earlier, wrongly-bound cache was never refreshed,
                // and a piece that failed to resolve while the catalogue was still loading kept
                // whatever it had. It is also PERSISTED now (changed = true): without that the boxes
                // came back empty after every restart, because only real stacks marked the cache
                // dirty - which is why the helmet stayed and the rest did not.
                if (entry.realPieces[p]) {
                    continue;
                }
                ItemStack fromLore = armorFromLore(lore, ARMOR_KEYS[p]);
                if (!fromLore.isEmpty() && !ItemStack.isSameItemSameComponents(fromLore, entry.pieces[p])) {
                    entry.pieces[p] = fromLore;
                    changed = true;
                }
            }
            storedOnPage |= number == rememberedSlot;
            maxSlot = Math.max(maxSlot, number);
        }
        // What this page says is equipped becomes the widget's answer until the next visit.
        if (positional > 0) {
            adoptMenuRead(markedOnPage, storedOnPage);
        }
        // The worn armor in the menu's own preview column is the best dye/skin source there is for
        // the loadout you are ALREADY wearing. Modern menu only: the legacy wardrobe layout puts
        // other loadouts' pieces at these positions, the very mis-binding the column rules banned.
        if (title(screen).toLowerCase(Locale.ROOT).contains("loadout")
                && harvestWornColumn(menu, upper)) {
            changed = true;
        }
        if (changed) {
            saveCache(false);
        } else {
            flushDeferred();
        }
    }

    /**
     * The generic slot icon for each of HOTF / HOTM / Power Stone / Tuning, harvested from the menu
     * by name. These are the SAME item for every loadout (the loadout only changes the value shown
     * in the icon's lore), so one icon per type is enough; the per-loadout value is read from each
     * loadout's own lore in {@link #drawCard}. Persisted globally.
     */
    private final ItemStack[] extraSlotIcons =
            {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};

    /**
     * Vanilla stand-ins, built <b>lazily</b> - never in a static initialiser.
     *
     * <p>{@code new ItemStack(...)} reads the item's components, and those are not bound until well
     * into startup: building one while this class is being loaded crashes the game with "Components
     * not bound yet". As a static field that made the whole class a landmine - merely <i>mentioning</i>
     * {@code LoadoutsOverlay} from anything that runs early (a client-tick hook was enough) blew it
     * up. Lazy, they cost one null check on a path that only ever runs while a menu is open, and the
     * class is safe to touch from anywhere.
     */
    private static ItemStack[] extraFallbackIcons;
    private static ItemStack petFallbackIcon;

    /** Stand-in per extra slot, shown until the real menu items have been harvested. */
    private static ItemStack[] extraFallbackIcons() {
        if (extraFallbackIcons == null) {
            extraFallbackIcons = new ItemStack[] {
                new ItemStack(Items.OAK_SAPLING),      // Heart of the Forest
                new ItemStack(Items.DIAMOND_PICKAXE),  // Heart of the Mountain
                new ItemStack(Items.NETHER_STAR),      // Power Stone
                new ItemStack(Items.REDSTONE),         // Stat Tuning
            };
        }
        return extraFallbackIcons;
    }

    /** Stand-in for a pet whose real head has not resolved yet (the Pets menu icon). */
    private static ItemStack petFallbackIcon() {
        if (petFallbackIcon == null) {
            petFallbackIcon = new ItemStack(Items.BONE);
        }
        return petFallbackIcon;
    }

    /**
     * Maxwell power (lower-case) -> the power stone item that unlocks it, extracted from the repo
     * item lores ("Combine 9x of this stone ... to permanently unlock the Silky power"). Lets
     * every card show ITS power's actual stone instead of one generic slot icon.
     */
    private static final Map<String, String> POWER_STONE_IDS = Map.ofEntries(
            Map.entry("forceful", "ACACIA_BIRDHOUSE"),
            Map.entry("bloody", "BEATING_HEART"),
            Map.entry("bubba", "BUBBA_BLISTER"),
            Map.entry("crumbly", "CHOCOLATE_CHIP"),
            Map.entry("shaded", "DARK_ORB"),
            Map.entry("sanguisuge", "DISPLACED_LEECH"),
            Map.entry("bizarre", "ECCENTRIC_PAINTING"),
            Map.entry("sighted", "ENDER_MONOCLE"),
            Map.entry("adept", "END_STONE_SHULKER"),
            Map.entry("itchy", "FURBALL"),
            Map.entry("frozen", "GLACITE_SHARD"),
            Map.entry("slender", "HAZMAT_ENDERMAN"),
            Map.entry("demonic", "HORNS_OF_TORMENT"),
            Map.entry("silky", "LUXURIOUS_SPOOL"),
            Map.entry("hurtful", "MAGMA_URCHIN"),
            Map.entry("strong", "MANDRAA"),
            Map.entry("mythical", "OBSIDIAN_TABLET"),
            Map.entry("pleasant", "PRECIOUS_PEARL"),
            Map.entry("sweet", "ROCK_CANDY"),
            Map.entry("scorching", "SCORCHED_BOOKS"),
            Map.entry("buttery", "SUNFLOWER_BUTTER"),
            Map.entry("healthy", "VITAMIN_DEATH"),
            Map.entry("unhealthy", "VITAMIN_LIFE"));

    /** Resolved power stone icons by power name; successes only (icons resolve async). */
    private final Map<String, ItemStack> powerStoneCache = new ConcurrentHashMap<>();

    /** The stone item icon for a Maxwell power ("Silky" -> Luxurious Spool), or empty. */
    private ItemStack powerStoneIcon(String power) {
        String key = cleanName(power);
        ItemStack cached = powerStoneCache.get(key);
        if (cached != null) {
            return cached;
        }
        String id = POWER_STONE_IDS.get(key);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        ItemStack icon = SkyBlockItemIcons.getInstance().icon(id, power, 1);
        if (icon == null || icon.isEmpty() || isBarrier(icon)) {
            return ItemStack.EMPTY;   // still loading - retry next frame
        }
        powerStoneCache.put(key, icon);
        return icon;
    }

    private void harvestExtraSlotIcons(AbstractContainerMenu menu, int upper) {
        boolean changed = false;
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = strip(stack.getHoverName().getString()).trim();
            // Never match a loadout item: its lore lists "Power Stone:" etc. and a custom-renamed
            // loadout could even carry one of the keywords in its NAME.
            if (isLoadoutItem(name, name + " " + String.join(" ", strippedLore(stack)))) {
                continue;
            }
            String lower = cleanName(name);
            // Power Stone matches "power" as its own word too (the slot item may be named after
            // the active power, e.g. "Fortuitous Power") - but never inside "Empowered ...".
            int idx = lower.contains("heart of the forest") ? 0
                    : lower.contains("heart of the mountain") ? 1
                    : lower.contains("power stone") || lower.matches(".*\\bpowers?\\b.*") ? 2
                    : lower.contains("tuning") ? 3 : -1;
            if (idx >= 0 && extraSlotIcons[idx].isEmpty()) {
                extraSlotIcons[idx] = stack.copy();
                changed = true;
            }
        }
        if (changed) {
            saveCache(true);   // a fresh slot icon must never be dropped by the save throttle
        }
    }

    /**
     * Real pet items harvested from the PETS menu, keyed by cleaned pet name ("golden dragon") -
     * the ONLY place the pet exists as an item, complete with its skull texture and applied skin
     * (the repo carries no pet appearances at all). Persisted with the rest of the cache.
     */
    private final Map<String, ItemStack> petStacks = new ConcurrentHashMap<>();

    private static final Pattern PET_ITEM_NAME = Pattern.compile("^\\[Lvl\\s*[0-9]+]\\s*(.+)$");

    /**
     * Called for every container frame (before the loadouts gate): while a Pets menu is open, every
     * pet item is remembered so the loadout cards can show the REAL pet head, skin included.
     */
    private void harvestPetsMenu(AbstractContainerScreen<?> screen) {
        // The title carries the page counter FIRST, exactly like the Loadouts menu: "(1/3) Pets" -
        // a plain startsWith("pets") never matched and the harvest silently never ran.
        String bare = title(screen).replaceAll("\\([0-9]+/[0-9]+\\)", "").trim().toLowerCase(Locale.ROOT);
        if (!bare.startsWith("pets")) {
            return;
        }
        loadCache();
        AbstractContainerMenu menu = screen.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        boolean changed = false;
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Matcher m = PET_ITEM_NAME.matcher(strip(stack.getHoverName().getString()).trim());
            if (!m.matches()) {
                continue;
            }
            String key = cleanName(m.group(1));
            // Only a pet we do not already hold counts as a change. Storing it unconditionally made
            // every frame the Pets menu was open a "change", and each one reset the save throttle
            // (lastSaveAt = 0) and rewrote the whole cache: ~70 writes a second of a file that is
            // approaching a megabyte, for a minute at a time. Verified in the play instance's log.
            ItemStack known = petStacks.get(key);
            if (!key.isEmpty() && (known == null || !ItemStack.isSameItemSameComponents(known, stack))) {
                petStacks.put(key, stack.copy());
                changed = true;
            }
        }
        if (changed) {
            saveCache(true);   // freshly harvested pets must never be dropped by the save throttle
        }
    }

    /**
     * Copies the worn armor into the loadout that is wearing it. Called every client tick.
     *
     * <p>This is the one source that needs no matching at all. Every other route - menu columns,
     * name comparisons against the lore - has to <i>work out</i> which stacks belong to which
     * loadout, and each attempt got it wrong in its own way (a column can belong to two loadouts, a
     * name cannot tell two identical sets with different skins apart). Reading your own body cannot
     * be ambiguous: those four stacks are that loadout's armor, with its dyes and its applied skins,
     * and they are stored under its number and never derived again.
     *
     * <p>Two routes to it, and they answer "which loadout is this" differently. {@link
     * #healWornLoadout} asks the <b>helmet</b> and so runs whenever you are wearing a loadout, no
     * matter how you equipped it. {@link #captureAfterEquip} takes the answer from the equip click
     * itself and so also covers the pieces the lore does not name, but only inside
     * {@link #CAPTURE_WINDOW_MS} after that click - armor you change by hand later is not quietly
     * absorbed into whatever loadout you last wore.
     */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        endEditModeWhenTheMenuIsGone();
        healWornLoadout(now);
        captureAfterEquip(now);
    }

    /**
     * Ends "Edit" mode once the player is no longer in the Loadouts menu.
     *
     * <p><b>Why not on a new screen instance.</b> That is what this used to do, and it made the
     * button almost unusable: Hypixel answers most clicks inside a menu by sending the menu again,
     * which builds a <i>new</i> {@code AbstractContainerScreen} for what the player experiences as
     * the same open menu. Edit mode therefore ended on the first click made in the menu it had just
     * revealed, the overlay slammed back over it, and every further click needed another press of
     * Edit first.
     *
     * <p>A screen swap and a player walking away are the same event to the object identity and
     * completely different events to the person: the swap keeps a Loadouts menu on screen
     * throughout, while leaving does not. So the question asked here is "is one of our menus still
     * open", which is true across any number of server-side refreshes and false the moment the
     * player closes it or opens something else. No timers - the swap replaces one screen with the
     * next directly, so there is never a tick in between with nothing open.
     */
    private void endEditModeWhenTheMenuIsGone() {
        if (!editMode) {
            return;
        }
        Screen screen = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        boolean stillInMenu = screen instanceof AbstractContainerScreen<?> container
                && isLoadoutsMenu(title(container));
        if (!stillInMenu) {
            editMode = false;
        }
    }

    /** How often the worn gear is copied into the loadout the body proves it is wearing. */
    private static final long HEAL_INTERVAL_MS = 1000L;

    private long lastHealAt;

    /**
     * Copies the worn armor into the loadout the <b>helmet</b> proves is on your body - always, not
     * only inside the window an equip click opens.
     *
     * <p>Every other capture route needs you to have equipped the loadout through this overlay
     * ({@link #captureAfterEquip}) or to have the Loadouts menu open while wearing it
     * ({@link #harvestWornColumn}). Equip it from Hypixel's own menu, from a command or from a
     * keybind and none of them ever sees it, so that loadout keeps whatever the lore could resolve:
     * an undyed catalogue icon. That is the "my Necron and Crimson boots are default orange after a
     * restart" report - dyed leather resolves to bare {@code leather_boots} - and it fixed itself
     * "for this session" only because the click capture happened to run that once.
     *
     * <p>The gates are the strict ones, not new ones: the helmet decides which loadout this is
     * ({@link #resolveWornSlotByHelmet} - a uuid match on the loadout's own item, never the listing),
     * and a piece is only stored when that loadout's own lore names exactly the item you are wearing.
     * So a piece you swapped by hand is skipped rather than absorbed, and the helmet itself is never
     * touched - it only ever comes from the loadout item.
     */
    private void healWornLoadout(long now) {
        if (now - lastHealAt < HEAL_INTERVAL_MS || slots.isEmpty()) {
            return;
        }
        lastHealAt = now;
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        repairHelmetFromBody();
        int wornSlot = resolveWornSlotByHelmet();
        Entry entry = slots.get(wornSlot);
        if (entry == null) {
            return;
        }
        if (wornSlot == rememberedSlot) {
            confirmSession("the worn set identified by its helmet");
        }
        List<String> lore = entry.lore();
        boolean changed = false;
        boolean newPiece = false;
        for (int p = 1; p < 4; p++) {
            ItemStack worn = player.getItemBySlot(ARMOR_SLOTS[p]);
            String listed = loreValue(lore, ARMOR_KEYS[p]);
            if (worn == null || worn.isEmpty() || listed == null
                    || !cleanName(listed).equals(cleanName(strip(worn.getHoverName().getString())))
                    || samePieceLook(worn, entry.pieces[p])) {
                continue;   // no piece, someone else's piece, or already stored
            }
            newPiece |= !uuidOf(worn).equals(uuidOf(entry.pieces[p]));
            entry.pieces[p] = worn.copy();
            entry.realPieces[p] = true;
            changed = true;
        }
        if (changed) {
            // Through the throttle unless a different physical piece arrived: a look-only refresh
            // (dye, skin) can wait four seconds, and an animated dye must never mean a write per tick.
            saveCache(newPiece);
        }
    }

    /**
     * Gives the remembered loadout a helmet to draw when its loadout item was lost (an older build
     * wrote nulls over it after a server hop): if the worn chestplate, leggings and boots are that
     * loadout's own pieces by uuid, the worn helmet is the loadout's helmet. Kept apart from the
     * menu's item ({@link Entry#bodyHelmet}), so it never identifies a loadout.
     */
    private void repairHelmetFromBody() {
        Entry entry = rememberedSlot > 0 ? slots.get(rememberedSlot) : null;
        if (entry == null || !entry.loadoutItem.isEmpty() || !entry.pieces[0].isEmpty()
                || !entry.bodyHelmet.isEmpty()) {
            return;
        }
        ItemStack[] worn = wornArmor();
        if (worn == null || worn[0].isEmpty()) {
            return;
        }
        int matched = 0;
        for (int p = 1; p < 4; p++) {
            String stored = uuidOf(entry.pieces[p]);
            if (stored.isEmpty()) {
                continue;
            }
            if (!stored.equals(uuidOf(worn[p]))) {
                return;   // a stored piece that is not on the body: not this loadout
            }
            matched++;
        }
        if (matched == 0) {
            return;
        }
        entry.bodyHelmet = worn[0].copy();
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Loadouts] repaired slot {} helmet from the body "
                + "({} worn piece(s) matched by uuid)", rememberedSlot, matched);
        saveCache(false);
    }

    private void captureAfterEquip(long now) {
        if (equippedSlot < 0 || now > captureUntil || now - lastCaptureAt < 500L) {
            return;
        }
        lastCaptureAt = now;
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Entry entry = slots.get(equippedSlot);
        if (entry == null) {
            return;
        }
        // Self-verifying: the helmet came from the loadout's OWN item, so it is known to be right for
        // this slot. Until the head on the body matches it, the swap has not landed yet and the body
        // is still wearing the PREVIOUS loadout - capturing then would write that loadout's chest,
        // legs and boots into this slot, and the two would look identical afterwards. Waiting for the
        // helmet to agree makes a wrong capture impossible rather than merely unlikely.
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (entry.pieces[0].isEmpty() || head == null
                || !sameSkyblockPiece(head, entry.pieces[0])) {
            return;
        }
        confirmSession("an overlay equip landed");
        boolean changed = false;
        // From p = 1: the helmet only ever comes from the loadout's own item, and the check above
        // already proved the worn one is identical anyway.
        for (int p = 1; p < 4; p++) {
            ItemStack worn = player.getItemBySlot(switch (p) {
                case 1 -> EquipmentSlot.CHEST;
                case 2 -> EquipmentSlot.LEGS;
                default -> EquipmentSlot.FEET;
            });
            if (worn == null || worn.isEmpty() || samePieceLook(worn, entry.pieces[p])) {
                continue;
            }
            entry.pieces[p] = worn.copy();
            entry.realPieces[p] = true;
            changed = true;
        }
        if (changed) {
            saveCache(true);   // a one-shot capture must never be eaten by the save throttle
        }
    }

    /**
     * Whether a worn piece is already stored, for the heal and the capture: the same SkyBlock item
     * (uuid) looking the same way (dye, skin, model, trim, glint). Not every component: Hypixel
     * rewrites parts of a worn piece's data while you wear it, so a full component compare called
     * the same boots "new" on every one-second heal and rewrote a megabyte of cache each time - the
     * heartbeat of one "[SBS][Loadouts] cache:" line per second. Pieces without a uuid fall back to
     * the full compare, as before.
     */
    static boolean samePieceLook(ItemStack worn, ItemStack stored) {
        if (worn == null || stored == null || worn.isEmpty() || stored.isEmpty()) {
            return false;
        }
        String a = SkyblockItem.extraAttributes(worn).getStringOr("uuid", "");
        String b = SkyblockItem.extraAttributes(stored).getStringOr("uuid", "");
        if (a.isEmpty() || b.isEmpty()) {
            return ItemStack.isSameItemSameComponents(worn, stored);
        }
        if (!a.equals(b) || worn.getItem() != stored.getItem()) {
            return false;
        }
        for (var type : LookComponents.ALL) {
            if (!java.util.Objects.equals(worn.get(type), stored.get(type))) {
                return false;
            }
        }
        return PieceCompare.sameDye(dyeItem(worn), dyeItem(stored), dyeColor(worn), dyeColor(stored));
    }

    /** custom_data {@code dye_item} (an animated dye such as DYE_BLACK_ICE), or "". */
    private static String dyeItem(ItemStack stack) {
        return SkyblockItem.extraAttributes(stack).getStringOr("dye_item", "");
    }

    private static Integer dyeColor(ItemStack stack) {
        var dyed = stack.get(net.minecraft.core.component.DataComponents.DYED_COLOR);
        return dyed == null ? null : dyed.rgb();
    }

    private static String uuidOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : SkyblockItem.extraAttributes(stack).getStringOr("uuid", "");
    }

    /**
     * The components that change how a stored piece is drawn. A holder class, so touching
     * {@code DataComponents} waits for the first compare instead of loading with this class - the
     * menu-title tests load it without a bootstrapped registry.
     */
    private static final class LookComponents {
        // DYED_COLOR is not here: an animated dye rewrites it every second - see PieceCompare.sameDye.
        static final List<net.minecraft.core.component.DataComponentType<?>> ALL = List.of(
            net.minecraft.core.component.DataComponents.PROFILE,
            net.minecraft.core.component.DataComponents.ITEM_MODEL,
            net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
            net.minecraft.core.component.DataComponents.TRIM,
            net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
    }

    /** Preview column of the modern Loadouts menu: the WORN helmet / chestplate / leggings / boots. */
    private static final int[] WORN_COLUMN_SLOTS = {11, 20, 29, 38};

    /**
     * Copies the armor shown in the menu's own preview column into the loadout wearing it.
     *
     * <p>The modern Loadouts menu always displays the gear on your body down its second column
     * (helmet 11, chestplate 20, leggings 29, boots 38 - dev layout dump 2026-08-02), as REAL
     * stacks with their dyes and applied skins. That makes it the one armor source that needs no
     * equip click: the tick capture only serves loadouts you (re-)equip through the overlay, so a
     * loadout you simply already wear never went through it and kept default-coloured catalogue
     * icons forever.
     *
     * <p>Ownership: the worn helmet's uuid picks the loadout when it can ({@link
     * #sameSkyblockPiece}); for a helmetless loadout a SINGLE lore-equipped entry is accepted, and
     * two claimants without helmet proof are refused outright. Each piece is then only stored when
     * the owner's own lore names exactly that item, so a hand-swapped piece is skipped instead of
     * absorbed (the old "loadout 9" trap).
     */
    private boolean harvestWornColumn(AbstractContainerMenu menu, int upper) {
        if (upper < 45) {
            return false;   // not the modern six-row loadouts layout
        }
        ItemStack wornHelmet = menu.getSlot(WORN_COLUMN_SLOTS[0]).getItem();
        Entry owner = null;
        for (int n = 1; n <= maxSlot && owner == null; n++) {
            Entry entry = slots.get(n);
            if (entry != null && sameSkyblockPiece(wornHelmet, entry.pieces[0])) {
                owner = entry;
            }
        }
        if (owner == null) {
            for (int n = 1; n <= maxSlot; n++) {
                Entry entry = slots.get(n);
                if (entry == null || entry.status() != Status.EQUIPPED) {
                    continue;
                }
                if (owner != null) {
                    return false;   // two claimants and no helmet proof - never guess
                }
                owner = entry;
            }
            if (owner == null) {
                return false;
            }
        }
        boolean changed = false;
        List<String> lore = owner.lore();
        for (int p = 1; p < 4; p++) {   // the helmet always stays the loadout's own item
            ItemStack worn = menu.getSlot(WORN_COLUMN_SLOTS[p]).getItem();
            String listed = loreValue(lore, ARMOR_KEYS[p]);
            if (worn == null || worn.isEmpty() || listed == null
                    || !cleanName(listed).equals(cleanName(strip(worn.getHoverName().getString())))
                    || ItemStack.isSameItemSameComponents(worn, owner.pieces[p])) {
                continue;   // empty cell, a placeholder, someone else's piece, or already stored
            }
            owner.pieces[p] = worn.copy();
            owner.realPieces[p] = true;
            changed = true;
        }
        return changed;
    }

    /**
     * Dumps the real menu layout (slot ▸ item id ▸ name) once per page while dev mode is on.
     *
     * <p>Chest, leggings and boots are still matched by NAME, and that cannot separate two loadouts
     * wearing the same set with different skins. Fixing it needs to know where Hypixel actually puts
     * those stacks - and every positional guess so far has been wrong - so this prints the ground
     * truth instead of inviting another guess. Enable with {@code /sbsdev}, open the menu, read the
     * {@code [SBS][Loadouts] layout} lines.
     */
    private void logMenuLayout(AbstractContainerMenu menu, int upper, int page) {
        if (!sbs.modid.client.core.dev.DevMode.ACTIVE || !loggedLayoutPages.add(page)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            sb.append(i).append('=').append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath())
                    .append('"').append(strip(stack.getHoverName().getString()).trim()).append("\" ");
        }
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Loadouts] layout page {} ({} slots): {}",
                page, upper, sb.toString().trim());
    }

    /**
     * This loadout's helmet, taken from the loadout item itself.
     *
     * <p>The menu draws every loadout as the head it wears: the item is a {@code player_head} (or the
     * helmet item) carrying the real applied skin, only renamed to "Loadout N". That makes it the one
     * armor source that is bound to the loadout <b>by construction</b> - no column, no name match, no
     * way for a neighbour to claim it - which is why it overrides everything else.
     *
     * @return the helmet, or empty when this loadout has none (the menu then shows a placeholder,
     *         typically a dye, which must never be worn by the preview)
     */
    private static ItemStack helmetFromLoadoutItem(ItemStack stack, List<String> lore) {
        return carriesOwnHelmet(stack, lore) ? stack.copy() : ItemStack.EMPTY;
    }

    /**
     * Whether a loadout's own menu item <b>is</b> its helmet - it lists one, and it is a wearable
     * head. Hypixel builds the icon out of the helmet and keeps the piece's SkyBlock {@code uuid}
     * through the rename, so the item alone identifies the armor without any of it being captured.
     *
     * <p>Separate from {@link #helmetFromLoadoutItem} only so the identity can be asked without the
     * {@code copy()} that method makes - {@link #resolveWornSlotByHelmet} asks it for every cached
     * loadout, several times a second, and never mutates the answer. One test, two callers.
     */
    private static boolean carriesOwnHelmet(ItemStack stack, List<String> lore) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String listed = loreValue(lore, ARMOR_KEYS[0]);
        if (listed == null || listed.isBlank() || listed.equalsIgnoreCase("None")) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.equals("player_head") || path.endsWith("_head") || path.endsWith("_skull")
                || path.endsWith("helmet") || path.endsWith("cap");
    }

    /**
     * Whether two stacks are the same <b>physical</b> SkyBlock item, surviving Hypixel's renames.
     *
     * <p>The loadout item IS the loadout's helmet, but renamed ("Loadout 2", green) with the
     * listing as its lore - so {@link ItemStack#isSameItemSameComponents} against the genuinely
     * worn helmet ("Ancient Storm's Helmet ...") can never hold, and every capture gate built on it
     * was stuck shut: chest, legs and boots were never captured again, and those cells fell back to
     * catalogue icons in default colours (the un-dyed boots / missing black-ice complaints).
     * Hypixel carries the piece's SkyBlock {@code uuid} through the rename inside
     * {@code custom_data} (verified in the cached "Loadout 2" head: {@code uuid}, {@code id},
     * {@code skin} all intact) - unique per physical item, the identity a rename cannot touch.
     * Stacks without a uuid fall back to strict component equality.
     */
    private static boolean sameSkyblockPiece(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        String uuidA = SkyblockItem.extraAttributes(a).getStringOr("uuid", "");
        String uuidB = SkyblockItem.extraAttributes(b).getStringOr("uuid", "");
        return uuidA.isEmpty() || uuidB.isEmpty()
                ? ItemStack.isSameItemSameComponents(a, b) : uuidA.equals(uuidB);
    }

    /**
     * Comparable item name: glyphs, stars AND superscript digits stripped (the master-star tier
     * "⁵" is a Unicode number, so a {@code \p{N}} class kept it and "... Belt ⁵" no longer ended
     * with "belt" - that broke most equipment detection). SkyBlock names are ASCII, so ASCII-only
     * is the safe filter.
     */
    private static String cleanName(String name) {
        return name.replaceAll("[^A-Za-z0-9' -]", " ").replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Persistence: real stacks (dyes, applied skins and all components) survive a restart. Stacks
    // are serialised through ItemStack.CODEC with registry-aware ops, so nothing is lost.
    // ------------------------------------------------------------------

    private volatile boolean cacheLoaded;
    /** When a write may happen; see {@link SaveThrottle}. */
    private final SaveThrottle saveThrottle = new SaveThrottle();

    /**
     * The loadout number currently on the body, or {@code -1}. This is the anchor of the whole
     * armor problem: everything else the menu offers has to be matched to a loadout somehow, but the
     * gear you are wearing after equipping slot N simply <b>is</b> slot N's gear.
     */
    private volatile int equippedSlot = -1;

    /** Until when the worn gear is copied into {@link #equippedSlot} after an equip click. */
    private volatile long captureUntil;

    /**
     * How long an equip click keeps capturing. Long enough for the server to actually swap the
     * armor (and for a lagging piece to arrive), short enough that gear you change by hand
     * afterwards is not silently recorded as belonging to that loadout.
     */
    private static final long CAPTURE_WINDOW_MS = 15_000L;

    private long lastCaptureAt;

    // ------------------------------------------------------------------
    // The HUD widget's view of all this ({@link LoadoutHud})
    // ------------------------------------------------------------------

    /**
     * Which loadout the HUD widget is showing.
     *
     * <p>The widget keeps <b>no data of its own</b> and draws no card of its own either: it asks for
     * this, then hands the rectangle back to {@link #renderCard}, which runs the very same
     * {@link #drawCard} the grid does. Same cache ({@code loadouts_cache.json}, per profile), same
     * armor with the same dyes and applied skins, same layout - one card, two places.
     *
     * @param slot the loadout number, or {@code -1} for the card built from the gear on your body
     *             because no cached loadout matches it
     */
    public record Equipped(int slot) {
    }

    /** How often the widget's snapshot (and its "changed" hint) is rebuilt. */
    private static final long WIDGET_REFRESH_MS = 400L;

    /**
     * The slot the Loadouts menu last showed as equipped - or the slot last equipped through the
     * overlay - persisted as {@code _equipped} in the cache and read back on startup. {@code -1}
     * when nothing is known, or when the last visit showed no loadout equipped.
     *
     * <p><b>This is the widget's answer, full stop</b> - see {@link LoadoutWidgetDecision}. It only
     * changes when the menu is read again ({@link #adoptMenuRead}) or an equip click goes through
     * the overlay; nothing the body does moves it.
     *
     * <p>It is stored in {@code loadouts_cache.json} rather than in a file of its own. A slot number
     * belongs to one account plus one SkyBlock profile, exactly like the loadouts it indexes, and
     * the two are only ever meaningful together: a separate file would have to be scoped the same
     * way, kept in step by hand, and could disagree with the cache it points into. One file cannot.
     */
    private volatile int rememberedSlot = -1;

    /** When {@link #rememberedSlot} was learned (epoch ms, persisted as {@code _equippedAt}), or 0. */
    private volatile long rememberedAt;

    /**
     * Takes a new answer from the menu or an equip click. Written at once (one-shot): a throttled
     * request is only deferred, but this is the one fact the widget stands on, and the deferred
     * flush needs a later refresh to happen before the game closes.
     */
    private void adoptEquipped(int slot, String why) {
        boolean changed = rememberedSlot != slot;
        rememberedSlot = slot;
        rememberedAt = System.currentTimeMillis();
        widgetRefreshedAt = 0;   // the next draw asks again instead of showing the old card 400 ms
        if (changed) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Loadouts] equipped loadout is now {} ({})",
                    slot > 0 ? "slot " + slot : "none", why);
        }
        saveCache(true);
    }

    /**
     * One read of an open Loadouts page: the slot it marks as equipped, and whether the stored slot
     * was among the loadouts on it. See {@link LoadoutWidgetDecision#afterMenuRead}.
     */
    private void adoptMenuRead(int markedOnPage, boolean storedOnPage) {
        if (markedOnPage > 0) {
            confirmSession("the Loadouts menu");
        }
        int next = LoadoutWidgetDecision.afterMenuRead(rememberedSlot, markedOnPage, storedOnPage);
        // Re-marking the same slot refreshes its age, but not on every read: the page is re-read
        // every frame while open, and each adoption is a disk write.
        if (next != rememberedSlot
                || (next > 0 && System.currentTimeMillis() - rememberedAt > SaveThrottle.INTERVAL_MS)) {
            adoptEquipped(next, "from the Loadouts menu");
        }
    }

    /**
     * The loadout the Loadouts menu last said is equipped, or -1. A change in this number is a real
     * loadout switch - it only ever moves when the menu or an equip click says so.
     */
    public int provenSlot() {
        loadCache();
        return rememberedSlot;
    }

    private long widgetRefreshedAt;
    private volatile Equipped widgetSnapshot;

    /**
     * Whether the live stacks off your body may stand in for the card's armour
     * ({@link #displayPieces}). True for the body card, and for a loadout card only while the worn
     * armour still matches it - then the two are the same stacks and the live ones are cheaper to
     * draw. Once it no longer matches, the card draws the loadout's own armour.
     */
    private volatile boolean widgetShowsBody = true;

    /** Whether the worn armour no longer matches the loadout on the card; drives the dim hint. */
    private volatile boolean widgetChanged;

    /**
     * The loadout the widget shows, or null when nothing can be said about one yet.
     *
     * <p>The slot is {@link #rememberedSlot} - what the Loadouts menu last said - and nothing else;
     * the body is only compared against it for the "changed since last Loadouts visit" hint. See
     * {@link LoadoutWidgetDecision} for why armour matching no longer decides anything here.
     */
    public Equipped equipped() {
        long now = System.currentTimeMillis();
        if (now - widgetRefreshedAt < WIDGET_REFRESH_MS) {
            return widgetSnapshot;
        }
        widgetRefreshedAt = now;
        loadCache();   // the widget has to work in a session where the menu was never opened
        retryPendingDecodes();
        LoadoutWidgetDecision.Choice choice = LoadoutWidgetDecision.decide(rememberedSlot, this::hasCard);
        if (choice.slot() > 0) {
            Entry entry = slots.get(choice.slot());
            Diff diff = wornDiff(entry);
            boolean hint = session.hint(diff != null, now);
            if (hint && !widgetChanged) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Loadouts] changed: piece={} worn uuid={} stored uuid={} reason={}",
                        ARMOR_NAMES[diff.piece()], diff.wornUuid().isEmpty() ? "none" : diff.wornUuid(),
                        diff.storedUuid().isEmpty() ? "none" : diff.storedUuid(),
                        diff.verdict().name().toLowerCase(Locale.ROOT));
            }
            widgetChanged = hint;
            widgetShowsBody = WidgetSession.showsLiveBody(session.confirmed(), complete(entry), hint);
            widgetSnapshot = new Equipped(choice.slot());
        } else {
            widgetChanged = false;
            widgetShowsBody = true;
            bodyEntry = bodyEntry();
            widgetSnapshot = bodyEntry == null ? null : new Equipped(-1);
        }
        logWidgetChoice(choice, widgetSnapshot);
        flushDeferred();
        return widgetSnapshot;
    }

    /** This session's confirmation of the worn loadout ({@link WidgetSession}). */
    private final WidgetSession session = new WidgetSession();

    private void confirmSession(String why) {
        if (session.confirm(why)) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Loadouts] worn loadout confirmed this session "
                    + "by {} - the widget now shows the live body", why);
            widgetRefreshedAt = 0;
        }
    }

    private static final String[] ARMOR_NAMES = {"helmet", "chest", "legs", "boots"};

    /** The first worn piece that contradicts the card, with the evidence. */
    private record Diff(int piece, String wornUuid, String storedUuid, PieceCompare.Verdict verdict) {
    }

    /** Uuids seen on the body last refresh, for the "piece lost its uuid" diagnostic. */
    private final String[] lastWornUuid = {"", "", "", ""};
    private boolean uuidLossLogged;

    /**
     * The first worn piece that contradicts what the card stores, or null. A missing piece or a worn
     * piece without its uuid is no evidence ({@link PieceCompare}).
     */
    private Diff wornDiff(Entry entry) {
        ItemStack[] worn = wornArmor();
        if (entry == null || worn == null) {
            return null;
        }
        Diff first = null;
        for (int p = 0; p < ARMOR_SLOTS.length; p++) {
            ItemStack stored = p == 0 && entry.pieces[0].isEmpty() ? entry.bodyHelmet : entry.pieces[p];
            String wornUuid = uuidOf(worn[p]);
            if (wornUuid.isEmpty() && !worn[p].isEmpty() && !lastWornUuid[p].isEmpty() && !uuidLossLogged) {
                uuidLossLogged = true;
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Loadouts] worn {} seen without its uuid "
                        + "(it had {} a moment before)", ARMOR_NAMES[p], lastWornUuid[p]);
            }
            lastWornUuid[p] = wornUuid;
            PieceCompare.Verdict verdict = PieceCompare.compare(worn[p].isEmpty(),
                    stored == null || stored.isEmpty(), wornUuid, uuidOf(stored),
                    stored != null && !stored.isEmpty() && samePieceLook(worn[p], stored));
            if (first == null && (verdict == PieceCompare.Verdict.UUID || verdict == PieceCompare.Verdict.LOOK)) {
                first = new Diff(p, wornUuid, uuidOf(stored), verdict);
            }
        }
        return first;
    }

    /** Whether a cached card can be drawn without holes: the menu's item and a helmet. */
    private static boolean complete(Entry entry) {
        return entry != null && !entry.loadoutItem.isEmpty()
                && !(entry.pieces[0].isEmpty() && entry.bodyHelmet.isEmpty());
    }

    /**
     * Whether {@code slot} has enough to draw its card. Not the loadout item alone: a slot whose item
     * was lost or has not decoded yet still has its number, its rename, its armour and its pet, and
     * that card is the right answer - the body card is for "no remembered slot at all".
     */
    private boolean hasCard(int slot) {
        Entry entry = slots.get(slot);
        if (entry == null) {
            return false;
        }
        int armour = 0;
        for (ItemStack piece : entry.pieces) {
            armour += piece.isEmpty() ? 0 : 1;
        }
        return LoadoutWidgetDecision.cardable(!entry.loadoutItem.isEmpty(), armour,
                entry.rawLoadoutItem != null, customName(slot) != null, entry.pet != null);
    }

    /** Stacks still waiting to decode from the file, and how many refreshes have retried them. */
    private int pendingDecodes;
    private int decodeRetries;
    private static final int MAX_DECODE_RETRIES = 50;

    /**
     * Retries the stacks {@link #loadCache} could not decode. Registries that were not ready at
     * load (or belonged to a world that has since gone) are the usual reason, and the same rule as
     * a failed file read applies: a failure is not latched.
     */
    private synchronized void retryPendingDecodes() {
        if (pendingDecodes == 0 || decodeRetries >= MAX_DECODE_RETRIES) {
            return;
        }
        var ops = jsonOps();
        if (ops == null) {
            return;
        }
        decodeRetries++;
        int failuresBefore = decodeFailures;
        int left = 0;
        int resolved = 0;
        for (Entry entry : slots.values()) {
            for (int p = 0; p < 4; p++) {
                if (entry.rawPieces[p] == null) {
                    continue;
                }
                ItemStack stack = decodeStack(ops, entry.rawPieces[p]);
                if (stack.isEmpty()) {
                    left++;
                } else {
                    entry.pieces[p] = stack;
                    entry.realPieces[p] = true;
                    entry.rawPieces[p] = null;
                    resolved++;
                }
            }
            if (entry.rawLoadoutItem != null) {
                ItemStack stack = decodeStack(ops, entry.rawLoadoutItem);
                if (stack.isEmpty()) {
                    left++;
                } else {
                    entry.loadoutItem = stack;
                    entry.equipment = equipmentOf(strippedLore(stack));
                    entry.rawLoadoutItem = null;
                    resolved++;
                }
            }
        }
        decodeFailures = failuresBefore;   // retries are not new failures of the load pass
        pendingDecodes = left;
        if (resolved > 0 || left == 0 || decodeRetries == MAX_DECODE_RETRIES) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Loadouts] decode retry {}: {} stack(s) resolved, {} still pending",
                    decodeRetries, resolved, left);
        }
    }

    /** Whether the widget's loadout card no longer matches the armour you are wearing. */
    boolean widgetChanged() {
        return widgetChanged;
    }

    /** The last answer reported by {@link #logWidgetChoice}, so a steady answer is logged once. */
    private String loggedWidgetChoice;

    /**
     * One line whenever the widget's answer changes: the snapshot that is actually returned to the
     * draw and where it came from - logged from the returned value itself, so the log and the
     * screen cannot disagree.
     */
    private void logWidgetChoice(LoadoutWidgetDecision.Choice choice, Equipped snapshot) {
        String key = choice.source() + ":" + (snapshot == null ? "none" : snapshot.slot())
                + ":" + widgetChanged;
        if (key.equals(loggedWidgetChoice)) {
            return;
        }
        loggedWidgetChoice = key;
        String what;
        if (snapshot == null) {
            what = "nothing";
        } else if (snapshot.slot() > 0) {
            what = "slot " + snapshot.slot() + " (from the Loadouts menu, "
                    + (rememberedAt > 0 ? age(System.currentTimeMillis() - rememberedAt) + " ago" : "age unknown")
                    + (widgetChanged ? "; armour changed since" : "") + ")";
        } else {
            what = "the body card (" + (rememberedSlot > 0 ? "slot " + rememberedSlot + " has no card: "
                    + entryState(slots.get(rememberedSlot))
                    : "the Loadouts menu showed none equipped, or was never opened") + ")";
        }
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Loadouts] widget shows {}; {} loadout(s) cached",
                what, slots.size());
    }

    /** What a cache entry holds, for the widget log line. */
    private static String entryState(Entry entry) {
        if (entry == null) {
            return "no cache entry";
        }
        int armour = 0;
        for (ItemStack piece : entry.pieces) {
            armour += piece.isEmpty() ? 0 : 1;
        }
        return "item " + (!entry.loadoutItem.isEmpty() ? "yes" : entry.rawLoadoutItem != null
                ? "undecoded" : "no") + ", armour " + armour + "/4, pet " + (entry.pet != null);
    }

    /** "3h 12m" / "4m" / "20s" for the widget log. */
    private static String age(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) {
            return s + "s";
        }
        long m = s / 60;
        return m < 60 ? m + "m" : (m / 60) + "h " + (m % 60) + "m";
    }

    /** The cached loadout whose own helmet is the one on the player's head, or -1. */
    private int resolveWornSlotByHelmet() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return -1;
        }
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (head == null || head.isEmpty()) {
            return -1;
        }
        for (int n = 1; n <= maxSlot; n++) {
            Entry entry = slots.get(n);
            if (entry == null) {
                continue;
            }
            // The captured helmet when there is one - and the loadout's own menu item when there is
            // not, which is the case this exists for. Armor is only ever learned while a loadout is
            // on your body or its menu is open, so a cache can hold a loadout with no armor at all;
            // it always holds the loadout item, because that is where the name, the equipment and
            // the HOTM/HOTF values come from. Asking only pieces[0] made the widget need a menu
            // visit per session to know what it was already holding, and until it got one the card
            // fell back to the body - no name, no equipment, no tunings.
            // The uuid compare first, deliberately: it settles 26 of 27 loadouts on a string
            // equality, and carriesOwnHelmet strips the whole lore to answer. Only the one loadout
            // whose uuid already matched pays for that.
            if (sameSkyblockPiece(head, entry.pieces[0])
                    || (sameSkyblockPiece(head, entry.loadoutItem)
                            && carriesOwnHelmet(entry.loadoutItem, entry.lore()))) {
                return n;
            }
        }
        return -1;
    }

    /** The armor slots in {@link #ARMOR_KEYS} order (helmet first), for reading them off the body. */
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** The card built from the body, kept between refreshes; only used while no loadout matches. */
    private volatile Entry bodyEntry;

    /**
     * A card for the gear on your body, for when no cached loadout matches it.
     *
     * <p>Without this the widget draws nothing at all until the Loadouts menu has been opened once,
     * and "nothing at all" is indistinguishable from a broken feature. What you are wearing is
     * always knowable, so there is always a real card to show: your armor, your player model and
     * your active pet ({@link sbs.modid.client.ui.hud.logic.PetTracker}, the same source the Active
     * Pet card uses). It has no loadout item, so the equipment and HOTM/HOTF/Power/Tuning boxes
     * stand empty until the menu has been read once and the real loadout takes over.
     */
    private Entry bodyEntry() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        Entry entry = new Entry();
        boolean any = false;
        for (int p = 0; p < ARMOR_SLOTS.length; p++) {
            ItemStack worn = player.getItemBySlot(ARMOR_SLOTS[p]);
            if (worn != null && !worn.isEmpty()) {
                entry.pieces[p] = worn;
                entry.realPieces[p] = true;
                any = true;
            }
        }
        var pet = sbs.modid.client.ui.hud.logic.PetTracker.getInstance();
        if (pet.hasPet()) {
            entry.pet = "[Lvl " + pet.level() + "] " + pet.name();
        }
        return any || entry.pet != null ? entry : null;
    }

    /**
     * Draws the loadout the widget asked about as a card, at {@code (x, y)} and in the given size -
     * <b>the same {@link #drawCard} the grid uses</b>, so the widget is that card, not a lookalike
     * that has to be kept in step with it. Interaction-only chrome (the rename pencil, the page
     * chip) is left off: there is nothing to click on the HUD.
     *
     * @param screen where the card's {@code (x, y)} actually lands on screen and how much it is
     *               scaled, as {@code {screenX, screenY, scale}} - the GUI editor's transform,
     *               which the player model has to be placed through by hand
     */
    public void renderCard(GuiGraphicsExtractor g, Equipped loadout, int x, int y,
                           int cardW, int cardH, float[] screen) {
        Entry entry = loadout.slot() > 0 ? slots.get(loadout.slot()) : bodyEntry;
        if (entry == null) {
            return;
        }
        ensurePreview(Minecraft.getInstance());
        // Never the "equipped" green: in the grid that colour tells one card apart from 26 others,
        // and here every card is that one - it would only mean "this widget is a widget", in a
        // colour that is not the SBS theme.
        drawCard(g, Minecraft.getInstance().font, entry, loadout.slot(), -1, x, y, cardW, cardH,
                false, screen, false, NO_MOUSE, NO_MOUSE);
    }

    /** Mouse position for a card nobody can point at - far enough out that no box hovers. */
    private static final int NO_MOUSE = Integer.MIN_VALUE / 2;

    /** Whether the HUD widget wants the cache kept fed, even with the card grid switched off. */
    private static boolean widgetEnabled() {
        return ConfigManager.getInstance().get().skyblockMenu.loadoutWidget.shows();
    }

    private static java.nio.file.Path cacheFile() {
        return sbs.modid.client.core.config.SBSFiles.loadoutsCacheFile();
    }

    /** The registry set {@link #currentOps} was built for. */
    private static net.minecraft.core.RegistryAccess opsAccess;
    private static com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> currentOps;
    /**
     * The previous world's ops. A stack harvested before a server hop still points into that
     * world's registries, and only these can encode it. Holds one old registry set alive - the
     * price of not writing {@code null} over the loadout item (see {@link StackJsonMemory}).
     */
    private static com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> previousOps;

    /** Remembers each stack's last good JSON so an encode failure falls back instead of nulling. */
    private static final StackJsonMemory<ItemStack, com.google.gson.JsonElement> STACK_JSON =
            new StackJsonMemory<>();

    /** Registry-aware JSON ops; null while no world is loaded (the codec needs the registries). */
    private static synchronized com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> jsonOps() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        net.minecraft.core.RegistryAccess access = level.registryAccess();
        if (access != opsAccess) {
            previousOps = currentOps;
            currentOps = net.minecraft.resources.RegistryOps.create(
                    com.mojang.serialization.JsonOps.INSTANCE, access);
            opsAccess = access;
        }
        return currentOps;
    }

    /**
     * How many stacks failed to ENCODE during the current {@link #writeCache()} pass, and the first
     * error verbatim.
     *
     * <p>The decode side has counted these since the day a silent {@code orElse(EMPTY)} was found to
     * turn "the cache loaded" into "every card is blank". The encode side kept the same swallow, and
     * it is the worse of the two: a decode failure loses a read, an encode failure writes a
     * {@code null} over data that was on disk a moment ago and is now gone for good.
     */
    private static int encodeFailures;
    private static String firstEncodeError;

    private static com.google.gson.JsonElement encodeStack(
            com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        var prior = previousOps;
        var result = STACK_JSON.encode(stack,
                st -> {
                    var encoded = ItemStack.CODEC.encodeStart(ops, st);
                    if (encoded.result().isEmpty() && firstEncodeError == null) {
                        firstEncodeError = encoded.error().map(Object::toString).orElse("unknown");
                    }
                    return encoded.result();
                },
                prior == null || prior == ops ? null
                        : st -> ItemStack.CODEC.encodeStart(prior, st).result());
        switch (result.via()) {
            case PREVIOUS -> encodedViaPrevious++;
            case REMEMBERED -> encodedViaRemembered++;
            case FAILED -> encodeFailures++;
            default -> { }
        }
        return result.json() == null ? com.google.gson.JsonNull.INSTANCE : result.json();
    }

    /** Stacks the current world could not encode but the previous world's registries could. */
    private static int encodedViaPrevious;
    /** Stacks neither world could encode, written as the JSON they were last read or written as. */
    private static int encodedViaRemembered;

    private ItemStack decodeStack(
            com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops,
            com.google.gson.JsonElement json) {
        if (json == null || json.isJsonNull()) {
            return ItemStack.EMPTY;
        }
        var parsed = ItemStack.CODEC.parse(ops, json);
        ItemStack stack = parsed.result().orElse(ItemStack.EMPTY);
        if (stack.isEmpty()) {
            // An EMPTY for JSON that was there is a failure too, with or without a codec error -
            // "parsed fine to nothing" is exactly the silent case that blanked cards before.
            decodeFailures++;
            if (firstDecodeError == null) {
                firstDecodeError = parsed.error().map(e -> e.message())
                        .orElse("decoded to an empty stack without an error");
            }
            return ItemStack.EMPTY;
        }
        STACK_JSON.remember(stack, json);
        return stack;
    }

    /** Save the loadouts to the profile file immediately (on a profile switch). */
    @Override
    public void flushProfile() {
        writeCache();
    }

    /** Drop the cached loadouts and reload them from the (now current) profile's file. */
    @Override
    public void reloadProfile() {
        synchronized (this) {
            slots.clear();
            petStacks.clear();
            java.util.Arrays.fill(extraSlotIcons, ItemStack.EMPTY);
            // Profile state, like everything else here: another profile's slot number would name a
            // loadout this one does not have.
            rememberedSlot = -1;
            rememberedAt = 0;
            cacheLoaded = false;
            session.reset();
            pendingDecodes = 0;
            decodeRetries = 0;
        }
        loadCache();
    }

    /** Writes every entry that carries real data; throttled like the storage cache. */
    /**
     * Throttled write. A request that lands inside the throttle is <b>remembered</b>, not dropped:
     * the caller's change is often something that resolves once and never reports itself again (a
     * catalogue icon arriving asynchronously), so a silently swallowed write meant that piece was
     * lost until something else happened to mark the cache dirty.
     */
    private void saveCache(boolean oneShot) {
        long now = System.currentTimeMillis();
        if (saveThrottle.request(oneShot, now) && writeCache()) {
            saveThrottle.written(now);   // only spend the throttle once a world+registries let us write
        }
    }

    /** Writes a change the throttle deferred, once its interval has passed. No change, no write. */
    private void flushDeferred() {
        long now = System.currentTimeMillis();
        if (saveThrottle.pending(now) && writeCache()) {
            saveThrottle.written(now);
        }
    }

    /** Serialises the loadouts to the current profile file. Returns false when no world is loaded yet. */
    private boolean writeCache() {
        var ops = jsonOps();
        if (ops == null) {
            return false;
        }
        // NOTHING IN MEMORY IS AUTHORITATIVE UNTIL THE FILE HAS BEEN READ.
        //
        // This is the bug that emptied a real player's cache. reloadProfile() clears every map and
        // then calls loadCache(), which returns without loading when no world is up yet - a profile
        // switch during login or a server hop lands exactly there. The maps are now empty and
        // cacheLoaded is false, and the next thing to mark the cache dirty (a pet harvest, an icon
        // resolving, healWornLoadout) wrote that emptiness straight over 27 good loadouts. What
        // survived was whatever had been re-harvested since, which is why the slots the player had
        // looked at kept their data and the rest lost their loadout item and their helmet.
        //
        // A save is a statement about the whole profile, so it may only be made by a session that
        // has read the whole profile. Refusing costs a deferred write; not refusing costs the file.
        if (!cacheLoaded) {
            return false;
        }
        encodeFailures = 0;
        encodedViaPrevious = 0;
        encodedViaRemembered = 0;
        firstEncodeError = null;
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty("_version", CACHE_VERSION);
        com.google.gson.JsonArray extraIcons = new com.google.gson.JsonArray();
        for (ItemStack stack : extraSlotIcons) {
            extraIcons.add(encodeStack(ops, stack));
        }
        root.add("_extraIcons", extraIcons);
        com.google.gson.JsonObject pets = new com.google.gson.JsonObject();
        for (var pet : petStacks.entrySet()) {
            pets.add(pet.getKey(), encodeStack(ops, pet.getValue()));
        }
        root.add("_pets", pets);
        // Which slot the Loadouts menu last said is equipped - the widget's answer until the menu is
        // opened again (see rememberedSlot). Absent = none known / none equipped.
        if (rememberedSlot > 0) {
            root.addProperty("_equipped", rememberedSlot);
            root.addProperty("_equippedAt", rememberedAt);
        }
        for (var mapEntry : slots.entrySet()) {
            Entry entry = mapEntry.getValue();
            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            com.google.gson.JsonArray pieces = new com.google.gson.JsonArray();
            com.google.gson.JsonArray real = new com.google.gson.JsonArray();
            for (int p = 0; p < 4; p++) {
                // real[] describes what the file actually carries, not what memory believed. The
                // damaged cache had real=[true,true,true,true] beside a null helmet: the flag was
                // written from the in-memory field while the stack beside it had failed to encode,
                // so the file asserted armour it did not contain and the next load trusted it.
                com.google.gson.JsonElement encoded = entry.realPieces[p]
                        ? encodeStack(ops, entry.pieces[p])
                        : com.google.gson.JsonNull.INSTANCE;
                boolean isReal = entry.realPieces[p] && !encoded.isJsonNull();
                if (encoded.isJsonNull() && entry.pieces[p].isEmpty() && entry.rawPieces[p] != null) {
                    encoded = entry.rawPieces[p];   // not decoded yet: the file's own data goes back
                    isReal = true;
                }
                pieces.add(encoded);
                real.add(isReal);
            }
            json.add("pieces", pieces);
            json.add("real", real);
            json.add("loadoutItem", entry.loadoutItem.isEmpty() && entry.rawLoadoutItem != null
                    ? entry.rawLoadoutItem
                    : encodeStack(ops, entry.loadoutItem));
            if (entry.pet != null) {
                json.addProperty("pet", entry.pet);
            }
            json.addProperty("page", entry.page);
            if (!entry.bodyHelmet.isEmpty()) {
                json.add("bodyHelmet", encodeStack(ops, entry.bodyHelmet));
            }
            root.add(String.valueOf(mapEntry.getKey()), json);
        }
        // The stacks were encoded above, on this thread (they are live). Turning the finished JSON
        // into text and writing it - ~1 MB on a full profile - happens on the ordered IO thread.
        java.nio.file.Path path = cacheFile();
        com.google.gson.JsonObject snapshot = root;
        sbs.modid.client.core.async.SbsExecutors.io().execute(() -> {
            try {
                sbs.modid.client.core.config.SBSFiles.ensureParent(path);
                try (var writer = java.nio.file.Files.newBufferedWriter(path)) {
                    sbs.modid.client.core.config.SBSFiles.GSON.toJson(snapshot, writer);
                }
                sbs.modid.client.core.perf.Perf.countDiskWrite();
            } catch (Exception e) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn("[SBS] Loadout cache write failed: {}", e.toString());
            }
        });
        // Diagnostic heartbeat (throttled with the save): what the harvest currently knows.
        int extraIconCount = 0;
        for (ItemStack stack : extraSlotIcons) {
            if (!stack.isEmpty()) {
                extraIconCount++;
            }
        }
        // Which loadout the body proves it is wearing is in here on purpose: "the widget says
        // Equipped instead of my loadout's name" and "this loadout never learns its dyes" are both
        // that number being -1, and nothing else in the log said so.
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Loadouts] cache: {} loadout(s), {} pet(s), extra icons {}/{}, equipped slot {}{}",
                slots.size(), petStacks.size(), extraIconCount, EXTRA_KEYS.length,
                rememberedSlot,
                (encodeFailures == 0 ? "" : " - WROTE " + encodeFailures
                        + " null(s) for stacks that failed to encode; first: " + firstEncodeError)
                        + (encodedViaPrevious + encodedViaRemembered == 0 ? ""
                                : " - " + encodedViaPrevious + " via the previous world's registries, "
                                        + encodedViaRemembered + " kept as last written"));
        return true;
    }

    /**
     * How many stacks failed to decode during the current {@link #loadCache()} pass, plus the first
     * codec error verbatim. A decode failure used to vanish inside {@code result().orElse(EMPTY)} -
     * the cache would "load" and every card would still be blank, which on the widget reads as the
     * loadout losing its name, and nothing anywhere said why. Nothing fails silently.
     */
    private int decodeFailures;
    private String firstDecodeError;

    /** Loads the persisted loadouts once a world (and its registries) is available. */
    private synchronized void loadCache() {
        if (cacheLoaded) {
            return;
        }
        var ops = jsonOps();
        if (ops == null) {
            return;   // no world yet - retry on the next frame
        }
        cacheLoaded = true;
        decodeFailures = 0;
        firstDecodeError = null;
        try {
            java.nio.file.Path path = cacheFile();
            if (!java.nio.file.Files.exists(path)) {
                // A real state, not an error: a fresh profile has no cache until its menu is opened
                // once. Logged because "no file here" and "file here but unreadable" look identical
                // on screen, and this line is what tells them apart in a report.
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Loadouts] no cache yet for profile '{}'",
                        path.getParent().getFileName());
                return;
            }
            com.google.gson.JsonObject root;
            try (var reader = java.nio.file.Files.newBufferedReader(path)) {
                root = sbs.modid.client.core.config.SBSFiles.GSON.fromJson(reader,
                        com.google.gson.JsonObject.class);
            }
            if (root == null) {
                return;
            }
            if (root.has("_extraIcons")) {
                var icons = root.getAsJsonArray("_extraIcons");
                for (int e = 0; e < EXTRA_KEYS.length && e < icons.size(); e++) {
                    ItemStack stack = decodeStack(ops, icons.get(e));
                    if (!stack.isEmpty()) {
                        extraSlotIcons[e] = stack;
                    }
                }
            }
            if (root.has("_equipped")) {
                rememberedSlot = root.get("_equipped").getAsInt();
                rememberedAt = root.has("_equippedAt") ? root.get("_equippedAt").getAsLong() : 0L;
            }
            if (root.has("_pets")) {
                for (var pet : root.getAsJsonObject("_pets").entrySet()) {
                    ItemStack stack = decodeStack(ops, pet.getValue());
                    if (!stack.isEmpty()) {
                        petStacks.put(pet.getKey(), stack);
                    }
                }
            }
            // Armor written by an older build was bound to a menu COLUMN, and a column could belong
            // to two loadouts at once - loadouts 2 and 9 sit one row apart in the same one. Those
            // stacks are "real", so the never-downgrade rule would defend the wrong skin forever;
            // dropping them once makes the next menu visit re-harvest under the corrected rules.
            // Pets and the generic slot icons were never column-bound and are kept.
            int undecoded = 0;
            List<Integer> noItemInFile = new ArrayList<>();
            List<Integer> itemUndecoded = new ArrayList<>();
            boolean dropArmor = !root.has("_version") || root.get("_version").getAsInt() < CACHE_VERSION;
            if (dropArmor) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Loadouts] cache predates the per-slot armor binding - re-harvesting armor");
            }
            for (var jsonEntry : root.entrySet()) {
                int number;
                try {
                    number = Integer.parseInt(jsonEntry.getKey());
                } catch (NumberFormatException bad) {
                    continue;
                }
                com.google.gson.JsonObject json = jsonEntry.getValue().getAsJsonObject();
                Entry entry = slots.computeIfAbsent(number, n -> new Entry());
                var pieces = dropArmor ? null : json.getAsJsonArray("pieces");
                var real = json.getAsJsonArray("real");
                for (int p = 0; p < 4 && pieces != null && p < pieces.size(); p++) {
                    var pieceJson = pieces.get(p);
                    ItemStack stack = decodeStack(ops, pieceJson);
                    if (!stack.isEmpty()) {
                        entry.pieces[p] = stack;
                        entry.realPieces[p] = real == null || p >= real.size()
                                || real.get(p).getAsBoolean();
                    } else if (pieceJson != null && !pieceJson.isJsonNull()) {
                        entry.rawPieces[p] = pieceJson;
                        undecoded++;
                    }
                }
                var itemJson = json.get("loadoutItem");
                boolean itemInFile = itemJson != null && !itemJson.isJsonNull();
                ItemStack loadoutItem = decodeStack(ops, itemJson);
                if (entry.loadoutItem.isEmpty() && !loadoutItem.isEmpty()) {
                    entry.loadoutItem = loadoutItem;
                    entry.equipment = equipmentOf(strippedLore(loadoutItem));
                } else if (loadoutItem.isEmpty() && itemInFile) {
                    entry.rawLoadoutItem = itemJson;
                    undecoded++;
                }
                if (!itemInFile) {
                    noItemInFile.add(number);
                } else if (loadoutItem.isEmpty()) {
                    itemUndecoded.add(number);
                }
                // Self-repair: the stored helmet MUST be this loadout's own item. If it is not, an
                // older build wrote another loadout's gear into this slot - and then the whole set
                // came from there, not just the helmet. Drop all four so they are learned again
                // instead of defending armor that provably belongs to someone else. Targeted on
                // purpose: slots that are fine keep their real stacks.
                if (!entry.pieces[0].isEmpty() && !loadoutItem.isEmpty()
                        && !ItemStack.isSameItemSameComponents(entry.pieces[0], loadoutItem)
                        && !helmetFromLoadoutItem(loadoutItem, strippedLore(loadoutItem)).isEmpty()) {
                    for (int p = 0; p < 4; p++) {
                        entry.pieces[p] = ItemStack.EMPTY;
                        entry.realPieces[p] = false;
                    }
                    sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                            "[SBS][Loadouts] slot {} held armor from another loadout - relearning", number);
                }
                if (json.has("pet")) {
                    entry.pet = json.get("pet").getAsString();
                }
                if (json.has("page")) {
                    entry.page = json.get("page").getAsInt();
                }
                if (json.has("bodyHelmet") && entry.loadoutItem.isEmpty()) {
                    entry.bodyHelmet = decodeStack(ops, json.get("bodyHelmet"));
                }
                maxSlot = Math.max(maxSlot, number);
            }
            // The load-side twin of the write heartbeat: what this profile's file actually yielded,
            // including the decode failures that used to disappear into empty stacks. "The widget
            // says Equipped after a restart" is diagnosed from exactly this line - either it never
            // printed (the load did not run), it names a different profile (the context pointed at
            // the wrong folder), or it counts failures (the file is unreadable to this build).
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Loadouts] loaded {} loadout(s), {} pet(s) from profile '{}', "
                            + "remembered slot " + rememberedSlot + "{}",
                    slots.size(), petStacks.size(), path.getParent().getFileName(),
                    decodeFailures == 0 ? "" : " - " + decodeFailures
                            + " stack(s) failed to decode; first: " + firstDecodeError);
            // Per slot, once: which loadouts have no loadout item in the FILE (a write lost it) and
            // which have one that did not decode (kept raw and retried) - the two look identical
            // on the widget and are opposite bugs.
            if (!noItemInFile.isEmpty() || !itemUndecoded.isEmpty()) {
                java.util.Collections.sort(noItemInFile);
                java.util.Collections.sort(itemUndecoded);
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Loadouts] loadout item missing from the file for slot(s) {}; "
                                + "in the file but not decoded for slot(s) {}",
                        noItemInFile, itemUndecoded);
            }
            pendingDecodes = undecoded;
        } catch (Exception e) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn("[SBS] Loadout cache read failed: {}", e.toString());
        }
    }

    /** A loadout item announces itself in its lore (equip hint or the armor listing). */
    private static boolean isLoadoutItem(String name, String allText) {
        String lower = allText.toLowerCase(Locale.ROOT);
        if (lower.contains("left-click to equip") || lower.contains("helmet:")) {
            return true;
        }
        // Empty slots keep a bare "Loadout N" name without the listing.
        return name.toLowerCase(Locale.ROOT).startsWith("loadout")
                && LOADOUT_NUMBER.matcher(name).find();
    }

    /**
     * Resolves one lore-listed armor piece ("Helmet: ✿ Ancient Diamond Necron Head ✪✪✪✪✪⁵") to a
     * renderable icon stack.
     *
     * <p>Resolution goes through the SkyBlock item <b>catalogue by display name</b> first: names
     * like "Necron's Leggings" have ids like POWER_WITHER_LEGGINGS, which no mechanical
     * name-to-id normalisation can produce – only the catalogue knows the mapping (and its icons
     * carry the real SkyBlock textures / colours). The reforge word is stripped for the lookup;
     * {@code normalizeName} remains the last resort for items the catalogue is missing.
     */
    private static ItemStack armorFromLore(List<String> lore, String key) {
        String value = loreValue(lore, key);
        if (value == null) {
            return ItemStack.EMPTY;
        }
        // ASCII-only clean: the superscript master-star tier (⁵) is a Unicode digit, so a
        // \p{L}\p{N} class kept it glued to the name and every lookup missed.
        String clean = value.replaceAll("[^A-Za-z0-9' -]", " ").replaceAll("\\s+", " ").trim();
        // Candidates: the full name, then with leading words dropped one by one. The fixed REFORGES
        // list misses many equipment reforges ("Primordial Manticore Claws"), so dropping words is
        // the only reforge-agnostic way to reach the base name. The full name is always tried
        // first (real item names win over their own suffixes), and a bare single word is never
        // tried ("Necklace" alone is too generic to trust).
        List<String> candidates = new ArrayList<>(4);
        candidates.add(clean);
        String rest = clean;
        for (int drop = 0; drop < 3; drop++) {
            int space = rest.indexOf(' ');
            if (space <= 0) {
                break;
            }
            rest = rest.substring(space + 1);
            if (rest.indexOf(' ') < 0) {
                break;
            }
            candidates.add(rest);
        }
        for (String candidate : candidates) {
            ItemStack resolved = iconByCatalogName(candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        for (String candidate : candidates) {
            String id = SkyblockItem.normalizeName(candidate);
            if (!id.isEmpty()) {
                ItemStack icon = SkyBlockItemIcons.getInstance().icon(id, candidate, 1);
                if (icon != null && !isBarrier(icon) && !icon.isEmpty()) {
                    return icon;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** Catalogue display-name lookup -> repo icon (real texture), or null. */
    private static ItemStack iconByCatalogName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        var entry = sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog.getInstance().byName(name);
        if (entry == null) {
            return null;
        }
        ItemStack icon = SkyBlockItemIcons.getInstance().icon(entry.id, entry.name, 1);
        return icon == null || isBarrier(icon) ? null : icon;
    }

    private static boolean isBarrier(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals("barrier");
    }

    /** The value after {@code key + ":"} in the lore, or null when absent / "None". */
    private static String loreValue(List<String> loreLines, String key) {
        for (String line : loreLines) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, key, 0, key.length())) {
                int colon = trimmed.indexOf(':');
                if (colon >= 0) {
                    String value = trimmed.substring(colon + 1).trim();
                    if (!value.isEmpty() && !value.equalsIgnoreCase("none")) {
                        return value;
                    }
                }
                return null;
            }
        }
        return null;
    }

    private static boolean[] equipmentOf(List<String> loreLines) {
        boolean[] present = new boolean[EQUIP_KEYS.length];
        for (int i = 0; i < EQUIP_KEYS.length; i++) {
            present[i] = loreValue(loreLines, EQUIP_KEYS[i]) != null;
        }
        return present;
    }

    /**
     * Which loadout is on the body.
     *
     * <p><b>Superseded for EQUIPPED as of 2026-08-08:</b> {@link #cachePage} decides that from the
     * helmet on your body, because the hint rule below stopped holding - the menu dump of that day
     * has "Left-click to equip!" on the loadout being worn as well. What is still read here is
     * {@link Status#EMPTY}, which is a property of the loadout and not of your body. The rest of this
     * note stays as the record of why the word "equipped" is not what to look for.
     *
     * <p><b>Hypixel never writes the word "equipped".</b> It marks the active loadout by leaving the
     * <i>"Left-click to equip!"</i> hint OFF it - there is nothing to click, because you are already
     * wearing it. So a filled loadout without that hint is the equipped one, and looking for the word
     * (as this did) meant no loadout was ever recognised as equipped: the card never lit up, the
     * "read the armor off your body" path never ran, and the only thing that knew which slot you wore
     * was the last equip click that happened to go through this overlay - stale the moment you
     * equipped anything any other way.
     *
     * <p>The word check is kept below it as a harmless fallback for a layout that does spell it out.
     */
    private static Status statusOf(String text, List<String> lore) {
        String lower = text.toLowerCase(Locale.ROOT);
        // An UNUSED loadout still prints the whole listing - every line just says "None" - so the
        // presence of "Helmet:" proves nothing. Hypixel spells the state out instead ("You must
        // customize this loadout before you can equip it!"), and the listing itself is all-None;
        // either signal is enough, and taking both means neither a reworded message nor an odd
        // loadout with a pet but no armor can make an empty slot look worn.
        if (lower.contains("must customize") || !hasAnyArmorListed(lore)) {
            return Status.EMPTY;
        }
        if (lower.contains("equipped") || lower.contains("selected")) {
            return Status.EQUIPPED;
        }
        return lower.contains("left-click to equip") ? Status.READY : Status.EQUIPPED;
    }

    /** Whether the lore names at least one real armor piece (a value other than "None"). */
    private static boolean hasAnyArmorListed(List<String> lore) {
        for (String key : ARMOR_KEYS) {
            String value = loreValue(lore, key);
            if (value != null && !value.isBlank() && !value.equalsIgnoreCase("None")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> strippedLore(ItemStack stack) {
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
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

    // ------------------------------------------------------------------
    // Rendering (top-most, called from the overlay TAIL hook)
    // ------------------------------------------------------------------

    private int cardW;
    private int cardH;
    private int gridLeft;
    private int gridTop;
    private int buttonsY;
    private int buttonsX;
    private int buttonW;
    private int buttonGap;
    private int navY;
    private int navButtonX;
    private int navButtonW;
    private int navGap;

    public void renderTopMost(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        String title = title(screen);
        // The HUD widget draws from this same cache, so the SCRAPE runs whenever either of the two
        // is on. With only the widget enabled the vanilla menu is left completely alone - it is
        // read as you page through it, and nothing is drawn over it.
        if (!enabled() && !widgetEnabled()) {
            return;
        }
        harvestPetsMenu(screen);   // the Pets menu is the one source of real pet heads (skins!)
        if (!isLoadoutsMenu(title)) {
            return;
        }
        cachePage(screen); // refresh continuously while open (server fills slots in)
        if (!enabled() || editMode || maxSlot == 0) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        g.nextStratum(); // above the container AND its tooltips

        int rows = (maxSlot + GRID_COLS - 1) / GRID_COLS;
        // Big, readable cards; the grid SCROLLS when it does not fit (never shrinks the cards).
        cardW = clamp((screen.width - 60) / GRID_COLS, 96, 150);
        cardH = cardW + 60;
        int gridW = GRID_COLS * cardW;
        int panelW = gridW + 16;
        int panelH = screen.height - 8;
        int x = (screen.width - panelW) / 2;
        int y = 4;
        int currentPage = currentPage(title);

        HudCard.draw(g, x, y, panelW, panelH, SBSTheme.PANEL_CORNER);
        g.centeredText(font, Component.literal("SBS Loadouts  •  Page " + currentPage + " open"),
                x + panelW / 2, y + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2, SBSTheme.ACCENT_BRIGHT);

        gridLeft = x + 8;
        gridTop = y + SBSTheme.HEADER_HEIGHT;
        buttonsY = y + panelH - SBSTheme.SEARCH_HEIGHT - 8;
        navY = buttonsY - SBSTheme.SEARCH_HEIGHT - 6;   // quick-nav row sits above Back / Close / Edit
        int viewBottom = navY - 6;
        int gridH = rows * cardH;
        maxScroll = Math.max(0, gridH - (viewBottom - gridTop));
        scroll = clamp(scroll, 0, maxScroll);
        gridViewBottom = viewBottom;

        ensurePreview(minecraft);
        Entry hovered = null;
        pieceTooltip = null;
        g.enableScissor(x, gridTop, x + panelW, viewBottom);
        for (int slot = 1; slot <= maxSlot; slot++) {
            int cx = gridLeft + ((slot - 1) % GRID_COLS) * cardW;
            int cy = gridTop - scroll + ((slot - 1) / GRID_COLS) * cardH;
            if (cy + cardH < gridTop || cy > viewBottom) {
                continue;
            }
            Entry entry = slots.get(slot);
            boolean hover = mouseX >= cx && mouseX < cx + cardW && mouseY >= cy && mouseY < cy + cardH
                    && mouseY >= gridTop && mouseY <= viewBottom;
            if (hover && entry != null) {
                hovered = entry;
            }
            drawCard(g, font, entry, slot, currentPage, cx, cy, cardW, cardH,
                    entry != null && entry.status() == Status.EQUIPPED, null, hover, mouseX, mouseY);
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int trackH = viewBottom - gridTop;
            int thumbH = Math.max(12, trackH * trackH / gridH);
            int thumbY = gridTop + (int) ((long) (trackH - thumbH) * scroll / maxScroll);
            g.fill(x + panelW - 5, gridTop, x + panelW - 2, viewBottom, SBSTheme.CARD_BG_DISABLED);
            g.fill(x + panelW - 5, thumbY, x + panelW - 2, thumbY + thumbH, SBSTheme.ACCENT);
        }

        // Quick-nav row: jump straight to the other loadout-related menus (Armor / Equipment / …).
        navGap = 4;
        int navCount = NAV_BUTTONS.length;
        navButtonW = (gridW - navGap * (navCount - 1)) / navCount;
        int navTotal = navButtonW * navCount + navGap * (navCount - 1);
        navButtonX = x + (panelW - navTotal) / 2;
        for (int i = 0; i < navCount; i++) {
            int bx = navButtonX + i * (navButtonW + navGap);
            boolean over = mouseX >= bx && mouseX < bx + navButtonW
                    && mouseY >= navY && mouseY <= navY + SBSTheme.SEARCH_HEIGHT;
            drawNavButton(g, font, bx, navY, navButtonW, NAV_BUTTONS[i][0], over);
        }

        // Bottom buttons: Back / Close / Edit.
        buttonW = 70;
        buttonGap = 8;
        int totalW = buttonW * 3 + buttonGap * 2;
        buttonsX = x + (panelW - totalW) / 2;
        drawButton(g, font, buttonsX, buttonsY, buttonW, "Back");
        drawButton(g, font, buttonsX + buttonW + buttonGap, buttonsY, buttonW, "Close");
        drawButton(g, font, buttonsX + (buttonW + buttonGap) * 2, buttonsY, buttonW, "Edit");

        // A hovered piece box (armor cell, equipment, pet, HOTM/...) beats the whole-card tooltip.
        // Drawn DIRECTLY (not via setTooltipForNextFrame): the deferred flush happens before this
        // overlay's stratum next frame, so a deferred tooltip would be painted over and never seen.
        List<Component> tooltip = pieceTooltip != null ? pieceTooltip
                : hovered != null ? cardTooltip(hovered) : null;
        if (tooltip != null) {
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

    private int maxScroll;
    private int scroll;
    private int gridViewBottom;

    /** Wheel scrolling over the card grid (wired from the container scroll hook). */
    public boolean handleScroll(AbstractContainerScreen<?> screen, double mouseY, double scrollY) {
        if (!isActive(screen) || maxScroll == 0 || scrollY == 0
                || mouseY < gridTop || mouseY > gridViewBottom) {
            return isActive(screen);   // still swallow scrolls over the overlay
        }
        scroll = clamp(scroll - (int) (scrollY * 24), 0, maxScroll);
        return true;
    }

    /** Inventory-style hover veil over a piece box. */
    private static final int HOVER_VEIL = 0x80FFFFFF;

    /** Tooltip of the piece box under the mouse this frame (beats the whole-card tooltip). */
    private List<Component> pieceTooltip;

    /**
     * One loadout card: page chip + number on top; the pet bobbing top-left with its nametag to its
     * RIGHT; HOTF / HOTM / Power Stone / Tuning boxes down the left; the equipment (Necklace /
     * Cloak / Belt / Gloves) down the right middle; your player model (large) in the centre; the
     * four armor pieces as a hoverable strip along the bottom. Every box highlights like an
     * inventory slot and shows its own tooltip; the equipped loadout glows.
     *
     * <p>The card size comes in as a parameter (shadowing the grid's own {@link #cardW} /
     * {@link #cardH}) because the HUD widget draws this exact method at its own size - one card,
     * two places, no second layout to keep in step.
     *
     * @param hud null in the grid; on the HUD, {@code {screenX, screenY, scale}} - where the card's
     *            top-left lands after the GUI editor's transform, and by how much it is scaled.
     *            Its presence is what makes this the HUD card, and it changes four things and
     *            nothing else: the rename pencil and the page chip are dropped (they are
     *            affordances for clicking, and nothing on the HUD can be clicked), the model looks
     *            straight ahead instead of at a cursor that is not there, the model is placed in
     *            screen space (see below), and the card surface comes from the theme's <b>HUD</b>
     *            card colours so the widget matches every other SBS card and follows the UI style
     *            switch with them.
     */
    private void drawCard(GuiGraphicsExtractor g, Font font, Entry entry, int slot, int currentPage,
                          int cx, int cy, int cardW, int cardH, boolean equipped, float[] hud,
                          boolean hover, int mouseX, int mouseY) {
        boolean onHud = hud != null;
        int page = entry != null ? entry.page() : (slot - 1) / PER_PAGE + 1;
        boolean onOpenPage = page == currentPage;

        if (equipped) {
            // The active loadout gets a glow + strong border so it reads from across the grid.
            SciFiRender.glow(g, cx + 1, cy + 1, cardW - 2, cardH - 2, SBSTheme.CORNER_RADIUS,
                    0x5533FF66, 2);
        }
        // On the HUD the theme's HUD card colours; in the grid the translucent card that sits on the
        // overlay's own panel gradient.
        int bg = equipped ? 0x8033AA44 : hover ? SBSTheme.CARD_BG_HOVER
                : onHud ? SBSTheme.HUD_CARD_BG : 0xA0102640;
        int border = equipped ? 0xFF55FF55 : hover ? SBSTheme.ACCENT_BRIGHT
                : onHud ? SBSTheme.HUD_CARD_BORDER : SBSTheme.CARD_BORDER;
        SciFiRender.roundedRectWithBorder(g, cx + 1, cy + 1, cardW - 2, cardH - 2,
                SBSTheme.CORNER_RADIUS, bg, border);

        // Top row: number + custom name left, a rename pencil + page chip on the right (chip is
        // green when its page is the one open = a single click equips).
        String pageChip = "P" + page;
        int chipW = font.width(pageChip) + 6;
        int chipX = cx + cardW - chipW - 4;
        int penX = chipX - PENCIL_W - 2;
        int penY = cy + 3;
        int penH = font.lineHeight + 2;
        boolean penHover = !onHud && mouseX >= penX && mouseX < penX + PENCIL_W
                && mouseY >= penY && mouseY < penY + penH;
        if (!onHud) {
            SciFiRender.roundedRectWithBorder(g, penX, penY, PENCIL_W, penH, 2,
                    penHover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    penHover ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
            g.centeredText(font, Component.literal("✎"), penX + PENCIL_W / 2, penY + 2, SBSTheme.TEXT);
        }

        String custom = slot > 0 ? customName(slot) : null;
        String label = slot < 1 ? "Equipped"
                : custom != null ? "#" + slot + " §b" + custom : "#" + slot;
        if (onHud && slot > 0 && widgetChanged()) {
            // Only before this session has confirmed the worn loadout: the card is last session's,
            // and the body has differed from it for a while. Never switches the card.
            label = label + " §8(from last session)";
        }
        int labelRight = onHud ? cx + cardW - 4 : penX;
        label = font.plainSubstrByWidth(label, labelRight - (cx + 4) - 2, false);
        g.text(font, Component.literal(label), cx + 4, cy + 3, SBSTheme.TEXT_MUTED);

        if (!onHud) {
            SciFiRender.roundedRectWithBorder(g, chipX, cy + 3, chipW, penH, 2,
                    onOpenPage ? 0x8033AA44 : SBSTheme.CARD_BG,
                    onOpenPage ? 0xFF55FF55 : SBSTheme.CARD_BORDER);
            g.text(font, Component.literal(pageChip), chipX + 3, cy + 4,
                    onOpenPage ? 0xFF55FF55 : SBSTheme.TEXT_MUTED);
        }
        if (penHover) {
            pieceTooltip = List.of(Component.literal("§eRename loadout"));
        }

        if (entry == null || entry.status() == Status.EMPTY) {
            g.centeredText(font, Component.literal(entry == null ? "§8?" : "§8empty"),
                    cx + cardW / 2, cy + cardH / 2, SBSTheme.TEXT_MUTED);
            return;
        }

        int leftX = cx + 4;
        int columnsY = cy + 34;

        // Pet box top-left, gently bobbing up and down: the real pet head (skin included) in the
        // box, its name to the right. A bone (the Pets menu icon) stands in until the head resolves.
        if (entry.pet() != null) {
            ItemStack petIcon = petIcon(entry.pet());
            if (petIcon.isEmpty()) {
                petIcon = petFallbackIcon();
            }
            int bob = Math.round((float) Math.sin(System.currentTimeMillis() / 350.0) * 2.0f);
            int petY = columnsY + bob;
            boolean over = mouseX >= leftX && mouseX < leftX + 16 && mouseY >= columnsY && mouseY < columnsY + 16;
            SciFiRender.roundedRectWithBorder(g, leftX, petY, 16, 16, 2,
                    SBSTheme.CARD_BG_HOVER, SBSTheme.ACCENT);
            g.item(petIcon, leftX, petY);
            String petName = entry.pet().replaceAll("(?i)^\\[Lvl\\s*[0-9]+]\\s*", "");
            petName = font.plainSubstrByWidth(petName, (cardW - 26) * 2, false);
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(leftX + 18, petY + 5);
            pose.scale(0.5f, 0.5f);
            int w = font.width(petName);
            g.fill(-2, -2, w + 2, font.lineHeight + 1, 0x66000000);
            g.text(font, Component.literal(petName), 0, 0, 0xFF6FD9FF);
            pose.popMatrix();
            if (over) {
                g.fill(leftX, petY, leftX + 16, petY + 16, HOVER_VEIL);
                pieceTooltip = lineTooltip(entry, "Pet", "Pet");
            }
        }

        // Left column under the pet: HOTF / HOTM / Power Stone / Tuning. The icon is the generic
        // slot item (same for all loadouts; vanilla stand-in until harvested); the VALUE is this
        // loadout's own lore line.
        for (int i = 0; i < EXTRA_KEYS.length; i++) {
            String value = loreValue(entry.lore(), EXTRA_KEYS[i]);
            boolean present = value != null;
            ItemStack detail = extraSlotIcons[i].isEmpty() ? extraFallbackIcons()[i] : extraSlotIcons[i];
            // The Power Stone box shows THIS loadout's stone ("Silky" -> Luxurious Spool), not
            // the generic slot icon - the power differs per loadout.
            if (present && EXTRA_KEYS[i].equals("Power Stone")) {
                ItemStack stone = powerStoneIcon(value);
                if (!stone.isEmpty()) {
                    detail = stone;
                }
            }
            int bx = leftX;
            int by = columnsY + (i + 1) * 18;
            boolean over = mouseX >= bx && mouseX < bx + 16 && mouseY >= by && mouseY < by + 16;
            SciFiRender.roundedRectWithBorder(g, bx, by, 16, 16, 2,
                    present ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    present ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
            if (present) {
                g.item(detail, bx, by);
            } else {
                g.centeredText(font, Component.literal("§8" + EXTRA_LETTERS[i]),
                        bx + 8, by + 4, SBSTheme.TEXT);
            }
            if (over) {
                g.fill(bx, by, bx + 16, by + 16, HOVER_VEIL);
                pieceTooltip = lineTooltip(entry, EXTRA_KEYS[i], EXTRA_NAMES[i]);
            }
        }

        // Right column: the equipment (Necklace / Cloak / Belt / Gloves), resolved from THIS
        // loadout's lore to a catalogue icon - so every loadout shows its own equipment at once.
        // Without the loadout item there is no lore to read; the Equipment menu's last capture
        // stands in, and its tooltip says how old it is.
        boolean fromEquipmentMenu = entry.loadoutItem.isEmpty();
        for (int i = 0; i < EQUIP_KEYS.length; i++) {
            boolean capturedTooltip = false;
            ItemStack detail = equipIcon(entry, i);
            boolean present = entry.equipment() != null && entry.equipment()[i];
            if (fromEquipmentMenu && detail.isEmpty()) {
                ItemStack captured = sbs.modid.client.helper.inventory.logic.EquipmentStore.getInstance()
                        .piece(sbs.modid.client.helper.inventory.logic.EquipmentMenu.Piece.values()[i]);
                if (captured != null && !captured.isEmpty()) {
                    detail = captured;
                    present = true;
                    capturedTooltip = true;
                }
            }
            int bx = cx + cardW - 20;
            int by = columnsY + i * 18;
            boolean over = mouseX >= bx && mouseX < bx + 16 && mouseY >= by && mouseY < by + 16;
            SciFiRender.roundedRectWithBorder(g, bx, by, 16, 16, 2,
                    present ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    present ? SBSTheme.ACCENT : SBSTheme.CARD_BORDER);
            if (!detail.isEmpty()) {
                g.item(detail, bx, by);
            } else {
                g.centeredText(font, Component.literal((present ? "§6" : "§8") + EQUIP_LETTERS[i]),
                        bx + 8, by + 4, SBSTheme.TEXT);
            }
            if (over) {
                g.fill(bx, by, bx + 16, by + 16, HOVER_VEIL);
                pieceTooltip = capturedTooltip
                        ? List.of(detail.getHoverName(), Component.literal("§8From the Equipment menu, "
                                + age(System.currentTimeMillis() - sbs.modid.client.helper.inventory.logic
                                        .EquipmentStore.getInstance().capturedAt()) + " ago"))
                        : lineTooltip(entry, EQUIP_KEYS[i], EQUIP_KEYS[i]);
            }
        }

        /*
         * Your own player model (large) wearing the loadout, head following the mouse - or looking
         * straight ahead on the HUD, where there is no cursor for it to follow.
         *
         * The entity is the one thing on this card that does NOT go through the pose stack: its
         * submission takes screen coordinates of its own and ignores the matrix `HudLayout.begin`
         * pushed, so on the HUD it was drawn at the widget's *untransformed* anchor - a full-size
         * player standing somewhere else entirely on screen while the card sat where it was moved
         * to. It therefore gets the card's post-transform rectangle (and the widget's scale folded
         * into its own) instead of the local one every other part of the card uses.
         */
        ItemStack[] shown = displayPieces(entry, onHud);
        // EVERY card with armor gets its own model, always. This was briefly narrowed to the hovered
        // card and the worn one, to stop two dozen player render states being rebuilt per frame -
        // but seeing all of your loadouts at once, dressed, IS the menu, and a grid where the body
        // only appears under the cursor is a different feature that happens to be faster. If the
        // cost has to come down again it comes down inside the preview, not by drawing less of it.
        if (preview != null && entry.hasArmor()) {
            dress(shown);
            // Mirror My Player (HUD card only): pose, hands and animations copied from you onto the
            // never-ticked preview; armour stays the loadout's own (dress above). Off, or in the
            // menu, the preview is put back to its untouched static state - once, not every frame.
            Minecraft mc = Minecraft.getInstance();
            boolean mirror = onHud && mc.player != null
                    && ConfigManager.getInstance().get().skyblockMenu.loadoutMirror;
            if (mirror) {
                PreviewMirror.copy(mc.player, preview, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
                previewMirrored = true;
            } else if (previewMirrored) {
                PreviewMirror.clear(preview);
                previewMirrored = false;
            }
            int x1 = cx + 26;
            int y1 = cy + 24;
            int x2 = cx + cardW - 26;
            int y2 = cy + cardH - 26;
            int scale = Math.max(24, (cardH - 54) / 2);
            float lookX = mouseX;
            float lookY = mouseY;
            if (hud != null) {
                x1 = Math.round(hud[0] + (x1 - cx) * hud[2]);
                y1 = Math.round(hud[1] + (y1 - cy) * hud[2]);
                x2 = Math.round(hud[0] + (x2 - cx) * hud[2]);
                y2 = Math.round(hud[1] + (y2 - cy) * hud[2]);
                scale = Math.max(1, Math.round(scale * hud[2]));
                lookX = (x1 + x2) / 2f;
                lookY = (y1 + y2) / 2f;
                if (mirror) {
                    // Your real head turn instead of straight ahead: the look point that makes the
                    // vanilla call produce it (PreviewMirror.lookX / lookY for the inverse formula).
                    lookX = PreviewMirror.lookX(lookX, PreviewMirror.relativeYaw(mc.player));
                    lookY = PreviewMirror.lookY(lookY, PreviewMirror.pitch(mc.player));
                }
            }
            InventoryScreen.extractEntityInInventoryFollowsMouse(g, x1, y1, x2, y2, scale, 0.0625F,
                    lookX, lookY, preview);
        }

        // Bottom strip: the four armor pieces as inventory-like hoverable cells.
        int stripW = 4 * 18;
        int sx = cx + (cardW - stripW) / 2;
        int sy = cy + cardH - 22;
        ItemStack[] pieces = shown;
        for (int i = 0; i < 4; i++) {
            int bx = sx + i * 18;
            boolean over = mouseX >= bx && mouseX < bx + 18 && mouseY >= sy && mouseY < sy + 18;
            SciFiRender.roundedRectWithBorder(g, bx, sy, 17, 17, 2,
                    SBSTheme.CARD_BG, over ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            if (!pieces[i].isEmpty()) {
                g.item(pieces[i], bx + 1, sy + 1);
            }
            if (over) {
                g.fill(bx + 1, sy + 1, bx + 16, sy + 16, HOVER_VEIL);
                pieceTooltip = pieces[i].isEmpty()
                        ? lineTooltip(entry, ARMOR_KEYS[i], ARMOR_KEYS[i])
                        : stackTooltip(pieces[i]);
            }
        }
    }

    /**
     * Catalogue icon for a loadout's equipment slot (from its lore name). Cached by cleaned name,
     * but ONLY successful resolutions are cached - the catalogue / repo icons load async, so a
     * failure is retried next frame (caching it froze Manticore Claw / Primordial invisible).
     */
    private ItemStack equipIcon(Entry entry, int slot) {
        String value = loreValue(entry.lore(), EQUIP_KEYS[slot]);
        if (value == null) {
            return ItemStack.EMPTY;
        }
        String key = cleanName(value);
        ItemStack cached = equipIconCache.get(key);
        if (cached != null) {
            return cached;
        }
        ItemStack resolved = armorFromLore(List.of(EQUIP_KEYS[slot] + ": " + value), EQUIP_KEYS[slot]);
        if (!resolved.isEmpty()) {
            equipIconCache.put(key, resolved);
        }
        return resolved;
    }

    /** Resolved equipment icons by cleaned equipment name (catalogue lookup runs once each). */
    private final Map<String, ItemStack> equipIconCache = new ConcurrentHashMap<>();

    /**
     * The pet's head icon <b>with any applied skin</b>. The real Pets-menu stack (the ONLY carrier
     * of the applied skin's head texture) is checked FIRST every frame - before the icon cache - so
     * a plain default-skin fallback resolved earlier is upgraded the instant the Pets menu is
     * harvested (or its persisted copy loads). The default-skin fallbacks (the /pv PetIconCache
     * GitHub fetch, then the catalogue) are cached; a failure is never cached so it retries.
     */
    private ItemStack petIcon(String petLine) {
        String petName = petLine.replaceAll("(?i)^\\[Lvl\\s*[0-9]+]\\s*", "");
        // Applied-skin head from the Pets menu wins outright - never cached, always re-checked.
        ItemStack skinned = petStacks.get(cleanName(petName));
        if (skinned != null && !skinned.isEmpty()) {
            return skinned;
        }
        ItemStack cached = petIcons.get(petLine);
        if (cached != null) {
            return cached;
        }
        // Fallback (DEFAULT skin only): the /pv way - PetIconCache fetches the pet's skull straight
        // from the repo on GitHub (the LOCAL appearance repo carries no pets at all); then the
        // catalogue. Null while the fetch is in flight.
        String petId = cleanName(petName).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        ItemStack resolved = sbs.modid.client.core.player.PetIconCache.getInstance().get(petId);
        if (resolved == null) {
            resolved = iconByCatalogName(cleanName(petName));
        }
        if (resolved != null && !resolved.isEmpty() && !isBarrier(resolved)) {
            petIcons.put(petLine, resolved);
            return resolved;
        }
        return ItemStack.EMPTY;   // retry next frame while the fetch is in flight
    }

    /** Tooltip for a lore-listed part: its ORIGINAL coloured lore line, plus a muted label. */
    private static List<Component> lineTooltip(Entry entry, String key, String label) {
        List<Component> tip = new ArrayList<>();
        var lore = entry.loadoutItem().get(net.minecraft.core.component.DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                String stripped = strip(line.getString()).trim();
                if (stripped.regionMatches(true, 0, key, 0, key.length())) {
                    tip.add(line);
                    break;
                }
            }
        }
        if (tip.isEmpty()) {
            tip.add(Component.literal("§7" + label + ": §8None"));
        }
        return tip;
    }

    /** Tooltip of a real armor stack: its coloured name + full lore, like hovering it in a menu. */
    private static List<Component> stackTooltip(ItemStack stack) {
        List<Component> tip = new ArrayList<>();
        tip.add(stack.getHoverName());
        var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore != null) {
            tip.addAll(lore.lines());
        }
        return tip;
    }

    /** The hover tooltip: the loadout item's own coloured name + lore (armor, equipment, pet). */
    private static List<Component> cardTooltip(Entry entry) {
        List<Component> tip = new ArrayList<>();
        tip.add(entry.loadoutItem().getHoverName());
        var lore = entry.loadoutItem().get(net.minecraft.core.component.DataComponents.LORE);
        if (lore != null) {
            tip.addAll(lore.lines());
        }
        return tip;
    }

    private static void drawButton(GuiGraphicsExtractor g, Font font, int x, int y, int w, String label) {
        SciFiRender.roundedRectWithBorder(g, x, y, w, SBSTheme.SEARCH_HEIGHT,
                SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);
        g.centeredText(font, Component.literal(label), x + w / 2,
                y + (SBSTheme.SEARCH_HEIGHT - font.lineHeight) / 2, SBSTheme.TEXT);
    }

    /** A quick-nav button that highlights on hover; its label is clipped to fit its width. */
    private static void drawNavButton(GuiGraphicsExtractor g, Font font, int x, int y, int w,
                                      String label, boolean hover) {
        SciFiRender.roundedRectWithBorder(g, x, y, w, SBSTheme.SEARCH_HEIGHT, SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        String fitted = font.plainSubstrByWidth(label, w - 6, false);
        g.centeredText(font, Component.literal(fitted), x + w / 2,
                y + (SBSTheme.SEARCH_HEIGHT - font.lineHeight) / 2,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
    }

    /** Closes the loadouts menu and runs a SkyBlock command (no leading slash). */
    private static void runCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.setScreenAndShow(null);
            minecraft.player.connection.sendCommand(command);
        }
    }

    /** Client-only fake entity id: never-spawned entities have none, but the renderer reads it. */
    private static final int PREVIEW_ID = PreviewEntities.LOADOUT_PLAYER_ID;

    /** (Re)creates the preview player: your profile (= your skin), silent, never added to the level. */
    private void ensurePreview(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            preview = null;
            return;
        }
        if (preview == null || preview.level() != minecraft.level) {
            preview = new RemotePlayer(minecraft.level, minecraft.player.getGameProfile());
            previewMirrored = false;   // a fresh preview is the static one
            preview.setId(PREVIEW_ID);
            preview.setSilent(true);
        }
    }

    private void dress(ItemStack[] pieces) {
        for (int p = 0; p < ARMOR_SLOTS.length; p++) {
            preview.setItemSlot(ARMOR_SLOTS[p], pieces[p]);
        }
    }

    /**
     * The armor a card should <b>draw</b> - the loadout's own stacks, except on the HUD widget while
     * you are genuinely wearing that loadout, where it is <b>the armor on your body instead</b>.
     *
     * <p>Same gear either way; the difference is that one set is the live stacks the game is already
     * rendering you in and the other was rebuilt out of {@code loadouts_cache.json}. That matters
     * because the widget is on screen every frame of the game, and every frame
     * {@code extractEntityInInventoryFollowsMouse} rebuilds the preview's render state from scratch -
     * which re-resolves all four armor models, and for a skinned helmet its head texture with them.
     * Cached stacks pay that resolution cold, forever, at 60 Hz; the stacks off your own body are the
     * ones the client has already resolved, so the same card costs a fraction of it. Nothing about
     * how the armor is coloured or animated changes - this hands the renderer better inputs, it does
     * not draw anything itself.
     *
     * <p>Only while the widget is showing the body ({@link #widgetShowsBody}). During a capture
     * window it deliberately shows the loadout you just clicked while your body still wears the one
     * you left, and copying the body then would show the wrong armor.
     */
    private ItemStack[] displayPieces(Entry entry, boolean onHud) {
        if (!onHud || !widgetShowsBody) {
            if (entry.pieces[0].isEmpty() && !entry.bodyHelmet.isEmpty()) {
                ItemStack[] withHelmet = entry.pieces.clone();
                withHelmet[0] = entry.bodyHelmet;
                return withHelmet;
            }
            return entry.pieces;
        }
        ItemStack[] live = wornArmor();
        return live == null ? entry.pieces : live;
    }

    /** The four armor stacks on the player right now, or null when there is no player / no armor. */
    private static ItemStack[] wornArmor() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        ItemStack[] worn = new ItemStack[ARMOR_SLOTS.length];
        boolean any = false;
        for (int p = 0; p < ARMOR_SLOTS.length; p++) {
            ItemStack stack = player.getItemBySlot(ARMOR_SLOTS[p]);
            worn[p] = stack == null ? ItemStack.EMPTY : stack;
            any |= !worn[p].isEmpty();
        }
        return any ? worn : null;
    }

    // ------------------------------------------------------------------
    // Interaction (called first in the container mouse hook)
    // ------------------------------------------------------------------

    /** Handles a click while the overlay is active; consumes everything over the menu. */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!isActive(screen)) {
            return false;
        }
        double mx = event.x();
        double my = event.y();

        // Quick-nav row: each button runs its SkyBlock command (closing the loadouts menu first).
        if (my >= navY && my <= navY + SBSTheme.SEARCH_HEIGHT) {
            for (int i = 0; i < NAV_BUTTONS.length; i++) {
                int bx = navButtonX + i * (navButtonW + navGap);
                if (mx >= bx && mx <= bx + navButtonW) {
                    if (NAV_BUTTONS[i][1].equals("wardrobe")) {
                        // Armor Sets is reached from inside this menu: one click on the worn
                        // chestplate (slot 20), the path the Layout Recorder captured on 2026-10-02.
                        // The menu swaps in place, so the SBS Wardrobe grid takes over from here.
                        clickMenuSlot(screen, ARMOR_SETS_SLOT, 0);
                    } else {
                        runCommand(NAV_BUTTONS[i][1]);
                    }
                    return true;
                }
            }
        }

        // Bottom buttons.
        if (my >= buttonsY && my <= buttonsY + SBSTheme.SEARCH_HEIGHT) {
            int which = -1;
            for (int i = 0; i < 3; i++) {
                int bx = buttonsX + i * (buttonW + buttonGap);
                if (mx >= bx && mx <= bx + buttonW) {
                    which = i;
                    break;
                }
            }
            if (which == 0) {
                clickSlotNamed(screen, "go back");
                return true;
            }
            if (which == 1) {
                Minecraft.getInstance().setScreenAndShow(null);
                return true;
            }
            if (which == 2) {
                editMode = true; // reveal Hypixel's original menu for this visit
                return true;
            }
        }

        // Grid (scroll-aware): the pencil in a card's corner renames it; a LEFT click equips (same
        // page) or steps the page arrow ONCE towards the card (other page - click again on the new
        // page); a RIGHT click edits the loadout in Hypixel's own menu ("Right-click to edit").
        int col = (int) ((mx - gridLeft) / cardW);
        int row = (int) ((my - gridTop + scroll) / cardH);
        if (mx >= gridLeft && col >= 0 && col < GRID_COLS && row >= 0
                && my >= gridTop && my <= gridViewBottom) {
            int slotNumber = row * GRID_COLS + col + 1;
            if (slotNumber >= 1 && slotNumber <= maxSlot) {
                Entry entry = slots.get(slotNumber);
                int targetPage = entry != null ? entry.page() : (slotNumber - 1) / PER_PAGE + 1;
                int currentPage = currentPage(title(screen));

                // Pencil hit zone (top-right, left of the page chip) - matches the drawCard layout.
                Font font = Minecraft.getInstance().font;
                int cx = gridLeft + col * cardW;
                int cy = gridTop - scroll + row * cardH;
                int chipW = font.width("P" + targetPage) + 6;
                int penX = cx + cardW - chipW - 4 - PENCIL_W - 2;
                int penH = font.lineHeight + 2;
                if (mx >= penX && mx < penX + PENCIL_W && my >= cy + 3 && my < cy + 3 + penH) {
                    renameLoadout(slotNumber);
                    return true;
                }

                // 1 = right button -> edit; anything else -> equip. Both need the open page.
                int button = event.button() == 1 ? 1 : 0;
                if (targetPage == currentPage && entry != null) {
                    clickMenuSlot(screen, entry.menuSlot(), button);
                    // A left-click equips the loadout, which swaps the pet too - update the Active
                    // Pet HUD immediately from the loadout's own pet (Hypixel's summon chat line
                    // confirms it a moment later; this makes it instant and carries the skin icon).
                    if (button == 0 && entry.pet() != null) {
                        sbs.modid.client.ui.hud.logic.PetTracker.getInstance()
                                .setActivePet(entry.pet(), petIcon(entry.pet()));
                    }
                    if (button == 0) {
                        // We just told the server to wear THIS slot. Whatever lands on the body from
                        // here on is that slot's armor, by the strongest evidence there is - no
                        // column, no name, no guess. The menu closes on equip, so the capture has to
                        // outlive it (see onClientTick).
                        equippedSlot = slotNumber;
                        adoptEquipped(slotNumber, "equipped through the overlay");
                        long now = System.currentTimeMillis();
                        captureUntil = now + CAPTURE_WINDOW_MS;
                        // The body still wears the set being left until the swap lands: no compare meanwhile.
                        session.quietUntil(captureUntil);
                        // Hold off a moment: for the first few ticks the body still wears the OLD
                        // loadout, and sampling that would write it into the new slot (harmless -
                        // the next sample corrects it - but pointless churn on disk).
                        lastCaptureAt = now + 500L;
                    }
                } else if (targetPage != currentPage) {
                    clickSlotNamed(screen, targetPage > currentPage ? "next page" : "previous page");
                }
            }
        }
        return true; // the overlay swallows every click over the menu
    }

    /** Pencil button: name the loadout (persisted; the menu closes while typing, reopen after). */
    private static void renameLoadout(int slotNumber) {
        Map<String, String> names = ConfigManager.getInstance().get().skyblockMenu.loadoutNames;
        String current = names.getOrDefault(String.valueOf(slotNumber), "");
        Minecraft.getInstance().setScreenAndShow(new sbs.modid.client.core.dev.NameInputScreen(
                Component.literal("Name Loadout #" + slotNumber),
                current.isEmpty() ? "e.g. Dungeon F7" : current,
                name -> {
                    if (name == null || name.isBlank()) {
                        names.remove(String.valueOf(slotNumber));
                    } else {
                        names.put(String.valueOf(slotNumber), name.trim());
                    }
                    ConfigManager.getInstance().save();
                }));
    }

    /** The player's custom name for a loadout, or null. */
    private static String customName(int slotNumber) {
        String name = ConfigManager.getInstance().get().skyblockMenu.loadoutNames
                .get(String.valueOf(slotNumber));
        return name == null || name.isBlank() ? null : name;
    }

    /** The open page from the menu title "(2/3) Loadouts"; 1 when the title carries no counter. */
    private static int currentPage(String title) {
        Matcher m = PAGE.matcher(title);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static void clickSlotNamed(AbstractContainerScreen<?> screen, String nameFragment) {
        AbstractContainerMenu menu = screen.getMenu();
        int upper = Math.max(0, menu.getItems().size() - 36);
        for (int i = 0; i < upper; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack != null && !stack.isEmpty()
                    && strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT).contains(nameFragment)) {
                clickMenuSlot(screen, i, 0);
                return;
            }
        }
    }

    /** Clicks a real menu slot; {@code button} 0 = left (equip), 1 = right (edit). */
    private static void clickMenuSlot(AbstractContainerScreen<?> screen, int slot, int button) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode != null && minecraft.player != null) {
            minecraft.gameMode.handleContainerInput(screen.getMenu().containerId, slot, button,
                    ContainerInput.PICKUP, minecraft.player);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
