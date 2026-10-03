/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config;

import sbs.modid.client.core.config.share.Kind;
import sbs.modid.client.core.config.share.Shareable;
import sbs.modid.client.economy.bazaar.model.BazaarOrder;
import sbs.modid.client.skills.fishing.logic.FishingHudVisibility;
import sbs.modid.client.skills.fishing.logic.SpawnAlertFilter;
import sbs.modid.client.ui.hud.model.HealthBarMode;
import sbs.modid.client.ui.hud.model.ManaBarMode;
import sbs.modid.client.ui.hud.edit.model.HudTransform;
import sbs.modid.client.helper.rarity.RarityOverlayMode;
import sbs.modid.client.core.keybind.CommandKeybind;
import sbs.modid.client.economy.recipe.model.PanelSide;
import sbs.modid.client.helper.texture.model.TexturePackMode;
import sbs.modid.client.helper.visual.model.ExplosionMode;
import sbs.modid.client.helper.visual.model.FireOverlayMode;
import sbs.modid.client.helper.visual.model.PotionParticleMode;
import sbs.modid.client.helper.visual.model.WindowButtonStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain data object holding all persisted settings of the mod.
 *
 * <p>This is intentionally just a POJO so Gson can (de)serialize it directly.
 * As modules are added, each one gets its own nested settings object here (or a
 * dedicated config file), keeping per-feature settings cleanly separated.
 *
 * <p>{@code schemaVersion} lets future versions migrate old config files safely.
 */
public final class SBSConfig {

    /** Bumped whenever the config layout changes; enables migrations later. */
    public int schemaVersion = 1;

    /**
     * Marks a config that has been through the number-format migration. Boxed so {@code null} - a
     * config written before it existed - is distinguishable from a config that has already been
     * carried over; see {@code ConfigManager.migrateNumberFormats}.
     */
    public Boolean numberFormatsFollowGlobal;

    /** True until the GUI has been opened at least once. */
    public boolean firstLaunch = true;

    /**
     * Persisted developer-mode flag. {@code null} (the default) means off, so the key is absent from
     * {@code config.json} until dev mode is enabled and is removed again on disable. Mirrored at
     * runtime by {@code DevMode.ACTIVE}.
     */
    public Boolean devMode;

    /** GUI-related preferences. */
    public GuiSettings gui = new GuiSettings();

    /** User-defined command keybinds (a key paired with a command / chat message). */
    public List<CommandKeybind> keybinds = new ArrayList<>();

    /**
     * Every scoreboard area the player has stood in, learned over time. Feeds the keybind island
     * dropdown - Hypixel publishes no area list, so the game itself is the only correct source.
     */
    public Set<String> keybindAreas = new LinkedHashSet<>();

    /**
     * Learned scoreboard area -> the island the tab list said it was on. The pairing is the one thing
     * neither source publishes on its own, so it can only be observed live; it fills the gaps in the
     * seeded island table for places (and whole islands) added after this build.
     */
    public Map<String, String> keybindAreaIslands = new LinkedHashMap<>();

    /** User-defined command shortcuts (a short alias expanding to a full command anywhere in the mod). */
    public List<sbs.modid.client.core.keybind.CommandShortcut> commandShortcuts = new ArrayList<>();

    /** Built-in command shortcuts (/pw, /pt, prefix-free warping, ...). */
    public ShortCommandsSettings shortCommands = new ShortCommandsSettings();

    /** Bazaar module preferences. */
    public BazaarSettings bazaar = new BazaarSettings();

    /** Forge flip overlay (Economy): server-ranked forge profits per forge hour. */
    public ForgeSettings forge = new ForgeSettings();

    /** AH Flip Alerts (Economy): chat alerts for underpriced auction-house listings. */
    public AhFlipAlertSettings ahFlips = new AhFlipAlertSettings();

    /** Quality of Life: small comforts that do not belong to any one feature area. */
    public ConvenienceSettings convenience = new ConvenienceSettings();

    /** Fishing (Skills): spawn alert and the catch / sea creature / shard / profit trackers. */
    public FishingSettings fishing = new FishingSettings();

    /** Dungeon chest case-opening animation (cosmetic; never touches drops). */
    public CaseOpeningSettings caseOpening = new CaseOpeningSettings();

    /** Custom warp menu (Overlays): island boxes that run the warp commands. */
    public WarpMenuSettings warpMenu = new WarpMenuSettings();

    /** Hypixel GUI (HUD) module preferences. */
    public HypixelGuiSettings hypixelGui = new HypixelGuiSettings();

    /** Scrollable Tooltips module preferences. */
    public ScrollableTooltipsSettings scrollableTooltips = new ScrollableTooltipsSettings();

    /** Item Overlay module preferences. */
    public ItemOverlaySettings itemOverlay = new ItemOverlaySettings();

    /** Chat Options module preferences. */
    public ChatOptionsSettings chatOptions = new ChatOptionsSettings();

    /** Minecraft Overlay (global SBS theme) module preferences. */
    public MinecraftOverlaySettings minecraftOverlay = new MinecraftOverlaySettings();

    /** Visuals module preferences. */
    public VisualsSettings visuals = new VisualsSettings();

    /** Dark Mode (Visuals): client-side time, uniform brightness and world-wide block darkening. */
    public DarkModeSettings darkMode = new DarkModeSettings();

    public static final class DarkModeSettings {

        /**
         * Client Side Time: show the time of day this player picked instead of the server's.
         *
         * <p>Nothing is sent to the server and no other player is affected - the override lives in
         * the return value of the client's own world clock ({@code DarkModeClockMixin}), which is
         * what every time-driven visual is sampled from, so the sun really does stand where
         * {@link #timeOfDay} says.
         *
         * <p>Off by default. Unlike the End and Mist effects this is not gated on a location, so it
         * would otherwise change how the whole game looks the first time the mod is launched.
         */
        @Shareable(Kind.BOOL)
        public boolean clientTime = false;

        /**
         * The hour to show while {@link #clientTime} is on, 0-23 on a normal wall clock: 0 is
         * midnight, 6 sunrise, 12 noon, 18 sunset. Stored as an hour rather than as a raw tick count
         * because "18:00" is readable on the slider and 12000 is not.
         */
        public int timeOfDay = 0;

        /**
         * Uniform Brightness: light the entire world at {@link #brightness}, with no light gradient
         * left - a cave, a lit island and a dungeon corridor all read equally bright.
         *
         * <p>Distinct from Fullbright, which floors the lightmap at white and leaves the sky and
         * block light to add on top. This flattens the lightmap outright, which is what allows a
         * level <i>below</i> the natural one. Fullbright wins while both are on.
         */
        @Shareable(Kind.BOOL)
        public boolean uniformBrightness = false;

        /**
         * That uniform level, in percent: 0 is near black, 100 is as bright as vanilla ever gets.
         * The bottom of the range is clamped a hair above zero because the lightmap shader divides
         * by the largest channel of the colour it is given.
         */
        public int brightness = 50;

        /**
         * Darken Blocks: multiply a grey onto every block in the world, on the chunk-mesh seam the
         * End and Mist effects use ({@link sbs.modid.client.helper.visual.logic.DarkMode}).
         *
         * <p>This is the fallback layer: quads The End or The Mist already claimed keep their own
         * look, so those two stay overrides on their islands and this covers everywhere else.
         */
        @Shareable(Kind.BOOL)
        public boolean darkenBlocks = false;

        /**
         * Reverse brightness for those blocks, in percent, on the exact scale of the End and Mist
         * darkness sliders: 0 = their natural brightness, 100 = pitch black.
         */
        public int blockDarkness = 50;

        /**
         * Window Title Bar: paint the strip Windows draws above the game in windowed mode - the one
         * with the minimise, maximise and close buttons - in {@link #titleBarHex} instead of the
         * system's white.
         *
         * <p>Windows only, and only while the game is in a normal window: borderless and real
         * fullscreen have no title bar on screen to colour. Done through the documented
         * {@code DwmSetWindowAttribute} call on this one window - see
         * {@link sbs.modid.client.helper.visual.logic.WindowTitleBar}, which explains why it starts
         * no process and installs nothing.
         */
        @Shareable(Kind.BOOL)
        public boolean titleBar = false;

        /**
         * That colour, {@code RRGGBB}. Empty or unparsable falls back to the SBS panel navy, so the
         * frame matches the mod rather than turning black.
         */
        @Shareable(Kind.HEX_COLOR)
        public String titleBarHex = "0C2138";
    }

    /** Mob Highlight (Visuals): box a chosen set of entity types in the world. */
    public MobHighlightSettings mobHighlight = new MobHighlightSettings();

    /** Diana (Combat): the Mythological Ritual - burrows, guesses, chains and rare creatures. */
    public DianaSettings diana = new DianaSettings();

    /** Ghost Hunter (Combat): mark the ghost in The Mist that is cheapest to actually get on. */
    public GhostHunterSettings ghostHunter = new GhostHunterSettings();

    /** Third Person (Visuals): your own nametag + the crosshair, shown in third-person view. */
    public ThirdPersonSettings thirdPerson = new ThirdPersonSettings();

    /** SBS Players (Visuals): an SBS badge on the nametag of everyone else running the mod. */
    public SbsPlayersSettings sbsPlayers = new SbsPlayersSettings();

    /** Fonts (Interface): which font each kind of SBS text is drawn in. */
    public FontSettings fonts = new FontSettings();

    /**
     * Font choices, stored as {@code SbsFontRegistry} ids rather than indices or display names.
     *
     * <p>An id that no longer resolves - a bundled font dropped from a later build, a user font
     * deleted from the config folder - falls back to Minecraft's own font and is reported on the
     * settings page. It is deliberately <b>not</b> rewritten to the fallback on load: put the file
     * back and the selection returns, which is not true of a config that silently healed itself.
     */
    public static final class FontSettings {

        /** SBS screens, windows and settings rows. */
        public String ui = "vanilla_default";

        /** HUD cards and trackers drawn over the world. */
        public String hud = "vanilla_default";

        /** SBS lines in chat and the chat overlays. */
        public String chatOverlay = "vanilla_default";

        /** Tooltip lines SBS adds to items. */
        public String tooltip = "vanilla_default";

        /**
         * Replace Minecraft's own font as well, not just ours.
         *
         * <p>Off by default and it stays that way. Switching it on reaches outside our own
         * rendering: it competes with the player's resource packs and restyles other mods' text
         * too, which is a bargain worth offering and never worth making for somebody.
         */
        @Shareable(Kind.BOOL)
        public boolean overrideVanilla = false;
    }

    /** Theme (Visuals): the three base colours every SBS surface derives from. */
    public ThemeSettings theme = new ThemeSettings();

    public static final class ThemeSettings {
        /** Accent ({@code RRGGBB}): borders, highlights, the sci-fi blue family. */
        @Shareable(Kind.HEX_COLOR)
        public String accentHex = "3FB4FF";

        /**
         * The HUD card frame's overrides - priority 3 in
         * {@link sbs.modid.client.ui.hud.render.CardChrome}'s order.
         *
         * <p><b>Blank is the default and it means "the theme decides"</b>, which is what makes
         * "reset to priority 1" a matter of clearing them rather than restoring some remembered
         * value, and what lets a theme change reach every card nobody has pinned.
         */
        @Shareable(Kind.HEX_COLOR)
        public String hudCardBorderHex = "";
        @Shareable(Kind.HEX_COLOR)
        public String hudCardFillHex = "";
        @Shareable(Kind.HEX_COLOR)
        public String hudCardGlowHex = "";

        /**
         * Whether a card carries the soft glow behind it. On, because that is the frame the great
         * majority of cards already drew and the one the SBS panels use; the handful that drew a
         * flat frame instead were the odd ones out, which is the mismatch this whole block exists
         * to end.
         */
        @Shareable(Kind.BOOL)
        public boolean hudCardGlow = true;

        /** Background ({@code RRGGBB}): panels, cards, fills - the dark navy family. */
        @Shareable(Kind.HEX_COLOR)
        public String backgroundHex = "0C2138";

        /** Text ({@code RRGGBB}): primary text and bright highlights. */
        @Shareable(Kind.HEX_COLOR)
        public String textHex = "FFFFFF";

        /**
         * Which colours every chroma effect sweeps through
         * ({@link sbs.modid.client.helper.visual.render.ChromaPalette} name): the maxed-enchant
         * text shimmer, the active-pet bar and the profile viewer's maxed rows all read this one
         * value, the same way they already share a speed. Stored by name so an unknown value
         * degrades to the rainbow rather than breaking the config.
         */
        public String chromaPalette = "RAINBOW";

        /**
         * How fast every chroma effect cycles, in percent of the default rate ({@code 100} = one
         * full sweep per ~1.6s; lower is calmer, higher is faster). Range
         * {@link sbs.modid.client.helper.visual.render.Chroma#MIN_SPEED}-{@code MAX_SPEED}.
         *
         * <p><b>One speed for all of them, on purpose.</b> The phase comes from the wall clock, so
         * two effects on the same speed are in step wherever they appear together and read as one
         * effect showing up in several places; give them their own numbers and a tooltip and the HUD
         * behind it visibly disagree. This replaced three per-effect speeds that could do exactly
         * that - see {@code ItemOverlaySettings}' legacy fields.
         */
        @Shareable(value = Kind.INT, min = 10, max = 400)
        public int chromaSpeed = 100;

        /**
         * Stops for the {@code CUSTOM} chroma palette ({@code RRGGBB}), blended in order and looping
         * from the last back to the first.
         *
         * <p><b>Blank means unused</b>, which is how the count is chosen without a separate setting:
         * fill two for a back-and-forth, four for a full loop, one for a solid colour. All blank
         * falls back to the rainbow, so a half-configured Custom palette is never an invisible bar.
         */
        @Shareable(Kind.HEX_COLOR)
        public String chromaCustom1 = "FF5555";
        @Shareable(Kind.HEX_COLOR)
        public String chromaCustom2 = "FFD64D";
        @Shareable(Kind.HEX_COLOR)
        public String chromaCustom3 = "57D977";
        @Shareable(Kind.HEX_COLOR)
        public String chromaCustom4 = "";

        /**
         * Visual style of the whole mod ({@link sbs.modid.client.ui.theme.UiStyle} name).
         * {@code CLASSIC} is the original look and stays the default; {@code JUST_IDEA} squares the
         * windows and rounds everything inside them. Stored by name so an unknown value degrades to
         * Classic rather than breaking the config.
         */
        public String style = "CLASSIC";

        /**
         * How solid every SBS surface is, in percent of the alpha its style asked for: {@code 100}
         * is the style untouched, lower lets the world and the game behind show through.
         * See {@link sbs.modid.client.ui.theme.SBSTheme#applySurfaceOpacity}.
         *
         * <p><b>A multiplier over the style, not a value of its own</b>, which is what makes one
         * slider work for all nine of them: Glass at 100% is already see-through and Bug at 100% is
         * still near-black, and each keeps its own relations between panel, card and hover as it
         * fades.
         *
         * <p><b>Goes to 0</b>, where the mod's HUD is fully transparent and only its text and icons
         * are left over the game. Screens stop at
         * {@link sbs.modid.client.ui.theme.SBSTheme#MIN_SCREEN_OPACITY} however far the slider is
         * dragged - a screen faded to nothing is a screen whose buttons cannot be found, and this
         * slider is on one.
         */
        @Shareable(value = Kind.INT, min = 0, max = 100)
        public int surfaceOpacity = 100;
    }

    /** Experimentation Table (Skills): Chronomatron / Ultrasequencer / Superpairs helpers. */
    public MetalDetectorSettings metalDetector = new MetalDetectorSettings();

    /** Coins per Hour: the session's purse earnings per active hour. */
    public CoinsPerHourSettings coinsPerHour = new CoinsPerHourSettings();

    public static final class CoinsPerHourSettings {
        /** Master toggle. Off by default - a new card nobody asked for should not appear. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;
        /** No purse change and no movement for this many minutes stops the clock. */
        public int idleMinutes = 3;
        /** Show earned and spent by source (Bazaar / Auction / NPC / unknown) on the card. */
        @Shareable(Kind.BOOL)
        public boolean showBreakdown = true;
        /** Count coins claimed from a filled Bazaar sell offer as earnings. */
        @Shareable(Kind.BOOL)
        public boolean bazaarClaimsEarn = true;
        /** Count coins collected from a sold auction as earnings. */
        @Shareable(Kind.BOOL)
        public boolean auctionClaimsEarn = true;
    }

    /** Fallen Star: crash alert, zone waypoint and HUD line in the Dwarven Mines. */
    public FallenStarSettings fallenStar = new FallenStarSettings();

    public static final class FallenStarSettings {
        /** On by default: the crash line it reads is confirmed from the instance logs. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;
        /** Where the crash alert goes. */
        public int alertChannels = sbs.modid.client.core.alert.AlertChannel.CHAT.bit()
                | sbs.modid.client.core.alert.AlertChannel.SOUND.bit();
        /** A transient waypoint on the named zone (approximate). */
        @Shareable(Kind.BOOL)
        public boolean waypoint = true;
        /** The HUD line while a star or the Cult is active. */
        @Shareable(Kind.BOOL)
        public boolean hud = true;
    }
    public MiningEventSettings miningEvents = new MiningEventSettings();

    /** Mining Event Timer (Skills > Mining): current event, remaining time, next-start window. */
    public static final class MiningEventSettings {
        /** On by default: the chat lines it runs on are confirmed from the instance logs. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;
        /** The Mining Events HUD card. */
        @Shareable(Kind.BOOL)
        public boolean hud = true;
        /** The second line about the other mining island's latest event. */
        @Shareable(Kind.BOOL)
        public boolean otherIsland = true;
        /** Resync the countdown from the sidebar. The sidebar shape is unverified. */
        @Shareable(Kind.BOOL)
        public boolean readScoreboard = true;
        /** Log matched chat lines and new sidebar lines under [SBS][MiningEvents]. */
        public boolean captureLog = true;
        /** {@code MiningEvent} ids whose start alerts. 2x Powder only by default. */
        public java.util.List<String> startAlerts = new java.util.ArrayList<>(java.util.List.of("TWO_X_POWDER"));
        /** {@code MiningEvent} ids that alert a minute before they end. */
        public java.util.List<String> endingAlerts = new java.util.ArrayList<>();
        /** Alert when an estimated next start is within two minutes. */
        @Shareable(Kind.BOOL)
        public boolean nextAlert = false;
        /** Alert when the Powder Ghast spawns. */
        @Shareable(Kind.BOOL)
        public boolean ghastAlert = false;
        /** Where every mining-event alert goes. */
        public int alertChannels = sbs.modid.client.core.alert.AlertChannel.CHAT.bit()
                | sbs.modid.client.core.alert.AlertChannel.SOUND.bit();
    }
    public ExperimentationSettings experimentation = new ExperimentationSettings();

    public static final class MetalDetectorSettings {
        /**
         * Off by default, and deliberately: the action-bar wording the whole feature reads has
         * never been checked against a real Mines of Divan, so it may never fire - and a helper
         * that silently does nothing is worse than one you switched on yourself. Turn it on once
         * `docs/features/metal-detector.md` says the wording is confirmed.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Publish the solved spot as a waypoint (the beam and distance you already know). */
        @Shareable(Kind.BOOL)
        public boolean waypoint = true;

        /** Outline the solved block in the world. */
        @Shareable(Kind.BOOL)
        public boolean box = true;
    }

    public static final class ExperimentationSettings {
        /** Master toggle for the whole Experimentation Table helper. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Chronomatron: remember the flashed sequence and highlight it back in order. */
        @Shareable(Kind.BOOL)
        public boolean chronomatron = true;

        /** Ultrasequencer: keep the numbers shown at their slots and highlight the next one. */
        @Shareable(Kind.BOOL)
        public boolean ultrasequencer = true;

        /**
         * Ultrasequencer: shade the tiles by their position in the sequence (a green brightness
         * gradient) instead of a flat "next bright, rest dark". Off by default - the shifting shades
         * read as the order changing mid-round, which is exactly the confusion it was meant to avoid.
         */
        @Shareable(Kind.BOOL)
        public boolean ultrasequencerOrderGradient = false;

        /** Superpairs: keep revealed icons shown after they flip back over. */
        @Shareable(Kind.BOOL)
        public boolean superpairs = true;

        /** Block out-of-order clicks in Chronomatron / Ultrasequencer (never Superpairs). */
        @Shareable(Kind.BOOL)
        public boolean blockMisclicks = true;

        /** Warn in chat when the table is opened without a Guardian pet equipped. */
        @Shareable(Kind.BOOL)
        public boolean guardianPetAlert = true;

        /** Melody's Harp helper: the timing cue + next-notes preview. Display only. */
        @Shareable(Kind.BOOL)
        public boolean harp = true;
        /** Ping for the lead: true = rolling average measured while the Harp is open. */
        @Shareable(Kind.BOOL)
        public boolean harpPingAuto = true;
        /** Ping used when {@link #harpPingAuto} is off, in ms. */
        public int harpManualPingMs = 80;
        /** Reaction time added to the lead, in ms. 250 = a young adult's average (ESTIMATED). */
        public int harpReactionMs = 250;
        /** The row a click counts in, 0 = top. 4 (the terracotta) is UNVERIFIED. */
        public int harpHitRow = 4;
        /** Cue and preview colour, RRGGBB. */
        public String harpColorHex = "5DE0A0";
        /** Write "NOW" on the lit hit slot. */
        @Shareable(Kind.BOOL)
        public boolean harpFlash = true;
    }

    /** Settings shared by every Mining module (Mining Routes, Frozen Corpse highlight, ...). */
    public MiningSettings mining = new MiningSettings();

    public static final class MiningSettings {
        /**
         * Run the mining features only on mining islands
         * ({@link sbs.modid.client.skills.SkillIslands#MINING_ISLANDS}). One field rather than one per
         * module, because "mining features belong on mining islands" is a single decision - having to
         * make it again in every mining module would be a chore, not a choice.
         */
        @Shareable(Kind.BOOL)
        public boolean islandLock = true;
    }

    /** Mining Helpers module: commission progress, Heart of the Mountain / powder, tool durability. */
    public MiningHelpersSettings miningHelpers = new MiningHelpersSettings();

    public static final class MiningHelpersSettings {
        /** Master toggle for every card on this page. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** The commission card: one row per active commission with its progress. */
        @Shareable(Kind.BOOL)
        public boolean commissions = true;
        /** Announce in chat + play a sound the moment a commission reaches 100%. */
        @Shareable(Kind.BOOL)
        public boolean commissionDoneAlert = true;
        /** Hide a commission's row once it is complete, leaving only the ones still to do. */
        @Shareable(Kind.BOOL)
        public boolean hideCompletedCommissions = false;

        /** The Heart of the Mountain card: the tier. */
        @Shareable(Kind.BOOL)
        public boolean hotm = true;
        /** The HotM perk advisor's panel beside the Heart of the Mountain menu. Display only. */
        @Shareable(Kind.BOOL)
        public boolean hotmAdvisorPanel = true;
        /** The advisor's goal profile, as an index into hotm-strategies.json. */
        public int hotmAdvisorGoal = 0;

        /**
         * HotM Upgrade Reminder: alert when a watched perk's next level is affordable with the powder
         * you have. Which perks are watched is per profile, in the HotM tree cache, not here.
         */
        @Shareable(Kind.BOOL)
        public boolean hotmReminder = true;
        /** How its alerts reach the player (an {@code AlertChannels} mask). Chat + sound. */
        public int hotmReminderChannels = sbs.modid.client.core.alert.AlertChannel.CHAT.bit()
                | sbs.modid.client.core.alert.AlertChannel.SOUND.bit();
        /** "HotM: N upgrades affordable" when that number rises, at most once per 10 minutes. */
        @Shareable(Kind.BOOL)
        public boolean hotmReminderSummary = true;
        /** Unspent Tokens of the Mountain: once per session on a mining island, and after a tier-up. */
        @Shareable(Kind.BOOL)
        public boolean hotmReminderTokens = true;
        /** Outline affordable perks and mark watched ones in the HotM menu. Display only. */
        @Shareable(Kind.BOOL)
        public boolean hotmReminderHighlight = true;
        /** Toggles watching the hovered perk in the HotM menu. Unbound (0) by default. */
        public int hotmWatchKey = 0;
        /** The "HotM: N affordable · X tokens" HUD line on mining islands. */
        @Shareable(Kind.BOOL)
        public boolean hotmReminderHud = false;
        /**
         * The powder card: Mithril / Gemstone / Glacite totals with this session's gain. Its own card
         * and its own toggle since 2026-08 - these rows used to be drawn inside the Heart of the
         * Mountain card, which meant the two could not be placed or turned off independently.
         */
        @Shareable(Kind.BOOL)
        public boolean powder = true;
        /** Add the powder-per-hour rates measured over this session. */
        @Shareable(Kind.BOOL)
        public boolean powderRate = true;

        /**
         * Outline the Crystal Hollows treasure chest you uncovered. Off by default: the spawn line
         * and the chest block it is paired with are unverified - see
         * {@code docs/features/hollows-treasure-chest.md}.
         */
        @Shareable(Kind.BOOL)
        public boolean treasureChestBox = false;
        /** Mark the spot the lockpick particles point at on that chest. Off: unverified, as above. */
        @Shareable(Kind.BOOL)
        public boolean lockpickMarker = false;
        /** Count chests opened and the powder they paid, in the Powder card. Off: unverified. */
        @Shareable(Kind.BOOL)
        public boolean treasureChestCounter = false;

        /**
         * The tool card: how much of a limited-use tool (Pickonimbus, Jungle Axe, ...) is left.
         * Not gated on the mining islands – the same tools are swung while foraging.
         */
        @Shareable(Kind.BOOL)
        public boolean toolDurability = true;
        /** Warn once when the held tool drops below {@link #toolWarnPercent} of its uses. */
        @Shareable(Kind.BOOL)
        public boolean toolWarn = true;
        /** The remaining-uses percentage that triggers that warning. */
        public int toolWarnPercent = 10;

        /**
         * Commission Route: mark where each active commission is done and route to the nearest.
         *
         * <p>Part of the commission card rather than a feature of its own, so it needs
         * {@link #commissions} on as well. Costs nothing until a commission is actually running.
         */
        public boolean commissionRoute = true;

        /**
         * Pick the routed commission automatically - the nearest incomplete one by route, moving on
         * by itself as each is finished. Off, the route stays on whichever commission was chosen by
         * hand and never changes on its own.
         */
        public boolean commissionRouteAuto = true;

        /**
         * Mark every commission whose place is known, not only the routed one. The route line always
         * goes to exactly one of them; this decides whether the others get a marker as well.
         */
        public boolean commissionRouteMarkAll = false;

        /** Cycles the route to the next commission by hand; 0 = unbound. */
        public int commissionRouteKey = 0;

        /**
         * Ability Ready Alert: tell the player when Hypixel says an ability is available again.
         * Not gated on the mining islands - axe abilities are used while foraging.
         */
        @Shareable(Kind.BOOL)
        public boolean abilityReady = true;
        /** How the ready alert reaches the player (an {@code AlertChannels} mask). */
        public int abilityReadyChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;
        /** Every ability ever seen announced, and whether its alert is on. Grows as new ones appear. */
        public Map<String, Boolean> abilityReadyAbilities = new LinkedHashMap<>();
        /** Cooldown in seconds learned per ability: the gap from its use line to its ready line. */
        public Map<String, Integer> abilityReadyLearned = new LinkedHashMap<>();
        /** Only alert while the tool the ability was last used with is in hand. Off by default. */
        @Shareable(Kind.BOOL)
        public boolean abilityReadyOnlyHolding = false;
        /** The small "Pickobulus: in 42s" HUD line. */
        @Shareable(Kind.BOOL)
        public boolean abilityReadyHud = false;

        /** The "Cold: N" card in the Glacite Tunnels and Mineshafts. */
        @Shareable(Kind.BOOL)
        public boolean coldCard = true;
        /** Warn once when Cold reaches {@link #coldWarnPercent} of {@link #coldCap}. */
        @Shareable(Kind.BOOL)
        public boolean coldWarning = true;
        /** How the Cold warning reaches the player (an {@code AlertChannels} mask). */
        public int coldWarningChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;
        /** Warning threshold as a percentage of the cap. */
        public int coldWarnPercent = 75;
        /** The Cold at which you are removed from the area. WIKI value (100), not yet seen in game. */
        public int coldCap = 100;
    }

    /** Gemstone Profit module: coins per hour from mined gemstones, priced off the Bazaar. */
    public GemstoneProfitSettings gemstoneProfit = new GemstoneProfitSettings();

    public static final class GemstoneProfitSettings {
        /**
         * Master toggle. Off by default: it costs a Bazaar refresh cycle to keep the prices warm, and
         * a player who does not mine gemstones should not pay for that.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** The profit card itself. */
        @Shareable(Kind.BOOL)
        public boolean card = true;
        /** Add the projected coins-per-hour beside the session total. */
        @Shareable(Kind.BOOL)
        public boolean perHour = true;
        /** Break the card down per gemstone rather than showing one total. */
        @Shareable(Kind.BOOL)
        public boolean perGemstone = false;
        /**
         * Show the sell-now vs combine-and-sell comparison. Separate toggle because the ratios behind
         * it are the unverified part of the feature - a player who does not want a recommendation
         * built on a hypothesis can have the raw figure alone.
         */
        @Shareable(Kind.BOOL)
        public boolean combineAdvice = true;
        /** Warn when the projected hourly output is large against what the product actually trades. */
        @Shareable(Kind.BOOL)
        public boolean volumeWarning = true;

        /**
         * Bazaar Flipper level, which sets the sell tax. Defaults to 0 - the highest tax - because
         * assuming the worst under-promises, and an under-promised profit figure is the cheap error.
         */
        public int bazaarFlipperLevel = 0;

        /** Row colour for the card's total line. */
        public int totalColor = 0xFF57D977;
        /** Row colour for a projection, distinct from a measured figure. */
        public int projectionColor = 0xFF4DC3E0;
        /** Row colour for a warning (thin volume, unverified ratio). */
        public int warningColor = 0xFFFFC12E;
    }

    /** Nucleus Run Profit Tracker: profit per Crystal Nucleus run and the lifetime total. */
    public NucleusRunSettings nucleusRun = new NucleusRunSettings();

    public static final class NucleusRunSettings {
        /**
         * Master toggle. Off by default: built on chat lines from the old client's logs and never yet
         * watched in game on this one (docs/features/nucleus-run-profit-tracker.md).
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;
        /** The Nucleus Run card. */
        @Shareable(Kind.BOOL)
        public boolean card = true;
        /** Show the card off the Crystal Hollows too (the run keeps counting either way). */
        @Shareable(Kind.BOOL)
        public boolean showEverywhere = false;
        /** A chat summary after each bundle. */
        @Shareable(Kind.BOOL)
        public boolean chatSummary = true;
        /** 0 = Bazaar instant-sell, 1 = sell offer. */
        public int priceSide = 0;
        /** Value every used-up item at market, even one this run looted itself. */
        @Shareable(Kind.BOOL)
        public boolean countSelfObtainedCosts = true;
        /** Write the [SBS][Nucleus] capture lines to the log. */
        @Shareable(Kind.BOOL)
        public boolean captureLog = true;
        /** Remind when crystals are about to be or are being placed without the Mole as active pet. */
        @Shareable(Kind.BOOL)
        public boolean moleReminder = true;
        /** Also mention a Mole's extra-drop chance below level 100, once per run. */
        @Shareable(Kind.BOOL)
        public boolean moleChance = false;
        /** How the Mole reminder reaches the player (an {@code AlertChannels} mask). */
        public int moleReminderChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;
        /**
         * Jungle Temple cheese waypoint, at a fixed offset from the Kalhuiki Door Guardian. Off by
         * default: the offset has not been checked in game (docs/features/jungle-temple-cheese-waypoint.md).
         */
        @Shareable(Kind.BOOL)
        public boolean templeCheese = false;
        /** The waypoint's colour as RRGGBB; empty for the default. */
        @Shareable(Kind.HEX_COLOR)
        public String templeCheeseColorHex = "";
    }

    /** Mining Routes module: custom waypoint routes with connecting lines. */
    public MiningRoutesSettings miningRoutes = new MiningRoutesSettings();

    /** Frozen Corpse highlight (Glacite Mineshafts): box corpses so a spotted one can be found again. */
    public FrozenCorpseSettings frozenCorpse = new FrozenCorpseSettings();

    /** Pickobolus preview: highlights the blocks the thrown-pickaxe ability would break. */
    public PickobolusSettings pickobolus = new PickobolusSettings();

    public static final class PickobolusSettings {
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Highlight colour for the affected blocks. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor color =
                sbs.modid.client.core.render.OverlayColor.GREEN;

        /**
         * Fallback explosion radius. Verified as 3 in-game at every ability level - only the cooldown
         * scales - and the pickaxe's own lore is read first, so this is only reached when the lore
         * states no radius.
         */
        public int radius = 3;

        /** How far ahead the aim ray looks for the impact block. */
        public int aimRange = 40;

        /** Outline the whole affected sphere, not just the blocks inside it. */
        @Shareable(Kind.BOOL)
        public boolean showAreaOutline = true;

        /** Shade the affected blocks in as well as outlining them. */
        @Shareable(Kind.BOOL)
        public boolean fillBlocks = true;

        /**
         * Narrow the highlight to blocks whose registry id looks like an ore. Off by default: Hypixel's
         * ores are ordinary blocks wearing a texture pack, so the guess misses more than it helps until
         * it has been tuned against a real mine.
         */
        @Shareable(Kind.BOOL)
        public boolean oresOnly = false;
    }

    public static final class FrozenCorpseSettings {
        @Shareable(Kind.BOOL)
        public boolean enabled = false;
        /** Box colour for spotted corpses. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor color = sbs.modid.client.core.render.OverlayColor.CYAN;

        /** Tracer line from the crosshair to every remembered corpse. */
        @Shareable(Kind.BOOL)
        public boolean showTracers = false;
    }

    /** NPC module: waypoint + pathfinding to the NPC the scoreboard objective names. */
    public NpcSettings npc = new NpcSettings();

    public static final class NpcSettings {
        /**
         * Legacy master switch, no longer read. It shipped {@code false} and is written into every
         * existing config, so flipping its default would have left everyone upgraded still off - and
         * reusing it for the new meaning would read as a random reset. {@link #objectiveRouting} is
         * the switch now: a new field, absent from old configs, so it arrives on for everyone.
         */
        public boolean enabled = false;

        /**
         * Route to whatever the scoreboard Objective names - an NPC or a catalogued place. On by
         * default since 2026-09-25; turning it off is saved like any toggle and sticks.
         */
        @Shareable(Kind.BOOL)
        public boolean objectiveRouting = true;

        /** Match catalogued places and zones as well as NPCs. */
        @Shareable(Kind.BOOL)
        public boolean includePlaces = true;

        /** Key that dismisses the current objective's route until the objective changes. 0 = unbound. */
        @Shareable(Kind.KEYCODE)
        public int dismissKey = 0;

        /** Place a world marker on the objective's NPC. */
        @Shareable(Kind.BOOL)
        public boolean waypoint = true;

        /** Route to the marker, not just show it. */
        @Shareable(Kind.BOOL)
        public boolean pathfinding = true;
    }

    /** Rift Time: the ф countdown as a HUD card, with low-time and area warnings. */
    public RiftTimeSettings riftTime = new RiftTimeSettings();

    public static final class RiftTimeSettings {
        /** Master switch - off draws nothing, reads nothing and alerts about nothing. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Put the visit's starting time under the countdown. */
        @Shareable(Kind.BOOL)
        public boolean showMax = true;

        /** Draw the remaining fraction as a bar under the countdown. */
        @Shareable(Kind.BOOL)
        public boolean showBar = true;

        /** Say when the area you are in runs the clock at anything but normal speed. */
        @Shareable(Kind.BOOL)
        public boolean showDrainRate = true;

        /** Put the motes purse on the card too, for players who want one Rift card rather than two. */
        @Shareable(Kind.BOOL)
        public boolean showMotes = false;

        /**
         * Colour thresholds, as a percentage of the time you entered with. Amber at or below
         * {@code warnPercent}, red at or below {@code criticalPercent}, green above both.
         */
        public int warnPercent = 40;
        public int criticalPercent = 15;

        /**
         * Alert thresholds, in seconds remaining. Seconds rather than percentages because this is the
         * one that has to survive not knowing the maximum - a client that joined an in-progress visit
         * has no trustworthy fraction, and "two minutes left" is meaningful either way.
         */
        public int warnSeconds = 120;
        public int criticalSeconds = 30;

        /** Warn about areas that need a minimum, and about being too deep in to walk out. */
        @Shareable(Kind.BOOL)
        public boolean areaWarnings = true;

        /** Which channels the warnings go out on. */
        public int alertChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;
    }

    /** Vampire helper: Blood Effigy state around Stillgore Château. Informational only. */
    public VampireSettings vampire = new VampireSettings();

    public static final class VampireSettings {
        /** Master switch - off scans nothing and draws nothing. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Mark the Blood Effigies and how much of each is left. */
        @Shareable(Kind.BOOL)
        public boolean effigies = true;

        /**
         * Warn while aiming at the base of a broken effigy.
         *
         * <p>The one warning worth interrupting for: the reset is silent, easy to do by accident,
         * and costs another full respawn.
         */
        @Shareable(Kind.BOOL)
        public boolean effigyResetWarning = true;

        /** Draw the respawn countdown on a broken effigy's marker. */
        @Shareable(Kind.BOOL)
        public boolean effigyRespawnTimer = true;

        /** Keep effigy markers visible through terrain. */
        @Shareable(Kind.BOOL)
        public boolean throughWalls = true;

        /** Standing effigy marker: preset, optional {@code RRGGBB} override, and opacity. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor standingColor =
                sbs.modid.client.core.render.OverlayColor.RED;
        @Shareable(Kind.HEX_COLOR)
        public String standingColorHex = "";
        public int standingOpacity = 90;

        /** Broken effigy marker - muted, since a broken one is not something to walk to. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor brokenColor =
                sbs.modid.client.core.render.OverlayColor.WHITE;
        @Shareable(Kind.HEX_COLOR)
        public String brokenColorHex = "";
        public int brokenOpacity = 40;

        /** Which channels the base-hit warning goes out on. */
        public int alertChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;
    }

    /** Enigma Souls: waypoints for the Rift's souls, in found / missing / unknown. */
    public EnigmaSoulSettings enigmaSouls = new EnigmaSoulSettings();

    public static final class EnigmaSoulSettings {
        /** Master switch - off draws nothing and records nothing. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Keep drawing souls already on record as found, dimmed. */
        @Shareable(Kind.BOOL)
        public boolean showFound = false;

        /**
         * Draw the souls that are neither on record nor confirmed missing.
         *
         * <p>On by default: on an established profile these are most of the field, and hiding them
         * would leave the player with an almost empty map and no explanation.
         */
        @Shareable(Kind.BOOL)
        public boolean showUnknown = true;

        /** Draw a line from the crosshair to each soul that is not found. */
        @Shareable(Kind.BOOL)
        public boolean showTracers = false;

        /** Keep markers visible through terrain. */
        @Shareable(Kind.BOOL)
        public boolean throughWalls = true;

        /** Put the distance in each marker's label. */
        @Shareable(Kind.BOOL)
        public boolean showDistance = true;

        /** Put the soul's data-file id in the label - for correcting coordinates, off by default. */
        @Shareable(Kind.BOOL)
        public boolean showSoulIds = false;

        /** Show how to actually collect the nearest soul under its marker. */
        @Shareable(Kind.BOOL)
        public boolean showInstructions = true;

        /** Hide the souls that need a second player - unreachable for somebody playing alone. */
        @Shareable(Kind.BOOL)
        public boolean hideMultiplayer = false;

        /** Hide the souls that are bought rather than found. */
        @Shareable(Kind.BOOL)
        public boolean hidePurchases = false;

        /** Say so in chat when a collected soul matched no catalogued coordinate. */
        @Shareable(Kind.BOOL)
        public boolean reportUnmatched = true;

        /** Missing (confirmed still out there): preset, optional {@code RRGGBB}, and opacity. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor missingColor =
                sbs.modid.client.core.render.OverlayColor.CYAN;
        @Shareable(Kind.HEX_COLOR)
        public String missingColorHex = "";
        public int missingOpacity = 100;

        /** Unknown (found by somebody, but not identified) - deliberately a different hue, not a dim
         * version of missing: the difference is a kind, not a degree. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor unknownColor =
                sbs.modid.client.core.render.OverlayColor.YELLOW;
        @Shareable(Kind.HEX_COLOR)
        public String unknownColorHex = "";
        public int unknownOpacity = 70;

        /** Found - muted, since the field only empties out by these disappearing. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor foundColor =
                sbs.modid.client.core.render.OverlayColor.WHITE;
        @Shareable(Kind.HEX_COLOR)
        public String foundColorHex = "";
        public int foundOpacity = 35;

        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor tracerColor =
                sbs.modid.client.core.render.OverlayColor.CYAN;
        @Shareable(Kind.HEX_COLOR)
        public String tracerColorHex = "";
        public int tracerOpacity = 60;
    }

    /** Fairy Souls: markers for every soul on the island, and routing to the nearest uncollected. */
    public FairySoulSettings fairySouls = new FairySoulSettings();

    public static final class FairySoulSettings {
        /** Master switch - off draws nothing and tracks nothing. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Route to a soul, not just mark them. */
        @Shareable(Kind.BOOL)
        public boolean pathfind = true;

        /**
         * Keep targeting to the island you are on.
         *
         * <p>On for now in every practical sense: crossing islands needs the inter-island edges that
         * arrive with the router work, and soul coordinates only mean anything on their own island.
         */
        @Shareable(Kind.BOOL)
        public boolean currentIslandOnly = true;

        /**
         * Draw souls already on record as collected. Off, they disappear the moment they are found.
         *
         * <p>Replaces an earlier {@code hideCollected} rather than flipping its default: that field
         * is written into every existing config.json as {@code false}, so a changed default would
         * have reached nobody who had already run the mod. A new name is absent from those files,
         * and absent means this default applies.
         */
        @Shareable(Kind.BOOL)
        public boolean showCollected = false;

        /** Draw a line from the crosshair to each uncollected soul. */
        @Shareable(Kind.BOOL)
        public boolean showTracers = false;

        /** Keep markers visible through terrain. */
        @Shareable(Kind.BOOL)
        public boolean throughWalls = true;

        /** Put the distance in each marker's label. */
        @Shareable(Kind.BOOL)
        public boolean showDistance = true;

        /** Put the soul's data-file id in the label - for correcting coordinates, off by default. */
        @Shareable(Kind.BOOL)
        public boolean showSoulIds = false;

        /** Show the data file's "where exactly" hint under the soul being routed to. */
        @Shareable(Kind.BOOL)
        public boolean showHints = true;

        /** Say so in chat when a collected soul matched no catalogued coordinate. */
        @Shareable(Kind.BOOL)
        public boolean reportUnmatched = true;

        /** Uncollected marker colour: preset, optional {@code RRGGBB} override, and opacity. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor uncollectedColor =
                sbs.modid.client.core.render.OverlayColor.PINK;
        @Shareable(Kind.HEX_COLOR)
        public String uncollectedColorHex = "";
        public int uncollectedOpacity = 100;

        /** Collected marker colour - muted by default so the field reads at a glance. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor collectedColor =
                sbs.modid.client.core.render.OverlayColor.WHITE;
        @Shareable(Kind.HEX_COLOR)
        public String collectedColorHex = "";
        public int collectedOpacity = 35;

        /** Tracer colour. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor tracerColor =
                sbs.modid.client.core.render.OverlayColor.PURPLE;
        @Shareable(Kind.HEX_COLOR)
        public String tracerColorHex = "";
        public int tracerOpacity = 55;
    }

    /** SkyBlock Map: the island maps, and clicking a place to be taken there. */
    public MapSettings map = new MapSettings();

    public static final class MapSettings {
        /** Master switch - off draws nothing and never sends a command. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Key that opens the map screen (GLFW key code; 0 = unbound). */
        @Shareable(Kind.KEYCODE)
        public int openKey = 0;

        /**
         * Whether clicking a place may run the warp commands that get you near it.
         *
         * <p>Off, a click still marks the place and draws the route - it just tells you which warp to
         * run instead of running it. Some players would rather no mod ever typed a command for them.
         */
        @Shareable(Kind.BOOL)
        public boolean autoWarp = true;

        /** Draw the walking route to the clicked place, not only the marker. */
        @Shareable(Kind.BOOL)
        public boolean showRoute = true;

        /** Keep the marker visible with terrain in the way. */
        @Shareable(Kind.BOOL)
        public boolean throughWalls = true;

        /**
         * Crystal Hollows: record where you walked into a structure and mark it on the map.
         *
         * <p>The Hollows are regenerated every few hours, so nothing in them has a coordinate worth
         * writing into a data file. What the map can show is what you have found in the lobby you
         * are in, which is what this switches on. Kept per lobby id for 6 hours (ch_map.json).
         */
        @Shareable(Kind.BOOL)
        public boolean hollowsDiscover = true;

        /** Crystal Hollows: also pin coordinates other players post in chat. */
        @Shareable(Kind.BOOL)
        public boolean hollowsChatPins = true;

        /**
         * Crystal Hollows Structure Sharing: exchange structure locations with other SBS players in
         * the same lobby. <b>Off by default</b>, because it opens a connection to the SBS backend;
         * it also needs the {@code hollows_structures} consent scope and a licence token.
         */
        @Shareable(Kind.BOOL)
        public boolean hollowsShare = false;

        /** Structure Sharing: send the structures you walk into (off = receive only). */
        @Shareable(Kind.BOOL)
        public boolean hollowsShareContribute = true;

        /**
         * Structure Sharing: show structures only one player has reported so far - as waypoints, on
         * the SkyBlock Map and on the Crystal Hollows map and minimap.
         */
        @Shareable(Kind.BOOL)
        public boolean hollowsShareShowUnconfirmed = true;

        /** Put the distance to the marker in its label. */
        @Shareable(Kind.BOOL)
        public boolean showDistance = true;

        /** Map zoom, as a percentage of the fit-to-window scale. */
        public int zoom = 100;

        /** Draw a marker for every catalogued warp on the island, alongside the places. */
        @Shareable(Kind.BOOL)
        public boolean showWarps = true;

        /** Key that opens the schematic Crystal Hollows map (GLFW key code; 0 = unbound). */
        @Shareable(Kind.KEYCODE)
        public int hollowsMapKey = 0;

        /** Crystal Hollows: the HUD minimap. Off by default. */
        @Shareable(Kind.BOOL)
        public boolean hollowsMinimap = false;

        /** Crystal Hollows minimap: side length in GUI pixels. */
        public int hollowsMinimapSize = 112;

        /** Crystal Hollows minimap: blocks from the centre to the edge. */
        public int hollowsMinimapRadius = 96;

        /** Crystal Hollows minimap: turn with the player instead of keeping north up. */
        @Shareable(Kind.BOOL)
        public boolean hollowsMinimapRotate = false;

        /** Crystal Hollows map and minimap: draw the cells you have stood in. */
        @Shareable(Kind.BOOL)
        public boolean hollowsShowTrail = true;

        /** Crystal Hollows map and minimap: draw the player's own markers. */
        @Shareable(Kind.BOOL)
        public boolean hollowsShowMarkers = true;
    }

    /**
     * Warp knowledge shared by the warp menu and the map: which fast travels this profile has been
     * observed to have. Not a preference - it is learned state, see
     * {@link sbs.modid.client.helper.warp.WarpAvailability}.
     */
    public WarpSettings warp = new WarpSettings();

    public static final class WarpSettings {
        /**
         * SkyBlock profile name → (warp command → {@code UNKNOWN}/{@code UNLOCKED}/{@code LOCKED}).
         *
         * <p>Keyed by profile because fast travel is unlocked per profile. Values are stored as
         * strings rather than the enum so a state written by a newer build parses here instead of
         * throwing on load.
         */
        public java.util.Map<String, java.util.Map<String, String>> warpStates =
                new java.util.LinkedHashMap<>();
    }

    /** Particles (Visuals): per-particle-type visibility. */
    public ParticleSettings particles = new ParticleSettings();

    public static final class ParticleSettings {
        /** Master switch - off renders every particle normally without clearing the selection. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * The registry ids ("minecraft:flame") of the particles that must NOT render. The hidden
         * set rather than the visible one is stored so an untouched install persists nothing, and
         * so particles added by a game update default to visible instead of silently off.
         *
         * <p>This stays the only set the renderer reads, whether it was ticked by hand or written
         * by a preset.
         */
        public Set<String> hiddenParticles = new LinkedHashSet<>();

        /**
         * Which ready-made selection is active. Defaults to Custom so a config written before
         * presets existed keeps its hand-made {@link #hiddenParticles} untouched on load.
         */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.helper.particles.model.ParticlePreset.class)
        public sbs.modid.client.helper.particles.model.ParticlePreset preset =
                sbs.modid.client.helper.particles.model.ParticlePreset.CUSTOM;

        /**
         * The hand-made selection parked while a preset is active, handed back when Custom is
         * picked again - so trying a preset out is never destructive.
         */
        public Set<String> customHidden = new LinkedHashSet<>();
    }

    /** Far Terrain (Visuals): remembered island terrain served back to the renderer. */
    public FarTerrainSettings farTerrain = new FarTerrainSettings();

    public static final class FarTerrainSettings {
        /** Off / Performance / On - Performance caps the radius and streams chunks in slowly. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.helper.terrain.FarTerrainMode.class)
        public sbs.modid.client.helper.terrain.FarTerrainMode mode =
                sbs.modid.client.helper.terrain.FarTerrainMode.OFF;

        /** How many chunks around the player remembered terrain is served for (1-128). */
        public int extraChunks = 32;

        /**
         * Load the map's ENTIRE remembered file at once, ignoring the radius and the live-window
         * cap. Off by default: it is the setting most likely to hurt, and it should be a deliberate
         * choice rather than something a slider drifts into.
         */
        @Shareable(Kind.BOOL)
        public boolean uncapped = false;

        /**
         * @deprecated Superseded by {@link #distanceFog}, which is the same scale read the way round
         *     a player expects. Kept only so an existing config migrates instead of resetting; read
         *     it through {@link sbs.modid.client.helper.terrain.FarTerrainFog#fogPercent()}.
         */
        @Deprecated
        public int fogClarity = 0;

        /**
         * How much distance fog to draw over far terrain, 0-100. <b>0 is no fog at all</b> and 100
         * is untouched vanilla fog.
         *
         * <p>This replaced a "clarity" value that ran the other way, because that is not how anyone
         * reads a fog setting: told to get rid of the haze, you set the fog slider to zero, and on
         * the old scale zero was the haziest it went. Boxed so that {@code null} means "an older
         * config that never had this field", which is what lets the old value be carried over
         * exactly once rather than silently replaced by a default.
         */
        public Integer distanceFog;

        /**
         * Narrow the drawn distance to what can actually be seen while the view is boxed in - a
         * cave, a tunnel, a slayer camp underground. On by default: it costs frames to draw terrain
         * hidden behind rock and there is nothing to see for it, and nothing is unloaded, so the
         * full view is back the moment the way opens.
         */
        @Shareable(Kind.BOOL)
        public boolean pauseWhenEnclosed = true;

        /**
         * Hold a framerate by giving far terrain back until it is met. Off by default - it moves
         * the drawn distance around on its own, which should be something the player asked for.
         */
        @Shareable(Kind.BOOL)
        public boolean autoFps = false;

        /** The framerate {@link #autoFps} aims at, 20-360. */
        public int targetFps = 120;

        /**
         * Our own copy of Minecraft's render distance, because Minecraft cannot keep one past its
         * own ceiling.
         *
         * <p>{@code options.txt} is read inside the {@code Options} constructor, long before any mod
         * has run, and {@code OptionInstance.set} does not clamp a value it considers illegal - it
         * replaces it with the option's default. So a render distance this module widened the slider
         * to accept is simply gone on the next launch, and reverts to vanilla's default rather than
         * even to its maximum. Storing it here and putting it back once the slider has been widened
         * again is the only way it survives a restart. 0 means "never recorded".
         */
        public int renderDistance = 0;

        /**
         * Key that switches the module off and back on, with a popup naming the new state. 0 =
         * unbound. See {@link sbs.modid.client.core.keybind.Keys} for the encoding.
         */
        @Shareable(Kind.KEYCODE)
        public int toggleKey = 0;

        /**
         * Key that logs which island the crosshair is aimed at - the measuring tool for lining the
         * neighbour islands up. 0 = unbound. Same encoding as {@link #toggleKey}.
         */
        @Shareable(Kind.KEYCODE)
        public int markKey = 0;

        /**
         * Allow islands to be drawn from each other's remembered terrain. Every island currently
         * sits in a coordinate frame of its own, so nothing is drawn from a neighbour whatever
         * this says; the switch is the frame table in {@code FarTerrainNeighborView}. Scenery
         * only: waypoints, pathfinding and the map keep speaking true coordinates.
         */
        @Shareable(Kind.BOOL)
        public boolean neighborView = true;

        /**
         * Also draw islands whose real position has not been measured yet, at a searched
         * artificial spot beside the world. Off by default: a made-up position is exactly what
         * reads as "the offset is wrong" - an unmeasured island is better invisible than
         * convincingly misplaced.
         */
        @Shareable(Kind.BOOL)
        public boolean unalignedBeside = false;
    }

    /** Memory Guard: watch the heap over long sessions and hand memory back before the game dies. */
    public MemorySettings memory = new MemorySettings();

    public static final class MemorySettings {
        /**
         * On by default. It costs one heap read every five seconds while nothing is wrong, and the
         * session it saves is the one that would otherwise have ended in an out-of-memory crash.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Heap percentage at which this mod's own caches are given back. */
        public int relievePercent = 85;

        /** Heap percentage at which remembered terrain is unloaded too. */
        public int emergencyPercent = 95;
    }

    /** Alerts: the shared channel system every feature that tells you something goes through. */
    public AlertSettings alerts = new AlertSettings();

    /**
     * How SBS reaches you, mod-wide.
     *
     * <p>Whether an event alerts at all stays with the feature; <i>which channels</i> it uses is a
     * per-alert mask on the feature's own settings. This block holds only what the channels
     * themselves need.
     *
     * <p>The desktop-notification settings that used to live here are gone (2026-08). Reaching the
     * OS meant an AWT tray that Minecraft's forced headless mode blocks, or spawning
     * {@code powershell.exe}, which pattern-matches malware and trips endpoint protection - an
     * unacceptable support and reputation cost. What it was for is covered from inside the process:
     * the sound channel has its own audio output that Minecraft's sliders cannot mute, and the
     * narrator channel speaks through the OS voice, which a fullscreen game cannot swallow.
     */
    public static final class AlertSettings {

        /**
         * Volume of the mod's own alert pings, in percent.
         *
         * <p>Independent of every Minecraft slider on purpose - the point of the channel is being
         * audible while the game is muted. Applied logarithmically, so 50% sounds like half volume.
         */
        @Shareable(value = Kind.INT, min = 1, max = 100)
        public int soundVolume = 70;

        /** Volume the narrator speaks alerts at, in percent of the system voice's own level. */
        @Shareable(value = Kind.INT, min = 1, max = 100)
        public int narratorVolume = 100;

        /**
         * Which language spoken numbers are spelled out in, so an announcement is not half English
         * and half whatever the system voice's locale happens to be. Auto follows Minecraft's own
         * language setting.
         */
        @Shareable(value = Kind.ENUM,
                enumType = sbs.modid.client.core.alert.NarratorLanguage.class)
        public sbs.modid.client.core.alert.NarratorLanguage narratorLanguage =
                sbs.modid.client.core.alert.NarratorLanguage.AUTO;

        /**
         * Whether the one-time notice about the removed desktop notifications has been shown.
         *
         * <p>Set by the migration, read once at the first world join after it. Kept in the config
         * rather than in memory so an update explains itself exactly once, not once per launch.
         */
        public boolean desktopRemovalNoticeShown = false;
    }

    /** Sound Manager: which sounds may play, at what volume - the mod's and the game's. */
    public SoundSettings sounds = new SoundSettings();

    /**
     * The Sound Manager module's state.
     *
     * <p>Entries are keyed by sound id ({@code minecraft:block.note_block.pling}). The map is read
     * on the sound engine's hot path, so it stays a plain map lookup - see
     * {@code SoundControl} for why nothing here may become a scan.
     */
    public static final class SoundSettings {

        /** Master switch: with this off the sound engine is not touched at all. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * {@code false} = blacklist (everything plays except the listed entries),
         * {@code true} = whitelist (nothing plays except the listed entries).
         *
         * <p>Whitelist mode silences the entire game until entries are added - including UI clicks
         * and warning cues - so the settings page confirms before switching into it.
         */
        @Shareable(Kind.BOOL)
        public boolean whitelistMode = false;

        /**
         * Muted sound ids in blacklist mode / allowed ids in whitelist mode. The meaning follows
         * {@link #whitelistMode}, so switching modes does not rewrite the list.
         */
        public java.util.List<String> listed = new java.util.ArrayList<>();

        /** Per-sound volume in percent, keyed by sound id. Absent means 100. */
        public java.util.Map<String, Integer> volumes = new java.util.HashMap<>();
    }

    /** Pelt Tracker (Trapper's Den): find the animal Trevor sends you after. */
    public PeltSettings pelt = new PeltSettings();

    public static final class PeltSettings {
        /** Master switch: no chat parsing, no scan, no boxes while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Box the quest animal once it is detected (through walls - finding it is the point). */
        @Shareable(Kind.BOOL)
        public boolean highlightAnimal = true;

        /** Box colour for the quest animal. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor color =
                sbs.modid.client.core.render.OverlayColor.ORANGE;

        /** Tracer line from the crosshair to the detected animal. */
        @Shareable(Kind.BOOL)
        public boolean showTracer = true;

        /** One chat line + ping the moment the animal is first detected. */
        @Shareable(Kind.BOOL)
        public boolean foundPing = true;

        /**
         * Draw the Trapper Crest's collected-pelt count over the item in the hotbar. The number is
         * read from the crest's own {@code Collected:} tooltip line, so it is whatever Hypixel says
         * rather than a count this mod keeps.
         */
        @Shareable(Kind.BOOL)
        public boolean crestCounter = true;

        /** Write Trevor's quest cooldown under his name while standing near him. */
        @Shareable(Kind.BOOL)
        public boolean trapperCooldown = true;

        /**
         * Fallback cooldown length in seconds, used to start the countdown after a hunt ends. Any
         * "come back in Ns" Trevor says overrides it outright - he knows the real number, this is
         * only what the countdown assumes until he has spoken.
         */
        public int cooldownSeconds = 20;
    }

    /** One shareable route: an ordered list of waypoints connected by lines. */
    public static final class MiningRoute {
        public String name = "Route";
        /** Line / marker colour, {@code RRGGBB}. */
        public String colorHex = "3FB4FF";
        /** Drawn in the world while true. */
        public boolean visible = true;
        /** Connect the last waypoint back to the first. */
        public boolean loop = false;
        /** Waypoints, each {@code {x,y,z}} block coordinate, in walk order. */
        public List<int[]> points = new ArrayList<>();
    }

    public static final class MiningRoutesSettings {
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** GLFW keys (0 = unbound): add a waypoint at your feet; open the routes screen. */
        @Shareable(Kind.KEYCODE)
        public int addWaypointKey = 0;
        @Shareable(Kind.KEYCODE)
        public int openKey = 0;

        /** Index of the route new waypoints are appended to. */
        public int selectedRoute = 0;

        public int lineWidth = 3;
        @Shareable(Kind.BOOL)
        public boolean showWaypointBoxes = true;
        @Shareable(Kind.BOOL)
        public boolean showLabels = true;

        /**
         * Legacy / migration only. Route data now lives in {@code config/sbs/miningroutes.txt} via
         * {@code MiningRouteStore}; this field is read once on first launch to migrate old routes out
         * of the config, then cleared. Do not write to it - use the store.
         */
        public List<MiningRoute> routes = new ArrayList<>();
    }

    /** Secret Routes module (Dungeons): record + annotate + render per-room secret routes. */
    public SecretRoutesSettings secretRoutes = new SecretRoutesSettings();

    public static final class SecretRoutesSettings {
        /** Master toggle for the module. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Draw the stored routes' waypoints in the world. */
        @Shareable(Kind.BOOL)
        public boolean showRoutes = true;
        /** Draw the movement path/trail between the points (uses the Pathfinding renderer + colour). */
        @Shareable(Kind.BOOL)
        public boolean showPathfinding = true;

        /**
         * Walk a computed route to the room's nearest uncollected secret.
         *
         * <p>Distinct from {@link #showPathfinding} despite the similar name, and the two are not
         * alternatives: that one draws the line you recorded, through the points you placed, and says
         * nothing about where you are standing now. This one is an A* search from your feet that
         * re-picks as secrets are collected, so it still answers "where next" in a room whose route
         * you joined halfway, or walked backwards.
         *
         * <p>On by default: the module exists to answer that question, and a router that has to be
         * found in the settings before it does anything is one most players never meet.
         */
        @Shareable(Kind.BOOL)
        public boolean pathfind = true;
        /** Render each point's description text above it. */
        @Shareable(Kind.BOOL)
        public boolean showDescriptions = true;
        /** Dim points the player has already walked past. */
        @Shareable(Kind.BOOL)
        public boolean dimPassed = true;
        /** Highlight the Dungeon Breaker blocks (numbered, own colour). */
        @Shareable(Kind.BOOL)
        public boolean showBreakerBlocks = true;

        /**
         * Line width of the route/trail, in pixels. The Pathfinding module has no such setting (its
         * width is a constant), so this is the Secret Routes equivalent - same meaning as the Mining
         * Routes line width.
         */
        public int lineWidth = 2;

        /**
         * Approximate occlusion check: skip / dim points the camera has no line of sight to. Only a
         * raycast approximation - the in-world render is hand-projected onto the HUD and has no depth
         * buffer, so true depth-testing is not possible here.
         */
        @Shareable(Kind.BOOL)
        public boolean depthCheck = false;

        /** Pearl/AOTV aim indicator: how many degrees off the crosshair may be and still read "on target". */
        public int aimToleranceDeg = 2;

        /** Index of the currently selected route within the detected room. */
        public int selectedRoute = 0;

        /**
         * The secret subtype the "Scan Items" action places, as the ordinal of
         * {@code SecretWaypoint.Secret} (0=Chest,1=Lever,2=Bat,3=Item,4=Wither Essence). Shared by
         * the keybind and the editor's cycle button. Defaults to Item.
         */
        public int scanSubtype = 3;

        /** GLFW keys (0 = unbound) for the in-world recording actions. */
        @Shareable(Kind.KEYCODE)
        public int scanItemKey = 0;
        @Shareable(Kind.KEYCODE)
        public int addStandingKey = 0;
        @Shareable(Kind.KEYCODE)
        public int addAotvKey = 0;
        @Shareable(Kind.KEYCODE)
        public int addPearlKey = 0;
        @Shareable(Kind.KEYCODE)
        public int openKey = 0;
    }

    /** Abiphone module: render the Abiphone menus as a fancy phone UI. */
    public AbiphoneSettings abiphone = new AbiphoneSettings();

    public static final class AbiphoneSettings {
        /** Master toggle: reskin every Abiphone menu as a phone (default off, it renders). */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;
        /** Show the phone status bar (carrier + clock + signal + battery). */
        @Shareable(Kind.BOOL)
        public boolean statusBar = true;
    }

    /** Carry Tickets module: open carry tickets, verified carriers, per-ticket chat. */
    public CarrySettings carry = new CarrySettings();

    public static final class CarrySettings {
        /** Master toggle for the Carry Tickets module. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** GLFW key that opens the Carry Tickets screen (0 = unbound). */
        @Shareable(Kind.KEYCODE)
        public int openKey = 0;
    }

    /** Party highlight module: highlight your Hypixel party members on the island (box + nametag). */
    public PartyHighlightSettings partyHighlight = new PartyHighlightSettings();
    /**
     * @deprecated Legacy key from when this was called "ESP". Boxed so that "absent"
     *     is distinguishable from "false" - a rename must not silently reset a setting
     *     the player switched on. Read once by {@code ConfigManager.migrateHighlightNames}
     *     and nulled; remove once no config in the wild still carries it.
     */
    @Deprecated
    public PartyHighlightSettings partyEsp;


    public static final class PartyHighlightSettings {
        /** Master toggle for the Party highlight module. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** highlight box + nametag colour (cycled preset). */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor color = sbs.modid.client.core.render.OverlayColor.GREEN;

        /** Draw the member's name above the box. */
        @Shareable(Kind.BOOL)
        public boolean showNametag = true;

        /** Draw the box (off = nametag only). */
        @Shareable(Kind.BOOL)
        public boolean showBox = true;

        /** Tracer line from the crosshair to each member - finds them through walls and terrain. */
        @Shareable(Kind.BOOL)
        public boolean showTracer = false;

        /**
         * Dungeon boss rooms in which the party highlight draws nothing at all, by floor number
         * (1 = Bonzo, 2 = Scarf, 3 = The Professor, 4 = Thorn, 5 = Livid, 6 = Sadan, 7 = Necron;
         * F and M share a number because they share a boss).
         *
         * <p>A boss fight is the one place the member boxes stop helping and start hiding things:
         * five players stand on top of the boss, and their through-wall boxes sit exactly where the
         * boss's own hitbox has to be read. Which fights that ruins is personal (Livid's clones vs.
         * Necron's terminals are nothing alike), so it is one toggle per boss rather than one for
         * "boss rooms".
         */
        public java.util.Set<Integer> hiddenBossFloors = new java.util.LinkedHashSet<>();

        /** Tracked Hypixel party member IGNs (persisted; cleared when stale, see {@code updatedAt}). */
        public java.util.List<String> members = new java.util.ArrayList<>();

        /** Last time the party list was confirmed active (ms). Older than 5min on load = stale. */
        public long updatedAt = 0;
    }

    /** Slayer module: boss/profit tracker, Voidgloom beacon/nukekubi/phase helpers, miniboss alerts. */
    public SlayerSettings slayer = new SlayerSettings();

    public static final class SlayerSettings {
        /** Master toggle for the Slayer module. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Session tracker overlay (bosses, kill times, profit, drop list). */
        @Shareable(Kind.BOOL)
        public boolean showOverlay = true;

        /**
         * Track each slayer's RNG meter: read from the Slayer menu, moved by the chat line after
         * every boss, shown on the Slayer tracker card.
         */
        @Shareable(Kind.BOOL)
        public boolean rngMeter = true;

        /** Add the selected drop's current price to the RNG meter lines on the card. */
        @Shareable(Kind.BOOL)
        public boolean rngMeterDropValue = true;

        /** Play a sound with every slayer alert. */
        @Shareable(Kind.BOOL)
        public boolean alertSound = true;

        // --- Ender (Voidgloom) ---
        /** Highlight the thrown beacon that must be broken. */
        @Shareable(Kind.BOOL)
        public boolean beaconHighlight = true;
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor beaconColor = sbs.modid.client.core.render.OverlayColor.CYAN;
        /** Big alert when a beacon is placed. */
        @Shareable(Kind.BOOL)
        public boolean beaconAlert = true;
        /**
         * Tracer line to the thrown beacon, independent of {@link #showTracers}. The beacon lands
         * behind you as often as in front of you and has to be reached in five seconds, so it is
         * worth a line of its own without turning lines on for everything else.
         */
        @Shareable(Kind.BOOL)
        public boolean beaconTracer = false;

        /** Highlight the nukekubi fixations – the skulls that double the boss's damage per head. */
        @Shareable(Kind.BOOL)
        public boolean nukekubiHighlight = true;
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor nukekubiColor = sbs.modid.client.core.render.OverlayColor.RED;
        @Shareable(Kind.BOOL)
        public boolean nukekubiAlert = true;
        /** Tracer line to every nukekubi head, independent of {@link #showTracers}. */
        @Shareable(Kind.BOOL)
        public boolean nukekubiTracer = false;

        /** Colour the boss box by its current phase. */
        @Shareable(Kind.BOOL)
        public boolean phaseHighlight = true;
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor phaseNormalColor = sbs.modid.client.core.render.OverlayColor.PURPLE;
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor phaseBeaconColor = sbs.modid.client.core.render.OverlayColor.YELLOW;
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor phaseHitsColor = sbs.modid.client.core.render.OverlayColor.GREEN;

        // --- Zombie (Revenant) ---
        /** Alert when the boss enrages (T3/T4 Mad/Enraged) or the Atoned Horror charges its blast. */
        @Shareable(Kind.BOOL)
        public boolean revEnrageAlert = true;
        /** Highlight the TNT the Atoned Horror (T5) throws at your feet. */
        @Shareable(Kind.BOOL)
        public boolean revTntHighlight = true;
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor revTntColor = sbs.modid.client.core.render.OverlayColor.ORANGE;
        /** Big alert when that TNT is in the air. */
        @Shareable(Kind.BOOL)
        public boolean revTntAlert = true;

        // --- Spider (Tarantula) ---
        /** Highlight the egg sacs the Broodfather lays at 66%/33% (they hatch if not broken). */
        @Shareable(Kind.BOOL)
        public boolean taraEggHighlight = true;
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor taraEggColor = sbs.modid.client.core.render.OverlayColor.YELLOW;
        /** Big alert when the egg phase starts. */
        @Shareable(Kind.BOOL)
        public boolean taraEggAlert = true;

        // --- Wolf (Sven) ---
        /** Highlight the Sven Pups the boss calls at half health (the boss is protected meanwhile). */
        @Shareable(Kind.BOOL)
        public boolean svenPupHighlight = true;
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor svenPupColor = sbs.modid.client.core.render.OverlayColor.CYAN;
        /** Big alert when the pups are called. */
        @Shareable(Kind.BOOL)
        public boolean svenPupAlert = true;

        /**
         * Boss box colour while the boss is in a special state – Rev Mad/Enraged/charging, Sven
         * "Protected" behind its pups, Tara cocooned during the egg phase. The Voidgloom keeps its
         * own three phase colours above; this is the same idea for the other slayers.
         */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor bossStateColor = sbs.modid.client.core.render.OverlayColor.RED;

        // --- all slayers ---
        /** Alert + highlight when a miniboss spawns near you. */
        @Shareable(Kind.BOOL)
        public boolean minibossAlert = true;
        @Shareable(Kind.BOOL)
        public boolean minibossHighlight = true;
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor minibossColor = sbs.modid.client.core.render.OverlayColor.ORANGE;

        /**
         * Keep the miniboss alert + highlight quiet unless a slayer quest of your own is running.
         * Without one the named mobs in a slayer area belong to everybody else, and boxing them all
         * turns the area into noise. Overridden while the Carry Counter is on a carry, where the
         * quest belongs to the person being carried – see {@link CarryCounterSettings#minibossHighlight}.
         */
        @Shareable(Kind.BOOL)
        public boolean minibossOnlyWithQuest = true;

        /**
         * Keep highlighting that slayer's minibosses once its boss is up, per slayer.
         *
         * <p><b>Off by default, and that is the point of the setting.</b> Before the boss spawns a
         * miniboss is the best thing on screen; once you are in the fight it is usually something to
         * ignore, and a box on it competes with the boss box for the same glance. Which of those is
         * true depends entirely on the slayer - a Voidgloom fight has no attention to spare, a
         * Revenant one often does - so it is one answer per slayer rather than one for all six.
         *
         * <p>Only the box is affected. The Miniboss Alert stays as it is: it fires once per
         * miniboss, and a thing that happens once is not the clutter this is about.
         *
         * <p>There is no field for the Riftstalker: the vampire slayer has no minibosses, so
         * {@link sbs.modid.client.combat.slayer.model.SlayerData#MINIBOSSES} has no entry for it and
         * nothing could ever read one - see {@link #minibossInFight}.
         */
        @Shareable(Kind.BOOL)
        public boolean minibossInFightRevenant = false;

        /** @see #minibossInFightRevenant */
        @Shareable(Kind.BOOL)
        public boolean minibossInFightTarantula = false;

        /** @see #minibossInFightRevenant */
        @Shareable(Kind.BOOL)
        public boolean minibossInFightSven = false;

        /** @see #minibossInFightRevenant */
        @Shareable(Kind.BOOL)
        public boolean minibossInFightVoidgloom = false;

        /** @see #minibossInFightRevenant */
        @Shareable(Kind.BOOL)
        public boolean minibossInFightInferno = false;

        /**
         * Whether that slayer's minibosses stay boxed during its boss fight.
         *
         * <p>A slayer with no minibosses answers {@code false} and can never be asked anything else:
         * nothing produces one, so nothing reads this for it.
         */
        public boolean minibossInFight(sbs.modid.client.combat.carry.model.SlayerBoss boss) {
            if (boss == null) {
                return false;
            }
            return switch (boss) {
                case REVENANT -> minibossInFightRevenant;
                case TARANTULA -> minibossInFightTarantula;
                case SVEN -> minibossInFightSven;
                case VOIDGLOOM -> minibossInFightVoidgloom;
                case INFERNO -> minibossInFightInferno;
                case BLOODFIEND -> false;
            };
        }

        /** Flips {@link #minibossInFight}; a slayer with no minibosses has nothing to flip. */
        public void toggleMinibossInFight(sbs.modid.client.combat.carry.model.SlayerBoss boss) {
            switch (boss) {
                case REVENANT -> minibossInFightRevenant = !minibossInFightRevenant;
                case TARANTULA -> minibossInFightTarantula = !minibossInFightTarantula;
                case SVEN -> minibossInFightSven = !minibossInFightSven;
                case VOIDGLOOM -> minibossInFightVoidgloom = !minibossInFightVoidgloom;
                case INFERNO -> minibossInFightInferno = !minibossInFightInferno;
                case BLOODFIEND -> { }
            }
        }

        /** Tracer line from the crosshair to each highlighted miniboss. */
        @Shareable(Kind.BOOL)
        public boolean minibossTracer = false;

        /**
         * Show only the minibosses that belong to your own fight, rather than every one in the lobby.
         *
         * <p>Minibosses carry no owner line the way a slayer boss does, so ownership is positional:
         * yours spawn at your own boss, so a miniboss near your boss entity is yours. Until a boss is
         * known the test falls back to distance from you, which is the same thing at the moment a
         * fight starts.
         *
         * <p>Turning it off is for seeing the whole lobby's minibosses – useful when hunting them for
         * drops rather than fighting your own quest, and the reason it is a setting rather than a
         * fixed rule.
         */
        @Shareable(Kind.BOOL)
        public boolean minibossOwnOnly = true;

        // --- Blaze (Inferno) ---
        /** Alert for the fire pillar countdown during the Inferno fight. */
        @Shareable(Kind.BOOL)
        public boolean pillarAlert = true;
        /** Readout of the attunement your dagger has to match (Ashen / Spirit / Auric / Crystal). */
        @Shareable(Kind.BOOL)
        public boolean attunementDisplay = true;
        /** Flash + ping whenever that attunement changes, i.e. whenever you have to swap. */
        @Shareable(Kind.BOOL)
        public boolean attunementAlert = true;
        /** Yellow box around the hotbar slot holding the dagger that can reach the needed mode. */
        @Shareable(Kind.BOOL)
        public boolean daggerHighlight = true;

        // --- Vampire (Riftstalker) ---
        /** Big alert on the boss's Twinclaws attack. */
        @Shareable(Kind.BOOL)
        public boolean twinclawsAlert = true;
        /** Highlight Blood Ichor / Killer Spring during the vampire fight. */
        @Shareable(Kind.BOOL)
        public boolean ichorHighlight = true;

        /** Tracer line from the crosshair to every slayer highlight (boss, beacon, ichor, miniboss). */
        @Shareable(Kind.BOOL)
        public boolean showTracers = false;
    }

    /** Better Chat: restyle / filter / compact / hide the vanilla chat. */
    public BetterChatSettings betterChat = new BetterChatSettings();

    public static final class BetterChatSettings {
        /** Master toggle. Default off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Collapse a repeated identical message into the previous line with a {@code (xN)} counter. */
        @Shareable(Kind.BOOL)
        public boolean compactor = true;

        /** Prefix every message with a {@code [HH:mm]} timestamp. */
        @Shareable(Kind.BOOL)
        public boolean timestamps = false;

        /** 24-hour timestamps ({@code false} = 12-hour with am/pm). */
        @Shareable(Kind.BOOL)
        public boolean timestamps24h = true;

        /** Highlight (bright prefix + optional sound) messages that mention you. */
        @Shareable(Kind.BOOL)
        public boolean highlightMentions = false;

        /** Extra words that also count as a mention (comma-separated); your name always counts. */
        public String mentionWords = "";

        /** Play a soft pling when a mention is highlighted. */
        @Shareable(Kind.BOOL)
        public boolean mentionSound = true;

        /** Hide the whole chat while it is not focused (press chat key to read/type as normal). */
        @Shareable(Kind.BOOL)
        public boolean hideWhenUnfocused = false;

        /** GLFW key that flips {@link #hideWhenUnfocused} in-game ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int hideChatKey = 0;

        /** Preset filter: hide the "You are sending commands too fast!" spam. */
        @Shareable(Kind.BOOL)
        public boolean hideCommandsTooFast = false;

        /** Preset filter: hide the "You do not have enough mana" ability spam. */
        @Shareable(Kind.BOOL)
        public boolean hideNotEnoughMana = false;

        /** Preset filter: hide the "There are blocks in the way!" teleport/ability spam. */
        @Shareable(Kind.BOOL)
        public boolean hideBlocksInTheWay = false;

        /** Custom hide list: messages containing any of these (comma-separated) are removed. */
        public String hideContaining = "";
    }

    /** Chat Tabs: sort the chat by channel and show one channel at a time. */
    public ChannelSendSettings channelSend = new ChannelSendSettings();

    /** Sending typed messages to the selected chat channel without typing its command each time. */
    public static final class ChannelSendSettings {
        /**
         * Master toggle. <b>Off by default and it must stay that way.</b> With it off nothing is
         * prefixed and chat behaves exactly as it always has — that is not merely the intended
         * default, it is what makes the off state provably identical to today rather than a second
         * code path that happens to look the same.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * Keep the selected channel across a restart.
         *
         * <p>Default off, and that is the safer half of the option rather than the lazier one: a
         * selection made an hour ago in a party that has since disbanded is the least predictable
         * state this feature can be in, and the player has no reason to check the indicator on the
         * first message after a launch.
         */
        @Shareable(Kind.BOOL)
        public boolean persist = false;

        /**
         * The persisted selection, read at startup only when {@link #persist} is on. Written by name,
         * so renaming a {@code SendChannel} constant silently resets whatever the player chose.
         */
        @Shareable(value = Kind.ENUM,
                enumType = sbs.modid.client.social.chat.model.SendChannel.class)
        public sbs.modid.client.social.chat.model.SendChannel active =
                sbs.modid.client.social.chat.model.SendChannel.PUBLIC;

        /**
         * Tint the chat input's border to the channel's colour.
         *
         * <p>Separable from the label because colour alone must never be the only signal — a
         * meaningful share of players cannot separate these hues, and the label is what carries the
         * meaning. Switching this off leaves the label; there is no setting that leaves neither.
         */
        @Shareable(Kind.BOOL)
        public boolean colorInput = true;

        /** Let a click on a chat tab also set the send channel, per {@code SendChannel.from}. */
        @Shareable(Kind.BOOL)
        public boolean followTabs = true;
    }

    /** {@code /sendcoords}: the command that puts your block position into a chat channel. */
    public SendCoordsSettings sendCoords = new SendCoordsSettings();

    /**
     * {@code /sendcoords} - the coordinates command.
     *
     * <p>One field, and it decides what leaves this client, which is why it is not free-form: the
     * format is checked for its three placeholders before it is ever used. See
     * {@link sbs.modid.client.social.sendcoords.SendCoords}.
     *
     * <p><b>There is deliberately no default channel here.</b> The command sends to whichever chat
     * the player is already in ({@code ActiveChannel}), so a stored destination would be a second
     * answer to that question - one that is not on screen at the moment of sending, and whose being
     * wrong shows a coordinate to people it was not meant for. A field of that name existed before
     * and was removed; a config carrying the old value simply loses it.
     */
    public static final class SendCoordsSettings {

        /**
         * The line that is sent, with {@code %x%}, {@code %y%} and {@code %z%} standing for the block
         * position. All three are required: a format missing one cannot carry the coordinates, so it
         * is refused and the default used instead rather than sending a line that says nothing.
         *
         * <p><b>Not {@code @Shareable}, on purpose.</b> It decides the text this client says in a
         * channel other players read, and there is deliberately no {@link Kind} that can express such
         * a thing - see that enum's own first paragraph. An imported config choosing your words is
         * the failure the allowlist exists to prevent.
         */
        public String format = sbs.modid.client.social.sendcoords.SendCoords.DEFAULT_FORMAT;
    }

    public ChatTabsSettings chatTabs = new ChatTabsSettings();

    public static final class ChatTabsSettings {
        /** Master toggle. Default off — with it off the chat is never filtered at all. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Draw the tab strip at all. Its place and size come from the HUD editor, like every card. */
        @Shareable(Kind.BOOL)
        public boolean showBar = true;

        /**
         * Keep the strip on screen while you are not typing. On, because the unread counts are the
         * half of it that matters when the chat is closed — off makes it appear only with the chat.
         */
        @Shareable(Kind.BOOL)
        public boolean barWhileClosed = true;

        /**
         * The tab selected when the chat history is cleared — a rejoin, a server hop, F3+D. Nobody
         * should ever log in to a chat that is quietly filtered by a choice they made an hour ago.
         */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.social.chat.model.ChatTab.class)
        public sbs.modid.client.social.chat.model.ChatTab defaultTab =
                sbs.modid.client.social.chat.model.ChatTab.ALL;

        /** Which tabs are offered. A tab that is off is not selectable; its messages stay in All. */
        @Shareable(Kind.BOOL)
        public boolean tabPublic = true;
        @Shareable(Kind.BOOL)
        public boolean tabParty = true;
        @Shareable(Kind.BOOL)
        public boolean tabGuild = true;
        @Shareable(Kind.BOOL)
        public boolean tabCoop = false;
        @Shareable(Kind.BOOL)
        public boolean tabPrivate = true;
        /** Only ever offered while IRC Chat itself is on, over in Chat Options. */
        @Shareable(Kind.BOOL)
        public boolean tabIrc = true;

        /**
         * Channels kept out of the All tab — "I do not want to read this, but I do not want it
         * gone". A muted channel still shows on its own tab, and muting is ignored while that tab
         * is switched off, so muting can never make a message unreadable everywhere at once.
         */
        @Shareable(Kind.BOOL)
        public boolean mutePublic = false;
        @Shareable(Kind.BOOL)
        public boolean muteParty = false;
        @Shareable(Kind.BOOL)
        public boolean muteGuild = false;
        @Shareable(Kind.BOOL)
        public boolean muteCoop = false;
        @Shareable(Kind.BOOL)
        public boolean mutePrivate = false;
        @Shareable(Kind.BOOL)
        public boolean muteIrc = false;

        /** Keep the server's own messages (rewards, warnings, level-ups) visible on every tab. */
        @Shareable(Kind.BOOL)
        public boolean keepSystem = false;

        /** Keep this mod's own {@code [SBS]} lines visible on every tab. */
        @Shareable(Kind.BOOL)
        public boolean keepModMessages = true;

        /** GLFW key that steps to the next tab while playing ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int cycleKey = 0;
    }

    /** Ability Damage: where Hypixel's "Your X hit N enemies for Y damage." lines end up. */
    public AbilityDamageSettings abilityDamage = new AbilityDamageSettings();

    public static final class AbilityDamageSettings {
        /**
         * The whole module in one setting: leave the lines in chat (default, nothing is touched),
         * remove them, or move them out of chat into the movable HUD card.
         */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.combat.damage.model.AbilityDamageMode.class)
        public sbs.modid.client.combat.damage.model.AbilityDamageMode mode =
                sbs.modid.client.combat.damage.model.AbilityDamageMode.CHAT;

        /** How many of the most recent hits the HUD card lists. */
        public int hudLines = 5;

        /** How long a hit stays on the HUD card after it landed, in seconds. */
        public int hudHoldSeconds = 8;

        /** Add a total line summing the hits currently on the card. */
        @Shareable(Kind.BOOL)
        public boolean hudTotal = true;
    }

    /** Damage Attribution: hide foreign damage splashes, attribute your own via click+timer+value. */
    public DamageAttributionSettings damageAttribution = new DamageAttributionSettings();

    public static final class DamageAttributionSettings {
        /** Master toggle. Default off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** GLFW key that flips the master toggle in-game ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int toggleKey = 0;

        /** Hide splashes that were NOT attributed to you (on whitelisted mobs only). */
        @Shareable(Kind.BOOL)
        public boolean hideForeign = true;

        /**
         * When the attribution is uncertain (no learned damage profile yet / identical values),
         * still hide foreign splashes. Off = keep everything visible in uncertain moments, so an
         * own hit can never vanish by mistake.
         */
        @Shareable(Kind.BOOL)
        public boolean hideOnLowConfidence = false;

        /** Append a small ✔ to splashes attributed to you (client-side rename). */
        @Shareable(Kind.BOOL)
        public boolean markOwn = false;

        /** Matching window after a click, in ms (150..600). */
        public int windowMs = 350;

        /** Value tolerance band around the expected damage, in percent. */
        public int tolerancePct = 12;

        /** Extra same-click hits accepted (Ferocity procs): cluster = 1 + this. */
        public int maxFerocityProcs = 2;

        /** Weight of the time score vs the value score, in percent (50 = equal). */
        public int timeWeightPct = 50;

        /** Whitelist: all Diana / Mythological mobs. */
        @Shareable(Kind.BOOL)
        public boolean dianaMobs = true;

        /** Whitelist: all Slayer bosses. */
        @Shareable(Kind.BOOL)
        public boolean slayerBosses = true;

        /** Additional mob names (comma-separated, matched against the floating nametag). */
        public String extraMobs = "";

        /** LootShare tracker: chat alert once your damage passes the eligibility share. */
        @Shareable(Kind.BOOL)
        public boolean lootshareEnabled = true;

        /** Eligibility share for regular mobs (wiki: 1% of max HP). */
        public int lootsharePctMobs = 1;

        /** Eligibility share for Slayer bosses (wiki: 10% of max HP). */
        public int lootsharePctSlayer = 10;

        /** Play a pling with the LootShare chat alert. */
        @Shareable(Kind.BOOL)
        public boolean lootshareSound = true;

        /**
         * Melee Damage HUD: a movable card with your last melee hit, the best hit and the DPS.
         * It is fed by this module's own-splash attribution, so it needs {@link #enabled} – melee
         * damage exists only as world splashes, never as a chat line.
         */
        @Shareable(Kind.BOOL)
        public boolean meleeHud = false;

        /** Rolling window the melee card measures over (and how long it stays up), in seconds. */
        public int meleeHoldSeconds = 8;

        /** Show the best hit of the current window on the melee card. */
        @Shareable(Kind.BOOL)
        public boolean meleeShowMax = true;

        /** Show DPS and the hit count of the current window on the melee card. */
        @Shareable(Kind.BOOL)
        public boolean meleeShowDps = true;

        /** Chat a line per attribution decision (tuning aid). */
        @Shareable(Kind.BOOL)
        public boolean debugLog = false;
    }

    /** Damage Overlay module: predicted hit / crit / DPS against the mob under the crosshair. */
    public DamageOverlaySettings damageOverlay = new DamageOverlaySettings();

    public static final class DamageOverlaySettings {
        /** Master toggle. Default off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** How far ahead (blocks) the crosshair looks for a mob. */
        public int range = 30;

        /** Show the damage-per-second line. */
        @Shareable(Kind.BOOL)
        public boolean showDps = true;

        /** Show the "kill in ~N hits" line (simulated swing by swing, so Execute is honoured). */
        @Shareable(Kind.BOOL)
        public boolean showHitsToKill = true;

        /** Show the muted footer with the assumed defense and the learned calibration factor. */
        @Shareable(Kind.BOOL)
        public boolean showDetails = true;

        /**
         * Learn a per-mob correction factor from your own attributed splashes (real / predicted).
         * This absorbs the mob's unknown Defense and every multiplier the client cannot see.
         */
        @Shareable(Kind.BOOL)
        public boolean autoCalibrate = true;

        /** Combat skill level (additive +4%/level to 50, +1% for 51-60). Auto-captured from menus. */
        public int combatLevel = 0;

        /** Manual defense values: "mob name:defense" pairs, comma-separated. Beats the built-in table. */
        public String mobDefenseOverrides = "";

        /** Chat a line per estimate/calibration decision (tuning aid). */
        @Shareable(Kind.BOOL)
        public boolean debugLog = false;

        // ---- Last player-stats snapshot (captured from a stats menu, survives relogs) ----

        /**
         * Layout version of the fields below. Bumped whenever the capture changes shape, so an
         * older snapshot is treated as absent (asking for one menu open) instead of being read
         * with the wrong meaning.
         */
        public int statsVersion = 0;

        /** When the snapshot was taken (0 = never; the overlay asks for a menu open until then). */
        public long statsCapturedAt = 0;

        /** The menu TOTALS at capture time – gear of that moment included. */
        public double statHealth = 0;
        public double statStrength = 0;
        public double statCritChance = 0;
        public double statCritDamage = 0;
        public double statAttackSpeed = 0;
        public double statFerocity = 0;

        /**
         * The share of those totals that came from live-readable gear at capture time (the four
         * worn armor pieces plus the held weapon). Subtracting it leaves the base – skills,
         * accessories / magical power, pets, potions – so the current setup's gear can be added
         * back on top and armor / weapon / loadout swaps need no new menu visit.
         */
        public double gearStrength = 0;
        public double gearCritChance = 0;
        public double gearCritDamage = 0;
        public double gearAttackSpeed = 0;
        public double gearFerocity = 0;
        public double gearHealth = 0;
    }

    /** Bestiary Tracker module: kills remaining to max level for a chosen mob (read from the menu). */
    public BestiarySettings bestiary = new BestiarySettings();

    public static final class BestiarySettings {
        /** Master toggle for the Bestiary Tracker module. */
        public boolean enabled = false;

        /** The mob to keep pinned on the HUD (name, matched loosely). Empty = nothing pinned. */
        public String pinnedMob = "";

        /**
         * Baseline per mob, saved from the last Bestiary menu read (normalized name -> entry). This
         * is what lets the tracker keep counting after the menu is closed and survive a relog: the
         * menu total is the anchor, and kills seen since are added on top of it.
         */
        public Map<String, BestiaryEntry> saved = new LinkedHashMap<>();
    }

    /** One saved Bestiary baseline: the menu's total kills, the kills needed to max, and the maxed flag. */
    public static final class BestiaryEntry {
        public String name = "";
        public long kills;
        public long maxKills = -1;
        public boolean maxed;
    }

    /** Party Overlay module: a keybindable screen of party-command buttons + a favourites invite list. */
    public PartyOverlaySettings partyOverlay = new PartyOverlaySettings();

    public static final class PartyOverlaySettings {
        /** Master toggle for the Party Overlay module. */
        public boolean enabled = true;

        /** Raw key code that opens the overlay (0 = unbound). */
        public int openKey = 0;

        /** Saved player names for one-click invites (the favourites list). */
        public List<String> favorites = new java.util.ArrayList<>();
    }

    /** Bits Shop Helper: coins per bit on every offer, and the best deals boxed. */
    public BitsShopSettings bitsShop = new BitsShopSettings();

    /** Essence Shop Overview: what maxing every perk of the open shop still costs. */
    public EssenceShopSettings essenceShop = new EssenceShopSettings();

    public static final class EssenceShopSettings {
        /** Master toggle. Nothing is read or drawn outside an essence shop anyway. */
        public boolean enabled = true;

        /** Add what the missing essence would cost on the Bazaar, at the instant-buy price. */
        public boolean coinEstimate = true;

        /**
         * Show the button that opens the Bazaar for this essence. One click sends {@code /bz} once;
         * off, the panel is display-only and nothing is ever sent.
         */
        public boolean bazaarShortcut = true;
    }

    public static final class BitsShopSettings {
        /** Master toggle. Nothing is scanned, learned or drawn outside a bits shop page anyway. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Box the best (green) and second-best (yellow) coins-per-bit offers in the whole shop. */
        @Shareable(Kind.BOOL)
        public boolean highlightBest = true;

        /** Add the coins-per-bit line, the rank and the sell value to each offer's tooltip. */
        @Shareable(Kind.BOOL)
        public boolean showTooltipLine = true;

        /**
         * Keep recording offers as pages are opened. Off means the comparison is frozen to what is
         * already known - the ranking still works, it just stops learning new pages.
         */
        @Shareable(Kind.BOOL)
        public boolean learnOffers = true;
    }

    /** Looking At module: a crosshair chip naming the block / mob under the crosshair at range. */
    public LookingAtSettings lookingAt = new LookingAtSettings();

    public static final class LookingAtSettings {
        /**
         * Master toggle, off by default: the chip sits right under the crosshair and is drawn
         * almost continuously in normal play, so it is not something to put in front of someone
         * who never asked for it.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Name the block the crosshair rests on. */
        @Shareable(Kind.BOOL)
        public boolean showBlocks = true;

        /** Name mobs (their SkyBlock nametag line) and players. */
        @Shareable(Kind.BOOL)
        public boolean showEntities = true;

        /** Reach of the crosshair ray in blocks. */
        public int range = 60;

        /** Second line: live distance to the target. */
        @Shareable(Kind.BOOL)
        public boolean showDistance = true;

        /** Second line: the aimed-at block's coordinates. */
        @Shareable(Kind.BOOL)
        public boolean showCoords = false;

        /**
         * Name blocks the way SkyBlock does ("Mithril", not "Gray Wool") where the island makes it
         * unambiguous. The vanilla block name moves to the second line rather than being lost.
         */
        @Shareable(Kind.BOOL)
        public boolean skyblockBlockNames = true;
    }

    /** Pest Status module: the Pests widget's spray / repellent / bonus rows as a Garden-only card. */
    public PestStatusSettings pestStatus = new PestStatusSettings();

    /** Beacon Tuner (Skills): the Moonglade beacon's beat, read rather than matched by ear. */
    public BeaconTunerSettings beaconTuner = new BeaconTunerSettings();

    public static final class BeaconTunerSettings {
        /**
         * Off by default: the feature reads the beat but not the control settings, so it is
         * knowingly incomplete and ships disabled rather than surprising anyone with half a tuner.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;
    }

    public static final class PestStatusSettings {
        /** Master toggle. The card only ever draws while the Pests widget is on the tab list. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** "Spray: None" - which plot is sprayed and for how long. */
        @Shareable(Kind.BOOL)
        public boolean showSpray = true;

        /** "Repellent: None" - whether the pest repellent is running. */
        @Shareable(Kind.BOOL)
        public boolean showRepellent = true;

        /** "Bonus: INACTIVE" - the pest-drop bonus state. */
        @Shareable(Kind.BOOL)
        public boolean showBonus = true;

        /** "Cooldown: MAX PESTS" - already a row on the pest card, so off here by default. */
        @Shareable(Kind.BOOL)
        public boolean showCooldown = false;

        /**
         * "Phillip: ☘200 24m 12s" - how much longer the Farming Fortune buff from handing pests to
         * Pesthunter Phillip lasts. Read from his chat line, not from the tab widget.
         */
        @Shareable(Kind.BOOL)
        public boolean showPhillip = true;
    }

    /** Sprayonator (Skills): what is sprayed, for how much longer, and what was sprayed before. */
    public SprayonatorSettings sprayonator = new SprayonatorSettings();

    public static final class SprayonatorSettings {
        /** Master toggle for the Sprayonator card. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Show the material the Sprayonator is loaded with while nothing is sprayed. */
        @Shareable(Kind.BOOL)
        public boolean showSelected = true;

        /** Show which material was sprayed before the current one. */
        @Shareable(Kind.BOOL)
        public boolean showLast = true;

        /** Also name the sprayed plot, when the Pests widget states it. */
        @Shareable(Kind.BOOL)
        public boolean showPlot = true;

        /**
         * SkyBlock item id of the last spray that ran out, and when it did (epoch millis).
         * Persisted rather than kept in memory: within a session you still remember what you
         * sprayed, so the only version of this worth having is the one that survives a restart.
         */
        public String lastMaterial = "";
        public long lastSprayedAt = 0;
    }

    /** Event Timers module: Dark Auction, Jacob's Contest, volcano, lobby age as one HUD card. */
    public TimersSettings timers = new TimersSettings();

    public static final class TimersSettings {
        /** Master toggle for the Event Timers module. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Minecraft-day counter of the current lobby, from its world time - how fresh a lobby is. */
        @Shareable(Kind.BOOL)
        public boolean showLobbyAge = true;

        /** Countdown to the next Dark Auction. */
        @Shareable(Kind.BOOL)
        public boolean showDarkAuction = true;

        /** Countdown to the next Jacob's Farming Contest (calibrates off a contest or the calendar). */
        @Shareable(Kind.BOOL)
        public boolean showJacobContest = true;

        /** The Crimson Isle volcano's current eruption, as the tab widget words it. */
        @Shareable(Kind.BOOL)
        public boolean showVolcano = true;

        /** The current SkyBlock date. */
        @Shareable(Kind.BOOL)
        public boolean showSkyblockDate = false;

        /** The event calendar rows under the timers (Hoppity's Hunt, Season of Jerry, ...). */
        @Shareable(Kind.BOOL)
        public boolean showCalendar = true;

        /** How many calendar rows the card shows, soonest first. */
        @Shareable(value = Kind.INT, min = 1, max = 20)
        public int calendarRows = 5;

        /** List mayor-only events as "if <mayor> is elected" while that mayor is not in office. */
        @Shareable(Kind.BOOL)
        public boolean calendarMayorEvents = false;

        /** Calendar event ids the player switched off (ids from timers/event_calendar.json). */
        public java.util.Set<String> calendarHidden = new java.util.LinkedHashSet<>();

        /** Minutes before an enabled event starts to alert (0 = no alert). */
        @Shareable(value = Kind.INT, min = 0, max = 120)
        public int calendarAlertMinutes = 0;

        /** How the start alert reaches you (an AlertChannels mask). */
        public int calendarAlertChannels = 1 | (1 << 3);

        /**
         * Minute past every real hour the Dark Auction starts. Three SkyBlock days are exactly one
         * real hour, so the event keeps a fixed slot; this is a setting rather than a constant
         * because that slot is the one part of the maths that could ever drift.
         */
        public int darkAuctionMinute = 55;

        /**
         * The day-of-year remainder Jacob's contests fall on ({@code -1} = not known yet). The 3-day
         * cadence is documented, its phase is not derivable from anything on screen, so it is
         * observed once and then remembered — either by watching a contest run, or by reading the
         * SkyBlock calendar the next time the player opens it, whichever happens first.
         */
        public int jacobPhase = -1;
    }

    /** Milestone module: the tab widget's "Your Milestone" line as a HUD card. */
    public MilestoneSettings milestone = new MilestoneSettings();

    public static final class MilestoneSettings {
        /** Master toggle. On by default - it costs nothing outside a run, where it draws nothing. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Also show the "(7.1% to ...)" progress line under the value. */
        @Shareable(Kind.BOOL)
        public boolean showProgress = true;
    }

    /** Active Buffs module: God Potion / Booster Cookie remaining time as HUD cards. */
    public BuffsSettings buffs = new BuffsSettings();

    public static final class BuffsSettings {
        /** Master toggle for the Active Buffs module. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /**
         * Show the God Potion HUD card while one is active. Off by default: the timer is a Custom
         * Scoreboard row now, and two copies of the same number is one too many.
         */
        @Shareable(Kind.BOOL)
        public boolean godPotionCard = false;

        /** Show the Booster Cookie HUD card while the buff is active – off by default, see above. */
        @Shareable(Kind.BOOL)
        public boolean cookieCard = false;

        /** Show the Century Cake buffs card while any cake buff is active. */
        @Shareable(Kind.BOOL)
        public boolean cakeCard = true;

        /** Cake card as one line ("19 cakes - next ends in 47h 12m") instead of one row per stat. */
        @Shareable(Kind.BOOL)
        public boolean cakeCollapsed = true;

        /** Alert this many hours before the first cake buff runs out; 0 = off. */
        public int cakeWarnHours = 0;

        /** Where the cake expiry alert goes. */
        public int cakeWarnChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;

        /**
         * Consumable Timers: track God Potion, cookie, mixins, potions and other timed consumables.
         * Off by default until it has been run in game - docs/features/consumable-timers.md.
         */
        @Shareable(Kind.BOOL)
        public boolean consumablesEnabled = false;

        /** The Consumable Timers HUD card. */
        @Shareable(Kind.BOOL)
        public boolean consumablesCard = true;

        /** Leave timers with more than this many hours left off the card; 0 = show all. */
        public int consumablesHideAboveHours = 0;

        /** Read the Active Effects menu when the player opens it. Off: its format is unverified. */
        @Shareable(Kind.BOOL)
        public boolean consumablesReadMenu = false;

        /** Per kind: alerts on/off, and the warning lead time (0 = only the "ran out" alert). */
        @Shareable(Kind.BOOL)
        public boolean consumableAlertGodPotion = true;
        public int consumableWarnGodPotionMinutes = 30;
        @Shareable(Kind.BOOL)
        public boolean consumableAlertCookie = true;
        public int consumableWarnCookieHours = 12;
        @Shareable(Kind.BOOL)
        public boolean consumableAlertPotion = true;
        public int consumableWarnPotionMinutes = 5;
        @Shareable(Kind.BOOL)
        public boolean consumableAlertMixin = true;
        public int consumableWarnMixinMinutes = 0;
        @Shareable(Kind.BOOL)
        public boolean consumableAlertOther = true;
        public int consumableWarnOtherMinutes = 5;

        /** Also alert when a buff has run out, not only before. */
        @Shareable(Kind.BOOL)
        public boolean consumableEndAlert = true;

        /** Where the consumable alerts go. */
        public int consumableAlertChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;

        /**
         * The four colours the buff readouts are written in, {@code RRGGBB}, empty for the default.
         *
         * <p>Used by the Custom Scoreboard rows and the HUD cards alike - see
         * {@link sbs.modid.client.helper.buffs.BuffColors}, which owns the defaults and the reason
         * for each of them. They live under Active Buffs rather than under the scoreboard because
         * the buff is what they belong to; the scoreboard is one of the things that draws it.
         */
        @Shareable(Kind.HEX_COLOR)
        public String godPotionNameHex = "FF5555";

        /** @see #godPotionNameHex */
        @Shareable(Kind.HEX_COLOR)
        public String godPotionTimeHex = "FF55FF";

        /** @see #godPotionNameHex */
        @Shareable(Kind.HEX_COLOR)
        public String cookieBuffNameHex = "FFAA00";

        /** @see #godPotionNameHex */
        @Shareable(Kind.HEX_COLOR)
        public String cookieBuffTimeHex = "55FF55";

        /**
         * The card flags from before the timers moved into the Custom Scoreboard. Boxed so an older
         * config is distinguishable from a fresh one; migrated once, then dropped.
         *
         * @deprecated use {@link #godPotionCard}
         */
        @Deprecated
        public Boolean showGodPotion;

        /** @deprecated use {@link #cookieCard} */
        @Deprecated
        public Boolean showCookieBuff;
    }

    /** Garden Plots module: the plot grid as an SBS screen, with pest flashing and one-click warp. */
    public GardenPlotsSettings gardenPlots = new GardenPlotsSettings();

    public static final class GardenPlotsSettings {
        /** Master toggle for the Garden Plots module. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Raw key code that opens the plot grid (0 = unbound). */
        @Shareable(Kind.KEYCODE)
        public int openKey = 0;

        /**
         * Raw key code that teleports to an infested plot (0 = unbound).
         *
         * <p>One press, one teleport. Repeated presses walk the infested plots in a stable order;
         * the mod never travels on its own.
         */
        @Shareable(Kind.KEYCODE)
        public int infestedKey = 0;

        /**
         * Order the infested-plot hotkey walks the plots in.
         *
         * <p>{@code false} = by plot number, {@code true} = nearest first. There is deliberately no
         * "most pests" option: the Pests widget lists each infested plot once no matter how many
         * pests are on it, so the client cannot know a per-plot count and an option promising one
         * would silently do nothing.
         */
        @Shareable(Kind.BOOL)
        public boolean infestedNearestFirst = false;

        /** Flash the plots the Pests widget lists as infested. */
        @Shareable(Kind.BOOL)
        public boolean flashInfested = true;

        /**
         * Show the grid as a floating window beside the inventory, like the Recipe Viewer. It only
         * appears on the Garden, starts minimized to its button next to the search bar, and after
         * that comes back wherever you last left it.
         */
        @Shareable(Kind.BOOL)
        public boolean showInInventory = true;

        /**
         * The commands the grid and its footer buttons run, without the leading slash.
         *
         * <p>Configurable rather than hardcoded: Hypixel has renamed Garden commands before, and a
         * wrong constant here would mean a rebuild to fix a one-word typo. Defaults are the current
         * names; correct them in the module settings if Hypixel moves them.
         */
        public String plotTeleportCommand = "plottp";
        public String deskCommand = "desk";
        public String setSpawnCommand = "setgardenspawn";
        public String gardenSpawnCommand = "gardenspawn";
    }

    /** Collection Tracker module: auto-detected collection, exact counter + tier progress HUD. */
    public CollectionTrackerSettings collectionTracker = new CollectionTrackerSettings();

    public static final class CollectionTrackerSettings {
        /** Master toggle for the Collection Tracker module. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Show the session rate ("/h") next to the gain. */
        @Shareable(Kind.BOOL)
        public boolean showRate = true;

        /**
         * The collection to keep pinned on the HUD (name or id, matched loosely). Empty = auto-detect
         * the collection currently being farmed, the original behaviour.
         */
        public String pinnedCollection = "";
    }

    /** Slot Hotkeys module: bind a key/mouse button to instantly click a saved menu slot. */
    public SlotHotkeysSettings slotHotkeys = new SlotHotkeysSettings();

    public static final class SlotHotkeysSettings {
        /** Master toggle for the Slot Hotkeys module. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Scan key (Keys-encoded key or mouse button; 0 = unbound): press in a menu, then click a
         *  slot to save it for the next hotkey. */
        @Shareable(Kind.KEYCODE)
        public int scanKey = 0;

        /** The saved hotkeys. */
        public java.util.List<sbs.modid.client.helper.slothotkey.logic.SlotHotkey> hotkeys = new java.util.ArrayList<>();
    }

    /** Slayer Carry Counter module: /sbs trackcarry, boss-kill counting + overlay. */
    public CarryCounterSettings carryCounter = new CarryCounterSettings();

    public static final class CarryCounterSettings {
        /** Master toggle for the Slayer Carry Counter module. */
        public boolean enabled = true;

        /** Draw the counter overlay on the HUD. */
        public boolean showOverlay = true;

        /** Auto-count a slayer boss the moment it dies next to you (an assist); off = manual only. */
        public boolean autoDetect = true;

        /** Print an [SBS] chat line each time a boss is counted. */
        public boolean announce = true;

        /** Send a party-chat progress line "/pc carry <type> <count>/<goal> <player>" on each kill. */
        public boolean partyAnnounce = false;

        /** A boss death within this many blocks of you counts as your assist (auto-detect). */
        public int detectRadius = 15;

        /**
         * How long a vanished boss is held before it counts as a kill. A boss whose body comes back
         * (same type and owner, same spot) inside this window was changing phase, not dying.
         */
        public int deathGraceMs = 3_000;

        // --- Boss highlight: highlight the slayer mini-boss you are carrying ---
        /** Draw a box (and label) on every slayer mini-boss the counter can see in the world. */
        public boolean highlightEnabled = false;
        /**
         * @deprecated Legacy key from when this was called "ESP". Boxed so that "absent"
         *     is distinguishable from "false" - a rename must not silently reset a setting
         *     the player switched on. Read once by {@code ConfigManager.migrateHighlightNames}
         *     and nulled; remove once no config in the wild still carries it.
         */
        @Deprecated
        public Boolean espEnabled;


        /** Colour of the carry boss-highlight box and label. */
        public sbs.modid.client.core.render.OverlayColor highlightColor = sbs.modid.client.core.render.OverlayColor.CYAN;
        /**
         * @deprecated Legacy key from when this was called "ESP". Boxed so that "absent"
         *     is distinguishable from "false" - a rename must not silently reset a setting
         *     the player switched on. Read once by {@code ConfigManager.migrateHighlightNames}
         *     and nulled; remove once no config in the wild still carries it.
         */
        @Deprecated
        public sbs.modid.client.core.render.OverlayColor espColor;


        /** Label each highlight box with the boss short name (Eman, Blaze, ...). */
        public boolean highlightLabel = true;
        /**
         * @deprecated Legacy key from when this was called "ESP". Boxed so that "absent"
         *     is distinguishable from "false" - a rename must not silently reset a setting
         *     the player switched on. Read once by {@code ConfigManager.migrateHighlightNames}
         *     and nulled; remove once no config in the wild still carries it.
         */
        @Deprecated
        public Boolean espLabel;


        /**
         * Only box bosses owned by a relevant player – a party member or a {@code /sbs trackcarry}
         * target – attributed by the boss's "Slayed by &lt;name&gt;" hologram or by that player standing
         * within ~8 blocks of it. Off = box every slayer boss in range.
         */
        public boolean highlightOwnedOnly = true;
        /**
         * @deprecated Legacy key from when this was called "ESP". Boxed so that "absent"
         *     is distinguishable from "false" - a rename must not silently reset a setting
         *     the player switched on. Read once by {@code ConfigManager.migrateHighlightNames}
         *     and nulled; remove once no config in the wild still carries it.
         */
        @Deprecated
        public Boolean espOwnedOnly;


        /** Only box the boss type of the active carry (hides other players' slayers in a public lobby). */
        public boolean highlightActiveBossOnly = false;
        /**
         * @deprecated Legacy key from when this was called "ESP". Boxed so that "absent"
         *     is distinguishable from "false" - a rename must not silently reset a setting
         *     the player switched on. Read once by {@code ConfigManager.migrateHighlightNames}
         *     and nulled; remove once no config in the wild still carries it.
         */
        @Deprecated
        public Boolean espActiveBossOnly;


        /**
         * Let the Slayer module's miniboss alert + highlight run while a carry is active, even though
         * the quest is the carried player's and not yours – the one case
         * {@link SlayerSettings#minibossOnlyWithQuest} would otherwise silence for good reason.
         */
        public boolean minibossHighlight = false;

        /** Tracer line from the crosshair to each boxed carry boss. */
        public boolean highlightTracer = false;
        /**
         * @deprecated Legacy key from when this was called "ESP". Boxed so that "absent"
         *     is distinguishable from "false" - a rename must not silently reset a setting
         *     the player switched on. Read once by {@code ConfigManager.migrateHighlightNames}
         *     and nulled; remove once no config in the wild still carries it.
         */
        @Deprecated
        public Boolean espTracer;


        /** IGN of the carry that currently receives auto-detected kills (empty = none active). */
        public String activePlayer = "";

        /** All carries being tracked (survive a relog mid-carry). */
        public java.util.List<Carry> carries = new java.util.ArrayList<>();

        /** One tracked carry: a player, the boss they are buying, the running count and an optional goal. */
        public static final class Carry {
            public String player = "";
            /** {@link SlayerBoss} enum name, or empty when the boss was not specified. */
            public String boss = "";
            /** Slayer tier (0 = unspecified). */
            public int tier = 0;
            public int count = 0;
            /** Target number of bosses (0 = open-ended). */
            public int goal = 0;
            public long startedAt = 0L;
        }
    }

    /** Rejoin Timer (Quality of Life): a countdown after a SkyBlock kick, then "try rejoining now". */
    public RejoinTimerSettings rejoinTimer = new RejoinTimerSettings();

    public static final class RejoinTimerSettings {
        /** Master toggle for the Rejoin Timer. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** How long to wait after a kick before it says you can rejoin (seconds). */
        public int seconds = 60;

        /** Play a ding when the countdown reaches zero. */
        @Shareable(Kind.BOOL)
        public boolean sound = true;

        /**
         * When the countdown ends, run the rejoin command ({@code /play SKYBLOCK}) automatically.
         *
         * <p>One command, once, never retried - it puts the player back on the server they were
         * thrown off and wins nothing against anybody. See the no-advantage exception in
         * {@code AGENTS.md}; off by default, as that exception requires.
         */
        @Shareable(Kind.BOOL)
        public boolean autoRejoin = false;

        /**
         * Prompt even when the client could not work out <i>why</i> SkyBlock was left.
         *
         * <p><b>Off by default, and that is the fix for the scheduled-restart bug.</b> A lobby with
         * no reason seen is usually a kick - but "usually" is what made the banner appear on every
         * scheduled reboot, and a prompt that is wrong that often is one players learn to ignore.
         * A missed prompt costs one command; the trained-to-ignore version costs the feature.
         *
         * <p>On, it restores the old always-prompt behaviour for anyone who would rather have the
         * false positives than miss a kick whose wording this build does not recognise.
         */
        @Shareable(Kind.BOOL)
        public boolean promptOnUnknownCause = false;

        /**
         * Also arm the timer when the sidebar stops saying SKYBLOCK, i.e. you ended up in a lobby.
         *
         * <p>Wording-independent, which is the point: Hypixel's restart and evacuation lines get
         * reworded, but landing in a lobby is the same observable event every time. Deliberate lobby
         * commands are ignored - see {@link RejoinTimerSettings#autoRejoin}, which would otherwise
         * drag you back out of a lobby you walked into on purpose.
         */
        @Shareable(Kind.BOOL)
        public boolean scoreboardFallback = true;
    }

    /** Reminders (Quality of Life): chat pings for recurring chores you keep forgetting. */
    public RemindersSettings reminders = new RemindersSettings();

    public static final class RemindersSettings {
        /** Master toggle for the Reminders module. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Play a ding with the reminder, so it lands even when chat is busy. */
        @Shareable(Kind.BOOL)
        public boolean sound = true;

        /**
         * Extra channels a due reminder uses on top of its chat line (an {@code AlertChannels}
         * mask). The chat line always goes out - it is the reminder.
         */
        public int extraChannels = 0;

        /**
         * @deprecated the old desktop-notification toggle. Only read by the migration into
         *     {@link #extraChannels}, then nulled so the next write drops it. Boxed so "absent" is
         *     distinguishable from "was switched off".
         */
        @Deprecated
        public Boolean systemNotification;

        /**
         * How often a <i>chore</i> reminder repeats while it is still not done (minutes,
         * {@code 0} = say it once). Does not apply to the custom reminders, which re-arm themselves
         * on their own interval instead of nagging.
         */
        public int repeatMinutes = 30;

        /** Hungry Hiker: ping when he can be fed again (12h after the last feed). */
        @Shareable(Kind.BOOL)
        public boolean hungryHiker = true;

        /** Crystal Hollows: ping before, and when, the lobby stops accepting warps. */
        @Shareable(Kind.BOOL)
        public boolean hollowsLobbyClosing = true;

        /**
         * The first lobby day on which nobody can warp into a Crystal Hollows lobby. Maintainer-
         * reported, not measured by SBS - a setting so a change on Hypixel's side is one edit.
         */
        @Shareable(value = Kind.INT, min = 1, max = 60)
        public int hollowsCloseDay = 19;

        /**
         * The lead window, in minutes of lobby clock (20 to a Minecraft day): the default 20 warns from
         * day 18.0. A clock position rather than real time, so lag does not move it; the ETA a notice
         * quotes is real time. {@code 0} = no window, only the "closing" line.
         */
        @Shareable(value = Kind.INT, min = 0, max = 60)
        public int hollowsWarnMinutes = 20;

        /** Ping on joining a Hollows lobby that is already inside the lead window. */
        @Shareable(Kind.BOOL)
        public boolean hollowsJoinNotice = true;

        /** Ping when the lead window starts while you are in the lobby. */
        @Shareable(Kind.BOOL)
        public boolean hollowsLeadNotice = true;

        /** Ping when the lobby reaches the cutoff day. */
        @Shareable(Kind.BOOL)
        public boolean hollowsClosedNotice = true;

        /** The "Lobby Day" card on the Crystal Hollows. */
        @Shareable(Kind.BOOL)
        public boolean hollowsHud = true;

        /**
         * Past the cutoff day, hold a typed leave command once until it is repeated. Off by default:
         * it adds a keystroke to something the player asked for, so it is theirs to switch on.
         */
        @Shareable(Kind.BOOL)
        public boolean hollowsLeaveGuard = false;

        /** Free reminder slot 1: what to say (empty = the slot is off). */
        public String custom1Label = "";
        /** How often slot 1 repeats, in minutes. */
        public int custom1Minutes = 60;

        public String custom2Label = "";
        public int custom2Minutes = 60;

        public String custom3Label = "";
        public int custom3Minutes = 60;

        /**
         * Reminder id -> when it was last done, as epoch millis. Persisted on purpose: a 12-hour
         * cooldown outlives the session it started in, and a timer that resets on every launch would
         * be exactly as useless as no timer at all.
         */
        public java.util.Map<String, Long> lastDone = new java.util.LinkedHashMap<>();
    }

    /** Web Browser module: a movable in-game browser you can watch while playing. */
    public BrowserSettings browser = new BrowserSettings();

    public static final class BrowserSettings {
        /** Master toggle for the Web Browser module. */
        public boolean enabled = true;

        /** Keep the browser drawn over the game world (so a video keeps playing while you play). */
        public boolean showInWorld = true;

        /** GLFW key that opens / closes the interactive browser screen (0 = unbound). */
        public int openKey = 0;

        /** Saved window rectangle (GUI pixels), draggable/resizable in the browser screen. */
        public int x = 20;
        public int y = 20;
        public int w = 360;
        public int h = 220;

        /** The last page, restored on open. */
        public String url = "https://www.google.com";
    }

    /** Texture Pack module preferences. */
    public TexturePackSettings texturePack = new TexturePackSettings();

    /** Recipe Viewer module preferences. */
    public RecipeViewerSettings recipeViewer = new RecipeViewerSettings();

    /** Calculator module preferences (the floating in-game calculator window). */
    public CalculatorSettings calculator = new CalculatorSettings();

    /** Player Viewer module preferences (/sbs skycrypt). */
    public PlayerViewerSettings playerViewer = new PlayerViewerSettings();

    /** SBS Party Finder module preferences. */
    public PartyFinderSettings partyFinder = new PartyFinderSettings();

    /** Party Commands module preferences (!warp, !allinv, ... in the Hypixel party chat). */
    public PartyCommandsSettings partyCommands = new PartyCommandsSettings();

    /** Skyblock Menu module preferences. */
    public SkyblockMenuSettings skyblockMenu = new SkyblockMenuSettings();

    /** Licence Token module preferences. */
    public LicenceSettings licence = new LicenceSettings();

    /** Item Price History module preferences. */
    public PriceHistorySettings priceHistory = new PriceHistorySettings();

    /** Dungeons module preferences. */
    public DungeonsSettings dungeons = new DungeonsSettings();

    /** Animation & Scaling module preferences. */
    public AnimationScalingSettings animationScaling = new AnimationScalingSettings();

    /** Hypixel API access settings. */
    public ApiSettings api = new ApiSettings();

    /** Hidden developer-tool keybinds (only ever act while dev mode is enabled). */
    public DevSettings dev = new DevSettings();

    /**
     * Cached Bazaar orders last read from the orders menu. Persisted so the background
     * API sync can keep tracking them across restarts until the menu is reopened.
     */
    public List<BazaarOrder> bazaarOrders = new ArrayList<>();

    public static final class GuiSettings {
        /** Remember the last search query between sessions. */
        @Shareable(Kind.BOOL)
        public boolean rememberSearch = false;

        /** Last search query (only used when {@link #rememberSearch} is true). */
        public String lastSearch = "";

        /**
         * HUD editor "Align": while dragging, an element snaps flush against its neighbours
         * (directly under / next to them) and onto their edge lines. Toggleable from the editor
         * itself, because pixel-precise free placement needs it OFF.
         */
        @Shareable(Kind.BOOL)
        public boolean editorSnap = true;

        /**
         * What the settings sidebar's category headers are folded to when the config is opened.
         *
         * <p>Default: folded once per game launch. Boxed by being an enum, so a config written
         * before this existed reads back as {@code null} and
         * {@link sbs.modid.client.ui.settings.CategoryFolding#mode()} answers with that default
         * rather than with the first constant.
         */
        @Shareable(value = Kind.ENUM,
                enumType = sbs.modid.client.ui.settings.CategoryFolding.Mode.class)
        public sbs.modid.client.ui.settings.CategoryFolding.Mode categoryFolding =
                sbs.modid.client.ui.settings.CategoryFolding.Mode.CLOSED_ON_STARTUP;

        /**
         * The folded groups, as {@code ModuleGroup} constant <b>names</b> - only read under
         * {@link sbs.modid.client.ui.settings.CategoryFolding.Mode#SAVE_CURRENT}.
         *
         * <p>Deliberately not {@code @Shareable}: how categories behave is a setting worth sending
         * somebody, which categories you happen to have folded is not.
         */
        public Set<String> collapsedCategories = new LinkedHashSet<>();

        /**
         * The folded sub-headers, as {@code GROUP/SUBGROUP} constant <b>names</b> ({@code SKILLS/FORAGING},
         * and {@code SKILLS/GENERAL} for a group's undeclared bucket) - only read under
         * {@link sbs.modid.client.ui.settings.CategoryFolding.Mode#SAVE_CURRENT}.
         *
         * <p>A field of its own rather than more entries in {@link #collapsedCategories}: that set is
         * rebuilt from the group folds alone on every write, so anything else kept in it would vanish
         * the next time a group was folded. Not {@code @Shareable}, for the reason above.
         */
        public Set<String> collapsedSubgroups = new LinkedHashSet<>();

        /**
         * Per-screen opacity, screen key -> percent (see {@code ui/theme/ScreenKeys}). A value here
         * replaces {@code theme.surfaceOpacity} for that screen; absent means the global value. Not
         * {@code @Shareable} yet - the share payload carries single values, not maps.
         */
        public java.util.Map<String, Integer> screenOpacity = new java.util.LinkedHashMap<>();

        /**
         * The folded custom sidebar categories, by their generated ids - same rules as the two sets
         * above, and not {@code @Shareable} for the same reason.
         */
        public Set<String> collapsedCustomCategories = new LinkedHashSet<>();

        /**
         * The player's own sidebar arrangement, or {@code ""} for the built-in one. One line of text
         * in {@link sbs.modid.client.ui.settings.layout.SidebarLayout}'s format, keyed on module ids,
         * group names and generated category ids - never labels or positions. Per profile like the
         * rest of this file, and shareable: a layout is a preference, not personal data.
         */
        @Shareable(Kind.SIDEBAR_LAYOUT)
        public String sidebarLayout = "";
    }

    public static final class BazaarSettings {
        /** Color tracked Bazaar orders in the Bazaar GUI by their live status. */
        @Shareable(Kind.BOOL)
        public boolean highlightItems = true;

        /** Send a client-side chat message whenever a tracked order's status changes. */
        @Shareable(Kind.BOOL)
        public boolean sendChatInfo = true;

        /**
         * Copy a cancelled BUY order's amount to the clipboard, ready to paste back into the custom
         * amount sign. A cancelled SELL offer states its amount in chat; a cancelled BUY order only
         * refunds coins, so the quantity is the one thing you would otherwise have to remember.
         */
        @Shareable(Kind.BOOL)
        public boolean copyCancelledAmount = true;

        /**
         * Remember the unfilled remainder of every cancelled BUY order, so it can be re-placed in a
         * click instead of being worked out again by hand.
         *
         * <p>The master switch for the feature: off, nothing is recorded and the panel never appears.
         * Recording happens only once Hypixel's refund line confirms the cancellation went through.
         */
        @Shareable(Kind.BOOL)
        public boolean orderHistory = true;

        /** Show the Order History beside the Bazaar menu. Off keeps recording, it just hides the panel. */
        @Shareable(Kind.BOOL)
        public boolean orderHistoryPanel = true;

        /**
         * Print the exact remaining count on each Order History row as well as drawing its bar.
         *
         * <p>Default off. The bar answers "how much of this did I actually get" without being read,
         * and the exact figures are in the row's tooltip - so the number is available on hover rather
         * than competing with the item name for a narrow row's width. On for anyone who would rather
         * read it straight off the list.
         */
        @Shareable(Kind.BOOL)
        public boolean orderHistoryNumbers = false;

        /**
         * How long a remembered remainder stays on offer, in hours (0 = never expires).
         *
         * <p>A remainder is a note about a market position, and an old note about a price that has
         * moved invites re-placing an order at a figure that is no longer competitive. Two days is
         * long enough to cover "I will finish this tomorrow" and short enough that the panel does not
         * become an archive.
         */
        @Shareable(value = Kind.INT, min = 0, max = 336)
        public int orderHistoryExpiryHours = 48;

        /**
         * Highlight claimable items (lore contains "Click to claim!") with an animated chroma border,
         * overriding the ordinary matched/outdated overlay for that slot.
         */
        @Shareable(Kind.BOOL)
        public boolean highlightClaimable = true;

        /** Movable Best Flips window over the Bazaar GUI (server-ranked top flip opportunities). */
        @Shareable(Kind.BOOL)
        public boolean bestFlips = false;

        /**
         * Show the Manage Orders panel as a movable HUD card during play, outside the Bazaar.
         *
         * <p>Default off, unlike {@code manageOrdersPanel}: the side panel appears only where it is
         * already relevant, but this one is on screen permanently, and most of the time an order's
         * status is not something you can act on from where you are standing.
         */
        @Shareable(Kind.BOOL)
        public boolean manageOrdersHud = false;

        /** Max starting capital for Best Flips in coins (sent to the server; 0 = unlimited). */
        public long flipsBudget = 0;

        /** Server ranking or local ranking for Best Flips. See {@link SourceChoice}. */
        public SourceChoice flipSource = new SourceChoice();

        // ------------------------------------------------------------------
        // Local flip fallback – used when the licence-backed ranking is unavailable.
        // Every threshold here is a setting rather than a constant on purpose: they are heuristics
        // over a single snapshot, and a player who reads their market better than we do should be
        // able to say so. See LocalFlipEngine.
        // ------------------------------------------------------------------

        /**
         * Compute flips locally when the backend ranking cannot be had — no licence, expired token,
         * offline, backend down. Off leaves the window empty on those paths, as it was before.
         */
        @Shareable(Kind.BOOL)
        public boolean localFlipFallback = true;

        /**
         * Bazaar Flipper account-upgrade level (0-2). Sets BOTH the sell tax (1.25% / 1.125% / 1.00%)
         * and how many orders may be open at once (14 / 21 / 28), because the one upgrade moves both.
         *
         * <p>Defaults to 0, the conservative end on both counts: the highest tax and the fewest slots.
         * A player who has the upgrade and does not say so is under-promised, which is the cheap
         * direction to be wrong in.
         */
        @Shareable(value = Kind.INT, min = 0, max = 2)
        public int bazaarFlipperLevel = 0;

        /** NPC flips: learn NPC shop prices while a shop is open, and mark profitable offers there. */
        @Shareable(Kind.BOOL)
        public boolean npcFlips = true;

        /** Show the best NPC flips as a section in the Best Flips window. */
        @Shareable(Kind.BOOL)
        public boolean npcFlipsInBestFlips = true;

        /** Profit per unit, in coins, below which an NPC flip is not shown. */
        @Shareable(value = Kind.INT, min = 0, max = 10_000_000)
        public int npcFlipMinProfit = 1;

        /** Units per week sold into Bazaar buy orders, below which an NPC flip is not shown. */
        @Shareable(value = Kind.INT, min = 0, max = 100_000_000)
        public int npcFlipMinVolume = 1_000;

        /** Which Bazaar price an NPC flip sells at: 0 insta-sell (top buy order), 1 sell offer (top ask). */
        @Shareable(value = Kind.INT, min = 0, max = 1)
        public int npcFlipSellSide = 0;

        /**
         * The share of an item's two-sided hourly volume the estimate assumes you capture, in percent.
         *
         * <p>You are not the only flipper in the market, and a projection that quietly assumes you take
         * all of it is wrong by whatever the competition is. 8% is deliberately pessimistic. The figure
         * is printed in the flow line beside every result, so a player who reads their own market
         * better can raise it and see exactly which number it moved.
         */
        @Shareable(value = Kind.INT, min = 1, max = 100)
        public int localFlipSharePct = 8;

        /** Spread above this percentage reads as an anomaly rather than as an opportunity. */
        @Shareable(value = Kind.INT, min = 1, max = 500)
        public int localFlipMaxSpreadPct = 12;

        /** Minimum units traded per week on EACH side; below it neither leg reliably fills. */
        public long localFlipMinWeeklyVolume = 25_000;

        /** Minimum distinct orders on each side. A thin book is one person's to move. */
        @Shareable(value = Kind.INT, min = 0, max = 500)
        public int localFlipMinOrders = 12;

        /** Share of one side's visible book allowed at a single price level before it reads as a wall. */
        @Shareable(value = Kind.INT, min = 10, max = 100)
        public int localFlipMaxConcentrationPct = 35;

        /** Show the entries the anomaly filters excluded, each labelled with why. */
        @Shareable(Kind.BOOL)
        public boolean localFlipShowFiltered = false;

        /** "Manage Orders" side panel: your tracked orders + live status next to the Bazaar menu. */
        @Shareable(Kind.BOOL)
        public boolean manageOrdersPanel = true;

        /** Search history on the Bazaar / Auction House search sign. */
        @Shareable(Kind.BOOL)
        public boolean searchHistoryEnabled = true;

        /**
         * Recent <b>Bazaar</b> search terms, most-recent-first (persisted). Kept apart from the
         * auction list on purpose: the two shops sell different things, so one shared list means
         * every Bazaar search is buried under armour pieces you looked up on the Auction House.
         */
        public java.util.List<String> searchHistory = new java.util.ArrayList<>();

        /** Recent <b>Auction House</b> search terms, most-recent-first (persisted). */
        public java.util.List<String> auctionSearchHistory = new java.util.ArrayList<>();
    }

public static final class CaseOpeningSettings {
        /** Play the case-opening animation. Purely visual - the reward is unchanged either way. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Animation length in milliseconds, clamped to 2000-5000 by the reel. */
        @Shareable(value = Kind.INT, min = 2000, max = 5000)
        public int durationMs = 3500;

        /** Screen flash + fireworks on the very best drops. */
        @Shareable(Kind.BOOL)
        public boolean topTierEffects = true;
    }

    public static final class FishingSettings {
        /** Master switch: nothing is tracked and no HUD is drawn while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Big "!" on the HUD when a sea creature spawns. */
        @Shareable(Kind.BOOL)
        public boolean spawnAlert = true;

        /** Which spawns raise the alert: everything, or only the trophies. */
        @Shareable(value = Kind.ENUM, enumType = SpawnAlertFilter.class)
        public SpawnAlertFilter alertFilter = SpawnAlertFilter.MYTHIC_AND_LEGENDARY;

        /** Null-safe read – an old or hand-edited config can leave the enum field unset. */
        public SpawnAlertFilter alertFilter() {
            return alertFilter == null ? SpawnAlertFilter.MYTHIC_AND_LEGENDARY : alertFilter;
        }

        /** Colour of the spawn alert "!" as {@code RRGGBB} ("0x"/"#" prefix tolerated). */
        @Shareable(Kind.HEX_COLOR)
        public String alertColorHex = "FF5555";

        /** Play a sound with the spawn alert. */
        @Shareable(Kind.BOOL)
        public boolean alertSound = true;

        /** Let the spawn alert pulse instead of just appearing. */
        @Shareable(Kind.BOOL)
        public boolean alertAnimation = true;

        /** How long the spawn alert stays up, in milliseconds. */
        @Shareable(value = Kind.INT, min = 200, max = 10000)
        public int alertDurationMs = 2000;

        /** Everything you caught, with counts, prices and total value. */
        @Shareable(Kind.BOOL)
        public boolean catchTracker = true;

        /** KILLED sea creatures with counts - separate from the profit tracker on purpose. */
        @Shareable(Kind.BOOL)
        public boolean seaCreatureTracker = true;

        /** Shiny Fish / Piranha / Eel shards with counts and value. */
        @Shareable(Kind.BOOL)
        public boolean shardTracker = true;

        /** Session profit, profit per hour and the totals. */
        @Shareable(Kind.BOOL)
        public boolean profitTracker = true;

        /** Show each row's coin value next to its count. */
        @Shareable(Kind.BOOL)
        public boolean showValues = true;

        /**
         * While an inventory / container is open, draw the tracker as a full scrollable list – the
         * catch cap is lifted so the cheaper items become visible, and the mouse wheel scrolls it.
         */
        @Shareable(Kind.BOOL)
        public boolean expandInInventory = true;

        /** Show the movable "baits left" chip: current bait icon + how many remain (inventory + Fishing Bag). */
        @Shareable(Kind.BOOL)
        public boolean showRemainingBaits = false;

        /** When the tracker panels are on screen: with a rod, permanently, or the mixed default. */
        @Shareable(value = Kind.ENUM, enumType = FishingHudVisibility.class)
        public FishingHudVisibility hudVisibility = FishingHudVisibility.ROD_OR_DATA;

        /** Null-safe read – an old or hand-edited config can leave the enum field unset. */
        public FishingHudVisibility hudVisibility() {
            return hudVisibility == null ? FishingHudVisibility.ROD_OR_DATA : hudVisibility;
        }

        /** Opacity of the fishing HUD panels, 0-100 - 0 leaves the readouts with no panel behind them. */
        @Shareable(value = Kind.INT, min = 0, max = 100)
        public int hudOpacity = 80;

        /** The big "!!!" the moment something bites your bobber. */
        @Shareable(Kind.BOOL)
        public boolean biteAlert = true;

        /**
         * Golden Fish timer HUD for lava fishing on the Crimson Isle. Off by default: every timing
         * in {@code GoldenFishRules} is ESTIMATED until an in-game probe confirms it.
         */
        @Shareable(Kind.BOOL)
        public boolean goldenFishTimer = false;

        /** Warn before idling throws the Golden Fish progress away. */
        @Shareable(Kind.BOOL)
        public boolean goldenFishResetWarning = true;

        /** Channels for "a Golden Fish is up". */
        public int goldenFishSpawnChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;

        /** Channels for "your Golden Fish progress is about to reset". */
        public int goldenFishResetChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;

        /** The "Reel in now!" line under the "!!!" – independent of the marks themselves. */
        @Shareable(Kind.BOOL)
        public boolean reelInText = true;

        /** Colour of the bite "!!!" as {@code RRGGBB} ("0x"/"#" prefix tolerated). */
        @Shareable(Kind.HEX_COLOR)
        public String biteColorHex = "FF4040";

        // --- Legacy hunting keys, boxed so absent != 0/"" - they moved to HuntingSettings. ---
        /** @deprecated moved to {@link HuntingSettings#huntingKey}; migrated then nulled. */
        @Deprecated
        public Integer huntingKey;
        /** @deprecated moved to {@link HuntingSettings#huntingCommand}; migrated then nulled. */
        @Deprecated
        public String huntingCommand;
        /** @deprecated moved to {@link HuntingSettings#shardFusionKey}; migrated then nulled. */
        @Deprecated
        public Integer shardFusionKey;
        /** @deprecated moved to {@link HuntingSettings#shardFusionCommand}; migrated then nulled. */
        @Deprecated
        public String shardFusionCommand;
    }

    /** Sea Creature Announcer (Fishing): what your own rod just hooked, said out loud in chat. */
    public SeaCreatureAnnouncerSettings seaCreatureAnnouncer = new SeaCreatureAnnouncerSettings();

    public static final class SeaCreatureAnnouncerSettings {

        /**
         * Master switch. Ships <b>off</b>: it is a new module that writes into the player's chat,
         * and a feature that talks unasked is one they should have switched on themselves.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** The chat line itself. Off leaves the title / sound / highlight extras usable alone. */
        @Shareable(Kind.BOOL)
        public boolean chat = true;

        /**
         * The chat line's shape. Placeholders: {@code {prefix}}, {@code {creature}},
         * {@code {rarity}}. Anything else in the string is printed as typed, so a player who wants
         * bare names sets it to {@code {creature}}. The {@code [SBS]} tag is prepended by
         * {@code SBSChat} and is not part of this.
         */
        @Shareable(value = Kind.TEXT, max = 64)
        public String chatFormat = "{prefix} {creature} ({rarity})";

        /** What {@code {prefix}} expands to. */
        @Shareable(value = Kind.TEXT, max = 24)
        public String chatPrefix = "Sea Creature";

        // --- Per-rarity switches. One boolean per tier rather than a set: the tiers are a fixed
        // ladder of five, and a boolean each is a settings row each with no migration to write. ---

        /** Announce {@link sbs.modid.client.skills.fishing.model.SeaCreatureRarity#COMMON} spawns. */
        @Shareable(Kind.BOOL)
        public boolean announceCommon = false;

        /** Announce RARE spawns. */
        @Shareable(Kind.BOOL)
        public boolean announceRare = false;

        /** Announce EPIC spawns. */
        @Shareable(Kind.BOOL)
        public boolean announceEpic = true;

        /** Announce LEGENDARY spawns. */
        @Shareable(Kind.BOOL)
        public boolean announceLegendary = true;

        /** Announce MYTHIC spawns. */
        @Shareable(Kind.BOOL)
        public boolean announceMythic = true;

        /**
         * Announce a creature this build has never heard of – a nametag the creature table does not
         * contain. Off by default because the name is then whatever Hypixel wrote on the stand, not
         * something we have verified; the sighting is logged either way, which is how a new area's
         * creatures get into the table without an update.
         */
        @Shareable(Kind.BOOL)
        public boolean announceUnknown = false;

        /**
         * Per-creature exceptions to the tier switches above, keyed by the creature's canonical name
         * ({@code FishingData}'s spelling). Absent means
         * {@link sbs.modid.client.skills.fishing.model.CreatureAnnounceMode#AUTO}, so the map holds
         * only the rows the player actually changed.
         */
        public java.util.Map<String, sbs.modid.client.skills.fishing.model.CreatureAnnounceMode>
                creatureModes = new java.util.LinkedHashMap<>();

        /** Null-safe read – an old or hand-edited config can leave the map unset. */
        public sbs.modid.client.skills.fishing.model.CreatureAnnounceMode modeFor(String creature) {
            var fallback = sbs.modid.client.skills.fishing.model.CreatureAnnounceMode.AUTO;
            if (creatureModes == null || creature == null) {
                return fallback;
            }
            var mode = creatureModes.get(creature);
            return mode == null ? fallback : mode;
        }

        /** Whether {@code tier}'s switch is on. */
        public boolean announces(sbs.modid.client.skills.fishing.model.SeaCreatureRarity tier) {
            if (tier == null) {
                return false;
            }
            return switch (tier) {
                case UNKNOWN -> announceUnknown;
                case COMMON -> announceCommon;
                case RARE -> announceRare;
                case EPIC -> announceEpic;
                case LEGENDARY -> announceLegendary;
                case MYTHIC -> announceMythic;
            };
        }

        /** Also put the spawn on screen as a title / subtitle. */
        @Shareable(Kind.BOOL)
        public boolean title = false;

        /** Play a ping when a spawn is announced. */
        @Shareable(Kind.BOOL)
        public boolean sound = true;

        /** Which ping – the shared alert tones, so it sounds like the rest of the mod. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.audio.SbsAudio.Tone.class)
        public sbs.modid.client.core.audio.SbsAudio.Tone soundTone =
                sbs.modid.client.core.audio.SbsAudio.Tone.CHIME;

        /** Null-safe read – an old or hand-edited config can leave the enum field unset. */
        public sbs.modid.client.core.audio.SbsAudio.Tone soundTone() {
            return soundTone == null ? sbs.modid.client.core.audio.SbsAudio.Tone.CHIME : soundTone;
        }

        /** Box the mob that spawned, in its rarity's colour, for {@link #highlightSeconds}. */
        @Shareable(Kind.BOOL)
        public boolean highlight = false;

        /** How long the spawn highlight stays on the mob. */
        @Shareable(value = Kind.INT, min = 1, max = 60)
        public int highlightSeconds = 8;

        /**
         * Resolve the creature from its nametag when the chat line did not name one. This is what
         * makes a fishing area newer than this build work at all, and what disambiguates the lines
         * several creatures share.
         */
        @Shareable(Kind.BOOL)
        public boolean nametagFallback = true;

        /**
         * How long after an announcement the same creature is treated as already announced. This is
         * the anti-spam: the chat trigger and the nametag fallback both see one spawn, and whichever
         * is second must stay quiet. Long enough to cover the fallback's scan window, short enough
         * that a genuinely fast second catch of the same creature still gets its own line.
         */
        @Shareable(value = Kind.INT, min = 500, max = 15000)
        public int dedupeMs = 4000;
    }

    /** Settings shared by every Foraging module (Beacon Tuning, and whatever Galatea gets next). */
    public ForagingSettings foraging = new ForagingSettings();

    public static final class ForagingSettings {
        /**
         * Run the foraging features only on the foraging islands
         * ({@link sbs.modid.client.skills.SkillIslands#FORAGING_ISLANDS}). One field rather than one
         * per module, for the same reason {@link MiningSettings#islandLock} is one: "foraging features
         * belong on Galatea" is a single decision, not one to make again in every module.
         */
        public boolean islandLock = true;
    }

    /** Sweep HUD (Foraging): the effective Sweep Hypixel prints per chop. */
    public SweepSettings sweep = new SweepSettings();

    public static final class SweepSettings {
        /** Master toggle. Nothing is parsed and nothing is drawn while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** The tree's toughness row, when the message carries it. */
        @Shareable(Kind.BOOL)
        public boolean showToughness = true;

        /** The blocks-this-chop row, when the message carries it. */
        @Shareable(Kind.BOOL)
        public boolean showBlocks = true;

        /** Session max and average. Off by default - the live number is what the card is for. */
        @Shareable(Kind.BOOL)
        public boolean showSessionStats = false;

        /** Keep the card to the foraging islands. */
        @Shareable(Kind.BOOL)
        public boolean onlyForagingIslands = true;

        /**
         * Only show the card while a log actually came down recently.
         *
         * <p>Different question from {@link #staleMode}, which is about how old the displayed
         * <i>number</i> is. This one is about whether the player is chopping at all, so it still
         * hides the card when Hypixel repeats a message or when the mode is
         * {@code KEEP}. Off by default: it narrows an existing card, and that is the player's call.
         */
        @Shareable(Kind.BOOL)
        public boolean requireRecentChop = false;

        /** How long a felled log keeps the card up, in seconds. */
        @Shareable(value = Kind.INT, min = 5, max = 120)
        public int recentChopSeconds = 20;

        /**
         * Only show the card while an axe is in hand.
         *
         * <p>Recognised by the vanilla item and by an id containing {@code AXE} rather than from a
         * catalogue of Galatea's axes, which nobody here has seen - see
         * {@code sbs.modid.client.skills.foraging.model.ForagingItems}.
         */
        @Shareable(Kind.BOOL)
        public boolean requireAxeInHand = false;

        /** What the card does once no chop has arrived for {@link #staleAfterSeconds}. */
        public sbs.modid.client.skills.foraging.model.SweepStaleMode staleMode =
                sbs.modid.client.skills.foraging.model.SweepStaleMode.GREY;

        /** How long a chop stays "live", in seconds. Bounds match the settings slider. */
        @Shareable(value = Kind.INT, min = 2, max = 60)
        public int staleAfterSeconds = 10;

        /**
         * Take the raw per-chop lines out of chat once the card is showing them.
         *
         * <p><b>Off by default on purpose.</b> Plenty of players call these lines spam and this is a
         * real convenience, but a mod that starts by hiding messages nobody asked it to hide has
         * made a decision that belongs to the player. Only lines the parser fully read are ever
         * hidden - see {@code SweepTracker.shouldHide}.
         */
        @Shareable(Kind.BOOL)
        public boolean hideChatMessages = false;

        /** Whether the one-time "switch the detail lines on" hint has been given. */
        public boolean sweepHintShown = false;

        /** Which channels that hint uses. */
        public int hintChannels = sbs.modid.client.core.alert.AlertChannel.CHAT.bit();
    }

    /** Chocolate Factory helper: best upgrade by payback, strays, Time Tower, the barn. */
    public ChocolateFactorySettings chocolateFactory = new ChocolateFactorySettings();

    public static final class ChocolateFactorySettings {

        /**
         * Master toggle. Nothing is read, drawn or announced while this is off.
         *
         * <p><b>Off by default, and the page says why.</b> Not one lore string this reads has been
         * seen in game - no session has opened the factory with {@code /sbs probe} armed - so every
         * keyword below is a hypothesis, which root {@code AGENTS.md} says ships off.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Outline the shortest-payback upgrade and print each slot's payback on it. */
        @Shareable(Kind.BOOL)
        public boolean bestUpgradeHighlight = true;

        /** The movable card: chocolate per second, the best buy and the wait for it. */
        @Shareable(Kind.BOOL)
        public boolean showHud = false;

        // ---------------------------------------------------------- what the reader looks for
        // All five stay inside ShareValues.MAX_TEXT (64). That cap is not what an
        // @Shareable(max = ...) says - the attribute is only read for numbers - and a longer
        // default is one that silently shortens the first time a config is shared.

        /** Words marking a lore line as a price. ESTIMATED - see ChocolateLore. */
        @Shareable(Kind.TEXT)
        public String costWords =
                sbs.modid.client.helper.chocolate.logic.ChocolateLore.DEFAULT_COST_WORDS;

        /** Words marking a lore line as a rate. ESTIMATED - see ChocolateLore. */
        @Shareable(Kind.TEXT)
        public String rateWords =
                sbs.modid.client.helper.chocolate.logic.ChocolateLore.DEFAULT_RATE_WORDS;

        /** Words in a slot's NAME that mean it is a stray rabbit. ESTIMATED. */
        @Shareable(Kind.TEXT)
        public String strayWords = "stray";

        /** Words in a slot's NAME that mean it is the Time Tower. ESTIMATED. */
        @Shareable(Kind.TEXT)
        public String towerWords = "time tower";

        /** Words in a slot's NAME that mean it is the Rabbit Barn. ESTIMATED. */
        @Shareable(Kind.TEXT)
        public String barnWords = "rabbit barn, barn";

        // ---------------------------------------------------------- alerts

        /** Say something when a stray rabbit is in the open menu. The player does the clicking. */
        @Shareable(Kind.BOOL)
        public boolean strayAlert = true;

        /**
         * Channels for the stray alert. Title and sound by default: it is the one thing here that
         * is gone if it is not noticed within seconds.
         */
        public int strayChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;

        /** Warn when the barn is at or past {@link #barnWarnPercent} full. */
        @Shareable(Kind.BOOL)
        public boolean barnWarning = true;

        /** How full the barn has to be before it is worth saying so. */
        @Shareable(value = Kind.INT, min = 50, max = 100)
        public int barnWarnPercent = 90;

        /** Warn when a Time Tower charge is ready and the tower is not running. */
        @Shareable(Kind.BOOL)
        public boolean towerAlert = true;

        /**
         * Channels for the barn and Time Tower warnings. Chat by default, and separate from the
         * stray channels on purpose: these two are things to deal with at some point, the stray is
         * a thing to deal with now, and one channel setting cannot say both.
         */
        public int statusChannels = sbs.modid.client.core.alert.AlertChannel.CHAT.bit();
    }

    /** Honey (Foraging): the smear cooldown on Galatea's protected honey trees. */
    public HoneySettings honey = new HoneySettings();

    public static final class HoneySettings {

        /** What {@link #chatWords} means when the player has not changed it. */
        public static final String DEFAULT_WORDS = "lather, smear, honeycomb, honey tree";

        /**
         * Master toggle. Nothing is armed, parsed, counted or drawn while this is off.
         *
         * <p><b>Off by default, and that is not timidity.</b> Neither the Honeycomb item id nor the
         * chat wording exists in any dataset this repository holds, so every detection here is
         * built on a hypothesis - which root {@code AGENTS.md} says ships off, with the page saying
         * why. It becomes a sensible default the moment one session confirms the wording.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * Substrings that identify Honeycomb by its SkyBlock id, comma-separated.
         *
         * <p>A setting rather than a constant because the id is unverified - see
         * {@code sbs.modid.client.skills.foraging.model.HoneyItems}, which also explains why the
         * default is not simply {@code COMB}.
         */
        @Shareable(value = Kind.TEXT, max = 200)
        public String honeycombIds = sbs.modid.client.skills.foraging.model.HoneyItems.DEFAULT_IDS;

        /**
         * Words that mark a chat line as "the smear worked", comma-separated.
         *
         * <p>A setting for the same reason: nobody here has seen the line. A line matching one of
         * these inside an open confirmation window starts that tree's timer.
         */
        @Shareable(value = Kind.TEXT, max = 200)
        public String chatWords = DEFAULT_WORDS;

        /**
         * How long the cooldown is assumed to be, in minutes.
         *
         * <p><b>{@code ESTIMATED}, from the request rather than from observation.</b> Nothing has
         * timed a real cooldown, so every MM:SS this feature draws is only as right as this number
         * - which is exactly why it is an override and why a time read out of the world beats it.
         */
        @Shareable(value = Kind.INT, min = 1, max = 60)
        public int durationMinutes = 15;

        /** How far a click may be from a shipped tree, horizontally, and still be that tree. */
        @Shareable(value = Kind.INT, min = 4, max = 24)
        public int toleranceBlocks = 9;

        /** How long a click waits for a chat line to confirm it, in seconds. */
        @Shareable(value = Kind.INT, min = 1, max = 15)
        public int confirmWindowSeconds = 4;

        /**
         * Start the timer anyway when the confirmation window runs out.
         *
         * <p><b>On by default</b>, because the wording is a guess: refusing would make the feature
         * silently do nothing, which is indistinguishable from being switched off and is the exact
         * failure recorded in {@code docs/issues/skills.md}. A timer started this way is marked
         * unconfirmed and drawn with a {@code ?}, so a guess is never shown as a fact.
         */
        @Shareable(Kind.BOOL)
        public boolean startWithoutConfirmation = true;

        /** Print one line in chat when a smear starts a timer. */
        @Shareable(Kind.BOOL)
        public boolean startMessage = true;

        /** Put the remaining time under the tree's waypoint label. */
        @Shareable(Kind.BOOL)
        public boolean showOnWaypoint = true;

        /** Show the movable card listing every running timer. */
        @Shareable(Kind.BOOL)
        public boolean showHud = true;

        /** Only draw timers belonging to the island underfoot. The timers keep running either way. */
        @Shareable(Kind.BOOL)
        public boolean onlyOnHoneyIslands = true;

        /** Which channels announce an expiry and the pre-warning. */
        public int notifyChannels = sbs.modid.client.core.alert.AlertChannel.CHAT.bit()
                | sbs.modid.client.core.alert.AlertChannel.SOUND.bit();

        /** How long before a timer runs out to warn, in seconds. {@code 0} switches it off. */
        @Shareable(value = Kind.INT, min = 0, max = 300)
        public int preWarningSeconds = 60;

        /** How long a finished timer is kept before it is dropped, in minutes. */
        @Shareable(value = Kind.INT, min = 5, max = 360)
        public int pruneAfterMinutes = 60;

        /**
         * Trust a remaining time read off a hologram over our own clock.
         *
         * <p><b>Off by default.</b> The reader is written against a hologram format nobody has
         * observed; on by default would mean preferring an unverified parse to an unverified
         * constant while looking like a measurement. Its logging half runs regardless, and that is
         * what makes switching this on an informed act.
         */
        @Shareable(Kind.BOOL)
        public boolean syncFromWorld = false;
    }

    /** Beacon Tuning (Skills): the frequency minigame at the Moonglade and Torrhus beacons. */
    public BeaconTuningSettings beaconTuning = new BeaconTuningSettings();

    public static final class BeaconTuningSettings {
        /** Master toggle. Nothing is scanned and nothing is drawn while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /**
         * Also ring the traits the beacon already matches, thinly. On by default: "that one is
         * already right" is the difference between a helper you trust and one you double-check.
         */
        @Shareable(Kind.BOOL)
        public boolean markMatching = true;
    }

    /** Trap Highlighter (Dungeons): tripwire and dispenser boxes inside a Catacombs run. */
    public TrapHighlightSettings trapHighlight = new TrapHighlightSettings();

    public static final class TrapHighlightSettings {
        /** Master toggle. Nothing is indexed and nothing is drawn while this is off. */
        public boolean enabled = true;

        /** Draw tripwire, grouped into one shape per run rather than a box per block. */
        public boolean tripwires = true;

        /** Draw arrow dispensers, which are usually sunk into a wall or a ceiling. */
        public boolean dispensers = true;

        /** Colour of the tripwire runs. */
        public sbs.modid.client.core.render.OverlayColor tripwireColor =
                sbs.modid.client.core.render.OverlayColor.RED;

        /** Colour of the dispenser boxes - a different one, so the two read apart at a glance. */
        public sbs.modid.client.core.render.OverlayColor dispenserColor =
                sbs.modid.client.core.render.OverlayColor.ORANGE;

        /**
         * Draw the spike showing which way a dispenser fires. Read off the blockstate, so it is the
         * real direction rather than a guess from which side of the block happens to be open.
         */
        public boolean showFacing = true;

        /** How far away a trap is still drawn, in blocks - the cap that keeps a big room readable. */
        public int renderDistance = 48;

        /**
         * Draw traps the camera has no line to.
         *
         * <p>Off by default: a trap two rooms away is not the one about to go off under you, and a
         * corridor seen through three walls of boxes is harder to read than one with none. The world
         * overlay has no depth test, so this is done with one ray per trap rather than by the GPU -
         * which is why it is a real switch and not a promise the renderer cannot keep.
         */
        public boolean showThroughWalls = false;
    }

    /** Level Colours (Interface): your own colour table for the SkyBlock level. */
    public LevelColorSettings levelColors = new LevelColorSettings();

    public static final class LevelColorSettings {
        /**
         * Colour the SkyBlock level from the table below instead of leaving it as Hypixel wrote it.
         *
         * <p><b>Off by default, and that is the point.</b> Reproducing the server's colour is what
         * this mod has always done, and it is the only version that stays right for a tier Hypixel
         * has not shipped yet - see {@code core/level/LevelColors}. Turning this on trades that for
         * control, which is the player's trade to make, not ours.
         */
        public boolean enabled = false;

        /**
         * The bands, each a level to start at plus a colour. Order is not trusted: the resolver
         * sorts a copy, so a hand-edited file is read charitably and never rewritten underneath the
         * player.
         */
        public List<sbs.modid.client.core.level.LevelTier> tiers =
                sbs.modid.client.core.level.LevelColors.defaultTiers();
    }

    /** Hunting (Skills): the Hunting/Fusion hotkeys and the in-menu fusion helpers. */
    public HuntingSettings hunting = new HuntingSettings();

    public static final class HuntingSettings {
        /**
         * Session card for shards caught while hunting. Counts and rates only - see
         * {@code docs/features/hunting-profit-tracker.md} for why it carries no coin figure yet.
         */
        @Shareable(Kind.BOOL)
        public boolean sessionTracker = true;

        /** Hotkey opening the Hunting menu; 0 = unbound. */
        @Shareable(Kind.KEYCODE)
        public int huntingKey = 0;

        /** What the Hunting hotkey runs - configurable because Hypixel renames these menus. */
        public String huntingCommand = "/hunting";

        /** Hotkey opening Shard Fusion; 0 = unbound. */
        @Shareable(Kind.KEYCODE)
        public int shardFusionKey = 0;

        /** What the Shard Fusion hotkey runs. */
        public String shardFusionCommand = "/fusion";

        /** Pressed inside the Fusion menu: clicks the confirm slot to accept the fusion. */
        @Shareable(Kind.KEYCODE)
        public int acceptFusionKey = 0;

        /** Pressed inside the Fusion menu: re-selects the shards of the last accepted fusion. */
        @Shareable(Kind.KEYCODE)
        public int repeatFusionKey = 0;

        /**
         * Hunting Box shard value: read the box while it is open and say what is in it.
         *
         * <p>The master switch for the whole feature - nothing is scanned, stored or drawn while it
         * is off. On by default: it acts inside one menu only, and the figure it leads with is the
         * surplus, which is the one nobody can work out by eye.
         */
        @Shareable(Kind.BOOL)
        public boolean boxValue = true;

        /** The value panel beside the Hunting Box menu. */
        @Shareable(Kind.BOOL)
        public boolean boxPanel = true;

        /** The per-shard breakdown appended to a shard's own tooltip inside the box. */
        @Shareable(Kind.BOOL)
        public boolean boxTooltip = true;

        /**
         * Missing Shards: while the Attribute Menu is open, list the shards still to collect.
         *
         * <p>Master switch for the whole feature - nothing is detected, parsed, stored or drawn while
         * it is off.
         *
         * <p><b>Off by default</b>, and not for the usual reason. Nobody has read this menu off a
         * live client: not its title, not whether it pages, not whether its entries carry an item id,
         * and not what an unowned entry looks like. Every reader is written to fail to "not known"
         * rather than to a number, but a panel that is silently empty - or worse, silently confident -
         * is not something to switch on for somebody who did not ask for it. One
         * {@code /sbs probe arm} in the open menu is what promotes this to on by default.
         */
        @Shareable(Kind.BOOL)
        public boolean missingShards = false;

        /** The panel itself, separable from the master switch. */
        @Shareable(Kind.BOOL)
        public boolean missingShardsPanel = true;

        /** Persisted {@code ShardSort}, by name so a reordered enum cannot repoint a stored choice. */
        public String missingShardsSort = "PRICE";

        /** Highest first. Off by default: cheapest first answers "what can I go and get now". */
        @Shareable(Kind.BOOL)
        public boolean missingShardsDescending = false;

        /** Persisted {@code ShardPriceSource}: instant buy, or the cheaper standing buy order. */
        public String missingShardsPriceSource = "INSTANT_BUY";

        /** Include shards whose attribute is already finished. */
        @Shareable(Kind.BOOL)
        public boolean missingShardsShowOwned = false;

        /** Include part-collected shards. On, because a partly collected shard is still a gap. */
        @Shareable(Kind.BOOL)
        public boolean missingShardsShowPartial = true;

        /** Clicking a row closes the menu and runs {@code /bz} once for that shard. */
        @Shareable(Kind.BOOL)
        public boolean missingShardsClickOpensBazaar = true;

        /**
         * The title fragment the Attribute Menu is recognised by, matched colour-stripped and
         * lower-cased. A setting rather than a constant for the reason {@link #huntingCommand} is
         * one: Hypixel renames these menus, and a feature that dies on a name nobody can correct
         * from the config dies for good.
         */
        public String missingShardsTitle = "attribute";
    }

    /** Critter Finder (Skills): boxes the critters that hide from you inside the Critter Safari. */
    public CritterFinderSettings critterFinder = new CritterFinderSettings();

    public static final class CritterFinderSettings {
        /**
         * Master toggle. Nothing is scanned, nothing is drawn and nothing is logged while this is
         * off.
         *
         * <p><b>Off by default</b>, and not for the usual reason. The critter this was built for is
         * named {@code Hideonfloor} in the request, and that name appears in no dataset, cache or log
         * this repository holds - the only critter of that family in the bundled Hypixel data is
         * {@code Hideonleaf}. Until one trip into the Safari confirms which is right, the shipped
         * default may match nothing, and a highlight that is silently dark is worse than one the
         * player switched on knowingly. {@link #critterNames} is how it gets fixed without an update.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * The critter names to box, comma-separated, matched case-insensitively and on word
         * boundaries against the name extracted from the nametag.
         *
         * <p>A setting rather than a constant for the same reason {@link SafariSettings#zoneName} is
         * one: this is the single fact the feature cannot verify from inside the client, and a
         * feature that dies on a name nobody can correct from the config dies for good. Adding a
         * second name here is also how the other critters that share the hiding mechanic get boxed -
         * the match is deliberately never a {@code Hide} prefix, which would light up the whole
         * family at once.
         */
        public String critterNames = "Hideonfloor";

        /**
         * Box and tracer colour as {@code RRGGBB}. Magenta by default because no other shipped
         * highlight uses it - the traps are red and orange, the pelt animal orange, the waypoint
         * presets blue - so a critter box is never confused for one of those.
         */
        public String colorHex = "FF2FD5";

        /** A line from the crosshair to the critter, so it can be found before the box is on screen. */
        @Shareable(Kind.BOOL)
        public boolean showTracer = true;

        // Retired: "Draw Through Walls". A critter is now boxed only while the player has a clear
        // line to it, which is the rule MobHighlightTracker applies to every mob it draws. Boxing a
        // hiding critter through the terrain is not showing the player what they can see, it is
        // finding it for them. Gson drops the stored key on the next save; nothing reads it.

        /**
         * How far away a critter is still looked for and drawn, in blocks. It caps the <i>scan</i> as
         * well as the draw, so the entity sweep never walks further than the render could show.
         */
        public int renderDistance = 64;

        /** One chat line and a ping the first time a critter is seen. */
        @Shareable(Kind.BOOL)
        public boolean foundPing = true;

        /**
         * The sparkling half of the same sweep: boxes a critter carrying the sparkling marker in its
         * nametag, whatever the critter is called. Gated independently of {@link #enabled} - the
         * sweep runs while either half is on - because wanting the rare ones does not mean wanting a
         * box on every watched critter in range.
         */
        public SparklingSettings sparkling = new SparklingSettings();

        /**
         * Gemzie Waypoints: markers on the known Gemzie spawn spots, drawn on the same page as the
         * critter highlight because they are the same trip into the same instance.
         */
        public GemzieSettings gemzie = new GemzieSettings();
    }

    /**
     * The sparkling-critter highlight, riding the Critter Finder's sweep and renderer.
     *
     * <p>Kept beside {@code CritterFinderSettings} rather than inside it as loose fields because the
     * two halves are switched, coloured and pinged separately - the shared rows are the ones about
     * <i>drawing</i> (distance, tracer, occlusion), and those stay on the parent.
     */
    public static final class SparklingSettings {

        /**
         * Master toggle for the sparkling half.
         *
         * <p><b>Off by default</b>, for the reason {@link CritterFinderSettings#enabled} is: the
         * marker word came with the request and no mob, nametag or critter name in any dataset,
         * cache or log this repository holds carries it. (The one hit for "Sparkling" is the
         * {@code SPARKLING_RUNE} item, which is not a critter and not evidence.) Until one Safari
         * trip confirms how Hypixel marks these critters, the shipped default may match nothing -
         * and a highlight that is silently dark is worse than one the player switched on knowingly.
         * {@link #markers} is how it gets fixed without an update.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * What marks a critter as sparkling, comma-separated, matched case-insensitively against the
         * <b>raw</b> nametag with only the colour codes taken out.
         *
         * <p>The raw tag and not the extracted name, because
         * {@code MobHighlightTracker.mobNameInNametag} strips every character outside
         * {@code [A-Za-z0-9'.,/\- ]} - so a symbol marker ({@code ✦}, {@code ✨}) is gone before any
         * compare could see it. A marker of letters and digits is matched on word boundaries; one
         * carrying a symbol is matched by containment, since a symbol has no word boundary.
         */
        public String markers = "Sparkling";

        /**
         * Box, label and tracer colour as {@code RRGGBB}. Cyan by default because every neighbouring
         * highlight is already something else - the critter box is magenta, the Gemzie spots green,
         * purple and orange, the quest bells gold, the preset waypoints {@code 7094FF}.
         */
        public String colorHex = "4DE8FF";

        /**
         * One chat line and a ping the first time a sparkling critter is seen.
         *
         * <p>Its own toggle rather than {@link CritterFinderSettings#foundPing}: these are the rare
         * ones, so a player may well want silence for the watched critters and a sound for these.
         */
        @Shareable(Kind.BOOL)
        public boolean ping = true;
    }

    /**
     * Markers on the known Gemzie spawn spots inside the Critter Safari.
     *
     * <p>The positions themselves are not here - they live in {@code GemzieSpot}, which is the one
     * place a spawn spot exists. This holds only what the player may change, keyed by that enum's
     * stable id so a renamed label cannot move a correction onto a different spot.
     */
    public static final class GemzieSettings {

        /**
         * Master toggle. Nothing is published and nothing is drawn while this is off.
         *
         * <p><b>Off by default</b>, for the reason {@link CritterFinderSettings#enabled} is: the three
         * coordinates were supplied in the request and appear in no dataset this repository holds,
         * so they have never been checked against the game. Markers standing in the wrong place are
         * worse than markers the player switched on knowingly.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Append the distance to each label ("Gemzie 1 §712m"). */
        @Shareable(Kind.BOOL)
        public boolean showDistance = true;

        /**
         * Blocks below which a marker fades out, or 0 to never fade.
         *
         * <p>Six by default: near enough that the marker is still guiding you in at arm's length,
         * far enough that the beam and the label are gone before you are stood on the spot trying to
         * interact with what is there.
         */
        public int fadeWithin = 6;

        /**
         * Per-spot colour as {@code RRGGBB}, keyed by {@code GemzieSpot.id()}. Absent means "the
         * shipped colour", which is why this starts empty rather than pre-filled: a default that was
         * written out once could never be corrected by an update.
         */
        public Map<String, String> colorHex = new LinkedHashMap<>();

        /**
         * Per-spot opacity as a percentage of the alpha the renderer would otherwise use, keyed the
         * same way. A percentage and not an eighth hex digit because the shipped colour picker
         * parses {@code RRGGBB} only - see {@code GemzieSpot}.
         */
        public Map<String, Integer> opacity = new LinkedHashMap<>();

        /** The colour in effect for a spot: the player's if they set one, else the shipped one. */
        public String colorHexOf(sbs.modid.client.skills.hunting.model.GemzieSpot spot) {
            String own = colorHex.get(spot.id());
            return own == null || own.isBlank() ? spot.defaultColorHex() : own;
        }

        /** The opacity in effect for a spot, clamped to 0-100. */
        public int opacityOf(sbs.modid.client.skills.hunting.model.GemzieSpot spot) {
            Integer own = opacity.get(spot.id());
            int value = own == null ? spot.defaultOpacity() : own;
            return Math.max(0, Math.min(100, value));
        }

        /** How many spots the player has recoloured or re-faded, for the settings status line. */
        public int changedSpots() {
            Set<String> touched = new LinkedHashSet<>(colorHex.keySet());
            touched.addAll(opacity.keySet());
            return touched.size();
        }

        /** Forgets every per-spot change, restoring the shipped colours and opacities. */
        public void resetSpots() {
            colorHex.clear();
            opacity.clear();
        }
    }

    /** Hideyho Finder (Skills): the walk between the places the Hideyho hides, one round at a time. */
    public HideyhoSettings hideyho = new HideyhoSettings();

    /** Wardrobe: what the client draws on the player themself. */
    public WardrobeSettings wardrobe = new WardrobeSettings();

    /**
     * The Wardrobe group's settings - cosmetics this client paints onto your own player.
     *
     * <p><b>Everything in here is local.</b> Hypixel never hears about any of it and no other player
     * sees it: these textures are chosen on this machine, at render time, on top of the skin the
     * server sent. That is a limit of a client mod rather than a decision, and the settings page says
     * so, because "nobody else can see my cape" is otherwise read as the feature being broken.
     */
    public static final class WardrobeSettings {

        /**
         * Draw the SBS cape on your own player.
         *
         * <p><b>On by default</b>, which is safe in a way most defaults are not: it is purely
         * cosmetic, visible only to this client, and reversible with one click. It replaces whatever
         * cape the account already has while it is on - a Mojang cape included - so the switch is
         * how you get that one back.
         */
        @Shareable(Kind.BOOL)
        public boolean sbsCape = true;
    }

    /**
     * The Hideyho hide-and-seek finder.
     *
     * <p><b>There is no "through walls" here, and that is the design.</b> Every other marker set in
     * the mod has that switch; this one publishes its spots occluded and offers no way back. A
     * finder that shines through the terrain answers the question the round is asking - it turns a
     * search into a straight line at the critter - so what this feature draws is the <i>route</i>
     * between the places it can be, and the walls stay in the way of the rest.
     *
     * <p><b>The spots themselves are not in here and are not shipped at all.</b> They are learned
     * from this client's own finds and kept in {@code HideyhoSpots}; there is no bundled coordinate
     * table, because the only lists that exist belong to a wiki under CC BY-SA and to another mod,
     * and neither is ours to copy.
     */
    public static final class HideyhoSettings {

        /**
         * Master toggle. Nothing is published, routed, learned or logged while this is off.
         *
         * <p><b>Off by default.</b> The critter's name is real - {@code Hideyho} is in the bundled
         * shard data - but every chat line the round is driven by came from a wiki and has never
         * been watched arriving, and the spot list starts empty on a fresh install. A feature that
         * may do nothing on its first trip is one the player switches on knowingly.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * Hand the spots to the pathfinder as a goal set, so the route leads to the nearest unchecked
         * one <i>by route</i>. Off leaves the markers standing and the walking to the player.
         */
        @Shareable(Kind.BOOL)
        public boolean pathfind = true;

        /**
         * Keep the spots up outside a round as well.
         *
         * <p>Off by default: the markers describe a search that is running, and a Safari's worth of
         * standing markers is scenery rather than guidance. On is for learning the map.
         */
        @Shareable(Kind.BOOL)
        public boolean showAlways = false;

        /** Marker colour as {@code RRGGBB}. Mint, which no other shipped marker set uses. */
        public String colorHex = "4DFFC3";

        /** The distance readout under each spot's label. */
        @Shareable(Kind.BOOL)
        public boolean showDistance = true;

        /**
         * How close, in blocks, counts as having looked at a spot - together with a clear line to it.
         * Distance alone would cross off a spot behind a wall the player never saw, which is the one
         * bug that makes a finder untrustworthy.
         */
        public int arriveRadius = 6;

        /**
         * How long a round may run before it is dropped, in seconds.
         *
         * <p>Hypixel's real timeout is unknown, so this is a ceiling rather than a reading of it: a
         * round that never produces a payout line has to end somewhere, and markers standing over a
         * search that finished are worse than none.
         */
        public int roundSeconds = 300;

        /** One chat line when a round starts, ends or times out. */
        @Shareable(Kind.BOOL)
        public boolean announce = true;

        /**
         * Restrict the markers to the biome named by {@link #biomeWord}.
         *
         * <p><b>Off by default</b>, and not by preference: whether the Haunted biome is a zone of its
         * own in the {@code ⏣} line has never been checked. Gating on an unverified zone word would
         * make the whole feature silently dark, so the restriction is opt-in until one trip settles
         * it - the module logs the zone it sees, which is what settles it.
         */
        @Shareable(Kind.BOOL)
        public boolean restrictToBiome = false;

        /**
         * The biome word {@link #restrictToBiome} matches against the zone, case-insensitively. A
         * setting for the reason {@code SafariSettings.zoneName} is one.
         */
        public String biomeWord = "Haunted";
    }

    /** Safari Summary (Skills): what a Safari trip caught, reported the moment you come back out. */
    public SafariSettings safari = new SafariSettings();

    public static final class SafariSettings {
        /** Master toggle. Nothing is counted and nothing is drawn while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /**
         * The word a trip is recognised by, matched case-insensitively against the scoreboard's
         * {@code ⏣} zone line and the island name. A setting rather than a constant because Hypixel
         * renames its areas, and a rename must be fixable from the config instead of an update.
         * Empty falls back to {@code SafariTracker.DEFAULT_ZONE}.
         */
        public String zoneName = "Safari";

        /** Print the trip's shard list into chat when it ends. */
        @Shareable(Kind.BOOL)
        public boolean chatSummary = true;

        /** Show the same summary as an on-screen card ({@code safari_summary}). */
        @Shareable(Kind.BOOL)
        public boolean hudSummary = true;

        /** How long that card stays up, in seconds. */
        public int hudSeconds = 20;

        /** Add the Bazaar value of each shard (and the trip total) next to the counts. */
        @Shareable(Kind.BOOL)
        public boolean showValues = true;

        /**
         * Keep the card on screen while still inside, counting along. Off by default: the feature is
         * about the moment you come back, and an always-on panel is a different thing to want.
         */
        @Shareable(Kind.BOOL)
        public boolean liveCounter = false;

        /** A short ping when the summary appears. */
        @Shareable(Kind.BOOL)
        public boolean sound = true;
    }

    /**
     * Floor Drops: boxes the loot lying on the ground in Galatea, Torrhus Canyon and the Critter
     * Safari. Kept beside the Safari settings because it covers the same region, but gated on its
     * own area test - the three areas span two skill themes, so it belongs to neither.
     */
    public FloorDropSettings floorDrops = new FloorDropSettings();

    public static final class FloorDropSettings {

        /**
         * Master toggle. Nothing is scanned, nothing is drawn, nothing is logged and no particle
         * packet is looked at while this is off.
         *
         * <p><b>Off by default</b>, for the reason {@link CritterFinderSettings#enabled} is: two
         * facts underneath the feature are unverified. That a floor drop arrives as a
         * {@code minecraft:item_display} entity comes from the request rather than from a capture,
         * and which particle Hypixel puts around one is not recorded anywhere in this repository.
         * Both are reported in the {@code [SBS][FloorDrop]} log line and on the page, so one trip
         * settles them; until then a highlight the player switched on knowingly beats one that is
         * silently dark.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * Box, label and tracer colour as {@code RRGGBB}.
         *
         * <p>White by default, and not for want of imagination: every other highlight drawn in these
         * three areas is already a hue - the critter box magenta, sparkling cyan, the Gemzie spots
         * green, purple and orange, the preset waypoints blue, the quest bells gold - and the drop
         * itself glows green, so a green box would be lost in the drop's own particles.
         */
        public String colorHex = "FFFFFF";

        /** A line from the crosshair to the drop, so it is found before the box is on screen. */
        @Shareable(Kind.BOOL)
        public boolean showTracer = true;

        /** Append how far away the drop is to its label ("Enchanted Bone §712m"). */
        @Shareable(Kind.BOOL)
        public boolean showDistance = true;

        /**
         * How far away a drop is still looked for and drawn, in blocks. It caps the <i>scan</i> as
         * well as the draw, so the entity sweep never walks further than the render could show.
         */
        public int renderDistance = 48;

        /**
         * How many blocks below a display entity a solid block has to be for it to count as lying on
         * the floor. {@code 0} switches the test off.
         *
         * <p>This is the filter that separates loot from decoration: Hypixel builds scenery out of
         * display entities too, and scenery is mounted on walls, hung in doorways and floated inside
         * builds, while loot lies on the ground. The default of 2 is a judgement and not a
         * measurement - if real drops hover higher than that, raise it or switch it off.
         */
        public int groundWithin = 2;

        /**
         * Also box ordinary dropped items ({@code ItemEntity}), not only display entities.
         *
         * <p><b>Off by default, and it is the escape hatch for the one assumption this feature
         * cannot check from inside the client.</b> The request says a floor drop renders as an item
         * display, and the sweep is written for that; if it turns out to be a dropped item after all,
         * the log line - which counts both kinds in range whatever this is set to - says so, and this
         * switch turns the sweep onto the other class without an update. It stays off by default
         * because in the ordinary case it would box every item any player has dropped nearby.
         */
        @Shareable(Kind.BOOL)
        public boolean includeDroppedItems = false;

        /**
         * Only box a drop that has had one of {@link #particleTypes} beside it in the last few
         * seconds.
         *
         * <p><b>Off by default</b>, because the link is inferred and the type is a guess. The
         * particle packet carries a position and no entity id, so "this drop's particles" is a
         * proximity test rather than a fact the server told us - and requiring an unverified type
         * would make the whole highlight silently dark.
         */
        @Shareable(Kind.BOOL)
        public boolean requireParticles = false;

        /**
         * Which particle types count as a floor drop's, comma-separated, matched against the
         * registry id. A bare name is qualified to {@code minecraft:} automatically.
         *
         * <p>{@code ESTIMATED}: these are the two green particles in the vanilla registry, chosen
         * from the request's "green particles" and confirmed by nothing. The log line tallies every
         * type actually arriving in the gated areas, which is how this field gets the right value.
         */
        public String particleTypes = "happy_villager, composter";

        /**
         * Which Minecraft items a floor drop may be, comma-separated, matched against the item's
         * registry name. A bare name is enough - {@code string} and {@code minecraft:string} both
         * answer - and an empty field boxes a drop whatever it is made of.
         *
         * <p><b>{@code string} is the shipped default, and it is the filter that does the real work
         * against scenery.</b> The loot lying on the ground in these areas is the String item
         * wearing a custom model, and the decoration standing around it is not, so one comparison
         * separates the two before a single block is read.
         *
         * <p>{@code ESTIMATED}: which item the drops are built on came from the maintainer rather
         * than from a capture, so it is a field and not a constant - a drop built on something else
         * is then one edit on this page instead of an update. The {@code [SBS][FloorDrop]} log and
         * the page's own Accuracy row both list the items actually standing around the player, which
         * is what makes the right value readable rather than guessable.
         */
        public String itemTypes = "string";

        /**
         * Only box drops whose item name or SkyBlock id contains one of these, comma-separated.
         * Empty - the default - boxes every drop.
         *
         * <p>The narrowing tool for an area whose decoration also sits on the floor, and the way a
         * player who only cares about one drop stops seeing the rest.
         */
        public String itemFilter = "";
    }

    public static final class ConvenienceSettings {
        /**
         * Keep the cursor where it was instead of letting Minecraft re-centre it every time a
         * screen opens – so clicking through a chain of SkyBlock menus does not move the mouse.
         */
        @Shareable(Kind.BOOL)
        public boolean keepMousePosition = true;

        /** Sprint without holding the key. */
        @Shareable(Kind.BOOL)
        public boolean autoSprint = false;

        /**
         * Keep Hypixel at the top of the multiplayer server list, adding it if it is missing. An
         * entry the player already has is moved rather than replaced, so their own name and address
         * survive.
         */
        @Shareable(Kind.BOOL)
        public boolean pinHypixelServer = true;

        /** A "Hypixel" row in the title screen's menu, under Multiplayer, that connects straight there. */
        @Shareable(Kind.BOOL)
        public boolean hypixelMenuButton = true;

        /** The animated chroma frame around the SkyBlock button in the lobby's Game Menu. */
        @Shareable(Kind.BOOL)
        public boolean highlightGameMenuSkyBlock = true;

        /**
         * Show a beginner-friendly tooltip explaining a setting when it is hovered in the SBS config
         * screen. On by default; the explanation text comes from each setting's own description line.
         */
        @Shareable(Kind.BOOL)
        public boolean settingTooltips = true;

        /**
         * Write every big number SBS shows as {@code 1.4K} / {@code 12.7M} / {@code 3.2B} instead of
         * the full grouped figure. Mod-wide on purpose: it is a reading preference, not a feature, and
         * having half the HUD compact while the tooltips spell it out is the state this replaces.
         * Upper-case suffixes because that is Hypixel's own spelling. See
         * {@link sbs.modid.client.core.util.NumberDisplay}.
         */
        @Shareable(Kind.BOOL)
        public boolean shortenNumbers = true;

        /**
         * Put {@code [SBS]} in front of every chat message the mod sends for you - the {@code !command}
         * answers, the Kuudra call-outs, the carry counter. Mod-wide and on by default: the party reads
         * those lines as if you had typed them, and telling a person's message apart from a client's is
         * worth six characters. Only affects what other players see; messages SBS shows in your own chat
         * carry their own prefix. See {@link sbs.modid.client.core.util.ChatTag}.
         */
        @Shareable(Kind.BOOL)
        public boolean chatMessageTag = true;
    }

    public static final class AhFlipAlertSettings {
        /**
         * Master switch: subscribe to server-found AH flips (long-poll against the SBS flip
         * server; needs a licence token). What a received flip *does* is controlled by the
         * independent output toggles below - popups, chat line, flips window.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * Show each flip as a clickable popup card (position/scale via the GUI editor, element
         * {@code ah_flip_popups}). The cards persist when a container is open, so they can be
         * clicked there - a click opens the auction, the ✕ dismisses the card.
         */
        @Shareable(Kind.BOOL)
        public boolean popupAlerts = true;

        /** How long a popup card stays on screen, in seconds. */
        public int popupSeconds = 120;

        /** Additionally print each flip as a chat line with a click-to-open link. */
        @Shareable(Kind.BOOL)
        public boolean chatAlerts = false;

        /**
         * Movable Flips window (like the Bazaar Best Flips window) listing the current flips,
         * auto-opening over the player inventory and Hypixel's Auction House GUIs.
         */
        @Shareable(Kind.BOOL)
        public boolean windowOverlay = true;

        /**
         * Lower bound of the alert price range in coins (total auction price). The server never
         * sends flips below its own floor (5m), so values below that are raised to it.
         */
        public long minPrice = 5_000_000;

        /** Upper bound of the alert price range in coins; {@code 0} = unlimited. */
        public long maxPrice = 0;

        // ------------------------------------------------------------------
        // Local ranking – live auctions only. No sale history is used, kept or derived here.
        // ------------------------------------------------------------------

        /** Server flip stream or the local one. See {@link SourceChoice}. */
        public SourceChoice flipSource = new SourceChoice();

        /** Server appraisal or the local live-listings view, for Similar Auctions. */
        public SourceChoice similarSource = new SourceChoice();

        /**
         * How far under the next-cheapest listing an auction must sit before the local ranking calls
         * it a flip, in percent.
         *
         * <p>The local engine has one signal — this listing against the rest of its own live market —
         * so this threshold is the whole of its judgement. Set it low and every ordinary bit of price
         * spread becomes an alert; the default is deliberately well clear of that.
         */
        public int localMinDiscountPct = 20;

        /**
         * How many BIN listings of an item must exist before the local ranking will judge it.
         *
         * <p>Two listings are not a market. With only a couple on sale, "cheapest versus next
         * cheapest" is a comparison between two arbitrary asking prices, and the gap between them
         * says nothing about what the item is worth. This is the one guard against that, and it is
         * why the default is not 2.
         */
        public int localMinListings = 4;

        /**
         * Auction-house sale tax taken off the resale price by the local ranking, in percent.
         *
         * <p>A setting rather than a constant because it has <b>not been confirmed in game</b> and
         * Hypixel has changed it before. Left wrong it only ever makes the local ranking optimistic,
         * which is the direction that costs coins.
         */
        public double localAhTaxPct = 1.0;
    }

        public static final class ForgeSettings {
        /** Show the Forge Flips window while the Forge GUI is open. */
        @Shareable(Kind.BOOL)
        public boolean showFlips = false;

        /** Max craft cost in coins (sent to the server, and applied locally; 0 = unlimited). */
        public long budget = 0;

        /** In-world hotkey that opens the Forge Flips screen anywhere; 0 = unbound. */
        @Shareable(Kind.KEYCODE)
        public int openKey = 0;

        /** Server ranking or local ranking for Forge Flips. See {@link SourceChoice}. */
        public SourceChoice flipSource = new SourceChoice();

        // ------------------------------------------------------------------
        // Local forge ranking – used when the licence-backed one cannot be had.
        // The thresholds are settings rather than constants for the same reason the bazaar ones
        // are: they are heuristics over a single snapshot, and a player who reads their market
        // better than we do should be able to say so. See LocalForgeEngine.
        // ------------------------------------------------------------------

        /**
         * Rank forge flips locally when the backend ranking cannot be had — no licence, expired
         * token, offline, backend down. Off leaves the window empty on those paths, as it was
         * before.
         */
        @Shareable(Kind.BOOL)
        public boolean localFallback = true;

        /**
         * Minimum profit per forge run for the local ranking, in coins. Mirrors the backend's
         * {@code min_profit}; entries under it stay visible behind the filtered toggle rather than
         * disappearing, because "too small for me" is a judgement, not a defect.
         */
        public long localMinProfit = 100_000;

        /**
         * Minimum liquidity, expressed in <b>runs per week</b> rather than units: how many runs'
         * worth of the result gets bought, and of the tightest ingredient gets sold, over 7 days.
         *
         * <p>Runs rather than units because a recipe that yields 8 at a time and one that yields 1
         * are not comparable in units, and a unit floor silently favours whichever produces more.
         */
        @Shareable(value = Kind.INT, min = 0, max = 10_000)
        public int localMinWeeklyRuns = 5;

        /** Show the entries the local filters excluded, each labelled with why. */
        @Shareable(Kind.BOOL)
        public boolean localShowFiltered = false;

        // ------------------------------------------------------------------
        // Forge timers - see economy/forge/logic/ForgeTimers.
        // ------------------------------------------------------------------

        /** What {@link #timerMenuTitle} means when blank. UNVERIFIED - the menu is unprobed. */
        public static final String DEFAULT_TIMER_MENU_TITLE = "The Forge";

        /**
         * Track the forge slots from the forge menu. Off by default: the menu's layout and wording
         * are unverified, and root AGENTS.md ships such a feature off until they are.
         */
        @Shareable(Kind.BOOL)
        public boolean timersEnabled = false;

        /** Show the movable Forge Timers card. */
        @Shareable(Kind.BOOL)
        public boolean timerCard = true;

        /** Announce a slot that finishes, also on join for one that finished offline. */
        @Shareable(Kind.BOOL)
        public boolean timerAlert = true;

        /** Which channels carry the finished alert. */
        public int timerChannels = sbs.modid.client.core.alert.AlertChannel.CHAT.bit()
                | sbs.modid.client.core.alert.AlertChannel.SOUND.bit();

        /** After how many hours without a menu read the card says "last seen". */
        @Shareable(value = Kind.INT, min = 1, max = 72)
        public int timerStaleHours = 6;

        /** The start of the forge menu's title; the recipe picker must NOT match it. */
        @Shareable(Kind.TEXT)
        public String timerMenuTitle = DEFAULT_TIMER_MENU_TITLE;
    }

    public static final class WarpMenuSettings {
        /** In-world hotkey that opens the custom warp menu; 0 = unbound. */
        @Shareable(Kind.KEYCODE)
        public int openKey = 0;
    }

    /** Minion Calculator (Economy): rankings by coins / skill XP per slot per day. */
    public MinionCalcSettings minionCalc = new MinionCalcSettings();

    public static final class MinionCalcSettings {
        /** Master toggle for the module (the screen itself is only ever opened on demand). */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /**
         * How often the player empties their minions, in hours. THE assumption every capped figure
         * depends on: production stops when storage fills, so a rate that ignores this is wrong
         * for everyone who does not log in daily.
         */
        public int intervalHours = 24;

        // ---------------------------------------------------------- stopped-minion warning

        /**
         * Master toggle for the stopped-minion warning.
         *
         * <p><b>Off by default, and the page says why.</b> The hologram wording it reads is a
         * hypothesis - nothing resembling it exists anywhere in this repository, and no session has
         * seen a real one. Root {@code AGENTS.md} requires such a feature to ship off. It logs every
         * hologram it finds over a minion, so one trip to the island turns the guess into a fact.
         */
        @Shareable(Kind.BOOL)
        public boolean stoppedWarning = false;

        /** Draw a box and a label over each stopped minion. */
        @Shareable(Kind.BOOL)
        public boolean stoppedWorldMarks = true;

        /**
         * Comma-separated words meaning "storage full". ESTIMATED - see MinionStopRules, which also
         * explains why both defaults have to stay under {@code ShareValues.MAX_TEXT} (64).
         */
        @Shareable(Kind.TEXT)
        public String stoppedFullWords =
                sbs.modid.client.economy.minions.logic.MinionStopRules.DEFAULT_FULL_WORDS;

        /** Comma-separated words meaning "cannot work here". ESTIMATED - see MinionStopRules. */
        @Shareable(Kind.TEXT)
        public String stoppedBlockedWords =
                sbs.modid.client.economy.minions.logic.MinionStopRules.DEFAULT_BLOCKED_WORDS;

        /**
         * Treat any other line above a minion as a stoppage.
         *
         * <p>Off by default: a decorative nametag would otherwise become a minion the player is
         * told is broken. Worth switching on only while hunting the real wording, and the log line
         * is the better tool for that.
         */
        @Shareable(Kind.BOOL)
        public boolean stoppedFlagUnknown = false;

        /** Which channels carry the stopped-minion warning. */
        public int stoppedChannels = sbs.modid.client.core.alert.AlertChannel.CHAT.bit();

        /** Fuel assumed in every minion's fuel slot; empty = none. */
        public String fuelId = "ENCHANTED_LAVA_BUCKET";

        /** Hopper assumed ("BUDGET_HOPPER" / "ENCHANTED_HOPPER"); empty = collect manually. */
        public String hopperId = "";

        /**
         * A minion has TWO upgrade slots; these hold what is in them (modifier ids, empty = none).
         * Everything the fitted upgrade does - speed, compaction, added drops, spreading, engine
         * cooldowns - is applied by the engine, so these are not cosmetic.
         */
        public String upgradeSlot1 = "SUPER_COMPACTOR_3000";
        public String upgradeSlot2 = "";

        /**
         * Superseded by {@link #upgradeSlot1}. Boxed so "absent" (a config written since the
         * change) is distinguishable from "explicitly off", which is what the migration needs.
         */
        @Deprecated
        public Boolean superCompactor;

        /**
         * Fit the best upgrade pair to each minion automatically instead of using the two slots
         * above - what a minion wants in its slots differs per minion, so one fixed pair
         * understates most of the list.
         */
        @Shareable(Kind.BOOL)
        public boolean autoUpgrades = false;

        /** Max coins for one minion's two upgrade items while {@link #autoUpgrades}; 0 = any. */
        public long upgradeBudget = 0;

        /**
         * Mithril Infusion applied to every minion (+10% speed, permanent, anvil-applied). It
         * occupies no slot, which is why it is a toggle here rather than an upgrade choice.
         */
        @Shareable(Kind.BOOL)
        public boolean mithrilInfusion = false;

        /**
         * Free Will applied to every minion (+10% speed, permanent). A gamble in-game - the minion
         * can leave instead - so this assumes the ones you kept.
         */
        @Shareable(Kind.BOOL)
        public boolean freeWill = false;

        /**
         * Extra additive speed % the player claims beyond the fuel: crystals, beacon, infusions,
         * expander/flycatcher - whatever their island actually runs. Applied to every minion.
         */
        public int extraSpeedPct = 0;

        /** Extra skill-XP % (Wisdom, events) applied to every XP figure. */
        public int xpBoostPct = 0;

        /** The skill the XP ranking tab is currently answering for. */
        public String rankSkill = "MINING";

        /** Last selected ranking tab (0 = coins, 1 = XP, 2 = both, 3 = mine). */
        public int tab = 0;

        /**
         * Community Shop minion-slot upgrades bought (0-5). Only used to derive the slot count
         * while the Crafted Minions menu (which states the limit outright) has not been read.
         */
        public int communitySlots = 0;

        /** Optimizer: upfront coins available. */
        public long planBudget = 50_000_000;

        /** Optimizer: slot override; 0 = use the witnessed limit. */
        public int planSlots = 0;

        /** Optimizer objective (0 = coins, 1 = XP in rankSkill, 2 = blend). */
        public int planObjective = 0;

        /** Optimizer: evaluation horizon in days (fuel/beacon worth-it calls). */
        public int planHorizonDays = 30;

        /** Optimizer diversity cap: max copies of one minion type; 0 = unlimited. */
        public int planMaxPerType = 0;

        /** Optimizer: seed the plan with the placed minions the island scan witnessed. */
        @Shareable(Kind.BOOL)
        public boolean planUseOwned = true;

        /** In-world hotkey that opens the calculator screen; 0 = unbound. */
        @Shareable(Kind.KEYCODE)
        public int openKey = 0;
    }

    public static final class HypixelGuiSettings {
        /** How the player's health is rendered on the HUD. */
        @Shareable(value = Kind.ENUM, enumType = HealthBarMode.class)
        public HealthBarMode healthBar = HealthBarMode.CLASSIC_HEARTS;

        /** How (and whether) the hunger bar is replaced by an SBS mana bar. */
        @Shareable(value = Kind.ENUM, enumType = ManaBarMode.class)
        public ManaBarMode manaBar = ManaBarMode.NONE;

        /** Whether the new Vitality pool gets its own SBS bar, drawn above the health bar. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.ui.hud.model.VitalityBarMode.class)
        public sbs.modid.client.ui.hud.model.VitalityBarMode vitalityBar = sbs.modid.client.ui.hud.model.VitalityBarMode.NONE;

        /** How (and whether) the vanilla experience bar is replaced by the SBS XP bar. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.ui.hud.model.XpBarMode.class)
        public sbs.modid.client.ui.hud.model.XpBarMode xpBar = sbs.modid.client.ui.hud.model.XpBarMode.NONE;

        /** Per-segment colour overrides for the SBS bars; empty entries keep the stock colour. */
        public BarColorSettings barColors = new BarColorSettings();

        /** Hide Minecraft's vanilla armor bar (the armor value itself is unaffected). */
        @Shareable(Kind.BOOL)
        public boolean hideArmorBar = false;

        /**
         * Hide the potion-effect icons and text everywhere they are drawn – the gameplay HUD and the
         * panel beside open inventories (effects still apply normally).
         */
        @Shareable(Kind.BOOL)
        public boolean hidePotionEffects = false;

        /** Per-element GUI-editor transforms (position + scale), keyed by {@code HudElement.id()}. */
        public Map<String, HudTransform> hudLayout = new LinkedHashMap<>();

        /** Which side of the open inventory the Recipe Viewer item list panel is rendered on. */
        @Shareable(value = Kind.ENUM, enumType = PanelSide.class)
        public PanelSide recipeViewerPosition = PanelSide.RIGHT;

        /** HUD card showing the active pet (icon, name, level, held item, XP % to next level). */
        @Shareable(Kind.BOOL)
        public boolean showActivePet = false;

        /**
         * A maxed pet's XP bar shimmers with the same travelling rainbow a maxed skill gets in the
         * profile viewer and a maxed enchantment gets in a tooltip.
         *
         * <p>Takes its speed and its colours from {@code theme.chromaSpeed} and
         * {@code theme.chromaPalette} like every other chroma effect, rather than owning either:
         * the phase comes from the wall clock, so one speed keeps every "this is maxed" shimmer on
         * screen moving as a single effect instead of several that drift against each other.
         */
        @Shareable(Kind.BOOL)
        public boolean chromaMaxPetBar = true;

        /**
         * Whether the Active Pet card keeps the colours Hypixel sends the pet's name in.
         *
         * <p>On, and deliberately the one card that reads as different from the rest: the colour is
         * the pet's rarity, which is a fact worth showing, and a card title that changed colour with
         * the pet is not the same kind of inconsistency as two cards drawn in two different frames.
         * Off draws it in the theme's own title colour like every other card.
         */
        @Shareable(Kind.BOOL)
        public boolean petNameServerColors = true;

        /** HUD text with the held item's remaining cooldown ("10.1s"), right of the crosshair. */
        @Shareable(Kind.BOOL)
        public boolean showCooldownHud = false;

        /**
         * Small chip beside the hotbar with the loaded arrow type and how many are left, while a bow
         * is held. Both come from Hypixel's own slot-9 arrow display (its name is the arrow type, its
         * lore carries "Arrows Remaining: N").
         */
        @Shareable(Kind.BOOL)
        public boolean showRemainingArrows = false;

        /** HUD card with ping (ms), estimated server TPS and FPS, top-left by default. */
        @Shareable(Kind.BOOL)
        public boolean showServerStats = false;

        /** In-world hotkey that opens the GUI editor directly; 0 = unbound. */
        @Shareable(Kind.KEYCODE)
        public int editGuiKey = 0;

        /**
         * In-world hotkey for the visible-only GUI editor; 0 = unbound.
         *
         * <p>Its own key rather than a modifier on {@link #editGuiKey}: this is the variant worth
         * pressing mid-game (the one that shows exactly the cards in front of you), and the visible
         * set is whatever the HUD drew in the last frame - so pressing it out in the world is when it
         * is most accurate.
         */
        @Shareable(Kind.KEYCODE)
        public int editGuiVisibleKey = 0;

        /**
         * Show the SBS button in the vanilla pause menu. Its position, size and corner radius are not
         * here: they live with every other pause button in {@code gui/pause_menu.json}, so the button
         * and the pause-menu editor cannot end up disagreeing about where it is.
         */
        @Shareable(Kind.BOOL)
        public boolean pauseButton = true;
    }

    /**
     * The colours of the SBS HUD bars, one {@code RRGGBB} per segment.
     *
     * <p><b>Empty means "leave it alone".</b> Health-red, mana-blue and XP-green are semantic - the
     * theme engine deliberately never repaints them, because a mana bar that follows the accent reads
     * as a broken mana bar. An empty string here keeps exactly that stock colour, so the defaults are
     * still one place ({@code SBSTheme}) and a player who never opens this menu sees no change. Only
     * an entry the player actually filled in wins over it.
     *
     * <p>The alpha is not the player's to set: each segment keeps the opacity its stock colour has
     * (the track is deliberately translucent, the fills solid), so a picked colour cannot make a bar
     * invisible or the track opaque enough to hide the world behind it.
     */
    public static final class BarColorSettings {

        /** Normal health segment; stock is red. */
        @Shareable(Kind.HEX_COLOR)
        public String healthHex = "";

        /** Overheal / absorption segment of the health bar; stock is yellow. */
        @Shareable(Kind.HEX_COLOR)
        public String overhealHex = "";

        /** Normal mana segment; stock is blue. */
        @Shareable(Kind.HEX_COLOR)
        public String manaHex = "";

        /** Overflow-mana segment, left-anchored inside the mana bar; stock is teal. */
        @Shareable(Kind.HEX_COLOR)
        public String overflowManaHex = "";

        /** Vitality (healing pool) bar; stock is rose-pink. */
        @Shareable(Kind.HEX_COLOR)
        public String vitalityHex = "";

        /** XP bar fill; stock is experience green. */
        @Shareable(Kind.HEX_COLOR)
        public String xpHex = "";

        /** The empty track behind every bar; stock follows the theme's background family. */
        @Shareable(Kind.HEX_COLOR)
        public String trackHex = "";

        /** True while every segment is still stock - the reset row uses it to describe itself. */
        public boolean allDefault() {
            return healthHex.isEmpty() && overhealHex.isEmpty() && manaHex.isEmpty()
                    && overflowManaHex.isEmpty() && vitalityHex.isEmpty() && xpHex.isEmpty()
                    && trackHex.isEmpty();
        }

        /** Drops every override, putting all bars back to their stock colours. */
        public void reset() {
            healthHex = "";
            overhealHex = "";
            manaHex = "";
            overflowManaHex = "";
            vitalityHex = "";
            xpHex = "";
            trackHex = "";
        }
    }

    public static final class ItemOverlaySettings {
        /** How the rarity-colored overlay is drawn over items (off / round / square). */
        @Shareable(value = Kind.ENUM, enumType = RarityOverlayMode.class)
        public RarityOverlayMode rarityMode = RarityOverlayMode.OFF;

        /** Rarity overlay opacity in percent (5–80): how visible the color tint is over the item. */
        @Shareable(value = Kind.INT, min = 5, max = 80)
        public int rarityOpacity = 30;

        /** Client-side item renaming via {@code /sbs itemrename <new name>} (held item only). */
        @Shareable(Kind.BOOL)
        public boolean itemRenamer = false;

        /**
         * Persistent client-side renames: item identity (SkyBlock uuid, else its id) → new name.
         * Applied live wherever the name is read, so renames survive restarts and server swaps;
         * {@code /sbs itemoriginalname} removes the held item's entry.
         */
        public Map<String, String> itemRenames = new LinkedHashMap<>();

        /** Animated rainbow (chroma) glint over maxed-enchantment names in item tooltips. */
        @Shareable(Kind.BOOL)
        public boolean chromaMaxedEnchants = false;

        /**
         * @deprecated Legacy per-effect speed, replaced by the single {@code theme.chromaSpeed}.
         *     Boxed so that "absent" is distinguishable from "set to the default": the migration
         *     has to know whether this player ever changed it, and {@code 0} is not a speed anyone
         *     chose. Read only by {@code ConfigManager.migrateChromaSpeed}, which nulls it.
         */
        @Deprecated
        public Integer chromaEnchantSpeed;

        /**
         * Characters one full rainbow is spread over on a normal enchant. This is the single
         * setting that decides whether the effect reads as a gradient or as banding: the old
         * hard-coded 8 stepped the hue 45° per letter, which is a stripe pattern, not a sweep.
         * Range {@link sbs.modid.client.helper.visual.render.ChromaText#MIN_SPREAD}–{@code MAX_SPREAD}.
         */
        @Shareable(value = Kind.INT,
                min = sbs.modid.client.helper.visual.render.ChromaText.MIN_SPREAD,
                max = sbs.modid.client.helper.visual.render.ChromaText.MAX_SPREAD)
        public int chromaEnchantSpread = 24;

        /** Colour saturation of the normal-enchant chroma, in percent (0 = white, 100 = pure hue). */
        @Shareable(Kind.PERCENT)
        public int chromaEnchantSaturation = 85;

        /**
         * Chroma on <b>ultimate</b> enchantments, off by default and deliberately separate from
         * {@link #chromaMaxedEnchants}: Hypixel gives ultimates their own bold light-purple styling
         * and that is how they are recognised at a glance in a wall of lore. Painting them the same
         * animated rainbow as every other maxed enchant throws that recognisability away, so this
         * is opt-in for players who want it anyway.
         */
        @Shareable(Kind.BOOL)
        public boolean chromaUltimateEnchants = false;

        /** @deprecated Legacy per-effect speed - see {@link #chromaEnchantSpeed}. */
        @Deprecated
        public Integer chromaUltimateSpeed;

        /** Characters per full rainbow on an ultimate enchant. */
        @Shareable(value = Kind.INT,
                min = sbs.modid.client.helper.visual.render.ChromaText.MIN_SPREAD,
                max = sbs.modid.client.helper.visual.render.ChromaText.MAX_SPREAD)
        public int chromaUltimateSpread = 24;

        /** Colour saturation of the ultimate chroma, in percent. */
        @Shareable(Kind.PERCENT)
        public int chromaUltimateSaturation = 85;

        /**
         * Continue the gradient <i>down</i> a multi-line enchant block instead of restarting it on
         * every line, so the block reads as one plate scrolling behind the text. The old code
         * offset each line by an arbitrary 30°, which made consecutive lines look unrelated.
         */
        @Shareable(Kind.BOOL)
        public boolean chromaFlowAcrossLines = true;

        /**
         * Animated chroma on maxed skills / slayers / dungeon classes in the profile viewer
         * ({@code /pv}): the "MAX" label and its progress bar shimmer instead of sitting static.
         */
        @Shareable(Kind.BOOL)
        public boolean chromaMaxedSkills = true;

        /** @deprecated Legacy per-effect speed - see {@link #chromaEnchantSpeed}. */
        @Deprecated
        public Integer chromaSkillSpeed;

        /** Hold SHIFT while hovering an item to list enchants it can have but is missing. */
        @Shareable(Kind.BOOL)
        public boolean shiftMissingEnchants = false;

        /**
         * Drop the enchantment lines vanilla prints under the item name on SkyBlock items.
         *
         * <p>Several SkyBlock enchants are real Minecraft ones (Depth Strider, Growth, Protection),
         * so Hypixel puts a genuine enchantments component on the item to make them work and vanilla
         * renders it above the stats - while Hypixel's own lore lists the same enchant again, in the
         * right place. On by default: the duplicate is never what the player wants to see.
         */
        @Shareable(Kind.BOOL)
        public boolean hideVanillaEnchantLines = true;

        /** White draining overlay over items while their (vanilla) cooldown runs. */
        @Shareable(Kind.BOOL)
        public boolean showItemCooldown = false;

        /** Inject a "Lowest BIN" line at the bottom of item tooltips (fetched + cached from the API). */
        @Shareable(Kind.BOOL)
        public boolean showLbin = false;

        /** Inject a "Lowest Bazaar Price" line at the bottom of item tooltips (fetched + cached). */
        @Shareable(Kind.BOOL)
        public boolean showBazaarPrice = false;

        /**
         * Inject an "Est. Value" line: the item's market price <i>plus</i> everything applied onto
         * it – stars, enchantment books, gems, the reforge stone, potato books, runes. Unlike
         * {@link #showLbin} this is what the item in front of you is worth, not what a clean one
         * off the Auction House costs. Silent on items nothing can be priced for.
         */
        @Shareable(Kind.BOOL)
        public boolean showItemValue = true;

        /**
         * One tooltip line naming every gemstone slot of the item: filled (and with what), unlocked
         * but empty, or locked. Only on items that have gemstone slots.
         */
        @Shareable(Kind.BOOL)
        public boolean showGemSlots = true;

        /** Appends what the applied gemstones sell for to the gemstone slot line. */
        @Shareable(Kind.BOOL)
        public boolean showGemSlotValue = true;

        /**
         * A card above the open menu totalling the value of the container's contents and of your
         * own inventory. Works on any container screen, the Ender Chest and Storage backpacks
         * included.
         */
        @Shareable(Kind.BOOL)
        public boolean containerValue = true;

        /**
         * Abbreviated enchant name bottom-left on Enchanted Book icons ("First Strike" → FS,
         * "Sharpness" → Sha; ultimates pink + bold). Single-enchant books only.
         */
        @Shareable(Kind.BOOL)
        public boolean bookAbbreviation = true;

        /** The enchant's level as a number top-right on Enchanted Book icons (gold at max level). */
        @Shareable(Kind.BOOL)
        public boolean bookTier = true;

        /** Item Stack Tips: the pet's level top-left on pet icons ("[Lvl 87]" → 87). */
        @Shareable(Kind.BOOL)
        public boolean stackTipPets = true;

        /** Item Stack Tips: the minion's tier top-left on minion icons, read from the item id. */
        @Shareable(Kind.BOOL)
        public boolean stackTipMinions = true;

        /** Item Stack Tips: the floor on Catacombs passes ("M7" for Master Mode). */
        @Shareable(Kind.BOOL)
        public boolean stackTipDungeonPasses = true;

        /** Item Stack Tips: skill level in the Skills menu. Off: the name shape is unverified. */
        @Shareable(Kind.BOOL)
        public boolean stackTipSkills = false;

        /** Item Stack Tips: collection tier in a collection menu. Off: the name shape is unverified. */
        @Shareable(Kind.BOOL)
        public boolean stackTipCollections = false;

        /** Where Item Stack Tips draw: open menus, the HUD hotbar, or both. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.helper.stacktips.StackTipScope.class)
        public sbs.modid.client.helper.stacktips.StackTipScope stackTipScope =
                sbs.modid.client.helper.stacktips.StackTipScope.BOTH;
    }

    public static final class ChatOptionsSettings {
        /**
         * How the big numbers in incoming chat lines are written: as the server sent them,
         * {@code 12.7M}, or {@code 12,700,000}. Independent of the sidebar's own setting - chat is
         * mostly prose and the sidebar is mostly values, so one appetite does not fit both.
         *
         * <p>Defaults to leaving them alone. Rewriting the server's own words is the kind of thing a
         * player should switch on deliberately.
         */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.util.NumberTextFormat.class)
        public sbs.modid.client.core.util.NumberTextFormat numberFormat =
                sbs.modid.client.core.util.NumberTextFormat.AUTO;

        /** CTRL + left-click a chat message to copy its clean text to the system clipboard. */
        @Shareable(Kind.BOOL)
        public boolean copyChatToClipboard = false;

        /**
         * Right-click chat messages to copy them, with Ctrl / Shift building a multi-message
         * selection like a file manager. Only uses the right button, so it never collides with the
         * left-click behaviours (links, run-command components, the shift-click profile lookup).
         */
        @Shareable(Kind.BOOL)
        public boolean chatSelection = false;

        /**
         * Turn coordinates posted in chat ("x: 187, y: 120, z: -430", or a bare "187 120 -430")
         * into world waypoints. Only messages with a sender are read, so Hypixel's own lines can
         * never create one.
         */
        @Shareable(Kind.BOOL)
        public boolean chatWaypoints = false;

        /** How long a chat waypoint lives, in minutes (0 = until it is reached or cleared). */
        @Shareable(value = Kind.INT, min = 0, max = 120)
        public int chatWaypointMinutes = 10;

        /** SBS IRC chat: receive the community channel and send via /sbs irc <message>. */
        @Shareable(Kind.BOOL)
        public boolean ircEnabled = false;

        /** Who YOU see in IRC: OFF = everyone, WHITELIST = only ircNames, BLACKLIST = hide ircNames. */
        public String ircFilterMode = "OFF";

        /** Player names for the IRC whitelist/blacklist (case-insensitive). */
        public List<String> ircNames = new ArrayList<>();
    }

    /**
     * Pathfinding module preferences. The feature lives with the normal modules, but while it is
     * being tested its settings are exposed under the <b>Developer</b> module and every part of it
     * is gated on {@code DevMode.ACTIVE}, so it is inert for normal players.
     */
    public PathfindingSettings pathfinding = new PathfindingSettings();

    public static final class PathfindingSettings {
        /** Draw a box on every waypoint in the current dimension. */
        @Shareable(Kind.BOOL)
        public boolean renderWaypoints = true;

        /** Compute and draw the route to the nearest waypoint. Off = no pathfinding runs at all. */
        @Shareable(Kind.BOOL)
        public boolean renderPaths = true;

        /**
         * Route through gaps only 1.5 blocks high, which the player passes by sneaking (a floor with
         * a top slab overhead, a bottom slab with a block two above). Such segments are drawn as
         * "sneak".
         */
        @Shareable(Kind.BOOL)
        public boolean allowSneakGaps = true;

        /** Box colour preset of the waypoints. Overridden by {@link #waypointColorHex} when set. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor waypointColor =
                sbs.modid.client.core.render.OverlayColor.BLUE;

        /** Line colour preset of the route. Overridden by {@link #pathColorHex} when set. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor pathColor =
                sbs.modid.client.core.render.OverlayColor.BLUE;

        /**
         * Free-form colours as {@code RRGGBB} hex, for when none of the presets is the right one.
         * Empty (the default) means "use the preset above"; the preset cycler clears these so it
         * always wins back. Kept alongside the presets rather than replacing them so existing
         * configs keep parsing.
         */
        @Shareable(Kind.HEX_COLOR)
        public String waypointColorHex = "";
        @Shareable(Kind.HEX_COLOR)
        public String pathColorHex = "";

        /** The waypoint box colour actually used: the hex when it parses, else the preset. */
        public int waypointRgb() {
            Integer custom = sbs.modid.client.core.render.OverlayColor.parseHex(waypointColorHex);
            return custom != null ? custom : waypointColor.rgb();
        }

        /** The route colour actually used: the hex when it parses, else the preset. */
        public int pathRgb() {
            Integer custom = sbs.modid.client.core.render.OverlayColor.parseHex(pathColorHex);
            return custom != null ? custom : pathColor.rgb();
        }

        /**
         * How far the route may drop in one move. Higher finds shortcuts a big jump height makes
         * safe; lower keeps the route off ledges.
         */
        @Shareable(value = Kind.INT, min = 1, max = 32)
        public int maxFall = 3;

        /**
         * How many sources may have a route at once (multi-route pathfinding). Past it the lowest
         * priority routes are paused, not dropped - see {@code RoutePlanner.admit}.
         */
        @Shareable(value = Kind.INT, min = 1, max = 8)
        public int maxRoutes = 4;

        /** Whether the destination marker of each route draws through terrain. */
        @Shareable(Kind.BOOL)
        public boolean routeMarkersThroughWalls = true;

        /** The Route List HUD card; drawn only while two or more routes exist. */
        @Shareable(Kind.BOOL)
        public boolean routeHudList = true;

        /**
         * Per-source route colour as hex, keyed by {@code RouteSource.id()}. An absent or empty
         * entry falls back to the source's own default, so every source is distinct out of the box.
         */
        public java.util.Map<String, String> routeColorHex = new java.util.HashMap<>();

        /**
         * Sources whose destination marker the player switched off, by {@code RouteSource.id()}. A
         * set of the exceptions rather than a map of booleans, so a source added later defaults to
         * showing its marker without a migration.
         */
        public java.util.Set<String> routeMarkersOff = new java.util.HashSet<>();

        /** The colour a source's route and marker are drawn in. */
        public int routeRgb(sbs.modid.client.core.pathfinding.RouteSource source) {
            String hex = routeColorHex == null ? null : routeColorHex.get(source.id());
            Integer custom = sbs.modid.client.core.render.OverlayColor.parseHex(hex);
            return custom != null ? custom & 0xFFFFFF : source.defaultRgb();
        }

        /** Whether a source's route gets its destination marker. */
        public boolean routeMarker(sbs.modid.client.core.pathfinding.RouteSource source) {
            return routeMarkersOff == null || !routeMarkersOff.contains(source.id());
        }

        /** How the route is drawn: a trail of cubes, a connected line, or both (the default). */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.pathfinding.PathStyle.class)
        public sbs.modid.client.core.pathfinding.PathStyle pathStyle =
                sbs.modid.client.core.pathfinding.PathStyle.CUBES_AND_LINE;

        /** Cube edge length in percent of a block (Cubes style). */
        @Shareable(value = Kind.INT, min = 5, max = 90)
        public int cubeSize = 30;

        /** Draw a cube every N path nodes (Cubes style); 1 = every block. */
        @Shareable(value = Kind.INT, min = 1, max = 8)
        public int cubeSpacing = 2;

        /**
         * How far off the route the player may stray before it is re-searched, in blocks.
         *
         * <p>Measured against the <b>route itself</b>, not against where it started – so walking
         * along it never triggers a re-search, only actually leaving it does. Too small and the
         * route visibly snaps around as you walk, because nobody follows a block grid exactly.
         */
        @Shareable(value = Kind.INT, min = 1, max = 16)
        public int pathTolerance = 3;

        /** GLFW key that drops a waypoint at the player's feet ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int addWaypointKey = 0;

        /** GLFW key that removes the waypoint nearest to the player ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int removeWaypointKey = 0;

        /**
         * Movement rules to route with. {@code AUTO} follows the player (flying vs walking, and
         * their live jump height); {@code WALK} / {@code FLY} force one for testing.
         */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.pathfinding.PathMode.class)
        public sbs.modid.client.core.pathfinding.PathMode pathMode =
                sbs.modid.client.core.pathfinding.PathMode.AUTO;

        /**
         * Climb height per move in blocks, overriding the measured jump height ({@code 0} = auto).
         * Needed only when a jump boost is applied server-side and the client never sees the effect,
         * so it cannot be measured.
         */
        @Shareable(value = Kind.INT, min = 0, max = 32)
        public int jumpHeightOverride = 0;

        /**
         * Let routes use Instant Transmission and Ether Transmission when an Aspect of the End or of
         * the Void is in the inventory. Off routes on foot only, as it always did.
         */
        @Shareable(Kind.BOOL)
        public boolean useTransmission = true;

        /**
         * Node budget for a single search. Higher finds longer / more awkward routes but costs more;
         * when it runs out the best partial path is used instead of nothing.
         */
        @Shareable(value = Kind.INT, min = 1000, max = 200000)
        public int maxNodes = 20_000;

        /** The saved waypoints (all dimensions; filtered per dimension at render time). */
        public List<sbs.modid.client.core.pathfinding.Waypoint> waypoints = new ArrayList<>();
    }

    /**
     * Ping Marker module preferences.
     *
     * <p>Its own block rather than a section of {@link PathfindingSettings}, although a ping is drawn
     * by the waypoint renderer: everything in there is gated on {@code DevMode.ACTIVE} while the
     * pathfinder is being tested, and a ping has to work for every player on every island. There is
     * deliberately no waypoint list here - a ping is never persisted, see
     * {@code sbs.modid.client.core.pathfinding.Waypoint#SOURCE_PING}.
     */
    public PingSettings ping = new PingSettings();

    public static final class PingSettings {
        /** Master toggle. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /**
         * The bind that drops a ping ({@code 0} = unbound), as a {@code core/keybind/Keys} code.
         *
         * <p><b>The one feature hotkey in this mod that ships bound.</b> Middle mouse is what a
         * player already reaches for to ping, and it is what was asked for. It collides with the
         * vanilla Pick Block bind and nothing is done about that on purpose: neither bind is
         * cancelled, both run, and the settings row says so - see {@code PingModule}.
         */
        @Shareable(Kind.KEYCODE)
        public int key = sbs.modid.client.core.keybind.Keys.MOUSE_MIDDLE;

        /**
         * How far the ping ray reaches, in blocks. Nothing to do with the vanilla reach distance:
         * this ray places a marker, it does not touch the world, so its length is a question of how
         * far away a thing is still worth pointing at.
         */
        @Shareable(value = Kind.INT, min = 16, max = 512)
        public int maxDistance = 150;

        /** How long a ping lives, in seconds. The last second of it is the fade. */
        @Shareable(value = Kind.INT, min = 3, max = 120)
        public int lifetimeSeconds = 12;

        /** How many pings may be up at once; placing past this drops the oldest. */
        @Shareable(value = Kind.INT, min = 1, max = 20)
        public int maxPings = 5;

        /** Marker colour as {@code RRGGBB}; empty falls back to {@link #DEFAULT_PING_COLOR}. */
        @Shareable(Kind.HEX_COLOR)
        public String colorHex = "";

        /** Whether the label carries the live distance to the marker. */
        @Shareable(Kind.BOOL)
        public boolean showDistance = true;

        /** Whether a ping sticks to the mob it landed on instead of to the block behind it. */
        @Shareable(Kind.BOOL)
        public boolean followEntities = true;

        /** Whether placing a ping plays a short sound. */
        @Shareable(Kind.BOOL)
        public boolean sound = true;

        /** Volume of that sound, as a percentage. {@code 0} is silent, as is {@link #sound} off. */
        @Shareable(value = Kind.INT, min = 0, max = 100)
        public int soundVolume = 50;

        /** The shipped marker colour: amber, which no other marker set in the mod draws in. */
        public static final int DEFAULT_PING_COLOR = 0xFFAA00;

        /** The colour actually drawn: {@link #colorHex} when it parses, else the shipped amber. */
        public int rgb() {
            Integer custom = sbs.modid.client.core.render.OverlayColor.parseHex(colorHex);
            return custom != null ? custom : DEFAULT_PING_COLOR;
        }
    }

    /** Inventory Buttons module preferences. */
    public InventoryButtonsSettings inventoryButtons = new InventoryButtonsSettings();

    public static final class InventoryButtonsSettings {
        /** Master toggle: draw the buttons over open containers and let them be clicked. */
        public boolean enabled = false;

        /**
         * The user's buttons. Positions are stored relative to the container GUI's top-left, so
         * they follow the inventory across window sizes and GUI scales.
         */
        public List<sbs.modid.client.helper.inventorybuttons.logic.InventoryButton> buttons = new ArrayList<>();
    }

    /** Quest Guide module preferences (skeleton – the feature set is still being defined). */
    public QuestGuideSettings questGuide = new QuestGuideSettings();

    public static final class QuestGuideSettings {
        /** Master toggle for the Quest Guide module. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * <b>Legacy.</b> The quest that was being tracked, globally, before progress became
         * per-profile. Still written for one release, because it is the only thing that says which
         * quest an orphaned {@link #stepIndex} belonged to. The live answer is
         * {@code QuestProgressStore.activeQuest()}.
         */
        public String activeQuest;

        /**
         * <b>Legacy, retired.</b> How far into the active quest the player was, as a position in the
         * step order, shared by every account and every SkyBlock profile.
         *
         * <p><b>Boxed on purpose.</b> {@code null} means "already migrated, or never set"; the
         * primitive it replaced could not tell that apart from a genuine step 0, so the migration
         * would have re-run on every load and dragged a finished player back to the start.
         *
         * <p>{@code QuestProgressStore.migrateLegacyIndex} maps it through the step order it was
         * recorded against, writes the resulting step id into the current profile's record, and
         * clears this. Do not read it anywhere else.
         */
        public Integer stepIndex = 0;

        /** Show the current step's waypoint (and route to it) via the pathfinding module. */
        @Shareable(Kind.BOOL)
        public boolean showWaypoint = true;

        /** Show the HUD overlay with the progress bar and the step checklist. */
        @Shareable(Kind.BOOL)
        public boolean showOverlay = true;
    }

    /** Skill Progress overlay module preferences. */
    public SkillOverlaySettings skillOverlay = new SkillOverlaySettings();

    public static final class SkillOverlaySettings {
        /**
         * Master toggle. The overlay additionally only appears while a skill is actually earning XP,
         * and hides itself again a few seconds after the last gain.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Show the averaged XP per hour. */
        @Shareable(Kind.BOOL)
        public boolean showRate = true;

        /** Show the estimated time to the next level (seconds / minutes / hours as appropriate). */
        @Shareable(Kind.BOOL)
        public boolean showEta = true;

        /** Show how many more actions the next level needs (estimated from the average payout). */
        @Shareable(Kind.BOOL)
        public boolean showActions = true;
    }

    /** Inventory Overlay module preferences (HUD overlay + transparent inventory screen). */
    public InventoryOverlaySettings inventoryOverlay = new InventoryOverlaySettings();

    public static final class InventoryOverlaySettings {
        /**
         * HUD mode: draw the main inventory above the hotbar while playing. Purely visual, so it
         * can never interrupt normal play – position and size come from the GUI editor
         * ({@code HudElement.INVENTORY_OVERLAY}).
         */
        @Shareable(Kind.BOOL)
        public boolean hudOverlay = false;

        /**
         * Screen mode: the player inventory screen drops its blur/dimming backdrop and its panel
         * turns translucent, so the game stays visible while the inventory is operated normally.
         */
        @Shareable(Kind.BOOL)
        public boolean transparentScreen = false;

        /** Opacity in percent (0–100) of both the HUD overlay and the translucent screen panel. */
        @Shareable(value = Kind.INT,
                min = sbs.modid.client.helper.inventory.ui.InventoryOverlay.MIN_OPACITY,
                max = sbs.modid.client.helper.inventory.ui.InventoryOverlay.MAX_OPACITY)
        public int opacity = 70;

        /**
         * Inventory Window: the inventory screen gets a title bar to drag it anywhere and fold it
         * away, remembered across restarts. Off = vanilla placement, untouched.
         */
        @Shareable(Kind.BOOL)
        public boolean windowed = false;

        /**
         * Which screens {@link #windowed} applies to: {@link #SCOPE_INVENTORY} or
         * {@link #SCOPE_ALL_CONTAINERS} (every plain container - chests and every Hypixel menu).
         */
        @Shareable(value = Kind.INT, min = 0, max = 1)
        public int windowScope = SCOPE_INVENTORY;

        public static final int SCOPE_INVENTORY = 0;
        public static final int SCOPE_ALL_CONTAINERS = 1;
    }

    /** Inventory Slot Lock module preferences. */
    public SlotLockSettings slotLock = new SlotLockSettings();

    /** Slot Bindings module: shift-click swaps between slots you tied together. */
    public SlotBindingsSettings slotBindings = new SlotBindingsSettings();

    public static final class SlotBindingsSettings {
        /** Master toggle. Off here, shift-click is vanilla everywhere and no binding is drawn. */
        public boolean enabled = true;

        /**
         * Key (or mouse button) held while clicking slots to tie them together ({@code 0} = unbound).
         * Defaults to {@code B} ("bind"), which no vanilla inventory action uses. Held rather than
         * pressed, exactly like the Slot Lock key, so it can never be mistaken for a normal click.
         */
        public int bindKey = 66;

        /**
         * Only swap while the player's own inventory screen is open, not in chest and server menus.
         *
         * <p>On by default, because shift-clicking your own slots <i>into</i> an open menu is how
         * everything is deposited, sold and sacked on Hypixel — and a bound slot that swaps instead
         * would read as the mod having broken shift-click.
         */
        public boolean onlyPlayerInventory = true;

        /** Play a short sound when a shift-click swaps two bound slots. */
        public boolean swapSound = true;

        /** Every binding the player has made. Empty by default; the feature does nothing until one exists. */
        public List<SlotBinding> bindings = new ArrayList<>();
    }

    /**
     * One binding: a hotbar (or offhand) slot plus the slots whose items swap into it.
     *
     * <p>Both fields are <b>player-inventory container indices</b> — 0-8 hotbar, 9-35 main, 36-39
     * armour, 40 offhand — never menu slot ids, which differ between the inventory screen and a chest.
     *
     * <p>Why an anchor rather than a free set: a swap has to be one action. Vanilla can exchange any
     * slot with a hotbar or offhand slot in a single container input (it is what pressing 1-9 over a
     * slot does), and nothing else. A binding of two arbitrary slots would need three clicks
     * synthesized on the player's behalf, which this mod does not do.
     */
    public static final class SlotBinding {
        /** The hotbar slot (0-8) or offhand (40) every member swaps with. {@code -1} = unset. */
        public int anchor = -1;

        /** The bound partner slots, in the order they were bound. */
        public List<Integer> members = new ArrayList<>();
    }

    /** Full Inventory Warning module: say something before the next drop has nowhere to go. */
    public FullInventorySettings fullInventory = new FullInventorySettings();

    public static final class FullInventorySettings {

        /**
         * Master toggle. Nothing is counted while this is off - the watcher returns on a field read.
         *
         * <p>On by default, unlike most new detection: there is no hypothesis here to be wrong
         * about. An empty {@code ItemStack} is vanilla, so this feature cannot go silently dark for
         * a reworded message or a renamed id, which is what the off-by-default rule in root
         * {@code AGENTS.md} protects against.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /**
         * Warn at or below this many free slots. {@code 0} = only when completely full.
         *
         * <p>Ceiling 35 rather than 36: the SkyBlock Menu occupies a hotbar slot, so a profile
         * carrying it can never have all 36 free and a threshold of 36 would warn permanently.
         */
        @Shareable(value = Kind.INT, min = 0, max = 35)
        public int threshold = 0;

        /** Shortest gap between two warnings, in seconds. */
        @Shareable(value = Kind.INT, min = 5, max = 300)
        public int cooldownSeconds = 30;

        /** Stay quiet in the Catacombs, where an inventory fills and empties by design. */
        @Shareable(Kind.BOOL)
        public boolean offInDungeons = true;

        /** Show the movable "Free slots: N" chip while at or under {@link #threshold}. */
        @Shareable(Kind.BOOL)
        public boolean showHud = false;

        /** Which channels carry the warning. */
        public int notifyChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;
    }

    /** Accessory Bag module: highlight duplicate accessories. */
    public AccessoryBagSettings accessoryBag = new AccessoryBagSettings();

    public static final class AccessoryBagSettings {
        /** Master toggle for the accessory-bag highlights. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Highlight accessories that appear more than once in the open bag page. */
        @Shareable(Kind.BOOL)
        public boolean highlightDuplicates = true;

        /** Box / fill colour for a duplicate accessory. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor duplicateColor =
                sbs.modid.client.core.render.OverlayColor.RED;

        /**
         * Whether the Accessory Bag reads its pages into the missing-accessory index. Separate from
         * {@link #enabled} on purpose: indexing is what makes the missing list work, and tying it to
         * the highlight toggle would make turning off a cosmetic box silently blind the feature.
         */
        @Shareable(Kind.BOOL)
        public boolean trackOwned = true;

        /** Draw the "Missing" button on the Accessory Bag screen. */
        @Shareable(Kind.BOOL)
        public boolean bagButton = true;

        /** Persisted {@code AccessoryProgress.Filter} the missing screen opens on. */
        public String missingFilter = "MISSING";

        /** Persisted {@code AccessoryProgress.Sort}. */
        public String missingSort = "POWER";

        /**
         * Include Rift-only accessories. Off by default: they are a separate progression that cannot
         * leave the Rift, and mixing them in makes the main list look permanently unfinished.
         */
        @Shareable(Kind.BOOL)
        public boolean includeRift = false;

        /**
         * Show lower tiers that an owned higher tier has replaced. Off by default - they are not
         * gaps - but available, because it is also how the upgrade ladders can be spot-checked.
         */
        @Shareable(Kind.BOOL)
        public boolean includeSuperseded = false;

        /** GLFW key that opens the missing-accessory screen ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int openKey = 0;
    }

    public static final class SlotLockSettings {
        /** Master toggle. With this off nothing is locked and no inventory logic is touched at all. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /**
         * GLFW key held while left-clicking a slot to lock / unlock it ({@code 0} = unbound).
         * Defaults to {@code L} ("lock"), which no vanilla inventory action uses.
         */
        @Shareable(Kind.KEYCODE)
        public int lockKey = 76;

        /**
         * GLFW key held to MOVE / swap locked items despite the lock ({@code 0} = unbound). Dropping
         * and shift-selling stay blocked; only a normal pickup/place is let through, so a locked set
         * can be reorganised without unlocking it. Defaults to Left Alt.
         */
        @Shareable(Kind.KEYCODE)
        public int swapKey = 342;

        /**
         * The locked <b>player-inventory</b> indices (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand).
         * Indices rather than item ids, so the lock belongs to the slot and survives the item in it
         * changing. Persisted here, which is what makes locks outlive closing the inventory and
         * restarting the world.
         */
        public Set<Integer> lockedSlots = new LinkedHashSet<>();

        /** Play a short sound when an action on a locked slot is refused. */
        @Shareable(Kind.BOOL)
        public boolean denySound = true;

        /** Show an action-bar message when an action on a locked slot is refused. */
        @Shareable(Kind.BOOL)
        public boolean denyMessage = true;

        /**
         * Block dropping a locked hotbar slot's item with the in-world drop key (Minecraft's Q, or
         * whatever it is rebound to). On by default; turn off to allow a locked hotbar item to be
         * thrown while playing (the in-menu locks stay enforced regardless).
         */
        @Shareable(Kind.BOOL)
        public boolean blockHotbarDrop = true;

        // --- Rarity drop protection: rare items can't be dropped even from unlocked slots ---
        /** Refuse dropping items at/above {@link #rarityProtectMin} (from any slot, locked or not). */
        @Shareable(Kind.BOOL)
        public boolean rarityProtect = true;

        /** Threshold as a {@link sbs.modid.client.core.item.Rarity} name; that rarity and above protect. */
        public String rarityProtectMin = "EPIC";

        /** true = pressing drop 3× within 3s still drops; false = protected drops are refused flat. */
        @Shareable(Kind.BOOL)
        public boolean rarityTriplePress = true;
    }

    /** Item Protection module preferences (per-item and per-item-type protection). */
    public ItemProtectionSettings itemProtection = new ItemProtectionSettings();

    /**
     * Item Protection: items the player marked cannot be dropped, sold, salvaged, sacked or
     * consumed by a menu. The marked items themselves are NOT here - they live in the global
     * {@code data/protected_items.json}, because an item travels between profiles and the config
     * does not. See {@code helper/itemprotection/logic/ProtectedItems}.
     */
    public static final class ItemProtectionSettings {

        /**
         * Master toggle. Off, nothing is drawn, no click is inspected and no file is read.
         *
         * <p><b>Off by default</b>, deliberately: most of the menu titles the guard classifies on
         * have not yet been seen in game by this build (see {@code DestructiveScreens}), so the
         * player switches it on knowing it is new.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * Key or mouse button pressed while hovering a slot to protect / unprotect that item
         * ({@code 0} = unbound, which is the default - it must not collide with anything).
         */
        @Shareable(Kind.KEYCODE)
        public int toggleKey = 0;

        /** Draw a marker over protected slots. */
        @Shareable(Kind.BOOL)
        public boolean indicator = true;

        /** Marker style: a coloured border, a small shield, or both. */
        @Shareable(value = Kind.ENUM,
                enumType = sbs.modid.client.helper.itemprotection.model.IndicatorStyle.class)
        public sbs.modid.client.helper.itemprotection.model.IndicatorStyle indicatorStyle =
                sbs.modid.client.helper.itemprotection.model.IndicatorStyle.BOTH;

        /** Marker colour as "#RRGGBB"; empty falls back to {@link #DEFAULT_INDICATOR_COLOR}. */
        public String indicatorColorHex = "";

        /** Amber - used by no other slot decoration, so a protected slot is unambiguous. */
        public static final int DEFAULT_INDICATOR_COLOR = 0xFFC83F;

        /** Add a "Protected" line to a protected item's tooltip. */
        @Shareable(Kind.BOOL)
        public boolean tooltipLine = true;

        /**
         * Hard block refuses every time; confirm refuses once and lets an identical repeat through.
         * Auction listings and unrecognised menus always confirm regardless - see
         * {@code ProtectionCategory#confirmOnly}.
         */
        @Shareable(value = Kind.ENUM,
                enumType = sbs.modid.client.helper.itemprotection.model.ProtectionMode.class)
        public sbs.modid.client.helper.itemprotection.model.ProtectionMode mode =
                sbs.modid.client.helper.itemprotection.model.ProtectionMode.CONFIRM;

        /** How long a confirmation stays open, in seconds. */
        @Shareable(value = Kind.INT, min = 1, max = 15)
        public int confirmSeconds = 5;

        /** Play a short sound when an action on a protected item is refused. */
        @Shareable(Kind.BOOL)
        public boolean denySound = true;

        // --- per-category toggles: which destinations the protection actually guards ---

        /** The drop key, Ctrl+drop, and dragging the item out of the window. */
        @Shareable(Kind.BOOL)
        public boolean blockDrop = true;

        /**
         * NPC sell menus, the Bazaar's "Sell Instantly" and "Create Sell Offer", sell confirmation
         * screens, and "sell all" / "Sell Inventory Now" buttons.
         */
        @Shareable(Kind.BOOL)
        public boolean blockSell = true;

        /** Reforge-anvil salvage, dungeon salvage, bulk salvage. */
        @Shareable(Kind.BOOL)
        public boolean blockSalvage = true;

        /** Sacks, Sack of Sacks, Personal Deletor. */
        @Shareable(Kind.BOOL)
        public boolean blockSack = true;

        /** Creating an auction. Confirms rather than blocks - people list protected items on purpose. */
        @Shareable(Kind.BOOL)
        public boolean blockAuction = true;

        /** Forge, Kat, attribute fusion, anvil, craft menus, museum donation. */
        @Shareable(Kind.BOOL)
        public boolean blockConsume = true;

        /**
         * The fallback: a protected item leaving the inventory in a menu SBS does not recognise as
         * safe asks for confirmation. Turning this off makes an unrecognised menu behave as if it
         * were safe, which is the one setting here that can lose an item.
         */
        @Shareable(Kind.BOOL)
        public boolean confirmUnknownMenus = true;

        /**
         * Write one {@code [SBS][Protect]} line per container click to {@code latest.log}: the
         * screen title raw and stripped, the container id, the menu class, the clicked slot and
         * which half of the screen it is in, the click type, the stack's resolved
         * {@code ExtraAttributes} uuid and id, how the screen was classified, whether the clicked
         * slot reads as a control that consumes the inventory, and whether the guard refused.
         *
         * <p>This is the module's tuning switch, and it exists because every menu shape this feature
         * classifies is something Hypixel can reword without notice. When protection does not fire
         * where it should, this log says which of the five things went wrong - the screen was not
         * recognised, the slot was not examined, the pressed control was not recognised, the
         * identity did not resolve, or the category is switched off - instead of leaving it to be
         * guessed at. Logs regardless of whether the feature would have acted, so "nothing happened"
         * is still a line.
         */
        @Shareable(Kind.BOOL)
        public boolean debugLog = false;
    }

    /** Ether Warp module preferences (target highlight + zoom, independently toggleable). */
    public EtherWarpSettings etherWarp = new EtherWarpSettings();

    public static final class EtherWarpSettings {
        /**
         * Feature 1 – highlight the etherwarp target block in real time while sneaking with an
         * etherwarp-capable AOTE/AOTV. Entirely independent of {@link #zoom}.
         */
        @Shareable(Kind.BOOL)
        public boolean targetHighlight = false;

        /** Outline colour of the target box. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor highlightColor =
                sbs.modid.client.core.render.OverlayColor.BLUE;

        /** Outline opacity in percent (10–100): how solid the box lines are. */
        @Shareable(value = Kind.INT, min = 10, max = 100)
        public int highlightOpacity = 80;

        /** Outline thickness in pixels (1–5). */
        @Shareable(value = Kind.INT, min = 1, max = 5)
        public int lineWidth = 2;

        /**
         * Tint the box red when the target is blocked (no room to stand), instead of always using
         * {@link #highlightColor}. Makes an impossible warp obvious before pressing the button.
         */
        @Shareable(Kind.BOOL)
        public boolean markInvalid = true;

        /**
         * Feature 2 – zoom the camera in while sneaking with an etherwarp-capable AOTE/AOTV, for
         * more precise aiming. Independent of {@link #targetHighlight}; ends the instant sneaking
         * stops or the held item changes.
         */
        @Shareable(Kind.BOOL)
        public boolean zoom = false;

        /**
         * How far the zoom goes, in percent: 10 = barely closer, 90 = far in. Replaces the old
         * {@code zoomLevel}, which was the inverse (a field-of-view percentage), so an old config
         * falls back to this default rather than silently meaning the opposite.
         */
        @Shareable(value = Kind.INT,
                min = sbs.modid.client.helper.visual.model.ZoomStrength.MIN,
                max = sbs.modid.client.helper.visual.model.ZoomStrength.MAX)
        public int zoomStrength = 70;

        /**
         * Feature 3 – lower the mouse sensitivity while sneaking with an etherwarp-capable
         * AOTE/AOTV, for steadier aim at long range. Independent of the other two; ends the instant
         * sneaking stops or the held item changes.
         */
        @Shareable(Kind.BOOL)
        public boolean reduceSensitivity = false;

        /**
         * The sensitivity to use while aiming, as a percentage of the player's normal setting
         * (100 = unchanged, 25 = a quarter as fast). Applied live – the saved option is never
         * written to, so it can never be left reduced.
         */
        @Shareable(value = Kind.INT,
                min = sbs.modid.client.helper.etherwarp.EtherWarpSensitivity.MIN_PERCENT,
                max = sbs.modid.client.helper.etherwarp.EtherWarpSensitivity.MAX_PERCENT)
        public int sensitivityPercent = 50;

        /** Toggle key for the sensitivity reduction (GLFW keycode; -1 = unbound). */
        @Shareable(Kind.KEYCODE)
        public int sensitivityKey = -1;
    }

    /** Fullbright (self-registered module, see {@code sbs.modid.client.helper.visual.FullbrightModule}). */
    public FullbrightSettings fullbright = new FullbrightSettings();

    public static final class FullbrightSettings {
        /** Whether the world is rendered fully lit. Toggled by the settings row or the key. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Toggle key (GLFW keycode; -1 = unbound). */
        @Shareable(Kind.KEYCODE)
        public int toggleKey = -1;
    }

    /** Performance (self-registered module, see {@code sbs.modid.client.helper.performance.PerformanceModule}). */
    public PerformanceSettings performance = new PerformanceSettings();

    /** Hide Nearby Players: other players close to you are not drawn (rendering only). */
    public HidePlayersSettings hidePlayers = new HidePlayersSettings();

    public static final class HidePlayersSettings {
        /** Master toggle for the card. Off by default: hiding people is something you ask for. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Flipped by the toggle key, so hiding can pause without switching the card off. */
        @Shareable(Kind.BOOL)
        public boolean hiding = true;

        /** Hide every other player, not only those within {@link #radius}. */
        @Shareable(Kind.BOOL)
        public boolean everywhere = false;

        /** Blocks around you inside which players are hidden. */
        @Shareable(value = Kind.INT, min = 1, max = 64)
        public int radius = 5;

        @Shareable(Kind.BOOL)
        public boolean keepParty = true;

        @Shareable(Kind.BOOL)
        public boolean keepDungeonTeam = true;

        /** Players tagged Trusted in Player Notes stay visible. */
        @Shareable(Kind.BOOL)
        public boolean keepTrusted = true;

        /** Also hide in dungeons, Kuudra and while your slayer boss is up (off: teammates matter). */
        @Shareable(Kind.BOOL)
        public boolean hideInCombat = false;

        /** The first mode: hide players within {@link #radius} of you. */
        @Shareable(Kind.BOOL)
        public boolean nearMe = true;

        /** The second mode: hide players crowding an NPC you are within 10 blocks of. */
        @Shareable(Kind.BOOL)
        public boolean nearNpcs = false;

        /** Blocks around an NPC inside which players are hidden (the NPC mode). */
        @Shareable(value = Kind.INT, min = 1, max = 16)
        public int npcRadius = 3;

        /** "Hidden player in the way - step aside" when the crosshair points through one at an NPC. */
        @Shareable(Kind.BOOL)
        public boolean npcHint = true;

        /** Toggles {@link #hiding}; 0 = unbound. */
        @Shareable(Kind.KEYCODE)
        public int toggleKey = 0;
    }

    public static final class PerformanceSettings {
        /** Master switch for the armor-stand render / tick culling. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Skip armor stands with nothing visible at all (invisible, no shown name, no equipment). */
        @Shareable(Kind.BOOL)
        public boolean hideChromeStands = true;

        /** Armor stands farther than this many blocks are not rendered (0 = vanilla, no limit). */
        public int standRenderDistance = 48;

        /** Also skip the client tick of armor stands that are not rendered anyway. */
        @Shareable(Kind.BOOL)
        public boolean tickCulling = true;

        /**
         * The mod-wide budget: when SBS's own tick work goes over {@link #tickBudgetMs}, work marked
         * deferrable runs less often until it is back under. Measuring is two clock reads per
         * section; off = nothing is measured or shed.
         */
        @Shareable(Kind.BOOL)
        public boolean sbsBudget = true;

        /** KPI target: SBS work per frame, average, in ms. */
        public double frameTargetMs = 1.5;

        /** KPI target: SBS work per frame, 95th percentile, in ms. */
        public double frameP95TargetMs = 3.0;

        /** KPI target: any single feature per frame, average, in ms. */
        public double featureTargetMs = 0.3;

        /** KPI target and budget: SBS work per client tick, in ms. */
        public double tickBudgetMs = 1.0;

        /** KPI target: sustained allocation of one feature, in MB per second. */
        public double allocTargetMbPerSec = 1.0;

        /** Dev: an artificial sleep inside SBS's tick work, in ms, to check the budget sheds. 0 = off. */
        public int simulateSlowMs = 0;
    }

    /** Farming helpers. */
    public FarmingSettings farming = new FarmingSettings();

    public static final class FarmingSettings {
        /** Jacob's Contest card: crop, time left, collected, bracket - only while a contest runs. */
        @Shareable(Kind.BOOL)
        public boolean contestCard = true;

        /** Show the rate and the "≈ N at end" estimate on the contest card. */
        @Shareable(Kind.BOOL)
        public boolean contestProjection = true;

        /** Alert when your bracket goes up during a contest. */
        @Shareable(Kind.BOOL)
        public boolean contestBracketAlert = false;

        /** Rare farming drops: counted per session and per profile, and announced. */
        @Shareable(Kind.BOOL)
        public boolean farmDrops = true;

        /** The Farm Drops card on farming islands. */
        @Shareable(Kind.BOOL)
        public boolean farmDropsCard = true;

        /** Alert channels for a RARE farming drop. Chat by default. */
        public int farmDropRareChannels = sbs.modid.client.core.alert.AlertChannel.CHAT.bit();

        /** Alert channels for VERY RARE and rarer farming drops. Title + sound by default. */
        public int farmDropVeryRareChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;

        /** Alert once when under a minute of the contest is left. */
        @Shareable(Kind.BOOL)
        public boolean contestLastMinuteAlert = false;

        /** Farming Speed card: crop blocks per second, average, peak, usual speed. */
        @Shareable(Kind.BOOL)
        public boolean speedCard = true;

        /** Warn when the 5 s speed stays under {@link #speedWarnPercent} of your usual for 3 s. */
        @Shareable(Kind.BOOL)
        public boolean speedWarning = false;

        /** The drop warning's threshold, percent of your usual speed. */
        @Shareable(value = Kind.INT, min = 10, max = 95)
        public int speedWarnPercent = 70;

        /** The 60 s graph under the Farming Speed card. */
        @Shareable(Kind.BOOL)
        public boolean speedGraph = true;

        /** Channels for the drop warning ({@code AlertChannels} bit mask). */
        @Shareable(value = Kind.INT, min = 0, max = 63)
        public int speedAlertChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;

        /** Log every block you break with what Farming Speed counted it as. Local log only. */
        @Shareable(Kind.BOOL)
        public boolean speedLogBlocks = false;

        /**
         * Lane End Warning: alert a few blocks before an edge of a lane area you marked yourself.
         * Manual only - there is no automatic lane detection, and no mode field (an old config that
         * still carries one loads fine: Gson ignores fields the class no longer declares).
         */
        @Shareable(Kind.BOOL)
        public boolean laneEndWarning = false;

        /** Warn with this many blocks of lane left. */
        @Shareable(value = Kind.INT, min = 1, max = 20)
        public int laneEndBlocks = 5;

        /** ...or this many tenths of a second left at your walking speed, whichever comes first. */
        @Shareable(value = Kind.INT, min = 0, max = 50)
        public int laneEndTenths = 10;

        /** Channels for the lane end warning ({@code AlertChannels} bit mask). Sound only by default. */
        @Shareable(value = Kind.INT, min = 0, max = 63)
        public int laneEndChannels = sbs.modid.client.core.alert.AlertChannel.SOUND.bit();

        /**
         * Key that marks a lane's start at your feet (0 = unbound). Named for the pre-farm "corner 1"
         * so existing bindings carry over; renaming the field would unbind every player's key.
         */
        public int laneCorner1Key = 0;
        /** Key that marks the lane's end and adds it to the current farm (0 = unbound). */
        public int laneCorner2Key = 0;
        /** Blocks across a newly marked lane that still count as "in it", centred on where you walked. */
        @Shareable(value = Kind.INT, min = 1, max = 5)
        public int laneWidth = 3;
        /** Draw the marked lanes on the Garden while a farming tool is held (always while marking). */
        @Shareable(Kind.BOOL)
        public boolean laneAreaPreview = true;

        /** Channels for both contest alerts ({@code AlertChannels} bit mask). */
        @Shareable(value = Kind.INT, min = 0, max = 63)
        public int contestAlertChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;

        /**
         * Log the sidebar under {@code [SBS][Jacob]} every 10 s while a contest runs - the probe the
         * card's sidebar patterns are waiting on. Local log only, nothing is sent anywhere.
         */
        @Shareable(Kind.BOOL)
        public boolean contestProbe = true;

        /**
         * Run the farming features only on farming islands
         * ({@link sbs.modid.client.skills.SkillIslands#FARMING_ISLANDS}). These features follow the
         * tool in your hand rather than the ground under your feet, so without this the milestone card
         * counts the pumpkins you break in a dungeon.
         */
        @Shareable(Kind.BOOL)
        public boolean islandLock = true;

        /** Toggle key for Mouse Lock (GLFW keycode; -1 = unbound). */
        @Shareable(Kind.KEYCODE)
        public int mouseLockKey = -1;

        /** Automatically lower the mouse sensitivity while a farming tool is held. */
        @Shareable(Kind.BOOL)
        public boolean reduceSensitivity = false;

        /**
         * The sensitivity to use while farming, as a percentage of the player's normal setting
         * (100 = unchanged, 25 = a quarter as fast). Applied live – the saved option is never
         * written to, so it can never be left reduced.
         */
        @Shareable(value = Kind.INT,
                min = sbs.modid.client.skills.farming.logic.FarmingSensitivity.MIN_PERCENT,
                max = sbs.modid.client.skills.farming.logic.FarmingSensitivity.MAX_PERCENT)
        public int sensitivityPercent = 40;

        /** Crop Milestone card: tier, progress, crops per minute and the ETA to the next tier. */
        @Shareable(Kind.BOOL)
        public boolean cropMilestone = true;

        /** Show the estimated time to the next milestone (needs a counter / Cultivating tool). */
        @Shareable(Kind.BOOL)
        public boolean milestoneEta = true;

        /** Add a coins-per-hour row to the milestone card, priced at the Bazaar instasell. */
        @Shareable(Kind.BOOL)
        public boolean milestoneProfit = true;

        /** Farming Fortune card: the effective fortune for the crop the held tool is for. */
        @Shareable(Kind.BOOL)
        public boolean farmingFortune = true;

        /** Hoe Level card: the held tool's level and its progress to the next one. */
        @Shareable(Kind.BOOL)
        public boolean hoeLevels = true;

        /** Keep showing the level card once the tool is past its level cap (overflow levels). */
        @Shareable(Kind.BOOL)
        public boolean hoeOverflow = true;

        /** Silence the level-up jingle that fires when a farming tool gains a level. */
        @Shareable(Kind.BOOL)
        public boolean muteHoeSounds = false;

        /** Crop-angle helper on the Squeaky Mousemat's sign: the panel, and saving what you type. */
        @Shareable(Kind.BOOL)
        public boolean mousematHelper = true;

        /**
         * Also type the saved angle into the sign as it opens. Only ever fills an angle that was
         * actually saved for that crop – never a default – so it cannot aim you somewhere you never
         * asked to look.
         */
        @Shareable(Kind.BOOL)
        public boolean mousematAutoFill = true;

        /** Key that saves where you are looking right now as the held crop's angle (-1 = unbound). */
        @Shareable(Kind.KEYCODE)
        public int mousematCaptureKey = -1;

        /**
         * The saved angles, keyed by {@link sbs.modid.client.skills.farming.model.CropType} name to
         * a {@code [yaw, pitch]} pair in degrees. Kept out of the profile stores on purpose: an angle
         * is a property of your farm layout and mouse, not of the profile you happen to be on.
         */
        public Map<String, double[]> mousematAngles = new LinkedHashMap<>();
    }

    /** Garden island helpers: level, pests and the coin values its menus leave out. */
    public GardenSettings garden = new GardenSettings();

    public static final class GardenSettings {
        /** Garden Level card, fed by the Garden tab widget. */
        @Shareable(Kind.BOOL)
        public boolean gardenLevel = true;

        /** Keep counting levels past the last real one. */
        @Shareable(Kind.BOOL)
        public boolean gardenLevelOverflow = true;

        /** Pest card: pests alive, infested plots, last spawn and the spawn cooldown. */
        @Shareable(Kind.BOOL)
        public boolean pestTimer = true;

        /** Big on-screen title when a pest spawns (and when its cooldown runs out). */
        @Shareable(Kind.BOOL)
        public boolean pestSpawnTitle = true;

        /** Sound on a pest spawn. */
        @Shareable(Kind.BOOL)
        public boolean pestSpawnSound = true;

        /** In-game warning (title + ping) shortly before pests are eligible to spawn again. */
        @Shareable(Kind.BOOL)
        public boolean pestCooldownWarning = false;

        /**
         * Which channels the "pests can spawn again" alert uses (an {@code AlertChannels} mask).
         * Independent of the spawn alert, since the two answer different questions ("deal with this
         * now" vs "you may get one again").
         */
        public int pestReadyChannels = 0;

        /**
         * @deprecated the old desktop-notification toggle for the ready alert. Only read by the
         *     migration into {@link #pestReadyChannels}, then nulled so the next write drops it.
         *     Boxed so "absent" is distinguishable from "was switched off".
         */
        @Deprecated
        public Boolean pestReadyNotification;

        /** How many seconds before the cooldown ends the warning / ready notification fires. */
        public int pestWarnBeforeSeconds = 0;

        /** The small countdown card, for players who want that one number without the pest card. */
        @Shareable(Kind.BOOL)
        public boolean pestCooldownHud = false;

        /**
         * Use the custom cooldown values below instead of the 5-minute baseline.
         *
         * <p>Hypixel publishes the real remaining cooldown in the Pests tab widget, and that is what
         * the timer uses whenever it is there. These values only feed the estimate for when it is
         * not - they cannot be better than a guess, because the true number depends on worn
         * Pesthunter gear, Squeaky reforges and the Sprayonator's repellent, none of which the
         * client can see.
         */
        @Shareable(Kind.BOOL)
        public boolean pestCustomCooldown = false;

        /** The custom pest cooldown, in seconds. Hypixel's base is 300 (5 minutes). */
        public int pestCooldownSeconds = 300;

        /**
         * The custom pest cooldown while Finnegan's "Pest Eradicator" perk is active, in seconds.
         * The perk is -20% of the base, so 240 s, and no gear can push the real floor below 75 s.
         */
        public int pestCooldownFinnegan = 240;

        /**
         * With Custom Cooldown on: also show Hypixel's own tab-list number, small, under the custom
         * countdown whenever the two differ by more than 5 s.
         */
        @Shareable(Kind.BOOL)
        public boolean pestShowHypixelCooldown = true;

        /** Whether the Finnegan perk is active - picks which custom cooldown applies. */
        @Shareable(Kind.BOOL)
        public boolean pestFinnegan = false;

        /** Outline the infested plots in the world. */
        @Shareable(Kind.BOOL)
        public boolean pestPlotHighlight = false;

        /**
         * Tracer line from the crosshair to the centre of every infested plot.
         *
         * <p>Its own toggle, separate from the pest tracers, and default OFF: the highlight now
         * shows every infested plot at once, and half a dozen lines converging on the crosshair
         * drown out the boxes they point at.
         */
        @Shareable(Kind.BOOL)
        public boolean pestPlotTracer = false;

        /**
         * How far up the plot outline reaches, in blocks above the Garden floor.
         *
         * <p>Tall enough by default to read as the wall it is from across the island rather than as
         * a line on the floor. A setting because the Garden's build height is not something the
         * client can ask the server for.
         *
         * <p>Replaces {@link #pestPlotHeight}, which measured from the player instead of the ground
         * - see {@code ConfigManager.migrateGardenPlotHeight}.
         */
        public int pestPlotWallHeight = 64;

        /**
         * @deprecated pre-ground-anchor outline height, measured from the player's feet. Only read
         *     by the migration into {@link #pestPlotWallHeight}, then nulled so the next write drops
         *     it from config.json. Boxed so "absent" is distinguishable from "set to 0".
         */
        @Deprecated
        public Integer pestPlotHeight;

        /** Fill the plot's sides with a translucent tint, not just draw its edges. */
        @Shareable(Kind.BOOL)
        public boolean pestPlotWalls = true;

        /** Tint strength of those walls, 0-100. Kept low - the wall has to be seen through. */
        public int pestPlotWallOpacity = 18;

        /** Box colour for infested plots. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor pestPlotColor =
                sbs.modid.client.core.render.OverlayColor.RED;

        /** Box the actual pest entities in the world (only while they are on screen). */
        @Shareable(Kind.BOOL)
        public boolean pestHighlight = false;
        /**
         * @deprecated Legacy key from when this was called "ESP". Boxed so that "absent"
         *     is distinguishable from "false" - a rename must not silently reset a setting
         *     the player switched on. Read once by {@code ConfigManager.migrateHighlightNames}
         *     and nulled; remove once no config in the wild still carries it.
         */
        @Deprecated
        public Boolean pestEsp;


        /** Tracer line from the crosshair to each pest (and to the pest-carrying plot). */
        @Shareable(Kind.BOOL)
        public boolean pestHighlightTracer = false;
        /**
         * @deprecated Legacy key from when this was called "ESP". Boxed so that "absent"
         *     is distinguishable from "false" - a rename must not silently reset a setting
         *     the player switched on. Read once by {@code ConfigManager.migrateHighlightNames}
         *     and nulled; remove once no config in the wild still carries it.
         */
        @Deprecated
        public Boolean pestEspTracer;


        /** Box colour for pest entities. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor pestHighlightColor =
                sbs.modid.client.core.render.OverlayColor.YELLOW;
        /**
         * @deprecated Legacy key from when this was called "ESP". Boxed so that "absent"
         *     is distinguishable from "false" - a rename must not silently reset a setting
         *     the player switched on. Read once by {@code ConfigManager.migrateHighlightNames}
         *     and nulled; remove once no config in the wild still carries it.
         */
        @Deprecated
        public sbs.modid.client.core.render.OverlayColor pestEspColor;


        /** Which channels the pest-spawn alert uses (an {@code AlertChannels} mask). */
        public int pestSpawnChannels = 0;

        /**
         * @deprecated the old desktop-notification toggle for the spawn alert. Only read by the
         *     migration into {@link #pestSpawnChannels}, then nulled so the next write drops it.
         *     Boxed so "absent" is distinguishable from "was switched off".
         */
        @Deprecated
        public Boolean pestSpawnNotification;

        /** Mute the pest vacuum's right-click loop while it is held. */
        @Shareable(Kind.BOOL)
        public boolean muteVacuum = false;

        /**
         * Comma-separated sound-id fragments muted while a vacuum is held. Empty means learn mode:
         * every sound heard with a vacuum in hand is logged as {@code [SBS][Vacuum]} so the right id
         * can be pasted in, rather than the mod guessing and silencing the wrong thing.
         */
        public String vacuumSoundIds = "";

        /** Coins-per-copper on every SkyMart slot. */
        @Shareable(Kind.BOOL)
        public boolean skyMartCopperPrice = true;

        /** The coin value of a plot's compost cost in the Configure Plots menu. */
        @Shareable(Kind.BOOL)
        public boolean plotPrice = true;

        /** Milestone tier drawn on the slot in the Crop Milestones menu. */
        @Shareable(Kind.BOOL)
        public boolean milestoneNumbers = true;

        /** Upgrade level drawn on the slot in the crop / composter upgrade menus. */
        @Shareable(Kind.BOOL)
        public boolean upgradeNumbers = true;
    }

    public static final class MinecraftOverlaySettings {
        /** Master toggle for the global SBS theme overhaul (default on). */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /**
         * Replace the Minecraft logo on the title screen with the SBS brand banner (vector artwork,
         * see {@link sbs.modid.client.ui.titlescreen.SbsBrandArt}). Independent of {@link #enabled}:
         * the title screen is the one place the theme shows before anything else has loaded.
         */
        @Shareable(Kind.BOOL)
        public boolean titleScreenBranding = true;

        /**
         * Extend the SBS widget theme to OTHER MODS' buttons, not just vanilla's and our own.
         *
         * <p>Default off, and deliberately so: repainting a third-party widget assumes a rounded
         * card with a centred label says the same thing the mod's own art did, which for some mods
         * left their screens unusable. Vanilla's widgets and ours are known quantities; every other
         * mod's are not.
         */
        @Shareable(Kind.BOOL)
        public boolean themeOtherMods = false;

        /**
         * Draw the mod's own panels, windows and popups <b>above every other mod's</b> screen
         * drawing (default on).
         *
         * <p>Who ends up on top of whom is otherwise an accident. Everything drawn over a screen is
         * drawn by somebody injecting into the same vanilla method, and the one whose injection
         * happens to run last wins - a race nobody configured and nobody can see the result of until
         * a panel is half-covered. On means the mod's top layer is drawn from the last hook of the
         * GUI frame, on a fresh stratum, so it is above anything drawn into the screen before it.
         *
         * <p>Off puts that layer back where it was: inside the screen's own render, where another
         * mod that draws later covers it. That is the setting for anyone who would rather see the
         * other mod's overlay through ours.
         *
         * <p>This settles order between mods, not correctness: a mod that also draws from the end of
         * the GUI frame is in the same position we are, and the two are back to whichever injection
         * runs last. There is no way to be unconditionally on top, and claiming otherwise in a
         * setting would be a lie.
         */
        @Shareable(Kind.BOOL)
        public boolean overlaysOnTop = true;
    }

    public static final class VisualsSettings {
        /** Borderless window mode: undecorated window stretched over the monitor (alt-tab friendly). */
        @Shareable(Kind.BOOL)
        public boolean borderlessWindow = false;

        /**
         * See-through Minecraft: a key that makes the whole game window semi-transparent, so what is
         * behind it can be read without alt-tabbing. Off by default, and does nothing until a key is
         * bound - see {@link sbs.modid.client.helper.visual.logic.WindowOpacity}.
         */
        @Shareable(Kind.BOOL)
        public boolean opacityEnabled = false;

        /** GLFW key (or {@code Keys.MOUSE_BASE + button}) that makes the window see-through; 0 = unbound. */
        @Shareable(Kind.KEYCODE)
        public int opacityKey = 0;

        /**
         * How see-through the window goes, as a percentage. 100 is solid.
         *
         * <p>Never below {@link #MIN_WINDOW_OPACITY}: a window faded to nothing is a window the player
         * cannot find to undo it, and the setting that would undo it is behind that window.
         */
        @Shareable(value = Kind.INT, min = SBSConfig.VisualsSettings.MIN_WINDOW_OPACITY, max = 100)
        public int opacityPercent = 50;

        /**
         * {@code true} = see-through only while the key is held, {@code false} = press to toggle.
         *
         * <p>Hold is the default because it cannot be left on by accident: let go and the window is
         * back, whatever else is happening. The toggle is the better one for actually reading
         * something, which is why both exist.
         */
        @Shareable(Kind.BOOL)
        public boolean opacityHold = true;

        /** The floor the opacity slider stops at - see {@link #opacityPercent}. */
        public static final int MIN_WINDOW_OPACITY = 15;

        /**
         * The stock title-bar picks. Static, hence never written to {@code config.json} – changing
         * one here moves every profile that has not overridden it.
         */
        public static final String STOCK_TITLE_BAR_TEXT_COLOR = "3FB4FF";
        public static final String STOCK_TITLE_BAR_BORDER_COLOR = "3FB4FF";

        /**
         * Paint the Windows title bar (caption, its text and the window frame) instead of leaving it
         * to the system. On by default: it is the mod announcing itself on the one surface the game
         * cannot draw on, and it costs four calls at start-up. Windows 11 only – see
         * {@link sbs.modid.client.helper.visual.logic.WindowTitleBar}.
         */
        @Shareable(Kind.BOOL)
        public boolean titleBar = true;

        /**
         * Title-bar background, {@code RRGGBB}. Empty by default, which follows the SBS theme's
         * panel colour – so recolouring the mod recolours the caption behind the SBS blue text.
         */
        @Shareable(Kind.HEX_COLOR)
        public String titleBarColorHex = "";

        /** Window-title text colour, {@code RRGGBB}; empty follows the theme's text colour. */
        @Shareable(Kind.HEX_COLOR)
        public String titleBarTextHex = STOCK_TITLE_BAR_TEXT_COLOR;

        /** Window frame colour, {@code RRGGBB}; empty follows the theme accent. */
        @Shareable(Kind.HEX_COLOR)
        public String titleBarBorderHex = STOCK_TITLE_BAR_BORDER_COLOR;

        /**
         * How the minimise / maximise / close glyphs are drawn. Light and dark are the only two
         * things Windows will do with them ({@code DWMWA_USE_IMMERSIVE_DARK_MODE}); {@code AUTO}
         * picks between them from the caption colour, so they stay legible when the caption follows
         * the theme. See {@link sbs.modid.client.helper.visual.model.WindowButtonStyle}.
         */
        @Shareable(value = Kind.ENUM, enumType = WindowButtonStyle.class)
        public WindowButtonStyle titleBarButtons = WindowButtonStyle.AUTO;

        /** @deprecated superseded by {@link #titleBarButtons}; boxed so absent is not {@code false}. */
        @Deprecated
        public Boolean titleBarWhiteButtons;

        /**
         * Write the operating system window's title ourselves instead of leaving it to Minecraft.
         * On by default, for the same reason {@link #titleBar} is: the caption is the one surface
         * the game cannot draw on, so it is where the mod says it is running.
         */
        @Shareable(Kind.BOOL)
        public boolean customWindowTitle = true;

        /**
         * The window title, as a template of {@code {placeholder}}s resolved every time the title is
         * built - see {@link sbs.modid.client.helper.visual.logic.WindowTitleText}. Deliberately not
         * a finished string: the Minecraft version in it is read from the running game, so this
         * default is still correct after the game updates.
         */
        public String windowTitle =
                sbs.modid.client.helper.visual.logic.WindowTitleText.DEFAULT_TEMPLATE;

        /** Hold-to-zoom key (Keys-encoded key or mouse button; 0 = unbound). */
        @Shareable(Kind.KEYCODE)
        public int zoomKey = 0;

        /**
         * How far the zoom goes, in percent: 10 = barely closer, 90 = far in. Replaces the old
         * {@code zoomLevel}, which was the inverse (a field-of-view percentage), so an old config
         * falls back to this default rather than silently meaning the opposite.
         */
        @Shareable(value = Kind.INT,
                min = sbs.modid.client.helper.visual.model.ZoomStrength.MIN,
                max = sbs.modid.client.helper.visual.model.ZoomStrength.MAX)
        public int zoomStrength = 70;

        /**
         * Glide into and out of the zoom instead of swapping the field of view on one frame. Off
         * restores the instant snap (some players aim by the jump; it is also one less thing moving
         * on screen). Applies to the Visuals hold-to-zoom only – the Ether Warp aim zoom is a
         * separate feature with its own settings.
         */
        @Shareable(Kind.BOOL)
        public boolean zoomAnimation = true;

        /**
         * How fast that glide runs, in percent of the default rate ({@code 100} = a full zoom in
         * about a fifth of a second; lower is slower, higher is snappier). Range
         * {@link sbs.modid.client.helper.visual.model.ZoomTransition#MIN_SPEED}–{@code MAX_SPEED}.
         */
        @Shareable(value = Kind.INT,
                min = sbs.modid.client.helper.visual.model.ZoomTransition.MIN_SPEED,
                max = sbs.modid.client.helper.visual.model.ZoomTransition.MAX_SPEED)
        public int zoomSpeed = 100;

        /** First-person fire overlay behaviour. */
        @Shareable(value = Kind.ENUM, enumType = FireOverlayMode.class)
        public FireOverlayMode fireOverlay = FireOverlayMode.LOWERED;

        /** Text Editor master toggle: apply the replacement rules everywhere text is shown. */
        @Shareable(Kind.BOOL)
        public boolean textEditorEnabled = false;

        /** Text Editor rules ("from" → "to"), each individually toggleable. */
        public List<sbs.modid.client.helper.visual.model.TextReplacement> textReplacements = new ArrayList<>();

        /** Explosion particle visibility. */
        @Shareable(value = Kind.ENUM, enumType = ExplosionMode.class)
        public ExplosionMode explosion = ExplosionMode.HALF;

        /** Ambient potion-effect particle visibility. */
        @Shareable(value = Kind.ENUM, enumType = PotionParticleMode.class)
        public PotionParticleMode potionParticles = PotionParticleMode.SEETHROUGH;

        /**
         * Cull every falling-block entity (sand, gravel, the terracotta rained down in dungeons) so
         * it never obscures the floor or the mobs standing on it. The blocks still exist and still
         * land – only their rendering is skipped.
         */
        @Shareable(Kind.BOOL)
        public boolean hideFallingBlocks = false;

        /**
         * Skip the Ender Dragon's death animation: once a dragon starts dying, neither its body nor
         * the beams of light shooting out of it are drawn any more (M7 stays readable while the
         * dragon dissolves).
         */
        @Shareable(Kind.BOOL)
        public boolean hideDragonDeath = false;

        /**
         * End Block Glow: make purple and pink blocks on the End island light themselves up, so the
         * structures stand out against the end stone. Applied while a chunk is meshed
         * ({@code EndBlockShadeFabricMixin}, or {@code EndBlockShadeMixin} without the Fabric
         * renderer), see {@link sbs.modid.client.helper.visual.logic.EndVisuals}.
         *
         * <p>On by default: it costs nothing outside the End - the snapshot is {@code null} and the
         * hook is one volatile read and a branch - and inside it, it is what the island is meant to
         * look like.
         */
        @Shareable(Kind.BOOL)
        public boolean endBlockGlow = true;

        /**
         * How strongly those blocks glow, in percent: 10 = a hint, 100 = fully self-lit and vividly
         * coloured. Drives the emitted light, how far the face shading is flattened out and how far
         * the colour is saturated alike - where the End is already fully lit, only the last two can
         * still show.
         */
        @Shareable(value = Kind.INT, min = 10, max = 100)
        public int endGlowIntensity = 70;

        /**
         * Dark End Blocks: de-saturate and darken the pale blocks the End is built out of - end
         * stone, sand, sandstone, birch, quartz - the look of the "dark end" resource packs, instead
         * of the bright yellow Hypixel's End is paved with. The yellow cast is always fully removed
         * while this is on; {@link #darkEndStrength} only picks the brightness. No texture is
         * downloaded or replaced, so the player's own resource pack is untouched.
         *
         * <p>On by default, on the same terms as {@link #endBlockGlow}: inert off the island, and the
         * hours of Zealots it is there for are exactly when nobody wants to stare at bright yellow.
         */
        @Shareable(Kind.BOOL)
        public boolean darkEndBlocks = true;

        /**
         * Reverse brightness of the de-saturated blocks, in percent: 0 = their natural brightness
         * (grey instead of yellow), 100 = pitch black.
         */
        @Shareable(Kind.PERCENT)
        public int darkEndStrength = 65;

        /**
         * Dim Ghosts: turn down the charged-creeper aura that <i>is</i> a Mist ghost's visible body -
         * vanilla draws it at a fixed, searing white ({@code GhostSwirlDimMixin}, see
         * {@link sbs.modid.client.helper.visual.logic.MistVisuals}).
         *
         * <p>On by default, on the same terms as {@link #endBlockGlow}: inert outside The Mist - the
         * snapshot is {@code null} and the hook is one volatile read - and inside it, hours of ghost
         * grinding are exactly when nobody wants to stare into the glare.
         */
        @Shareable(Kind.BOOL)
        public boolean dimGhosts = true;

        /**
         * How much of the aura's vanilla brightness survives, in percent: 100 = vanilla's full
         * glare, lower is gentler, 5 = only a faint shape left.
         */
        @Shareable(value = Kind.INT, min = 5, max = 100)
        public int ghostBrightness = 40;

        /**
         * Dim Mist Blocks: darken the white blocks the ghost pit is built out of - snow, white
         * glass, wool, quartz, ice - decided by map colour like the End set, applied on the same
         * chunk-mesh seam, and only while the scoreboard zone is The Mist (the rest of the Dwarven
         * Mines keeps its snow).
         */
        @Shareable(Kind.BOOL)
        public boolean dimMistBlocks = true;

        /**
         * Reverse brightness of the white pit blocks, in percent, on the exact scale of
         * {@link #darkEndStrength}: 0 = their natural brightness, 100 = pitch black. (Replaced the
         * short-lived {@code mistBlockBrightness}, which ran the other way round and read as "max =
         * max effect" - it does now.)
         */
        @Shareable(Kind.PERCENT)
        public int mistDarkness = 55;
    }

    public static final class MobHighlightSettings {
        /** Master switch: nothing is scanned or drawn while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * The SkyBlock mobs to highlight, stored by their wiki name ("Lapis Zombie", "Zealot"). The
         * name is the mob's real identity in SkyBlock – it is what the floating nametag shows and how
         * {@code SkyblockMobCatalog} keys every entry – so a saved selection keeps meaning something
         * across game versions. A {@link LinkedHashSet} so the selection keeps insertion order and
         * never duplicates.
         */
        public Set<String> selectedMobs = new LinkedHashSet<>();

        /** Box colour, cycled from a small named palette (see {@code HighlightColor}). */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.combat.mobhighlight.model.HighlightColor.class)
        public sbs.modid.client.combat.mobhighlight.model.HighlightColor color =
                sbs.modid.client.combat.mobhighlight.model.HighlightColor.RED;

        /** Draw the mob's name above its box. */
        @Shareable(Kind.BOOL)
        public boolean showLabels = true;

        /** Tracer line from the crosshair to every highlighted mob. */
        @Shareable(Kind.BOOL)
        public boolean showTracers = false;
    }

    /**
     * Ghost Hunter: which ghost in The Mist to go for next. The three penalties below are all in the
     * same currency - blocks of walking - so they can be weighed against each other and against the
     * plain distance; see {@code GhostTracker} for the model they add up to.
     */
    public static final class GhostHunterSettings {
        /** Master switch: nothing is scanned or drawn while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * What a full 180&deg; turn costs, in blocks of walking. The setting the module exists for:
         * at 10, a ghost right behind you must be 10 blocks closer than one straight ahead to be
         * worth turning around for, so a ghost a few blocks further away in front of you still wins.
         * 0 disables the idea and picks by distance alone.
         */
        public int turnCost = 10;

        /** What a ghost you have no line of sight to costs on top, in blocks. */
        public int wallPenalty = 8;

        /** What a ghost another player is standing closer to costs on top, in blocks. 0 = ignore. */
        public int contestedPenalty = 12;

        /** How far away a ghost may be to be considered at all, in blocks. */
        public int maxRange = 60;

        /** Box and tracer colour of the chosen ghost (see {@code HighlightColor}). */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.combat.mobhighlight.model.HighlightColor.class)
        public sbs.modid.client.combat.mobhighlight.model.HighlightColor color =
                sbs.modid.client.combat.mobhighlight.model.HighlightColor.RED;

        /** The line from the crosshair to the chosen ghost - the half that says which way to turn. */
        @Shareable(Kind.BOOL)
        public boolean showTracer = true;

        /** Distance caption above the chosen ghost. */
        @Shareable(Kind.BOOL)
        public boolean showLabel = true;

        /** Faint boxes on the ghosts that were not chosen, so the pick has visible context. */
        @Shareable(Kind.BOOL)
        public boolean showOthers = true;

        /**
         * Keep the module inside the ghost pit. Off, it marks any charged creeper anywhere, which is
         * how the highlight is tried out away from Hypixel - a SkyBlock ghost is exactly that.
         */
        @Shareable(Kind.BOOL)
        public boolean onlyInTheMist = true;
    }

    /** Blood Helper module: blood-room boxes, spawn markers, start-killing call-out, clear timer. */
    public BloodSettings blood = new BloodSettings();

    public static final class BloodSettings {
        /** Master switch: nothing is scanned, boxed, timed or announced while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Box the mobs in the blood room. */
        @Shareable(Kind.BOOL)
        public boolean highlightMobs = true;

        /** Draw each boxed mob's name above it. */
        @Shareable(Kind.BOOL)
        public boolean showLabels = false;

        /**
         * Lines from the crosshair to <b>every</b> blood mob - the live ones in their kind's colour,
         * the ones still landing in violet - drawn through walls. The blood room is fought turning
         * on the spot, so the mob that matters is usually the one behind you; a box cannot say that
         * and a line can. Independent of the boxes and the spawn markers.
         */
        @Shareable(Kind.BOOL)
        public boolean showTracers = true;

        /**
         * Only box mobs you have line of sight to. On by default: a blood room boxed through the
         * walls is a wall of colour, and the point of the highlight is to read the room you are in.
         * Applies to the boxes only - the lines above always go through walls.
         */
        @Shareable(Kind.BOOL)
        public boolean lineOfSightOnly = true;

        /**
         * Mark what is still arriving: a straight line from each thrown mob to the end of its
         * throw, with the mob's own hitbox drawn there to pre-aim into, plus the spawn skulls and
         * any body that has not touched the floor yet. The throw is a straight line of fixed
         * length, so the drop spot is known within a few ticks of the throw however hard the room
         * lags - park the crosshair in the floating box and the first hit lands the instant the
         * mob does.
         */
        @Shareable(Kind.BOOL)
        public boolean showSpawns = true;

        /**
         * Seconds from the blood door opening until the mobs are worth swinging at. The wait is
         * Hypixel's, not ours: the room hands you its mobs on its own schedule and hitting early
         * achieves nothing.
         */
        public int startKillingSeconds = 26;

        /** Flash + ping the moment that wait is over. */
        @Shareable(Kind.BOOL)
        public boolean startKillingAlert = true;

        /**
         * Blood camping: call out the moment the room has to be dead for the Watcher to skip the
         * rest of his dialogue. Off by default - it is a call-out for people farming the room on
         * purpose, and in a normal run it fires on a moment nobody is playing towards.
         */
        @Shareable(Kind.BOOL)
        public boolean campMove = false;

        /** Print the predicted mark to your own chat as well as flashing it. */
        @Shareable(Kind.BOOL)
        public boolean campMoveChat = true;

        /** The blood HUD card (clear clock, mobs left, the start-killing countdown). */
        @Shareable(Kind.BOOL)
        public boolean showHud = true;

        /** Print the clear time to your own chat when the room is done. */
        @Shareable(Kind.BOOL)
        public boolean chatOnClear = true;
    }

    /** Spirit Bear module (Dungeons): Thorn's bear on F4/M4 - the lantern ring, timer and call-out. */
    public SpiritBearSettings spiritBear = new SpiritBearSettings();

    /**
     * Spirit Bear settings. Its own section rather than part of the blood room: the bear is Thorn's
     * and lives in the F4/M4 <b>boss room</b>, which has nothing to do with the blood room the
     * Watcher runs.
     */
    public static final class SpiritBearSettings {
        /** Master switch: no ring scan, no timer and no call-out while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Box + trace the bear the moment it appears. */
        @Shareable(Kind.BOOL)
        public boolean highlightBear = true;

        /** Box, beam and trace the Spirit Bow the bear drops, until somebody picks it up. */
        @Shareable(Kind.BOOL)
        public boolean highlightBow = true;

        /** Flash + ping when the ring completes, when the bear lands and when the bow drops. */
        @Shareable(Kind.BOOL)
        public boolean alert = true;

        /** The card: lantern-ring progress, the spawn countdown and the bear's state. */
        @Shareable(Kind.BOOL)
        public boolean showHud = true;

        /**
         * Seconds the bear takes to materialise once the ring is full - the countdown between "it is
         * coming" and "it is here". Measured against the real arrival every fight, so the card can
         * correct it; this is only the value the countdown starts from.
         */
        public int spawnDelaySeconds = 3;
    }

    /** Stat Buff Feedback module: what an ability really did to your stats, measured off the tab. */
    public StatBuffSettings statBuffs = new StatBuffSettings();

    public static final class StatBuffSettings {
        /** Master switch: nothing is read, measured or said while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Announce the buff wearing off, with how long it lasted. */
        @Shareable(Kind.BOOL)
        public boolean reportEnd = true;

        /** Also announce changes no ability caused (gear swaps, potions) - noisy on purpose. */
        @Shareable(Kind.BOOL)
        public boolean reportUnattributed = false;

        /**
         * How long after using an item a stat change is still that item's doing. A cast takes
         * seconds to resolve, so this covers the cast plus the tab list's own update delay.
         */
        public int windowSeconds = 12;

        /** Changes smaller than this are noise - the tab list rounds what it prints. */
        public int minChange = 1;

        /** Write every reading and measured change to latest.log. */
        @Shareable(Kind.BOOL)
        public boolean debugLog = false;
    }

    /** Streamer Mode module: what of your identity and other players' is shown on screen. */
    public StreamerSettings streamer = new StreamerSettings();

    public static final class StreamerSettings {

        /** Master switch: no name is touched and no text is scanned while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /**
         * What happens to your own name. Stored as a {@code NameMode} constant name; anything
         * unrecognised reads as off.
         */
        public String ownMode = "OFF";

        /** Shown in place of your own name while {@link #ownMode} is {@code CUSTOM}. */
        public String ownName = "";

        /** What happens to every other player's name, unless one of {@link #aliases} claims it. */
        public String othersMode = "OFF";

        /** Shown in place of other players' names while {@link #othersMode} is {@code CUSTOM}. */
        public String othersName = "";

        /**
         * Per-player overrides, which win over {@link #othersMode} and {@link #ownMode} alike.
         *
         * <p>A list rather than a map: the editor shows them in the order they were added, and a
         * Gson map would reorder them on every save.
         */
        public java.util.List<sbs.modid.client.helper.streamer.model.PlayerAlias> aliases =
                new java.util.ArrayList<>();

        /**
         * The rank drawn in front of your own name. Stored as a {@code FakeRank} constant name;
         * anything unrecognised reads as the real rank.
         */
        public String ownRank = "REAL";

        /** Plus colour for a fake MVP+ / MVP++, as a legacy code ("c" = red, Hypixel's default). */
        public String rankPlusColour = "c";

        /** Draw a fake MVP++ in aqua instead of the default gold. */
        @Shareable(Kind.BOOL)
        public boolean rankAquaMvpPlusPlus = false;

        /** SkyBlock level drawn in front of your own name; 0 leaves the real one. */
        public int fakeLevel = 0;

        /** Take the Hypixel instance id ("mini24CD") out of everything that shows it. */
        @Shareable(Kind.BOOL)
        public boolean hideServerId = false;

        /** Log each distinct name and id as it is first redacted, to check the feature is working. */
        @Shareable(Kind.BOOL)
        public boolean debugLog = false;
    }

    /** Death-Save Timers module: Bonzo's Mask / Spirit Mask / Phoenix cooldown card. */
    public AbilityTimerSettings abilityTimers = new AbilityTimerSettings();

    public static final class AbilityTimerSettings {
        /** Master switch: no chat line starts a clock and no card is drawn while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Which of the three saves get a row. */
        @Shareable(Kind.BOOL)
        public boolean bonzoMask = true;
        @Shareable(Kind.BOOL)
        public boolean spiritMask = true;
        @Shareable(Kind.BOOL)
        public boolean phoenixPet = true;

        /** Keep a finished row on the card as "READY" instead of dropping it. */
        @Shareable(Kind.BOOL)
        public boolean showReady = true;

        /**
         * Cooldown lengths in seconds. Settings rather than constants: Hypixel has retuned all three
         * of these before, and a hard-coded number that is out of date tells you you are safe when
         * you are not. These are the current live values.
         */
        public int bonzoSeconds = 180;
        public int spiritSeconds = 30;
        public int phoenixSeconds = 60;
    }

    public static final class SbsPlayersSettings {
        /**
         * Master switch for the SBS badge. Off: nothing is drawn, announced or requested.
         *
         * <p><b>On by default, because on its own it is purely local.</b> Recognising yourself as an
         * SBS user takes no network call and no consent - you are obviously running SBS - so the
         * badge appears on your own nametag, tab entry and chat lines straight away.
         *
         * <p>Publishing your uuid so <i>other</i> people's clients can badge you is a separate step
         * behind {@code ConsentScope#BADGE_PUBLIC} and a licence token. Without that consent this
         * switch sends nothing at all; the badge is simply something only you see.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;
    }

    public static final class ThirdPersonSettings {
        /**
         * Show your own floating nametag above your head in third person – the same tag every other
         * SkyBlock player already sees over you. Vanilla hides your own name; this forces it on for
         * the local player while the camera is in third person.
         */
        @Shareable(Kind.BOOL)
        public boolean selfNametag = false;

        /** Show the crosshair in third person too (vanilla only draws it in first person). */
        @Shareable(Kind.BOOL)
        public boolean crosshair = false;
    }

    public static final class TexturePackSettings {
        /** Which asset routing theme is active (default SBS). */
        public TexturePackMode packTheme = TexturePackMode.SBS;

        /**
         * Ignore server-enforced resource packs (e.g. Hypixel Skyblock's required pack): the push
         * is swallowed client-side while the server is told it loaded fine, so the player keeps
         * their own packs. Applies to the next pack push (rejoin / lobby switch if already loaded).
         */
        public boolean ignoreEnforcedPacks = false;

        /**
         * Answer the vanilla "This server requires the use of a custom resource pack" prompt with
         * Proceed automatically, so joining a server never stops on that screen. Ignored while
         * {@link #ignoreEnforcedPacks} is on - that setting swallows the push before it can prompt.
         */
        public boolean autoAcceptServerPacks = true;
    }

    public static final class PlayerViewerSettings {
        /** Master toggle: {@code /sbs skycrypt [player]} opens SkyCrypt in the in-game browser. */
        public boolean enabled = true;

        /** The native SBS profile viewer ({@code /pv [player]}). */
        public boolean profileViewer = true;

        /**
         * SHIFT + left-click a player's name in chat opens their profile viewer. Replaces vanilla's
         * shift-click-to-insert-name, but only on real player names. Requires {@link #profileViewer}.
         */
        public boolean chatNameClick = false;

        /** Recently viewed players (most recent first), for the /pv side history strip. */
        public List<String> recentPlayers = new ArrayList<>();
    }

    public static final class PartyFinderSettings {
        /** Master toggle for the SBS Party Finder module (overlay, chat, matchmaking). */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Mirror the party chat into the regular game chat (like the IRC module does). */
        @Shareable(Kind.BOOL)
        public boolean chatInGame = true;

        /** Leader convenience: auto-run {@code /party invite <name>} when someone joins the SBS party. */
        @Shareable(Kind.BOOL)
        public boolean autoInvite = false;
    }

    public static final class PartyCommandsSettings {
        /** Master toggle: react to !commands typed in the party chat. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Also react to !commands YOU type yourself. */
        @Shareable(Kind.BOOL)
        public boolean reactToOwn = true;

        /** {@code !warp} / {@code !w} → {@code /party warp}. */
        @Shareable(Kind.BOOL)
        public boolean warp = true;

        /** {@code !allinv} / {@code !allinvite} → {@code /party settings allinvite}. */
        @Shareable(Kind.BOOL)
        public boolean allinv = true;

        /** {@code !transfer} / {@code !ptme} / {@code !pt} → {@code /party transfer <name>}. */
        @Shareable(Kind.BOOL)
        public boolean transfer = true;

        /** {@code !promote} / {@code !demote} → {@code /party promote|demote <name>}. */
        @Shareable(Kind.BOOL)
        public boolean promote = true;

        /** {@code !kick} / {@code !k} and {@code !kickoffline} / {@code !ko}. */
        @Shareable(Kind.BOOL)
        public boolean kick = false;

        /** {@code !invite} / {@code !inv} and {@code !reinv} / {@code !reinvite} (kick, then invite). */
        @Shareable(Kind.BOOL)
        public boolean invite = true;

        /** {@code !f1}-{@code !f7}, {@code !m1}-{@code !m7}, {@code !t1}-{@code !t5} → {@code /joininstance}. */
        @Shareable(Kind.BOOL)
        public boolean queue = true;

        /** Replies into the party chat: {@code !coords}, {@code !loc}, {@code !ping}, {@code !tps},
         * {@code !fps}, {@code !holding}, {@code !time}. */
        @Shareable(Kind.BOOL)
        public boolean info = true;

        /** Replies into the party chat: {@code !cf}, {@code !8ball}, {@code !dice}. */
        @Shareable(Kind.BOOL)
        public boolean fun = true;

        /** {@code !downtime} / {@code !dt} + {@code !undowntime} / {@code !undt} end-of-run reminder. */
        @Shareable(Kind.BOOL)
        public boolean downtime = true;

        /** {@code !boop [name]} → {@code /boop <name>}. */
        @Shareable(Kind.BOOL)
        public boolean boop = true;

        /** {@code !help} / {@code !h} → lists the enabled commands in the party chat. */
        @Shareable(Kind.BOOL)
        public boolean help = true;
    }

    public static final class ShortCommandsSettings {
        /** Master toggle for every built-in shortcut below. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** {@code /pw /pt /pp /pdm /pd /pk /pko /pi /pa} → the full {@code /party ...} command. */
        @Shareable(Kind.BOOL)
        public boolean party = true;

        /** A bare {@code /pa} accepts the invite you were sent last (valid for 60s). */
        @Shareable(Kind.BOOL)
        public boolean acceptLastInvite = true;

        /** {@code /pk <name> <reason>} announces the reason in the party chat before kicking. */
        @Shareable(Kind.BOOL)
        public boolean kickReason = true;

        /** Warp without the {@code warp} prefix ({@code /wizard} → {@code /warp wizard}). */
        @Shareable(Kind.BOOL)
        public boolean shortWarp = true;

        /** {@code /warp is} → {@code /is}, so both spellings reach the private island. */
        @Shareable(Kind.BOOL)
        public boolean warpIs = true;

        /** Hold a warp back until Hypixel's transfer cooldown is over instead of losing it. */
        @Shareable(Kind.BOOL)
        public boolean fixTransferCooldown = true;

        /** Say in chat when a held-back warp is finally sent. */
        @Shareable(Kind.BOOL)
        public boolean transferCooldownMessage = true;

        /** Accept lower-case item ids in {@code /viewrecipe} ({@code hyperion} → {@code HYPERION}). */
        @Shareable(Kind.BOOL)
        public boolean lowercaseViewrecipe = true;
    }

    public static final class RecipeViewerSettings {
        /**
         * The Recipe Viewer's kind chip: ALL, ITEMS, PETS or NPCS ({@code RecipeEntryKind.Filter} by
         * name). A string so an unknown value from an older or newer build reads as ALL, not a crash.
         */
        public String kindFilter = "ALL";

        /** Master On/Off toggle for the Recipe Viewer (search, lookup key, scraper, highlight mode). */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /**
         * Panel behavior: {@code false} = static panel beside the container (classic side-panel layout),
         * {@code true} = a movable, Ctrl+scroll-resizable window that can be minimized to a button
         * next to the search bar (never closed).
         */
        @Shareable(Kind.BOOL)
        public boolean windowed = false;

        /**
         * How much of the free area beside the container the static panel is allowed to fill, in
         * percent ({@code 30}–{@code 100}). The full-size panel runs from just under the top of the
         * screen down to the search bar and out to the screen edge, which is most of one half of the
         * view; anything below 100 keeps the grid docked against the container and trims it back
         * towards it. Ignored in windowed mode, where the window's own size decides.
         */
        @Shareable(value = Kind.INT, min = sbs.modid.client.economy.recipe.ui.RecipeOverlay.MIN_PANEL_SIZE, max = 100)
        public int panelSize = 55;

        /** Collapse enchant-book levels (Legion 1..max) into one grouped search row; hover shows the group. */
        @Shareable(Kind.BOOL)
        public boolean groupEnchants = true;

        /**
         * Search Highlight Mode reads each item's whole tooltip - lore lines and enchantments -
         * not only its name, so an auction page can be filtered by "sharpness 6" or by a stat.
         * Off narrows highlighting back to the item name and its SkyBlock id.
         */
        @Shareable(Kind.BOOL)
        public boolean searchTooltips = true;

        /** Shift-click an NPC entry ("... (NPC)") to open a small panel with its island + coordinates. */
        @Shareable(Kind.BOOL)
        public boolean npcLocator = true;

        /** When you shift-click an NPC and are already on its island, start pathfinding to it. */
        @Shareable(Kind.BOOL)
        public boolean npcPathfinding = true;
    }

    public static final class CalculatorSettings {
        /** Master On/Off: the floating calculator window over container screens. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Show the on-screen keypad; off leaves display + history for keyboard-only use. */
        @Shareable(Kind.BOOL)
        public boolean keypad = true;

        /** How many finished calculations the history keeps before dropping the oldest. */
        public int historySize = 30;

        /**
         * Clicking the {@code = ...} chip above the container search bar writes the result back into
         * the bar, so the next operator carries on from it.
         */
        @Shareable(Kind.BOOL)
        public boolean clickableSearchResult = true;
    }

    public static final class DungeonsSettings {
        /**
         * Teammate low-health warning. Off by default: the sidebar row it reads is unverified -
         * see {@code docs/features/teammate-low-health.md}.
         */
        @Shareable(Kind.BOOL)
        public boolean teammateHealthWarn = false;
        /** Below this percent of the most health seen this run, a teammate is low. */
        public int teammateHealthThreshold = 30;
        /** At most one warning per teammate per this many seconds. */
        public int teammateHealthCooldownSeconds = 10;
        /** Only warn while the local player is the Healer. */
        @Shareable(Kind.BOOL)
        public boolean teammateHealthHealerOnly = false;
        /** Tint a low teammate's party box red. */
        @Shareable(Kind.BOOL)
        public boolean teammateHealthTint = false;
        /** Where the warning is delivered. */
        public int teammateHealthChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;

        /**
         * Value a dungeon reward chest against its opening cost, priced live from the auction crawl
         * and the Bazaar. On by default: it answers the one question the chest menu asks.
         */
        @Shareable(Kind.BOOL)
        public boolean chestValue = true;

        /**
         * Write each chest's profit onto its slot, not only into its tooltip.
         *
         * <p>The tooltip answers one chest at a time, and the question a chest menu asks is
         * comparative - Croesus lists a whole run's chests at once, and reading six tooltips to
         * compare six numbers is the work this is meant to remove. Needs {@link #chestValue}.
         */
        @Shareable(Kind.BOOL)
        public boolean chestProfitText = true;

        /**
         * Mark the chests that lose money, instead of leaving them unmarked.
         *
         * <p><b>This reverses an earlier decision on purpose.</b> The ranking originally marked only
         * profitable chests, on the reasoning that the absence of a highlight already says "not worth
         * it". That holds in a menu where every slot is a chest and fails in Croesus's, where a slot
         * can equally be scenery, a filler pane or a chest nobody could price - so "unmarked" stopped
         * meaning one thing. The number drawn beside it carries its own sign, so the judgement never
         * rests on the colour alone.
         */
        @Shareable(Kind.BOOL)
        public boolean chestLossHighlight = true;

        /**
         * Print what a Kismet Feather currently costs beside a chest's value.
         *
         * <p>A price and nothing more. What a reroll is <i>worth</i> needs the per-floor drop tables
         * and the reroll's own odds, and neither is in this repository or checkable from the client -
         * see {@code docs/features/croesus-overlay.md}.
         */
        @Shareable(Kind.BOOL)
        public boolean kismetHint = true;

        /**
         * Croesus's run list: colour the runs that still have a chest waiting, dim the finished ones.
         *
         * <p><b>Off by default, and the reason is the rule rather than caution.</b> Not one line of
         * the Croesus menu is recorded anywhere in this repository - the wording that separates a run
         * with chests left from a finished one came from a description, so it is held the way the
         * root {@code AGENTS.md} says a named-but-unseen fact is held: in {@link #croesusUnopened}
         * and {@link #croesusOpened} below, matched tolerantly, counted on the page, and switched on
         * by a player who knows that. Nothing here is a literal in code.
         */
        @Shareable(Kind.BOOL)
        public boolean croesusRunHighlight = false;

        /**
         * On arriving in the Dungeon Hub, say how many runs were left unopened the last time Croesus
         * was open - with how long ago that was.
         *
         * <p>The age is not decoration: this is a remembered number, not a live one, and a reminder
         * that reads as current would be wrong the moment somebody opened a chest on another device.
         */
        @Shareable(Kind.BOOL)
        public boolean croesusReminder = false;

        /**
         * The fragment of a menu title that says "this is Croesus's own menu", lowercase.
         *
         * <p>Empty switches the title test off, which makes every menu a candidate - the answer if
         * Hypixel titles the list something that does not carry the NPC's name.
         */
        public String croesusTitle = "croesus";

        /**
         * Lore phrases meaning "this run still has a chest", comma-separated, matched as plain
         * containment against the colour-stripped lore. {@code ESTIMATED} - see
         * {@link #croesusRunHighlight}.
         */
        public String croesusUnopened = "unopened, unclaimed, chests left, rewards to claim";

        /**
         * Lore phrases meaning "this run is finished". Checked <b>before</b> the ones above, because
         * it is the more specific claim: "no more chests" contains no word that the unopened list
         * could not also be given, and a run that matches both is finished.
         */
        public String croesusOpened =
                "no more chests, all chests opened, no chests left, already claimed";

        /**
         * Write the Croesus menu's own lines to the log, once per opening.
         *
         * <p>The whole feature above rests on wording nobody has captured. This is the one trip that
         * replaces it - the same job {@code /sbs probe} does, without having to remember the command
         * while standing at the NPC.
         */
        @Shareable(Kind.BOOL)
        public boolean croesusDebugLog = false;

        /** Custom SBS Dungeon Map overlay (mirrors the real dungeon map item into an SBS-styled HUD). */
        @Shareable(Kind.BOOL)
        public boolean sbsDungeonMap = false;

        /**
         * Hide the SBS Dungeon Map for the rest of the run once it reaches the boss.
         *
         * <p>Off by default: the map going away on its own is surprising, and it is what everyone
         * already has. Nothing behind the map stops - only the drawing is skipped, so the room
         * counts, the score and the door boxes are unaffected either way.
         */
        @Shareable(Kind.BOOL)
        public boolean hideMapInBoss = false;

        /** Dungeon Score HUD card (floor, secrets, crypts, deaths, cleared %, estimated score). */
        @Shareable(Kind.BOOL)
        public boolean showScore = false;

        /** Sound + overlay feedback when a secret is collected (also hides its Secret-Routes waypoint). */
        @Shareable(Kind.BOOL)
        public boolean secretClickedFeedback = false;

        /** You run a Spirit pet: the score's first death costs −1 instead of −2 (set it yourself). */
        @Shareable(Kind.BOOL)
        public boolean spiritPetForScore = false;

        /**
         * A blessings line on the Dungeon Score card: each type's summed level this run, read from the
         * buff chat lines. Only drawn when the card is; a run joined midway shows "3+" / "–".
         */
        @Shareable(Kind.BOOL)
        public boolean showBlessings = true;

        /**
         * Call out the first Prince killed in a run (flash + one line in your own chat) and count its
         * +1 into the score card. Client-side only; nothing is sent to anyone. Off = no call-out and
         * no scan at all, and the score card stops counting the point.
         */
        @Shareable(Kind.BOOL)
        public boolean princeAlert = true;

        /** Highlight the F7/M7 terminal solution slots (display only, never clicks). */
        @Shareable(Kind.BOOL)
        public boolean terminalSolver = false;

        /**
         * Refuse a click the terminal solver did not mark, so a misclick never becomes a packet.
         * Only refuses - it never clicks for you - and it stands down on the melody terminal, where
         * the timing is the puzzle. Needs the solver itself to be on.
         */
        @Shareable(Kind.BOOL)
        public boolean terminalBlockWrongClicks = false;

        /**
         * Draw the open terminal as one big SBS board over the vanilla chest instead of outlining its
         * little slots. Same puzzle and the same clicks - a cell click is forwarded to the real slot
         * behind it - only at a size that can be read while a Necron is hitting you. Needs the solver.
         */
        @Shareable(Kind.BOOL)
        public boolean terminalCustomGui = false;

        /** Board size in percent of the built-in cell size (60-200). */
        @Shareable(value = Kind.INT, min = 60, max = 200)
        public int terminalGuiScale = 100;

        /**
         * Say one line in party chat when <b>you</b> finish a terminal, device or lever. Off by
         * default because it sends a message in your name; it never fires for anyone else's
         * activation, and never anywhere but party chat.
         */
        @Shareable(Kind.BOOL)
        public boolean terminalAnnounce = false;

        /**
         * The line the announcement sends. {@code {kind}} becomes terminal / device / lever,
         * {@code {done}} and {@code {total}} the server's own counter, {@code {time}} how long it
         * took you.
         */
        public String terminalAnnounceText = "{kind} {done}/{total} done ({time})";

        /**
         * Time each terminal from the moment it opens until your own activation line, and print the
         * result - with your best for that terminal - to your own chat. Local only.
         */
        @Shareable(Kind.BOOL)
        public boolean terminalTimes = false;

        /** Best live solve time in ms per terminal type, e.g. {@code {"ORDER":3100}}. */
        public java.util.Map<String, Long> terminalLivePb = new java.util.HashMap<>();

        /**
         * Highlight the next button on the phase-3 Simon Says device. Display only - it reads the
         * lit sequence off the wall and marks where to press, it never presses.
         */
        @Shareable(Kind.BOOL)
        public boolean deviceSolver = false;

        /** F7/M7 phase-3 progress card: terminals, devices and levers done, and who did them. */
        @Shareable(Kind.BOOL)
        public boolean terminalProgress = false;

        /**
         * Master switch for every puzzle solver. Off = nothing is read, solved or drawn, and the
         * per-puzzle toggles below do nothing.
         */
        @Shareable(Kind.BOOL)
        public boolean puzzleSolver = false;
        /** Blaze order: off = kill lowest health first (default), on = highest first. */
        @Shareable(Kind.BOOL)
        public boolean blazeHighestFirst = false;

        /**
         * Per-puzzle switches, each gated behind {@link #puzzleSolver}.
         *
         * <p>One per puzzle rather than one for all of them because they are not equally trustworthy:
         * a solver reading a mechanic nobody has verified yet should be switchable off on its own,
         * without costing the player the ones that work.
         */
        @Shareable(Kind.BOOL)
        public boolean puzzleQuiz = true;
        @Shareable(Kind.BOOL)
        public boolean puzzleThreeWeirdos = true;

        /** GLFW key (0 = unbound) that opens the Terminal Simulator practice screen. */
        @Shareable(Kind.KEYCODE)
        public int terminalSimKey = 0;
        /** Personal-best solve times (ms) per simulator terminal type, e.g. {@code {"NUMBERS":4200}}. */
        public java.util.Map<String, Long> terminalPb = new java.util.HashMap<>();

        /** Leap Menu: class-coloured Spirit-Leap slots + a slot keybind per class (menu must be open). */
        @Shareable(Kind.BOOL)
        public boolean leapMenuEnabled = false;
        /** GLFW keys (0 = unbound): while the Spirit Leap menu is open, leap to that class's player. */
        @Shareable(Kind.KEYCODE)
        public int leapMageKey = 0;
        @Shareable(Kind.KEYCODE)
        public int leapArcherKey = 0;
        @Shareable(Kind.KEYCODE)
        public int leapBerserkKey = 0;
        @Shareable(Kind.KEYCODE)
        public int leapTankKey = 0;
        @Shareable(Kind.KEYCODE)
        public int leapHealerKey = 0;

        /** Positional Messages: fire a message when near a saved coordinate. Outbound-capable → off. */
        @Shareable(Kind.BOOL)
        public boolean positionalMessagesEnabled = false;
        public java.util.List<PositionalMessage> positionalMessages = new java.util.ArrayList<>();

        /** Identify database rooms and resolve their waypoints (chest/lever) to world coordinates. */
        @Shareable(Kind.BOOL)
        public boolean roomWaypoints = false;

        /** Yellow hitbox around every visible dungeon mob whose nametag carries the ✯ star. */
        @Shareable(Kind.BOOL)
        public boolean boxStarredMobs = false;

        /**
         * F7/M7 phase timer: how long Maxor, Storm, Goldor, Necron and (M7) the dragons each took,
         * as a HUD card with the running phase and the total.
         */
        @Shareable(Kind.BOOL)
        public boolean phaseTimer = false;

        /** Print the finished splits once to chat at the end-of-run summary (client-side only). */
        @Shareable(Kind.BOOL)
        public boolean phaseTimerChat = true;

        /**
         * M7 dragon phase: a card with every dragon that is up, what it has left, and whether it is
         * standing at its own statue - which is what decides whether killing it counts.
         */
        @Shareable(Kind.BOOL)
        public boolean m7DragonHp = false;

        /** Draw each dragon's health at the dragon itself as well as on the card. */
        @Shareable(Kind.BOOL)
        public boolean m7DragonHpWorld = true;

        /**
         * Boxes on the dragon statues: a dragon only brings its statue down when it dies at that
         * statue, and the box is that spot. Positions are learned from where each dragon comes up;
         * stand at a statue and run {@code /sbs dragons <colour>} to correct one.
         */
        @Shareable(Kind.BOOL)
        public boolean m7DragonBoxes = false;

        /**
         * Half-width of a statue box, in blocks. A setting rather than a constant: Hypixel does not
         * publish the radius it actually checks, so this is the one number worth being able to tune.
         */
        @Shareable(value = Kind.INT, min = 4, max = 24)
        public int m7DragonBoxRadius = 10;

        /** The statues as learned or set, per dragon: {@code {"POWER":[x,y,z]}}. */
        public java.util.Map<String, double[]> m7DragonStatues = new java.util.HashMap<>();

        /**
         * F3/M3 Fire Freeze card: the countdown from the Professor's phase-turn line to the moment
         * the staff should be cast, then its wind-up and how long he stays frozen. Display only - it
         * never casts anything.
         */
        @Shareable(Kind.BOOL)
        public boolean fireFreezeTimer = false;

        /**
         * Milliseconds from the Professor's line until the staff should be cast. A setting rather
         * than a constant: the staff freezes five seconds after the cast, so being half a second out
         * wastes it entirely, and where that lands depends on your ping. ~3.5 s is the common answer.
         */
        @Shareable(value = Kind.INT, min = 0, max = 8000)
        public int fireFreezeCastMs = 3500;

        /** Flash + ping at the cast moment, on top of the card's countdown. */
        @Shareable(Kind.BOOL)
        public boolean fireFreezeAlert = true;

        /**
         * F6/M6 terracotta respawn markers: a counting box on the spot where each one died, since
         * that is exactly where it comes back. Sadan's first phase is a kill-rate race against his
         * interest meter, and the corpse's square is the next kill. A death is read off the flower
         * pot the mob collapses into, so every kill in the arena gets its marker.
         */
        @Shareable(Kind.BOOL)
        public boolean terracottaTimer = false;

        /**
         * Seconds a killed terracotta takes to re-form, per difficulty: 15 on F6, 12 on M6 (300 and
         * 240 server ticks - both measured against the live fight). Settings because Hypixel can
         * retune them, and the marker is only worth as much as these numbers.
         */
        @Shareable(value = Kind.INT, min = 1, max = 60)
        public int terracottaSecondsFloor = 15;
        @Shareable(value = Kind.INT, min = 1, max = 60)
        public int terracottaSecondsMaster = 12;

        /**
         * F6/M6 giant health: a card listing the giants that are still up, weakest first. Their own
         * nametags float above twelve blocks of mob, which is off the top of the screen from where
         * the fight is actually had.
         */
        @Shareable(Kind.BOOL)
        public boolean giantHp = false;

        /** Also draw each giant's health low on its body, not just on the card. */
        @Shareable(Kind.BOOL)
        public boolean giantHpWorld = true;

        /**
         * F3/M3 guardian health: a card listing the Professor's guardians that are still up, weakest
         * first. The four sit apart around the room behind their own water and lasers, and he cannot
         * be touched until the last one is down.
         */
        @Shareable(Kind.BOOL)
        public boolean guardianHp = false;

        /** Also draw each guardian's health over the guardian itself, not just on the card. */
        @Shareable(Kind.BOOL)
        public boolean guardianHpWorld = true;

        /**
         * The fixed "3x3" waypoint in the F7/M7 Necron arena – the block the healer breaks. Drawn
         * only during the Necron phase and only while you actually have line of sight to it.
         */
        @Shareable(Kind.BOOL)
        public boolean necronHealerWaypoint = false;

        /**
         * Livid Tracker (F5/M5): find the one real Livid among the clones - off the colour of the
         * wool in the boss-room ceiling, or off the first Livid to take damage - box + trace it and
         * show a HUD card with its colour, health and the fight time.
         */
        @Shareable(Kind.BOOL)
        public boolean lividTracker = false;

        /** Box the real Livid once it is identified. */
        @Shareable(Kind.BOOL)
        public boolean lividBox = true;

        /** Tracer from the crosshair to the real Livid. */
        @Shareable(Kind.BOOL)
        public boolean lividTracer = true;

        /** Livid HUD card (identified colour, health, fight time). */
        @Shareable(Kind.BOOL)
        public boolean lividHud = true;

        /** Announce the identified Livid once in chat (client-side only, nothing is sent). */
        @Shareable(Kind.BOOL)
        public boolean lividChatMessage = true;

        /** Tracer lines from the crosshair to secret waypoints, starred mobs and wither doors. */
        @Shareable(Kind.BOOL)
        public boolean showTracers = false;

        /** Box the coal wither / blood doors while in a dungeon (locked colour + key colour). */
        @Shareable(Kind.BOOL)
        public boolean witherDoors = false;

        /** Colour of a locked wither / blood door. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor witherDoorColor =
                sbs.modid.client.core.render.OverlayColor.YELLOW;

        /** Colour of your current room's door once you hold a wither key. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor witherDoorKeyColor =
                sbs.modid.client.core.render.OverlayColor.GREEN;

        /**
         * Draw a translucent green ground bubble in the Tank's 30-block "Diversion" range (the tank
         * diverts 80% of nearby teammates' damage), so you can see where you are covered.
         */
        @Shareable(Kind.BOOL)
        public boolean showTankRange = false;

        /** GLFW key that saves a route waypoint at the player's feet ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int routeStandingKey = 0;

        /** GLFW key that saves a route waypoint at the crosshair block ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int routeLookingKey = 0;

        /** 32-grid world origin offset (calibrate live with {@code /sbsdev origin <x> <z>}). */
        public int gridOriginX = 0;
        public int gridOriginZ = 0;

        // --- Mimic announce (F6/F7/M6/M7). Outbound to party chat, so both paths default OFF. ---
        /** The message sent to party chat when the mimic is announced. */
        public String mimicMessage = "Mimic dead!";
        /** GLFW key (0 = unbound): manually announce the mimic to party chat (reliable, no auto-detect). */
        @Shareable(Kind.KEYCODE)
        public int mimicAnnounceKey = 0;
        /**
         * Best-effort auto-announce when a trapped chest is broken in a mimic floor. OFF by default and
         * imperfect: some rooms use a trapped chest as a normal secret, so this can false-positive -
         * the manual key is the reliable path. Never sends unless explicitly enabled.
         */
        @Shareable(Kind.BOOL)
        public boolean mimicAutoAnnounce = false;
    }

    /** One Positional Message: fire {@code message} when the player is within {@code radius} of x/y/z. */
    public static final class PositionalMessage {
        public int x;
        public int y;
        public int z;
        public double radius = 3;
        public String message = "";
        /** Send to party chat ({@code /pc}) instead of only a local overlay. Outbound. */
        public boolean party = false;
    }

    public static final class AnimationScalingSettings {
        /**
         * Swing (attack) animation speed in percent: 100 = vanilla, 10 = ten times slower,
         * 999999 ≈ instant, 0 = frozen. Range 0–999999, applied to the local player only.
         */
        @Shareable(value = Kind.INT, min = 0, max = 999999)
        public int swingSpeed = 100;

        /**
         * Held-item render size in percent: 100 = vanilla, 0 = invisible. Range 0–1000.
         * Applied in the views selected by {@link #itemViewMode}.
         */
        @Shareable(value = Kind.INT, min = 0, max = 1000)
        public int itemSize = 100;

        /** Which views the held-item size / offset applies to (first person, third person, both). */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.helper.visual.model.ItemViewMode.class)
        public sbs.modid.client.helper.visual.model.ItemViewMode itemViewMode =
                sbs.modid.client.helper.visual.model.ItemViewMode.BOTH;

        /**
         * Held-item position offset in 1/100 blocks, hand-local axes (0 = vanilla position). These
         * are the <b>main hand</b> values, and - while {@link #separateOffHandOffset} is off - the
         * off hand's as well, which is the behaviour these fields have always had.
         */
        @Shareable(value = Kind.INT, min = -500, max = 500)
        public int itemOffsetX = 0;
        @Shareable(value = Kind.INT, min = -500, max = 500)
        public int itemOffsetY = 0;
        @Shareable(value = Kind.INT, min = -500, max = 500)
        public int itemOffsetZ = 0;

        /**
         * Give the off hand its own offset instead of mirroring the main hand's. Off by default so
         * an existing configuration keeps applying one offset to both hands exactly as before.
         */
        @Shareable(Kind.BOOL)
        public boolean separateOffHandOffset = false;

        /** Off-hand position offset, used only while {@link #separateOffHandOffset} is on. */
        @Shareable(value = Kind.INT, min = -500, max = 500)
        public int offHandOffsetX = 0;
        @Shareable(value = Kind.INT, min = -500, max = 500)
        public int offHandOffsetY = 0;
        @Shareable(value = Kind.INT, min = -500, max = 500)
        public int offHandOffsetZ = 0;

        /**
         * The offset that applies to one hand. Both render hooks (first and third person) go
         * through these three, so the two views can never disagree about where a hand's item sits.
         */
        public int handOffsetX(boolean mainHand) {
            return mainHand || !separateOffHandOffset ? itemOffsetX : offHandOffsetX;
        }

        public int handOffsetY(boolean mainHand) {
            return mainHand || !separateOffHandOffset ? itemOffsetY : offHandOffsetY;
        }

        public int handOffsetZ(boolean mainHand) {
            return mainHand || !separateOffHandOffset ? itemOffsetZ : offHandOffsetZ;
        }

        /** Dropped-item (ground item) render scale toggle + percent (10–1000, 100 = vanilla). */
        @Shareable(Kind.BOOL)
        public boolean droppedItemScaleEnabled = false;
        @Shareable(value = Kind.INT, min = 10, max = 1000)
        public int droppedItemScale = 100;

        /** Per-axis entity scale in percent (0–1000, 100 = vanilla). */
        @Shareable(value = Kind.INT, min = 0, max = 1000)
        public int entityScaleX = 100;
        @Shareable(value = Kind.INT, min = 0, max = 1000)
        public int entityScaleY = 100;
        @Shareable(value = Kind.INT, min = 0, max = 1000)
        public int entityScaleZ = 100;

        /** Who the entity scale applies to (independent toggles, combinable). */
        @Shareable(Kind.BOOL)
        public boolean scaleSelf = false;
        @Shareable(Kind.BOOL)
        public boolean scaleOtherPlayers = false;
        @Shareable(Kind.BOOL)
        public boolean scaleAllEntities = false;

        /**
         * Whether player-model NPCs follow the "other players" toggle. On by default because that
         * is what they have always done - before NPCs were told apart from players at all, they
         * were simply scaled along with them.
         */
        @Shareable(Kind.BOOL)
        public boolean scaleNpcs = true;

        /**
         * Registry ids of the non-player entity types the scale applies to
         * ({@code "minecraft:villager"}). <b>Empty means every type</b>, which is the historical
         * behaviour of {@link #scaleAllEntities}; adding entries narrows it to exactly those.
         */
        public java.util.Set<String> scaleEntityTypes = new java.util.LinkedHashSet<>();
    }

    public static final class SkyblockMenuSettings {
        /**
         * Sack Overlay: a panel beside an open sack listing everything in it with a value. It lives
         * on this card rather than on Item Overlay because a sack <i>is</i> a Hypixel menu and this
         * is a panel drawn beside one - the same thing the storage preview and the Pets grid are.
         * Item Overlay is about what is drawn onto an item; this is about a menu.
         *
         * <p><b>Off by default until the sack menu is confirmed in game.</b> The stored amount is read
         * from the lore, in a format ("Stored: ...") that no captured sack has shown yet, and the
         * Gemstones sack tiers and paging are unprobed - every amount the panel shows is a guess
         * until then, and the root AGENTS.md has unfinished features merge switched off. The capture
         * steps are in docs/features/sack-overlay.md; flipping this back on is its own commit.
         */
        @Shareable(Kind.BOOL)
        public boolean sackOverlay = false;

        /** Which price the Sack Overlay puts on everything. Persisted from the panel's own switch. */
        @Shareable(value = Kind.ENUM,
                enumType = sbs.modid.client.helper.sacks.model.SackPriceMode.class)
        public sbs.modid.client.helper.sacks.model.SackPriceMode sackPriceMode =
                sbs.modid.client.helper.sacks.model.SackPriceMode.INSTA_SELL;

        /** How the Sack Overlay orders its rows. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.helper.sacks.model.SackSort.class)
        public sbs.modid.client.helper.sacks.model.SackSort sackSort =
                sbs.modid.client.helper.sacks.model.SackSort.VALUE;

        /** Hide sack rows holding nothing. On by default - an empty row is noise in a long sack. */
        @Shareable(Kind.BOOL)
        public boolean sackHideEmpty = true;

        /**
         * Off / hover preview / full central item search. Supersedes the old boolean
         * {@link #enderchestBackpackPreview}.
         *
         * <p>Defaults to Full UI: the storage workspace is the feature, and a fresh install should
         * get it without hunting for the setting. Configs from before the three-way setting still
         * keep whatever the old on/off flag said (see {@code ConfigManager.migrateStoragePreview}).
         */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.helper.storage.StoragePreviewMode.class)
        public sbs.modid.client.helper.storage.StoragePreviewMode previewMode =
                sbs.modid.client.helper.storage.StoragePreviewMode.FULL_UI;

        /**
         * The old on/off preview flag. Boxed so its <b>absence</b> is distinguishable from
         * {@code false}: {@code ConfigManager} migrates a non-null value into {@link #previewMode}
         * and then clears it, so it disappears from config.json on the next write. Do not read this
         * anywhere else.
         */
        @Deprecated
        public Boolean enderchestBackpackPreview;

        /** SBS Loadouts: full-grid loadout overlay with 3D player previews over Hypixel's menu. */
        @Shareable(Kind.BOOL)
        public boolean sbsWardrobe = false;

        /**
         * SBS Wardrobe View: a full grid over Hypixel's "(N/M) Armor Sets" menu (the wardrobe, reached
         * from the Loadouts menu's chestplate slot). A new field, not {@link #sbsWardrobe}: that one is
         * the SBS Loadouts toggle and its id is player state. The label is "SBS Wardrobe View".
         */
        @Shareable(Kind.BOOL)
        public boolean sbsWardrobeView = true;

        /**
         * The equipped-loadout HUD widget: off / the card / the card with your player model. Reads
         * the same per-profile loadout cache SBS Loadouts fills, so the two always agree.
         */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.helper.loadouts.LoadoutWidgetMode.class)
        public sbs.modid.client.helper.loadouts.LoadoutWidgetMode loadoutWidget =
                sbs.modid.client.helper.loadouts.LoadoutWidgetMode.OFF;

        /**
         * Mirror My Player: the Equipped Loadout widget's model copies what you are doing - walking,
         * sneaking, held items, bow draw / eating, sword swings, where you look - while keeping the
         * loadout's own armour. A new field (default on), not a changed default of an existing one.
         */
        @Shareable(Kind.BOOL)
        public boolean loadoutMirror = true;

        /** SBS Pets: every page of the Pets menu in one card grid (cached per session). */
        @Shareable(Kind.BOOL)
        public boolean sbsPets = false;

        /** Custom loadout names, keyed by the loadout number ("3" -> "Dungeon F7"). */
        public Map<String, String> loadoutNames = new LinkedHashMap<>();
    }

    /** Central item search (Skyblock Menu module, "Full UI" mode) preferences. */
    public StorageSearchSettings storageSearch = new StorageSearchSettings();

    public static final class StorageSearchSettings {
        /** GLFW key that opens the central item search in-world. Defaults to {@code O}. */
        @Shareable(Kind.KEYCODE)
        public int openKey = 79;

        /** Also match an item's lore, not just its name / id (slower, but finds by stat text). */
        @Shareable(Kind.BOOL)
        public boolean searchLore = false;

        /**
         * Unused since the workspace became part of Full UI itself (a {@code false} here made the
         * overlay vanish on open pages – exactly the inconsistency Full UI is meant to prevent).
         * Kept so old config files still deserialize.
         */
        @Shareable(Kind.BOOL)
        public boolean seamlessUi = true;

        /** Result order: AMOUNT, PRICE, NAME or LOCATION (legacy COUNT reads as AMOUNT). */
        public String sort = "AMOUNT";

        /** Item identities the player starred; they sort above everything else. */
        public List<String> favorites = new ArrayList<>();

        /** Recently used search queries, most recent first. */
        public List<String> recentSearches = new ArrayList<>();

        /** Which storage families are indexed and searched, by {@code StorageSource.Kind} name. */
        public Set<String> sources = new LinkedHashSet<>(List.of(
                "INVENTORY", "ENDER_CHEST", "BACKPACK", "CHEST", "MUSEUM", "SACKS", "VAULT", "OTHER"));

        /** Whether a storage family is switched on. Unknown kinds default to on. */
        public boolean enabledFor(sbs.modid.client.helper.storage.StorageSource.Kind kind) {
            return sources == null || sources.isEmpty() || sources.contains(kind.name());
        }

        /** Toggles a storage family on / off. */
        public void toggleSource(sbs.modid.client.helper.storage.StorageSource.Kind kind) {
            if (sources == null) {
                sources = new LinkedHashSet<>();
            }
            if (!sources.remove(kind.name())) {
                sources.add(kind.name());
            }
        }
    }

    public static final class LicenceSettings {
        /**
         * Paint the settings that need a licence token red while none is set, in the config screen
         * and its sidebar. See {@link sbs.modid.client.core.licence.LicenceMarks}.
         *
         * <p>On by default: a feature that cannot work without a token is otherwise indistinguishable
         * from one that is simply switched off, and the way that reads is "the mod is broken". Once a
         * token is set nothing is marked at all, so the setting only matters to players without one -
         * which is exactly who it is for, and exactly who might not want the reminder.
         */
        public boolean markLicenceFeatures = true;

        /**
         * Where the licence token used to live. It now lives in {@code license/token.json}
         * ({@link sbs.modid.client.core.config.LicenceToken}) so it is shared by every config profile
         * instead of being lost on each new one.
         *
         * <p>Kept only so an existing config can hand its token over once. {@code null} (the default)
         * means "nothing to migrate", and Gson omits null fields - so once migrated it disappears
         * from config.json for good.
         *
         * <p>Deliberately NOT called {@code token}: it stays mapped to the old JSON key, but the field
         * name no longer matches what a reader would type, so {@code config.licence.token} fails to
         * compile instead of silently returning null. That is not hypothetical - moving the token out
         * left fifteen readers behind, twelve of them were missed, and every API they served answered
         * "invalid licence token" because null is a perfectly valid-looking empty token.
         *
         * @deprecated read {@link sbs.modid.client.core.config.LicenceToken#get()} instead.
         */
        @Deprecated
        @com.google.gson.annotations.SerializedName("token")
        public String legacyToken;
    }

    /**
     * Which ranking a feature that has both should use: the licence-backed server one, or the one
     * computed on this machine.
     *
     * <p>Shared by every feature with a local fallback (bazaar flips, forge flips, AH flips, similar
     * auctions) so the choice means the same thing and is remembered the same way in all of them.
     *
     * <p><b>The pin is the whole design.</b> A fresh install has no token, so local is the only thing
     * that can work and is the default. The first time a working token appears the choice moves to
     * the server <i>once</i> and pins itself — nobody who has just paid for a licence should have to
     * find four switches to get what they paid for. After that pin the field belongs to the player
     * and nothing in the mod writes it again, so someone who prefers the local ranking (or is
     * comparing the two) keeps their setting through every reconnect, token check and restart.
     */
    public static final class SourceChoice {
        /** {@code true} = compute here and never call the server. Default until a token shows up. */
        @Shareable(Kind.BOOL)
        public boolean preferLocal = true;

        /** Set once, when the auto-switch to the server has happened. Never reset by the mod. */
        @Shareable(Kind.BOOL)
        public boolean pinned = false;
    }

    public static final class PriceHistorySettings {
        /**
         * GLFW key that opens the Item Price History view ({@code 0} = unbound). Over an item in a
         * container it opens that item's chart in a movable overlay; pressed in-world (no screen) it
         * opens the fixed full-screen view with the general item search.
         */
        @Shareable(Kind.KEYCODE)
        public int openKey = 0;

        /**
         * GLFW key that opens the Item Value window for the hovered item ({@code 0} = unbound):
         * every applied modifier (stars, books, gems, ...) is priced and totalled in a movable,
         * resizable table.
         */
        @Shareable(Kind.KEYCODE)
        public int valueKey = 0;

        /**
         * GLFW key that opens the Similar Auctions window for the hovered item ({@code 0} =
         * unbound): live identical/similar auctions (click = open) plus the sale history with
         * time-to-sell, appraised by the cloud API.
         */
        @Shareable(Kind.KEYCODE)
        public int similarAuctionsKey = 0;
    }

    public static final class ApiSettings {
        /**
         * Hypixel developer API key, sent as the {@code API-Key} header on every Hypixel request
         * (higher rate limits). Empty by default: no key ships with this open-source build – a
         * hardcoded key would be public and instantly abused/rate-limited. Every endpoint the mod
         * uses also works keyless (just lower limits); users may paste their own key in the config.
         */
        public String hypixelApiKey = "";
    }

    /**
     * Keybinds for the hidden dev tools. Defaults are numpad keys (unlikely to clash with normal play)
     * and they are inert unless {@code DevMode.ACTIVE}. GLFW key codes, matching {@code KeyEvent.key()}.
     */
    public static final class DevSettings {
        /** Layout Recorder: store every unique Hypixel screen layout in Development_Stuff/layouts. */
        public boolean recordLayouts = false;

        /** Layout Recorder: variants kept per menu before it warns instead of writing more. */
        public int layoutVariantCap = 20;

        /** Layout Recorder: distinct origins (where/how a screen was opened) kept per layout file. */
        public int layoutOriginCap = 16;

        /** Layout Recorder: total size of the layouts folder, in MB, past which nothing new is written. */
        public int layoutMaxMb = 50;

        /** Numpad 9 – opens the name GUI, then scans the room. */
        public int scanRoomKey = 329;

        /** Numpad 7 – opens the name GUI, then saves a "standing" waypoint at the player. */
        public int waypointStandingKey = 327;

        /** Numpad 8 – opens the name GUI, then saves a "looking" waypoint at the crosshair block. */
        public int waypointLookingKey = 328;

        /** Numpad 6 – toggles the secret recorder for the room you are standing in (start/stop). */
        public int scanSecretsKey = 326;

        /** Numpad 3 – saves the block at your legs as an "item" secret of the current room. */
        public int saveItemSecretKey = 323;

        /** Numpad 4 - "what I am looking at is the current objective" (quest capture). */
        public int questObjectiveKey = 324;

        /** Numpad 5 - capture the open menu (quest capture). */
        public int questMenuKey = 325;

        /**
         * Numpad 2 - capture the scoreboard and tab list (quest capture).
         *
         * <p>Not Numpad 6 or 7: 326 is the secret recorder and 327 is the standing waypoint. This
         * field held 327 briefly and was therefore unreachable - {@code DevKeybinds} is an
         * {@code else if} chain and the waypoint key is tested first, so the capture never fired.
         */
        public int questStateKey = 322;
    }

    public static final class ScrollableTooltipsSettings {
        /**
         * Master On/Off toggle. When off, tooltips render exactly as vanilla; when on, tooltips
         * taller than the screen are trimmed to a fitting page that scrolls with the mouse wheel.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;
    }

    /**
     * How many rounds of "this now ships on by default" this config has been through.
     *
     * <p>A changed Java initialiser only ever reaches a player with <b>no</b> {@code config.json},
     * and {@link ConfigManager} re-writes every field on every load - so by the time a default is
     * changed, nobody who already plays the mod can be reached by it. This counter is how a new
     * shipped default is adopted once into configs that already exist, and never again.
     *
     * <p>Deliberately a single mod-wide number rather than one flag per feature: each bump is one
     * release's worth of "what a fresh install now looks like", applied together.
     */
    public int defaultsAdopted;

    /** Custom Scoreboard module preferences (fully styleable replacement for the vanilla sidebar). */
    public CustomScoreboardSettings customScoreboard = new CustomScoreboardSettings();

    /**
     * Custom Scoreboard (Visuals): replaces Hypixel's plain sidebar with a fully styleable one.
     *
     * <p>Everything is applied live from these fields on the very next frame – there is no cached
     * render state and no restart, so a toggle or a colour change shows instantly. Position and
     * scale live in the shared GUI editor via {@link HudTransform} (element {@code custom_scoreboard}),
     * so this class only carries look (colours, background, border) and content (which lines show and
     * in what order).
     *
     * <p><b>Line identity.</b> The layout is keyed on <i>what a row is</i>, never on what it says:
     * every sidebar line is classified into a stable element id (LOCATION, PURSE, BITS...) by
     * {@link sbs.modid.client.helper.scoreboard.ScoreboardElements}, and {@link #elementOrder} /
     * {@link #hiddenElements} store those ids. A row may rewrite its words as often as it likes -
     * the season turning over, a new lobby id, walking to another island - and it keeps the slot it
     * was given, because its type did not change.
     *
     * <p>The two {@code line*} fields below are the superseded text-keyed form, migrated once on load.
     */
    public static final class CustomScoreboardSettings {

        /**
         * The stock value of each colour picker below. A field still holding its stock value is one
         * the player never picked, which is what lets the renderer tell "leave this to the SBS theme"
         * apart from "the player chose exactly this colour" – so a picked colour survives a re-theme
         * and a style switch. Static, hence never written to {@code config.json}.
         */
        public static final String STOCK_LINE_COLOR = "FFFFFF";
        public static final String STOCK_TITLE_COLOR = "3FB4FF";
        public static final String STOCK_BACKGROUND_COLOR = "0B2138";
        public static final String STOCK_BORDER_COLOR = "3FB4FF";

        /**
         * Master switch: draw the SBS scoreboard instead of the vanilla sidebar. On by default, in
         * the layout {@link sbs.modid.client.helper.scoreboard.ScoreboardLayout#defaultOrder} ships.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Show the objective title (the top line, e.g. "SKYBLOCK"). */
        @Shareable(Kind.BOOL)
        public boolean showTitle = true;

        /** Override the server title with your own text; empty = use the server's title. */
        public String customTitle = "";

        /** Keep Hypixel's per-line colours; when false every body line uses {@link #lineColorHex}. */
        @Shareable(Kind.BOOL)
        public boolean useOriginalColors = true;

        /**
         * Paint each line entirely in one colour - the one Hypixel gave its <i>value</i> - instead of
         * leaving the label a different colour from the number after it ("Bits:" plain with an aqua
         * count becomes an aqua row). Only meaningful while {@link #useOriginalColors} is on, since
         * the single-colour mode below already does this to everything.
         */
        @Shareable(Kind.BOOL)
        public boolean solidLineColors = false;

        /** Body-line colour as {@code RRGGBB} when {@link #useOriginalColors} is off. */
        @Shareable(Kind.HEX_COLOR)
        public String lineColorHex = STOCK_LINE_COLOR;

        /** Title colour as {@code RRGGBB}. */
        @Shareable(Kind.HEX_COLOR)
        public String titleColorHex = STOCK_TITLE_COLOR;

        /** Background colour as {@code RRGGBB}; its opacity is {@link #backgroundOpacity}. */
        @Shareable(Kind.HEX_COLOR)
        public String backgroundColorHex = STOCK_BACKGROUND_COLOR;

        /** Background opacity in percent (0 = fully transparent, 100 = solid). */
        public int backgroundOpacity = 70;

        /** Draw a 1px border around the panel. */
        @Shareable(Kind.BOOL)
        public boolean showBorder = true;

        /** Border colour as {@code RRGGBB}. */
        @Shareable(Kind.HEX_COLOR)
        public String borderColorHex = STOCK_BORDER_COLOR;

        /** Border opacity in percent (0-100). */
        public int borderOpacity = 55;

        /** Border thickness in pixels (1-3); only drawn while {@link #showBorder} is on. */
        public int borderWidth = 1;

        /** Drop shadow behind the text (vanilla scoreboard look); off reads cleaner on a solid panel. */
        @Shareable(Kind.BOOL)
        public boolean textShadow = true;

        /**
         * Trace every line in black on all four sides instead of dropping one shadow behind it. Costs
         * four extra draws per row, which is why it is off by default - but it is the only thing that
         * keeps light text readable over snow or a lit-up Dwarven Mines wall at low background opacity.
         */
        @Shareable(Kind.BOOL)
        public boolean textOutline = false;

        /** Rounded-corner radius of the panel in pixels (0 = square). */
        public int cornerRadius = 4;

        /** Which edge the body lines line up against. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.helper.scoreboard.ScoreboardAlignment.class)
        public sbs.modid.client.helper.scoreboard.ScoreboardAlignment alignment =
                sbs.modid.client.helper.scoreboard.ScoreboardAlignment.LEFT;

        /** Which edge the title lines up against - centred, the way Hypixel writes its own heading. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.helper.scoreboard.ScoreboardAlignment.class)
        public sbs.modid.client.helper.scoreboard.ScoreboardAlignment titleAlignment =
                sbs.modid.client.helper.scoreboard.ScoreboardAlignment.CENTER;

        /** How the big values are written: as the server sent them, {@code 12.7M}, or {@code 12,700,000}. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.util.NumberTextFormat.class)
        public sbs.modid.client.core.util.NumberTextFormat numberFormat =
                sbs.modid.client.core.util.NumberTextFormat.AUTO;

        /** Extra pixels between body lines, on top of the font's own line height (0-10). */
        public int lineSpacing = 1;

        /** Pixels between the title and the first body line (0-12). */
        public int titleSpacing = 3;

        /** Left/right padding inside the panel, in pixels (0-20). */
        public int paddingX = 5;

        /** Top/bottom padding inside the panel, in pixels (0-20). */
        public int paddingY = 4;

        /** Draw the title in bold. */
        @Shareable(Kind.BOOL)
        public boolean titleBold = false;

        /** Cycle the title through the colour wheel instead of painting it {@link #titleColorHex}. */
        @Shareable(Kind.BOOL)
        public boolean rainbowTitle = false;

        /** Collapse the empty spacer lines Hypixel pads the sidebar with. */
        @Shareable(Kind.BOOL)
        public boolean hideEmptyLines = false;

        /**
         * How many blank spacer rows may follow each other (0-5). Hypixel never sends five in a row,
         * so the default leaves the sidebar exactly as it arrives; lowering it tightens the panel
         * without losing the blocks entirely, which is what {@link #hideEmptyLines} does.
         */
        public int maxEmptyLines = 5;

        /**
         * Append a "God Potion" row with the remaining time while one is active. On by default: the
         * sidebar is already the thing the eye goes to for status, so a buff timer belongs in it
         * rather than in a card of its own somewhere else on the screen.
         */
        @Shareable(Kind.BOOL)
        public boolean showGodPotion = true;

        /** Append a "Cookie Buff" row with the remaining time while the buff is active. */
        @Shareable(Kind.BOOL)
        public boolean showCookieBuff = true;

        /**
         * Add a "Bank" row with your bank balance, read off the tab list and placed directly under
         * the server's Purse line.
         *
         * <p>On by default, along with the four rows below it: they are the numbers the sidebar
         * <i>cannot</i> tell you, and the shipped layout is built around having them. Each is still
         * a switch - turning one off leaves no gap, because the layout is keyed on elements rather
         * than on positions.
         */
        @Shareable(Kind.BOOL)
        public boolean showBank = true;

        /**
         * Add a "Gems" row with your gem count, read off the tab list and placed directly under the
         * server's Bits line - the sidebar carries bits but never gems.
         */
        @Shareable(Kind.BOOL)
        public boolean showGems = true;

        /**
         * Add an "Interest" row - when the bank pays out next, and how much - directly under the
         * Bank row it belongs to. Falls to the bottom block when the Bank row is off.
         */
        @Shareable(Kind.BOOL)
        public boolean showInterest = true;

        /** Add a "Profile" row with the SkyBlock profile name, read off the tab list. */
        @Shareable(Kind.BOOL)
        public boolean showProfile = true;

        /** Add an "SB Level" row with your SkyBlock level and its XP progress, read off the tab list. */
        @Shareable(Kind.BOOL)
        public boolean showSbLevel = true;

        /**
         * Spell the buff rows' units out ("3 years, 2 months") instead of the short form
         * ("3y 2mon"). Off by default: a sidebar row is read at a glance and the short form keeps
         * the panel from growing a third wider just to say the same thing.
         */
        @Shareable(Kind.BOOL)
        public boolean buffTimersLongForm = false;

        /**
         * Add a row with the real-world clock, as the first line under the title. The one thing a
         * SkyBlock sidebar cannot tell you is what time it is where you are sitting, and
         * full-screen play hides the system clock.
         */
        @Shareable(Kind.BOOL)
        public boolean showRealTime = true;

        /**
         * Write the clock row as {@code 9:05 PM} rather than {@code 21:05}. On by default: this row
         * stands in for the system clock a full-screen game covers, and that is the reading most
         * players are used to seeing there.
         */
        @Shareable(Kind.BOOL)
        public boolean realTime12Hour = true;

        /**
         * Drop the server's own advertising line ({@code www.hypixel.net}) from the panel. It is the
         * one sidebar row that never says anything - you already know which server you are on - and
         * it costs a full line plus the spacer above it.
         */
        @Shareable(Kind.BOOL)
        public boolean hideWebsite = false;

        /** Add a row with the client's frame rate. */
        @Shareable(Kind.BOOL)
        public boolean showFps = false;

        /**
         * Add a row with the measured server ping, coloured by how bad it is. Uses the same real
         * round-trip measurement the Server Stats card does, not the tab list's number.
         */
        @Shareable(Kind.BOOL)
        public boolean showPing = false;

        /**
         * Add a row saying whether you have voted in the running mayor election. Only while an
         * election is actually running - the rest of the SkyBlock year it reserves no space.
         */
        @Shareable(Kind.BOOL)
        public boolean showMayorVote = true;

        /**
         * Element ids in the player's chosen order - the layout the editor writes.
         *
         * <p>Empty means "never customised", which is not the same as "customised to nothing": the
         * panel then leaves the server's own order exactly as it arrived. An id listed here is drawn
         * at this position whether or not the row exists right now, so a dungeon-only row keeps its
         * slot in the Hub instead of shuffling everything below it when the run starts.
         *
         * <p>Anything the classifier produces that is <b>not</b> listed here is drawn at the
         * {@code unrecognized} slot, wherever the player put it - never appended at the bottom.
         */
        public List<String> elementOrder =
                sbs.modid.client.helper.scoreboard.ScoreboardLayout.defaultOrder();

        /** Element ids the player took out of the layout; they are not drawn at all. */
        public List<String> hiddenElements =
                sbs.modid.client.helper.scoreboard.ScoreboardLayout.defaultHidden();

        /**
         * Which layout format the stored fields are in. {@code 0} is the old text-keyed pair below,
         * {@code 1} the element ids above; {@link sbs.modid.client.core.config.ConfigManager} upgrades
         * on load and stamps this.
         */
        public int layoutVersion;

        /**
         * Set by the migration when part of an old layout could not be mapped to an element, so the
         * player is told once rather than finding a silently rearranged scoreboard. Cleared as soon
         * as the notice has been shown.
         */
        @Shareable(Kind.BOOL)
        public boolean layoutResetNotice;

        /**
         * Line signatures the player switched off, in the superseded text-keyed form.
         *
         * @deprecated migrated into {@link #hiddenElements} on load. Null default on purpose: an
         *         absent list has to be distinguishable from an empty one, or a config written by
         *         this build would look like an old one with everything shown.
         */
        @Deprecated
        public List<String> hiddenLines;

        /**
         * Line signatures in the player's order, in the superseded text-keyed form.
         *
         * @deprecated migrated into {@link #elementOrder} on load. See {@link #hiddenLines} for why
         *         the default is null.
         */
        @Deprecated
        public List<String> lineOrder;
    }

    /** Custom Skin module preferences (make an item look like another item, client-side only). */
    public CustomSkinSettings customSkin = new CustomSkinSettings();

    /**
     * Custom Skin (Inventory & Items): give any item the look of any other Hypixel or vanilla item.
     *
     * <p>Purely cosmetic and purely local – the skin is re-applied on every render instead of being
     * written into the stack, so it survives restarts and server swaps and the server never sees it.
     * Names, lore and stats are untouched: the model swap happens inside the item model resolver,
     * which tooltips never pass through.
     *
     * <p><b>Item identity.</b> Keys are {@code SkyblockItem.uniqueKey}, the same identity the Item
     * Renamer uses: the SkyBlock {@code uuid} when the item has one, so a skin follows that one
     * weapon; for plain items without SkyBlock data the key falls back to the name, and the skin then
     * applies to every item with that name.
     */
    public static final class CustomSkinSettings {

        /** Master switch: apply saved skins while rendering. */
        public boolean enabled = false;

        /** Hotkey that opens the picker for the held item ({@code 0} = unbound). */
        public int openKey = 0;

        /**
         * Apply armor skins to the player model too, not only to the icon. On by default because
         * that is the point of skinning armor; the renderer still only swaps a worn piece for
         * another piece of the same slot, since armor is drawn from equipment textures that
         * weapons and blocks simply do not have.
         */
        public boolean applyToWornArmor = true;

        /** Item identity → the look it should render with. */
        public Map<String, sbs.modid.client.helper.customskin.model.CustomSkin> skins = new LinkedHashMap<>();
    }

    /**
     * Player Notes preferences. The notes themselves are <b>not</b> here: they live in their own
     * file ({@code SBSFiles.playerNotesFile()}), so config sharing - which walks this class - can
     * never carry them.
     */
    public PlayerNotesSettings playerNotes = new PlayerNotesSettings();

    public static final class PlayerNotesSettings {
        /** Master toggle: off, nothing is checked and nothing is shown (the notes are kept). */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Warn when a noted player enters your party (join line or /pl roster). */
        @Shareable(Kind.BOOL)
        public boolean warnPartyJoin = true;

        /** Warn when a noted player is on your dungeon team (tab list, once per run start). */
        @Shareable(Kind.BOOL)
        public boolean warnDungeonTeam = true;

        /** Warn when a noted player comes within render distance. Off: busy hubs are noisy. */
        @Shareable(Kind.BOOL)
        public boolean warnNearby = false;

        /** A coloured tag in front of a noted player's nametag in the world. */
        @Shareable(Kind.BOOL)
        public boolean nametagMarker = false;

        /**
         * How a warning reaches you (an {@code AlertChannels} mask). The chat channel is the
         * warning line with its [NOTE]/[KICK] buttons; the rest go through the shared alert path.
         */
        public int channels = 1 | (1 << 3);   // chat + sound
    }

    /** Museum Helper preferences. What was donated is per profile, in {@code museum.json}. */
    public MuseumHelperSettings museumHelper = new MuseumHelperSettings();

    public static final class MuseumHelperSettings {
        /** Master toggle: off, the museum pages are not read and nothing is shown. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** "Museum: not donated · +N XP" on item tooltips, for fully seen categories. */
        @Shareable(Kind.BOOL)
        public boolean tooltipLine = true;

        /** A small marker on undonated items in open containers (not the Auction House). */
        @Shareable(Kind.BOOL)
        public boolean highlightContainers = true;

        /** The same marker in the Auction House browse view. */
        @Shareable(Kind.BOOL)
        public boolean highlightAuctions = true;

        /** Missing-donations screen sort: 0 XP per coin, 1 XP, 2 price. View state, not shared. */
        public int sort = 0;
    }

    /** Equipment in the inventory. The captured pieces are per profile, in {@code equipment.json}. */
    public EquipmentDisplaySettings equipmentDisplay = new EquipmentDisplaySettings();

    public static final class EquipmentDisplaySettings {
        /**
         * Master toggle: the Equipment menu is read and the column is drawn beside the armor. Off
         * by default until the menu layout has been probed in game.
         */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** A capture older than this many hours gets the stale marker. */
        public int staleHours = 24;
    }

    /** Bingo Card Overlay preferences. The card itself is per profile, in {@code bingo.json}. */
    public BingoSettings bingo = new BingoSettings();

    public static final class BingoSettings {
        /** Master toggle. Off until the menu is verified: every shape it reads is ESTIMATED. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Also list finished goals, struck through. */
        @Shareable(Kind.BOOL)
        public boolean showCompleted = false;

        /** List community goals with their progress line. */
        @Shareable(Kind.BOOL)
        public boolean showCommunity = true;
    }

    /** Trophy Fish (Skills): every trophy fish x tier, what is missing, and this session's catches. */
    public TrophyFishSettings trophyFish = new TrophyFishSettings();

    public static final class TrophyFishSettings {
        /** Master toggle. Off by default: both data sources are still unverified in game. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Count catches from the chat line between menu visits. Off: the line is unverified. */
        @Shareable(Kind.BOOL)
        public boolean chatCounting = false;

        /** The fish x tier grid card. */
        @Shareable(Kind.BOOL)
        public boolean gridHud = true;

        /** Grid card lists only fish with a missing tier, and which tiers. */
        @Shareable(Kind.BOOL)
        public boolean compact = false;

        /** This session's catches, active time and rate. */
        @Shareable(Kind.BOOL)
        public boolean sessionHud = true;

        /** Show the cards only on the Crimson Isle. */
        @Shareable(Kind.BOOL)
        public boolean onlyCrimsonIsle = true;

        /** Alert on the first Gold or Diamond of a fish (needs one menu sync to know "first"). */
        @Shareable(Kind.BOOL)
        public boolean alertNewTier = true;

        /** Alert on every Diamond catch. */
        @Shareable(Kind.BOOL)
        public boolean alertAnyDiamond = false;

        /** Channels for both alerts ({@code AlertChannels} bit mask). */
        @Shareable(value = Kind.INT, min = 0, max = 63)
        public int alertChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;
    }

    /** Mayor Vote Reminder preferences. */
    public MayorVoteSettings mayorVote = new MayorVoteSettings();

    /**
     * Mayor Vote Reminder (Economy): remembers whether you voted in the running election and says so
     * until you have. State is per account + SkyBlock profile; only the settings live here.
     */
    public static final class MayorVoteSettings {

        /** Master switch. With this off nothing is tracked, reminded or drawn. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /** Whether the reminder is announced at all, or the feature is only the sidebar row. */
        @Shareable(Kind.BOOL)
        public boolean remind = true;

        /** Minutes between repeats of the reminder while an election runs and you have not voted. */
        public int repeatMinutes = 30;

        /**
         * Channels the reminder uses <b>on top of</b> its chat line (an {@code AlertChannels} mask).
         * Chat is always in the mask - the chat notification is the feature.
         */
        public int extraChannels = 0;
    }

    /** SBS Tab-List module preferences (the player list restyled as a themed SBS panel). */
    public TabListSettings tabList = new TabListSettings();

    /**
     * SBS Tab-List (Interface): restyles the player list shown while the tab key is held. The
     * content stays exactly vanilla (same entries, names, header/footer, scores); only the paint
     * changes, and it follows the SBS theme and the active UI style. Everything is read live each
     * frame, so a slider change shows immediately.
     */
    public static final class TabListSettings {

        /** {@link #pingDisplay} values: vanilla connection bars / numeric ms / nothing. */
        public static final int PING_BARS = 0;
        public static final int PING_NUMBER = 1;
        public static final int PING_HIDDEN = 2;

        /** Master switch: draw the SBS tab list instead of the vanilla player list. On by default. */
        @Shareable(Kind.BOOL)
        public boolean enabled = true;

        /**
         * Panel + row-cell opacity in percent (0 = invisible, 100 = solid). Deliberately low by
         * default: the SBS tab list floats over the world instead of blacking half the screen out.
         */
        public int backgroundOpacity = 8;

        /** Text/head/ping opacity in percent (0 = invisible, 100 = solid). */
        public int textOpacity = 100;

        /** The 8x8 skin face in front of each name. */
        @Shareable(Kind.BOOL)
        public boolean showHeads = true;

        /**
         * Draw the skin face only on rows that are <b>actually players</b>
         * ({@link sbs.modid.client.core.tab.TabWidgets#isPlayer}).
         *
         * <p>On by default because on Hypixel most rows are not players: the side columns are fake
         * entries carrying the Info / Skills / Stats widgets, and every one of them was being given
         * the same default Steve face, which is what made the columns read as a crowd of strangers
         * instead of as text. Off restores the vanilla behaviour of a face on every row.
         */
        public boolean headsPlayersOnly = true;

        /**
         * Show the ping only on rows that are actually players, on the same test as
         * {@link #headsPlayersOnly}. On by default, and the reason {@link #hidePing} no longer has
         * to be: the bars beside "Fairy Souls: 80/80" were the whole problem, not the bars.
         */
        public boolean pingPlayersOnly = true;

        /**
         * Drops the connection bars from the player list entirely, and the one tab-list option that
         * applies to the <b>vanilla</b> list as well as the SBS one.
         *
         * <p><b>Off by default now.</b> It was on because on Hypixel most rows are not players - the
         * side columns are fake entries carrying the Info / Skills / Stats widgets, and a
         * signal-strength icon next to "Fairy Souls: 80/80" measures nothing - so the only way to be
         * rid of those was to be rid of all of them, ping on real players included.
         * {@link #pingPlayersOnly} answers that properly, which leaves this as what its name says:
         * a switch for people who want no ping anywhere. An existing {@code config.json} keeps
         * whatever its owner set; only a fresh one starts off.
         */
        @Shareable(Kind.BOOL)
        public boolean hidePing = false;

        /** One of the {@code PING_*} constants above. Only consulted while {@link #hidePing} is off. */
        public int pingDisplay = PING_BARS;

        /** The server's header text above the columns. */
        @Shareable(Kind.BOOL)
        public boolean showHeader = true;

        /** The server's footer text below the columns. */
        @Shareable(Kind.BOOL)
        public boolean showFooter = true;

        /** Drop shadow behind all text - keeps it readable with the background faded out. */
        @Shareable(Kind.BOOL)
        public boolean textShadow = true;

        /** A 1px accent outline around the panel. */
        @Shareable(Kind.BOOL)
        public boolean showBorder = false;

        /** Outline opacity in percent (0-100). */
        public int borderOpacity = 35;

        /**
         * A cell drawn behind every grid row. Off: the rows sit straight on the panel, which is what
         * makes a four-column SkyBlock tab read as four blocks of text rather than eighty boxes.
         * The cells were the only thing {@link #backgroundOpacity} used to paint besides the panel,
         * so turning them off does not lose a setting - it stops one being applied twice.
         */
        public boolean rowCells = false;

        /** A 1px vertical rule between the grid's columns, in the theme accent. */
        public boolean columnDividers = true;

        /** Divider opacity in percent (0-100). */
        public int dividerOpacity = 30;

        /**
         * The client-stats strip above the columns: which instance you are on, the measured tick
         * rate, the frame rate and the real round-trip ping.
         *
         * <p>Ours, not Hypixel's - none of these four numbers is in the tab list the server sends.
         * They are the values the Server Stats card and the Custom Scoreboard already read, put
         * where you are already looking whenever you hold tab.
         */
        public boolean statsHeader = true;

        /** Which of the four stats the strip shows. All four, or the strip is not worth its row. */
        public boolean statsServer = true;
        public boolean statsTps = true;
        public boolean statsFps = true;
        public boolean statsPing = true;
    }

    /** Garden Blueprint module preferences (copy a plot, rebuild it from a 3D ghost overlay). */
    public GardenBlueprintSettings gardenBlueprint = new GardenBlueprintSettings();

    /** Build Tools: selection stick, /.. commands, schematic library, holograms. */
    public BuildToolsSettings buildTools = new BuildToolsSettings();

    /** Cinematic freecam: a pure flying camera for videos, independent of Build Tools. */
    public CinematicCameraSettings cinematicCamera = new CinematicCameraSettings();

    /**
     * Cinematic Camera: the freecam without any selection, for filming. Its own switch, so it works
     * with Build Tools off; the same safety gates as build freecam, with their own settings here.
     */
    public static final class CinematicCameraSettings {
        /** Master switch: the key and {@code /sbs freecam} do nothing while off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Toggles cinematic freecam ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int key = 0;

        /** Hides the whole HUD - vanilla and SBS cards - like F1, while flying. */
        @Shareable(Kind.BOOL)
        public boolean hideHud = true;

        /** With the HUD hidden, still draw SBS world markers (holograms, waypoints, highlights). */
        @Shareable(Kind.BOOL)
        public boolean showWorldMarkers = false;

        /** Acceleration / deceleration glide, 0 (instant) to 95 (longest). */
        public int movementSmoothing = 70;

        /** Mouse look through vanilla's cinematic camera smoothing. */
        @Shareable(Kind.BOOL)
        public boolean mouseSmoothing = true;

        /** Finer, slower speed steps on the wheel. */
        @Shareable(Kind.BOOL)
        public boolean fineSpeeds = true;

        /** The camera passes through blocks. */
        @Shareable(Kind.BOOL)
        public boolean noclip = true;

        /** On servers at all. Off by default; the first switch-on shows a warning. */
        @Shareable(Kind.BOOL)
        public boolean multiplayer = false;

        /** Whether the multiplayer warning has been shown. */
        public boolean warned = false;

        // "allIslands" (lift the island limit) was removed 2026-09-28: servers allow only the own
        // Private Island and Gardens. Old configs still carrying it load fine - Gson skips unknown keys.

        /** On servers the camera stays within this many blocks of you (4-128). */
        public int range = 32;

        /** In singleplayer, an optional range limit; 0 = none. */
        public int rangeSingleplayer = 0;

        /** On servers, players, mobs, stands and drops are not drawn. Singleplayer shows everything. */
        @Shareable(Kind.BOOL)
        public boolean hideEntities = true;
    }

    /**
     * Build Tools (Quality of Life): select with the Magic Stick Thingy or the corner keys, copy,
     * save to the library, and show any saved build as a hologram. World edits exist only in
     * singleplayer; on a server the feature reads and draws, nothing more.
     *
     * <p>Like Garden Blueprint, no build is ever stored here - builds live in
     * {@code config/sbs/schematics/}. Only the look, the keys and the edit pacing persist.
     */
    public static final class BuildToolsSettings {
        /** Master switch: commands, keys, the wand and the hologram are inert while off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Sets selection corner 1 at the block you look at ({@code 0} = unbound). Works everywhere. */
        @Shareable(Kind.KEYCODE)
        public int corner1Key = 0;

        /** Sets selection corner 2 at the block you look at ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int corner2Key = 0;

        /** Opens the Quick Paste grid of saved builds ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int quickPasteKey = 0;

        /** Singleplayer edits show their result as a hologram first; Enter applies, Esc cancels. */
        @Shareable(Kind.BOOL)
        public boolean previewEdits = true;

        /** Blocks changed per game tick by a singleplayer edit (1000-100000). */
        public int blocksPerTick = 20_000;

        /**
         * Live selection preview: holding the Magic Stick Thingy with corner 1 set, the box to the
         * block under the crosshair is drawn every frame (filled, outlined, sized) until corner 2.
         */
        @Shareable(Kind.BOOL)
        public boolean livePreview = true;

        /** Colour ({@code RRGGBB}) of the live box while corner 2 is still being chosen. */
        @Shareable(Kind.HEX_COLOR)
        public String previewColorHex = "7FD4FF";

        /** Fill opacity of the selection and hologram boxes in percent (0-60); outlines stay solid. */
        public int previewOpacity = 18;

        /** Outline + name of the block the stick (or freecam) targets. */
        @Shareable(Kind.BOOL)
        public boolean showTargetedBlock = true;

        /** How far the camera ray reaches for the stick and //pos1 //pos2 in freecam (8-128). */
        public int rayDistance = 64;

        /** Draw the selection only while the stick is held or a build key/command was just used. */
        @Shareable(Kind.BOOL)
        public boolean hideSelectionUnlessHeld = true;

        /** Clears the selection while the stick is held (default Backspace; never acts otherwise). */
        @Shareable(Kind.KEYCODE)
        public int clearSelectionKey = 259;

        /** Opens the Build Library screen ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int libraryKey = 0;

        /** The Magic Stick help card: commands and keys for what you are doing. On by default. */
        @Shareable(Kind.BOOL)
        public boolean helpCard = true;

        /** The help card as one line of the most useful keys. */
        @Shareable(Kind.BOOL)
        public boolean helpCompact = false;

        /** Toggles freecam ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int freecamKey = 0;

        /** Freecam on servers at all. Off by default; the first switch-on shows a warning. */
        @Shareable(Kind.BOOL)
        public boolean freecamMultiplayer = false;

        /** Whether the multiplayer warning has been shown (it is shown once). */
        public boolean freecamWarned = false;

        // "freecamAllIslands" was removed 2026-09-28, like cinematicCamera.allIslands.

        /** On servers the camera stays within this many blocks of you (4-128). */
        public int freecamRange = 32;

        /** In singleplayer, an optional range limit; 0 = none. */
        public int freecamRangeSingleplayer = 0;

        /** On servers, players, mobs, stands and drops are not drawn from the freecam view. */
        @Shareable(Kind.BOOL)
        public boolean freecamHideEntities = true;

        /** The camera passes through blocks. */
        @Shareable(Kind.BOOL)
        public boolean freecamNoclip = true;

        /** The selection box's size label ({@code W×H×L · N blocks}). */
        @Shareable(Kind.BOOL)
        public boolean sizeLabel = true;

        /** Only hologram blocks within this many blocks of you are drawn (4-64). */
        public int renderRadius = 24;

        /** Outline opacity of hologram blocks in percent (10-100). */
        public int edgeOpacity = 70;

        /** Outline thickness in pixels (1-4). */
        public int lineWidth = 2;

        /** Real, see-through block models for blocks still to place. */
        @Shareable(Kind.BOOL)
        public boolean ghostModels = true;

        /** Opacity of those models in percent (20-100; the pipeline drops anything fainter). */
        public int modelOpacity = 55;

        /** Flat colour where no model is drawn. */
        @Shareable(Kind.BOOL)
        public boolean fillGhosts = true;

        /** Fill opacity in percent (5-80). */
        public int fillOpacity = 25;

        /** Also outline blocks that already match. */
        @Shareable(Kind.BOOL)
        public boolean showCorrect = false;

        /** Colour ({@code RRGGBB}) of the selection box. */
        @Shareable(Kind.HEX_COLOR)
        public String selectionColorHex = "FFE24B";

        /** Colour of a hologram block still to place. */
        @Shareable(Kind.HEX_COLOR)
        public String ghostColorHex = "3FB4FF";

        /** Colour of a block a paste would overwrite, or a wrong block while building along. */
        @Shareable(Kind.HEX_COLOR)
        public String collisionColorHex = "FF2020";

        /** Colour of a block that already matches. */
        @Shareable(Kind.HEX_COLOR)
        public String correctColorHex = "30E030";

        /** Colour of a block an edit adds or changes, in its preview. */
        @Shareable(Kind.HEX_COLOR)
        public String addedColorHex = "30E030";

        /** Colour of a block an edit removes, in its preview. */
        @Shareable(Kind.HEX_COLOR)
        public String removedColorHex = "FF2020";
    }

    /**
     * Garden Blueprint (Skills): press a key to copy the 96x96 plot you stand on, then rebuild it
     * anywhere from a transparent 3D ghost that marks each block right (green) or wrong (red) live.
     *
     * <p>The captured blueprint itself is intentionally <b>not</b> stored here – it can be hundreds of
     * thousands of block states and belongs in memory (or, once saved, in the build library under
     * {@code config/sbs/schematics/}), not in {@code config.json}. Only the look and the keys persist;
     * see {@code sbs.modid.client.skills.garden.logic.GardenBlueprintManager}.
     */
    /** Garden Helpers module: visitor refuse-guard + composter status overlay. */
    public GardenHelpersSettings gardenHelpers = new GardenHelpersSettings();

    public static final class GardenHelpersSettings {
        /** Refusing a visitor whose offer contains a valuable reward needs Ctrl+Shift on the click. */
        @Shareable(Kind.BOOL)
        public boolean visitorGuard = true;

        /** Gold box + "★ Visitor · reward" over a visitor whose opened offer has a valuable reward. */
        @Shareable(Kind.BOOL)
        public boolean visitorHighlight = true;

        /** A small "? Visitor · open to check" over a visitor whose menu was never opened. */
        @Shareable(Kind.BOOL)
        public boolean visitorHighlightUnknown = true;

        /** A separate mark on Legendary-and-up visitors, read off their name colour. */
        @Shareable(Kind.BOOL)
        public boolean visitorHighlightRarity = false;

        /** The valuable-visitor highlight colour as hex; empty = gold. */
        @Shareable(Kind.HEX_COLOR)
        public String visitorHighlightColorHex = "";

        /** Greenhouse research: log greenhouse chat/tab/menus/zones under [SBS][Greenhouse]. */
        @Shareable(Kind.BOOL)
        public boolean greenhouseCapture = false;

        /** One "Bazaar: <item> ×N" button per item a visitor wants, beside the visitor's menu. */
        @Shareable(Kind.BOOL)
        public boolean visitorBazaarButtons = true;

        /** The insta-buy cost for the amount on each of those buttons (cached prices only). */
        @Shareable(Kind.BOOL)
        public boolean visitorBazaarPrice = true;

        /** Visitor shopping list: record each visitor's offer when its menu opens (per profile). */
        @Shareable(Kind.BOOL)
        public boolean visitorShoppingList = true;

        /** The shopping list as a HUD card on the Garden (the chat list works without it). */
        @Shareable(Kind.BOOL)
        public boolean visitorShoppingHud = true;

        /** Composter status card (matter / fuel / compost), fed by opening the Composter menu. */
        @Shareable(Kind.BOOL)
        public boolean composterOverlay = true;

        /** Farming Tracker card: fortune / overbloom / pest chance (tab widgets) + crop profit. */
        @Shareable(Kind.BOOL)
        public boolean farmingTracker = true;

        /** Pest Profit card: pests killed, their drops and value, per session and per profile. */
        @Shareable(Kind.BOOL)
        public boolean pestProfit = true;

        /** List kills per pest type on the Pest Profit card. */
        @Shareable(Kind.BOOL)
        public boolean pestProfitBreakdown = true;

        /** Count what emptied Pest Traps pay out in the Pest Profit totals (ESTIMATED detection). */
        @Shareable(Kind.BOOL)
        public boolean pestProfitTrapLoot = true;

        /** Farming Session Summary: crops, coins, pests and rare drops per session, vs the last one. */
        @Shareable(Kind.BOOL)
        public boolean farmingSession = true;

        /** A gap between crop breaks longer than this (seconds) is a pause and adds no active time. */
        @Shareable(value = Kind.INT, min = 5, max = 600)
        public int farmingSessionPauseSeconds = 60;

        /** No crop broken for this many minutes ends the session. */
        @Shareable(value = Kind.INT, min = 1, max = 120)
        public int farmingSessionEndMinutes = 10;

        /** Open the summary when a session ends (only while no other screen is open). */
        @Shareable(Kind.BOOL)
        public boolean farmingSessionAutoShow = true;

        /** Also post a one-line summary in your own chat (never sent to the server). */
        @Shareable(Kind.BOOL)
        public boolean farmingSessionChat = false;

        /** How many finished sessions are kept per profile. */
        @Shareable(value = Kind.INT, min = 1, max = 50)
        public int farmingSessionHistory = 10;

        /** Visitor Timer card: waiting visitors and when the next one arrives, from the tab widget. */
        @Shareable(Kind.BOOL)
        public boolean visitorTimer = true;

        /** Alert channels for "the visitor queue just became full". Title + sound by default. */
        public int visitorFullChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;

        /** Alert channels for "a new visitor arrived" (the waiting count went up). Off by default. */
        public int visitorArrivedChannels = 0;
    }

    public static final class GardenBlueprintSettings {
        /** Master switch for the module (keys and ghost render are inert while off). */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Key that copies the plot the player is standing on ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int copyKey = 0;

        /** Key that shows / hides the ghost preview of the saved blueprint ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int toggleKey = 0;

        /**
         * Key that pins the copied blueprint to the block you are looking at, so the ghost is drawn
         * from there instead of at the plot / spot it was captured at ({@code 0} = unbound).
         */
        @Shareable(Kind.KEYCODE)
        public int placeKey = 0;

        /** Only copy / show while in the Garden (guards against firing on unrelated islands). */
        @Shareable(Kind.BOOL)
        public boolean onlyInGarden = true;

        /** Blocks captured above the floor the player stands on (1-96). */
        public int captureHeight = 20;

        /** Blocks captured below the player's feet, so the ground layer is included (0-8). */
        public int captureDepth = 1;

        /** Only ghost blocks within this many blocks of the player, for performance (4-48). */
        public int renderRadius = 16;

        /** Outline thickness of the ghost boxes in pixels (1-4). */
        public int lineWidth = 2;

        /** Ghost opacity in percent (10-100). */
        public int opacity = 70;

        /** Fill each ghost block as a translucent cube, not just an outline. */
        @Shareable(Kind.BOOL)
        public boolean fillGhosts = true;

        /** Fill opacity of the translucent ghost cubes in percent (5-80); kept low so it reads as a ghost. */
        public int fillOpacity = 25;

        /**
         * Draw the block the blueprint wants as its real, translucent model in the world instead
         * of only a coloured box, so the block to place is
         * recognisable at a glance. Replaces the flat fill wherever a model is drawn.
         */
        @Shareable(Kind.BOOL)
        public boolean ghostModels = true;

        /**
         * Opacity of the ghost block models in percent (20-100). The floor is not cosmetic: the
         * translucent block pipeline discards fragments below alpha 0.1, so a lower value renders
         * nothing at all.
         */
        public int modelOpacity = 55;

        /**
         * Also ghost the wanted block over a <i>wrongly</i> placed one. Off by default: the mistake
         * already fills that space, so the two models overlap and the red box alone is clearer.
         */
        @Shareable(Kind.BOOL)
        public boolean ghostModelsOnWrong = false;

        /**
         * Custom-area mode: capture an arbitrary box you pick with two corners instead of the 96x96
         * plot. While on, the copy key captures the selection and
         * the ghost is anchored to where it was captured, not re-anchored to the plot you stand on.
         */
        @Shareable(Kind.BOOL)
        public boolean customArea = false;

        /** Key that sets the selection corners, alternating corner A / corner B ({@code 0} = unbound). */
        @Shareable(Kind.KEYCODE)
        public int cornerKey = 0;

        /** Colour ({@code RRGGBB}) of the custom-area selection box outline. */
        @Shareable(Kind.HEX_COLOR)
        public String selectionColorHex = "FFE24B";

        /** Colour ({@code RRGGBB}) of a block that still needs to be placed (world is air there). */
        @Shareable(Kind.HEX_COLOR)
        public String ghostColorHex = "3FB4FF";

        /** Colour ({@code RRGGBB}) of a wrongly placed block (e.g. Stone where Dirt belongs). */
        @Shareable(Kind.HEX_COLOR)
        public String errorColorHex = "FF2020";

        /** Colour ({@code RRGGBB}) of a correctly placed block. */
        @Shareable(Kind.HEX_COLOR)
        public String correctColorHex = "30E030";

        /** Also outline correctly placed blocks in green (off = only ghosts + errors show). */
        @Shareable(Kind.BOOL)
        public boolean showCorrect = true;
    }

    /** Year of the Pig (Skills): the Shiny Orb profit tracker and the pig-chase helpers. */
    public YearOfThePigSettings yearOfThePig = new YearOfThePigSettings();

    /**
     * Year of the Pig – the Shiny Pig event that comes round every 12 SkyBlock years.
     *
     * <p>Defaults are deliberately all-on behind an off master switch: the event is rare, so the
     * module sits inert almost all the time, and the one session it does run in is the worst
     * possible moment to be hunting for toggles.
     */
    public static final class YearOfThePigSettings {

        /** Master switch. Everything below is inert while off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** The session card: orbs spent / delivered, success rate, profit and the drop list. */
        @Shareable(Kind.BOOL)
        public boolean showTracker = true;

        /** The live orb card: seconds left plus the distance to the pig and to the orb. */
        @Shareable(Kind.BOOL)
        public boolean showTimer = true;

        /** Play a warning sound while an activated orb is running out of time. */
        @Shareable(Kind.BOOL)
        public boolean expiryWarning = true;

        /** How many seconds before an orb expires the warning fires (3-45). */
        public int expiryWarningSeconds = 15;

        /** Box every Shiny Pig in range – the run is bottlenecked on finding the next one. */
        @Shareable(Kind.BOOL)
        public boolean highlightPigs = true;

        /**
         * Also box plain pigs, not just ones whose nametag names them. The escape hatch for a
         * nametag encoding this detector does not know: Hypixel has moved event mob names between
         * the armor stand and the mob itself before, and a run where nothing highlights is worse
         * than one where the Village's ordinary pigs light up too.
         */
        @Shareable(Kind.BOOL)
        public boolean highlightAllPigs = false;

        /** Draw a line from the middle of your screen to each pig your orbs belong to. */
        @Shareable(Kind.BOOL)
        public boolean lineToPig = true;

        /** Draw a line from that pig to its orb – the geometry the knockback has to follow. */
        @Shareable(Kind.BOOL)
        public boolean lineToOrb = true;

        /** Colour of the pig boxes and the line to your pig. */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor pigColor =
                sbs.modid.client.core.render.OverlayColor.PINK;

        /** Colour of the orb marker and the pig→orb line (overridden by red when time is short). */
        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor orbColor =
                sbs.modid.client.core.render.OverlayColor.YELLOW;

        /** Thickness of the overlay lines and boxes in pixels (1-6). */
        public int lineThickness = 3;

        /** Sound when the orb charges – the cue to walk back and click it. */
        @Shareable(Kind.BOOL)
        public boolean chargedAlert = true;

        /** Sound on "this pig has already been clicked", so you retarget instead of walking. */
        @Shareable(Kind.BOOL)
        public boolean takenAlert = true;
    }

    /** The settings the player pinned to the Favourites page, in their order. */
    public FavoritesSettings favorites = new FavoritesSettings();

    /**
     * Favourites: config options pinned to a page of their own.
     *
     * <p>Stored as option ids and nothing else. No value is duplicated here – the page resolves each
     * id back to the real control – so this list can never disagree with the settings it points at.
     */
    public static final class FavoritesSettings {

        /**
         * Option ids ({@code module:option}), in the order they were pinned.
         *
         * <p>List order is the display order, which is why favourites never shuffle between
         * sessions. Ids that no longer resolve stay in the list rather than being pruned on load:
         * "the feature was removed" and "the module failed to describe itself this session" look
         * identical from here, and only one of them is worth losing a favourite over.
         */
        public java.util.List<String> optionIds = new java.util.ArrayList<>();
    }

    /** Overlay Inspector module preferences (the pointer that names the mod behind an overlay). */
    public OverlayInspectorSettings overlayInspector = new OverlayInspectorSettings();

    /**
     * Overlay Inspector (Interface): a free mouse pointer over the running game that names the mod
     * responsible for whatever HUD element it is over, and opens that mod's settings on click.
     *
     * <p>Off by default and only ever live while it is switched on in-game: the pointer freezes the
     * camera and the element capture it needs walks a call stack per drawn element, so nothing here
     * costs anything until a player deliberately turns it on.
     */
    public static final class OverlayInspectorSettings {

        /** Master switch: whether the inspector can be started at all. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        /** Toggle key for the pointer (GLFW keycode; -1 = unbound). */
        @Shareable(Kind.KEYCODE)
        public int toggleKey = -1;

        /** List every mod drawing on the current frame in the top-left corner. */
        @Shareable(Kind.BOOL)
        public boolean showModList = true;

        /** Pointer travel per mouse movement, in percent (100 = same as the desktop cursor). */
        public int pointerSpeed = 100;

        /** Print the "inspector on" line (with the click / ESC reminder) in chat. */
        @Shareable(Kind.BOOL)
        public boolean announce = true;

        /**
         * Show the class and method the answer was read from on the card.
         *
         * <p>On by default: the mod name is a conclusion drawn from a call stack, and without the
         * frame it came from there is no way to tell a right answer from a wrong one.
         */
        @Shareable(Kind.BOOL)
        public boolean showSource = true;

        /** Also write each new answer to the log, for reporting a wrong one. */
        @Shareable(Kind.BOOL)
        public boolean logHovers = false;
    }

    /** Kuudra module: phase tracking, the per-phase waypoints, the call-outs and the run card. */
    public KuudraSettings kuudra = new KuudraSettings();

    /**
     * Kuudra (Combat).
     *
     * <p>Three groups of defaults, and the reasoning is the same each time. The things that only
     * <b>draw</b> are on, because a marker you did not want is a marker you look past. The things
     * that <b>interrupt</b> - the flashes and the pings - are on only where the moment is genuinely
     * urgent. The things that <b>talk to other players</b> are all off: a mod that starts typing in
     * somebody's party chat the first time they queue is a mod they uninstall.
     */
    public static final class KuudraSettings {

        /** Master switch: nothing is read, drawn, timed or said while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        // -------------------------------------------------------------- supplies
        /** Beams on the six crate piles on the platform. */
        @Shareable(Kind.BOOL)
        public boolean pileWaypoints = true;

        /** Stop drawing a pile once its crate is in. */
        @Shareable(Kind.BOOL)
        public boolean hideDonePiles = true;

        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor pileColor =
                sbs.modid.client.core.render.OverlayColor.BLUE;

        /** Beams on the crates themselves, which move around down in the lava. */
        @Shareable(Kind.BOOL)
        public boolean supplyWaypoints = true;

        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor supplyColor =
                sbs.modid.client.core.render.OverlayColor.YELLOW;

        /** Watch your own camp and call it when no crate turns up there. */
        @Shareable(Kind.BOOL)
        public boolean noPreAlert = true;

        /**
         * How long after the phase starts the no-pre call may fire. Not purely a timer - the call
         * also waits for at least one crate to have surfaced somewhere, so this is the floor rather
         * than the whole condition.
         */
        public int noPreDelaySeconds = 10;

        /** Also call your second spot, once the first one is dealt with. */
        @Shareable(Kind.BOOL)
        public boolean secondSupplyAlert = true;

        /** Send the no-pre call to party chat. Off: it types on the player's behalf. */
        @Shareable(Kind.BOOL)
        public boolean noPreToParty = false;

        @Shareable(Kind.BOOL)
        public boolean noPreSound = true;

        /** Flash when the sixth crate goes in. */
        @Shareable(Kind.BOOL)
        public boolean supplyAlert = true;

        /** Flash when somebody drops a crate back into the lava. */
        @Shareable(Kind.BOOL)
        public boolean supplyDropAlert = true;

        @Shareable(Kind.BOOL)
        public boolean supplySound = true;

        // -------------------------------------------------------------- build
        /** Beams on the ballista piles, coloured by how far along each one is. */
        @Shareable(Kind.BOOL)
        public boolean buildWaypoints = true;

        /** The Fresh Tools countdown over each fresh player's head. */
        @Shareable(Kind.BOOL)
        public boolean freshTimers = true;

        /** Flash FRESH when you pick the tools up. */
        @Shareable(Kind.BOOL)
        public boolean freshAlert = true;

        /** Announce your fresh in party chat. Off: it types on the player's behalf. */
        @Shareable(Kind.BOOL)
        public boolean freshToParty = false;

        @Shareable(Kind.BOOL)
        public boolean freshSound = true;

        // -------------------------------------------------------------- stun / boss
        /** Boxes on the three pods inside Kuudra's mouth. */
        @Shareable(Kind.BOOL)
        public boolean stunWaypoints = true;

        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor stunColor =
                sbs.modid.client.core.render.OverlayColor.CYAN;

        /** Box Kuudra on his real hitbox - it is much bigger than he looks. */
        @Shareable(Kind.BOOL)
        public boolean kuudraHitbox = true;

        /** A line from the crosshair to him, through walls. */
        @Shareable(Kind.BOOL)
        public boolean bossTracer = false;

        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor bossColor =
                sbs.modid.client.core.render.OverlayColor.RED;

        /** Read the coloured floor tile under your feet in the last phase and call the slam. */
        @Shareable(Kind.BOOL)
        public boolean dangerAlert = true;

        @Shareable(Kind.BOOL)
        public boolean dangerSound = true;

        // -------------------------------------------------------------- pearls
        /** Draw the player's own recorded pearl throws for wherever they are standing. */
        @Shareable(Kind.BOOL)
        public boolean pearlWaypoints = true;

        @Shareable(value = Kind.ENUM, enumType = sbs.modid.client.core.render.OverlayColor.class)
        public sbs.modid.client.core.render.OverlayColor pearlColor =
                sbs.modid.client.core.render.OverlayColor.CYAN;

        // -------------------------------------------------------------- team
        /** Send the spell you cast, and where, to party chat. Off: it types on your behalf. */
        @Shareable(Kind.BOOL)
        public boolean abilityAnnounce = false;

        /** Draw the floor ring where an Ichor Pool landed. */
        @Shareable(Kind.BOOL)
        public boolean ichorPoolMarkers = true;

        /** Report a team buff's mana cost and how many it caught. Off: it types on your behalf. */
        @Shareable(Kind.BOOL)
        public boolean manaDrainAnnounce = false;

        // -------------------------------------------------------------- card
        @Shareable(Kind.BOOL)
        public boolean showHud = true;

        /** Keep each finished phase's time on the card - Hypixel only ever reports the total. */
        @Shareable(Kind.BOOL)
        public boolean showSplits = true;

        /** List who brought in each crate and when. */
        @Shareable(Kind.BOOL)
        public boolean showSupplyTimes = false;

        /** Flash the name of each phase as the run enters it. */
        @Shareable(Kind.BOOL)
        public boolean phaseAlert = true;

        @Shareable(Kind.BOOL)
        public boolean phaseAlertSound = false;

        /**
         * Write every detected event to the log under {@code [SBS][Kuudra]}.
         *
         * <p>Off by default, but this is the module's tuning switch: most of what it knows is read
         * off Hypixel chat lines that can be re-worded without notice, and the log is how a broken
         * one is identified against a real run instead of guessed at.
         */
        @Shareable(Kind.BOOL)
        public boolean debugLog = false;
    }

    /**
     * Diana - the Mythological Ritual. Burrow detection, the two guesses, chains, rare creatures.
     *
     * <p><b>The whole module ships off.</b> It paints the world, it can be made to write into party
     * chat, and half of it keys on four creature names that appear in none of this repository's
     * data. A player switching it on is the point at which those become something they have chosen
     * to trust rather than something we assumed for them.
     *
     * <p><b>Nothing behind these settings has been seen working in game.</b> Every particle shape,
     * coordinate and curve constant came from a description of a different client. See
     * {@code SPEC_DIANA.md} and {@code docs/features/diana-toolkit.md}.
     */
    public static final class DianaSettings {

        /** Master switch. Nothing below does anything while this is off. */
        @Shareable(Kind.BOOL)
        public boolean enabled = false;

        // --- Burrow detection -------------------------------------------------------------

        /** Read the particle packets and remember where burrows are. */
        @Shareable(Kind.BOOL)
        public boolean detectBurrows = true;

        /** Draw a world marker on each known burrow. */
        @Shareable(Kind.BOOL)
        public boolean burrowWaypoints = true;

        /** Put the kind - start, mob, treasure - on the marker's label. */
        @Shareable(Kind.BOOL)
        public boolean showBurrowKind = true;

        /** Colour for start-burrow markers, RRGGBB, empty for the global preset. */
        @Shareable(Kind.HEX_COLOR)
        public String startColorHex = "7FD9FF";

        /** Colour for mob-burrow markers. */
        @Shareable(Kind.HEX_COLOR)
        public String mobColorHex = "FF8A6B";

        /** Colour for treasure-burrow markers. */
        @Shareable(Kind.HEX_COLOR)
        public String treasureColorHex = "FFD65A";

        // --- Guesses ----------------------------------------------------------------------

        /** Fit the spade ability's particle arc and mark where it lands. */
        @Shareable(Kind.BOOL)
        public boolean spadeGuess = true;

        /** Fit the arrow the server draws after a dug burrow and cast it at the ground. */
        @Shareable(Kind.BOOL)
        public boolean arrowGuess = true;

        /**
         * Show the arrow guess's runners-up as faint markers.
         *
         * <p>Off by default. A ray cast down a long axis genuinely has several equally good answers
         * and showing all of them is the honest thing - but it is also four markers where someone
         * expects one, which reads as the feature being broken rather than as it being careful.
         */
        @Shareable(Kind.BOOL)
        public boolean showAlternativeGuesses = false;

        /** Label the runners-up "Possible" rather than leaving them as bare markers. */
        @Shareable(Kind.BOOL)
        public boolean labelAlternatives = false;

        /** Guess marker colour. */
        @Shareable(Kind.HEX_COLOR)
        public String guessColorHex = "9BE37F";

        /**
         * Work out which Hub warp lands nearest the target and say so on the marker.
         *
         * <p><b>Display only, deliberately.</b> Computing the best warp is arithmetic over a table
         * of coordinates and is squarely inside what this mod is for; sending the warp is the mod
         * acting for the player, and picking the optimal one is a rate improvement rather than a
         * convenience. The player's own command keybind sends it. See {@code SPEC_DIANA.md} section 8.
         */
        @Shareable(Kind.BOOL)
        public boolean suggestWarp = false;

        // --- Prompts ----------------------------------------------------------------------

        /** Say so when the arrow could not be fitted and there is nothing to walk to. */
        @Shareable(Kind.BOOL)
        public boolean promptOnGuessFailure = true;

        /** Say so when a chain ends with no burrow or guess anywhere near. */
        @Shareable(Kind.BOOL)
        public boolean promptOnChainEnd = true;

        /** Which channels the two prompts above use. */
        @Shareable(value = Kind.INT, min = 0, max = 63)
        public int promptChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;

        /** How far a burrow or guess may be and still count as "there is something nearby". */
        @Shareable(value = Kind.INT, min = 20, max = 300)
        public int chainEndRadius = 90;

        /** The running-chains card. */
        @Shareable(Kind.BOOL)
        public boolean chainsHud = true;

        // --- Mythological creatures -------------------------------------------------------

        /** The nearby-creature health card. */
        @Shareable(Kind.BOOL)
        public boolean creatureHealthHud = true;

        /**
         * Alert when a rare creature drops below this many million health. 0 is off.
         *
         * <p>Millions rather than raw health because that is the unit the numbers are read in, and
         * a slider in raw health would need seven digits to say what "3" says here.
         */
        @Shareable(value = Kind.INT, min = 0, max = 100)
        public int lowHealthMillions = 0;

        /** Warn while the closest live rare creature has no shuriken on it. */
        @Shareable(Kind.BOOL)
        public boolean shurikenWarning = false;

        /** Which channels the low-health alert uses. */
        @Shareable(value = Kind.INT, min = 0, max = 63)
        public int creatureAlertChannels = sbs.modid.client.core.alert.AlertChannels.TITLE_AND_SOUND;

        /**
         * Which of the four rare creatures the alerts, markers and sharing apply to.
         *
         * <p>Not shareable, on the same terms as {@code mobHighlight.selectedMobs}: an id set has a
         * {@link Kind} but no importer yet, so annotating it would export a payload that refuses to
         * come back in. A {@link LinkedHashSet} so the selection keeps its order and cannot
         * duplicate.
         */
        public Set<String> watchedCreatures = new LinkedHashSet<>(
                Set.of("INQUISITOR", "KING", "SPHINX", "MANTICORE"));

        /**
         * Name overrides, keyed by the creature constant.
         *
         * <p>Exists because none of the four default names appears anywhere in this repository's
         * data, so all four are hypotheses. An override here is how a wrong guess gets fixed without
         * waiting for a release - the point of the project rule that a name in a request becomes a
         * config field with a tolerant match rather than a literal in code.
         *
         * <p>Not shareable: it is a per-player correction to a detection gap, and importing someone
         * else's would silently change what this client is looking for.
         */
        public Map<String, String> creatureNames = new LinkedHashMap<>();

        /**
         * Mark rare creatures found in the world - only ones the player can actually see.
         *
         * <p>The line-of-sight condition is not a performance measure and is not optional. A marker
         * on something you can see is an overlay; a marker on something behind a hill is information
         * the player did not have.
         */
        @Shareable(Kind.BOOL)
        public boolean markVisibleCreatures = false;

        /** Turn a rare creature's coordinates posted in party chat into a marker. */
        @Shareable(Kind.BOOL)
        public boolean receiveSharedCreatures = true;

        /** How long a shared or scanned creature marker lives, in seconds. */
        @Shareable(value = Kind.INT, min = 10, max = 300)
        public int creatureMarkerSeconds = 45;

        /**
         * Post your own rare spawns to party chat.
         *
         * <p>Off, and it stays off until the player says otherwise: this is the mod speaking to
         * other people unprompted, which is a thing they have to choose. Same standing as the carry
         * counter's party line.
         */
        @Shareable(Kind.BOOL)
        public boolean announceSpawnsToParty = false;

        /** The line posted. {creature} is replaced; the coordinates are always in front of it. */
        @Shareable(value = Kind.TEXT, max = 64)
        public String announceFormat = "{creature}";

        // --- Trackers ---------------------------------------------------------------------

        /** Count burrows, mobs, drops and coins across the event. */
        @Shareable(Kind.BOOL)
        public boolean tracker = true;

        /** The session totals card. */
        @Shareable(Kind.BOOL)
        public boolean sessionHud = false;

        // --- Chat -------------------------------------------------------------------------

        /** Mark which of the Sphinx's three answers is the right one. Marking only. */
        @Shareable(Kind.BOOL)
        public boolean sphinxAnswers = true;

        /**
         * Click anywhere with the chat open to give the answer the solver marked.
         *
         * <p><b>Off, and it is the one setting in this mod that turns an unaimed click into a
         * command.</b> Added on an explicit request from the maintainer on 2026-08-26, with the
         * argument against it recorded on {@code SphinxAnswers} and in {@code SPEC_DIANA.md}
         * section 8 rather than lost in a conversation: `AGENTS.md` says the mod never plays the
         * game for the player, and answering a riddle progresses you rather than restoring you,
         * which is the condition an exception here fails.
         *
         * <p>It is fenced as tightly as the shape allows - armed only between a solved riddle and
         * the next click, expiring on its own, one command per riddle, never retried - and it does
         * nothing at all while {@link #sphinxAnswers} is off, because it can only give an answer the
         * marking has already worked out.
         */
        @Shareable(Kind.BOOL)
        public boolean sphinxClickToAnswer = false;

        /** Hide the ritual's own noise: the arrow hint, the cooldown line, "Warping...". */
        @Shareable(Kind.BOOL)
        public boolean hideRitualChatter = false;

        // --- Debug ------------------------------------------------------------------------

        /**
         * Log every particle and chat line that nearly matched but did not, under {@code [SBS][Diana]}.
         *
         * <p>This is the module's tuning switch and the reason it can be corrected from one session
         * in game. Nothing in the shipped constants has been verified against this client, so the
         * near-misses are the evidence: a footstep signature off by one in its count shows up here
         * as a packet that came close and was refused.
         */
        @Shareable(Kind.BOOL)
        public boolean debugLog = false;
    }
}
