/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.cooldowns;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ability Ready Alert: "Pickobulus ready" through the player's alert channels the moment Hypixel
 * prints "Pickobulus is now available!", plus an optional HUD countdown once a cooldown has been
 * learned. The line parsing is {@link AbilityReady}; settings sit on the Mining Helpers card.
 *
 * <p>Display and sound only. Nothing is used, swapped or clicked for the player.
 *
 * <p>Island and server changes clear nothing: the cooldown runs on Hypixel's side per profile and
 * the ready line still arrives after a warp. A use line with no ready line within ten minutes is
 * simply dropped by {@link AbilityReady}.
 */
public final class AbilityReadyAlert {

    private static final AbilityReadyAlert INSTANCE = new AbilityReadyAlert();
    private static final int PAD = 5;

    private final AbilityReady parser = new AbilityReady();
    /** Ability -> the timer key of the item it was last used with (for the holding gate + overlay). */
    private final Map<String, String> toolOf = new ConcurrentHashMap<>();
    /**
     * ESTIMATED ready times (ms) for axe abilities that have a lore "Cooldown: Ns" - started by the
     * right-click, dropped the moment Hypixel's own ready line arrives. Never without a lore cooldown.
     */
    private final Map<String, Long> estimatedReadyAt = new ConcurrentHashMap<>();
    /** Capture log: each distinct axe lore block / chat line once per session. */
    private final Set<String> logged = new HashSet<>();
    /** When an axe was last right-clicked - chat within {@link #CAPTURE_WINDOW_MS} of it is logged. */
    private volatile long axeUsedAt;
    private static final long CAPTURE_WINDOW_MS = 2_000L;

    private AbilityReadyAlert() {
    }

    public static AbilityReadyAlert getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.MiningHelpersSettings cfg() {
        return ConfigManager.getInstance().get().miningHelpers;
    }

    /** From the chat hook, with the colour-stripped line. */
    public void onChat(String text) {
        captureAxeChat(text);
        AbilityReady.Event event = parser.onChat(text, System.currentTimeMillis());
        if (event == null) {
            return;
        }
        SBSConfig.MiningHelpersSettings cfg = cfg();
        switch (event) {
            case AbilityReady.Used used -> {
                String key = AbilityCooldowns.getInstance().lastUsedKey();
                if (key != null) {
                    toolOf.put(used.ability(), key);
                }
                remember(cfg, used.ability(), used.tool());
            }
            case AbilityReady.Ready ready -> onReady(cfg, ready);
        }
    }

    private void onReady(SBSConfig.MiningHelpersSettings cfg, AbilityReady.Ready ready) {
        String ability = ready.ability();
        estimatedReadyAt.remove(ability);   // Hypixel's own word replaces the estimate
        remember(cfg, ability, null);
        String key = toolOf.get(ability);
        // Hypixel's word beats our lore-derived clock: the overlay on that item drains to nothing.
        AbilityCooldowns.getInstance().clear(key);
        if (ready.learnedSeconds() > 0) {
            cfg.abilityReadyLearned.put(ability, ready.learnedSeconds());
            ConfigManager.getInstance().save();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Ability] {} learned cooldown {}s", ability,
                    ready.learnedSeconds());
        }
        if (!cfg.abilityReady || !Boolean.TRUE.equals(cfg.abilityReadyAbilities.get(ability))) {
            return;
        }
        if (cfg.abilityReadyOnlyHolding && key != null && !key.equals(heldKey())) {
            return;
        }
        Alerts.send(new Alerts.Alert(ability + " ready", ability + " is available again",
                SbsAudio.Tone.CHIME, null), cfg.abilityReadyChannels);
    }

    /** Adds a first-seen ability to the list with its default; never changes one already there. */
    private static void remember(SBSConfig.MiningHelpersSettings cfg, String ability, String tool) {
        if (!cfg.abilityReadyAbilities.containsKey(ability)) {
            cfg.abilityReadyAbilities.put(ability, AbilityReady.defaultOn(ability, tool));
            ConfigManager.getInstance().save();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Ability] new ability seen: {} (tool {})",
                    ability, tool);
        }
    }

    // ------------------------------------------------------------------ axes

    /**
     * From the right-click hooks ({@code ItemUseMixin}). For an axe: logs its ability blocks, opens
     * the chat capture window, and starts an ESTIMATED timer for a triggered ability with a lore
     * cooldown. Passive abilities (Fig Hew's Frenzy) and ones without a cooldown get nothing.
     */
    public void onItemUse(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        var loreComponent = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (loreComponent == null) {
            return;
        }
        List<String> lore = new ArrayList<>();
        for (Component line : loreComponent.lines()) {
            lore.add(line.getString());
        }
        if (!AxeAbilityLore.isAxe(lore)) {
            return;
        }
        long now = System.currentTimeMillis();
        axeUsedAt = now;
        String item = stack.getHoverName().getString().replaceAll("§.", "");
        List<AxeAbilityLore.Ability> abilities = AxeAbilityLore.abilities(lore);
        log("lore|" + item + abilities, "[SBS][Ability] axe '{}' lore abilities={}", item, abilities);
        SBSConfig.MiningHelpersSettings cfg = cfg();
        for (AxeAbilityLore.Ability ability : abilities) {
            if (!ability.timed()) {
                continue;
            }
            Long due = estimatedReadyAt.get(ability.name());
            if (due != null && due > now) {
                continue;   // still cooling: a second click does not restart Hypixel's cooldown
            }
            remember(cfg, ability.name(), "axe");
            toolOf.put(ability.name(), AbilityCooldowns.key(stack));
            estimatedReadyAt.put(ability.name(), now + ability.cooldownSeconds() * 1000L);
        }
    }

    /** Fires the ESTIMATED alerts whose time has come. Called every client tick. */
    public void onClientTick() {
        if (estimatedReadyAt.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        SBSConfig.MiningHelpersSettings cfg = cfg();
        for (Map.Entry<String, Long> e : new ArrayList<>(estimatedReadyAt.entrySet())) {
            if (e.getValue() > now) {
                continue;
            }
            String ability = e.getKey();
            estimatedReadyAt.remove(ability);
            if (!cfg.abilityReady || !Boolean.TRUE.equals(cfg.abilityReadyAbilities.get(ability))) {
                continue;
            }
            String key = toolOf.get(ability);
            if (cfg.abilityReadyOnlyHolding && key != null && !key.equals(heldKey())) {
                continue;
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Ability] {} ready (ESTIMATED from lore cooldown)",
                    ability);
            Alerts.send(new Alerts.Alert(ability + " ready", ability + " should be available again "
                    + "(estimated from its lore cooldown)", SbsAudio.Tone.CHIME, null),
                    cfg.abilityReadyChannels);
        }
    }

    /** Chat right after an axe right-click that talks about abilities - the lines Step 1 wants. */
    private void captureAxeChat(String text) {
        if (text == null || System.currentTimeMillis() - axeUsedAt > CAPTURE_WINDOW_MS) {
            return;
        }
        String plain = text.replaceAll("§.", "").strip();
        String lower = plain.toLowerCase(Locale.ROOT);
        if (lower.contains("ability") || lower.contains("cooldown") || lower.contains("available")) {
            log("chat|" + plain, "[SBS][Ability] axe chat within {}ms of right-click: '{}'",
                    CAPTURE_WINDOW_MS, plain);
        }
    }

    private void log(String key, String format, Object a, Object b) {
        synchronized (logged) {
            if (logged.size() >= 200 || !logged.add(key)) {
                return;
            }
        }
        SkyblockSimplifiedSBS.LOGGER.info(format, a, b);
    }

    private static String heldKey() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : AbilityCooldowns.key(player.getMainHandItem());
    }

    /** The countdown line, one row per enabled ability used since it was last ready. */
    public void render(GuiGraphicsExtractor g) {
        SBSConfig.MiningHelpersSettings cfg = cfg();
        if (!cfg.abilityReady || !cfg.abilityReadyHud || HudLayout.isHidden(HudElement.ABILITY_READY)) {
            return;
        }
        long now = System.currentTimeMillis();
        List<String> lines = new ArrayList<>(2);
        for (String ability : parser.cooling()) {
            if (!Boolean.TRUE.equals(cfg.abilityReadyAbilities.get(ability))) {
                continue;
            }
            Integer learned = cfg.abilityReadyLearned.get(ability);
            lines.add(ability + ": " + AbilityReady.status(parser.usedAt(ability),
                    learned == null ? -1 : learned, now));
        }
        for (Map.Entry<String, Long> e : estimatedReadyAt.entrySet()) {
            if (Boolean.TRUE.equals(cfg.abilityReadyAbilities.get(e.getKey()))) {
                long left = Math.max(0, (e.getValue() - now + 999) / 1000);
                lines.add(e.getKey() + ": in " + left + "s (ESTIMATED)");
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        width += PAD * 2;
        int height = PAD * 2 + lineH * lines.size() - 2;
        HudElement.Bounds b = HudElement.ABILITY_READY.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.ABILITY_READY, x, y, width, height);
        HudLayout.begin(g, HudElement.ABILITY_READY);
        HudCard.draw(g, x, y, width, height);
        int iy = y + PAD;
        for (String line : lines) {
            g.text(font, Component.literal(line), x + PAD, iy, SBSTheme.ACCENT_BRIGHT);
            iy += lineH;
        }
        HudLayout.end(g);
    }
}
