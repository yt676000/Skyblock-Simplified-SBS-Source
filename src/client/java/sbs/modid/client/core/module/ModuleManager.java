/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.module;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Central registry for all module categories (and, later, their modules).
 *
 * <p>This is the single source of truth the GUI reads from. Adding a new category
 * is a one-liner in {@link #registerDefaults()}; adding modules later means calling
 * {@link ModuleCategory#addModule(Module)} during registration. Nothing in the GUI
 * needs to change when the catalogue grows.
 *
 * <p>Implemented as a lazily-initialized singleton so any part of the client can
 * reach it via {@link #getInstance()} while still allowing dependency injection
 * (the screen accepts a manager instance in its constructor).
 */
public final class ModuleManager {

    /** Id of the Command Keybinds module (whose card opens the keybind overlay). */
    public static final String COMMAND_KEYBINDS_ID = "command_keybinds";

    /** Id of the Bazaar module (whose card opens the Bazaar overlay). */
    public static final String BAZAAR_ID = "bazaar";

    public static final String FORGE_ID = "forge";

    public static final String CONVENIENCE_ID = "convenience";

    public static final String FISHING_ID = "fishing";

    /** Id of the Hunting module (Hunting/Fusion hotkeys + in-menu fusion helpers). */
    public static final String HUNTING_ID = "hunting";

    public static final String CASE_OPENING_ID = "case_opening";

    public static final String WARP_MENU_ID = "warp_menu";

    /** Id of the Hypixel GUI module (whose card opens the HUD-settings overlay). */
    public static final String HYPIXEL_GUI_ID = "hypixel_gui";

    /** Id of the Scrollable Tooltips module (whose card opens the tooltip-settings overlay). */
    public static final String SCROLLABLE_TOOLTIPS_ID = "scrollable_tooltips";

    /** Id of the Item Overlay module (whose card opens the rarity / LBIN overlay settings). */
    public static final String ITEM_OVERLAY_ID = "item_overlay";

    /** Id of the Chat Options module (whose card opens the chat-settings overlay). */
    public static final String CHAT_OPTIONS_ID = "chat_options";

    /** Id of the Minecraft Overlay module (global SBS theme master toggle). */
    public static final String MINECRAFT_OVERLAY_ID = "minecraft_overlay";

    /** Id of the Visuals module (screen obstructor / particle cleanup). */
    public static final String VISUALS_ID = "visuals";

    /** Id of the Mob Highlight module (box a chosen set of entity types in the world). */
    public static final String MOB_HIGHLIGHT_ID = "mob_highlight";

    /** Id of the Third Person module (own nametag + crosshair in third-person view). */
    public static final String THIRD_PERSON_ID = "third_person";

    /** Id of the SBS Players module (SBS badge on other SBS users' nametags). */
    public static final String SBS_PLAYERS_ID = "sbs_players";

    /** Id of the Theme module (the three base colours every SBS surface derives from). */
    public static final String THEME_ID = "theme";

    /** Id of the Texture Pack module (custom asset router state). */
    public static final String TEXTURE_PACK_ID = "texture_pack";

    /** Id of the Recipe Viewer module (item search + recipe display). */
    public static final String RECIPE_VIEWER_ID = "recipe_viewer";

    /** Id of the Skyblock Menu module (Ender Chest / Backpack preview). */
    public static final String SKYBLOCK_MENU_ID = "skyblock_menu";

    /** Id of the hidden Developer module (only listed while {@code DevMode.ACTIVE}). */
    public static final String DEVELOPER_ID = "developer";

    /** Id of the Dungeons module (Catacombs detection + SBS Dungeon Map). */
    public static final String DUNGEONS_ID = "dungeons";

    /** Id of the Licence Token module (SBS price-API token entry). */
    public static final String LICENCE_TOKEN_ID = "licence_token";

    /** Mod-wide settings actions (reset, disable all). Pinned directly above the Licence Token. */
    public static final String SBS_SETTINGS_ID = "sbs_settings";

    /** Id of the Item Price History module (price charts from skyblocksimplified.info). */
    public static final String ITEM_PRICE_HISTORY_ID = "item_price_history";

    /** Id of the Animation & Scaling module (swing speed + held-item size). */
    public static final String ANIMATION_SCALING_ID = "animation_scaling";

    /** Id of the Farming module (mouse lock etc.). */
    public static final String FARMING_ID = "farming";

    /** Id of the Ether Warp module (target highlight + zoom for AOTE/AOTV). */
    public static final String ETHER_WARP_ID = "ether_warp";

    /** Id of the Skill Progress module (live skill/job XP overlay). */
    public static final String SKILL_PROGRESS_ID = "skill_progress";

    /** Id of the Quest Guide module (guided quest objectives; feature set still being defined). */
    public static final String QUEST_GUIDE_ID = "quest_guide";

    /** Id of the Inventory Buttons module (custom command buttons over the open inventory). */
    public static final String INVENTORY_BUTTONS_ID = "inventory_buttons";

    /** Id of the Inventory Slot Lock module (lock slots against moving / dropping). */
    public static final String SLOT_LOCK_ID = "slot_lock";

    /** Id of the Inventory Overlay module (HUD inventory + transparent inventory screen). */
    public static final String INVENTORY_OVERLAY_ID = "inventory_overlay";

    /** Id of the Player Viewer module (/sbs skycrypt opens SkyCrypt in the in-game browser). */
    public static final String PLAYER_VIEWER_ID = "player_viewer";

    /** Id of the SBS Party Finder module (universal party finder over the cloud server). */
    public static final String PARTY_FINDER_ID = "party_finder";

    /** Id of the Party Commands module (!warp / !allinv / !transfer in the Hypixel party chat). */
    public static final String PARTY_COMMANDS_ID = "party_commands";

    private static ModuleManager instance;

    private final List<ModuleCategory> categories = new ArrayList<>();

    private ModuleManager() {
        registerDefaults();
    }

    public static ModuleManager getInstance() {
        if (instance == null) {
            instance = new ModuleManager();
        }
        return instance;
    }

    /**
     * Registers the production-ready module cards, each in its {@link ModuleGroup} (the sidebar
     * shows the groups in enum order, then any {@link ModuleSubgroup}s in theirs, with the modules
     * alphabetical inside; the Licence Token is
     * PINNED to the very top because the whole price API hangs off it). Only modules backed by a
     * real, functional system are listed here. Accent colors stay within a muted palette so the
     * dark-blue / white theme stays cohesive.
     */
    private void registerDefaults() {
        // Pinned – always the first entry, above every group.
        register(sbs.modid.client.ui.settings.Favorites.PAGE_ID, ModuleGroup.PINNED,
                "Favourites", "The settings you pinned, in the order you pinned them", 0xFFFFD24B);
        register(sbs.modid.client.ui.settings.CommandsPage.PAGE_ID, ModuleGroup.PINNED,
                "Commands", "Every SBS command - click one to type it into chat", 0xFF55FFFF);
        register(LICENCE_TOKEN_ID, ModuleGroup.PINNED,
                "Licence Token", "Your token for the SkyBlock Simplified price API", 0xFF3FB4FF);

        // Economy – making coins.
        register(BAZAAR_ID, ModuleGroup.ECONOMY,
                "Bazaar", "Bazaar flipping and prices", 0xFF8FD14D);
        register(ITEM_PRICE_HISTORY_ID, ModuleGroup.ECONOMY, "Item Price History",
                "The skyblocksimplified.info price browser in-game: track prices, spot manipulation", 0xFF3FB4FF);
        register(FORGE_ID, ModuleGroup.ECONOMY, "Forge",
                "Which forge item pays best per forge hour, ranked by the SBS server", 0xFFE0A14D);

        // Skills – levelling and gathering (the skill-XP HUD overlay belongs here: it is
        // about levelling, the Interface group is about how the HUD looks).
        register(FARMING_ID, ModuleGroup.SKILLS, ModuleSubgroup.FARMING_GARDEN,
                "Farming", "Crop milestones, farming fortune, hoe levels and the mouse lock keybind",
                0xFF8FD14D);
        register(FISHING_ID, ModuleGroup.SKILLS, ModuleSubgroup.FISHING, "Fishing",
                "Fishing: spawn alert, and tracking for catches, sea creatures, shards and profit",
                0xFF3FB4FF);
        register(HUNTING_ID, ModuleGroup.SKILLS, ModuleSubgroup.HUNTING, "Hunting",
                "Hunting & Shard Fusion hotkeys, plus accept / repeat fusion inside the Fusion menu",
                0xFF8FD14D);
        register(SKILL_PROGRESS_ID, ModuleGroup.SKILLS, "Skill Progress",
                "Live HUD overlay for the skill you are currently levelling: XP, rate and time to next level", 0xFF8FD14D);

        // Combat – fighting mobs outside dungeons.
        register(ETHER_WARP_ID, ModuleGroup.COMBAT,
                "Ether Warp", "Target highlight and aim zoom while sneaking with an AOTE / AOTV", 0xFFB44DFF);
        register(MOB_HIGHLIGHT_ID, ModuleGroup.COMBAT,
                "Mob Highlight", "Box chosen SkyBlock mobs in the world (searchable multi-select)", 0xFFFF6060);

        // Dungeons – Catacombs (the reward-chest animation lives here, not in QoL:
        // whoever looks for it thinks "dungeon chest", not "comfort").
        register(DUNGEONS_ID, ModuleGroup.DUNGEONS,
                "Dungeons", "Catacombs detection and the custom SBS Dungeon Map", 0xFF3FB4FF);
        register(CASE_OPENING_ID, ModuleGroup.DUNGEONS, "Case Opening",
                "A CS:GO-style reveal animation when a dungeon reward chest is opened (cosmetic)",
                0xFFFFD24B);

        // Party & Chat – playing with others.
        register(CHAT_OPTIONS_ID, ModuleGroup.PARTY_CHAT,
                "Chat Options", "Client: chat tweaks such as copy-to-clipboard", 0xFF8194B0);
        register(PARTY_COMMANDS_ID, ModuleGroup.PARTY_CHAT,
                "Party Commands", "!warp, !ptme, !f7, !coords, !8ball ... typed by party members", 0xFF8FD14D);
        register(PARTY_FINDER_ID, ModuleGroup.PARTY_CHAT,
                "SBS Party Finder", "Universal party finder with requirements + party chat over the SBS cloud", 0xFF8FD14D);
        register(PLAYER_VIEWER_ID, ModuleGroup.PARTY_CHAT,
                "Player Viewer", "/sbs skycrypt opens SkyCrypt (sky.shiiyu.moe) in the in-game browser", 0xFF3FB4FF);
        register(SBS_PLAYERS_ID, ModuleGroup.PARTY_CHAT,
                "SBS Players", "Show an SBS badge on the nametag of everyone else running SBS", 0xFF3FB4FF);

        // Inventory & Items – items and open menus.
        register(ITEM_OVERLAY_ID, ModuleGroup.INVENTORY_ITEMS, ModuleSubgroup.ITEMS,
                "Item Overlay", "Render: item rarity overlay and Lowest BIN / Bazaar price in tooltips", 0xFF3FB4FF);
        register(RECIPE_VIEWER_ID, ModuleGroup.INVENTORY_ITEMS, ModuleSubgroup.ITEMS,
                "Recipe Viewer", "Item search, SkyBlock recipes and acquisition info", 0xFF3FB4FF);
        register(SCROLLABLE_TOOLTIPS_ID, ModuleGroup.INVENTORY_ITEMS, ModuleSubgroup.ITEMS,
                "Scrollable Tooltips", "Scroll oversized item lore so it fits the screen", 0xFF3FB4FF);
        register(INVENTORY_BUTTONS_ID, ModuleGroup.INVENTORY_ITEMS, ModuleSubgroup.MENUS,
                "Inventory Buttons", "Your own command buttons over the open inventory", 0xFF8FD14D);
        register(INVENTORY_OVERLAY_ID, ModuleGroup.INVENTORY_ITEMS, ModuleSubgroup.MENUS,
                "Inventory Overlay", "See the inventory over the hotbar, and use it without losing sight of the game", 0xFF3FB4FF);
        register(SLOT_LOCK_ID, ModuleGroup.INVENTORY_ITEMS, ModuleSubgroup.SLOTS,
                "Inventory Slot Lock", "Lock slots so their items cannot be moved, dropped or replaced", 0xFF8FD14D);
        register(SKYBLOCK_MENU_ID, ModuleGroup.INVENTORY_ITEMS, ModuleSubgroup.MENUS,
                "Skyblock Menu", "Ender Chest & Backpack hover preview", 0xFF3FB4FF);

        // Interface & Theme – the SBS look of HUD and menus.
        register(HYPIXEL_GUI_ID, ModuleGroup.INTERFACE,
                "GUI", "Custom HUD: health bar, mana bar, hide toggles and the GUI editor", 0xFF3FB4FF);
        register(MINECRAFT_OVERLAY_ID, ModuleGroup.INTERFACE,
                "Minecraft Overlay", "Visual: global SBS theme master toggle", 0xFF3FB4FF);
        register(TEXTURE_PACK_ID, ModuleGroup.INTERFACE,
                "Texture Pack", "Visual: SBS custom asset router", 0xFF3FB4FF);
        register(THEME_ID, ModuleGroup.INTERFACE,
                "Theme", "Recolour all of SBS from three base colours - gradients derive automatically",
                0xFFB050FF);

        // Visuals – world rendering (not the SBS interface itself).
        register(ANIMATION_SCALING_ID, ModuleGroup.VISUALS, "Animation & Scaling",
                "Swing animation speed and held-item render size in percent", 0xFF3FB4FF);
        register(VISUALS_ID, ModuleGroup.VISUALS,
                "Visuals", "Visual: fire overlay, explosion and potion particle cleanup, "
                        + "hide falling blocks and the dragon death animation", 0xFF3FB4FF);
        register(THIRD_PERSON_ID, ModuleGroup.VISUALS,
                "Third Person", "Show your own nametag and the crosshair in third-person view", 0xFF3FB4FF);

        // Quality of Life – comforts that fit nowhere else.
        register(CONVENIENCE_ID, ModuleGroup.QUALITY_OF_LIFE, "Convenience",
                "Small comforts: auto sprint, and the mouse staying put between menus", 0xFF8FD14D);
        register(COMMAND_KEYBINDS_ID, ModuleGroup.QUALITY_OF_LIFE,
                "Command Keybinds", "Bind keys to commands, plus short commands like /pw, /pk, /pa", 0xFF3FB4FF);
        register(WARP_MENU_ID, ModuleGroup.QUALITY_OF_LIFE, ModuleSubgroup.NAVIGATION, "Warp Menu",
                "Your own warp menu: one box per island, click to warp", 0xFF8FD14D);

        // Hidden dev card – registered always but filtered out of the listings unless dev mode is on.
        register(DEVELOPER_ID, ModuleGroup.DEVELOPER,
                "Developer", "Room scanner & waypoint tools (dev mode)", 0xFFE0A030);
    }

    private void register(String id, ModuleGroup group, String name, String description, int accentColor) {
        register(id, group, null, name, description, accentColor);
    }

    private void register(String id, ModuleGroup group, ModuleSubgroup subgroup, String name,
                          String description, int accentColor) {
        categories.add(new ModuleCategory(
                id,
                group,
                checkedSubgroup(id, group, subgroup),
                Component.literal(name),
                Component.literal(description),
                accentColor));
    }

    /** Ids already warned about by {@link #checkedSubgroup}, so a per-frame rebuild logs once. */
    private static final Set<String> MISPLACED_WARNED = new HashSet<>();

    /**
     * The declared subgroup if it belongs to the module's own group, otherwise {@code null}.
     *
     * <p>A subgroup names its owning group, and a module declaring another group's would draw that
     * group's header - a Foraging sub-header under Combat. Dropping it to General keeps the module
     * listed where its group says; the log line is what gets it fixed.
     */
    static ModuleSubgroup checkedSubgroup(String id, ModuleGroup group, ModuleSubgroup declared) {
        if (declared == null || declared.group() == group) {
            return declared;
        }
        if (MISPLACED_WARNED.add(id)) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Modules] module '{}' declares subgroup {} of group {} but is in group {}"
                            + " - listed under General instead", id, declared, declared.group(), group);
        }
        return null;
    }

    /**
     * The legacy registrations above plus every self-registered {@link SbsModule}.
     *
     * <p>Both paths coexist on purpose: converting all 26 existing modules in one commit would be a
     * huge diff conflicting with every open branch, for no behaviour change. New modules use
     * {@code SbsModule} and touch no shared file; the old ones migrate one at a time, whenever
     * someone is in that code anyway.
     *
     * <p>A self-registered module wins over a legacy entry with the same id, so migrating one is
     * "add the class, delete the old lines" and never a moment where it appears twice.
     */
    private List<ModuleCategory> allCategories() {
        List<ModuleCategory> all = new ArrayList<>();
        java.util.Set<String> discovered = ModuleRegistry.all().keySet();
        for (ModuleCategory legacy : categories) {
            if (!discovered.contains(legacy.id())) {
                all.add(legacy);
            }
        }
        for (SbsModule module : ModuleRegistry.all().values()) {
            if (module.visible()) {
                all.add(new ModuleCategory(
                        module.id(),
                        module.group(),
                        checkedSubgroup(module.id(), module.group(), module.subgroup()),
                        Component.literal(module.displayName()),
                        Component.literal(module.description()),
                        module.accentColor()));
            }
        }
        return all;
    }

    /**
     * The PINNED group's order, top first. Pinned means "this sits where I put it", so unlike every
     * other group it is not alphabetical - otherwise "Licence Token" would sort above "SBS Settings"
     * purely because L precedes S. Anything pinned but unlisted falls below these, alphabetically.
     */
    private static final List<String> PINNED_ORDER =
            List.of(sbs.modid.client.ui.settings.Favorites.PAGE_ID,
                    sbs.modid.client.ui.settings.CommandsPage.PAGE_ID, SBS_SETTINGS_ID, LICENCE_TOKEN_ID);

    private static int pinnedRank(ModuleCategory category) {
        int index = PINNED_ORDER.indexOf(category.id());
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /** A module's place among its group's subgroups: declaration order, and General last. */
    private static int subgroupRank(ModuleCategory category) {
        ModuleSubgroup subgroup = category.subgroup();
        return subgroup == null ? Integer.MAX_VALUE : subgroup.ordinal();
    }

    /**
     * Sidebar order: PINNED first, then the {@link ModuleGroup} enum order, then the group's
     * {@link ModuleSubgroup}s in theirs with General last, alphabetical within.
     *
     * <p>The pinned rank and the subgroup rank are both {@code MAX_VALUE} for anything they do not
     * apply to, so a group with no subgroups ties on both and falls straight through to the name -
     * exactly the order it had before either existed.
     */
    private static final Comparator<ModuleCategory> SIDEBAR_ORDER =
            Comparator.comparingInt((ModuleCategory c) -> c.group().ordinal())
                    .thenComparingInt(ModuleManager::pinnedRank)
                    .thenComparingInt(ModuleManager::subgroupRank)
                    .thenComparing(c -> c.displayName().getString(), String.CASE_INSENSITIVE_ORDER);

    /**
     * The groups drawn with sub-headers: those where at least one listed module declares a subgroup.
     *
     * <p>Asked of the modules, not of {@link ModuleSubgroup}, so a subgroup constant nobody uses
     * cannot put an empty "General" header into a group that is flat today. Pass the whole catalogue
     * rather than a search result, or a group would flip between subdivided and flat as the player
     * types.
     */
    public static Set<ModuleGroup> subdividedGroups(Collection<ModuleCategory> categories) {
        Set<ModuleGroup> groups = EnumSet.noneOf(ModuleGroup.class);
        for (ModuleCategory category : categories) {
            if (category.subgroup() != null) {
                groups.add(category.group());
            }
        }
        return groups;
    }

    /** All registered categories in sidebar order (read-only), legacy and self-registered alike. */
    public List<ModuleCategory> getCategories() {
        List<ModuleCategory> sorted = new ArrayList<>(allCategories());
        filterHidden(sorted);
        sorted.sort(SIDEBAR_ORDER);
        return Collections.unmodifiableList(sorted);
    }

    /** Drops categories that should not be visible right now (currently just the dev card when off). */
    private static void filterHidden(List<ModuleCategory> list) {
        // DEV-ONLY: hides the Developer card
        if (!sbs.modid.client.core.dev.DevMode.ACTIVE) {
            list.removeIf(c -> c.id().equals(DEVELOPER_ID));
        }
    }

    /**
     * Returns the categories matching the given query, in sidebar order. An empty / null
     * query returns every category. This is the entry point the search bar uses.
     */
    public List<ModuleCategory> search(String query) {
        if (query == null || query.isBlank()) {
            return getCategories();
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<ModuleCategory> result = new ArrayList<>();
        for (ModuleCategory category : allCategories()) {
            if (category.matches(q)) {
                result.add(category);
            }
        }
        filterHidden(result);
        result.sort(SIDEBAR_ORDER);
        return result;
    }

    public Optional<ModuleCategory> byId(String id) {
        return allCategories().stream().filter(c -> c.id().equals(id)).findFirst();
    }
}
