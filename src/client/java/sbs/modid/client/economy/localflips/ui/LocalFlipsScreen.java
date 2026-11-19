/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.localflips.logic.FlipText;
import sbs.modid.client.economy.localflips.logic.LocalFlipFinder;
import sbs.modid.client.economy.localflips.model.LocalFlip;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.component.SciFiSegmentedSwitch;
import sbs.modid.client.ui.component.SciFiTextField;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

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
 * The Local Flip Finder window („Lokaler Flip-Finder“): the SOFORT finds on top, newest first by
 * default, the program's flip list below, every card with a large Open button.
 *
 * <p>Does not pause the game and holds no data of its own - {@link LocalFlipFinder} keeps receiving
 * while this is open or closed. The header edits the address and token; Connect (or Enter in either
 * field) saves them and rebuilds the connection. The fields only keep a draft while typing, so a
 * half-typed address never opens a connection.
 *
 * <p><b>Render path.</b> Everything drawn per frame is prepared in {@link #model()}, which is rebuilt
 * only when its key changes: the finder's revision, the second on the clock (for the live "found
 * X s ago"), the sort and filters, the thresholds and the list width. A frame only draws.
 *
 * <p><b>Layout.</b> Sized from the viewport with the margin shrinking on small screens; the header
 * controls flow onto further lines when they do not fit beside each other, and every card line is
 * cut to the card width when the model is built. Cards scroll in pixels behind the shared
 * {@link SciFiScrollbar}.
 */
public final class LocalFlipsScreen extends Screen {

    private static final List<String> SORTS = List.of("Gewinn", "Gewinn/Tag", "Sicherheit", "Neueste");
    private static final int PREFERRED_W = 640;
    private static final int PREFERRED_H = 560;
    private static final int CONTROL_H = 16;
    private static final int GAP = 4;
    private static final int LINE_H = 10;
    private static final int CARD_PAD = 4;
    private static final int BUTTON_H = 14;
    private static final int CARD_GAP = 4;
    private static final int SCROLL_STEP = 24;
    private static final long COPIED_SHOWN_MS = 1_500;

    private static final Component TITLE = Component.literal("Lokaler Flip-Finder");
    private static final Component ADDRESS = Component.literal("Adresse");
    private static final Component TOKEN = Component.literal("Token");
    private static final Component OPEN = Component.literal("Öffnen");
    private static final Component OPEN_GONE = Component.literal("weg");
    private static final Component COPY = Component.literal("Kopieren");
    private static final Component COPIED = Component.literal("Kopiert!");
    private static final Component HIDE = Component.literal("Ausblenden");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static final int GREEN = 0xFF57D977;
    private static final int YELLOW = 0xFFFFD64D;
    private static final int ORANGE = 0xFFFF9A3C;
    private static final int RED = 0xFFE0605F;
    private static final int GREY = 0xFF6A7685;

    /** A drawn block in the scrolling list; {@code y} is relative to the top of the list content. */
    private sealed interface Item permits Header, Card {
        int y();

        int h();
    }

    private record Header(int y, int h, Component text) implements Item {
    }

    /**
     * One prepared flip card. Button x positions are relative to the card's left edge.
     *
     * @param textColour colour for lines that carry no colour codes of their own (greyed cards)
     * @param noteLine   index into {@code lines} of the note line, or -1
     */
    private record Card(int y, int h, LocalFlip flip, boolean gone, int textColour,
                        Component badge, int badgeColour, int badgeW,
                        Component title, Component right, int rightW,
                        List<Component> lines, int noteLine, List<Component> noteTip,
                        int openW, int copyX, int copyW, int hideX, int hideW) implements Item {
    }

    private record Model(List<Component> status, List<Item> items, int contentH) {
    }


    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int titleY;
    private int addressLabelX;
    private int tokenLabelX;
    private int tokenLabelRowY;
    private int fieldsY;
    private int statusY;
    private int listTop;
    private int listBottom;
    private int cardW;

    private String urlDraft;
    private String tokenDraft;
    private SciFiTextField urlField;
    private SciFiTextField tokenField;

    private int scroll;
    private final SciFiScrollbar bar = new SciFiScrollbar();

    private Model model = new Model(List.of(), List.of(), 0);

    /*
     * What the model was built from. Plain fields rather than a key record so the per-frame check
     * allocates nothing; built is false until the first build and after anything forces a rebuild.
     */
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

    public LocalFlipsScreen() {
        super(TITLE);
    }

    private static SBSConfig.LocalFlipSettings cfg() {
        return LocalFlipFinder.cfg();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        if (urlDraft == null) {
            urlDraft = cfg().url;
            tokenDraft = cfg().token;
        }
        int marginX = this.width < 400 ? 4 : SBSTheme.SCREEN_MARGIN;
        int marginY = this.height < 300 ? 4 : SBSTheme.SCREEN_MARGIN;
        panelW = Math.min(Math.max(1, this.width - marginX * 2), PREFERRED_W);
        panelH = Math.min(Math.max(1, this.height - marginY * 2), PREFERRED_H);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = panelW < 360 ? 6 : SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentW = Math.max(1, panelW - pad * 2);
        int right = innerX + contentW;

        titleY = panelY + 4;
        int y = titleY + font.lineHeight + 4;

        addRenderableOnly(new PanelRenderable());

        // Row: "Adresse" [url] "Token" [token] [Verbinden]; the token half drops to its own line
        // when the address field would get narrower than 100 px.
        int addressLabelW = font.width(ADDRESS) + GAP;
        int tokenLabelW = font.width(TOKEN) + GAP;
        int connectW = font.width("Verbinden") + 16;
        int tokenW = Math.max(50, Math.min(120, contentW / 5));
        int urlW = contentW - addressLabelW - tokenLabelW - tokenW - connectW - GAP * 3;
        fieldsY = y;
        addressLabelX = innerX;
        if (urlW >= 100) {
            urlField = SciFiTextField.forRow(innerX + addressLabelW, y, urlW, CONTROL_H, "Adresse",
                    "http://127.0.0.1:3020", 200, () -> urlDraft, value -> urlDraft = value);
            tokenLabelX = innerX + addressLabelW + urlW + GAP;
            tokenField = maskedField(tokenLabelX + tokenLabelW, y, tokenW);
            addRenderableWidget(urlField);
            addRenderableWidget(tokenField);
            addRenderableWidget(new SciFiButton(right - connectW, y, connectW, CONTROL_H,
                    Component.literal("Verbinden"), this::connect));
            y += CONTROL_H + GAP;
        } else {
            urlField = SciFiTextField.forRow(innerX + addressLabelW, y, contentW - addressLabelW, CONTROL_H,
                    "Adresse", "http://127.0.0.1:3020", 200, () -> urlDraft, value -> urlDraft = value);
            addRenderableWidget(urlField);
            y += CONTROL_H + GAP;
            tokenLabelX = innerX;
            int stackedTokenW = Math.max(30, contentW - tokenLabelW - connectW - GAP);
            tokenField = maskedField(innerX + tokenLabelW, y, stackedTokenW);
            addRenderableWidget(tokenField);
            addRenderableWidget(new SciFiButton(right - connectW, y, connectW, CONTROL_H,
                    Component.literal("Verbinden"), this::connect));
            y += CONTROL_H + GAP;
        }
        // The token row's label sits on the row the token field ended up on.
        tokenLabelRowY = tokenField.getY() + (CONTROL_H - font.lineHeight) / 2 + 1;

        // Two status lines: connection, then the Hypixel data age (or the stuck warning).
        statusY = y;
        y += LINE_H * 2 + GAP;

        // Sort, "SOFORT only" and the profit filter, flowing onto further lines as needed.
        int switchW = SciFiSegmentedSwitch.widthFor(SORTS);
        int toggleW = font.width("Nur SOFORT") + 50;
        int filterW = font.width("Min. Gewinn") + 8 + font.width("0".repeat(7)) + 8 + 15;
        int[] widths = {Math.min(switchW, contentW), Math.min(toggleW, contentW), Math.min(filterW, contentW)};
        int x = innerX;
        int[] xs = new int[3];
        int[] ys = new int[3];
        for (int i = 0; i < widths.length; i++) {
            if (x > innerX && x + widths[i] > right) {
                x = innerX;
                y += CONTROL_H + GAP;
            }
            xs[i] = x;
            ys[i] = y;
            x += widths[i] + GAP;
        }
        y += CONTROL_H + GAP;
        // A switch wider than the panel would draw past it (ui/AGENTS.md, "a switch that does not
        // fit"). It fits from about 216 px of content; below that the four sorts become four plain
        // buttons sharing the row, so every choice stays visible and one click away.
        if (switchW <= contentW) {
            addRenderableWidget(new SciFiSegmentedSwitch(xs[0], ys[0], CONTROL_H, SORTS,
                    () -> clampSort(cfg().sort), this::setSort));
        } else {
            int each = Math.max(1, (contentW - GAP * 3) / 4);
            for (int i = 0; i < SORTS.size(); i++) {
                int index = i;
                addRenderableWidget(new SciFiButton(innerX + i * (each + GAP), ys[0], each, CONTROL_H,
                        Component.literal(SORTS.get(i)), () -> setSort(index)));
            }
        }
        addRenderableWidget(new SciFiToggleButton(xs[1], ys[1], widths[1], CONTROL_H,
                Component.literal("Nur SOFORT"), () -> cfg().filterSofortOnly, () -> {
                    cfg().filterSofortOnly = !cfg().filterSofortOnly;
                    save();
                }));
        addRenderableWidget(SciFiTextField.forValueRow(xs[2], ys[2], widths[2], CONTROL_H,
                "Min. Gewinn", "0", 12, 7, LocalFlipsScreen::filterText, this::onFilter));

        listTop = y;
        listBottom = Math.max(listTop + 1, panelY + panelH - pad);
        cardW = Math.max(1, contentW - SciFiScrollbar.WIDTH - 3);
        built = false;
    }


    private SciFiTextField maskedField(int x, int y, int w) {
        return SciFiTextField.forMaskedRow(x, y, w, CONTROL_H, "Token", "optional", 200,
                () -> tokenDraft, value -> tokenDraft = value, () -> false);
    }

    private static int clampSort(int sort) {
        return Math.max(0, Math.min(SORTS.size() - 1, sort));
    }

    private void setSort(int index) {
        cfg().sort = clampSort(index);
        save();
    }

    private static String filterText() {
        long value = cfg().filterMinProfit;
        return value > 0 ? FlipText.coins(value) : "";
    }

    /** Text responder: store and save only. The model key picks the change up; nothing is rebuilt. */
    private void onFilter(String text) {
        long parsed = FlipText.parseCoins(text);
        if (cfg().filterMinProfit != parsed) {
            cfg().filterMinProfit = parsed;
            save();
        }
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /** Saves the address and token drafts, switches the finder on, and reconnects now. */
    private void connect() {
        SBSConfig.LocalFlipSettings cfg = cfg();
        cfg.url = urlDraft == null || urlDraft.isBlank() ? new SBSConfig.LocalFlipSettings().url : urlDraft.trim();
        cfg.token = tokenDraft == null ? "" : tokenDraft.trim();
        cfg.enabled = true;
        save();
        LocalFlipFinder.getInstance().reconnectNow();
        built = false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    private Model model() {
        LocalFlipFinder finder = LocalFlipFinder.getInstance();
        SBSConfig.LocalFlipSettings cfg = cfg();
        long now = System.currentTimeMillis();
        long second = now / 1000;
        int sort = clampSort(cfg.sort);
        boolean current = built && builtRevision == finder.revision() && builtSecond == second
                && builtSort == sort && builtFilterMinProfit == cfg.filterMinProfit
                && builtSofortOnly == cfg.filterSofortOnly && builtShowList == cfg.showList
                && builtShowBids == cfg.showBids && builtMinProfit == cfg.minProfit
                && builtMinSafety == cfg.minSafety && builtWidth == cardW && builtStatus == finder.status();
        if (!current) {
            model = build(finder, cfg, now);
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
            bar.set(innerX + contentW - SciFiScrollbar.WIDTH, listTop, listBottom - listTop,
                    model.contentH(), listBottom - listTop);
            scroll = Math.max(0, Math.min(scroll, bar.maxScroll()));
        }
        return model;
    }

    private Model build(LocalFlipFinder finder, SBSConfig.LocalFlipSettings cfg, long now) {
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
                Component.literal("§fSOFORT §7(" + sofort.size() + ")"));
        items.add(sofortHeader);
        y += sofortHeader.h();
        if (sofort.isEmpty()) {
            Header empty = new Header(y, LINE_H + 4, Component.literal(fit(emptySofortText(finder), cardW)));
            items.add(empty);
            y += empty.h();
        }
        for (LocalFlip flip : sofort) {
            Card card = card(flip, y, finder, cfg, now);
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
                    Component.literal("§fFlip-Liste §7(" + list.size() + ")"));
            items.add(listHeader);
            y += listHeader.h();
            for (LocalFlip flip : list) {
                Card card = card(flip, y, finder, cfg, now);
                items.add(card);
                y += card.h() + CARD_GAP;
            }
        }
        return new Model(status, List.copyOf(items), y);
    }

    /**
     * A SOFORT entry without a {@code found_at} is sorted and labelled by when it arrived; giving it
     * that time here keeps the comparator and the card free of the special case.
     */
    private static LocalFlip withFoundAt(LocalFlipFinder.Entry entry) {
        LocalFlip f = entry.flip();
        if (!Double.isNaN(f.foundAt())) {
            return f;
        }
        return new LocalFlip(f.kind(), f.name(), f.itemId(), f.dropClass(), f.count(), f.price(), f.maxBid(),
                f.endsMin(), f.profitNow(), f.target(), f.netProfit(), f.netPct(), f.pSuccess(), f.sellTimeH(),
                f.profitPerDay(), f.priceNormal(), f.timeNormalH(), f.pricePatient(), f.timePatientH(),
                f.salesPerDay(), f.ageMin(), f.seller(), f.note(), f.auction(), entry.receivedMs() / 1000.0,
                f.start(), f.latencyS());
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
            default -> Comparator.comparingDouble(LocalFlipsScreen::newness).reversed();
        };
    }

    /** Larger is newer: {@code found_at} for SOFORT finds, minus the listing age for list entries. */
    private static double newness(LocalFlip flip) {
        if (!Double.isNaN(flip.foundAt())) {
            return flip.foundAt();
        }
        return Double.isNaN(flip.ageMin()) ? -Double.MAX_VALUE : -flip.ageMin() * 60;
    }

    private String emptySofortText(LocalFlipFinder finder) {
        return switch (finder.status()) {
            case OFF -> "§7Der Finder ist aus. „Verbinden“ schaltet ihn ein.";
            case CONNECTED -> "§7Noch keine SOFORT-Funde - sie erscheinen hier, sobald das Programm sie meldet.";
            default -> "§7Keine SOFORT-Funde - das Flip-Programm ist nicht verbunden.";
        };
    }

    private List<Component> statusLines(LocalFlipFinder finder, long now) {
        String connection = switch (finder.status()) {
            case OFF -> "§7● aus";
            case CONNECTING -> "§e● verbinde…";
            case CONNECTED -> "§a● verbunden";
            case DISCONNECTED -> "§c● getrennt – nächster Versuch in "
                    + Math.max(0, (finder.retryAtMs() - now + 999) / 1000) + " s";
            case UNAUTHORIZED -> "§c● 401 – falscher Token";
            case INVALID_ADDRESS -> "§c● Adresse ungültig – nur http(s) auf diesem PC oder im LAN";
        };
        if (finder.lastSignalMs() > 0 && finder.status() == LocalFlipFinder.Status.CONNECTED) {
            connection += "  §8·  §7letztes Signal vor " + FlipText.ago((now - finder.lastSignalMs()) / 1000);
        }
        String api;
        long apiAt = finder.apiLastUpdatedMs();
        if (apiAt <= 0) {
            api = "§7Hypixel-AH-Stand: unbekannt";
        } else if (now - apiAt > LocalFlipFinder.API_STUCK_MS) {
            api = "§cHypixel AH API hängt seit "
                    + CLOCK.format(Instant.ofEpochMilli(apiAt).atZone(ZoneId.systemDefault()))
                    + " – Funde können schon weg sein";
        } else {
            api = "§7Hypixel-AH-Stand: " + FlipText.ago((now - apiAt) / 1000) + " alt";
        }
        return List.of(Component.literal(fit(connection, contentW)), Component.literal(fit(api, contentW)));
    }

    private Card card(LocalFlip flip, int y, LocalFlipFinder finder, SBSConfig.LocalFlipSettings cfg, long now) {
        boolean gone = finder.isGone(flip.auction());
        boolean belowThreshold = !sbs.modid.client.economy.localflips.logic.AlertGate.passes(
                flip, cfg.minProfit, cfg.minSafety);
        int textW = cardW - CARD_PAD * 2;
        double nowSeconds = now / 1000.0;

        // Badge and title.
        String badgeText = gone ? "weg" : flip.kind().label();
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
        // Price line.
        StringBuilder price = new StringBuilder();
        if (flip.kind() == LocalFlip.Kind.GEBOT) {
            price.append("§7Gebot jetzt §f").append(FlipText.coins(flip.price()));
        } else {
            price.append("§f").append(FlipText.coins(flip.price()));
        }
        price.append(" §7→ §e").append(FlipText.coins(flip.target()));
        int safety = flip.safetyPct();
        if (safety >= 0) {
            price.append("  §7Sicherheit ").append(colourCode(LocalFlipFinder.safetyColour(safety)))
                    .append(safety).append(" %");
        }
        lines.add(price.toString());
        // Bids: the most worth bidding and the end, prominently.
        if (flip.kind() == LocalFlip.Kind.GEBOT && flip.has(flip.maxBid())) {
            StringBuilder bid = new StringBuilder("§e§lhöchstens ").append(FlipText.coins(flip.maxBid()))
                    .append(" bieten§r§e – endet in ").append(FlipText.minutes(flip.endsMin()));
            if (flip.has(flip.profitNow())) {
                bid.append(" §7· jetzt §a+").append(FlipText.coins(flip.profitNow()));
            }
            lines.add(bid.toString());
        }
        // Sell time and profit per day.
        StringBuilder sell = new StringBuilder();
        if (!flip.dropClass().isEmpty()) {
            sell.append("§d").append(flip.dropClass()).append(" §8· ");
        }
        sell.append("§7Verkauf ~").append(FlipText.hours(flip.sellTimeH()));
        if (flip.has(flip.profitPerDay())) {
            sell.append(" §8· §f").append(FlipText.coins(flip.profitPerDay())).append("§7/Tag");
        }
        lines.add(sell.toString());
        // Timing: SOFORT finds count live; list entries carry age, seller and sales.
        if (!Double.isNaN(flip.foundAt()) || !Double.isNaN(flip.start())) {
            StringBuilder timing = new StringBuilder("§7");
            if (!Double.isNaN(flip.foundAt())) {
                timing.append("gefunden vor ").append(FlipText.ago((long) (nowSeconds - flip.foundAt())));
            }
            if (!Double.isNaN(flip.start())) {
                double inAhMin = (nowSeconds - flip.start()) / 60.0;
                if (!Double.isNaN(flip.foundAt())) {
                    timing.append(" §8· ");
                }
                timing.append(inAhMin >= 3 ? "§6" : "§7").append("im AH seit ").append(FlipText.minutes(inAhMin));
            }
            lines.add(timing.toString());
        }
        if (!Double.isNaN(flip.ageMin()) || !flip.seller().isEmpty() || !Double.isNaN(flip.salesPerDay())) {
            List<String> parts = new ArrayList<>(3);
            if (!Double.isNaN(flip.ageMin())) {
                parts.add("Alter " + FlipText.minutes(flip.ageMin()));
            }
            if (!flip.seller().isEmpty()) {
                parts.add("Verkäufer " + flip.seller());
            }
            if (!Double.isNaN(flip.salesPerDay())) {
                parts.add(FlipText.decimal1(flip.salesPerDay()) + " Verkäufe/Tag");
            }
            lines.add("§7" + String.join(" §8· §7", parts));
        }
        // Normal and patient sell prices.
        if (flip.has(flip.priceNormal()) || flip.has(flip.pricePatient())) {
            StringBuilder targets = new StringBuilder();
            if (flip.has(flip.priceNormal())) {
                targets.append("§7normal §f").append(FlipText.coins(flip.priceNormal()))
                        .append(" §8(").append(FlipText.hours(flip.timeNormalH())).append(')');
            }
            if (flip.has(flip.pricePatient())) {
                if (!targets.isEmpty()) {
                    targets.append(" §8· ");
                }
                targets.append("§7geduldig §f").append(FlipText.coins(flip.pricePatient()))
                        .append(" §8(").append(FlipText.hours(flip.timePatientH())).append(')');
            }
            lines.add(targets.toString());
        }
        // The note: first hint on the card, all of them in the tooltip.
        List<String> noteParts = LocalFlipFinder.noteParts(flip.note());
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

        // Buttons: Open takes half the row, Copy and Hide share the rest.
        int buttonRow = textW;
        int openW = Math.max(30, buttonRow / 2);
        int restW = Math.max(2, buttonRow - openW - GAP * 2);
        int copyW = restW / 2;
        int hideW = restW - copyW;
        int copyX = CARD_PAD + openW + GAP;
        int hideX = copyX + copyW + GAP;

        int h = CARD_PAD + LINE_H + drawn.size() * LINE_H + 2 + BUTTON_H + CARD_PAD;
        return new Card(y, h, flip, gone, textColour, Component.literal(badgeText), badgeColour, badgeW,
                titleComponent, rightComponent, rightW, List.copyOf(drawn), noteLine, noteTip,
                openW, copyX, copyW, hideX, hideW);
    }

    /** FEHLPREIS red, GEBOT: yellow, the other warning prefixes orange, anything else grey. */
    private static String noteColour(String part) {
        if (part.startsWith("FEHLPREIS")) {
            return "§c§l";
        }
        if (part.startsWith("GEBOT:")) {
            return "§e";
        }
        if (part.startsWith("STAPEL") || part.startsWith("Pet Candy") || part.startsWith("KEINE UUID")) {
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
    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width("...")), false) + "...";
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean enter = event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER;
        if (enter && ((urlField != null && urlField.isFocused()) || (tokenField != null && tokenField.isFocused()))) {
            connect();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseY >= listTop && mouseY < listBottom) {
            model();
            scroll = Math.max(0, Math.min(bar.maxScroll(), scroll - (int) Math.round(scrollY * SCROLL_STEP)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        model();
        if (bar.handleClick(event.x(), event.y(), scroll, value -> scroll = value)) {
            return true;
        }
        if (event.button() == 0 && event.y() >= listTop && event.y() < listBottom) {
            Card card = cardAt(event.x(), event.y());
            if (card != null && clickCard(card, (int) event.x(), (int) event.y())) {
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    private boolean clickCard(Card card, int mouseX, int mouseY) {
        int top = listTop + card.y() - scroll;
        int buttonY = top + card.h() - CARD_PAD - BUTTON_H;
        if (mouseY < buttonY || mouseY >= buttonY + BUTTON_H) {
            return false;
        }
        int left = innerX;
        if (inside(mouseX, left + CARD_PAD, card.openW())) {
            if (!card.gone()) {
                LocalFlipFinder.openAuction(card.flip());
            }
            return true;
        }
        if (inside(mouseX, left + card.copyX(), card.copyW())) {
            LocalFlipFinder.copyCommand(card.flip());
            copiedAuction = card.flip().auction();
            copiedUntilMs = System.currentTimeMillis() + COPIED_SHOWN_MS;
            return true;
        }
        if (inside(mouseX, left + card.hideX(), card.hideW())) {
            LocalFlipFinder.getInstance().hide(card.flip().auction());
            return true;
        }
        return false;
    }

    private static boolean inside(int x, int from, int width) {
        return x >= from && x < from + width;
    }

    private Card cardAt(double mouseX, double mouseY) {
        if (mouseX < innerX || mouseX >= innerX + cardW) {
            return null;
        }
        int contentY = (int) mouseY - listTop + scroll;
        List<Item> items = model.items();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof Card card && contentY >= card.y() && contentY < card.y() + card.h()) {
                return card;
            }
        }
        return null;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (bar.handleDrag(event.y(), value -> scroll = value)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return bar.release() || super.mouseReleased(event);
    }

    // ------------------------------------------------------------------
    // Drawing: prepared data only
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = LocalFlipsScreen.this.font;
            Model m = model();
            g.fill(0, 0, LocalFlipsScreen.this.width, LocalFlipsScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            g.centeredText(font, TITLE, panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);

            int labelY = fieldsY + (CONTROL_H - font.lineHeight) / 2 + 1;
            g.text(font, ADDRESS, addressLabelX, labelY, SBSTheme.TEXT_MUTED);
            g.text(font, TOKEN, tokenLabelX, tokenLabelRowY, SBSTheme.TEXT_MUTED);

            List<Component> status = m.status();
            for (int i = 0; i < status.size(); i++) {
                g.text(font, status.get(i), innerX, statusY + i * LINE_H, SBSTheme.TEXT);
            }

            g.enableScissor(innerX, listTop, innerX + contentW, listBottom);
            Card hoveredNote = null;
            List<Item> items = m.items();
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                int top = listTop + item.y() - scroll;
                if (top + item.h() < listTop || top > listBottom) {
                    continue;
                }
                if (item instanceof Header header) {
                    g.text(font, header.text(), innerX, top + 1, SBSTheme.TEXT);
                } else if (item instanceof Card card) {
                    if (drawCard(g, font, card, top, mouseX, mouseY)) {
                        hoveredNote = card;
                    }
                }
            }
            g.disableScissor();
            bar.render(g, scroll, mouseX, mouseY);

            if (hoveredNote != null && mouseY >= listTop && mouseY < listBottom) {
                g.setTooltipForNextFrame(font, hoveredNote.noteTip(), Optional.empty(), mouseX, mouseY,
                        SBSTheme.tooltipStyle());
            }
        }
    }

    /** Draws one card; returns true when the mouse is over its note line (tooltip wanted). */
    private boolean drawCard(GuiGraphicsExtractor g, Font font, Card card, int top, int mouseX, int mouseY) {
        int x = innerX;
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
                !card.gone(), true, mouseX, mouseY);
        button(g, font, x + card.copyX(), buttonY, card.copyW(), copied ? COPIED : COPY, true, false,
                mouseX, mouseY);
        button(g, font, x + card.hideX(), buttonY, card.hideW(), HIDE, true, false, mouseX, mouseY);
        return overNote;
    }

    private void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, Component label, boolean active,
                        boolean primary, int mouseX, int mouseY) {
        boolean hovered = active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BUTTON_H
                && mouseY >= listTop && mouseY < listBottom;
        int fill = !active ? SBSTheme.CARD_BG_DISABLED : hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.SEARCH_FILL;
        int border = !active ? SBSTheme.CARD_BORDER : primary ? GREEN : hovered ? SBSTheme.ACCENT_BRIGHT
                : SBSTheme.CARD_BORDER;
        SciFiRender.roundedRectWithBorder(g, x, y, w, BUTTON_H, 3, fill, border);
        g.centeredText(font, label, x + w / 2, y + (BUTTON_H - font.lineHeight) / 2 + 1,
                !active ? GREY : primary ? GREEN : SBSTheme.TEXT);
    }
}
