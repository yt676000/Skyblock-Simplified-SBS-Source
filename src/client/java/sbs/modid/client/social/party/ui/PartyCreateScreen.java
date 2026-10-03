/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.party.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiCycleButton;
import sbs.modid.client.ui.component.SciFiTextField;
import sbs.modid.client.ui.component.SciFiToggleButton;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.social.party.logic.PartyFinderApi;
import sbs.modid.client.social.party.logic.PartyFinderManager;
import sbs.modid.client.social.party.model.PartyTypes;

/**
 * The "Create Party" dialog: the common fields (size, note, SB level, MP) plus the
 * selected type's own requirement fields. Numeric requirements with value 0 are "off"; toggles map
 * to server-checked ownership / stat requirements where the profile can prove them, and to manual
 * checkmarks where it can't. The draft survives closing the dialog (static), so re-opening keeps
 * the previous setup – parties are usually re-created with the same requirements.
 */
public final class PartyCreateScreen extends Screen {

    /** The editable draft (static: survives dialog close/reopen). */
    static final class Draft {
        String type = "diana";
        int size = 5;
        String note = "";
        int sbLevel;
        int mp;
        boolean eman9 = false;
        // fishing
        int fishingLevel;
        int bobbinTime;
        int locationIdx;                 // index into PartyTypes.FISHING_LOCATIONS
        boolean frozenBlaze;
        boolean magmaLord;
        int bestiaryMilestone;
        // mining
        int hotm;
        int powderMio;
        int nucleusRuns;
        int mineshafts;
        // combat / hunting
        String mob = "";
        int eyesPlaced;
        // kuudra
        int kuudraTierIdx;               // index into PartyTypes.KUUDRA_TIERS
        int kuudraCompletions;
        // kuudra pieces (armor-piece hunt)
        boolean pieceHelmet;
        boolean pieceChest;
        boolean pieceLegs;
        boolean pieceBoots;
        boolean cataclysmicLobby;
        // diana
        int griffinIdx;                  // 0 Any, 1 Legendary, 2 Mythic
        boolean lootingV;
        boolean fleece;
        boolean bloodshotBelt;
        int dianaMobKills;
        int modeIdx;                     // 0 everyone kills own, 1 one kills
    }

    private static final Draft DRAFT = new Draft();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentWidth;
    private int rowsTop;
    private int rowsBottom;
    private int scroll;
    private volatile String status = "";

    public PartyCreateScreen() {
        super(Component.literal("Create Party"));
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 340, 420);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 240, 420);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentWidth = panelW - pad * 2;
        int buttonY = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
        rowsTop = dividerY + SBSTheme.GAP_AFTER_HEADER;
        rowsBottom = buttonY - 6;

        addRenderableOnly(new PanelRenderable());
        buildRows();

        int bw = (contentWidth - 6) / 2;
        addRenderableWidget(new SciFiButton(innerX, buttonY, bw, SBSTheme.SEARCH_HEIGHT,
                Component.literal("Create Party"), this::onCreate));
        addRenderableWidget(new SciFiButton(innerX + bw + 6, buttonY, contentWidth - bw - 6,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Back"), this::onBack));
    }

    // ------------------------------------------------------------------
    // Row building (per type, scrollable)
    // ------------------------------------------------------------------

    /** One row = one widget factory; scrolling re-inits with an offset. */
    private interface Row {
        net.minecraft.client.gui.components.AbstractWidget create(int x, int y, int w, int h);
    }

    private final java.util.List<net.minecraft.client.gui.components.AbstractWidget> rowWidgets =
            new java.util.ArrayList<>();

    private void buildRows() {
        for (var widget : rowWidgets) {
            removeWidget(widget);
        }
        rowWidgets.clear();

        java.util.List<Row> rows = new java.util.ArrayList<>();
        Draft d = DRAFT;

        // Type selector first – switching rebuilds the type-specific rows.
        rows.add((x, y, w, h) -> new SciFiCycleButton(x, y, w, h, Component.literal("Party Type"),
                () -> Component.literal(PartyTypes.label(d.type)),
                () -> { d.type = PartyTypes.next(d.type); scroll = 0; buildRows(); }));

        boolean dungeons = d.type.equals("dungeons");

        // Common requirements (0 = off). Dungeons keep only Party Size, SB Level and MP.
        rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "Party Size", 2, 100,
                () -> d.size, v -> d.size = v, ""));
        if (!dungeons) {
            rows.add((x, y, w, h) -> SciFiTextField.forRow(x, y, w, h, "Note", "Note...", 200,
                    () -> d.note, v -> d.note = v));
        }
        rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "SB Lvl (0=off)", 0, 600,
                () -> d.sbLevel, v -> d.sbLevel = v, ""));
        rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "MP (0=off)", 0, 3000,
                () -> d.mp, v -> d.mp = v, ""));
        if (!dungeons) {
            rows.add((x, y, w, h) -> new SciFiToggleButton(x, y, w, h,
                    Component.literal("Eman Slayer 9"), () -> d.eman9, () -> d.eman9 = !d.eman9));
        }

        switch (d.type) {
            case "fishing" -> {
                rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "Fishing Lvl (0=off)", 0, 60,
                        () -> d.fishingLevel, v -> d.fishingLevel = v, ""));
                rows.add((x, y, w, h) -> new SciFiCycleButton(x, y, w, h, Component.literal("Location"),
                        () -> Component.literal(PartyTypes.FISHING_LOCATIONS[d.locationIdx]),
                        () -> d.locationIdx = (d.locationIdx + 1) % PartyTypes.FISHING_LOCATIONS.length));
                rows.add((x, y, w, h) -> new SciFiToggleButton(x, y, w, h,
                        Component.literal("Frozen Blaze Set"), () -> d.frozenBlaze, () -> d.frozenBlaze = !d.frozenBlaze));
                rows.add((x, y, w, h) -> new SciFiToggleButton(x, y, w, h,
                        Component.literal("Magma Lord Set"), () -> d.magmaLord, () -> d.magmaLord = !d.magmaLord));
                rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "Bestiary Milestone (0=off)", 0, 1000,
                        () -> d.bestiaryMilestone, v -> d.bestiaryMilestone = v, ""));
                rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "Bobbin' Time (0=off)", 0, 5,
                        () -> d.bobbinTime, v -> d.bobbinTime = v, ""));
            }
            case "mining" -> {
                rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "HOTM (0=off)", 0, 10,
                        () -> d.hotm, v -> d.hotm = v, ""));
                rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "Powder (0=off)", 0, 30000,
                        () -> d.powderMio, v -> d.powderMio = v, "m"));
                rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "Nucleus Runs (0=off)", 0, 10000,
                        () -> d.nucleusRuns, v -> d.nucleusRuns = v, ""));
                rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "Mineshafts (0=off)", 0, 100000,
                        () -> d.mineshafts, v -> d.mineshafts = v, ""));
            }
            case "combat" -> {
                rows.add((x, y, w, h) -> SciFiTextField.forRow(x, y, w, h, "Mob / Boss", "e.g. Zealot, Dragon...", 48,
                        () -> d.mob, v -> d.mob = v));
                rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "Bestiary Milestone (0=off)", 0, 1000,
                        () -> d.bestiaryMilestone, v -> d.bestiaryMilestone = v, ""));
                rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "Eyes Placed (0=off)", 0, 100000,
                        () -> d.eyesPlaced, v -> d.eyesPlaced = v, ""));
            }
            case "dungeons" -> {
                // Dungeons: only Party Size, SB Level and MP (the common rows above) - no extras.
            }
            case "kuudra" -> {
                rows.add((x, y, w, h) -> new SciFiCycleButton(x, y, w, h, Component.literal("Tier"),
                        () -> Component.literal(PartyTypes.KUUDRA_TIERS[d.kuudraTierIdx]),
                        () -> d.kuudraTierIdx = (d.kuudraTierIdx + 1) % PartyTypes.KUUDRA_TIERS.length));
                rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "Tier Completions (0=off)", 0, 100000,
                        () -> d.kuudraCompletions, v -> d.kuudraCompletions = v, ""));
            }
            case "kuudra_pieces" -> {
                rows.add((x, y, w, h) -> new SciFiToggleButton(x, y, w, h,
                        Component.literal("Helmet"), () -> d.pieceHelmet, () -> d.pieceHelmet = !d.pieceHelmet));
                rows.add((x, y, w, h) -> new SciFiToggleButton(x, y, w, h,
                        Component.literal("Chestplate"), () -> d.pieceChest, () -> d.pieceChest = !d.pieceChest));
                rows.add((x, y, w, h) -> new SciFiToggleButton(x, y, w, h,
                        Component.literal("Leggings"), () -> d.pieceLegs, () -> d.pieceLegs = !d.pieceLegs));
                rows.add((x, y, w, h) -> new SciFiToggleButton(x, y, w, h,
                        Component.literal("Boots"), () -> d.pieceBoots, () -> d.pieceBoots = !d.pieceBoots));
                rows.add((x, y, w, h) -> new SciFiToggleButton(x, y, w, h,
                        Component.literal("Cataclysmic Lobby"), () -> d.cataclysmicLobby,
                        () -> d.cataclysmicLobby = !d.cataclysmicLobby));
            }
            case "diana" -> {
                rows.add((x, y, w, h) -> new SciFiCycleButton(x, y, w, h, Component.literal("Griffin"),
                        () -> Component.literal(PartyTypes.GRIFFIN[d.griffinIdx]),
                        () -> d.griffinIdx = (d.griffinIdx + 1) % PartyTypes.GRIFFIN.length));
                rows.add((x, y, w, h) -> new SciFiToggleButton(x, y, w, h,
                        Component.literal("Looting V"), () -> d.lootingV, () -> d.lootingV = !d.lootingV));
                rows.add((x, y, w, h) -> new SciFiToggleButton(x, y, w, h,
                        Component.literal("Fleece"), () -> d.fleece, () -> d.fleece = !d.fleece));
                rows.add((x, y, w, h) -> new SciFiToggleButton(x, y, w, h,
                        Component.literal("Bloodshot Belt"), () -> d.bloodshotBelt, () -> d.bloodshotBelt = !d.bloodshotBelt));
                rows.add((x, y, w, h) -> SciFiTextField.forIntRow(x, y, w, h, "Mythos Kills / Crest (0=off)", 0, 1000000,
                        () -> d.dianaMobKills, v -> d.dianaMobKills = v, ""));
                rows.add((x, y, w, h) -> new SciFiCycleButton(x, y, w, h, Component.literal("Mode"),
                        () -> Component.literal(PartyTypes.MODES[d.modeIdx]),
                        () -> d.modeIdx = (d.modeIdx + 1) % PartyTypes.MODES.length));
            }
            default -> {
            }
        }

        int rowStep = SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;
        int maxVisible = Math.max(1, (rowsBottom - rowsTop + SBSTheme.ENTRY_SPACING) / rowStep);
        scroll = clamp(scroll, 0, Math.max(0, rows.size() - maxVisible));
        int y = rowsTop;
        for (int i = scroll; i < rows.size() && i < scroll + maxVisible; i++) {
            var widget = rows.get(i).create(innerX, y, contentWidth, SBSTheme.ENTRY_HEIGHT);
            addRenderableWidget(widget);
            rowWidgets.add(widget);
            y += rowStep;
        }
        this.totalRows = rows.size();
    }

    private int totalRows;

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && mouseY >= rowsTop && mouseY <= rowsBottom) {
            scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
            buildRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    /** Builds the create payload: party fields + the server-checked requirement list. */
    static JsonObject buildSpec() {
        Draft d = DRAFT;
        JsonObject spec = new JsonObject();
        spec.addProperty("type", d.type);
        spec.addProperty("size", d.size);
        spec.addProperty("note", d.note);
        JsonArray reqs = new JsonArray();

        if (d.sbLevel > 0) {
            reqs.add(stat("sb_level", d.sbLevel));
        }
        if (d.mp > 0) {
            reqs.add(stat("magical_power", d.mp));
        }
        if (d.eman9 && !d.type.equals("dungeons")) {
            reqs.add(stat("slayer_enderman", 9));
        }
        switch (d.type) {
            case "fishing" -> {
                if (d.fishingLevel > 0) {
                    reqs.add(stat("skill_fishing", d.fishingLevel));
                }
                if (d.locationIdx > 0) {
                    spec.addProperty("location", PartyTypes.FISHING_LOCATIONS[d.locationIdx]);
                }
                if (d.frozenBlaze) {
                    reqs.add(owns("owns_set:FROZEN_BLAZE"));
                }
                if (d.magmaLord) {
                    reqs.add(owns("owns_set:MAGMA_LORD"));
                }
                if (d.bestiaryMilestone > 0) {
                    reqs.add(stat("bestiary_milestone", d.bestiaryMilestone));
                }
                if (d.bobbinTime > 0) {
                    reqs.add(manual("Bobbin' Time " + d.bobbinTime + "+"));
                }
            }
            case "mining" -> {
                if (d.hotm > 0) {
                    reqs.add(stat("hotm", d.hotm));
                }
                if (d.powderMio > 0) {
                    reqs.add(stat("powder", d.powderMio * 1_000_000));
                }
                if (d.nucleusRuns > 0) {
                    reqs.add(stat("nucleus_runs", d.nucleusRuns));
                }
                if (d.mineshafts > 0) {
                    reqs.add(manual("Mineshafts " + d.mineshafts + "+"));
                }
            }
            case "combat" -> {
                if (!d.mob.isBlank()) {
                    spec.addProperty("mob", d.mob.trim());
                }
                if (d.bestiaryMilestone > 0) {
                    reqs.add(stat("bestiary_milestone", d.bestiaryMilestone));
                }
                if (d.eyesPlaced > 0) {
                    reqs.add(stat("eyes_placed", d.eyesPlaced));
                }
            }
            case "dungeons" -> {
                // Dungeons carry only the common requirements (SB Level, MP) - nothing extra.
            }
            case "kuudra" -> {
                spec.addProperty("location", PartyTypes.KUUDRA_TIERS[d.kuudraTierIdx]);
                if (d.kuudraCompletions > 0) {
                    reqs.add(stat("kuudra_" + PartyTypes.KUUDRA_TIER_KEYS[d.kuudraTierIdx], d.kuudraCompletions));
                }
            }
            case "kuudra_pieces" -> {
                // Armor-piece hunt: the wanted pieces + optional Cataclysmic Lobby are descriptive
                // filters (searchable via the finder's free-text search), not profile-checked reqs.
                java.util.List<String> pieces = new java.util.ArrayList<>();
                if (d.pieceHelmet) {
                    pieces.add("Helmet");
                }
                if (d.pieceChest) {
                    pieces.add("Chestplate");
                }
                if (d.pieceLegs) {
                    pieces.add("Leggings");
                }
                if (d.pieceBoots) {
                    pieces.add("Boots");
                }
                spec.addProperty("location", pieces.isEmpty() ? "Any Piece" : String.join(", ", pieces));
                if (d.cataclysmicLobby) {
                    spec.addProperty("mode", "Cataclysmic Lobby");
                }
            }
            case "diana" -> {
                if (d.griffinIdx > 0) {
                    reqs.add(stat("pet_griffin", d.griffinIdx == 1 ? 4 : 5));
                }
                if (d.lootingV) {
                    reqs.add(stat("looting", 5));
                }
                if (d.fleece) {
                    reqs.add(manual("Fleece"));
                }
                if (d.bloodshotBelt) {
                    reqs.add(manual("Bloodshot Belt"));
                }
                if (d.dianaMobKills > 0) {
                    // Profil-weiter Mythological-Kills-Zaehler - exakt der Wert,
                    // mit dem das Beastmaster Crest skaliert.
                    reqs.add(stat("mythos_kills", d.dianaMobKills));
                }
                spec.addProperty("mode", PartyTypes.MODES[d.modeIdx]);
            }
            default -> {
            }
        }
        spec.add("requirements", reqs);
        return spec;
    }

    private static JsonObject stat(String key, int min) {
        JsonObject req = new JsonObject();
        req.addProperty("key", key);
        req.addProperty("min", min);
        return req;
    }

    private static JsonObject owns(String key) {
        JsonObject req = new JsonObject();
        req.addProperty("key", key);
        return req;
    }

    private static JsonObject manual(String label) {
        JsonObject req = new JsonObject();
        req.addProperty("key", "manual:" + label);
        return req;
    }

    private void onCreate() {
        if (!PartyFinderApi.ready()) {
            status = "Need a world + licence token.";
            return;
        }
        status = "Creating...";
        PartyFinderApi.getInstance().create(buildSpec(), (result, error) -> {
            if (error != null) {
                status = "Create failed: " + error;
                return;
            }
            String id = result.getAsJsonObject("party").get("id").getAsString();
            PartyFinderManager.getInstance().enter(id);
            Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().setScreenAndShow(new PartyFinderScreen()));
        });
    }

    private void onBack() {
        Minecraft.getInstance().setScreenAndShow(new PartyFinderScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = PartyCreateScreen.this.font;
            g.fill(0, 0, PartyCreateScreen.this.width, PartyCreateScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Create Party"), panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);
            if (!status.isEmpty()) {
                g.centeredText(font, Component.literal(status), panelX + panelW / 2,
                        rowsBottom + 2, SBSTheme.TEXT_MUTED);
            }
            int rowStep = SBSTheme.ENTRY_HEIGHT + SBSTheme.ENTRY_SPACING;
            int maxVisible = Math.max(1, (rowsBottom - rowsTop + SBSTheme.ENTRY_SPACING) / rowStep);
            if (totalRows > maxVisible) {
                g.text(font, Component.literal((scroll + 1) + "-" + Math.min(totalRows, scroll + maxVisible)
                        + "/" + totalRows), panelX + panelW - pad - 30, titleY, SBSTheme.TEXT_MUTED);
            }
        }
    }
}
