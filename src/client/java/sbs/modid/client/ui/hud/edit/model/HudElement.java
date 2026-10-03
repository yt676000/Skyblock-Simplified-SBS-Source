/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.hud.edit.model;

import sbs.modid.client.ui.hud.edit.logic.HudLayout;
/**
 * The catalogue of movable / scalable HUD elements the GUI editor knows about.
 *
 * <p>Each element carries a stable {@code id} (the config key – never rename it), a human-readable
 * name shown in the editor, and its <b>default bounds</b>: the rectangle where vanilla / SBS draws it
 * before any transform. Bounds are computed from the current GUI size so they follow the anchor
 * vanilla uses (bottom-centre for the survival bars, centre for the crosshair, etc.).
 *
 * <p>Adding a future element is a one-liner here – give it an id, a name and its default bounds, then
 * wrap its render call with {@link HudLayout#begin}/{@link HudLayout#end}. Nothing else changes: the
 * editor iterates {@link #values()} automatically.
 */
public enum HudElement {

    // --- SBS-drawn elements (we control these directly in SBSHudRenderer) ---
    SBS_HEALTH_BAR("sbs_health_bar", "SBS Health Bar") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 91, gh - 37, 81, 5); }
    },
    SBS_MANA_BAR("sbs_mana_bar", "SBS Mana Bar") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 10, gh - 37, 81, 5); }
    },
    // Vitality pool bar (Hypixel GUI): sits one bar-row directly above the health bar.
    SBS_VITALITY_BAR("sbs_vitality_bar", "SBS Vitality Bar") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 91, gh - 44, 81, 5); }
    },
    SBS_XP_BAR("sbs_xp_bar", "SBS XP Bar") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 91, gh - 29, 182, 5); }
    },

    /** The big "!" that fires when a sea creature spawns. Centred, a little above the crosshair. */
    FISHING_ALERT("fishing_alert", "Fishing Spawn Alert") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 30, gh / 2f - 60, 60, 30); }
    },

    // Fishing tracker panels (catches / shards / profit). The panel sizes itself to its rows; this
    // is the top-left anchor plus a nominal size for the editor box.
    FISHING_HUD("fishing_hud", "Fishing Trackers") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(140, gh / 2f - 80, 130, 160); }
    },

    /** The sea creature counter list, anchored to the left edge (a clean list). */
    SEA_CREATURE_LIST("sea_creature_list", "Sea Creature Tracker") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f - 110, 120, 220); }
    },

    /**
     * Running burrow chains and the ritual's session totals, on one card.
     *
     * <p>One element rather than two, because both are the same kind of readout - numbers about how
     * the event is going - and two cards for one feature is two things to position for a player who
     * wanted one. Each half still has its own switch, so a card can be chains only.
     */
    DIANA_TRACKER("diana_tracker", "Diana Tracker") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, 40, 130, 90); }
    },

    /**
     * Health of the rare mythological creatures in sight.
     *
     * <p>Its own element, unlike the two above: this is combat information read while fighting and
     * belongs wherever the player's eyes already are, which is not where a session tally belongs.
     */
    DIANA_CREATURES("diana_creatures", "Diana Creature Health") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 150, 40, 140, 60); }
    },

    /** The "!!! Reel in now!" bite indicator, centred under the crosshair. */
    BITE_ALERT("bite_alert", "Bite Alert (Reel in!)") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 60, gh / 2f + 30, 120, 40); }
    },

    /** Trophy Fish grid: every fish x tier. Self-measuring; this is the anchor. */
    TROPHY_FISH("trophy_fish", "Trophy Fish") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 190, 40, 180, 220); }
    },

    /** Trophy Fish session card: catches, active time and rate this game session. */
    TROPHY_SESSION("trophy_session", "Trophy Session") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 190, 270, 130, 60); }
    },

    /** Small "baits left" chip: the current bait's icon + how many remain. Sits under the trackers. */
    BAIT_COUNTER("bait_counter", "Baits Left") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(140, gh / 2f + 84, 52, 22); }
    },

    /** Golden Fish timer: continuous lava-fishing time, the reset countdown, the fish while it is up. */
    GOLDEN_FISH("golden_fish", "Golden Fish Timer") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(140, gh / 2f + 110, 130, 34); }
    },

    // --- Hypixel SkyBlock custom stats: the REAL action bar (health / defense / mana / overflow are
    // all rendered by Hypixel in the vanilla overlay message). We transform that real render – we do
    // not draw our own text. ---
    HYPIXEL_ACTION_BAR("hypixel_action_bar", "Hypixel Stats (Action Bar)") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 90, gh - 48, 180, 10); }
    },

    // --- Vanilla survival HUD (bottom row) ---
    HEARTS("hearts", "Hearts") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 91, gh - 39, 81, 9); }
    },
    HUNGER("hunger", "Hunger Bar") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 10, gh - 39, 81, 9); }
    },
    ARMOR("armor", "Armor Bar") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 91, gh - 49, 81, 9); }
    },
    HOTBAR("hotbar", "Hotbar") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 91, gh - 22, 182, 22); }
    },
    EFFECTS("effects", "Potion Effects") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 26, 1, 24, 50); }
    },
    BOSS_BAR("boss_bar", "Boss Bar") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 91, 12, 182, 30); }
    },
    SCOREBOARD("scoreboard", "Scoreboard") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 90, gh / 2f - 40, 88, 80); }
    },
    CHAT("chat", "Chat") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(2, gh - 48, 164, 40); }
    },
    /**
     * The chat channel tabs. Sits by default in the band vanilla leaves free between the last chat
     * line ({@code gh - 40}) and the input box, which is where a tab strip belongs when nobody has
     * moved it. The width is nominal - the strip measures itself from its own tabs.
     */
    CHAT_TABS("chat_tabs", "Chat Tabs") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh - 40, 160, 12); }
    },

    // --- SBS extras (toggleable in the GUI module) ---
    ACTIVE_PET("active_pet", "Active Pet") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, 40, 130, 26); }
    },
    // The equipped loadout drawn as its SBS Loadouts card, under the Active Pet card by default.
    // 120x180 must match LoadoutHud.CARD_W and the grid's own "height = width + 60" card shape.
    EQUIPPED_LOADOUT("equipped_loadout", "Equipped Loadout") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, 70, 120, 180); }
    },
    // Ping / TPS / FPS card ("Show Server Stats"), top-left by default.
    SERVER_STATS("server_stats", "Server Stats") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, 4, 132, 16); }
    },
    ITEM_COOLDOWN("item_cooldown", "Item Cooldown") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 12, gh / 2f - 4, 34, 10); }
    },
    // "Show Remaining Arrows" chip: sits just right of the hotbar (which spans gw/2 ± 91), at the
    // hotbar's own height. ArrowDisplayHud measures the real width from the arrow name.
    ARROW_DISPLAY("arrow_display", "Arrows Left") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 97, gh - 26, 86, 24); }
    },

    // SBS Dungeon Map minimap (Dungeons module). 157x157 must match DungeonMapRenderer.PANEL_SIZE.
    DUNGEON_MAP("dungeon_map", "SBS Dungeon Map") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 163f, 44, 157, 157); }
    },

    // Transparent inventory overlay above the hotbar (Inventory Overlay module). 162x54 = the 9x3
    // main inventory grid at 18px per cell; must match InventoryOverlayHud's grid constants.
    INVENTORY_OVERLAY("inventory_overlay", "Inventory Overlay") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 81, gh - 80, 162, 54); }
    },

    // "Free slots: N" chip (Full Inventory Warning). Only drawn at or under the player's threshold,
    // so it sits clear of the hotbar's right end rather than over anything permanent. FreeSlotsHud
    // measures the real width from the text.
    // The Bingo card: open goals under a "card from Xh ago" header. Right edge, under the scoreboard's
    // usual height; it measures itself, so this is the anchor only.
    BINGO_CARD("bingo_card", "Bingo Card") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 240f, 110, 230, 80); }
    },
    FREE_SLOTS("free_slots", "Free Slots Counter") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 96, gh - 55, 86, 18); }
    },

    // Chocolate Factory card: production, best buy, Time Tower, barn. Everything on it is the last
    // menu capture with its age, so it is a readout rather than anything time-critical - top right,
    // clear of the crosshair. ChocolateHud measures its real height from the rows it has.
    CHOCOLATE_FACTORY("chocolate_factory", "Chocolate Factory") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170, 40, 160, 80); }
    },

    // Skill Progress overlay: only visible while a skill is actively earning XP. The height is the
    // panel at its tallest (all optional lines on); SkillProgressHud measures the real one itself.
    SKILL_PROGRESS("skill_progress", "Skill Progress") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f - 30, 130, 66); }
    },

    // Quest Guide overlay: only visible while a quest is being tracked. QuestOverlay measures its
    // own height from the checklist window it shows.
    // Wide enough for a real step title ("Bring 1x Enchanted Red Mushroom Block to Romero")
    // plus the item icon and count; QuestOverlay measures its own height from the rows it shows.
    QUEST_GUIDE("quest_guide", "Quest Guide") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 220f, 44, 214, 104); }
    },

    // Custom Scoreboard (Visuals): the SBS-styled sidebar replacement. Anchored on the right like the
    // vanilla sidebar; the panel measures its own width/height from its content, so this is only the
    // top-left anchor the editor moves and scales about.
    CUSTOM_SCOREBOARD("custom_scoreboard", "Custom Scoreboard") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 190f, gh / 2f - 60, 170, 120); }
    },

    // AH Flip Alerts popup stack (Economy): the anchor of the NEWEST card; further cards stack
    // downwards from it. 186x44 must match AhFlipPopups' card constants.
    AH_FLIP_POPUPS("ah_flip_popups", "AH Flip Alerts") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 192f, 40, 186, 44); }
    },

    // Bazaar Orders card (Economy): the Manage Orders panel outside the Bazaar menu. Self-measuring,
    // so this is only the top-left anchor; 172 wide must match ManageOrdersPanel.PANEL_W.
    // Anchored off the BOTTOM edge rather than at a fraction of the height: that keeps it clear of
    // both the AH flip popups (top right) and the custom scoreboard (right, mid-height) at every
    // viewport, which a gh/2 anchor does not.
    BAZAAR_ORDERS("bazaar_orders", "Bazaar Orders") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 178f, gh - 190f, 172, 120); }
    },

    // Slayer Carry Counter (Quality of Life): one row per tracked carry. CarryCounterOverlay measures
    // its own width/height from the carries it shows, so this is only the top-left anchor.
    CARRY_COUNTER("carry_counter", "Slayer Carry Counter") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f - 30, 140, 60); }
    },

    // Collection Tracker (Skills): active collection counter + tier progress. The card measures its
    // own width/height from its content, so this is only the top-left anchor.
    COLLECTION_TRACKER("collection_tracker", "Collection Tracker") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f + 40, 140, 60); }
    },

    // Slayer session tracker (Skills): bosses / kill times / profit / drop list. Self-measuring.
    SLAYER_TRACKER("slayer_tracker", "Slayer Tracker") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, gh / 2f - 60, 160, 120); }
    },

    // Blaze attunement alert (Slayer): the mode the fight needs against the mode your dagger is on.
    // A little above the crosshair - it is read while swinging, and a swap missed is a swing wasted.
    // Sits clear of the slayer flash alert (which draws from gh/2 - 46).
    BLAZE_ATTUNEMENT("blaze_attunement", "Blaze Attunement") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 60, gh / 2f - 78, 120, 26); }
    },

    // Composter status card (Garden): matter / fuel / compost read from the Composter menu. Self-measuring.
    COMPOSTER("composter", "Composter") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, gh / 2f + 70, 160, 70); }
    },

    // Visitor shopping list card (Garden): summed visitor wants, cost, copper per coin. Self-measuring.
    VISITOR_SHOPPING("visitor_shopping", "Visitor Shopping") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(10f, gh / 2f - 60, 150, 120); }
    },

    // Visitor Timer card (Garden): waiting visitors and the next arrival. Self-measuring.
    VISITOR_TIMER("visitor_timer", "Visitor Timer") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(10f, gh / 2f - 86, 150, 20); }
    },

    // Farming Tracker card (Garden): fortune / overbloom / pest chance / profit. Self-measuring.
    FARMING_TRACKER("farming_tracker", "Farming Tracker") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f + 110, 140, 70); }
    },

    // Farm Drops card (farming islands): this session's rare farming drops. Self-measuring.
    FARM_DROPS("farm_drops", "Farm Drops") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f + 30, 140, 60); }
    },

    // Crop Milestone card (Farming): crop, tier, progress bar, crops/min and the ETA. Self-measuring.
    /** Jacob's Contest card: only while a contest runs. Self-measuring; this is the anchor. */
    JACOB_CONTEST("jacob_contest", "Jacob's Contest") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f + 10, 140, 80); }
    },
    /** Farming Speed card: crop blocks per second. Self-measuring; this is the anchor. */
    FARMING_SPEED("farming_speed", "Farming Speed") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 20, gh / 2f + 20, 130, 60); }
    },
    CROP_MILESTONE("crop_milestone", "Crop Milestone") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f - 70, 140, 70); }
    },

    // Farming Fortune card (Farming): the effective fortune for the held tool's crop. Self-measuring.
    FARMING_FORTUNE("farming_fortune", "Farming Fortune") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f + 6, 140, 70); }
    },

    // Hoe Level card (Farming): the held tool's level and its progress bar. Self-measuring.
    HOE_LEVEL("hoe_level", "Hoe Level") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, gh / 2f - 130, 150, 42); }
    },

    // Commission card (Mining): one row + progress bar per active commission. Self-measuring.
    MINING_COMMISSIONS("mining_commissions", "Commissions") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, gh / 2f - 90, 150, 80); }
    },

    // Heart of the Mountain card (Mining): the tier. Self-measuring.
    MINING_HOTM("mining_hotm", "Heart of the Mountain") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, gh / 2f + 4, 150, 22); }
    },

    // HotM Upgrade Reminder line (Mining): "HotM: N affordable, X tokens". Off by default.
    // Self-measuring; below the other mining cards' default places.
    HOTM_REMINDER("hotm_reminder", "HotM Upgrades") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, gh / 2f + 140, 150, 18); }
    },

    // Powder card (Mining): the Mithril / Gemstone / Glacite totals, session gain and rates. Sits
    // under the HotM tier, which is where these rows used to be drawn. Self-measuring.
    MINING_POWDER("mining_powder", "Powders") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, gh / 2f + 30, 150, 46); }
    },

    // Gemstone profit card (Mining): what this session's gemstones are worth, the projected rate and
    // the sell-now / combine comparison. Its own element and not part of the powder card on purpose -
    // powder and coins are different currencies with different uses, and one card blending them
    // would hide the tradeoff the card exists to show. Self-measuring.
    GEMSTONE_PROFIT("gemstone_profit", "Gemstone Profit") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, gh / 2f + 80, 150, 56); }
    },

    // Nucleus Run (Crystal Hollows): the run in progress, the last run and the lifetime totals.
    // Self-measuring.
    NUCLEUS_RUN("nucleus_run", "Nucleus Run") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 210f, gh / 2f - 60, 200, 110); }
    },

    // Cold card (Glacite Tunnels / Mineshafts): the Cold stat, coloured by how close it is to the
    // cap. Hidden whenever the value is unknown. Self-measuring.
    GLACITE_COLD("glacite_cold", "Glacite Cold") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 40, gh - 92, 80, 18); }
    },

    // Tool uses card (Mining/Foraging): what is left of a Pickonimbus-style limited-use tool. Sits
    // above the hotbar, since it is about the item currently in your hand. Self-measuring.
    MINING_TOOL("mining_tool", "Tool Uses Left") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 100, gh - 74, 120, 34); }
    },

    // Ability Ready line (Mining Helpers): "Pickobulus: in 42s" per ability cooling down.
    // Self-measuring; left of the hotbar, mirroring the tool card.
    ABILITY_READY("ability_ready", "Ability Cooldowns") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 220, gh - 74, 120, 16); }
    },

    // Garden Level card (Garden): level and progress from the tab widget. Self-measuring.
    GARDEN_LEVEL("garden_level", "Garden Level") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, 44, 150, 34); }
    },

    // Pest card (Garden): pests alive, infested plots, last spawn and the cooldown. Self-measuring.
    PEST_TIMER("pest_timer", "Pest Timer") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, 86, 150, 76); }
    },

    // The shared alert title (any feature's TITLE channel). Above the crosshair, where an alert has
    // to register while your eyes are on what you are doing.
    SBS_ALERT("sbs_alert", "Alert Title") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 90, gh / 2f - 70, 180, 24); }
    },

    // Pest cooldown countdown (Garden): just the time until pests can spawn again, for players who
    // want that one number without the whole pest card. Self-measuring.
    PEST_COOLDOWN("pest_cooldown", "Pest Cooldown") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, 166, 96, 26); }
    },

    // Pest spawn title. Above the crosshair: a spawn has to register while your eyes are on the crop
    // you are breaking, which is the centre of the screen.
    PEST_ALERT("pest_alert", "Pest Spawn Title") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 70, gh / 2f - 46, 140, 24); }
    },

    // Active routes (multi-route pathfinding): colour dot, label, distance per route. Self-measuring.
    ROUTE_LIST("route_list", "Route List") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f - 40, 140, 60); }
    },

    // Bestiary Tracker card (Combat): kills remaining to max for the pinned mob. Self-measuring.
    BESTIARY_TRACKER("bestiary_tracker", "Bestiary Tracker") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, gh / 2f + 150, 160, 56); }
    },

    // Shiny Pig session card (Year of the Pig): orbs / success rate / profit / drops. Self-measuring.
    SHINY_PIG_TRACKER("shiny_pig_tracker", "Shiny Pig Tracker") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f - 100, 150, 110); }
    },

    // Live Shiny Orb countdown (Year of the Pig). Sits above the crosshair: the 90s limit has to be
    // readable while chasing the pig, which is exactly when the eyes are on the centre of the screen.
    SHINY_ORB_TIMER("shiny_orb_timer", "Shiny Orb Timer") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 60, gh / 2f - 50, 100, 56); }
    },

    // Ability Damage card (Combat): the "Your X hit N enemies for Y damage." lines pulled out of
    // chat. Sits right of centre, near eye level - the numbers are read mid-fight. Self-measuring.
    ABILITY_DAMAGE("ability_damage", "Ability Damage") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 70, gh / 2f + 10, 130, 70); }
    },

    // Melee Damage card (Combat): last hit / best hit / DPS from Damage Attribution's own-splash
    // matching. Mirrors the Ability Damage card on the left of centre. Self-measuring.
    MELEE_DAMAGE("melee_damage", "Melee Damage") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 200, gh / 2f + 10, 100, 56); }
    },

    // Damage Estimate card (Combat): predicted hit / crit / DPS against the mob under the
    // crosshair. Right of centre above the Ability Damage card - read while aiming. Self-measuring.
    DAMAGE_ESTIMATE("damage_estimate", "Damage Estimate") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 70, gh / 2f - 90, 120, 78); }
    },

    // Dungeon Score card (Dungeons): floor, secrets, crypts, deaths, cleared% and the estimated score.
    // Only visible inside a dungeon with the toggle on. Self-measuring, so this is the anchor only.
    DUNGEON_SCORE("dungeon_score", "Dungeon Score") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, 210, 158, 74); }
    },

    // God Potion / Booster Cookie remaining time, read off the tab list. Stacked top-left under the
    // server stats card: both are "glance at it now and then" numbers, not watched mid-fight. Each
    // card measures itself, so these are the anchors only.
    // Dev: /sbs perf - the KPI table, top-left under the buff cards.
    PERF_OVERLAY("perf_overlay", "SBS Perf (dev)") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, 110, 260, 200); }
    },
    GOD_POT_TIMER("god_pot_timer", "God Potion Timer") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, 24, 96, 24); }
    },
    COOKIE_BUFF_TIMER("cookie_buff_timer", "Cookie Buff Timer") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, 52, 96, 24); }
    },
    CAKE_BUFFS("cake_buffs", "Century Cake Buffs") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, 80, 140, 24); }
    },
    CONSUMABLE_TIMERS("consumable_timers", "Consumable Timers") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, 108, 150, 24); }
    },

    // Event clock (Dark Auction / Jacob / volcano / lobby age), top-right. Self-measuring, so this
    // is the anchor only.
    EVENT_TIMERS("event_timers", "Event Timers") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 132f, 4, 126, 60); }
    },

    // Pest widget rows the pest card does not cover (spray / repellent / bonus). Garden-only, so it
    // sits under the pest card's default slot. Self-measuring.
    /** Pest Profit card (Garden Helpers): pests killed, their drops and value. Self-measuring. */
    PEST_PROFIT("pest_profit", "Pest Profit") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 190f, gh / 2f - 40, 170, 90); }
    },
    PEST_STATUS("pest_status", "Pest Status") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, 166, 150, 56); }
    },

    // Beacon Tuner card: the beat's pitch and measured speed. Only ever drawn in the beacon's own
    // zone, so it shares the left column rather than competing for the crowded right edge.
    BEACON_TUNER("beacon_tuner", "Beacon Tuner") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(6, 120, 150, 62); }
    },

    // Sprayonator card: the running spray with its icon and countdown, plus the one before it.
    // Garden-only, so it continues the pest column under the status card. Self-measuring.
    SPRAYONATOR("sprayonator", "Sprayonator") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, 228, 150, 52); }
    },

    // Looking At chip: names the block / mob under the crosshair. Just below the crosshair, where
    // the eye already is - it is read while aiming, never hunted for. Self-measuring.
    LOOKING_AT("looking_at", "Looking At") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 55, gh / 2f + 14, 110, 26); }
    },

    // "Your Milestone" from the party-stats tab widget. Left of centre at eye level: it is read
    // mid-fight, like the damage cards. Self-measuring.
    MILESTONE("milestone", "Milestone") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f - 130, 110, 30); }
    },

    // Livid card (Dungeons): the identified Livid, its health and the fight clock. Right of centre at
    // eye level - it is read mid-fight, while looking at the room. Self-measuring, anchor only.
    LIVID_TRACKER("livid_tracker", "Livid Tracker") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 100, gh / 2f + 10, 140, 42); }
    },

    // F7/M7 phase splits (Dungeons): one line per boss phase plus the total. Right-hand side under
    // the Dungeon Score card - both are run-status readouts. Self-measuring, anchor only.
    F7_PHASE_TIMER("f7_phase_timer", "Boss Phase Timer") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, 292, 130, 74); }
    },

    // M7 dragon card (Dungeons): the dragons that are up, their health and whether each is at its
    // statue. Right of centre at eye level - it is read while flying at one, like the Livid card.
    // Self-measuring, anchor only.
    M7_DRAGONS("m7_dragons", "M7 Dragons") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 100, gh / 2f - 46, 150, 60); }
    },

    // Goldor gate progress (Dungeons): terminals / devices / levers and who did them. Directly under
    // the phase card - the terminals ARE the Goldor split, and only one of the two is ever live at a
    // time. Self-measuring, anchor only.
    TERMINAL_PROGRESS("terminal_progress", "Terminal Progress") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 170f, 372, 132, 58); }
    },

    // Death-save cooldowns (Combat): Bonzo / Spirit / Phoenix. Left of centre at eye level, next to
    // the damage cards - it is glanced at mid-fight, not between fights. Self-measuring.
    ABILITY_TIMERS("ability_timers", "Death-Save Timers") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f - 90, 108, 42); }
    },

    // Blood room card (Dungeons): start-killing countdown, clear clock, mobs left. Right of centre at
    // eye level - read while fighting, like the Livid card it sits under. Self-measuring.
    BLOOD_HELPER("blood_helper", "Blood Helper") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 100, gh / 2f + 64, 132, 56); }
    },

    // Spirit Bear card (Dungeons): lantern-ring progress and the bear countdown, F4/M4 boss room
    // only. Same column as the blood card - it is the other thing read mid-fight. Self-measuring.
    SPIRIT_BEAR("spirit_bear", "Spirit Bear") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 100, gh / 2f + 64, 128, 42); }
    },

    // Fire Freeze card (Dungeons): the F3/M3 cast countdown. Just under the crosshair on the left of
    // centre - it is watched, not glanced at, for the two seconds it matters. Self-measuring.
    FIRE_FREEZE("fire_freeze", "Fire Freeze Timer") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 228, gh / 2f + 64, 128, 30); }
    },

    // Giant health (Dungeons): the F6/M6 giants, weakest first. Left of centre at eye level, opposite
    // the blood/Livid column - it is read mid-fight while facing one of them. Self-measuring.
    GIANT_HP("giant_hp", "Giant HP") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 238, gh / 2f - 40, 138, 58); }
    },

    // Guardian health (Dungeons): the F3/M3 Professor's four, weakest first. Same column as the giant
    // card and directly above it - they belong to different floors and can never share a screen, so
    // the only thing to keep clear of each other is the editor. Self-measuring.
    GUARDIAN_HP("guardian_hp", "Guardian HP") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 238, gh / 2f - 110, 138, 58); }
    },

    // Kuudra run card (Combat): tier, phase, the run and phase clocks and whichever of the supply
    // count / build percentage / boss health the phase is about, plus the splits. Right of centre at
    // eye level - it is read mid-run while facing the platform. Self-measuring, anchor only.
    KUUDRA("kuudra", "Kuudra") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 100, gh / 2f - 40, 140, 70); }
    },

    // Safari trip summary (Skills): the shard list of the trip you just left. Right-hand side at eye
    // level - it is read once, on arrival, and never watched. Self-measuring, so this is the anchor.
    HUNTING_SESSION("hunting_session", "Hunting Session") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 176f, gh / 2f + 70, 160, 90); }
    },
    SAFARI_SUMMARY("safari_summary", "Safari Summary") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 176f, gh / 2f - 60, 160, 120); }
    },

    // Rift clock (Rift): the ф countdown, the visit's maximum and the drain rate. Above the hotbar
    // and right of centre - it is glanced at constantly for the whole visit, so it sits where the
    // eye already goes without covering the crosshair. Only ever drawn inside the Rift.
    // Self-measuring, so this is the anchor only.
    RIFT_TIME("rift_time", "Rift Time") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 100, gh / 2f + 40, 96, 52); }
    },

    // Sweep card (Foraging): the effective Sweep Hypixel reports for the chop you just made. Right
    // of centre at eye level, the band the mod already uses for numbers read while facing what you
    // are doing - a chop is aimed at, like a mob. Self-measuring, so this is the anchor only.
    FORAGING_SWEEP("foraging_sweep", "Sweep") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f + 100, gh / 2f + 40, 120, 52); }
    },

    // Honey timers (Foraging): every honey tree cooldown, readiest first. Left-hand side below the
    // other list cards - it is a list you consult between trees rather than watch while chopping,
    // which is the band the mod already uses for those. Self-measuring, so this is the anchor only.
    HONEY_TIMERS("honey_timers", "Honey Timers") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f + 40, 132, 80); }
    },

    // Forge timers (Economy): each Dwarven Forge slot and when it is ready. Left-hand list band,
    // under the Honey card. Self-measuring, so this is the anchor only.
    FORGE_TIMERS("forge_timers", "Forge Timers") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, gh / 2f - 40, 140, 60); }
    },

    // Coins per Hour card (Economy): earned per active hour, session totals, optional breakdown.
    // Self-measuring; anchored top-left under the usual info cards.
    COINS_PER_HOUR("coins_per_hour", "Coins per Hour") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, 90, 140, 60); }
    },

    // Fallen Star line (Mining): the crash zone and time since, or the Cult while it is on.
    // Self-measuring; top centre, where a short-lived event reads without covering the crosshair.
    FALLEN_STAR("fallen_star", "Fallen Star") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 80, 24, 160, 16); }
    },

    // Crystal Hollows Lobby Closing: the lobby day and time to the warp cutoff. Self-measuring; top
    // centre under the Fallen Star slot (that one is Dwarven Mines only, this one Hollows only).
    LOBBY_DAY("lobby_day", "Lobby Day") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 75, 40, 150, 16); }
    },

    // Mining Events card (Mining): the running event, its countdown and the next-start window.
    // Self-measuring; left-hand list band under Coins per Hour, consulted between veins.
    MINING_EVENTS("mining_events", "Mining Events") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(4, 150, 170, 50); }
    },

    // Crystal Hollows minimap (Skills > Mining, off by default). Self-measuring square; the nominal
    // size here is the default side length. Top right, under the status effects.
    CH_MINIMAP("ch_minimap", "Crystal Hollows Minimap") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 118, 30, 112, 112); }
    },

    // The direction card for the target picked on the Crystal Hollows map. Self-measuring; top centre
    // under the Lobby Day card (both are Hollows only), where a heading is read without covering
    // the crosshair.
    CH_TARGET("ch_target", "Crystal Hollows Target") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 70, 60, 140, 18); }
    },

    // Build Tools card: placing keys, edit progress, the apply/cancel prompt, the guide's count.
    // Self-measuring; above the hotbar, centred, where a prompt for Enter / Esc is read.
    BUILD_TOOLS("build_tools", "Build Tools") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw / 2f - 120, gh - 110, 240, 50); }
    },

    // Build Tools help card: commands and keys for the stick, a selection or a hologram. Self-measuring;
    // right edge, mid height, clear of the crosshair and the Build Tools card.
    BUILD_HELP("build_help", "Build Tools Help") {
        @Override public Bounds defaultBounds(int gw, int gh) { return new Bounds(gw - 214, gh / 2f - 40, 210, 60); }
    };

    private final String id;
    private final String displayName;

    HudElement(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The element's default (untransformed) screen rectangle for the current GUI size. */
    public abstract Bounds defaultBounds(int guiWidth, int guiHeight);

    /**
     * The config module this element's own settings live in – what the editor's middle-click jumps to,
     * so "I want this thing to look different" does not mean hunting the sidebar for the module that
     * happens to own it.
     *
     * <p>Ids of self-registered modules are spelled out because those modules declare their id in
     * their own package (there is no shared constants file to reference, by design). Anything not
     * listed belongs to the GUI module, which owns the HUD as a whole.
     */
    public String settingsModuleId() {
        return switch (this) {
            case FISHING_ALERT, FISHING_HUD, SEA_CREATURE_LIST, BITE_ALERT, BAIT_COUNTER, GOLDEN_FISH ->
                    sbs.modid.client.core.module.ModuleManager.FISHING_ID;
            case TROPHY_FISH, TROPHY_SESSION -> "trophy_fish";
            case CHAT -> "better_chat";
            case CHAT_TABS -> "chat_tabs";
            case DUNGEON_MAP, DUNGEON_SCORE, LIVID_TRACKER, F7_PHASE_TIMER, TERMINAL_PROGRESS,
                 M7_DRAGONS, FIRE_FREEZE, GIANT_HP, GUARDIAN_HP ->
                    sbs.modid.client.core.module.ModuleManager.DUNGEONS_ID;
            case EQUIPPED_LOADOUT -> sbs.modid.client.core.module.ModuleManager.SKYBLOCK_MENU_ID;
            case INVENTORY_OVERLAY -> sbs.modid.client.core.module.ModuleManager.INVENTORY_OVERLAY_ID;
            case CHOCOLATE_FACTORY -> "chocolate_factory";
            case FREE_SLOTS -> "full_inventory";
            case BINGO_CARD -> "bingo_card";
            case SKILL_PROGRESS -> sbs.modid.client.core.module.ModuleManager.SKILL_PROGRESS_ID;
            case QUEST_GUIDE -> sbs.modid.client.core.module.ModuleManager.QUEST_GUIDE_ID;
            case CUSTOM_SCOREBOARD -> "custom_scoreboard";
            case AH_FLIP_POPUPS -> "ah_flip_alerts";
            case BAZAAR_ORDERS -> sbs.modid.client.core.module.ModuleManager.BAZAAR_ID;
            case CARRY_COUNTER -> "slayer_carry_counter";
            case COLLECTION_TRACKER -> "collection_tracker";
            case SLAYER_TRACKER, BLAZE_ATTUNEMENT -> "slayer";
            case COMPOSTER, FARMING_TRACKER, VISITOR_SHOPPING, VISITOR_TIMER, PEST_PROFIT -> "garden_helpers";
            case CROP_MILESTONE, FARMING_FORTUNE, HOE_LEVEL, JACOB_CONTEST, FARM_DROPS, FARMING_SPEED ->
                    sbs.modid.client.core.module.ModuleManager.FARMING_ID;
            case MINING_COMMISSIONS, MINING_HOTM, HOTM_REMINDER, MINING_POWDER, MINING_TOOL, ABILITY_READY ->
                    "mining_helpers";
            case GARDEN_LEVEL, PEST_TIMER, PEST_ALERT, PEST_COOLDOWN -> "garden";
            case PEST_STATUS -> "pest_status";
            case BEACON_TUNER -> "beacon_tuner";
            case SPRAYONATOR -> "sprayonator";
            case LOOKING_AT -> "looking_at";
            case MILESTONE -> "milestone";
            case BESTIARY_TRACKER -> "bestiary_tracker";
            case ROUTE_LIST -> "routes";
            case SHINY_PIG_TRACKER, SHINY_ORB_TIMER -> "year_of_the_pig";
            case GOD_POT_TIMER, COOKIE_BUFF_TIMER, CAKE_BUFFS, CONSUMABLE_TIMERS -> "buffs";
            case EVENT_TIMERS -> "event_timers";
            case ABILITY_DAMAGE -> "ability_damage";
            case MELEE_DAMAGE -> "damage_attribution";
            case DAMAGE_ESTIMATE -> "damage_overlay";
            case ABILITY_TIMERS -> "ability_timers";
            case BLOOD_HELPER -> "blood_helper";
            case SPIRIT_BEAR -> "spirit_bear";
            case KUUDRA -> "kuudra";
            case HUNTING_SESSION -> "hunting_session";
            case SAFARI_SUMMARY -> "safari_summary";
            case RIFT_TIME -> "rift_time";
            case FORAGING_SWEEP -> "foraging_sweep";
            case HONEY_TIMERS -> "honey_timer";
            case FORGE_TIMERS -> sbs.modid.client.core.module.ModuleManager.FORGE_ID;
            case COINS_PER_HOUR -> "coins_per_hour";
            case FALLEN_STAR -> "fallen_star";
            case LOBBY_DAY -> "reminders";
            case NUCLEUS_RUN -> "nucleus_run";
            case MINING_EVENTS -> "mining_events";
            case BUILD_TOOLS, BUILD_HELP -> "build_tools";
            case DIANA_TRACKER, DIANA_CREATURES -> "diana";
            default -> sbs.modid.client.core.module.ModuleManager.HYPIXEL_GUI_ID;
        };
    }

    /** A simple screen-space rectangle in GUI-scaled pixels. */
    public record Bounds(float x, float y, float w, float h) {

        public boolean contains(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }
}
