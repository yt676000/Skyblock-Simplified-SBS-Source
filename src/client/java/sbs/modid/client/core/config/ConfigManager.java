/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.AlertChannel;
import sbs.modid.client.core.alert.AlertChannels;
import sbs.modid.client.core.util.NumberTextFormat;
import sbs.modid.client.helper.visual.model.WindowButtonStyle;
import sbs.modid.client.helper.visual.render.Chroma;
import sbs.modid.client.ui.hud.edit.logic.HudLayoutStore;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and saves {@link SBSConfig} to {@code .minecraft/config/}.
 *
 * <p>No Fabric API is used: the config directory is derived from Minecraft's own
 * {@link Minecraft#gameDirectory}. Loading is intentionally <b>lazy</b> – the file
 * is only read on first {@link #get()} (i.e. when the GUI is first opened), well
 * after {@code Minecraft.getInstance()} is fully available, which avoids any
 * early-initialization issues. Gson is bundled with Minecraft, so there is no
 * extra dependency.
 *
 * <p>Which file that is depends on the active {@link ConfigProfiles} profile: the base
 * {@code config.json} for Default, a {@code profiles/<name>.json} for a custom one. Everything else
 * in the mod is unaware of profiles because it reads through {@link #get()} every time – see
 * {@link #switchProfile}.
 */
public final class ConfigManager {

    private static ConfigManager instance;

    private SBSConfig config;

    private ConfigManager() {
    }

    public static ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    /** The in-memory config (loaded on first access). Mutate then call {@link #save()}. */
    public SBSConfig get() {
        if (config == null) {
            config = load();
        }
        return config;
    }

    /**
     * Drops the in-memory config so the next {@link #get()} reads the file again.
     *
     * <p>For the one caller that changes {@code config.json} behind this class's back: restoring a
     * backup. Everything else mutates the live object and calls {@link #save()}, and must keep doing
     * that - this is not a way to discard unsaved edits, it is how a file swapped underneath us gets
     * noticed.
     */
    public void reload() {
        config = null;
    }

    /** The file the active profile is stored in – the base config for Default, else its own file. */
    private Path configPath() {
        ConfigProfiles profiles = ConfigProfiles.getInstance();
        return profiles.fileFor(profiles.active());
    }

    /**
     * Switches to another config profile: the current one is written to <b>its own</b> file first,
     * then the pointer moves and the in-memory config is dropped so the next {@link #get()} loads the
     * new profile lazily. Saving before switching is the whole point – doing it the other way round
     * would flush the settings you were just editing into the profile you switched to.
     *
     * <p>Callers holding on to the {@link SBSConfig} instance would be left with the old profile's
     * object; nothing in the mod does (everything reads {@code ConfigManager.getInstance().get()}
     * on demand), and this is the reason it must stay that way.
     *
     * @return true when the active profile actually changed
     */
    public boolean switchProfile(String name) {
        ConfigProfiles profiles = ConfigProfiles.getInstance();
        String target = ConfigProfiles.isDefault(name) ? ConfigProfiles.DEFAULT : name;
        if (target.equals(profiles.active())) {
            return false;
        }
        if (config != null) {
            writeToDisk(config);
        }
        profiles.setActive(target);
        config = null;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Switched to config profile '{}'", target);
        return true;
    }

    private SBSConfig load() {
        Path path = configPath();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    SBSConfig loaded = SBSFiles.GSON.fromJson(reader, SBSConfig.class);
                    if (loaded != null) {
                        migrateHudLayout(loaded);
                        migrateLicenceToken(loaded);
                        migrateStoragePreview(loaded);
                        migrateWindowButtons(loaded);
                        migrateKeybindActions(loaded);
                        migrateHuntingKeys(loaded);
                        migrateGardenPlotHeight(loaded);
                        migrateBuffCards(loaded);
                        migrateDesktopNotifications(loaded);
                        migrateNumberFormats(loaded);
                        migrateScoreboardLayout(loaded);
                        migrateChromaSpeed(loaded);
                        migrateHighlightNames(loaded);
                        migrateDianaHud(loaded);
                        // Last, and after the migrations: it may only turn something on once the
                        // field it is turning on has finished being upgraded.
                        adoptShippedDefaults(loaded);
                        // Self-heal: rewrite so newly added default fields (e.g. the dev keybinds) are
                        // persisted and the old inlined hudLayout is dropped from config.json.
                        writeToDisk(loaded);
                        return loaded;
                    }
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to read config, using defaults", e);
        }
        SBSConfig fresh = new SBSConfig();
        migrateHudLayout(fresh);
        // Born with the shipped defaults - the field initialisers ARE them - so only record the
        // round. Running the rounds here would overwrite newer initialisers with older round
        // values (round 2 forces Keep Mouse Position on, which a fresh install now ships off).
        fresh.defaultsAdopted = SHIPPED_DEFAULTS;
        writeToDisk(fresh);
        return fresh;
    }

    /** The newest round of shipped defaults; bump when a new one has to reach existing configs. */
    private static final int SHIPPED_DEFAULTS = 2;

    /**
     * Adopts the current shipped defaults into a config that predates them - once, ever.
     *
     * <p>The problem it solves: a changed Java initialiser only reaches a player with no
     * {@code config.json}, and {@link #load} re-persists every field on every load, so an existing
     * player can never pick one up. Without this, "on by default" means "on for nobody who already
     * plays the mod".
     *
     * <p>Round 1 (Custom Scoreboard + SBS Tab-List on, and the shipped scoreboard layout for anyone
     * who never arranged one). It runs at most once per config file and is <b>not</b> re-applied
     * afterwards, so switching either back off sticks.
     */
    private void adoptShippedDefaults(SBSConfig config) {
        if (config.defaultsAdopted >= SHIPPED_DEFAULTS) {
            return;
        }
        int adopted = config.defaultsAdopted;
        config.defaultsAdopted = SHIPPED_DEFAULTS;

        // Round 2: Keep Mouse Position on. Gated on its own round rather than on the method being
        // entered at all - a config already at round 1 must NOT have round 1 applied a second time,
        // which is what a plain bump would do, and it would switch the Custom Scoreboard and the
        // Tab-List back on for everyone who had deliberately turned them off.
        if (adopted < 2 && config.convenience != null) {
            config.convenience.keepMousePosition = true;
        }

        if (adopted >= 1) {
            return;
        }
        if (config.customScoreboard != null) {
            SBSConfig.CustomScoreboardSettings scoreboard = config.customScoreboard;
            scoreboard.enabled = true;
            // The five tab-fed rows the shipped layout is built around. Without them the layout
            // arrives half empty, which is not the panel that was designed.
            scoreboard.showRealTime = true;
            scoreboard.showBank = true;
            scoreboard.showInterest = true;
            scoreboard.showGems = true;
            scoreboard.showProfile = true;
            scoreboard.showSbLevel = true;
            // Only for a player who never arranged anything: an empty order is "never customised",
            // and handing them a curated panel is the point. A layout that exists is left alone.
            if (scoreboard.elementOrder == null || scoreboard.elementOrder.isEmpty()) {
                scoreboard.elementOrder =
                        sbs.modid.client.helper.scoreboard.ScoreboardLayout.round1Order();
                scoreboard.hiddenElements =
                        sbs.modid.client.helper.scoreboard.ScoreboardLayout.round1Hidden();
            }
        }
        if (config.tabList != null) {
            config.tabList.enabled = true;
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS] Adopted shipped defaults v{}: Custom Scoreboard + SBS Tab-List on",
                SHIPPED_DEFAULTS);
    }

    /**
     * Turns the four Diana card switches into the arranged line layout, once. An unarranged config
     * takes exactly the lines its switches drew, so nothing on screen moves; the switches are then
     * nulled. See {@code DianaHudLayout.migrate}.
     */
    private void migrateDianaHud(SBSConfig config) {
        if (config.diana != null) {
            sbs.modid.client.combat.diana.logic.DianaHudLayout.migrate(config.diana);
        }
    }

    /**
     * Upgrades the Custom Scoreboard's line order from the old text-derived keys to stable element
     * ids. What maps carries over; what does not is dropped and the player is told once, rather than
     * being handed a panel that came back quietly rearranged.
     */
    private void migrateScoreboardLayout(SBSConfig config) {
        if (config.customScoreboard != null) {
            sbs.modid.client.helper.scoreboard.ScoreboardLayoutMigration.migrate(config.customScoreboard);
        }
    }

    /**
     * Moves the HUD-editor layout out of the main config and into {@code gui/hud_layout.json}.
     * Any inline layout from an older config is adopted once, then the in-config field is nulled so
     * Gson stops writing it to {@code config.json} (null fields are omitted by default).
     */
    private void migrateHudLayout(SBSConfig config) {
        if (config.hypixelGui != null) {
            HudLayoutStore.getInstance().adoptLegacy(config.hypixelGui.hudLayout);
            config.hypixelGui.hudLayout = null;
        }
    }

    /**
     * Moves the licence token out of the config and into {@code license/token.json}, so it survives
     * a profile switch. Runs for <b>every</b> profile's config, not just the default one: whichever
     * config still carries a token hands it over, and the field is nulled afterwards so the next
     * write drops it. An existing token file always wins - a stale copy in some old profile must
     * never overwrite the one in use.
     */
    @SuppressWarnings("deprecation")
    private void migrateLicenceToken(SBSConfig config) {
        if (config.licence == null || config.licence.legacyToken == null) {
            return;
        }
        LicenceToken.getInstance().adoptLegacy(config.licence.legacyToken);
        config.licence.legacyToken = null;
    }

    /**
     * Upgrades the old on/off Ender Chest preview flag to the three-way
     * {@link sbs.modid.client.helper.storage.StoragePreviewMode}. The boxed legacy field is only non-null when
     * an older config actually carried it, so a player who had the preview on keeps it on (rather
     * than being silently reset to the default), and it is nulled afterwards so the next write drops
     * it from config.json for good.
     */
    @SuppressWarnings("deprecation")
    private void migrateStoragePreview(SBSConfig config) {
        if (config.skyblockMenu == null || config.skyblockMenu.enderchestBackpackPreview == null) {
            return;
        }
        // Full UI is a value the legacy on/off flag could never express, so its presence means the
        // player already chose the new setting deliberately. Never let a stale boolean lingering
        // beside it downgrade Full UI back to Preview – just drop the boolean and keep the choice.
        if (!config.skyblockMenu.previewMode.indexes()) {
            config.skyblockMenu.previewMode = config.skyblockMenu.enderchestBackpackPreview
                    ? sbs.modid.client.helper.storage.StoragePreviewMode.PREVIEW
                    : sbs.modid.client.helper.storage.StoragePreviewMode.OFF;
        }
        config.skyblockMenu.enderchestBackpackPreview = null;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Migrated enderchestBackpackPreview -> previewMode={}",
                config.skyblockMenu.previewMode);
    }

    /**
     * Upgrades the old white/black title-bar button flag to the three-way
     * {@link sbs.modid.client.helper.visual.model.WindowButtonStyle}.
     *
     * <p>The flag's default was "white", and its <b>only</b> reason to be off was a caption too pale
     * for white glyphs - which is exactly the judgement the new {@code AUTO} makes on its own, and
     * makes again every time the theme moves. So a config still sitting on the old default lands on
     * the new one, and an explicit "off" - a decision the player made about a light caption - is
     * kept as {@code BLACK} rather than handed to a mode that might disagree with them.
     */
    @SuppressWarnings("deprecation")
    private void migrateWindowButtons(SBSConfig config) {
        if (config.visuals == null || config.visuals.titleBarWhiteButtons == null) {
            return;
        }
        if (!config.visuals.titleBarWhiteButtons) {
            config.visuals.titleBarButtons = WindowButtonStyle.BLACK;
        }
        config.visuals.titleBarWhiteButtons = null;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Migrated titleBarWhiteButtons -> titleBarButtons={}",
                config.visuals.titleBarButtons);
    }

    /**
     * Moves the infested-plot outline height onto its ground-anchored replacement.
     *
     * <p>The old height was measured from the player's feet, so the whole outline rode up and down
     * with every jump; it now grows from the Garden floor, and the sensible size for a wall you are
     * meant to spot from across the island is much taller than the old default. So a config still
     * sitting on that default is moved to the new one, while <b>a value the player actually chose is
     * carried over unchanged</b> - the point is to retire a default nobody picked, not to overwrite a
     * decision. Nulling the legacy field afterwards makes it a one-shot: a later return to 30 is a
     * choice and stays.
     */
    @SuppressWarnings("deprecation")
    private void migrateGardenPlotHeight(SBSConfig config) {
        if (config.garden == null || config.garden.pestPlotHeight == null) {
            return;
        }
        int legacy = config.garden.pestPlotHeight;
        if (legacy != LEGACY_PLOT_HEIGHT_DEFAULT) {
            config.garden.pestPlotWallHeight = Math.max(1, Math.min(256, legacy));
        }
        config.garden.pestPlotHeight = null;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Migrated pestPlotHeight={} -> pestPlotWallHeight={}",
                legacy, config.garden.pestPlotWallHeight);
    }

    /** The outline height's default before it was anchored to the ground. */
    private static final int LEGACY_PLOT_HEIGHT_DEFAULT = 30;

    /**
     * Retires the God Potion / Booster Cookie HUD cards in favour of the Custom Scoreboard rows.
     *
     * <p>Unlike the other migrations this one deliberately does <b>not</b> carry the old value over.
     * Both cards were on by default and the scoreboard rows are on by default too, so keeping them
     * would show every existing player the same two timers twice - which is the one outcome moving
     * them into the sidebar was meant to avoid. The cards are still there in the Active Buffs module
     * for anyone who wants them back; nulling the legacy fields makes this a one-shot, so switching
     * one back on afterwards is a choice and stays.
     */
    /**
     * Points the sidebar's and chat's number formats at the mod-wide Shorten Numbers switch.
     *
     * <p>Both shipped with their own setting defaulting to "leave the server's text alone", which
     * meant turning Shorten Numbers on did nothing to the two surfaces carrying the most numbers in
     * the game. Changing the default only helps a fresh install - every config written before this
     * already has {@code SERVER} in it - so an existing one is carried over too.
     *
     * <p>One-shot, and only over the value the old default wrote: a player who deliberately chose
     * {@code Shortened} or {@code Full} keeps it, and anyone who picks {@code Server} after the
     * migration keeps that as well, because the marker is set either way.
     */
    @SuppressWarnings("deprecation")
    private void migrateNumberFormats(SBSConfig config) {
        if (Boolean.TRUE.equals(config.numberFormatsFollowGlobal)) {
            return;
        }
        config.numberFormatsFollowGlobal = Boolean.TRUE;
        boolean migrated = false;
        if (config.customScoreboard != null
                && config.customScoreboard.numberFormat == NumberTextFormat.SERVER) {
            config.customScoreboard.numberFormat = NumberTextFormat.AUTO;
            migrated = true;
        }
        if (config.chatOptions != null && config.chatOptions.numberFormat == NumberTextFormat.SERVER) {
            config.chatOptions.numberFormat = NumberTextFormat.AUTO;
            migrated = true;
        }
        if (migrated) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS] Number formats now follow the Shorten Numbers setting");
        }
    }

    /**
     * Folds the three per-effect chroma speeds into the single {@code theme.chromaSpeed}.
     *
     * <p>Takes the first one the player actually moved, in the order the settings page listed them,
     * rather than averaging or preferring a particular effect: whichever they last cared enough to
     * drag is the pace they wanted chroma to run at, and carrying one across is the only outcome
     * that cannot surprise them. All three at the old default means they never had an opinion, so
     * the new default stands.
     *
     * <p>Nulling the legacy fields makes this one-shot, so changing the master afterwards sticks.
     */
    /**
     * Carries every setting that used to be called "ESP" onto its "Highlight" name.
     *
     * <p>The rename was to the feature, not to what anyone chose: a player who switched the Pest
     * highlight on wants the Pest Highlight on. Gson keys off the field name, so without this the renamed
     * fields simply take their defaults and every one of these settings silently reverts - which for
     * the three that default to {@code true} means a feature quietly turning itself back on.
     *
     * <p>The legacy fields are boxed so {@code null} means "this config predates the rename" and
     * {@code false} means "the player turned it off". A primitive could not tell those apart, and
     * would migrate an unset key as a deliberate {@code false} over the new default.
     *
     * <p>Each is nulled after it is read, so this is a one-shot: a later change back is the player's
     * and is never overwritten on the next load.
     *
     * <p>Pinned favourites move too. They store {@code module:option} ids, and two module ids
     * changed with the rename - an unresolvable favourite is hidden rather than deleted, so without
     * this the pin would not error, it would just stop appearing, which is the harder thing to
     * notice.
     */
    @SuppressWarnings("deprecation")
    private void migrateHighlightNames(SBSConfig config) {
        int moved = 0;
        if (config.partyEsp != null) {
            config.partyHighlight = config.partyEsp;
            config.partyEsp = null;
            moved++;
        }
        SBSConfig.CarryCounterSettings carry = config.carryCounter;
        if (carry != null) {
            if (carry.espEnabled != null) {
                carry.highlightEnabled = carry.espEnabled;
                carry.espEnabled = null;
                moved++;
            }
            if (carry.espColor != null) {
                carry.highlightColor = carry.espColor;
                carry.espColor = null;
                moved++;
            }
            if (carry.espLabel != null) {
                carry.highlightLabel = carry.espLabel;
                carry.espLabel = null;
                moved++;
            }
            if (carry.espOwnedOnly != null) {
                carry.highlightOwnedOnly = carry.espOwnedOnly;
                carry.espOwnedOnly = null;
                moved++;
            }
            if (carry.espActiveBossOnly != null) {
                carry.highlightActiveBossOnly = carry.espActiveBossOnly;
                carry.espActiveBossOnly = null;
                moved++;
            }
            if (carry.espTracer != null) {
                carry.highlightTracer = carry.espTracer;
                carry.espTracer = null;
                moved++;
            }
        }
        SBSConfig.GardenSettings garden = config.garden;
        if (garden != null) {
            if (garden.pestEsp != null) {
                garden.pestHighlight = garden.pestEsp;
                garden.pestEsp = null;
                moved++;
            }
            if (garden.pestEspTracer != null) {
                garden.pestHighlightTracer = garden.pestEspTracer;
                garden.pestEspTracer = null;
                moved++;
            }
            if (garden.pestEspColor != null) {
                garden.pestHighlightColor = garden.pestEspColor;
                garden.pestEspColor = null;
                moved++;
            }
        }
        moved += migrateHighlightFavourites(config);
        if (moved > 0) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS] Migrated {} ESP setting(s) to their Highlight names.",
                    moved);
        }
    }

    /** Repoints pinned favourites at the two module ids the rename changed. */
    private int migrateHighlightFavourites(SBSConfig config) {
        if (config.favorites == null || config.favorites.optionIds == null) {
            return 0;
        }
        int moved = 0;
        java.util.List<String> ids = config.favorites.optionIds;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (id == null) {
                continue;
            }
            if (id.startsWith("party_esp:")) {
                ids.set(i, "party_highlight:" + id.substring("party_esp:".length()));
                moved++;
            } else if (id.startsWith("frozen_corpse_esp:")) {
                ids.set(i, "frozen_corpse_highlight:" + id.substring("frozen_corpse_esp:".length()));
                moved++;
            }
        }
        return moved;
    }

    @SuppressWarnings("deprecation")
    private void migrateChromaSpeed(SBSConfig config) {
        if (config.itemOverlay == null || config.theme == null) {
            return;
        }
        Integer[] legacy = {config.itemOverlay.chromaEnchantSpeed,
                config.itemOverlay.chromaUltimateSpeed, config.itemOverlay.chromaSkillSpeed};
        if (legacy[0] == null && legacy[1] == null && legacy[2] == null) {
            return;   // already migrated, or a config written after the change
        }
        for (Integer value : legacy) {
            if (value != null && value != LEGACY_CHROMA_SPEED_DEFAULT) {
                config.theme.chromaSpeed = Math.max(Chroma.MIN_SPEED,
                        Math.min(Chroma.MAX_SPEED, value));
                break;
            }
        }
        config.itemOverlay.chromaEnchantSpeed = null;
        config.itemOverlay.chromaUltimateSpeed = null;
        config.itemOverlay.chromaSkillSpeed = null;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Migrated per-effect chroma speeds -> chromaSpeed={}",
                config.theme.chromaSpeed);
    }

    /** What all three per-effect chroma speeds defaulted to before they were folded into one. */
    private static final int LEGACY_CHROMA_SPEED_DEFAULT = 100;

    private void migrateBuffCards(SBSConfig config) {
        if (config.buffs == null
                || (config.buffs.showGodPotion == null && config.buffs.showCookieBuff == null)) {
            return;
        }
        config.buffs.showGodPotion = null;
        config.buffs.showCookieBuff = null;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS] Migrated buff timers from HUD cards to Custom Scoreboard rows");
    }

    /**
     * Carries the retired desktop-notification toggles onto the alert-channel system.
     *
     * <p>Each old toggle becomes <b>Title + Sound</b> - the in-game path those alerts already had.
     * Deliberately <i>not</i> the narrator: someone who switched on a desktop notification consented
     * to a popup, not to their computer speaking. Turning speech on for them during an update would
     * be a bigger behaviour change than the removal it is replacing, so the narrator stays opt-in
     * and the one-time notice below points at it.
     *
     * <p>The legacy fields are boxed, so this only fires for a config that actually carried them and
     * nulling them afterwards makes it a one-shot: a channel switched off later stays off.
     */
    @SuppressWarnings("deprecation")
    private void migrateDesktopNotifications(SBSConfig config) {
        boolean migrated = false;
        if (config.garden != null) {
            if (config.garden.pestSpawnNotification != null) {
                if (config.garden.pestSpawnNotification) {
                    config.garden.pestSpawnChannels |= AlertChannels.TITLE_AND_SOUND;
                    migrated = true;
                }
                config.garden.pestSpawnNotification = null;
            }
            if (config.garden.pestReadyNotification != null) {
                if (config.garden.pestReadyNotification) {
                    config.garden.pestReadyChannels |= AlertChannels.TITLE_AND_SOUND;
                    migrated = true;
                }
                config.garden.pestReadyNotification = null;
            }
        }
        if (config.reminders != null && config.reminders.systemNotification != null) {
            if (config.reminders.systemNotification) {
                // Reminders already always send a chat line, so the useful addition is the ping.
                config.reminders.extraChannels |= AlertChannel.SOUND.bit();
                migrated = true;
            }
            config.reminders.systemNotification = null;
        }
        if (migrated) {
            // Arms the one-time in-game notice: a setting that quietly changed shape needs to be
            // explained where the player will see it, not only in a log nobody reads.
            config.alerts.desktopRemovalNoticeShown = false;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS] Migrated desktop notifications to alert channels (title + sound).");
        }
    }

    /**
     * Moves the Hunting/Fusion hotkeys from their old home in the Fishing settings into the new
     * Hunting module. The boxed legacy fields are only non-null when an older config carried them,
     * so a bound key survives the move; they are nulled afterwards so the next write drops them.
     */
    @SuppressWarnings("deprecation")
    private void migrateHuntingKeys(SBSConfig config) {
        if (config.fishing == null || config.hunting == null) {
            return;
        }
        boolean migrated = false;
        if (config.fishing.huntingKey != null) {
            config.hunting.huntingKey = config.fishing.huntingKey;
            config.fishing.huntingKey = null;
            migrated = true;
        }
        if (config.fishing.huntingCommand != null) {
            config.hunting.huntingCommand = config.fishing.huntingCommand;
            config.fishing.huntingCommand = null;
            migrated = true;
        }
        if (config.fishing.shardFusionKey != null) {
            config.hunting.shardFusionKey = config.fishing.shardFusionKey;
            config.fishing.shardFusionKey = null;
            migrated = true;
        }
        if (config.fishing.shardFusionCommand != null) {
            config.hunting.shardFusionCommand = config.fishing.shardFusionCommand;
            config.fishing.shardFusionCommand = null;
            migrated = true;
        }
        if (migrated) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS] Migrated hunting hotkeys fishing -> hunting");
        }
    }

    /**
     * Folds each keybind's old inline single action into its new {@code actions} list, so a player
     * who already had keybinds keeps every one of them working unchanged (the migrated step gets
     * delay 0, i.e. the same instant behaviour as before). The legacy fields are nulled afterwards
     * so the next write drops them from config.json for good.
     */
    private void migrateKeybindActions(SBSConfig config) {
        if (config.keybinds == null) {
            return;
        }
        int migrated = 0;
        for (var keybind : config.keybinds) {
            if (keybind != null && keybind.migrateLegacyAction()) {
                migrated++;
            }
        }
        if (migrated > 0) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS] Migrated {} keybind(s) to the delayed-action list", migrated);
        }
    }

    /** Persists the current in-memory config to disk. */
    public void save() {
        writeToDisk(get());
    }

    /**
     * Throws away every setting in the active profile and writes a fresh default config.
     *
     * <p><b>Scope is exactly one file:</b> {@code config.json} of the profile you are on. Things that
     * deliberately live outside it are untouched - the licence token
     * ({@code license/token.json}, shared by every profile), the HUD layout
     * ({@code gui/hud_layout.json}), and the per-account/profile caches under {@code Accounts/}.
     * That split is the whole reason those files exist, and a reset that silently signed you out
     * would be a far worse surprise than one that left a saved layout behind.
     */
    public void resetToDefaults() {
        config = new SBSConfig();
        writeToDisk(config);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Reset all settings in profile '{}' to defaults",
                ConfigProfiles.getInstance().active());
    }

    /**
     * Switches off every feature toggle in the config, and returns how many it turned off.
     *
     * <p>Every {@code boolean} in the settings tree goes {@code false} - not just the fields named
     * {@code enabled}, because only 53 of the 82 settings groups have one and a sweep of those alone
     * would leave whole modules (the HUD, Visuals, Item Overlay, Dungeons...) running while claiming
     * everything was off.
     *
     * <p><b>Narrowing flags are swept too, and that is safe:</b> a flag like "only my minibosses"
     * reads as "show more" when false, but the feature it narrows is itself now off, so nothing is
     * drawn either way. Turning them back on restores their own defaults through
     * {@link #resetToDefaults()}.
     *
     * <p>Two things are deliberately skipped. <b>Collections are never entered</b>: a saved keybind
     * or route is <i>data</i>, and it carries booleans of its own that have nothing to do with
     * feature switches - sweeping into a list would quietly corrupt what the player saved rather
     * than disable anything. <b>Boxed {@code Boolean} fields are never written</b>: those are the
     * legacy fields the migrations read, where {@code null} means "nothing to migrate" and writing
     * {@code false} would resurrect a dead key and re-run a migration against it.
     */
    public int disableAllFeatures() {
        SBSConfig target = get();
        int changed = 0;
        for (Field field : SBSConfig.class.getFields()) {
            Object group = readGroup(target, field);
            if (group != null) {
                changed += disableIn(group, true);
            }
        }
        writeToDisk(target);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Disabled {} feature toggle(s)", changed);
        return changed;
    }

    /** The settings object held by a field, or {@code null} when the field is not one. */
    private static Object readGroup(Object owner, Field field) {
        if (Modifier.isStatic(field.getModifiers()) || !isSettingsType(field.getType())) {
            return null;
        }
        try {
            return field.get(owner);
        } catch (IllegalAccessException unreachable) {
            return null;
        }
    }

    /** A settings group is a class declared inside {@link SBSConfig} - never a list, map or enum. */
    private static boolean isSettingsType(Class<?> type) {
        return type.getEnclosingClass() == SBSConfig.class && !type.isEnum();
    }

    /**
     * Sets every primitive boolean on one settings object to {@code false}, descending one level into
     * nested settings objects. {@code recurse} stops it going deeper than that - the config is flat
     * inside a group, and an unbounded walk is how a sweep like this ends up inside saved data.
     */
    private static int disableIn(Object group, boolean recurse) {
        int changed = 0;
        for (Field field : group.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            try {
                if (field.getType() == boolean.class) {
                    if (field.getBoolean(group)) {
                        field.setBoolean(group, false);
                        changed++;
                    }
                } else if (recurse && isSettingsType(field.getType())) {
                    Object nested = field.get(group);
                    if (nested != null) {
                        changed += disableIn(nested, false);
                    }
                }
            } catch (IllegalAccessException skip) {
                // A field the JVM will not let us touch is one we simply leave alone.
            }
        }
        return changed;
    }

    private void writeToDisk(SBSConfig data) {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                SBSFiles.GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to write config", e);
        }
    }
}
