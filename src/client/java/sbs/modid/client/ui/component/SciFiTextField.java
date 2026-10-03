/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.component;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A themed single-line text input for the module settings rows: a borderless {@link EditBox} that
 * paints its own SBS card (rounded dark fill, border brightening on focus) behind the text, so it
 * drops into a settings row as one widget – same look as the input fields of the Text Editor /
 * Command Keybinds screens, which draw that background separately in their panels.
 *
 * <p>Create it through {@link #forRow}: the widget's interactive bounds are the inset text area,
 * while the card is drawn over the full row rectangle kept alongside. {@link #forIntRow} builds
 * the numeric variant instead – label on the left, a small digits-only input on the right with a
 * unit suffix ("%") behind it, clamped to a min/max range.
 */
public final class SciFiTextField extends EditBox {

    private final int rowX;
    private final int rowY;
    private final int rowW;
    private final int rowH;

    /** Label + unit of the numeric row variant ({@code null} for plain full-width text rows). */
    private String rowLabel;
    private String unit;

    /** Run when the field loses focus (numeric variant: re-normalise the shown text to the clamped value). */
    private Runnable onDefocus;

    @Override
    public void setFocused(boolean focused) {
        boolean wasFocused = isFocused();
        super.setFocused(focused);
        if (wasFocused && !focused && onDefocus != null) {
            onDefocus.run();
        }
    }

    private SciFiTextField(Font font, int textX, int textY, int textW, int textH,
                           int rowX, int rowY, int rowW, int rowH, Component label) {
        super(font, textX, textY, textW, textH, label);
        this.rowX = rowX;
        this.rowY = rowY;
        this.rowW = rowW;
        this.rowH = rowH;
        setBordered(false);
        setTextColor(SBSTheme.TEXT);
    }

    /**
     * Builds a settings-row text field over the given row bounds, wired to a config value: the
     * current value is loaded on creation and every edit is pushed through {@code setter}
     * (the caller persists it there).
     */
    public static SciFiTextField forRow(int x, int y, int w, int h, String label, String hint,
                                        int maxLength, Supplier<String> getter, Consumer<String> setter) {
        Font font = Minecraft.getInstance().font;
        int textH = font.lineHeight;
        SciFiTextField field = new SciFiTextField(font,
                x + 6, y + (h - textH) / 2, w - 12, textH,
                x, y, w, h, Component.literal(label));
        field.setMaxLength(maxLength);
        field.setHint(Component.literal(hint));
        field.setValue(getter.get());
        field.setResponder(setter);
        return field;
    }

    /**
     * A {@link #forRow} field whose text is <b>masked</b> (rendered as {@code ****}) while
     * {@code reveal} is false – a password field. The EditBox always holds the REAL value, so
     * pasting / editing / persisting work unchanged; only the drawn glyphs are hidden. The formatter
     * reads {@code reveal} live on every render, so a "show" toggle un-masks it instantly without a
     * rebuild.
     */
    public static SciFiTextField forMaskedRow(int x, int y, int w, int h, String label, String hint,
                                              int maxLength, Supplier<String> getter, Consumer<String> setter,
                                              java.util.function.BooleanSupplier reveal) {
        SciFiTextField field = forRow(x, y, w, h, label, hint, maxLength, getter, setter);
        field.addFormatter((text, firstCharIndex) -> reveal.getAsBoolean()
                ? net.minecraft.util.FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY)
                : net.minecraft.util.FormattedCharSequence.forward(
                        "*".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
        return field;
    }

    /**
     * Builds a numeric settings row: the label on the left, a digits-only input on the right with
     * the unit ("%") rendered behind it. Every edit is parsed, clamped to {@code [min, max]} and
     * pushed through {@code setter}; typed values above the maximum snap back to the maximum.
     */
    public static SciFiTextField forIntRow(int x, int y, int w, int h, String label, int min, int max,
                                           IntSupplier getter, IntConsumer setter, String unit) {
        Font font = Minecraft.getInstance().font;
        int textH = font.lineHeight;
        int digits = String.valueOf(max).length();
        int fieldW = font.width("0".repeat(digits)) + 8;
        // Right edge inwards: margin, unit, the gap that keeps the unit off the box border, the box.
        int textX = x + w - 6 - font.width(unit) - 5 - fieldW;
        SciFiTextField field = new SciFiTextField(font,
                textX, y + (h - textH) / 2, fieldW, textH,
                x, y, w, h, Component.literal(label));
        field.rowLabel = label;
        field.unit = unit;
        field.setMaxLength(digits);
        field.setValue(String.valueOf(clamp(getter.getAsInt(), min, max)));
        field.setResponder(text -> {
            String cleaned = text.replaceAll("[^0-9]", "");
            if (!cleaned.equals(text)) {
                field.setValue(cleaned); // re-fires this responder with digits only
                return;
            }
            if (cleaned.isEmpty()) {
                setter.accept(min); // cleared while typing – re-normalized on the next edit / blur
                return;
            }
            int parsed = (int) Math.min(Integer.MAX_VALUE, Long.parseLong(cleaned));
            // Only the MAX is enforced live. The MIN must NOT be clamped mid-typing: snapping the
            // first digit up to the minimum corrupted every further keystroke (min 5: "40" -> the
            // "4" became "5", then "0" appended -> "50"; any value below the min came out as a "5").
            // The stored value is clamped both ways, but the shown text is left as typed and only
            // re-normalised to the clamped value on blur.
            if (parsed > max) {
                field.setValue(String.valueOf(max)); // re-fires; max always has the full digit width
                return;
            }
            setter.accept(clamp(parsed, min, max));
        });
        field.onDefocus = () -> field.setValue(String.valueOf(clamp(getter.getAsInt(), min, max)));
        return field;
    }

    /**
     * Builds a labelled free-text row: the label on the left and a short input on the right, laid
     * out like {@link #forIntRow} but with no digits-only rule and no range.
     *
     * <p>{@link #forRow} is the wrong shape once a page carries several of these one after another –
     * it draws no label at all, so a run of them is a stack of identical boxes whose only clue is a
     * hint that disappears the moment anything is typed. Here the label is always visible.
     *
     * <p>The setter sees every keystroke, so it must tolerate half-typed text; on blur the field is
     * re-read from the getter, which is what puts a rejected edit back to the stored value.
     *
     * @param columns how many characters wide the input should be
     */
    public static SciFiTextField forValueRow(int x, int y, int w, int h, String label, String hint,
                                             int maxLength, int columns,
                                             Supplier<String> getter, Consumer<String> setter) {
        Font font = Minecraft.getInstance().font;
        int textH = font.lineHeight;
        int fieldW = font.width("0".repeat(Math.max(1, columns))) + 8;
        int textX = x + w - 9 - fieldW;
        SciFiTextField field = new SciFiTextField(font,
                textX, y + (h - textH) / 2, fieldW, textH,
                x, y, w, h, Component.literal(label));
        field.rowLabel = label;
        field.unit = "";
        field.setMaxLength(maxLength);
        field.setHint(Component.literal(hint));
        field.setValue(getter.get());
        field.setResponder(setter);
        field.onDefocus = () -> field.setValue(getter.get());
        return field;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int border = isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
        SciFiRender.roundedRectWithBorder(g, rowX, rowY, rowW, rowH,
                SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL, border);
        if (rowLabel != null) {
            Font font = Minecraft.getInstance().font;
            int textY = rowY + (rowH - font.lineHeight) / 2 + 1;
            // Cut at the input box rather than running underneath it - the number and its unit are
            // anchored to the right edge, so on a narrow row a full-length label collides with them.
            int labelX = rowX + 6;
            g.text(font, Component.literal(RowText.fit(font, rowLabel, getX() - 3 - labelX - 4)),
                    labelX, textY, SBSTheme.TEXT);
            g.text(font, Component.literal(unit), getX() + getWidth() + 5, textY, SBSTheme.TEXT_MUTED);
            // A subtle inner card marks the editable number area.
            SciFiRender.roundedRectWithBorder(g, getX() - 3, rowY + 2, getWidth() + 6, rowH - 4,
                    SBSTheme.CORNER_RADIUS, SBSTheme.CARD_BG,
                    isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        }
        super.extractWidgetRenderState(g, mouseX, mouseY, partialTick);
    }
}
