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
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.alert.AlertChannel;
import sbs.modid.client.core.alert.AlertChannels;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.skills.farming.model.FarmingText;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything about pests: when the last one spawned, when the next one may, which plots are
 * infested, and a loud enough warning that you notice a spawn without staring at chat.
 *
 * <p><b>Why the cooldown is the important number.</b> Pests spawn on a per-player cooldown, and the
 * whole point of watching it is that a pest spawning while you farm costs you crops until you deal
 * with it – while a pest spawning while you are <i>ready</i> for it costs nothing. So the card
 * leads with "how long until one can spawn", and the warning fires <i>before</i> the cooldown ends
 * (by a configurable number of seconds) rather than after a pest has already appeared. The custom
 * cooldown values exist because the real cooldown is not one number: equipment swapping and
 * Finnegan's Pest Eradicator perk both change it, and a timer that is confidently wrong is worse
 * than none.
 *
 * <p>Spawn detection is chat-driven and deliberately loose: Hypixel has reworded these lines more
 * than once ("Yuck! A ... has appeared in Plot 3!", "Ewww! 2 ... have spawned in Plot 3!"), so the
 * pattern keys on the shape ("... spawned/appeared in Plot N") rather than the exact wording.
 */
public final class PestTracker {

    private static final PestTracker INSTANCE = new PestTracker();

    private static final long SCAN_INTERVAL_MS = 1_000L;
    /** How long the spawn title stays on screen. */
    private static final long TITLE_MS = 3_000L;
    /** Hide the card once no Garden data has been seen for this long (long enough to ride out a
     *  widget that briefly rotates out of the tab list while you are still on the Garden). */
    private static final long HIDE_AFTER_MS = 45_000L;

    /** Any run of digits, for reading a count off a widget line regardless of where it sits. */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * Hypixel's baseline pest cooldown when nothing modifies it: 5 minutes.
     *
     * <p>This is only ever the fallback estimate. The real number is the base scaled by every
     * Pesthunter piece worn, the Squeaky reforges on them, Finnegan's Pest Eradicator perk and the
     * Sprayonator's repellent - none of which the client can read - so the widget's own cooldown row
     * outranks it whenever Hypixel is publishing one. Bounds observed by the wiki: 75 s at the very
     * best (with Finnegan), 20 minutes under Pest Repellent MAX.
     */
    private static final int DEFAULT_COOLDOWN_SECONDS = 300;

    /** The floor no combination of gear and perks can go below - a shorter estimate is not credible. */
    private static final int MIN_COOLDOWN_SECONDS = 75;

    /** The ceiling, under Pest Repellent MAX. */
    private static final int MAX_COOLDOWN_SECONDS = 1_200;

    /**
     * Pests alive at which Hypixel stops spawning more until one is killed. At the cap the cooldown
     * is not what is holding a spawn back, so the "ready" nudge would be telling you nothing.
     */
    private static final int PEST_CAP = 8;

    /** A duration inside a widget line: "1m 30s", "90s", "2h". Read piecewise so any order works. */
    private static final Pattern DURATION_PART = Pattern.compile("(?i)(\\d+)\\s*([hms])");

    /** The same thing written as a clock: "1:30". */
    private static final Pattern CLOCK = Pattern.compile("(\\d+):([0-5]\\d)");

    /**
     * Pesthunter Phillip's hand-in line: "Thanks for the Pest, X! In exchange for 12 Pests, I've
     * given you +60☘ Farming Fortune for 30m!". Only the number in front of "Farming Fortune" is
     * matched here – the ☘ is optional, since it is the part most likely to move or be recoloured.
     */
    private static final Pattern PHILLIP_FORTUNE =
            Pattern.compile("(?i)\\+?\\s*([\\d,]+)\\s*☘?\\s*farming fortune\\b");
    /**
     * The duration at the end of that line ("for 30m", "for 1h"). Read from the <i>tail</i> after the
     * fortune amount, so the "In exchange for 12 Pests" earlier in the same line cannot be mistaken
     * for it – and so Finnegan's Pest Eradicator (which doubles the buff to 60m) needs no special
     * case: whatever Hypixel says is what runs.
     */
    private static final Pattern PHILLIP_DURATION =
            Pattern.compile("(?i)\\bfor\\s+(\\d+)\\s*([hms])\\b");
    /** Fallback when the line carries a fortune but no readable duration - Hypixel's base 30 minutes. */
    private static final long PHILLIP_DEFAULT_MS = 30L * 60_000L;
    /** How long the row keeps saying "expired" after the buff ran out, so the end is not silent. */
    private static final long PHILLIP_EXPIRED_GRACE_MS = 30_000L;

    /** "... has spawned in Plot 3!" / "... have appeared in Plot - 3!". */
    private static final Pattern SPAWN = Pattern.compile(
            "(?i)(.{0,60}?)\\s+ha(?:s|ve)\\s+(?:spawned|appeared)\\s+in\\s+(?:the\\s+)?plot\\s*[-–]?\\s*(\\w+)");
    /**
     * The Pests widget's infested-plot line: {@code "Plots: 2, 4, 5, 14, 16, 18"} – a plain list of
     * plot numbers after a label ending in a colon. The label is matched loosely - any wording that
     * contains the word "plot(s)" before the colon counts ({@code "Plots:"}, {@code "Plot:"},
     * {@code "Infested Plots:"}) - and only the digits AFTER the colon are read, so a number Hypixel
     * ever puts into the label itself cannot become a phantom plot. The strict
     * {@code ^plots:}-anchored version silently stopped matching when the label was reworded, and
     * with it went every consumer at once: the tab-driven plot highlight AND the teleport grid's
     * infested markers.
     *
     * <p>(An even older {@code "Plot N: count"} pattern had the opposite failure: it read the
     * trailing "s" of "Plots" as a plot name. The digits-after-the-label rule survives both.)
     */
    private static final Pattern PLOTS_LINE = Pattern.compile("(?i)^[^:]*\\bplots?\\b[^:]*:");

    /** The widget's remaining rows: "Spray: None", "Bonus: INACTIVE", "Cooldown: MAX PESTS". */
    private static final Pattern STATUS_LINE =
            Pattern.compile("(?i)^\\s*(spray|repellent|bonus|cooldown|pest traps|full traps|no bait)\\s*:\\s*(.*)$");

    private volatile long lastSpawnAt;
    private volatile int spawnsThisSession;
    private volatile int alive = -1;
    /** The plot numbers the Pests widget currently lists as infested. */
    private volatile Set<Integer> infested = Set.of();

    /** The widget's remaining rows, keyed lowercase: spray, repellent, bonus, cooldown. */
    private volatile Map<String, String> status = Map.of();

    /** When the Pests widget was last on the tab list - i.e. when we last knew we were in the Garden. */
    private volatile long widgetSeenAt;
    private volatile long dataSeenAt;

    private volatile long titleShownAt;
    private volatile String titleText = "";

    /** Set when the title stands in for an undelivered desktop notification, which the "Spawn Title"
     *  setting may not suppress - it is then the only on-screen warning there is. */
    private volatile boolean titleForced;

    /** When Pesthunter Phillip's Farming Fortune buff runs out, and how much fortune it is worth. */
    private volatile long phillipEndsAt;
    private volatile int phillipFortune;

    /**
     * Whether a "cooldown is up" nudge is owed.
     *
     * <p>Armed only by <i>seeing</i> the cooldown still running, which is what keeps the nudge to
     * cooldowns this client watched elapse: warping in after it ended, or a relog, leaves it false
     * and stays quiet. Fired once, then re-armed by the next spawn.
     */
    private volatile boolean readyArmed;

    private long lastScanAt;
    private long lastWidgetLogAt;

    private PestTracker() {
    }

    public static PestTracker getInstance() {
        return INSTANCE;
    }

    private static sbs.modid.client.core.config.SBSConfig.GardenSettings cfg() {
        return ConfigManager.getInstance().get().garden;
    }

    // ------------------------------------------------------------------ chat

    /** Called for every chat line: books a spawn, raises the alert, and arms Phillip's buff timer. */
    public void onChat(String text) {
        if (text == null) {
            return;
        }
        String line = FarmingText.strip(text);
        // Phillip's buff has its own card row and its own toggle, so it is read whatever the spawn
        // alerts are set to.
        readPhillipBonus(line);

        var c = cfg();
        if (!c.pestTimer && !c.pestSpawnTitle && !c.pestSpawnSound
                && !AlertChannels.any(c.pestSpawnChannels)
                && !c.pestCooldownWarning && !AlertChannels.any(c.pestReadyChannels)
                && !c.pestCooldownHud) {
            return;
        }
        if (!line.toLowerCase(Locale.ROOT).contains("plot")) {
            return;
        }
        Matcher m = SPAWN.matcher(line);
        if (!m.find()) {
            return;
        }
        String pest = cleanPestName(m.group(1));
        String plot = m.group(2);
        lastSpawnAt = System.currentTimeMillis();
        // A spawn starts a new cycle, so the next "cooldown is up" is owed again - and any nudge
        // that had not fired yet is now about a state that no longer exists.
        readyArmed = false;
        spawnsThisSession++;
        dataSeenAt = lastSpawnAt;

        // Proof the detection half fired, whatever the alert settings are - "no notification" is
        // otherwise indistinguishable from "the spawn line was never recognised".
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Pest] spawn detected: pest='{}' plot='{}' (title={} sound={} notify={})",
                pest, plot, cfg().pestSpawnTitle, cfg().pestSpawnSound,
                AlertChannels.describe(cfg().pestSpawnChannels));

        if (cfg().pestSpawnTitle) {
            titleText = pest.isEmpty() ? "Pest in Plot " + plot : pest + " · Plot " + plot;
            titleShownAt = lastSpawnAt;
            titleForced = false;
        }
        if (cfg().pestSpawnSound) {
            playAlert(1.2f);
        }
        int channels = cfg().pestSpawnChannels;
        if (AlertChannels.any(channels)) {
            String plotText = "Plot " + plot;
            String body = pest.isEmpty() ? plotText : pest + " · " + plotText;
            // The dedicated spawn title above is this alert's own on-screen form, so the shared
            // title channel would draw a second one over it - drop it when that already fired.
            int mask = cfg().pestSpawnTitle
                    ? channels & ~AlertChannel.TITLE.bit() : channels;
            // Likewise the spawn sound: one ping per pest, whichever setting asked for it.
            if (cfg().pestSpawnSound) {
                mask &= ~AlertChannel.SOUND.bit();
            }
            Alerts.send(new Alerts.Alert("Pest spawned", body, SbsAudio.Tone.ALARM, null), mask);
        }
    }

    /**
     * The in-game stand-in for a desktop notification that could not be delivered.
     *
     * <p>Two channels on purpose: the title, forced past the "Spawn Title" setting (the player asked
     * to be notified, and this is the only notification they are getting), and a chat line, which is
     * the one channel no HUD toggle can hide.
     */
    private void showFallbackAlert(String text) {
        titleText = text;
        titleShownAt = System.currentTimeMillis();
        titleForced = true;
        SBSChat.send(Component.literal(" Pest spawned  •  " + text).withColor(0xE0605F));
    }

    /**
     * Trims the leading fluff off the captured pest phrase ("Yuck! A Beetle" -> "Beetle"). Only the
     * last two words are kept, which is what every pest name is at most.
     */
    private static String cleanPestName(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("(?i)^.*?[!,]\\s*", "").trim();
        cleaned = cleaned.replaceAll("(?i)^(an?|\\d+)\\s+", "").trim();
        String[] words = cleaned.split("\\s+");
        if (words.length > 2) {
            cleaned = words[words.length - 2] + " " + words[words.length - 1];
        }
        return cleaned;
    }

    // ------------------------------------------------------------------ phillip

    /**
     * Arms the Pesthunter Phillip timer off his hand-in line, which carries both numbers we need:
     * the Farming Fortune granted and how long it lasts.
     *
     * <p>Two cheap words gate the regex work – the line has to mention both "farming fortune" and a
     * pest. "Farming Fortune" alone shows up in other messages, and reacting to one of those would
     * start a timer for a buff that is not running.
     *
     * <p>The buff is only known from this line, so it starts unknown after a relog even when it is
     * still ticking on the server. Better an absent row than one confidently counting down a buff
     * that may already be gone.
     */
    private void readPhillipBonus(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (!lower.contains("farming fortune") || !lower.contains("pest")) {
            return;
        }
        Matcher fortune = PHILLIP_FORTUNE.matcher(line);
        if (!fortune.find()) {
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(fortune.group(1).replace(",", ""));
        } catch (NumberFormatException ignored) {
            return;
        }
        Matcher duration = PHILLIP_DURATION.matcher(line.substring(fortune.end()));
        long millis = PHILLIP_DEFAULT_MS;
        if (duration.find()) {
            long value = Long.parseLong(duration.group(1));
            millis = switch (duration.group(2).toLowerCase(Locale.ROOT)) {
                case "h" -> value * 3_600_000L;
                case "s" -> value * 1_000L;
                default -> value * 60_000L;
            };
        }
        phillipFortune = amount;
        phillipEndsAt = System.currentTimeMillis() + millis;
    }

    /** The Farming Fortune Phillip's running buff is worth, or {@code 0} when none is known. */
    public int phillipFortune() {
        return phillipEndsAt == 0 ? 0 : phillipFortune;
    }

    /** Milliseconds left on Phillip's buff, {@code 0} once it has run out or was never seen. */
    public long phillipRemainingMs() {
        return phillipEndsAt == 0 ? 0 : Math.max(0, phillipEndsAt - System.currentTimeMillis());
    }

    /**
     * Whether the buff is worth a row: while it runs, plus a short grace after it ends so the timer
     * hitting zero is visible instead of the row just vanishing.
     */
    public boolean phillipKnown() {
        return phillipEndsAt != 0
                && System.currentTimeMillis() - phillipEndsAt < PHILLIP_EXPIRED_GRACE_MS;
    }

    // ------------------------------------------------------------------ capture

    /**
     * Called every client tick: reads the Pests widget and fires the pre-cooldown warning.
     *
     * <p>The scan is deliberately NOT gated on the pest card's own toggle. It used to be, which meant
     * turning that card off silently starved every other reader - the Garden Plots grid then showed
     * "No pests" while the widget listed six infested plots. Anything that consumes this data keeps
     * the scan alive now.
     */
    public void onClientTick() {
        clearOnWorldChange();
        if (!anyConsumer()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanAt >= SCAN_INTERVAL_MS) {
            lastScanAt = now;
            scanTab(now);
        }
        sbs.modid.client.skills.garden.pests.PestProfitTracker.getInstance().onClientTick();
        fireCooldownWarning(now);
    }

    /** The world the current widget data was read in. Identity only - never dereferenced. */
    private java.lang.ref.WeakReference<Object> dataWorld = new java.lang.ref.WeakReference<>(null);

    /**
     * Drops the widget-derived state the moment the client switches worlds.
     *
     * <p>The 45s keep-alive ({@link #HIDE_AFTER_MS}) exists for one case: the widget briefly
     * rotating out of the tab list <i>while you are still standing on the Garden</i>. But every warp
     * on the server is a world switch, and there the same grace kept the card AND the plot borders
     * on screen for half a minute in the Hub - Garden-coordinate rectangles hanging in a world they
     * mean nothing in. The world's identity is the discriminator between those two cases, so the
     * blip-grace survives and the warp-linger dies.
     *
     * <p>Only the widget-derived state clears. The spawn cooldown keeps ticking server-side while
     * you are away, and Phillip's fortune buff survives warps too - wiping those would make their
     * timers wrong on the way BACK to the Garden.
     */
    private void clearOnWorldChange() {
        Object level = Minecraft.getInstance().level;
        if (dataWorld.get() == level) {
            return;
        }
        dataWorld = new java.lang.ref.WeakReference<>(level);
        alive = -1;
        infested = Set.of();
        status = Map.of();
        widgetSeenAt = 0;
        dataSeenAt = 0;
        // The owed nudge goes with them: a cooldown that ends while you are in the Hub is not
        // something to announce on arrival somewhere else. It re-arms the moment the widget shows
        // one running again.
        readyArmed = false;
        loggedCooldownRaw = "";
        // The infested-plot walk is about plots that were infested in the world we just left.
        InfestedPlotWarp.reset();
    }

    /** "Alive: 8" - the widget's pest count line, self-identifying. */
    private static final Pattern ALIVE_LINE = Pattern.compile("(?i)^\\s*alive\\b");

    /**
     * Reads the Pests widget out of the tab list, one line at a time, <b>each line on its own</b>.
     *
     * <p>This was a state machine: sub-lines ("Alive: 8", "Plots: 2, 4") only counted after the
     * "Pests:" header had been seen, because that is the order the widget is DRAWN in. But
     * {@link TabWidgets#lines()} iterates {@code getOnlinePlayers()}, a map view whose iteration
     * order is not the visual order - whenever a sub-line came up before its header, the whole
     * widget parsed as empty, and both consumers (world highlight AND the plot grid) went dark at
     * once while the tab list plainly showed "Plots: 1, 2, 3…". Every line the parser wants is
     * self-identifying ("Plots:" and "Alive:" appear in no other widget), so no line may depend on
     * having seen another line first.
     */
    private void scanTab(long now) {
        int foundAlive = -1;
        Set<Integer> foundPlots = new LinkedHashSet<>();
        Map<String, String> foundStatus = new LinkedHashMap<>();
        boolean sawWidget = false;
        List<String> widgetLines = new ArrayList<>(8);
        for (String line : TabWidgets.lines()) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("pests")) {
                sawWidget = true;
                widgetLines.add(line);
                int n = firstInt(line);   // some layouts write the count into the header itself
                if (n >= 0) {
                    foundAlive = n;
                }
                continue;
            }
            if (lower.contains("no pest")) {
                sawWidget = true;
                widgetLines.add(line);
                foundAlive = Math.max(foundAlive, 0);   // "No pests are infesting your plots!" = zero
                continue;
            }
            if (foundAlive < 0 && ALIVE_LINE.matcher(line).find()) {
                widgetLines.add(line);
                int n = firstInt(line);
                if (n >= 0) {
                    foundAlive = n;
                }
                continue;
            }
            Matcher plotsLabel = PLOTS_LINE.matcher(line);
            if (plotsLabel.find()) {
                widgetLines.add(line);
                // Digits after the label only - a number inside the label is not a plot.
                Matcher n = DIGITS.matcher(line.substring(plotsLabel.end()));
                while (n.find()) {
                    foundPlots.add(Integer.parseInt(n.group()));
                }
                continue;
            }
            Matcher status = STATUS_LINE.matcher(line);
            if (status.matches()) {
                widgetLines.add(line);
                foundStatus.put(status.group(1).toLowerCase(Locale.ROOT), status.group(2).trim());
            }
        }
        // Plots or status lines are proof of the widget even if the header line itself was missed
        // (reworded, or dropped from the entry list) - they exist in no other widget.
        sawWidget = sawWidget || !foundPlots.isEmpty() || !foundStatus.isEmpty();
        // Keep the card alive as long as the Pests widget is on the tab list (i.e. you are on the
        // Garden), even with zero pests when it shows no count - otherwise the card vanished ~20s
        // after the last pest died mid-grind and only popped back on the next spawn.
        if (sawWidget || foundAlive >= 0 || !foundPlots.isEmpty()) {
            int before = alive;
            alive = foundAlive >= 0 ? foundAlive : (sawWidget ? 0 : alive);
            // A read count that fell is pests killed - only between two real readings, so a world
            // change (alive back to -1) or a missing count row is never booked as kills.
            var profit = sbs.modid.client.skills.garden.pests.PestProfitTracker.getInstance();
            boolean trapFilled = sawWidget && profit.onTrapRows(foundStatus.get("pest traps"),
                    foundStatus.get("full traps"), foundStatus.get("no bait"), now);
            if (before > 0 && foundAlive >= 0 && foundAlive < before) {
                profit.onCountDrop(before - foundAlive, now, trapFilled);
            }
            infested = Set.copyOf(foundPlots);
            status = Map.copyOf(foundStatus);
            dataSeenAt = now;
        }
        if (sawWidget) {
            widgetSeenAt = now;
        }
        // Tuning aid: when the widget is missing, its count could not be read, or - the case that
        // used to stay silent - pests are alive but no plots line parsed, dump what the tab actually
        // says so the format can be pinned. That silence is why the infested-plot consumers could
        // all be dark with nothing in the log pointing at the parse.
        boolean plotsMissing = sawWidget && foundAlive > 0 && foundPlots.isEmpty();
        if ((!sawWidget || foundAlive < 0 || plotsMissing) && now - lastWidgetLogAt > 10_000L) {
            lastWidgetLogAt = now;
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pest] widget seen={} alive={} plots={} lines={}",
                    sawWidget, foundAlive, foundPlots, sawWidget ? widgetLines : TabWidgets.lines());
        }
    }

    /** The first run of digits in a line as an int, or {@code -1} when the line has none. */
    private static int firstInt(String line) {
        Matcher m = DIGITS.matcher(line);
        return m.find() ? Integer.parseInt(m.group()) : -1;
    }

    /** The pest cooldown in seconds, honouring the custom / Finnegan overrides. */
    /**
     * The plot numbers the Pests widget currently lists as infested, empty when the widget is absent
     * or says none. Read by the Garden Plots overlay to flash those plots.
     */
    public Set<Integer> infestedPlots() {
        return infested;
    }

    /** Whether the Pests widget has been seen recently enough for {@link #infestedPlots} to mean anything. */
    public boolean pestDataFresh() {
        return dataSeenAt != 0 && System.currentTimeMillis() - dataSeenAt <= HIDE_AFTER_MS;
    }

    /**
     * Whether the player is on the Garden. The Pests widget is only published there, so its presence
     * on the tab list is the test - no island name to parse and no area box to maintain.
     *
     * <p>With one correction: the widget is recognised by lines matched loosely on purpose
     * ({@link #PLOTS_LINE} takes any label containing the word "plot" before its colon, so a reworded
     * header cannot black out the plot grid), and {@code scanTab} promotes a single such line to
     * "widget seen". That makes this method a <i>location oracle built out of fuzzy text</i>, and on
     * an island whose tab list happens to contain a matching line it said Garden with full
     * confidence - every consumer of this method lit up somewhere it had no business being. So a
     * positively read, positively different island now refuses; see
     * {@link GardenBlueprintManager#locationRefutesGarden()} for why an <i>unreadable</i> location
     * deliberately does not.
     */
    public boolean onGarden() {
        if (widgetSeenAt == 0 || System.currentTimeMillis() - widgetSeenAt > HIDE_AFTER_MS) {
            return false;
        }
        return !GardenBlueprintManager.locationRefutesGarden();
    }

    /**
     * A row of the Pests widget by its lowercase label ({@code spray}, {@code repellent},
     * {@code bonus}, {@code cooldown}), or {@code null} when the widget did not carry it.
     */
    public String statusLine(String label) {
        return onGarden() ? status.get(label) : null;
    }

    /**
     * Whether anything currently wants pest data. The scan runs for any of them, so switching the
     * pest card off no longer blinds the plot grid and the status overlay.
     */
    private static boolean anyConsumer() {
        var config = ConfigManager.getInstance().get();
        return cfg().pestTimer
                || cfg().pestSpawnTitle || cfg().pestSpawnSound
                || AlertChannels.any(cfg().pestSpawnChannels)
                // The cooldown readers need the tab scan too - it carries the only exact cooldown
                // there is, and without the scan onGarden() never becomes true either.
                || cfg().pestCooldownWarning || AlertChannels.any(cfg().pestReadyChannels)
                || cfg().pestCooldownHud
                || cfg().pestPlotHighlight || cfg().pestHighlight
                || (config.gardenPlots.enabled && config.gardenPlots.flashInfested)
                || config.pestStatus.enabled
                // Pest Profit books a kill off the alive count dropping.
                || config.gardenHelpers.pestProfit;
    }

    public int cooldownSeconds() {
        var cfg = cfg();
        if (!cfg.pestCustomCooldown) {
            return DEFAULT_COOLDOWN_SECONDS;
        }
        return customSeconds(cfg.pestFinnegan, cfg.pestCooldownSeconds, cfg.pestCooldownFinnegan);
    }

    /** The custom length: the perk variant while the Finnegan toggle is on, clamped to 75-1200 s. */
    public static int customSeconds(boolean finnegan, int base, int withPerk) {
        int seconds = finnegan ? withPerk : base;
        return Math.max(MIN_COOLDOWN_SECONDS, Math.min(MAX_COOLDOWN_SECONDS, seconds));
    }

    // ------------------------------------------------------------------ cooldown

    /** Where a cooldown reading came from, which is what decides whether it may be shown as exact. */
    public enum CooldownSource {
        /** Hypixel's own cooldown row in the Pests widget - exact. */
        WIDGET,
        /** Our own clock since the last spawn line we saw, times a guessed length - an estimate. */
        ESTIMATE,
        /** The player's own Custom Cooldown length since the last spawn - chosen, not guessed. */
        CUSTOM,
        /** Nothing to go on. */
        NONE
    }

    /** What the cooldown is doing. */
    public enum CooldownKind {
        /** Counting down; {@link Cooldown#remainingMs} is meaningful. */
        COUNTING,
        /** Over - a pest can spawn again as soon as you break crops. */
        READY,
        /** Pests are at the cap, so nothing will spawn until one is killed, cooldown or not. */
        AT_CAP,
        /** No spawn seen this session and no widget row - a countdown here would be invented. */
        UNKNOWN
    }

    /** A cooldown reading: what it is doing, how long is left, and how much that is worth trusting. */
    public record Cooldown(CooldownKind kind, long remainingMs, CooldownSource source) {

        /** Whether the number should be shown with an "est." qualifier rather than as fact. */
        public boolean estimated() {
            return source == CooldownSource.ESTIMATE;
        }
    }

    /** The last raw widget cooldown text, so the parse is only logged when it actually changes. */
    private volatile String loggedCooldownRaw = "";

    /**
     * The current cooldown reading.
     *
     * <p><b>Hypixel's own number wins.</b> The widget's cooldown row is the only exact source there
     * is: the real length depends on worn Pesthunter gear, their Squeaky reforges, Finnegan's perk
     * and the repellent, and the client can see none of that. Our own clock is the fallback and says
     * so, rather than counting down a number that was invented.
     */
    public Cooldown cooldown() {
        // The widget is read (and its row logged) either way: with Custom Cooldown on it is the
        // reference the HUD compares against, and its "MAX PESTS" is a real cap regardless.
        Cooldown fromWidget = widgetCooldown();
        return resolve(cfg().pestCustomCooldown, cooldownSeconds(), alive, fromWidget,
                lastSpawnAt, System.currentTimeMillis());
    }

    /**
     * The decision behind {@link #cooldown()}, pure so it is unit-tested.
     *
     * <p><b>Custom Cooldown on = the custom length, everywhere (player request 2026-09-25).</b> It
     * used to feed only the estimate fallback, and since the widget row is present on the Garden
     * almost always, the setting silently did nothing. The cap still wins: at {@code PEST_CAP} alive
     * (or the widget saying MAX PESTS) nothing spawns whatever any timer says. With no spawn seen
     * yet there is nothing to count the custom length from, so Hypixel's reading stands in until the
     * first spawn rather than showing "unknown".
     */
    public static Cooldown resolve(boolean custom, int customSeconds, int alive, Cooldown widget,
                                   long lastSpawnAt, long now) {
        if (alive >= PEST_CAP || (widget != null && widget.kind() == CooldownKind.AT_CAP)) {
            return new Cooldown(CooldownKind.AT_CAP, 0, CooldownSource.WIDGET);
        }
        if (custom && lastSpawnAt > 0) {
            long remaining = Math.max(0, lastSpawnAt + customSeconds * 1000L - now);
            return new Cooldown(remaining == 0 ? CooldownKind.READY : CooldownKind.COUNTING,
                    remaining, CooldownSource.CUSTOM);
        }
        if (widget != null) {
            return widget;
        }
        if (lastSpawnAt == 0) {
            return new Cooldown(CooldownKind.UNKNOWN, 0, CooldownSource.NONE);
        }
        long remaining = Math.max(0, lastSpawnAt + customSeconds * 1000L - now);
        return new Cooldown(remaining == 0 ? CooldownKind.READY : CooldownKind.COUNTING,
                remaining, CooldownSource.ESTIMATE);
    }

    /**
     * Hypixel's own reading, for the small "Hypixel: 1m 20s" line under a custom countdown - or
     * {@code null} when there is no custom countdown, no widget number, or the two agree within 5 s.
     */
    public String hypixelReferenceText() {
        if (!cfg().pestCustomCooldown || !cfg().pestShowHypixelCooldown) {
            return null;
        }
        Cooldown shown = cooldown();
        if (shown.source() != CooldownSource.CUSTOM) {
            return null;
        }
        Cooldown hypixel = widgetCooldown();
        if (hypixel == null || hypixel.kind() == CooldownKind.AT_CAP
                || Math.abs(hypixel.remainingMs() - shown.remainingMs()) <= 5_000L) {
            return null;
        }
        return "Hypixel: " + cooldownText(hypixel);
    }

    /**
     * The widget's cooldown row parsed, or {@code null} when it carries nothing usable.
     *
     * <p>The exact wording of that row is not documented anywhere, so this reads shapes rather than
     * strings - a duration in any order of h/m/s, a {@code m:ss} clock, "max pests", or a word for
     * ready - and logs whatever it could not place, once per distinct value, as
     * {@code [SBS][Pest] cooldown row}. Tune from that log rather than from a guess.
     */
    private Cooldown widgetCooldown() {
        String raw = statusLine("cooldown");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT).trim();
        Cooldown parsed;
        if (lower.contains("max")) {
            // "MAX PESTS" - the cap, not a timer.
            parsed = new Cooldown(CooldownKind.AT_CAP, 0, CooldownSource.WIDGET);
        } else if (lower.contains("ready") || lower.contains("now") || lower.contains("available")) {
            parsed = new Cooldown(CooldownKind.READY, 0, CooldownSource.WIDGET);
        } else {
            long millis = parseDuration(lower);
            parsed = millis < 0 ? null
                    : new Cooldown(millis == 0 ? CooldownKind.READY : CooldownKind.COUNTING,
                            millis, CooldownSource.WIDGET);
        }
        if (!raw.equals(loggedCooldownRaw)) {
            loggedCooldownRaw = raw;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Pest] cooldown row '{}' -> {}", raw,
                    parsed == null ? "UNPARSED (falling back to the estimate)" : parsed);
        }
        return parsed;
    }

    /** A duration written as "1m 30s" / "90s" / "1:30" in milliseconds, or {@code -1} if it is neither. */
    private static long parseDuration(String text) {
        Matcher clock = CLOCK.matcher(text);
        if (clock.find()) {
            return (Long.parseLong(clock.group(1)) * 60L + Long.parseLong(clock.group(2))) * 1000L;
        }
        Matcher part = DURATION_PART.matcher(text);
        long millis = 0;
        boolean any = false;
        while (part.find()) {
            any = true;
            long value = Long.parseLong(part.group(1));
            millis += switch (part.group(2).toLowerCase(Locale.ROOT)) {
                case "h" -> value * 3_600_000L;
                case "m" -> value * 60_000L;
                default -> value * 1_000L;
            };
        }
        return any ? millis : -1;
    }

    /** Milliseconds until a pest may spawn again, or {@code 0} when it is over or unknown. */
    public long cooldownRemainingMs() {
        return cooldown().remainingMs();
    }

    /**
     * The nudge when the cooldown is up: the in-game warning and, separately, a desktop notification
     * through the shared pipeline.
     *
     * <p>Only fires for a cooldown this client actually watched run out. Arriving on the Garden with
     * it long over is not news, and neither is a "ready" that a spawn has already made obsolete -
     * the spawn moves {@link #lastSpawnAt}, which re-arms the one-shot below for the next cycle.
     */
    private void fireCooldownWarning(long now) {
        var cfg = cfg();
        boolean wantsWarning = cfg.pestCooldownWarning;
        boolean wantsNotification = AlertChannels.any(cfg.pestReadyChannels);
        if (!wantsWarning && !wantsNotification) {
            readyArmed = false;
            return;
        }
        // Off the Garden nothing can spawn anyway, and the widget - the only exact source - is gone.
        if (!onGarden()) {
            return;
        }
        Cooldown state = cooldown();
        if (state.kind() == CooldownKind.UNKNOWN || state.kind() == CooldownKind.AT_CAP) {
            return;
        }
        // "Was it still running while we were watching?" - the guard against announcing a cooldown
        // that was already over before we ever looked (a fresh warp onto the Garden).
        if (state.kind() == CooldownKind.COUNTING) {
            long lead = cfg.pestWarnBeforeSeconds * 1000L;
            if (state.remainingMs() > lead) {
                readyArmed = true;
                return;
            }
        }
        if (!readyArmed) {
            return;
        }
        readyArmed = false;

        if (wantsWarning) {
            if (cfg.pestSpawnTitle) {
                titleText = "Pest cooldown over";
                titleShownAt = now;
                titleForced = false;
            }
            playAlert(0.9f);
        }
        if (wantsNotification) {
            // Light on purpose: this is a heads-up, not the spawn alarm - hence the softer tone.
            // The in-game warning above already covered title and ping if it ran, so those channels
            // stand down here rather than saying the same thing twice.
            int mask = cfg.pestReadyChannels;
            if (wantsWarning) {
                mask &= ~(AlertChannel.TITLE.bit() | AlertChannel.SOUND.bit());
            }
            Alerts.send(new Alerts.Alert("Pests can spawn again",
                    "The Garden is off cooldown - happy farming.", SbsAudio.Tone.BLIP, null), mask);
        }
    }

    private static void playAlert(float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, pitch);
        }
    }

    // ------------------------------------------------------------------ vacuum

    /**
     * Whether a pest vacuum is in hand – the gate for muting its right-click loop. Matched on the
     * id fragment so every vacuum tier counts without listing them.
     */
    public boolean vacuumHeld() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (held == null || held.isEmpty()) {
            return false;
        }
        return SkyblockItem.extraAttributes(held).getStringOr("id", "")
                .toUpperCase(Locale.ROOT).contains("VACUUM");
    }

    /** Whether vacuum sounds should be dropped right now. */
    public boolean vacuumMuted() {
        return cfg().muteVacuum && vacuumHeld();
    }

    // ------------------------------------------------------------------ render

    /** The spawn / cooldown title, drawn from the HUD pass. Self-expiring. */
    public void renderTitle(GuiGraphicsExtractor g) {
        if ((!cfg().pestSpawnTitle && !titleForced) || HudLayout.isHidden(HudElement.PEST_ALERT)) {
            return;
        }
        long age = System.currentTimeMillis() - titleShownAt;
        if (titleShownAt == 0 || age > TITLE_MS) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        HudElement.Bounds b = HudElement.PEST_ALERT.defaultBounds(g.guiWidth(), g.guiHeight());
        int centreX = (int) (b.x() + b.w() / 2);
        int y = (int) b.y();

        // Fade over the last third; a hard cut reads as a rendering glitch.
        float life = Math.min(1f, age / (float) TITLE_MS);
        int alpha = (int) (255 * (life > 0.66f ? (1 - life) / 0.34f : 1f));
        int color = (Math.max(16, Math.min(255, alpha)) << 24) | 0x00E05A5A;

        HudLayout.begin(g, HudElement.PEST_ALERT);
        g.centeredText(font, Component.literal("PEST!"), centreX, y, color);
        g.centeredText(font, Component.literal(titleText), centreX, y + font.lineHeight + 2, color);
        HudLayout.end(g);
    }

    /**
     * The pest status card, drawn from the HUD pass.
     *
     * <p>Garden-only through {@link #onGarden()}, like {@link #renderCooldown} and the two Garden
     * HUDs. This card used to be the exception: it hid on {@code dataSeenAt} going stale and nothing
     * else, so "self-hiding away from the Garden" was a 45-second timer wearing a location's
     * clothes. It held for as long as anything kept refreshing that timestamp - and the widget scan
     * is loose on purpose ({@code PLOTS_LINE} takes any label with "plot" in it, so a reworded
     * header cannot black out the plot grid), so another island whose tab list happens to carry a
     * matching line refreshed it indefinitely. Reported on Galathea, where the card simply stayed
     * up.
     *
     * <p>{@code onGarden()} is the fix rather than a tighter timer because it asks the question the
     * card is actually gated on: it refuses when the location service positively reads a different
     * island, and deliberately does not when the location is merely unreadable.
     */
    public void render(GuiGraphicsExtractor g) {
        if (!cfg().pestTimer || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.PEST_TIMER)) {
            return;
        }
        if (!onGarden()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (dataSeenAt == 0 || now - dataSeenAt > HIDE_AFTER_MS) {
            return;
        }
        List<String[]> rows = new ArrayList<>(5);
        if (alive >= 0) {
            rows.add(new String[]{"Alive", String.valueOf(alive)});
        }
        Set<Integer> plots = infested;
        if (!plots.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int plot : plots) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(plot);
            }
            rows.add(new String[]{"Plots", sb.toString()});
        }
        if (lastSpawnAt > 0) {
            rows.add(new String[]{"Last spawn", FarmingText.duration(now - lastSpawnAt) + " ago"});
        }
        Cooldown state = cooldown();
        if (state.kind() != CooldownKind.UNKNOWN) {
            rows.add(new String[]{"Cooldown", cooldownText(state)});
            String reference = hypixelReferenceText();
            if (reference != null) {
                rows.add(new String[]{"", reference});
            }
        }
        if (spawnsThisSession > 0) {
            rows.add(new String[]{"Session", String.valueOf(spawnsThisSession)});
        }
        if (rows.isEmpty()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int pad = 5;
        String header = "Pests";
        int contentW = font.width(header);
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        int width = Math.max(126, contentW + pad * 2);
        int height = pad * 2 + lineH * (1 + rows.size()) - 2;

        HudElement.Bounds b = HudElement.PEST_TIMER.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.PEST_TIMER, x, y, width, height);

        HudLayout.begin(g, HudElement.PEST_TIMER);
        HudCard.draw(g, x, y, width, height);

        int ix = x + pad;
        int right = x + width - pad;
        int iy = y + pad;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            int color = "Cooldown".equals(row[0]) && row[1].startsWith("ready")
                    ? 0xFFE05A5A : SBSTheme.TEXT;
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, color);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /**
     * The cooldown as one short string. An estimate always says so: a countdown the client guessed
     * and one Hypixel published look identical otherwise, and only one of them is worth trusting.
     */
    public static String cooldownText(Cooldown state) {
        return switch (state.kind()) {
            case AT_CAP -> "max pests";
            case UNKNOWN -> "unknown";
            case READY -> state.estimated() ? "ready?" : "ready";
            case COUNTING -> FarmingText.duration(state.remainingMs())
                    + (state.estimated() ? " est."
                            : state.source() == CooldownSource.CUSTOM ? " custom" : "");
        };
    }

    /**
     * The small standalone cooldown card: the one number, nothing else. Only on the Garden, and only
     * once there is something to say - a card reading "unknown" forever is just clutter.
     */
    public void renderCooldown(GuiGraphicsExtractor g) {
        if (!cfg().pestCooldownHud || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.PEST_COOLDOWN)) {
            return;
        }
        if (!onGarden()) {
            return;
        }
        Cooldown state = cooldown();
        if (state.kind() == CooldownKind.UNKNOWN) {
            return;
        }
        boolean ready = state.kind() == CooldownKind.READY;
        String label = "Pests";
        String value = cooldownText(state);
        String reference = hypixelReferenceText();

        Font font = Minecraft.getInstance().font;
        int pad = 5;
        int width = Math.max(88, pad * 2 + font.width(label) + 10 + font.width(value));
        int height = pad * 2 + font.lineHeight;
        if (reference != null) {
            width = Math.max(width, pad * 2 + font.width(reference));
            height += font.lineHeight + 1;
        }

        HudElement.Bounds b = HudElement.PEST_COOLDOWN.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.PEST_COOLDOWN, x, y, width, height);

        HudLayout.begin(g, HudElement.PEST_COOLDOWN);
        // Ready is the state worth spotting from the corner of the eye, so it gets the warm ring
        // rather than a different word in the same grey box.
        int glow = ready ? 0x66E0A14D : SBSTheme.PANEL_GLOW;
        SciFiRender.glow(g, x, y, width, height, SBSTheme.HUD_CORNER, glow, 2);
        SciFiRender.roundedRect(g, x, y, width, height, SBSTheme.HUD_CORNER,
                ready ? 0xFFE0A14D : SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, x + 1, y + 1, width - 2, height - 2,
                SBSTheme.HUD_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
        g.text(font, Component.literal(label), x + pad, y + pad, SBSTheme.TEXT_MUTED);
        g.text(font, Component.literal(value), x + width - pad - font.width(value), y + pad,
                ready ? 0xFFE0A14D : SBSTheme.TEXT);
        if (reference != null) {
            g.text(font, Component.literal(reference), x + pad, y + pad + font.lineHeight + 1,
                    SBSTheme.TEXT_MUTED);
        }
        HudLayout.end(g);
    }
}
