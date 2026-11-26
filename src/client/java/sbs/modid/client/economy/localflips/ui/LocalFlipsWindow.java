/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.localflips.logic.AlertGate;
import sbs.modid.client.economy.localflips.logic.FlipText;
import sbs.modid.client.economy.localflips.logic.LocalFlipFinder;
import sbs.modid.client.economy.localflips.model.LocalFlip;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.window.FloatingWindows;
import sbs.modid.client.ui.window.WindowMemory;
import sbs.modid.client.ui.window.WindowResizer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Flip Finder window: the SOFORT finds on top, the flip list below, every card with Open,
 * Copy and Hide. A floating window like Lowball and the Item Value table - header drag, edge
 * resize, Ctrl+scroll to scale, ✕ to close, geometry remembered through {@link WindowMemory} - so
 * it stays open beside the Auction House and every other SBS window. With no container open
 * {@link LocalFlipsScreen} hosts it.
 *
 * <p>Holds no data of its own: {@link LocalFlipFinder} keeps receiving while this is open or
 * closed. Address, token and alert thresholds live on the settings page; the window has the sort
 * and the "SOFORT only" switch as header chips.
 *
 * <p><b>Render path.</b> Everything drawn is prepared in {@link #model()}, rebuilt only when its
 * key changes (the finder's revision, the second on the clock, sort, filters, thresholds, card
 * width). Card positions are relative to the list top, so dragging the window rebuilds nothing.
 */
public final class LocalFlipsWindow {

    private static final LocalFlipsWindow INSTANCE = new LocalFlipsWindow();

    private static final List<String> SORTS = List.of("Profit", "Profit/Day", "Confidence", "Newest");
    private static final int HEADER_H = 16;
    private static final int CHIP_H = 12;
    private static final int PAD = 6;
    private static final int MARGIN = 2;
    private static final int MIN_W = 220;
    private static final int MAX_W = 640;
    private static final int MIN_H = 140;
    private static final int MAX_H = 600;
    private static final int GAP = 4;
    private static final int LINE_H = 10;
    private static final int CARD_PAD = 4;
    private static final int BUTTON_H = 14;
    private static final int CARD_GAP = 4;
    private static final int SCROLL_STEP = 24;
    private static final long COPIED_SHOWN_MS = 1_500;

    private static final Component OPEN = Component.literal("Open");
    private static final Component OPEN_GONE = Component.literal("gone");
    private static final Component COPY = Component.literal("Copy");
    private static final Component COPIED = Component.literal("Copied!");
    private static final Component HIDE = Component.literal("Hide");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static final int GREEN = 0xFF57D977;
    private static final int YELLOW = 0xFFFFD64D;
    private static final int GREY = 0xFF6A7685;

    /** A drawn block in the scrolling list; {@code y} is relative to the top of the list content. */
    private sealed interface Item permits Header, Card {
        int y();

        int h();
    }

    private record Header(int y, int h, Component text) implements Item {
    }

    /** One prepared flip card. Button x positions are relative to the card's left edge. */
    private record Card(int y, int h, LocalFlip flip, boolean gone, int textColour,
                        Component badge, int badgeColour, int badgeW,
                        Component title, Component right, int rightW,
                        List<Component> lines, int noteLine, List<Component> noteTip,
                        int openW, int copyX, int copyW, int hideX, int hideW,
                        int fastLine, long fastPrice, int normalLine, long normalPrice, int patientLine, long patientPrice) implements Item {
    }

    private record Model(List<Component> status, List<Item> items, int contentH) {
    }

    private boolean open;
    /** Collapsed to the small reopen bar (still open) - remembered across restarts. */
    private boolean minimized;
    private int minBarX;
    private int minBarY;
    private int minBarW;
    private int minBarH;

    private int posX = Integer.MIN_VALUE;
    private int posY = Integer.MIN_VALUE;
    private int sizeW = 340;
    private int sizeH = 300;
    private int panelW = sizeW;
    private int panelH = sizeH;
    private boolean dragging;
    private double grabDX;
    private double grabDY;
    private final WindowResizer resizer = new WindowResizer();
    private final WindowMemory memory = new WindowMemory(FloatingWindows.Layer.LOCAL_FLIPS);

    private int scroll;
    private final SciFiScrollbar bar = new SciFiScrollbar();
    private Model model = new Model(List.of(), List.of(), 0);

    private boolean built;
    private int builtRevision;
    private long builtSecond;
    private int builtSort;
    private long builtFilterMinProfit;
    private boolean builtSofortOnly;
    private boolean builtShowList;
    private boolean builtShowBids;
    private long builtMinProfit;
    private int builtMinSafety;
    private int builtWidth;
    private LocalFlipFinder.Status builtStatus;

    private String copiedAuction;
    private long copiedUntilMs;

    private LocalFlipsWindow() {
    }

    public static LocalFlipsWindow getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.LocalFlipSettings cfg() {
        return LocalFlipFinder.cfg();
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Opens the window (and switches the finder on). Inside a container it simply appears there;
     * anywhere else {@link LocalFlipsScreen} hosts it.
     */
    public static void show() {
        if (!cfg().enabled) {
            cfg().enabled = true;
            ConfigManager.getInstance().save();
        }
        INSTANCE.open = true;
        if (INSTANCE.minimized) {   // the key / button asks to SEE it: bring it back up
            INSTANCE.minimized = false;
            INSTANCE.memory.remember(INSTANCE.posX, INSTANCE.posY, INSTANCE.sizeW, INSTANCE.sizeH, false);
        }
        FloatingWindows.raise(FloatingWindows.Layer.LOCAL_FLIPS);
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?>)) {
            minecraft.setScreenAndShow(new LocalFlipsScreen());
        }
    }

    /** The open-window key: opens it, or closes it when it is already open. */
    public static void toggle() {
        if (INSTANCE.open) {
            INSTANCE.close();
            if (Minecraft.getInstance().gui.screen() instanceof LocalFlipsScreen) {
                Minecraft.getInstance().setScreenAndShow(null);
            }
        } else {
            show();
        }
    }

    public void close() {
        open = false;
        dragging = false;
        resizer.end();
        bar.release();
    }

    // ------------------------------------------------------------------
    // Geometry (from the panel position; recomputed each frame, cheap)
    // ------------------------------------------------------------------

    private int innerX() {
        return posX + PAD;
    }

    private int contentW() {
        return Math.max(1, panelW - PAD * 2);
    }

    private int cardW() {
        return Math.max(1, contentW() - SciFiScrollbar.WIDTH - 3);
    }

    private int chipsY() {
        return posY + HEADER_H + 3;
    }

    private int statusY() {
        return chipsY() + CHIP_H + GAP;
    }

    private int listTop() {
        return statusY() + LINE_H * 2 + GAP;
    }

    private int listBottom() {
        return Math.max(listTop() + 1, posY + panelH - PAD);
    }

    private static String sortLabel() {
        return "Sort: " + SORTS.get(clampSort(cfg().sort));
    }

    private static String sofortLabel() {
        return (cfg().filterSofortOnly ? "☑" : "☐") + " Instant only";
    }

    private int sortChipW(Font font) {
        return font.width(sortLabel()) + 8;
    }

    /** Server / Local, right of the "Instant only" chip. */
    private int sourceSwitchX(Font font) {
        return sofortChipX(font) + font.width(sofortLabel()) + 8 + GAP;
    }

    private int sofortChipX(Font font) {
        return innerX() + sortChipW(font) + GAP;
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    private static int clampSort(int sort) {
        return Math.max(0, Math.min(SORTS.size() - 1, sort));
    }

    private Model model() {
        LocalFlipFinder finder = LocalFlipFinder.getInstance();
        SBSConfig.LocalFlipSettings cfg = cfg();
        long now = System.currentTimeMillis();
        long second = now / 1000;
        int sort = clampSort(cfg.sort);
        int cardW = cardW();
        boolean current = built && builtRevision == finder.revision() && builtSecond == second
                && builtSort == sort && builtFilterMinProfit == cfg.filterMinProfit
                && builtSofortOnly == cfg.filterSofortOnly && builtShowList == cfg.showList
                && builtShowBids == cfg.showBids && builtMinProfit == cfg.minProfit
                && builtMinSafety == cfg.minSafety && builtWidth == cardW && builtStatus == finder.status();
        if (!current) {
            model = build(finder, cfg, now, cardW);
            built = true;
            builtRevision = finder.revision();
            builtSecond = second;
            builtSort = sort;
            builtFilterMinProfit = cfg.filterMinProfit;
            builtSofortOnly = cfg.filterSofortOnly;
            builtShowList = cfg.showList;
            builtShowBids = cfg.showBids;
            builtMinProfit = cfg.minProfit;
            builtMinSafety = cfg.minSafety;
            builtWidth = cardW;
            builtStatus = finder.status();
        }
        return model;
    }

    private Model build(LocalFlipFinder finder, SBSConfig.LocalFlipSettings cfg, long now, int cardW) {
        List<Component> status = statusLines(finder, now);
        List<Item> items = new ArrayList<>();
        int y = 0;
        Comparator<LocalFlip> order = comparator(clampSort(cfg.sort));

        List<LocalFlip> sofort = new ArrayList<>();
        Set<String> inSofort = new HashSet<>();
        for (LocalFlipFinder.Entry entry : finder.sofort()) {
            LocalFlip flip = entry.flip();
            inSofort.add(flip.auction());
            if (visible(finder, flip, cfg, now)) {
                sofort.add(withFoundAt(entry));
            }
        }
        sofort.sort(order);
        Header sofortHeader = new Header(y, LINE_H + 2,
                Component.literal("§fInstant §7(" + sofort.size() + ")"));
        items.add(sofortHeader);
        y += sofortHeader.h();
        if (sofort.isEmpty()) {
            Header empty = new Header(y, LINE_H + 4, Component.literal(fit(emptySofortText(finder), cardW)));
            items.add(empty);
            y += empty.h();
        }
        for (LocalFlip flip : sofort) {
            Card card = card(flip, y, finder, cfg, now, cardW);
            items.add(card);
            y += card.h() + CARD_GAP;
        }

        if (cfg.showList && !cfg.filterSofortOnly) {
            List<LocalFlip> list = new ArrayList<>();
            for (LocalFlip flip : finder.liste()) {
                if (inSofort.contains(flip.auction())) {
                    continue;   // the same auction is already a SOFORT card above
                }
                if (!cfg.showBids && flip.kind() == LocalFlip.Kind.GEBOT) {
                    continue;
                }
                if (visible(finder, flip, cfg, now)) {
                    list.add(flip);
                }
            }
            list.sort(order);
            y += 4;
            Header listHeader = new Header(y, LINE_H + 2,
                    Component.literal("§fFlip List §7(" + list.size() + ")"));
            items.add(listHeader);
            y += listHeader.h();
            for (LocalFlip flip : list) {
                Card card = card(flip, y, finder, cfg, now, cardW);
                items.add(card);
                y += card.h() + CARD_GAP;
            }
        }
        return new Model(status, List.copyOf(items), y);
    }

    /** A SOFORT entry without {@code found_at} is sorted and labelled by when it arrived. */
    private static LocalFlip withFoundAt(LocalFlipFinder.Entry entry) {
        LocalFlip f = entry.flip();
        if (!Double.isNaN(f.foundAt())) {
            return f;
        }
        return new LocalFlip(f.kind(), f.name(), f.itemId(), f.dropClass(), f.count(), f.price(), f.maxBid(),
                f.endsMin(), f.profitNow(), f.target(), f.netProfit(), f.netPct(), f.pSuccess(), f.sellTimeH(),
                f.profitPerDay(), f.priceNormal(), f.timeNormalH(), f.pricePatient(), f.timePatientH(),
                f.salesPerDay(), f.ageMin(), f.seller(), f.note(), f.auction(), entry.receivedMs() / 1000.0,
                f.start(), f.latencyS(), f.botP(), f.auctionOnly(), f.ageRatio());
    }

    private static boolean visible(LocalFlipFinder finder, LocalFlip flip, SBSConfig.LocalFlipSettings cfg,
                                   long now) {
        if (finder.isHidden(flip.auction()) || finder.goneExpired(flip.auction(), now)) {
            return false;
        }
        return cfg.filterMinProfit <= 0
                || flip.has(flip.netProfit()) && flip.netProfit() >= cfg.filterMinProfit;
    }

    private static Comparator<LocalFlip> comparator(int sort) {
        return switch (sort) {
            case 0 -> Comparator.comparingLong((LocalFlip f) -> f.has(f.netProfit()) ? f.netProfit() : Long.MIN_VALUE)
                    .reversed();
            case 1 -> Comparator.comparingLong(
                    (LocalFlip f) -> f.has(f.profitPerDay()) ? f.profitPerDay() : Long.MIN_VALUE).reversed();
            case 2 -> Comparator.comparingInt(LocalFlip::safetyPct).reversed();
            default -> Comparator.comparingDouble(LocalFlipsWindow::newness).reversed();
        };
    }

    /** Larger is newer: {@code found_at} for SOFORT finds, minus the listing age for list entries. */
    private static double newness(LocalFlip flip) {
        if (!Double.isNaN(flip.foundAt())) {
            return flip.foundAt();
        }
        return Double.isNaN(flip.ageMin()) ? -Double.MAX_VALUE : -flip.ageMin() * 60;
    }

    private static String emptySofortText(LocalFlipFinder finder) {
        return switch (finder.status()) {
            case OFF -> "§7The finder is off.";
            case CONNECTED -> "§7No instant finds yet - they appear here as soon as the server reports them.";
            default -> "§7No instant finds - not connected.";
        };
    }

    private List<Component> statusLines(LocalFlipFinder finder, long now) {
        String source = LocalFlipFinder.server() ? "Server" : "Lokal";
        String connection = switch (finder.status()) {
            case OFF -> "§7● off";
            case CONNECTING -> "§e● connecting… §8(" + source + ")";
            case CONNECTED -> "§a● connected §8(" + source + ")";
            case DISCONNECTED -> "§c● disconnected – retrying in "
                    + Math.max(0, (finder.retryAtMs() - now + 999) / 1000) + " s";
            case UNAUTHORIZED -> LocalFlipFinder.server()
                    ? "§c● Licence token missing or wrong" : "§c● 401 – wrong token";
            case INVALID_ADDRESS -> "§c● Invalid address – only http(s) on this PC or in the LAN";
        };
        if (finder.lastSignalMs() > 0 && finder.status() == LocalFlipFinder.Status.CONNECTED) {
            connection += "  §8·  §7last signal " + FlipText.ago((now - finder.lastSignalMs()) / 1000) + " ago";
        }
        String api;
        long apiAt = finder.apiLastUpdatedMs();
        if (apiAt <= 0) {
            api = "§7Hypixel AH data: unknown";
        } else if (now - apiAt > LocalFlipFinder.API_STUCK_MS) {
            api = "§cHypixel AH API stalled for "
                    + CLOCK.format(Instant.ofEpochMilli(apiAt).atZone(ZoneId.systemDefault()))
                    + " – finds may already be gone";
        } else {
            api = "§7Hypixel AH data: " + FlipText.ago((now - apiAt) / 1000) + " old";
        }
        int w = contentW();
        return List.of(Component.literal(fit(connection, w)), Component.literal(fit(api, w)));
    }

    private Card card(LocalFlip flip, int y, LocalFlipFinder finder, SBSConfig.LocalFlipSettings cfg, long now,
                      int cardW) {
        Font font = font();
        boolean gone = finder.isGone(flip.auction());
        boolean belowThreshold = !AlertGate.passes(flip, cfg.minProfit, cfg.minSafety);
        int textW = cardW - CARD_PAD * 2;
        double nowSeconds = now / 1000.0;

        String badgeText = gone ? "GONE" : flip.kind().label();
        int badgeColour = gone ? GREY : switch (flip.kind()) {
            case SOFORT -> GREEN;
            case GEBOT -> YELLOW;
            default -> SBSTheme.ACCENT;
        };
        int badgeW = font.width(badgeText) + 6;
        String right = flip.has(flip.netProfit())
                ? "§a+" + FlipText.coins(flip.netProfit()) + " §7(" + FlipText.pct(flip.netPct()) + ")" : "";
        int rightW = font.width(right);
        String title = fit("§f" + flip.displayName(), Math.max(1, textW - badgeW - 4 - rightW - 6));

        List<String> lines = new ArrayList<>();
        StringBuilder price = new StringBuilder();
        if (flip.kind() == LocalFlip.Kind.GEBOT) {
            price.append("§7Bid now §f").append(FlipText.coins(flip.price()));
        } else {
            price.append("§f").append(FlipText.coins(flip.price()));
        }
        price.append(" §7→ §e").append(FlipText.coins(flip.target()));
        int safety = flip.safetyPct();
        if (safety >= 0) {
            price.append("  §7Confidence ").append(colourCode(LocalFlipFinder.safetyColour(safety)))
                    .append(safety).append(" %");
        }
        lines.add(price.toString());
        List<String> flags = new ArrayList<>(3);
        if (flip.botPct() >= 0) {
            flags.add(flip.botPrey() ? "§c§lBOT PREY " + flip.botPct() + "%"
                    : "§aquiet (" + flip.botPct() + "% bots)");
        }
        if (flip.auctionOnly()) {
            flags.add("§6AUCTION ONLY (no BIN)");
        }
        if (!Double.isNaN(flip.ageRatio()) && flip.ageRatio() > 2) {
            flags.add("§7listed " + FlipText.decimal1(flip.ageRatio()) + "x longer than usual");
        }
        if (!flags.isEmpty()) {
            lines.add(String.join(" §8· ", flags));
        }
        if (flip.kind() == LocalFlip.Kind.GEBOT && flip.has(flip.maxBid())) {
            StringBuilder bid = new StringBuilder("§e§lbid at most ").append(FlipText.coins(flip.maxBid()))
                    .append("§r§e – ends in ").append(FlipText.minutes(flip.endsMin()));
            if (flip.has(flip.profitNow())) {
                bid.append(" §7· now §a+").append(FlipText.coins(flip.profitNow()));
            }
            lines.add(bid.toString());
        }
        StringBuilder sell = new StringBuilder();
        if (!flip.dropClass().isEmpty()) {
            sell.append("§d").append(flip.dropClass()).append(" §8· ");
        }
        sell.append("§7Sells in ~").append(FlipText.hours(flip.sellTimeH()));
        if (flip.has(flip.profitPerDay())) {
            sell.append(" §8· §f").append(FlipText.coins(flip.profitPerDay())).append("§7/day");
        }
        lines.add(sell.toString());
        if (!Double.isNaN(flip.foundAt()) || !Double.isNaN(flip.start())) {
            StringBuilder timing = new StringBuilder("§7");
            if (!Double.isNaN(flip.foundAt())) {
                timing.append("found ").append(FlipText.ago((long) (nowSeconds - flip.foundAt()))).append(" ago");
            }
            if (!Double.isNaN(flip.start())) {
                double inAhMin = (nowSeconds - flip.start()) / 60.0;
                if (!Double.isNaN(flip.foundAt())) {
                    timing.append(" §8· ");
                }
                timing.append(inAhMin >= 3 ? "§6" : "§7").append("in AH for ").append(FlipText.minutes(inAhMin));
            }
            lines.add(timing.toString());
        }
        if (!Double.isNaN(flip.ageMin()) || !flip.seller().isEmpty() || !Double.isNaN(flip.salesPerDay())) {
            List<String> parts = new ArrayList<>(3);
            if (!Double.isNaN(flip.ageMin())) {
                parts.add("Age " + FlipText.minutes(flip.ageMin()));
            }
            if (!flip.seller().isEmpty()) {
                parts.add("Seller " + flip.seller());
            }
            if (!Double.isNaN(flip.salesPerDay())) {
                parts.add(FlipText.decimal1(flip.salesPerDay()) + " sales/day");
            }
            lines.add("§7" + String.join(" §8· §7", parts));
        }
        // Price ladder: one line per sell price, click = copy it (whole coins, no separators).
        int fastLine = -1;
        int normalLine = -1;
        int patientLine = -1;
        if (flip.has(flip.target())) {
            fastLine = lines.size();
            lines.add("§7fast §f" + FlipText.coins(flip.target())
                    + " §8(" + FlipText.hours(flip.sellTimeH()) + ")"
                    + " §8[copy]");
        }
        if (flip.has(flip.priceNormal())) {
            normalLine = lines.size();
            lines.add("§7normal §f" + FlipText.coins(flip.priceNormal()) + " §8(" + FlipText.hours(flip.timeNormalH())
                    + ") §8[copy]");
        }
        if (flip.has(flip.pricePatient())) {
            patientLine = lines.size();
            lines.add("§7patient §f" + FlipText.coins(flip.pricePatient()) + " §8("
                    + FlipText.hours(flip.timePatientH()) + ") §8[copy]");
        }
        List<String> noteParts = new ArrayList<>(LocalFlipFinder.noteParts(flip.note()));
        if (flip.botPct() >= 0) {
            noteParts.removeIf(part -> part.toUpperCase(java.util.Locale.ROOT).contains("BOT PREY"));
        }
        int noteLine = -1;
        List<Component> noteTip = List.of();
        if (!noteParts.isEmpty()) {
            noteLine = lines.size();
            String first = noteParts.get(0);
            lines.add(noteColour(first) + first + (noteParts.size() > 1 ? " §8(+" + (noteParts.size() - 1) + ")" : ""));
            List<Component> tip = new ArrayList<>(noteParts.size());
            for (String part : noteParts) {
                tip.add(Component.literal(noteColour(part) + part));
            }
            noteTip = List.copyOf(tip);
        }

        boolean grey = gone || belowThreshold;
        int textColour = gone ? GREY : SBSTheme.TEXT_MUTED;
        List<Component> drawn = new ArrayList<>(lines.size());
        for (String line : lines) {
            String text = grey ? ChatFormatting.stripFormatting(line) : line;
            drawn.add(Component.literal(fit(text, textW)));
        }
        Component titleComponent = Component.literal(grey ? ChatFormatting.stripFormatting(title) : title);
        Component rightComponent = Component.literal(grey ? ChatFormatting.stripFormatting(right) : right);

        int openW = Math.max(30, textW / 2);
        int restW = Math.max(2, textW - openW - GAP * 2);
        int copyW = restW / 2;
        int hideW = restW - copyW;
        int copyX = CARD_PAD + openW + GAP;
        int hideX = copyX + copyW + GAP;

        int h = CARD_PAD + LINE_H + drawn.size() * LINE_H + 2 + BUTTON_H + CARD_PAD;
        return new Card(y, h, flip, gone, textColour, Component.literal(badgeText), badgeColour, badgeW,
                titleComponent, rightComponent, rightW, List.copyOf(drawn), noteLine, noteTip,
                openW, copyX, copyW, hideX, hideW,
                fastLine, flip.has(flip.target()) ? flip.target() : 0,
                normalLine, flip.has(flip.priceNormal()) ? flip.priceNormal() : 0,
                patientLine, flip.has(flip.pricePatient()) ? flip.pricePatient() : 0);
    }

    /** FEHLPREIS red, GEBOT: yellow, the other warning prefixes orange, anything else grey. */
    private static String noteColour(String part) {
        if (part.startsWith("FEHLPREIS")) {
            return "§c§l";
        }
        if (part.startsWith("GEBOT:")) {
            return "§e";
        }
        if (part.startsWith("STAPEL") || part.startsWith("Pet Candy") || part.startsWith("KEINE UUID")
                || part.toUpperCase(java.util.Locale.ROOT).startsWith("BOT PREY")) {
            return "§6";
        }
        return "§8";
    }

    private static String colourCode(int rgb) {
        return switch (rgb & 0xFFFFFF) {
            case 0x57D977 -> "§a";
            case 0xFFD64D -> "§e";
            default -> "§6";
        };
    }

    /** Cuts to {@code maxWidth}, ending in "..." when anything was cut. Model build only. */
    private static String fit(String text, int maxWidth) {
        Font font = font();
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Inside a container (the floating-window layer). */
    public void renderTopMost(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        render(screen, g, mouseX, mouseY);
    }

    public void render(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!open) {
            if (showsOpenButton(screen)) {
                restorePosition();
                drawMinimizedBar(screen, g, font(), mouseX, mouseY);
            }
            return;
        }
        Font font = font();
        memory.restore(state -> {
            posX = state.x;
            posY = state.y;
            sizeW = state.width(sizeW);
            sizeH = state.height(sizeH);
            minimized = state.minimized;
        });
        panelW = clamp(Math.min(sizeW, screen.width - MARGIN * 2), Math.min(MIN_W, screen.width - MARGIN * 2), MAX_W);
        panelH = clamp(Math.min(sizeH, screen.height - MARGIN * 2), Math.min(MIN_H, screen.height - MARGIN * 2), MAX_H);
        if (posX == Integer.MIN_VALUE) {
            posX = 8;
            posY = 8;
        }
        posX = clamp(posX, MARGIN, Math.max(MARGIN, screen.width - panelW - MARGIN));
        posY = clamp(posY, MARGIN, Math.max(MARGIN, screen.height - panelH - MARGIN));
        if (minimized) {
            drawMinimizedBar(screen, g, font, mouseX, mouseY);
            return;
        }

        Model m = model();
        int listTop = listTop();
        int listBottom = listBottom();
        bar.set(innerX() + contentW() - SciFiScrollbar.WIDTH, listTop, listBottom - listTop,
                m.contentH(), listBottom - listTop);
        scroll = Math.max(0, Math.min(scroll, bar.maxScroll()));

        SciFiRender.glow(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, posX + 1, posY + 1, panelW - 2, panelH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        int textY = posY + (HEADER_H - font.lineHeight) / 2 + 1;
        g.text(font, Component.literal("Flip Finder"), posX + PAD, textY, SBSTheme.ACCENT_BRIGHT);
        g.text(font, Component.literal("x"), closeX() + 3, textY,
                inCloseBox(mouseX, mouseY) ? SBSTheme.WARN : SBSTheme.TEXT_MUTED);
        g.text(font, Component.literal("-"), minimizeX() + 3, textY,
                inMinimizeBox(mouseX, mouseY) ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED);
        g.fill(posX + PAD, posY + HEADER_H, posX + panelW - PAD, posY + HEADER_H + 1, SBSTheme.ACCENT_SOFT);

        chip(g, font, innerX(), chipsY(), sortChipW(font), sortLabel(), false, mouseX, mouseY);
        String sofort = sofortLabel();
        chip(g, font, sofortChipX(font), chipsY(), font.width(sofort) + 8, sofort, cfg().filterSofortOnly,
                mouseX, mouseY);
        boolean local = sbs.modid.client.economy.auctions.logic.FlipServer.isLocal();
        int switchX = sourceSwitchX(font);
        sbs.modid.client.ui.render.SourceSwitch.draw(g, font, switchX, chipsY(), local, mouseX, mouseY);
        if (mouseX >= switchX && mouseX < switchX + sbs.modid.client.ui.render.SourceSwitch.width(font)
                && mouseY >= chipsY() && mouseY < chipsY() + sbs.modid.client.ui.render.SourceSwitch.HEIGHT) {
            List<Component> tip = new ArrayList<>();
            for (String line : sbs.modid.client.ui.render.SourceSwitch.tooltip(local)) {
                tip.add(Component.literal(line));
            }
            g.setTooltipForNextFrame(font, tip, java.util.Optional.empty(), mouseX, mouseY,
                    SBSTheme.tooltipStyle());
        }

        List<Component> status = m.status();
        for (int i = 0; i < status.size(); i++) {
            g.text(font, status.get(i), innerX(), statusY() + i * LINE_H, SBSTheme.TEXT);
        }

        g.enableScissor(innerX(), listTop, innerX() + contentW(), listBottom);
        Card hoveredNote = null;
        for (Item item : m.items()) {
            int top = listTop + item.y() - scroll;
            if (top + item.h() < listTop || top > listBottom) {
                continue;
            }
            if (item instanceof Header header) {
                g.text(font, header.text(), innerX(), top + 1, SBSTheme.TEXT);
            } else if (item instanceof Card card && drawCard(g, font, card, top, mouseX, mouseY)) {
                hoveredNote = card;
            }
        }
        g.disableScissor();
        bar.render(g, scroll, mouseX, mouseY);
        resizer.renderGrips(g, posX, posY, panelW, panelH, mouseX, mouseY);

        if (hoveredNote != null && mouseY >= listTop && mouseY < listBottom) {
            g.setTooltipForNextFrame(font, hoveredNote.noteTip(), Optional.empty(), mouseX, mouseY,
                    SBSTheme.tooltipStyle());
        }
    }

    private static void chip(GuiGraphicsExtractor g, Font font, int x, int y, int w, String label, boolean on,
                             int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CHIP_H;
        SciFiRender.roundedRectWithBorder(g, x, y, w, CHIP_H, 3,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.SEARCH_FILL,
                on ? GREEN : hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        g.text(font, Component.literal(label), x + 4, y + (CHIP_H - font.lineHeight) / 2 + 1,
                on ? GREEN : SBSTheme.TEXT);
    }

    /** Draws one card; returns true when the mouse is over its note line (tooltip wanted). */
    private boolean drawCard(GuiGraphicsExtractor g, Font font, Card card, int top, int mouseX, int mouseY) {
        int x = innerX();
        int cardW = cardW();
        int listTop = listTop();
        int listBottom = listBottom();
        boolean hovered = mouseX >= x && mouseX < x + cardW && mouseY >= top && mouseY < top + card.h()
                && mouseY >= listTop && mouseY < listBottom;
        SciFiRender.roundedRectWithBorder(g, x, top, cardW, card.h(), SBSTheme.CORNER_RADIUS,
                card.gone() ? SBSTheme.CARD_BG_DISABLED : hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                SBSTheme.CARD_BORDER);

        int textX = x + CARD_PAD;
        int y = top + CARD_PAD;
        SciFiRender.roundedRectWithBorder(g, textX, y - 1, card.badgeW(), LINE_H, 2,
                SBSTheme.CARD_BG_DISABLED, card.badgeColour());
        g.centeredText(font, card.badge(), textX + card.badgeW() / 2, y, card.badgeColour());
        g.text(font, card.title(), textX + card.badgeW() + 4, y, card.textColour());
        g.text(font, card.right(), x + cardW - CARD_PAD - card.rightW(), y, card.textColour());
        y += LINE_H;

        boolean overNote = false;
        List<Component> lines = card.lines();
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), textX, y, card.textColour());
            if (i == card.noteLine() && mouseX >= textX && mouseX < x + cardW
                    && mouseY >= y && mouseY < y + LINE_H) {
                overNote = true;
            }
            y += LINE_H;
        }

        int buttonY = top + card.h() - CARD_PAD - BUTTON_H;
        boolean copied = card.flip().auction().equals(copiedAuction) && System.currentTimeMillis() < copiedUntilMs;
        button(g, font, x + CARD_PAD, buttonY, card.openW(), card.gone() ? OPEN_GONE : OPEN,
                !card.gone(), true, mouseX, mouseY, listTop, listBottom);
        button(g, font, x + card.copyX(), buttonY, card.copyW(), copied ? COPIED : COPY, true, false,
                mouseX, mouseY, listTop, listBottom);
        button(g, font, x + card.hideX(), buttonY, card.hideW(), HIDE, true, false, mouseX, mouseY,
                listTop, listBottom);
        return overNote;
    }

    private static void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, Component label,
                               boolean active, boolean primary, int mouseX, int mouseY, int listTop,
                               int listBottom) {
        boolean hovered = active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BUTTON_H
                && mouseY >= listTop && mouseY < listBottom;
        int fill = !active ? SBSTheme.CARD_BG_DISABLED : hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.SEARCH_FILL;
        int border = !active ? SBSTheme.CARD_BORDER : primary ? GREEN : hovered ? SBSTheme.ACCENT_BRIGHT
                : SBSTheme.CARD_BORDER;
        SciFiRender.roundedRectWithBorder(g, x, y, w, BUTTON_H, 3, fill, border);
        g.centeredText(font, label, x + w / 2, y + (BUTTON_H - font.lineHeight) / 2 + 1,
                !active ? GREY : primary ? GREEN : SBSTheme.TEXT);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    public boolean handleClick(Screen screen, MouseButtonEvent event) {
        if (!open) {
            if (showsOpenButton(screen) && event.button() == 0
                    && event.x() >= minBarX && event.x() < minBarX + minBarW
                    && event.y() >= minBarY && event.y() < minBarY + minBarH) {
                show();
                return true;
            }
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (minimized) {
            if (event.button() == 0 && mx >= minBarX && mx < minBarX + minBarW
                    && my >= minBarY && my < minBarY + minBarH) {
                minimized = false;
                memory.remember(posX, posY, sizeW, sizeH, false);
                FloatingWindows.raise(FloatingWindows.Layer.LOCAL_FLIPS);
                return true;
            }
            return false;
        }
        if (!inPanel(mx, my)) {
            return false;
        }
        FloatingWindows.raise(FloatingWindows.Layer.LOCAL_FLIPS);
        if (event.button() != 0) {
            return true;
        }
        if (inMinimizeBox(mx, my)) {
            minimized = true;
            memory.remember(posX, posY, sizeW, sizeH, true);
            return true;
        }
        if (inCloseBox(mx, my)) {
            close();
            return true;
        }
        if (resizer.begin(mx, my, posX, posY, panelW, panelH)) {
            return true;
        }
        if (my < posY + HEADER_H) {
            dragging = true;
            grabDX = mx - posX;
            grabDY = my - posY;
            return true;
        }
        Font font = font();
        if (my >= chipsY() && my < chipsY() + CHIP_H) {
            if (mx >= innerX() && mx < innerX() + sortChipW(font)) {
                cfg().sort = (clampSort(cfg().sort) + 1) % SORTS.size();
                ConfigManager.getInstance().save();
                return true;
            }
            int sx = sofortChipX(font);
            if (mx >= sx && mx < sx + font.width(sofortLabel()) + 8) {
                cfg().filterSofortOnly = !cfg().filterSofortOnly;
                ConfigManager.getInstance().save();
                return true;
            }
            Boolean picked = sbs.modid.client.ui.render.SourceSwitch.hit(font, sourceSwitchX(font), chipsY(), mx, my);
            if (picked != null) {
                sbs.modid.client.core.api.RankingSource.choose(
                        ConfigManager.getInstance().get().ahFlips.flipSource, picked);
                return true;
            }
        }
        model();
        if (bar.handleClick(mx, my, scroll, value -> scroll = value)) {
            return true;
        }
        if (my >= listTop() && my < listBottom()) {
            Card card = cardAt(mx, my);
            if (card != null) {
                clickCard(card, (int) mx, (int) my);
            }
        }
        return true;
    }

    private void clickCard(Card card, int mouseX, int mouseY) {
        int top = listTop() + card.y() - scroll;
        int lineIndex = (mouseY - (top + CARD_PAD + LINE_H)) / LINE_H;
        if (mouseY >= top + CARD_PAD + LINE_H && lineIndex < card.lines().size()) {
            long copy = lineIndex == card.fastLine() ? card.fastPrice()
                    : lineIndex == card.normalLine() ? card.normalPrice()
                    : lineIndex == card.patientLine() ? card.patientPrice() : 0;
            if (copy > 0) {
                copyPrice(copy);
                return;
            }
        }
        int buttonY = top + card.h() - CARD_PAD - BUTTON_H;
        if (mouseY < buttonY || mouseY >= buttonY + BUTTON_H) {
            return;
        }
        int left = innerX();
        if (inside(mouseX, left + CARD_PAD, card.openW())) {
            if (!card.gone()) {
                LocalFlipFinder.openAuction(card.flip());
            }
        } else if (inside(mouseX, left + card.copyX(), card.copyW())) {
            LocalFlipFinder.copyCommand(card.flip());
            copiedAuction = card.flip().auction();
            copiedUntilMs = System.currentTimeMillis() + COPIED_SHOWN_MS;
        } else if (inside(mouseX, left + card.hideX(), card.hideW())) {
            LocalFlipFinder.getInstance().hide(card.flip().auction());
        }
    }

    /** A sell price to the clipboard as a whole number - never with a comma. */
    private static void copyPrice(long coins) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.keyboardHandler.setClipboard(Long.toString(coins));
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.literal("Copied: " + coins).withColor(GREEN));
        }
    }

    private static boolean inside(int x, int from, int width) {
        return x >= from && x < from + width;
    }

    private Card cardAt(double mouseX, double mouseY) {
        if (mouseX < innerX() || mouseX >= innerX() + cardW()) {
            return null;
        }
        int contentY = (int) mouseY - listTop() + scroll;
        for (Item item : model.items()) {
            if (item instanceof Card card && contentY >= card.y() && contentY < card.y() + card.h()) {
                return card;
            }
        }
        return null;
    }

    public boolean handleDrag(Screen screen, MouseButtonEvent event) {
        if (!open || minimized) {
            return false;
        }
        if (dragging) {
            posX = clamp((int) (event.x() - grabDX), MARGIN, Math.max(MARGIN, screen.width - panelW - MARGIN));
            posY = clamp((int) (event.y() - grabDY), MARGIN, Math.max(MARGIN, screen.height - panelH - MARGIN));
            return true;
        }
        if (resizer.isActive()) {
            int[] rect = resizer.drag(event.x(), event.y(), MIN_W, MAX_W, MIN_H, MAX_H);
            posX = rect[0];
            posY = rect[1];
            sizeW = rect[2];
            sizeH = rect[3];
            return true;
        }
        return bar.handleDrag(event.y(), value -> scroll = value);
    }

    public boolean handleRelease(MouseButtonEvent event) {
        if (!open || minimized) {
            return false;
        }
        boolean wasBar = bar.release();
        boolean wasResizing = resizer.end();
        if (!dragging && !wasResizing) {
            return wasBar;
        }
        dragging = false;
        memory.remember(posX, posY, sizeW, sizeH, false);
        return true;
    }

    public boolean handleScroll(Screen screen, double mouseX, double mouseY, double scrollY) {
        if (!open || minimized || !inPanel(mouseX, mouseY)) {
            return false;
        }
        if (isCtrlDown()) {
            int step = (int) Math.signum(scrollY);
            sizeW = clamp(sizeW + step * 30, MIN_W, MAX_W);
            sizeH = clamp(sizeH + step * 20, MIN_H, MAX_H);
            memory.remember(posX, posY, sizeW, sizeH, false);
            return true;
        }
        scroll = Math.max(0, Math.min(bar.maxScroll(), scroll - (int) Math.round(scrollY * SCROLL_STEP)));
        return true;
    }

    /**
     * While the window is closed, the player inventory and the Auction House show the same small
     * "Flip Finder" bar the minimized window uses, so the window is always one click away. Switched
     * off with the "Flip Finder Button" setting.
     */
    private static boolean showsOpenButton(Screen screen) {
        if (!cfg().inventoryButton || !(screen instanceof AbstractContainerScreen<?>)) {
            return false;
        }
        if (screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
            return true;
        }
        String title = screen.getTitle() == null ? "" : screen.getTitle().getString();
        return title.toLowerCase(java.util.Locale.ROOT).contains("auction");
    }

    /** The remembered position, for the open button drawn while the window itself is closed. */
    private void restorePosition() {
        memory.restore(state -> {
            posX = state.x;
            posY = state.y;
            sizeW = state.width(sizeW);
            sizeH = state.height(sizeH);
            minimized = state.minimized;
        });
        if (posX == Integer.MIN_VALUE) {
            posX = 8;
            posY = 8;
        }
    }

    /** The collapsed state: a small "Flip Finder" bar with a restore glyph; click it to reopen. */
    private void drawMinimizedBar(Screen screen, GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        minBarW = font.width("Flip Finder") + 26;
        minBarH = HEADER_H;
        minBarX = clamp(posX, MARGIN, Math.max(MARGIN, screen.width - minBarW - MARGIN));
        minBarY = clamp(posY, MARGIN, Math.max(MARGIN, screen.height - minBarH - MARGIN));
        boolean hover = mouseX >= minBarX && mouseX < minBarX + minBarW
                && mouseY >= minBarY && mouseY < minBarY + minBarH;
        SciFiRender.roundedRectWithBorder(g, minBarX, minBarY, minBarW, minBarH, SBSTheme.CORNER_RADIUS,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        int textY = minBarY + (minBarH - font.lineHeight) / 2 + 1;
        g.text(font, Component.literal("Flip Finder"), minBarX + 5, textY, SBSTheme.ACCENT_BRIGHT);
        int gx = minBarX + minBarW - 12;
        int gy = minBarY + 4;
        g.fill(gx, gy, gx + 8, gy + 2, SBSTheme.ACCENT);
        g.outline(gx, gy, 8, minBarH - 8, hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.ACCENT);
    }

    private int minimizeX() {
        return closeX() - 12;
    }

    private boolean inMinimizeBox(double mx, double my) {
        return mx >= minimizeX() && mx < minimizeX() + 12 && my >= posY + 2 && my < posY + HEADER_H;
    }

    private int closeX() {
        return posX + panelW - 14;
    }

    private boolean inCloseBox(double mx, double my) {
        return mx >= closeX() && mx < closeX() + 12 && my >= posY + 2 && my < posY + HEADER_H;
    }

    private boolean inPanel(double mx, double my) {
        return mx >= posX && mx < posX + panelW && my >= posY && my < posY + panelH;
    }

    private static boolean isCtrlDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
