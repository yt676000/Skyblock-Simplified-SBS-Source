/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import sbs.modid.client.core.build.io.SchematicStore;
import sbs.modid.client.core.build.logic.BuildLibrary;
import sbs.modid.client.core.build.logic.Thumbnails;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * One saved build as a card - thumbnail, name (★ for a favourite), size and block count, date and
 * folder - shared by Quick Paste and the Build Library so both show a build the same way.
 *
 * <p>A button, so it is focusable and Enter / Space press it like a click. The host decides what a
 * press does, whether the card is drawn as selected, and when hover is suppressed (an open popup).
 */
public final class BuildCard extends AbstractButton {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final SchematicStore.Entry entry;
    private final int pictureSize;
    private final int textLines;
    private final Runnable onPress;
    private final BooleanSupplier selected;
    private final BooleanSupplier hoverSuppressed;
    private final Set<String> thumbnailsRequested;

    public BuildCard(int x, int y, int width, int height, SchematicStore.Entry entry, int pictureSize, int textLines,
                     Runnable onPress, BooleanSupplier selected, BooleanSupplier hoverSuppressed,
                     Set<String> thumbnailsRequested) {
        super(x, y, width, height, Component.literal(entry.displayName()));
        this.entry = entry;
        this.pictureSize = pictureSize;
        this.textLines = textLines;
        this.onPress = onPress;
        this.selected = selected;
        this.hoverSuppressed = hoverSuppressed;
        this.thumbnailsRequested = thumbnailsRequested;
    }

    public SchematicStore.Entry entry() {
        return entry;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (!hoverSuppressed.getAsBoolean()) {
            onPress.run();
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        boolean hovered = isHoveredOrFocused() && !hoverSuppressed.getAsBoolean();
        boolean chosen = selected.getAsBoolean();
        int x = getX();
        int y = getY();
        SciFiRender.roundedRectWithBorder(g, x, y, getWidth(), getHeight(), SBSTheme.CORNER_RADIUS,
                hovered || chosen ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                chosen ? SBSTheme.ACCENT : hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

        int pictureX = x + (getWidth() - pictureSize) / 2;
        int pictureY = y + 3;
        Identifier texture = Thumbnails.texture(entry.slug(), entry.file().resolveSibling(entry.slug()
                + SchematicStore.THUMBNAIL_EXTENSION));
        if (texture != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, texture, pictureX, pictureY, 0F, 0F, pictureSize, pictureSize,
                    128, 128, 128, 128, 0xFFFFFFFF);
        } else {
            g.fill(pictureX, pictureY, pictureX + pictureSize, pictureY + pictureSize, 0x30FFFFFF);
            if (thumbnailsRequested.add(entry.slug())) {
                BuildLibrary.ensureThumbnailAsync(entry.slug(), () -> Thumbnails.forget(entry.slug()));
            }
        }
        SchematicHeader header = entry.summary().header();
        int textW = getWidth() - 6;
        int ty = pictureY + pictureSize + 2;
        int lineH = font.lineHeight + 1;
        String name = (header.favourite() ? "★ " : "") + entry.displayName();
        g.text(font, Component.literal(RowText.fit(font, name, textW)), x + 3, ty, SBSTheme.ACCENT_BRIGHT);
        if (textLines >= 2) {
            String size = entry.summary().sizeLabel() + String.format(Locale.ROOT, " • %,d", entry.summary().blocks());
            g.text(font, Component.literal(RowText.fit(font, size, textW)), x + 3, ty + lineH, SBSTheme.TEXT);
        }
        if (textLines >= 3) {
            String date = DATE.format(Instant.ofEpochMilli(header.createdAt()))
                    + (header.folder().isEmpty() ? "" : " • " + header.folder());
            g.text(font, Component.literal(RowText.fit(font, date, textW)), x + 3, ty + 2 * lineH, SBSTheme.TEXT_MUTED);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
