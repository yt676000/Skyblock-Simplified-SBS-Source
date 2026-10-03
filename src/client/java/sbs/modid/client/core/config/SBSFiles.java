/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Central file manager for Skyblock Simplified.
 *
 * <p>Everything the mod reads or writes goes through here, so the on-disk layout lives in exactly
 * one place. No Fabric API is used: the base directory is derived from Minecraft's own
 * {@link Minecraft#gameDirectory} ({@code .minecraft/config/sbs/}).
 *
 * <pre>
 * config/sbs/
 *   config.json                    (global base config)
 *   repo/
 *     items.json                   (static item catalogue)
 *     recipes.json                 (static recipe database)
 *     scraped_recipes.json         (recipes learned while browsing)
 *   economy/
 *     bazaar_prices.json           (live prices / market data)
 *   gui/
 *     hud_layout.json              (Edit-HUD element positions)
 *     pause_menu.json              (per-button pause menu layout)
 *     windows.json                 (floating window position / size / minimized)
 *   Development_Stuff/             (HIDDEN – see below)
 *     Waypoints.json
 * </pre>
 *
 * <p><b>Tarnung / camouflage:</b> {@link #developmentDir()} and {@link #waypointsFile()} are pure
 * path getters – they never create anything. {@link #prepareBaseDirs()} deliberately creates only
 * the four visible folders, never {@code Development_Stuff}. That directory is created for the first
 * time only inside the dev-mode waypoint export (see {@code WaypointExporter}), so it stays invisible
 * during normal play.
 */
public final class SBSFiles {

    /** Shared Gson used by every store (pretty printed, no HTML escaping of {@code =}/{@code <}). */
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private SBSFiles() {
    }

    // ------------------------------------------------------------------
    // Base directory + global config
    // ------------------------------------------------------------------

    /**
     * The game directory to resolve against when there is no client - set by offline tooling
     * (the {@code codeIndex} Gradle task) via {@code -Dsbs.gameDir=...}, unset in the game.
     */
    private static final String OFFLINE_GAME_DIR = "sbs.gameDir";

    /** {@code config/sbs/}. */
    public static Path root() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            // No client: we are running under a build task, not in the game. Falling back keeps
            // the offline walk on a real (empty) directory instead of throwing halfway through
            // building a module's settings rows - see the class doc on why that matters.
            String offline = System.getProperty(OFFLINE_GAME_DIR);
            if (offline == null) {
                throw new IllegalStateException(
                        "SBSFiles.root() called with no Minecraft client and no -D" + OFFLINE_GAME_DIR);
            }
            return Path.of(offline).resolve("config").resolve("sbs");
        }
        return minecraft.gameDirectory.toPath().resolve("config").resolve("sbs");
    }

    /** {@code config/sbs/config.json} – the only file directly in the root. */
    public static Path configFile() {
        return root().resolve("config.json");
    }

    // ------------------------------------------------------------------
    // license/ – the licence token, deliberately OUTSIDE every profile
    // ------------------------------------------------------------------

    /**
     * {@code config/sbs/license/} – home of the licence token.
     *
     * <p>It lives in its own folder rather than in the config because the config is now per-profile
     * ({@link ConfigProfiles}), and the token is not a per-profile setting: it identifies <b>you</b>.
     * Kept in the config it would have to be pasted again for every profile you create, and a profile
     * switch would silently drop you back to "no token" - which is what happened.
     */
    public static Path licenseDir() {
        return root().resolve("license");
    }

    /** {@code config/sbs/license/token.json}. */
    public static Path licenseTokenFile() {
        return licenseDir().resolve("token.json");
    }

    /**
     * {@code config/sbs/license/privacy/} – one consent file per Minecraft account.
     *
     * <p>Alongside the token rather than inside a profile for the same reason the token is: consent
     * is given by a <b>person</b>, not by a config preset. Were it per-profile, creating a profile
     * would silently reset every answer to "not granted" – which fails safe, but would also mean a
     * user who consented once is re-asked forever. Keyed by account so two people sharing a PC
     * cannot inherit each other's answers.
     */
    public static Path privacyDir() {
        return licenseDir().resolve("privacy");
    }

    /**
     * {@code config/sbs/license/privacy/consent-<uuid>.json} for one Minecraft account.
     *
     * @param accountId the account's uuid, or any stable non-empty id for the offline case
     */
    public static Path consentFile(String accountId) {
        return privacyDir().resolve("consent-" + accountKey(accountId) + ".json");
    }

    /**
     * {@code config/sbs/wizard/} – what the onboarding and update-showcase overlay remembers, one
     * file per Minecraft account.
     *
     * <p>Outside every config profile, for the reason the token and the consent files are: which
     * pages a <b>person</b> has already been shown is not a config preset, and a profile switch that
     * replayed the first-run wizard would be a bug rather than a reset.
     *
     * <p>Keyed by the account <b>uuid</b> and not by {@code ProfileContext.account()}, whose
     * {@code <ign>-<uuid8>} form moves when the player renames - and a key that moves on rename is a
     * key that silently loses the record, which here means replaying the wizard at someone who
     * already finished it.
     */
    public static Path wizardDir() {
        return root().resolve("wizard");
    }

    /**
     * {@code config/sbs/wizard/wizard-<uuid>.json} for one Minecraft account.
     *
     * @param accountId the account's uuid, or any stable non-empty id for the offline case
     */
    public static Path wizardFile(String accountId) {
        return wizardDir().resolve("wizard-" + accountKey(accountId) + ".json");
    }

    /** Filename-safe account key, with the same fallback for "no account id yet" everywhere. */
    private static String accountKey(String accountId) {
        String safe = accountId == null ? "" : accountId.replaceAll("[^a-zA-Z0-9-]", "");
        return safe.isEmpty() ? "unknown" : safe;
    }

    // ------------------------------------------------------------------
    // profiles/ – alternative config profiles (see ConfigProfiles)
    // ------------------------------------------------------------------

    /**
     * {@code config/sbs/profiles/} – one {@code <name>.json} per custom profile. The Default profile
     * deliberately has no file here: it <b>is</b> {@link #configFile()}, so a player who never touches
     * profiles keeps exactly the config they always had, in the place it has always been.
     *
     * <p>Not to be confused with {@link #profileDir()}, which is the current <i>SkyBlock</i> profile's
     * cache folder – a different concept entirely.
     */
    public static Path configProfilesDir() {
        return root().resolve("profiles");
    }

    /** {@code config/sbs/profiles/<name>.json} – the config file backing one custom profile. */
    public static Path configProfileFile(String name) {
        return configProfilesDir().resolve(name + ".json");
    }

    /**
     * {@code config/sbs/profiles/active.txt} – which profile is selected. A plain text file rather
     * than an entry in a config, because the choice has to be readable <i>before</i> deciding which
     * config to load; a {@code .txt} also cannot collide with a profile's own {@code .json}.
     */
    public static Path activeConfigProfileFile() {
        return configProfilesDir().resolve("active.txt");
    }

    // ------------------------------------------------------------------
    // repo/ – static game data
    // ------------------------------------------------------------------

    public static Path repoDir() {
        return root().resolve("repo");
    }

    public static Path itemsFile() {
        return repoDir().resolve("items.json");
    }

    public static Path recipesFile() {
        return repoDir().resolve("recipes.json");
    }

    /** Recipes the player has scraped while browsing (kept out of the curated {@link #recipesFile()}). */
    public static Path scrapedRecipesFile() {
        return repoDir().resolve("scraped_recipes.json");
    }

    /**
     * The Bits Shop offers learned while browsing its category pages - item id, bits price and the
     * page it was on. Static game data rather than a per-profile cache: the shop sells the same
     * things at the same prices to everyone.
     */
    public static Path bitsShopFile() {
        return repoDir().resolve("bits_shop.json");
    }

    // ------------------------------------------------------------------
    // economy/ – live prices / market data
    // ------------------------------------------------------------------

    public static Path economyDir() {
        return root().resolve("economy");
    }

    public static Path bazaarPricesFile() {
        return economyDir().resolve("bazaar_prices.json");
    }

    // ------------------------------------------------------------------
    // gui/ – editor layouts
    // ------------------------------------------------------------------

    public static Path guiDir() {
        return root().resolve("gui");
    }

    public static Path hudLayoutFile() {
        return guiDir().resolve("hud_layout.json");
    }

    /** Per-button position, size and corner radius of the vanilla pause menu. */
    public static Path pauseMenuFile() {
        return guiDir().resolve("pause_menu.json");
    }

    /**
     * Where each floating overlay window (Best Flips, Recipe Viewer, Calculator, ...) was last left:
     * position, size and whether it was minimized. See {@code ui/window/WindowMemory}.
     */
    public static Path windowsFile() {
        return guiDir().resolve("windows.json");
    }

    // ------------------------------------------------------------------
    // render/ – remembered island terrain, one file per island
    // ------------------------------------------------------------------

    /** {@code config/sbs/render/} – the Far Terrain module's per-island terrain files. */
    public static Path renderDir() {
        return root().resolve("render");
    }

    /** {@code config/sbs/render/<island-slug>.sbsr} – one island's remembered chunks. */
    public static Path renderFile(String islandSlug) {
        return renderDir().resolve(islandSlug + ".sbsr");
    }

    // ------------------------------------------------------------------
    // tracker/ – human-readable all-time drop logs, one file per tracker
    // ------------------------------------------------------------------

    public static Path trackerDir() {
        return root().resolve("tracker");
    }

    /** {@code config/sbs/tracker/<name>.txt} – e.g. {@code fishingtracker.txt}. */
    public static Path trackerFile(String name) {
        return trackerDir().resolve(name + ".txt");
    }

    /**
     * {@code config/sbs/tracker/ghast-sightings.json} – timestamped Powder Ghast observations.
     *
     * <p>Its own file rather than a {@code TrackerStore} tally because the question is <i>when</i>,
     * not <i>how many</i>: the spawn mechanic is unverified, and the only way to establish it is to
     * accumulate stamped sightings across several sessions and derive the interval from them. Not
     * profile-scoped – a server-side spawn rule belongs to no profile.
     */
    public static Path ghastSightingsFile() {
        return trackerDir().resolve("ghast-sightings.json");
    }

    // ------------------------------------------------------------------
    // data/ – client-learned, account-wide catalogues (not per profile)
    // ------------------------------------------------------------------

    /**
     * {@code config/sbs/schematics/} – the Build Tools library: one {@code .sbsbp} per saved build
     * plus its thumbnail. Visible and outside every profile on purpose: a build is the player's own
     * work, meant to be backed up, shared and found without digging through hidden folders.
     */
    public static Path schematicsDir() {
        return root().resolve("schematics");
    }

    /** {@code config/sbs/data/} – what this client learned, belonging to no single profile. */
    public static Path dataDir() {
        return root().resolve("data");
    }

    /**
     * {@code config/sbs/data/protected_items.json} – the Item Protection lists.
     *
     * <p>Global rather than profile-scoped, and that is the whole design decision: an item moves
     * between profiles through the auction house and the museum, so protection filed under one
     * profile would fall off exactly when the item did the thing the feature exists to survive.
     */
    public static Path protectedItemsFile() {
        return dataDir().resolve("protected_items.json");
    }

    /**
     * {@code config/sbs/data/player_notes.json} – the Player Notes: what the player thinks of other
     * players.
     *
     * <p>Its own file, not {@code config.json}: it grows with every note, and it is the player's
     * private opinion of real people, so it must stay out of anything that exports the config.
     * Config sharing walks {@code SBSConfig} only, so a separate file is excluded by construction
     * ({@code PlayerNotesShareTest} holds that). Global rather than profile-scoped: somebody you
     * avoid is the same person on every profile.
     */
    public static Path playerNotesFile() {
        return dataDir().resolve("player_notes.json");
    }

    /**
     * {@code config/sbs/data/npc_shops.json} – what NPC shops charge, learned from their tooltips
     * (NPC flips). Global: a shop's prices are the same on every profile.
     */
    public static Path npcShopsFile() {
        return dataDir().resolve("npc_shops.json");
    }

    // ------------------------------------------------------------------
    // probe/ – menu captures written by the dev MenuProbe, one file per capture
    // ------------------------------------------------------------------

    /**
     * {@code config/sbs/probe/} – where {@code /sbs probe} puts its captures.
     *
     * <p>Visible on purpose, unlike {@link #developmentDir()}: a capture is something a player may be
     * asked to produce and send back, so it has to be findable without knowing where to look.
     */
    public static Path probeDir() {
        return root().resolve("probe");
    }

    /**
     * {@code config/sbs/questcapture/} - where the dev questline recorder writes its sessions.
     *
     * <p>Visible like {@link #probeDir()} rather than hidden like {@link #developmentDir()}: a
     * capture exists to be found, opened and sent on, which is the whole point of recording it.
     */
    public static Path questCaptureDir() {
        return root().resolve("questcapture");
    }

    /** {@code config/sbs/probe/probe-<stamp>-<title>.txt} – one capture. */
    public static Path probeFile(String name) {
        return probeDir().resolve("probe-" + name + ".txt");
    }

    /**
     * {@code config/sbs/probe/chat-<stamp>.txt} – one armed chat-probe session.
     *
     * <p>Beside {@link #probeFile} rather than in a folder of its own: both are captures taken to be
     * found, opened and sent on, and one probe directory is one place to look.
     */
    public static Path chatProbeFile(String name) {
        return probeDir().resolve("chat-" + name + ".txt");
    }

    /**
     * {@code config/sbs/probe/diana/} – Diana Log Mode's captures ({@code diana-<stamp>.jsonl}, the
     * exported {@code .json} beside each) and the guard's one-off {@code diana-crash-<stamp>.json}.
     *
     * <p>A folder of its own inside the probe directory, unlike the single-file probes: one capture is
     * two files, and a session of testing produces several.
     */
    public static Path dianaLogDir() {
        return probeDir().resolve("diana");
    }

    // ------------------------------------------------------------------
    // Accounts/ – per account + SkyBlock profile caches (see ProfileContext)
    // ------------------------------------------------------------------

    /** {@code config/sbs/Accounts/} – root of the per-account-profile cache tree. */
    public static Path accountsDir() {
        return root().resolve("Accounts");
    }

    /**
     * {@code Accounts/<account>/mining_events.json} – observed Dwarven Mines / Crystal Hollows
     * events. Per account, not per profile: lobbies are shared by every profile.
     */
    public static Path miningEventsFile(String account) {
        return accountsDir().resolve(account).resolve("mining_events.json");
    }

    /** The current account+profile folder ({@code Accounts/<account>/profile/<profile>/}). */
    public static Path profileDir() {
        return ProfileContext.getInstance().dir();
    }

    /** A file inside the current account+profile folder. */
    public static Path profileFile(String name) {
        return profileDir().resolve(name);
    }

    /** Enderchest / backpack / sack snapshots – now per account + profile. */
    public static Path storageCacheFile() {
        return profileFile("storage_cache.json");
    }

    /** Wardrobe / equipment loadout snapshots – per account + profile. */
    public static Path loadoutsCacheFile() {
        return profileFile("loadouts_cache.json");
    }

    /** The Armor Sets menu's pages, as last seen - per account + profile. */
    public static Path armorSetsCacheFile() {
        return profileFile("armor_sets_cache.json");
    }

    /** The Garden plot icons learned from Configure Plots – per account + profile. */
    public static Path gardenPlotsCacheFile() {
        return profileFile("garden_plots_cache.json");
    }

    /** Accessory Bag pages seen, so "what am I missing" survives a restart – per account + profile. */
    public static Path accessoryCacheFile() {
        return profileFile("accessories_cache.json");
    }

    // ------------------------------------------------------------------
    // Development_Stuff/ – HIDDEN dev tools (path getters ONLY, never created here)
    // ------------------------------------------------------------------

    /** Path getter only. Callers that write here must create it explicitly, and only in dev mode. */
    public static Path developmentDir() {
        return root().resolve("Development_Stuff");
    }

    /** Path getter only – see {@link #developmentDir()}. */
    public static Path waypointsFile() {
        return developmentDir().resolve("Waypoints.json");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Creates the parent directory of {@code file} if needed. */
    public static void ensureParent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /**
     * Eagerly creates the <b>visible</b> folder structure and migrates any old loose files into it.
     * Called once on client init. This never touches {@link #developmentDir()}.
     */
    public static void prepareBaseDirs() {
        try {
            Files.createDirectories(root());
            Files.createDirectories(repoDir());
            Files.createDirectories(economyDir());
            Files.createDirectories(guiDir());
            SkyblockSimplifiedSBS.LOGGER.info("[SBS] Config structure ready at {}", root());
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to prepare config directories", e);
        }
        migrateLegacyFiles();
    }

    /**
     * One-time, best-effort migration of the pre-refactor loose files that used to sit directly in
     * {@code config/}. A file is only moved when the new destination does not already exist, so this
     * is safe to run on every start.
     */
    private static void migrateLegacyFiles() {
        move(legacy(SkyblockSimplifiedSBS.MOD_ID + ".json"), configFile());
        move(legacy(SkyblockSimplifiedSBS.MOD_ID + "-items.json"), itemsFile());
        move(legacy(SkyblockSimplifiedSBS.MOD_ID + "-repo-recipes.json"), recipesFile());
        move(legacy(SkyblockSimplifiedSBS.MOD_ID + "-recipes.json"), scrapedRecipesFile());
        move(legacy(SkyblockSimplifiedSBS.MOD_ID + "-prices.json"), bazaarPricesFile());
    }

    private static Path legacy(String name) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(name);
    }

    private static void move(Path from, Path to) {
        try {
            if (Files.exists(from) && !Files.exists(to)) {
                ensureParent(to);
                Files.move(from, to);
                SkyblockSimplifiedSBS.LOGGER.info("[SBS] Migrated {} -> {}", from.getFileName(), to);
            }
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] Could not migrate {}", from, e);
        }
    }
}
