/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The searchable multi-select behind "Limit To Entity Types": every living entity type the game
 * knows, ticked or not, deciding which of them the Animation &amp; Scaling module's entity scale
 * reaches.
 *
 * <p><b>An empty selection means every type.</b> That is not a quirk - it is what keeps the older
 * "Scale All Entities" switch behaving exactly as it always did for anyone who never opens this
 * screen, so the picker is purely additive. The footer says so out loud, because an empty list that
 * silently means "all" is otherwise the kind of thing you only discover by being surprised.
 *
 * <p>The list is built from the entity registry rather than a curated catalogue: the scale is
 * applied in {@code LivingEntityRenderer}, which every living entity in the game renders through,
 * so anything listed here can genuinely be scaled. Non-living entities (dropped items, item frames)
 * are deliberately absent - they do not pass through that renderer and ticking one would be a
 * promise this cannot keep. Dropped items have their own separate setting.
 *
 * <p>Modelled on {@code MobSelectionScreen}: search box on top, scrollable checkbox list, bulk
 * actions, footer status.
 */
public final class EntityTypeSelectionScreen extends Screen {

    private static final int ROW_H = 18;

    private final Screen parent;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentW;
    private int listTop;
    private int listBottom;

    private EditBox search;
    private List<EntityType<?>> results = new ArrayList<>();
    private int scroll;
    private int scrollMax;

    /** The list's scrollbar - the shared one, so it can actually be dragged. */
    private final SciFiScrollbar bar = new SciFiScrollbar();

    /** @param parent the screen to return to on close, so the settings page is restored as left. */
    public EntityTypeSelectionScreen(Screen parent) {
        super(Component.literal("Select Entity Types"));
        this.parent = parent;
    }

    private static SBSConfig.AnimationScalingSettings cfg() {
        return ConfigManager.getInstance().get().animationScaling;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    /** Registry id of a type ({@code "minecraft:villager"}) - what the config stores. */
    public static String idOf(EntityType<?> type) {
        return EntityType.getKey(type).toString();
    }

    /** Every living entity type, alphabetical by display name. */
    private static List<EntityType<?>> allLiving() {
        List<EntityType<?>> types = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            // MISC is the non-living bucket (items, projectiles, vehicles): those never reach
            // LivingEntityRenderer, so listing them would offer a scale that silently does nothing.
            if (type.getCategory() != MobCategory.MISC) {
                types.add(type);
            }
        }
        types.sort((a, b) -> a.getDescription().getString()
                .compareToIgnoreCase(b.getDescription().getString()));
        return types;
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 380, 560);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 260, 460);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentW = panelW - pad * 2;

        int topY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        int btnW = 70;
        int textH = this.font.lineHeight;

        listTop = topY + SBSTheme.SEARCH_HEIGHT + 6;
        listBottom = panelY + panelH - pad - font.lineHeight - 4;

        // Panel first so the widgets added after it paint on top of its fills.
        addRenderableOnly(new PanelRenderable());

        search = new EditBox(this.font, innerX + 6, topY + (SBSTheme.SEARCH_HEIGHT - textH) / 2,
                contentW - btnW * 2 - 24, textH, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(48);
        search.setTextColor(SBSTheme.TEXT);
        search.setHint(Component.literal("Search entity types..."));
        search.setResponder(query -> refresh());
        addRenderableWidget(search);
        setInitialFocus(search);

        addRenderableWidget(new SciFiButton(innerX + contentW - btnW * 2 - 6, topY, btnW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Select All"), this::selectAllShown));
        addRenderableWidget(new SciFiButton(innerX + contentW - btnW, topY, btnW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Clear"), this::clearAll));

        refresh();
    }

    private void refresh() {
        String needle = (search == null ? "" : search.getValue().trim()).toLowerCase(Locale.ROOT);
        List<EntityType<?>> list = new ArrayList<>();
        for (EntityType<?> type : allLiving()) {
            if (needle.isEmpty()
                    || type.getDescription().getString().toLowerCase(Locale.ROOT).contains(needle)
                    || idOf(type).toLowerCase(Locale.ROOT).contains(needle)) {
                list.add(type);
            }
        }
        results = list;
        scroll = 0;
    }

    private static boolean isSelected(EntityType<?> type) {
        return cfg().scaleEntityTypes.contains(idOf(type));
    }

    private void toggle(EntityType<?> type) {
        var selected = cfg().scaleEntityTypes;
        String id = idOf(type);
        if (!selected.remove(id)) {
            selected.add(id);
        }
        save();
    }

    private void selectAllShown() {
        for (EntityType<?> type : results) {
            cfg().scaleEntityTypes.add(idOf(type));
        }
        save();
    }

    /** Back to "every type" - the empty selection is the all-types state, not a broken one. */
    private void clearAll() {
        cfg().scaleEntityTypes.clear();
        save();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        // The bar before the rows: a click on it must not also tick the entry behind it.
        if (bar.handleClick(event.x(), event.y(), scroll, value -> scroll = value)) {
            return true;
        }
        if (event.x() < innerX || event.x() > innerX + contentW
                || event.y() < listTop || event.y() >= listBottom) {
            return false;
        }
        int row = scroll + (int) ((event.y() - listTop) / ROW_H);
        if (row < 0 || row >= results.size()) {
            return false;
        }
        toggle(results.get(row));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll = clamp(scroll - (int) Math.signum(scrollY), 0, scrollMax);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

    /** Feeds the shared scrollbar the list's track box, from what the render pass measured. */
    private void syncBar(int total, int visible) {
        bar.set(innerX + contentW - SciFiScrollbar.WIDTH, listTop, visible * ROW_H, total, visible);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
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
            var font = EntityTypeSelectionScreen.this.font;
            g.fill(0, 0, EntityTypeSelectionScreen.this.width, EntityTypeSelectionScreen.this.height,
                    SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Scale Only These Entity Types"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            int topY = dividerY + SBSTheme.GAP_AFTER_HEADER;
            SciFiRender.roundedRectWithBorder(g, innerX, topY, contentW - 70 * 2 - 12, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    search.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            drawList(g, mouseX, mouseY);
            drawFooter(g);
        }

        private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = EntityTypeSelectionScreen.this.font;
            if (results.isEmpty()) {
                g.centeredText(font, Component.literal("§7No matching entity types."),
                        panelX + panelW / 2, listTop + 12, SBSTheme.TEXT_MUTED);
                scrollMax = 0;
                return;
            }
            int visible = Math.max(1, (listBottom - listTop) / ROW_H);
            scrollMax = Math.max(0, results.size() - visible);
            scroll = clamp(scroll, 0, scrollMax);

            boolean scrollable = results.size() > visible;
            int rowW = contentW - (scrollable ? SciFiScrollbar.WIDTH + 3 : 0);
            int y = listTop;
            for (int i = scroll; i < results.size() && i < scroll + visible; i++) {
                drawRow(g, results.get(i), innerX, y, rowW, mouseX, mouseY);
                y += ROW_H;
            }
            syncBar(results.size(), visible);
            bar.render(g, scroll, mouseX, mouseY);
        }

        /** One row: checkbox, the type's display name, and its registry id muted on the right. */
        private void drawRow(GuiGraphicsExtractor g, EntityType<?> type, int x, int y, int w,
                             int mouseX, int mouseY) {
            var font = EntityTypeSelectionScreen.this.font;
            int h = ROW_H - 2;
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
            SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                    hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);

            int textY = y + (h - font.lineHeight) / 2;
            boolean selected = isSelected(type);

            int boxSize = 9;
            int boxX = x + 4;
            int boxY = y + (h - boxSize) / 2;
            SciFiRender.roundedRectWithBorder(g, boxX, boxY, boxSize, boxSize, 2,
                    selected ? SBSTheme.ACCENT : SBSTheme.CARD_BG,
                    selected ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            if (selected) {
                g.text(font, Component.literal("§f✔"), boxX + 1, boxY - 1, SBSTheme.TEXT);
            }

            String name = type.getDescription().getString();
            int nameX = boxX + boxSize + 5;
            g.text(font, Component.literal((selected ? "§f" : "§7") + name), nameX, textY, SBSTheme.TEXT);

            String id = "§8" + idOf(type);
            int idW = font.width(id);
            if (nameX + font.width(name) + 8 + idW < x + w - 4) {
                g.text(font, Component.literal(id), x + w - 4 - idW, textY, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawFooter(GuiGraphicsExtractor g) {
            var font = EntityTypeSelectionScreen.this.font;
            int y = listBottom + 2;
            int count = cfg().scaleEntityTypes.size();
            String status = count == 0
                    ? "§7Nothing ticked §8· every entity type is scaled"
                    : "§7" + count + " type(s) §8· only these are scaled";
            g.text(font, Component.literal(status), innerX, y, SBSTheme.TEXT_MUTED);
            String hint = "§8click a row to toggle · Esc to go back";
            g.text(font, Component.literal(hint), innerX + contentW - font.width(hint), y, SBSTheme.TEXT_MUTED);
        }
    }
}
