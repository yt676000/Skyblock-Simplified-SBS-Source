/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.statbuffs.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.StatBuffSettings;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.tab.TabStats;
import sbs.modid.client.core.tab.TabStats.Stat;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.skills.farming.model.FarmingText;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.ui.hud.logic.HypixelHudState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tells you what an ability actually did to your stats: <i>"Ragnarock Axe: +247 Strength
 * (512 → 759)"</i>, and again when it wears off.
 *
 * <p><b>Why the tab list.</b> The number an item's lore promises is not the number you get - it is
 * multiplied by whatever else you are wearing, standing in and buffed by. The only place the real,
 * current Strength appears is Hypixel's tab-list stats widget ({@link TabStats}), so the answer is
 * arithmetic on what the widget said before and after: cache the value, cast, subtract.
 *
 * <p><b>What arms a measurement.</b> Using an item ({@link #onItemUse}) is the trigger, because a
 * right-click is a fact the client already has - {@code MultiPlayerGameMode.useItem} - and needs no
 * assumption about how Hypixel words anything. The cast line ("casting 3s") arms it too, on both the
 * surfaces it could appear on, but only as a second way in: if Hypixel rewords it the use hook has
 * already armed the window anyway. Everything past that point is measurement, not pattern matching.
 *
 * <p><b>Why a window and not "the next change".</b> A cast takes seconds, so the stats move well
 * after the click; {@link StatBuffSettings#windowSeconds} is how long a change still counts as that
 * ability's doing. Outside a window, a change is a gear swap or a potion - it silently becomes the
 * new baseline once it holds still, and is only announced when the player asks for that
 * ({@link StatBuffSettings#reportUnattributed}).
 *
 * <p><b>Settling.</b> Nothing is reported the instant a number moves. The widget updates a stat at a
 * time, so a buff granting Strength and Speed arrives as two different snapshots; reporting the
 * first one would print half the buff and then call the other half an unrelated change. A snapshot
 * has to repeat unchanged before it counts, which costs a fraction of a second and no accuracy.
 *
 * <p><b>Known limit</b>: swapping gear <i>while</i> a buff is running is folded into that buff's
 * numbers, because the baseline is the pre-cast one and there is no way to tell the two apart from
 * outside. The end-of-buff line is then wrong by the gear difference. Measuring against the item's
 * lore instead would trade this for being wrong every time, which is worse.
 */
public final class StatBuffTracker {

    private static final StatBuffTracker INSTANCE = new StatBuffTracker();

    /** Fast enough to catch the moment a buff lands, slow enough to be free. */
    private static final long SCAN_INTERVAL_MS = 200L;

    /** How long a snapshot must repeat unchanged before it is believed. */
    private static final long SETTLE_MS = 600L;

    /** A buff still "running" after this long is a mismeasurement - re-baseline instead. */
    private static final long MAX_BUFF_MS = 10 * 60 * 1000L;

    /** The label used when a change had no ability behind it. */
    private static final String UNATTRIBUTED = "Stats";

    private final Map<Stat, Double> baseline = new EnumMap<>(Stat.class);
    private boolean hasBaseline;

    /** The snapshot waiting to be believed, and since when. */
    private final Map<Stat, Double> pending = new EnumMap<>(Stat.class);
    private long pendingSince;

    /** The armed ability: what was used, and when. */
    private String armLabel;
    private long armAt;

    /** The buff currently being watched: its deltas, its name and when it landed. */
    private Map<Stat, Double> buff;
    private String buffLabel;
    private long buffStartedAt;

    private long lastScanAt;
    private String lastIsland = "";
    private String lastActionBar = "";
    private boolean patternsRegistered;

    private StatBuffTracker() {
    }

    public static StatBuffTracker getInstance() {
        return INSTANCE;
    }

    private static StatBuffSettings cfg() {
        return ConfigManager.getInstance().get().statBuffs;
    }

    // ---- arming -----------------------------------------------------------------------------

    /**
     * An item was right-clicked: whatever it does to the stats in the next few seconds is its doing.
     * Called from the item-use hook for every use, and cheap enough to be - it stores two fields.
     */
    public void onItemUse(ItemStack stack) {
        if (!cfg().enabled || stack == null || stack.isEmpty()) {
            return;
        }
        arm(label(stack));
    }

    /** Arms the window under {@code label}, unless a buff is already being watched. */
    private void arm(String label) {
        if (label == null || label.isEmpty() || buff != null) {
            return;
        }
        armLabel = label;
        armAt = System.currentTimeMillis();
    }

    /** The item's name as the player sees it, colour codes and leading star glyphs removed. */
    private static String label(ItemStack stack) {
        String name = FarmingText.strip(stack.getHoverName().getString())
                .replaceFirst("^[^A-Za-z0-9\\[]+", "").trim();
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    /** The held item's name - what a cast line, which never names the item, has to fall back on. */
    private static String heldLabel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        ItemStack held = mc.player.getMainHandItem();
        return held.isEmpty() ? null : label(held);
    }

    /**
     * Registers the cast-line patterns once. Deliberately narrow - only a line that says something
     * is casting for a number of seconds - and it only arms a window, so a false positive costs a
     * measurement that never happens rather than a wrong message.
     */
    private void registerPatterns() {
        if (patternsRegistered) {
            return;
        }
        patternsRegistered = true;
        ChatPatternRegistry.getInstance().register(
                "(?i)\\bcasting\\b[^\\n]{0,32}?\\d+(?:\\.\\d+)?\\s*s\\b",
                matcher -> {
                    if (cfg().enabled) {
                        arm(heldLabel());
                    }
                },
                "stat buffs: cast line");
    }

    /**
     * The same cast text can arrive on the action bar instead, where no chat pattern ever sees it.
     *
     * <p>Only a <i>new</i> bar line arms anything: the last line is kept until the next one
     * replaces it, so a stale "casting" would otherwise re-arm the window several times a second
     * for as long as Hypixel stayed quiet.
     */
    private void armFromActionBar() {
        String bar = HypixelHudState.getInstance().lastActionBar();
        if (bar == null || bar.equals(lastActionBar)) {
            return;
        }
        lastActionBar = bar;
        if (bar.toLowerCase(Locale.ROOT).contains("casting")) {
            arm(heldLabel());
        }
    }

    // ---- the measurement --------------------------------------------------------------------

    /** Called every client tick; throttles itself and does nothing at all while switched off. */
    public void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            reset();    // logged out: nothing measured here survives into the next session
            return;
        }
        StatBuffSettings cfg = cfg();
        if (!cfg.enabled) {
            return;
        }
        registerPatterns();

        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;

        // An island change is an instance boundary: the stats on the other side belong to a
        // different setup, and a delta across it would be pure fiction.
        String island = SkyBlockLocation.island();
        if (!island.equals(lastIsland)) {
            lastIsland = island;
            reset();
            return;
        }

        armFromActionBar();

        TabStats.Snapshot snapshot = TabStats.read();
        if (!snapshot.available()) {
            // No stats widget (or no SkyBlock). Forget the baseline rather than measure against one
            // taken who knows how long ago.
            reset();
            return;
        }
        Map<Stat, Double> values = snapshot.values();
        if (!hasBaseline) {
            adopt(values);
            return;
        }

        Map<Stat, Double> deltas = diff(baseline, values, cfg.minChange);

        if (buff != null) {
            if (deltas.isEmpty()) {
                if (settled(values, now)) {
                    reportEnd(now);
                }
                return;
            }
            pendingSince = 0;
            if (now - buffStartedAt > MAX_BUFF_MS) {
                SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][StatBuff] '{}' never returned to baseline in {} minutes - re-basing",
                        buffLabel, MAX_BUFF_MS / 60_000);
                buff = null;
                adopt(values);
            }
            return;
        }

        if (deltas.isEmpty()) {
            // A stat the widget did not list before has no "before" to subtract, so it can never be
            // a delta - but leaving it out of the baseline means it stays invisible for the rest of
            // the session. Adopt it quietly instead: an unknown starting value is not a gain, and
            // saying "+512 Strength" for a row that simply appeared would be inventing the 0.
            if (!values.keySet().equals(baseline.keySet()) && settled(values, now)) {
                adopt(values);
            } else if (values.keySet().equals(baseline.keySet())) {
                pendingSince = 0;
            }
            return;
        }
        if (!settled(values, now)) {
            return;
        }

        boolean armed = armLabel != null && now - armAt <= cfg.windowSeconds * 1000L;
        if (armed && hasGain(deltas)) {
            reportGain(armLabel, deltas, values);
            buff = deltas;
            buffLabel = armLabel;
            buffStartedAt = now;
            armLabel = null;
            return;
        }
        // Not an ability: a gear swap, a potion, a level-up. It becomes the new normal.
        if (cfg.reportUnattributed) {
            reportGain(UNATTRIBUTED, deltas, values);
        }
        adopt(values);
    }

    /** Takes {@code values} as the new baseline. */
    private void adopt(Map<Stat, Double> values) {
        baseline.clear();
        baseline.putAll(values);
        hasBaseline = true;
        pendingSince = 0;
        if (cfg().debugLog) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][StatBuff] baseline: {}", describe(values));
        }
    }

    /** Whether {@code values} has now repeated unchanged for long enough to be believed. */
    private boolean settled(Map<Stat, Double> values, long now) {
        if (pendingSince == 0 || !pending.equals(values)) {
            pending.clear();
            pending.putAll(values);
            pendingSince = now;
            return false;
        }
        return now - pendingSince >= SETTLE_MS;
    }

    /** Every stat that moved by at least {@code minChange}, as {@code now − baseline}. */
    private static Map<Stat, Double> diff(Map<Stat, Double> from, Map<Stat, Double> to,
                                          double minChange) {
        Map<Stat, Double> out = new EnumMap<>(Stat.class);
        for (Map.Entry<Stat, Double> entry : to.entrySet()) {
            Double before = from.get(entry.getKey());
            if (before == null) {
                continue;   // the widget only started listing it - not a change we watched happen
            }
            double delta = entry.getValue() - before;
            if (Math.abs(delta) >= Math.max(0.0001, minChange)) {
                out.put(entry.getKey(), delta);
            }
        }
        return out;
    }

    private static boolean hasGain(Map<Stat, Double> deltas) {
        for (double delta : deltas.values()) {
            if (delta > 0) {
                return true;
            }
        }
        return false;
    }

    /** Forgets everything measured. Called on logout, island change and a vanished widget. */
    public void reset() {
        baseline.clear();
        pending.clear();
        hasBaseline = false;
        pendingSince = 0;
        armLabel = null;
        buff = null;
        buffLabel = null;
    }

    // ---- the chat lines ---------------------------------------------------------------------

    /** "Ragnarock Axe  +247 Strength (512 → 759)" - one segment per stat that moved. */
    private void reportGain(String source, Map<Stat, Double> deltas, Map<Stat, Double> values) {
        MutableComponent line = Component.literal(" " + source)
                .withColor(SBSChat.PREFIX_COLOR);
        boolean first = true;
        for (Map.Entry<Stat, Double> entry : deltas.entrySet()) {
            Stat stat = entry.getKey();
            double delta = entry.getValue();
            Double before = baseline.get(stat);
            Double after = values.get(stat);
            line.append(Component.literal(first ? "  " : ", ").withColor(0xAAAAAA));
            first = false;
            line.append(Component.literal(signed(stat, delta))
                    .withColor(delta > 0 ? 0x55FF55 : 0xFF5555));
            line.append(Component.literal(" " + stat.displayName()).withColor(stat.color()));
            if (before != null && after != null) {
                line.append(Component.literal(
                                " (" + number(stat, before) + " → " + number(stat, after) + ")")
                        .withColor(0x888888));
            }
        }
        SBSChat.send(line);
        if (cfg().debugLog) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][StatBuff] {} -> {}", source, describe(deltas));
        }
    }

    /** "Ragnarock Axe  ran out after 15.2s" - only when the player asked to be told. */
    private void reportEnd(long now) {
        Map<Stat, Double> ended = buff;
        String label = buffLabel;
        double seconds = (now - buffStartedAt) / 1000.0;
        buff = null;
        buffLabel = null;
        pendingSince = 0;
        if (!cfg().reportEnd || ended == null) {
            return;
        }
        MutableComponent line = Component.literal(" " + label).withColor(SBSChat.PREFIX_COLOR);
        line.append(Component.literal("  ran out").withColor(0xAAAAAA));
        line.append(Component.literal(String.format(Locale.ROOT, " after %.1fs", seconds))
                .withColor(0xFFAA00));
        List<String> lost = new ArrayList<>(ended.size());
        for (Map.Entry<Stat, Double> entry : ended.entrySet()) {
            lost.add(signed(entry.getKey(), -entry.getValue()) + " " + entry.getKey().displayName());
        }
        line.append(Component.literal("  (" + String.join(", ", lost) + ")").withColor(0x888888));
        SBSChat.send(line);
    }

    /**
     * Prints what the tab list says right now - the settings button behind it is how you find out
     * whether the widget is being read at all, without having to cast anything.
     */
    public void printCurrent() {
        TabStats.Snapshot snapshot = TabStats.read();
        if (!snapshot.available()) {
            SBSChat.send(Component.literal(
                            " No stats in the tab list - turn the stats widget on in game "
                                    + "(SkyBlock Menu ▸ Settings ▸ Tab Widgets).")
                    .withColor(0xFF5555));
            List<String> candidates = TabStats.unparsedCandidates();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][StatBuff] no stats parsed; tab lines with a "
                    + "number: {}", candidates);
            return;
        }
        MutableComponent line = Component.literal(" Stats").withColor(SBSChat.PREFIX_COLOR);
        boolean first = true;
        for (Map.Entry<Stat, Double> entry : snapshot.values().entrySet()) {
            line.append(Component.literal(first ? "  " : ", ").withColor(0xAAAAAA));
            first = false;
            line.append(Component.literal(number(entry.getKey(), entry.getValue()) + " "
                    + entry.getKey().displayName()).withColor(entry.getKey().color()));
        }
        SBSChat.send(line);
    }

    /** {@code +247} / {@code -12.5%} - a delta with its sign and, for percent stats, its sign. */
    private static String signed(Stat stat, double value) {
        return (value > 0 ? "+" : "") + number(stat, value);
    }

    /** Whole numbers stay whole; a fractional stat keeps one decimal. Percent stats get a %. */
    private static String number(Stat stat, double value) {
        String text = value == Math.rint(value)
                ? String.format(Locale.ROOT, "%,.0f", value)
                : String.format(Locale.ROOT, "%,.1f", value);
        return stat.percent() ? text + "%" : text;
    }

    private static String describe(Map<Stat, Double> values) {
        StringBuilder sb = new StringBuilder(64);
        for (Map.Entry<Stat, Double> entry : values.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey().displayName()).append(' ').append(entry.getValue());
        }
        return sb.toString();
    }
}
