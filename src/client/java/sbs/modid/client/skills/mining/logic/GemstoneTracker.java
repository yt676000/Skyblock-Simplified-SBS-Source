/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.GemstoneProfitSettings;
import sbs.modid.client.core.util.StyledText;
import sbs.modid.client.skills.SkillIslands;
import sbs.modid.client.skills.mining.model.GemstoneTier;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts gemstones as they are picked up, off the {@code [Sacks]} chat breakdown.
 *
 * <p><b>Why the sack feed.</b> It is the only passive per-drop source there is. The tab widget does
 * not carry gemstone counts, the scoreboard does not, and the sack itself is a menu - so anything
 * built on opening one is a tracker that is wrong until the player remembers to update it. Hypixel
 * already publishes every pickup as a hover breakdown on the {@code [Sacks]} line, and three other
 * features in this mod read it, so this is a well-travelled path rather than a new guess.
 *
 * <p><b>It depends on the player owning the sack</b> and having the notification on. That is
 * detectable by its absence rather than silently wrong: no sack lines means no counts, the card says
 * it is waiting for one, and {@link #everSawSackLine()} is what lets the UI tell "you have not mined
 * anything yet" apart from "this is never going to work".
 *
 * <p><b>Session-scoped and instance-scoped.</b> Counts, rates and every figure derived from them are
 * cleared on world change, server hop and profile switch. A rate carried across an instance boundary
 * describes somewhere the player no longer is.
 */
public final class GemstoneTracker {

    private static final GemstoneTracker INSTANCE = new GemstoneTracker();

    /** One "[Sacks]" hover line: "+24 Rough Jade Gemstone (Gemstone Sack)". */
    private static final Pattern SACK_LINE = Pattern.compile("([+-][\\d,.]+) (.+?) \\((.+)\\)");

    /** Rates are refused below this: a shorter sample is noise wearing a number's clothes. */
    private static final long MIN_SAMPLE_MS = 60_000L;

    /** One line every 30s at most, so a heavy mining session cannot flood the log. */
    private static final long LOG_INTERVAL_MS = 30_000L;

    /** Session counts per gemstone product. Insertion-ordered so the card is stable to read. */
    private final Map<GemstoneCatalog.Gem, Long> counts = new LinkedHashMap<>();

    private long sessionStartMs;
    private long lastGainAt;
    private long lastLogAt;
    private boolean sawSackLine;

    private GemstoneTracker() {
    }

    public static GemstoneTracker getInstance() {
        return INSTANCE;
    }

    private static GemstoneProfitSettings cfg() {
        return ConfigManager.getInstance().get().gemstoneProfit;
    }

    // ------------------------------------------------------------------ input

    /**
     * Fed every chat line from the chat funnel. {@code raw} still carries its colour codes and
     * {@code component} is needed for the hover text, which is where the amounts actually live.
     */
    public void onChat(String raw, Component component) {
        if (!cfg().enabled || raw == null || component == null) {
            return;
        }
        String plain = StyledText.strip(raw);
        if (plain == null || !plain.startsWith("[Sacks]")) {
            return;
        }
        sawSackLine = true;
        if (!SkillIslands.miningAllowed()) {
            return;   // gemstones from somewhere that is not a mining island are not this session
        }
        StringBuilder hover = new StringBuilder();
        collectHoverText(component, hover);

        // ONE amount per (item, sack) per message, collapsed by MAX. The hover sits on several styled
        // segments of the same chat line, so the identical breakdown is collected more than once and
        // summing it would multiply every pickup by however many segments Hypixel happened to use.
        Map<String, Long> perItem = new HashMap<>();
        Map<String, GemstoneCatalog.Gem> gems = new HashMap<>();
        for (String line : hover.toString().split("\n")) {
            Matcher matcher = SACK_LINE.matcher(StyledText.strip(line));
            if (!matcher.find() || matcher.group(1).startsWith("-")) {
                continue;   // "-N" is spending out of the sack, not mining into it
            }
            GemstoneCatalog.Gem gem = GemstoneCatalog.byDisplayName(matcher.group(2).trim());
            if (gem == null) {
                continue;
            }
            long amount = parseCount(matcher.group(1));
            if (amount > 0) {
                String key = gem.bazaarId() + "|" + matcher.group(3);
                perItem.merge(key, amount, Math::max);
                gems.put(key, gem);
            }
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : perItem.entrySet()) {
            book(gems.get(entry.getKey()), entry.getValue(), now);
        }
    }

    private void book(GemstoneCatalog.Gem gem, long amount, long now) {
        if (gem == null || amount <= 0) {
            return;
        }
        if (sessionStartMs == 0) {
            sessionStartMs = now;
        }
        counts.merge(gem, amount, Long::sum);
        lastGainAt = now;
    }

    /** Called every client tick; only logs, on a throttle. The counting itself is chat-driven. */
    public void onClientTick() {
        if (!cfg().enabled || counts.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLogAt < LOG_INTERVAL_MS) {
            return;
        }
        lastLogAt = now;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Gemstone] session={} counts={} netPerHour={} priceAge={}s",
                elapsedMs() / 1000, counts, Math.round(netPerHour()),
                GemstoneProfit.priceAgeMs() / 1000);
    }

    /** World change, server hop or profile switch: the whole session goes. */
    public void onWorldChange() {
        resetSession();
        // Deliberately NOT cleared: whether this account has ever produced a sack line is a fact about
        // the account's setup, not about the instance, and re-learning it every hop would make the
        // card flip back to "waiting for a sack line" on arrival every single time.
    }

    /** Restarts the measurement without touching what the player has learned about their setup. */
    public void resetSession() {
        counts.clear();
        sessionStartMs = 0L;
        lastGainAt = 0L;
    }

    // ------------------------------------------------------------------ read model

    /** Session counts per gemstone, in the order they were first mined. */
    public Map<GemstoneCatalog.Gem, Long> counts() {
        return counts;
    }

    /** Whether a {@code [Sacks]} line has been seen at all - see the class note on detectability. */
    public boolean everSawSackLine() {
        return sawSackLine;
    }

    /** Whether there is anything worth drawing. */
    public boolean hasData() {
        return !counts.isEmpty();
    }

    /** How long the session has been running, or {@code 0} before the first gemstone. */
    public long elapsedMs() {
        return sessionStartMs == 0 ? 0L : System.currentTimeMillis() - sessionStartMs;
    }

    /** Whether the sample is long enough to state a rate. */
    public boolean rateReady() {
        return elapsedMs() >= MIN_SAMPLE_MS;
    }

    /** When the last gemstone was booked, for the card's idle handling. */
    public long lastGainAt() {
        return lastGainAt;
    }

    /**
     * Total coins the session's gemstones are worth, sold as mined and after tax.
     *
     * <p>Deliberately the as-mined figure and not the ladder's best: this is the "what have I got"
     * number, and mixing an unverified combine ratio into it would make the one measured figure on
     * the card depend on the one hypothesis. The ladder is reported separately.
     */
    public double netValue() {
        double total = 0;
        for (Map.Entry<GemstoneCatalog.Gem, Long> entry : counts.entrySet()) {
            double unit = GemstoneProfit.netUnitValue(entry.getKey());
            if (unit >= 0) {
                total += unit * entry.getValue();
            }
        }
        return total;
    }

    /** {@link #netValue()} projected to an hour, or {@code 0} while the sample is too short. */
    public double netPerHour() {
        long elapsed = elapsedMs();
        if (elapsed < MIN_SAMPLE_MS) {
            return 0;
        }
        return netValue() * 3_600_000.0 / elapsed;
    }

    /** Per-hour pickup rate of one gemstone product, for the volume check. */
    public double unitsPerHour(GemstoneCatalog.Gem gem) {
        long elapsed = elapsedMs();
        Long count = counts.get(gem);
        if (count == null || elapsed < MIN_SAMPLE_MS) {
            return 0;
        }
        return count * 3_600_000.0 / elapsed;
    }

    /** Session totals collapsed to one entry per grade, for the "which tier am I actually mining" row. */
    public Map<GemstoneTier, Long> byTier() {
        Map<GemstoneTier, Long> totals = new EnumMap<>(GemstoneTier.class);
        for (Map.Entry<GemstoneCatalog.Gem, Long> entry : counts.entrySet()) {
            totals.merge(entry.getKey().tier(), entry.getValue(), Long::sum);
        }
        return totals;
    }

    /** Whether any priced gemstone is in the session - i.e. whether a coin figure means anything. */
    public boolean anyPriced() {
        for (GemstoneCatalog.Gem gem : counts.keySet()) {
            if (GemstoneProfit.netUnitValue(gem) >= 0) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static void collectHoverText(Component component, StringBuilder out) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component text)) {
            out.append(text.getString()).append('\n');
        }
        for (Component sibling : component.getSiblings()) {
            collectHoverText(sibling, out);
        }
    }

    private static long parseCount(String raw) {
        try {
            return Long.parseLong(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
