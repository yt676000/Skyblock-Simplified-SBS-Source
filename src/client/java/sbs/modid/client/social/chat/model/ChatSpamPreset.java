/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.model;

import java.util.regex.Pattern;

/**
 * Better Chat's one-click spam filters: one row per known spam line, the pattern that recognises it
 * and the label its setting carries. Adding a filter is adding a constant - the settings page, the
 * config map and the session counters all come from this table.
 *
 * <p>Patterns are anchored and run on the colour-stripped line with runs of spaces collapsed
 * (stripping can leave {@code hit §a3 §c enemies} as a double space) and the client's
 * {@code (N)} / {@code (xN)} stacking counter cut off. A line shaped like player chat is never
 * matched, so somebody quoting "Autopet equipped your …" in party chat stays visible.
 *
 * <p>The {@link #id} is the config key and must never change; the first three are the ids the old
 * standalone booleans migrate to.
 */
public enum ChatSpamPreset {

    COMMANDS_TOO_FAST("commandsTooFast", "Commands Too Fast",
            "Hypixel's 'You are sending commands too fast' warning.",
            "(?i).*sending commands too fast.*"),
    NOT_ENOUGH_MANA("notEnoughMana", "Not Enough Mana",
            "The 'not enough mana' spam from mashing an ability.",
            "(?i).*not enough mana.*"),
    BLOCKS_IN_THE_WAY("blocksInTheWay", "Blocks In The Way",
            "'There are blocks in the way!' from teleport abilities.",
            "(?i).*blocks in the way.*"),
    AUTOPET("autopet", "Autopet",
            "'Autopet equipped your [Lvl 93] Ammonite!' when an Autopet rule swaps your pet.",
            "Autopet equipped your \\[Lvl \\d+] .+?!(?: VIEW RULE)?"),
    ABILITY_DAMAGE("abilityDamage", "Ability Damage",
            "'Your Implosion hit 5 enemies for 3.3M damage.' - also Wither Impact, Giant's Sword and"
                    + " the like. The Ability Damage card still records every hit.",
            "Your [A-Za-z' ]+ hit \\d+ enem(?:y|ies) for [\\d.,]+[kKmMbB]? damage\\."),
    KILL_COMBO("killCombo", "Kill Combo Gains",
            "'+10 Kill Combo +10 coins per kill' and the other combo milestones.",
            "\\+\\d+ Kill Combo .+"),
    KILL_COMBO_EXPIRED("killComboExpired", "Kill Combo Expired",
            "'Your Kill Combo has expired! You reached a 20 Kill Combo!'",
            "Your Kill Combo has expired! You reached a \\d+ Kill Combo!"),
    LOOT_SHARE("lootShare", "Loot Share",
            "'LOOT SHARE You received loot for assisting <player>!' - the loot itself still arrives"
                    + " and is still counted.",
            "LOOT SHARE You received loot for assisting \\w{1,16}!"),
    ABILITY_COOLDOWN("abilityCooldown", "Ability Cooldown",
            "'This ability is on cooldown for 3s.'",
            "This ability is on cooldown for \\d+s\\."),
    ABILITY_READY("abilityReady", "Ability Ready",
            "'<Ability> is ready to use! Press DROP to activate it!'",
            ".+ is ready to use! Press DROP to activate it!");

    /** {@code [rank] name: }, {@code Party > }, {@code From }, … - a line somebody typed. */
    private static final Pattern PLAYER_CHAT = Pattern.compile(
            "^(?:(?:Party|Guild|Officer|Co-op) > |(?:From|To) |(?:\\[[^]]+] )*[A-Za-z0-9_]{1,16}(?: [^\\s:]{1,4})?: )");

    /** The stacking counter a chat mod (or Better Chat itself) appends: {@code (3)} / {@code (x3)}. */
    private static final Pattern STACK_SUFFIX = Pattern.compile("\\s*\\(x?\\d+\\)$");

    private static final Pattern SPACES = Pattern.compile("\\s{2,}");

    private static final ChatSpamPreset[] ALL = values();

    public final String id;
    public final String label;
    public final String description;
    private final Pattern pattern;

    /** Lines hidden (or compacted) this session. Render thread only; never persisted, no content. */
    private int hiddenThisSession;

    ChatSpamPreset(String id, String label, String description, String regex) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.pattern = Pattern.compile(regex);
    }

    /**
     * Normalises a colour-stripped chat line for matching: collapses space runs and cuts the
     * stacking counter. Returns {@code null} for a line shaped like player chat, which no preset may
     * claim.
     */
    public static String normalise(String plain) {
        if (plain == null) {
            return null;
        }
        String text = SPACES.matcher(plain.trim()).replaceAll(" ");
        text = STACK_SUFFIX.matcher(text).replaceFirst("");
        return PLAYER_CHAT.matcher(text).find() ? null : text;
    }

    /** The preset whose pattern matches this colour-stripped line, or {@code null}. */
    public static ChatSpamPreset match(String plain) {
        String text = normalise(plain);
        if (text == null) {
            return null;
        }
        for (ChatSpamPreset preset : ALL) {
            if (preset.pattern.matcher(text).matches()) {
                return preset;
            }
        }
        return null;
    }

    public void countHidden() {
        hiddenThisSession++;
    }

    public int hiddenThisSession() {
        return hiddenThisSession;
    }
}
