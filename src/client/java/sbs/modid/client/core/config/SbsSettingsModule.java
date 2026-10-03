/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config;

import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleManager;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.ui.settings.ConfirmClick;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * SBS Settings: the actions that act on <i>the mod itself</i> rather than on any one feature.
 *
 * <p>Pinned directly above the Licence Token, because these are the two entries that are about SBS
 * as a whole rather than about playing SkyBlock - and because someone hunting for "how do I undo all
 * this" should find it without knowing which module broke.
 *
 * <p><b>Both actions here are destructive and irreversible, so both are two-step.</b> The first
 * click only arms the button and says what will happen; the second, within
 * {@link ConfirmClick#WINDOW_MS} ms, performs it. A settings list is scrolled past and mis-clicked all
 * the time, and "reset everything" is the one row where a stray click must not be enough - while a
 * modal dialog for two buttons would be heavier than the rest of the settings UI.
 */
public final class SbsSettingsModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public SbsSettingsModule() {
    }

    @Override
    public String id() {
        return ModuleManager.SBS_SETTINGS_ID;
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.PINNED;
    }

    @Override
    public String displayName() {
        return "SBS Settings";
    }

    @Override
    public String description() {
        return "Mod-wide actions: reset every setting to default, or switch every feature off";
    }

    @Override
    public int accentColor() {
        return 0xFFE0605F;   // the warning red - everything on this card is destructive
    }

    /**
     * A button that has to be clicked twice. Holds only the moment it was armed, so it disarms by
     * itself simply by the window passing - nothing has to tick it or clean it up.
     */
    private final ConfirmClick resetConfirm = new ConfirmClick();
    private final ConfirmClick disableConfirm = new ConfirmClick();

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.label("— Setup & Updates —"),
                SettingRow.button("Show The Setup Again",
                                sbs.modid.client.ui.wizard.logic.WizardActions::replayOnboarding)
                        .anchor("wizard_replay")
                        .describe("Runs the first-run setup again - the look picker and the "
                                + "brightness and view-distance settings. Nothing is reset by "
                                + "opening it; every page still starts on whatever you have now."),
                SettingRow.toggle("Show Update Notices",
                                () -> !sbs.modid.client.ui.wizard.logic.WizardAccount.state()
                                        .showcaseOptOut(),
                                () -> {
                                    var state = sbs.modid.client.ui.wizard.logic.WizardAccount.state();
                                    state.setShowcaseOptOut(!state.showcaseOptOut());
                                })
                        .anchor("wizard_update_notices")
                        .describe("Whether a short screen appears after an update listing what "
                                + "changed. Off means it never appears again. This is the same "
                                + "switch as the one on that screen - either place sets both, "
                                + "because it is one setting and not two. Default: on."),
                SettingRow.button("Show What Changed",
                                sbs.modid.client.ui.wizard.logic.WizardActions::replayShowcase)
                        .anchor("wizard_replay_showcase")
                        .describe("Opens the update pages for this version, whether or not you "
                                + "have already seen them. Says so plainly when there are none, "
                                + "which is the case until a version ships with something to show."),
                SettingRow.label("§8Stored per Minecraft account, not per config profile"),

                SettingRow.label("— Settings Screen —"),
                SettingRow.enumOptions("Categories",
                                sbs.modid.client.ui.settings.CategoryFolding::mode,
                                sbs.modid.client.ui.settings.CategoryFolding::pick,
                                sbs.modid.client.ui.settings.CategoryFolding.Mode::displayName)
                        .anchor("category_folding")
                        .describe("Whether the category headers in the list on the left start "
                                + "folded. Always Open and Always Closed set them every time you "
                                + "open SBS settings. Open On Startup and Closed On Startup set "
                                + "them the first time you open it after starting the game and "
                                + "then leave your folds alone until you quit. Save Current keeps "
                                + "exactly what you folded, across restarts. Default: Closed On "
                                + "Startup."),
                SettingRow.label("§8Click a category header to fold it; searching unfolds them all"),

                SettingRow.label("— Memory —"),
                SettingRow.toggle("Memory Guard",
                        () -> ConfigManager.getInstance().get().memory.enabled,
                        () -> {
                            SBSConfig.MemorySettings memory = ConfigManager.getInstance().get().memory;
                            memory.enabled = !memory.enabled;
                            ConfigManager.getInstance().save();
                        })
                        .describe("Watches the heap over a long session. It reports in the log when "
                                + "memory is being held rather than merely used - the thing that "
                                + "makes an evening of server hops end in a crash - and when the "
                                + "heap gets genuinely tight it hands back what this mod is holding, "
                                + "remembered terrain first, since that costs nothing to rebuild. It "
                                + "does not collect garbage on a timer: that cannot free anything "
                                + "still in use and is a stutter for nothing."),
                SettingRow.label(sbs.modid.client.core.memory.MemoryGuard.statusLine()),
                SettingRow.label("§8Read the log for [SBS][Memory] lines after a long session"),

                SettingRow.label("— Alerts —"),
                SettingRow.rangeSlider("Alert Volume", 1, 100,
                        () -> ConfigManager.getInstance().get().alerts.soundVolume,
                        value -> {
                            ConfigManager.getInstance().get().alerts.soundVolume = value;
                            ConfigManager.getInstance().save();
                        }, "%")
                        .describe("How loud the mod's own alert pings are. This is a separate audio "
                                + "output from the game's, so these stay audible with every "
                                + "Minecraft volume slider at zero - which is the point of it."),
                SettingRow.rangeSlider("Narrator Volume", 1, 100,
                        () -> ConfigManager.getInstance().get().alerts.narratorVolume,
                        value -> {
                            ConfigManager.getInstance().get().alerts.narratorVolume = value;
                            ConfigManager.getInstance().save();
                        }, "%")
                        .describe("How loud spoken alerts are, relative to your system voice's own "
                                + "level. Speaking never changes your Minecraft narrator setting."),
                SettingRow.enumOptions("Narrator Language",
                        () -> sbs.modid.client.core.alert.NarratorLanguage
                                .orAuto(ConfigManager.getInstance().get().alerts.narratorLanguage),
                        value -> {
                            ConfigManager.getInstance().get().alerts.narratorLanguage = value;
                            ConfigManager.getInstance().save();
                        }, v -> v.displayName())
                        .anchor("narrator_language")
                        .describe("Which language numbers are spoken in. Your system voice reads "
                                + "digits by its own locale, so a German voice says \"vier\" in the "
                                + "middle of an English alert - the words are only accented but the "
                                + "numbers are fully translated, which is why they stand out. "
                                + "Numbers are written out as words before speaking so the whole "
                                + "announcement lands in one language. Auto follows your Minecraft "
                                + "language. Only what is spoken changes: chat and the HUD keep "
                                + "their digits."),
                SettingRow.label("§8Alerts stay in English - this is what the numbers are read as"),
                SettingRow.label("Alert audio: "
                        + sbs.modid.client.core.audio.SbsAudio.statusText()),
                SettingRow.label("Narrator: " + (sbs.modid.client.core.alert.NarratorVoice.available()
                        ? "available" : "§7unavailable on this system")),
                SettingRow.label("§8Which alerts use which channel is set on each feature's page"),
                SettingRow.label("§8Run /sbs testnotify to fire a test through your channels"),

                SettingRow.label("— Share Settings —"),
                SettingRow.label("§8" + sbs.modid.client.core.config.share.ConfigShare.shareableCount()
                        + " settings travel; nothing else does"),
                SettingRow.button("Export Settings To Clipboard", this::exportConfig)
                        .describe("Copies your shareable settings as a code you can paste to "
                                + "somebody. NOT included: your licence, your Hypixel API key, any "
                                + "player names, your trackers, keybinds, command shortcuts and "
                                + "inventory buttons - none of those can travel, by design."),
                SettingRow.button("Import Settings From Clipboard", this::importConfig)
                        .describe("Reads a code from your clipboard and shows you exactly what it "
                                + "would change before anything happens. Nothing is applied until "
                                + "you press Apply on that screen."),
                SettingRow.label("§8An import always shows a preview first - there is no way past it"),
                SettingRow.button("Restore Last Backup", this::restoreBackup)
                        .describe("Puts back the config as it was before your last import. Your "
                                + "current settings are backed up first, so this is itself undoable."),

                SettingRow.label("— Sidebar —"),
                SettingRow.button("Edit Sidebar Layout", () -> net.minecraft.client.Minecraft.getInstance()
                                .setScreenAndShow(new sbs.modid.client.ui.settings.layout.SidebarLayoutScreen(
                                        new sbs.modid.client.ui.screen.SBSMainScreen())))
                        .describe("Opens an editor where you put the sidebar's categories and pages in "
                                + "your own order, move pages between categories and make categories "
                                + "of your own. Saved per config profile and included in Share "
                                + "Settings. Reset To Default in the editor brings back the built-in "
                                + "layout."),
                SettingRow.label("§8Favourites and the Licence Token always stay on top"),

                SettingRow.label("— Careful —"),
                SettingRow.label("§7Both actions below take effect immediately and cannot be undone"),
                SettingRow.label("§7Each needs a second click within 5s to confirm"),

                SettingRow.cycle("Reset All Settings",
                        () -> resetConfirm.state("§cClick again to reset!", "Click to reset"),
                        this::resetAll)
                        .describe("Puts every SBS setting in the current config profile back to "
                                + "its default. Your licence token, your HUD layout and the "
                                + "per-profile caches live in their own files and are NOT touched. "
                                + "Click once to arm, again within 5 seconds to confirm."),
                SettingRow.label("§8Keeps: licence token, HUD layout, storage/pet caches"),
                SettingRow.label("§8Resets: every toggle, colour, keybind and value in this profile"),

                SettingRow.cycle("Disable All Features",
                        () -> disableConfirm.state("§cClick again to disable!", "Click to disable"),
                        this::disableAll)
                        .describe("Switches off every feature toggle in SBS at once, leaving your "
                                + "colours, keybinds and values exactly as they are - so turning "
                                + "individual features back on gives you your setup back. Use it "
                                + "to check whether SBS is causing something. Click once to arm, "
                                + "again within 5 seconds to confirm."),
                SettingRow.label("§8Only switches things OFF - your settings themselves are kept"),
                SettingRow.label("§8Turn features back on individually, or use Reset above"));
    }

    /** Wipes the profile's settings, then re-derives the palette so the change is visible at once. */
    // ------------------------------------------------------------------
    // Share settings
    // ------------------------------------------------------------------

    /**
     * Copies the shareable settings to the clipboard and says plainly what did NOT go with them.
     *
     * <p>Saying so at the moment of export is the point: a player about to paste a code into Discord
     * is exactly the person who needs to know their licence and their API key are not in it, and
     * telling them afterwards is telling them too late.
     */
    private void exportConfig() {
        try {
            String encoded = sbs.modid.client.core.config.share.ConfigShare.export();
            net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(encoded);
            int count = sbs.modid.client.core.config.share.ConfigShare.shareableCount();
            SBSChat.send("§bCopied §f" + count + "§b setting(s) to your clipboard ("
                    + (sbs.modid.client.core.config.share.ConfigShare.byteLength(encoded) / 1024 + 1)
                    + " KB).");
            SBSChat.send("§7No licence, no API key, no player names, no trackers and no "
                    + "keybinds or commands are in it.");
        } catch (sbs.modid.client.core.config.share.ShareCodec.ShareException failed) {
            SBSChat.send("§c" + failed.getMessage());
        }
    }

    /** Reads the clipboard and opens the preview. Never applies anything itself. */
    private void importConfig() {
        String clipboard = net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard();
        try {
            var preview = sbs.modid.client.core.config.share.ConfigShare.read(clipboard);
            net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
                    new sbs.modid.client.core.config.share.ui.ImportPreviewScreen(
                            preview, sbs.modid.client.core.config.share.ConfigShare.rawJson(clipboard)));
        } catch (sbs.modid.client.core.config.share.ShareCodec.ShareException refused) {
            // Every refusal says which of the four things went wrong. A silent no-op here would
            // leave the player pressing the button again with the same clipboard.
            SBSChat.send("§c" + refused.getMessage());
        }
    }

    private void restoreBackup() {
        if (sbs.modid.client.core.config.share.ConfigShare.restoreLatest()) {
            SBSChat.send("§bRestored your previous config. Reopen the settings to see it.");
        } else {
            SBSChat.send("§7There is no backup to restore yet - one is written before every "
                    + "import.");
        }
    }

    private void resetAll() {
        if (!resetConfirm.click()) {
            return;
        }
        ConfigManager.getInstance().resetToDefaults();
        SBSTheme.refreshFromConfig();
        SBSChat.send("All SBS settings reset to default.");
    }

    private void disableAll() {
        if (!disableConfirm.click()) {
            return;
        }
        int count = ConfigManager.getInstance().disableAllFeatures();
        SBSTheme.refreshFromConfig();
        SBSChat.send("Disabled " + count + " SBS feature" + (count == 1 ? "" : "s") + ".");
    }
}
