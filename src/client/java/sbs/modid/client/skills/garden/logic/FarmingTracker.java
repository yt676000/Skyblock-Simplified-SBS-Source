/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import sbs.modid.client.skills.collection.CollectionTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.economy.prices.BazaarPriceCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Farming Tracker (Garden Helpers): Farming Fortune, Overbloom and the pest chance straight from
 * the tab-list widgets, plus the session's farming profit – as one movable HUD card that only shows
 * while the stats are actually being served (i.e. on the Garden / farming islands).
 *
 * <p><b>Stats.</b> Hypixel publishes the farming stats as tab-list widget lines ("Farming Fortune:
 * ☘2,489", "Overbloom: ✿120", pest lines from the Pests widget). The exact wording is a best-guess
 * against the live tab list, so a throttled {@code [SBS][Farm]} line logs every candidate tab line
 * it saw – tune the patterns from that.
 *
 * <p><b>Profit.</b> Reuses the Collection Tracker's anchored counter (the exact collection total
 * plus the session gain and rate): profit = session gain × the crop's Bazaar instasell. That makes
 * this a <i>crop-sale</i> profit – rare-drop / pest-loot value is not part of it – but it is exact,
 * cheap and always consistent with the collection card. Needs the Collection widget enabled
 * ({@code /widgets}) like the Collection Tracker itself.
 */
public final class FarmingTracker {

    private static final FarmingTracker INSTANCE = new FarmingTracker();

    private static final long SCAN_INTERVAL_MS = 500L;
    /** Hide the card once no farming stat has been seen for this long (left the Garden). */
    private static final long HIDE_AFTER_MS = 30_000L;

    /**
     * Tab-line patterns, colour-stripped. Group 1 = the shown value.
     *
     * <p><b>The icon sits between the colon and the number</b> and is a private-use glyph from
     * Hypixel's icon font, not a printable symbol: the live rows are {@code Farming Fortune:
     * U+E051 76} and {@code Bonus Pest Chance: U+E019 90} (read byte-for-byte out of the play
     * instance's logs). The first version allowed only {@code ☘} there and so matched none of the
     * rows it was written for, logging them as "unmatched" every 5 s instead. The separator is now
     * "anything that is neither a digit nor a letter", the same rule {@code core/tab/TabStats} uses,
     * so whichever codepoint the font uses next year still parses.
     */
    private static final String ICON = "[^\\dA-Za-z]*";
    static final Pattern FORTUNE = Pattern.compile(
            "(?i)^Farming Fortune:?" + ICON + "([\\d,.]+)");
    static final Pattern OVERBLOOM = Pattern.compile(
            "(?i)^Overbloom:?" + ICON + "([\\d,.]+%?)");
    static final Pattern PEST_CHANCE = Pattern.compile(
            "(?i)Pest Chance:?" + ICON + "([\\d,.]+\\s*%?)");

    /** Which farming stat a tab line is, with its shown value. */
    enum Stat { FORTUNE, OVERBLOOM, PEST_CHANCE }

    /** One recognised tab row. */
    record StatLine(Stat stat, String value) {
    }

    /** Distinct unmatched lines already logged - each is logged once, not every scan. */
    private final java.util.Set<String> loggedUnmatched = new java.util.HashSet<>();

    private volatile String fortune;
    private volatile String overbloom;
    private volatile String pestChance;
    private volatile long statsSeenAt;
    private long lastScanAt;

    private FarmingTracker() {
    }

    public static FarmingTracker getInstance() {
        return INSTANCE;
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().gardenHelpers.farmingTracker;
    }

    /**
     * The general Farming Fortune Hypixel is currently serving in the tab widget, or {@code -1}
     * when it has not been seen. Shared with the Farming Fortune display, which adds the held
     * tool's crop-specific fortune on top of it.
     *
     * <p>The tab scan below therefore also runs while only the Farming Fortune display is on – the
     * card's own toggle must not decide whether another feature gets its data.
     */
    public double fortuneValue() {
        String shown = fortune;
        if (shown == null || System.currentTimeMillis() - statsSeenAt > HIDE_AFTER_MS) {
            return -1;
        }
        return sbs.modid.client.skills.farming.model.FarmingText.parseNumber(shown);
    }

    // ------------------------------------------------------------------ capture

    /** Called every client tick; scans the tab list for the farming stat widgets (throttled). */
    public void onClientTick() {
        // Also scans for the Farming Fortune display, which reads fortuneValue() from here.
        if (!enabled() && !ConfigManager.getInstance().get().farming.farmingFortune) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null) {
            return;
        }
        String foundFortune = null;
        String foundOverbloom = null;
        String foundPest = null;
        List<String> candidates = null;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;   // real players carry no styled tab name; widget lines do
            }
            String line = strip(display.getString()).trim();
            if (line.isEmpty()) {
                continue;
            }
            StatLine stat = parseLine(line);
            if (stat != null) {
                switch (stat.stat()) {
                    case FORTUNE -> foundFortune = stat.value();
                    case OVERBLOOM -> foundOverbloom = stat.value();
                    case PEST_CHANCE -> foundPest = stat.value();
                }
            } else if (looksFarming(line)) {
                // Tuning: every line that LOOKS farming-related but matched nothing.
                if (candidates == null) {
                    candidates = new ArrayList<>(4);
                }
                candidates.add(line);
            }
        }
        if (foundFortune != null || foundOverbloom != null || foundPest != null) {
            fortune = foundFortune != null ? foundFortune : fortune;
            overbloom = foundOverbloom != null ? foundOverbloom : overbloom;
            pestChance = foundPest != null ? foundPest : pestChance;
            statsSeenAt = now;
        }
        if (candidates != null) {
            for (String line : candidates) {
                // Once per distinct text: the Pests widget's "Pest Traps: 0/3" is always there and
                // is not a stat this card reads, so repeating it every scan is pure noise.
                if (loggedUnmatched.size() < 64 && loggedUnmatched.add(line)) {
                    sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                            "[SBS][Farm] unmatched farming-ish tab line: {}", line);
                }
            }
        }
    }

    /** Parses one colour-stripped tab line, or {@code null} when it is none of the three stats. */
    static StatLine parseLine(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        Matcher m = FORTUNE.matcher(trimmed);
        if (m.find()) {
            return new StatLine(Stat.FORTUNE, m.group(1));
        }
        m = OVERBLOOM.matcher(trimmed);
        if (m.find()) {
            return new StatLine(Stat.OVERBLOOM, m.group(1));
        }
        m = PEST_CHANCE.matcher(trimmed);
        if (m.find()) {
            return new StatLine(Stat.PEST_CHANCE, m.group(1).trim());
        }
        return null;
    }

    /** Whether an unparsed line is worth a tuning log line. */
    static boolean looksFarming(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("farming fortune") || lower.contains("overbloom")
                || lower.contains("pest chance");
    }

    // ------------------------------------------------------------------ render

    /** Called from the HUD render hook once per frame; self-hiding off the Garden. */
    public void render(GuiGraphicsExtractor g) {
        if (!enabled() || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.FARMING_TRACKER)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (statsSeenAt == 0 || now - statsSeenAt > HIDE_AFTER_MS) {
            return;   // no farming widgets in sight - not farming
        }

        List<String[]> rows = new ArrayList<>(5);
        if (fortune != null) {
            rows.add(new String[]{"Fortune", "☘" + fortune});
        }
        if (overbloom != null) {
            rows.add(new String[]{"Overbloom", overbloom});
        }
        if (pestChance != null) {
            rows.add(new String[]{"Pest Chance", pestChance});
        }
        // Profit rides the Collection Tracker's exact counter: gain × Bazaar instasell.
        CollectionTracker.Snapshot snap = CollectionTracker.getInstance().snapshot();
        if (snap != null && snap.anchored() && snap.sessionGain() > 0) {
            BazaarPriceCache.BzPrice price = BazaarPriceCache.getInstance().get(snap.info().id());
            if (price != null && price.sell() > 0) {
                rows.add(new String[]{"Profit", coins(snap.sessionGain() * (double) price.sell())});
                if (snap.perHour() > 0) {
                    rows.add(new String[]{"Profit/h", coins(snap.perHour() * price.sell())});
                }
            }
        }
        if (rows.isEmpty()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int contentW = font.width("Farming");
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        int pad = 5;
        int width = Math.max(120, contentW + pad * 2);
        int height = pad * 2 + lineH * (1 + rows.size()) - 2;

        HudElement.Bounds b = HudElement.FARMING_TRACKER.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.FARMING_TRACKER, x, y, width, height);

        HudLayout.begin(g, HudElement.FARMING_TRACKER);
        HudCard.draw(g, x, y, width, height);

        int ix = x + pad;
        int right = x + width - pad;
        int iy = y + pad;
        g.text(font, Component.literal("Farming"), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy,
                    row[0].startsWith("Profit") ? 0xFF57D977 : SBSTheme.TEXT);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /** Short coin format: 1.2K / 3.4M / 5.6B, honouring "Shorten Numbers". */
    private static String coins(double value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }

    private static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == (char) 0x00A7 && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
