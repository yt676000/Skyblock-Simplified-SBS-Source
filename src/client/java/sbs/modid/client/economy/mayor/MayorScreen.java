/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.mayor;

import com.google.gson.JsonArray;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.economy.mayor.MayorApi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The Mayor window: the active SkyBlock mayor with their perks + minister, and the running
 * election's candidates ranked by live vote count. Opened with {@code /sbs mayor}; with
 * {@code /sbs mayor <item>} it also shows that item's current mayor price effect and the per-mayor
 * price history.
 *
 * <p>All the data comes from the read-only {@code /api/mayor} endpoint ({@link MayorApi}); this
 * screen only renders it. It is written to survive thin data - a missing {@code active}/
 * {@code election} block, empty perk or candidate lists, an item with no effect or an all-zero
 * history - by simply showing a muted "no data yet" line for that part, never a crash and never a
 * misleading placeholder. Most of the data is thin at first (the server is still gathering it), so
 * that is the expected state, not an error.
 */
public final class MayorScreen extends Screen {

    private static final int ROW_H = 11;
    private static final long REFRESH_MS = 60_000L;

    private static MayorApi.Response cached;
    private static long cachedAt;
    private static String error;
    private static boolean loading;
    /** The item id the cache was fetched for ({@code null} = the base item-less request). */
    private static String cachedItem;

    /** The item this screen was opened for ({@code null} = item-less), and its display name. */
    private final String itemId;
    private final String itemName;

    /** One rendered line; {@code right} is the vote/share column (empty for headers and perks). */
    private record Line(String left, String right, int leftColor, int rightColor) {
    }

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int listTop;
    private int listBottom;
    private int scroll;

    /** Lines cache, rebuilt only when the response reference changes. */
    private List<Line> lines = List.of();
    private Object builtFor;

    /** The base window: active mayor + election, no item price effect. */
    public MayorScreen() {
        this(null, null);
    }

    /**
     * The item variant: additionally requests and shows the item's current mayor price effect and
     * per-mayor history. {@code itemId} is the normalised Hypixel id (e.g. {@code HYPERION});
     * {@code itemName} is what the header shows ({@code itemId} when null).
     */
    public MayorScreen(String itemId, String itemName) {
        super(Component.literal("Mayor"));
        this.itemId = itemId != null && !itemId.isBlank() ? itemId : null;
        this.itemName = itemName != null && !itemName.isBlank() ? itemName : this.itemId;
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 380, 620);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 240, 460);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        innerX = panelX + SBSTheme.PANEL_PADDING;
        contentWidth = panelW - SBSTheme.PANEL_PADDING * 2;
        listTop = dividerY + SBSTheme.GAP_AFTER_HEADER;
        int backY = panelY + panelH - SBSTheme.PANEL_PADDING - SBSTheme.SEARCH_HEIGHT;
        listBottom = backY - 6;

        addRenderableOnly(new PanelRenderable());

        int half = (contentWidth - 6) / 2;
        addRenderableWidget(new SciFiButton(innerX, backY, half, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Refresh"), () -> load(true, itemId)));
        addRenderableWidget(new SciFiButton(innerX + half + 6, backY, contentWidth - half - 6,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Back"),
                () -> Minecraft.getInstance().setScreenAndShow(null)));

        load(false, itemId);
    }

    /**
     * Fetches the mayor data for {@code itemId} ({@code null} = item-less), reusing the last
     * response unless it is stale, {@code force}d, or was fetched for a <b>different</b> item -
     * switching between {@code /sbs mayor} and {@code /sbs mayor <item>} must never show the wrong
     * item's data, so a changed item drops the cache and reloads.
     */
    public static void load(boolean force, String itemId) {
        boolean sameItem = Objects.equals(itemId, cachedItem);
        if (loading && sameItem) {
            return; // a request for this exact item is already in flight
        }
        if (!force && sameItem && cached != null && System.currentTimeMillis() - cachedAt < REFRESH_MS) {
            return;
        }
        if (!sameItem) {
            cached = null; // different item - do not render the previous item's data while loading
        }
        loading = true;
        error = null;
        cachedItem = itemId;
        MayorApi.getInstance().fetch(itemId, (result, err) -> {
            cached = result;
            error = err;
            cachedAt = System.currentTimeMillis();
            loading = false;
        });
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            int visible = Math.max(1, (listBottom - listTop) / ROW_H);
            int max = Math.max(0, currentLines().size() - visible);
            scroll = clamp(scroll - (int) Math.signum(scrollY), 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------
    // Line building (defensive - every block/list can be missing or empty)
    // ------------------------------------------------------------------

    private List<Line> currentLines() {
        if (loading && cached == null) {
            return List.of(muted("Loading mayor data..."));
        }
        if (cached == null) {
            return List.of(new Line(error != null ? error : "No data", "", SBSTheme.WARN, SBSTheme.WARN));
        }
        if (builtFor != cached) {
            lines = buildLines(cached, itemId, itemName);
            builtFor = cached;
        }
        return lines;
    }

    private static List<Line> buildLines(MayorApi.Response r, String itemId, String itemName) {
        List<Line> built = new ArrayList<>();
        if (itemId != null) {
            addItemEffect(built, r.current_effect, itemName);
        }
        addActive(built, r.active);
        addElection(built, r.election);
        if (itemId != null) {
            addHistory(built, r.history);
        }
        if (built.isEmpty()) {
            built.add(muted("No mayor data available yet"));
        }
        return built;
    }

    /**
     * The queried item's current mayor price effect (same framing as the appraise line): a red
     * warning when the price is inflated (above normal), a muted note when deflated (below normal).
     * Shown only when the server sent {@code available == true}; otherwise a single honest muted
     * line - the effect data is thin at first, which is the normal case, not an error.
     */
    private static void addItemEffect(List<Line> built, MayorApi.CurrentEffect effect, String itemName) {
        built.add(header("Price Effect" + (itemName != null ? " — " + sanitize(itemName) : "")));
        if (effect == null || !Boolean.TRUE.equals(effect.available)) {
            built.add(muted("No mayor price data yet for this item"));
            return;
        }
        double pct = effect.effect_pct != null ? effect.effect_pct : 0;
        boolean inflated = pct > 0;
        String note = effect.note != null ? sanitizeNote(effect.note) : "";
        String who = effect.mayor_name != null && !effect.mayor_name.isBlank()
                ? " under Mayor " + sanitize(effect.mayor_name) : "";
        String text;
        if (!note.isEmpty()) {
            text = (inflated ? "! " : "") + note;
        } else if (inflated) {
            text = "! ~+" + trimPct(pct) + "% above normal" + who + " - price inflated, factor it out";
        } else if (pct < 0) {
            text = "~" + trimPct(pct) + "% below normal" + who + " - prices deflated";
        } else {
            built.add(muted("Price is around normal right now"));
            return;
        }
        int color = inflated ? SBSTheme.WARN : SBSTheme.TEXT_MUTED;
        built.add(new Line(text, "", color, color));
    }

    /**
     * The per-mayor price history for the queried item, keyed by mayor name in {@code history}.
     * Until a term completes {@code n_terms_recorded} is 0 for everyone - shown honestly as
     * "no history yet", never faked.
     */
    private static void addHistory(List<Line> built, com.google.gson.JsonObject history) {
        built.add(header("Mayor Price History"));
        if (history == null || history.size() == 0) {
            built.add(muted("No history recorded yet"));
            return;
        }
        int shown = 0;
        for (String mayor : history.keySet()) {
            MayorApi.HistoryEntry entry = MayorApi.historyEntry(history, mayor);
            if (entry == null) {
                continue;
            }
            String right = entry.termsRecorded() > 0
                    ? entry.termsRecorded() + (entry.termsRecorded() == 1 ? " term" : " terms")
                    : "no history yet";
            int rightColor = entry.termsRecorded() > 0 ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED;
            built.add(new Line(sanitize(mayor), right, SBSTheme.TEXT, rightColor));
            shown++;
        }
        if (shown == 0) {
            built.add(muted("No history recorded yet"));
        }
    }

    private static void addActive(List<Line> built, MayorApi.Active active) {
        built.add(header("Active Mayor"));
        if (active == null || active.mayor_name == null || active.mayor_name.isBlank()) {
            built.add(muted("Not available yet"));
            return;
        }
        String year = active.election_year != null ? "  ·  Year " + active.election_year : "";
        built.add(new Line(sanitize(active.mayor_name) + year, "", SBSTheme.ACCENT_BRIGHT, SBSTheme.ACCENT_BRIGHT));
        if (active.minister_name != null && !active.minister_name.isBlank()) {
            built.add(muted("Minister: " + sanitize(active.minister_name)));
        }
        addPerks(built, active.perks);
        addPerks(built, active.minister_perks);
    }

    private static void addElection(List<Line> built, MayorApi.Election election) {
        String yearLabel = election != null && election.year != null ? " — Year " + election.year : "";
        built.add(header("Election" + yearLabel));
        if (election == null || election.candidates == null || election.candidates.length == 0) {
            built.add(muted("No election data yet"));
            return;
        }
        // Rank by live votes, highest first; the share is out of the total cast so far.
        MayorApi.Candidate[] candidates = election.candidates.clone();
        Arrays.sort(candidates, Comparator.comparingLong(
                (MayorApi.Candidate c) -> c.votes != null ? c.votes : 0L).reversed());
        long total = 0;
        for (MayorApi.Candidate candidate : candidates) {
            total += candidate.votes != null ? candidate.votes : 0L;
        }
        int rank = 0;
        for (MayorApi.Candidate candidate : candidates) {
            if (candidate == null || candidate.name == null || candidate.name.isBlank()) {
                continue;
            }
            rank++;
            long votes = candidate.votes != null ? candidate.votes : 0L;
            String key = candidate.key != null && !candidate.key.isBlank()
                    ? " (" + sanitize(candidate.key) + ")" : "";
            String share = total > 0 ? "  " + Math.round(votes * 100.0 / total) + "%" : "";
            String right = votes > 0 ? formatVotes(votes) + share : "no votes";
            built.add(new Line("#" + rank + " " + sanitize(candidate.name) + key, right,
                    rank <= 1 ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT, SBSTheme.TOGGLE_ON));
        }
    }

    /** Renders a perk list as muted "· label" lines (with the description appended when present). */
    private static void addPerks(List<Line> built, JsonArray perks) {
        if (perks == null) {
            return;
        }
        for (int i = 0; i < perks.size(); i++) {
            String label = MayorApi.perkLabel(perks.get(i));
            if (label == null || label.isBlank()) {
                continue;
            }
            String description = MayorApi.perkDescription(perks.get(i));
            String text = "· " + sanitize(label)
                    + (description != null && !description.isBlank() ? " — " + sanitize(description) : "");
            built.add(muted(text));
        }
    }

    private static Line header(String text) {
        return new Line(text, "", SBSTheme.ACCENT, SBSTheme.ACCENT);
    }

    private static Line muted(String text) {
        return new Line(text, "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED);
    }

    /** API text is untrusted display data: strip § / control chars and clamp to a tidy length. */
    private static String sanitize(String text) {
        String clean = text.replaceAll("[^\\x20-\\x7E]", "").trim();
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    /** The server's price-effect note is untrusted: strip control/§ chars and clamp to one tidy line. */
    private static String sanitizeNote(String note) {
        String clean = note.replaceAll("[^\\x20-\\x7E]", "").trim();
        return clean.length() > 90 ? clean.substring(0, 90) : clean;
    }

    /** Percent to one decimal, sign preserved: -12.4 -> "-12.4", 8.0 -> "8". */
    private static String trimPct(double value) {
        String text = String.format(Locale.US, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /** "422,081", or "422.1k" when the player has asked for shortened numbers. */
    private static String formatVotes(long votes) {
        return sbs.modid.client.core.util.NumberDisplay.format(votes);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = MayorScreen.this.font;
            g.fill(0, 0, MayorScreen.this.width, MayorScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            String title = itemName != null ? "SkyBlock Mayor — " + sanitize(itemName) : "SkyBlock Mayor";
            g.centeredText(font, Component.literal(title), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            g.fill(panelX + SBSTheme.PANEL_PADDING, dividerY, panelX + panelW - SBSTheme.PANEL_PADDING,
                    dividerY + 1, SBSTheme.ACCENT);

            List<Line> list = currentLines();
            int visible = Math.max(1, (listBottom - listTop) / ROW_H);
            int maxScroll = Math.max(0, list.size() - visible);
            scroll = clamp(scroll, 0, maxScroll);
            if (maxScroll > 0) {
                g.text(font, Component.literal((scroll + 1) + "-" + Math.min(list.size(), scroll + visible)
                                + "/" + list.size()),
                        panelX + panelW - SBSTheme.PANEL_PADDING - 44, titleY, SBSTheme.TEXT_MUTED);
            }
            for (int i = 0; i < visible && scroll + i < list.size(); i++) {
                Line line = list.get(scroll + i);
                int rowY = listTop + i * ROW_H;
                String right = line.right();
                int rightW = right.isEmpty() ? 0 : font.width(right) + 4;
                g.text(font, Component.literal(font.plainSubstrByWidth(line.left(),
                                contentWidth - rightW, false)),
                        innerX, rowY, line.leftColor());
                if (!right.isEmpty()) {
                    g.text(font, Component.literal(right), innerX + contentWidth - font.width(right),
                            rowY, line.rightColor());
                }
            }
        }
    }
}
