/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing;

import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.ModuleSubgroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.skills.fishing.ui.SeaCreatureAnnounceScreen;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Sea Creature Announcer (Fishing): what your own rod just hooked, restated in chat in the
 * creature's rarity colour, so a Lord Jawbus never reads like a Squid and never scrolls away.
 *
 * <p>The runtime is {@code logic/SeaCreatureAnnouncer}, which also carries the reasoning about how
 * a spawn is detected and how it is known to be yours. This class is the settings page and nothing
 * else. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p><b>Its own card rather than rows on the Fishing page.</b> The request placed it at "Fishing →
 * Sea Creature Announcer". This mod's settings have exactly one level – a group in the sidebar, a
 * card in the group – so a literal sub-page does not exist to be built without reworking the
 * settings screen for one feature. A card of its own in the Skills group, named for the feature and
 * described as a fishing one, is the closest true thing: the announcer has fifteen settings and a
 * selection screen, which appended to Fishing's twenty-odd rows would have buried both. The Fishing
 * page keeps its own spawn alert, which is the HUD "!" and a different feature.
 *
 * <p><b>Ships off.</b> It writes into the player's chat unprompted, so they switch it on knowing
 * that. The nametag fallback's unknown-creature path is off separately again, for the reason given
 * on that setting.
 */
public final class SeaCreatureAnnouncerModule implements SbsModule {

    /** ServiceLoader needs a public no-arg constructor. */
    public SeaCreatureAnnouncerModule() {
    }

    @Override
    public String id() {
        return "sea_creature_announcer";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.SKILLS;
    }

    @Override
    public ModuleSubgroup subgroup() {
        return ModuleSubgroup.FISHING;
    }

    @Override
    public String displayName() {
        return "Sea Creature Announcer";
    }

    @Override
    public String description() {
        return "Fishing: announces in chat what your own rod hooked, coloured by rarity";
    }

    @Override
    public int accentColor() {
        return 0xFF3FB4FF;
    }

    private static SBSConfig.SeaCreatureAnnouncerSettings cfg() {
        return ConfigManager.getInstance().get().seaCreatureAnnouncer;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        List<SettingRow> rows = new ArrayList<>();

        rows.add(SettingRow.toggle("Sea Creature Announcer", () -> cfg().enabled,
                        () -> { cfg().enabled = !cfg().enabled; save(); })
                .describe("Prints a line of your own the moment a sea creature spawns from your "
                        + "rod - so you know what you hooked even when Hypixel's own message has "
                        + "already scrolled away. Only your catches: the spawn message is sent to "
                        + "the fisher who hooked it and nobody else."));

        rows.add(SettingRow.label("§8Only your own catches - never the pond's other fishers."));

        // --- The chat line ---
        rows.add(SettingRow.toggle("Announce in Chat", () -> cfg().chat,
                        () -> { cfg().chat = !cfg().chat; save(); })
                .describe("The chat line itself. Off leaves the title, sound and highlight below "
                        + "working on their own."));

        rows.add(SettingRow.text("Message Format", "{prefix} {creature} ({rarity})", 64,
                        () -> cfg().chatFormat,
                        v -> { cfg().chatFormat = v; save(); })
                .describe("How the line reads. {prefix}, {creature} and {rarity} are replaced; "
                        + "everything else prints as typed, so \"{creature}\" alone gives you bare "
                        + "names. The whole line is coloured by the creature's rarity."));

        rows.add(SettingRow.text("Message Prefix", "Sea Creature", 24,
                        () -> cfg().chatPrefix,
                        v -> { cfg().chatPrefix = v; save(); })
                .describe("What {prefix} becomes. The [SBS] tag in front of the line is added "
                        + "separately and is not part of this."));

        // --- Which spawns ---
        rows.add(SettingRow.label("§bWhich spawns"));

        rows.add(SettingRow.toggle("Announce Mythic", () -> cfg().announceMythic,
                        () -> { cfg().announceMythic = !cfg().announceMythic; save(); })
                .describe("Thunder, Lord Jawbus, Titanoboa, Nessie and the rest of the top tier."));

        rows.add(SettingRow.toggle("Announce Legendary", () -> cfg().announceLegendary,
                        () -> { cfg().announceLegendary = !cfg().announceLegendary; save(); })
                .describe("Water Hydra, Great White Shark, Alligator, The Loch Emperor and their "
                        + "tier."));

        rows.add(SettingRow.toggle("Announce Epic", () -> cfg().announceEpic,
                        () -> { cfg().announceEpic = !cfg().announceEpic; save(); })
                .describe("Guardian Defender, Manta Ray, Bayou Sludge and their tier."));

        rows.add(SettingRow.toggle("Announce Rare", () -> cfg().announceRare,
                        () -> { cfg().announceRare = !cfg().announceRare; save(); })
                .describe("The Crimson Isle regulars and the other mid-tier spawns. Off by "
                        + "default: on a lava rod these are most of what you catch."));

        rows.add(SettingRow.toggle("Announce Common", () -> cfg().announceCommon,
                        () -> { cfg().announceCommon = !cfg().announceCommon; save(); })
                .describe("Squid, Sea Walker and the rest of the filler. Off by default - an "
                        + "announcement that fires for every Squid is one you stop reading, which "
                        + "costs you the Jawbus it was meant to catch."));

        rows.add(SettingRow.button("Per-Creature Exceptions...",
                        () -> open(new SeaCreatureAnnounceScreen(
                                GuiStateManager.getInstance().getCurrentScreen())))
                .describe("Every sea creature this build knows, each one cycling Auto / Always / "
                        + "Never. Auto follows the rarity switches above; the other two override "
                        + "them, so you can keep one Mythic quiet or hear one Common."));

        rows.add(SettingRow.label(exceptionSummary()));

        // --- Extras ---
        rows.add(SettingRow.label("§bAlso when a spawn is announced"));

        rows.add(SettingRow.toggle("Show Title", () -> cfg().title,
                        () -> { cfg().title = !cfg().title; save(); })
                .describe("Puts the creature's name across the middle of the screen with its "
                        + "rarity underneath, through the shared alert titles."));

        rows.add(SettingRow.toggle("Play Sound", () -> cfg().sound,
                        () -> { cfg().sound = !cfg().sound; save(); })
                .describe("A ping on the mod's own audio output, so it is audible with the game "
                        + "muted and follows your alert volume."));

        rows.add(SettingRow.enumOptions("Sound", () -> cfg().soundTone(),
                        value -> { cfg().soundTone = value; save(); },
                        v -> v.name().charAt(0) + v.name().substring(1).toLowerCase(java.util.Locale.ROOT))
                .describe("Which ping: a two-note chime, a single soft blip, or the three-note "
                        + "alarm. Click to cycle."));

        rows.add(SettingRow.toggle("Highlight the Mob", () -> cfg().highlight,
                        () -> { cfg().highlight = !cfg().highlight; save(); })
                .describe("Boxes the creature that spawned, in its rarity's colour, so \"a Lord "
                        + "Jawbus spawned\" and \"that one, there\" are the same information."));

        rows.add(SettingRow.intField("Highlight For", 1, 60, () -> cfg().highlightSeconds,
                        v -> { cfg().highlightSeconds = v; save(); }, "s")
                .describe("How long the box stays on the creature before it fades out on its own."));

        // --- Detection ---
        rows.add(SettingRow.label("§bDetection"));

        rows.add(SettingRow.toggle("Read Nametags Too", () -> cfg().nametagFallback,
                        () -> { cfg().nametagFallback = !cfg().nametagFallback; save(); })
                .describe("When the spawn message names no creature this build knows, read the "
                        + "name off the mob's own nametag instead. This is what makes a fishing "
                        + "area newer than the mod work at all. Only nametags that appear at your "
                        + "own bobber, in the couple of seconds after your cast ends, are ever "
                        + "read."));

        rows.add(SettingRow.toggle("Announce Unknown Creatures", () -> cfg().announceUnknown,
                        () -> { cfg().announceUnknown = !cfg().announceUnknown; save(); })
                .describe("Announce a nametag this build has never heard of - Torrhus Canyon's "
                        + "sea creatures are not in the table yet, and this is how you would hear "
                        + "them. Off by default because the name is then whatever Hypixel wrote on "
                        + "the mob, not something we have checked. Every such sighting is written "
                        + "to the log either way."));

        rows.add(SettingRow.intField("Repeat Guard", 500, 15000, () -> cfg().dedupeMs,
                        v -> { cfg().dedupeMs = v; save(); }, "ms")
                .describe("How long one creature stays 'already announced'. Both detections see "
                        + "the same spawn, and this is what keeps the second one quiet. Lower it "
                        + "if fast repeat catches of the same creature go unannounced."));

        rows.add(SettingRow.label("§8Hypixel's own spawn message is never hidden or changed."));

        return rows;
    }

    /** The one-line state of the exception list, so the page says what the screen behind it holds. */
    private static String exceptionSummary() {
        int count = cfg().creatureModes == null ? 0 : cfg().creatureModes.size();
        return count == 0
                ? "§8No exceptions - every creature follows its rarity"
                : "§7" + count + " per-creature exception" + (count == 1 ? "" : "s") + " set";
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
    }
}
