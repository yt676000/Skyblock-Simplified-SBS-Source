/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Locale;
import java.util.Optional;

/**
 * A server {@code Component} as the two strings a capture needs: what it says, and how it was
 * coloured.
 *
 * <p>Both, because a chat parser matches on the first and a nametag reader often keys on the second:
 * the colour of a mob's name, or a private-use glyph in one colour, is information the plain text
 * throws away. Converted on the client thread at the hook, so the component itself never leaves it.
 */
public final class DevLogText {

    /** Longest string kept from one value, so one oversized component cannot bloat a line. */
    public static final int MAX_LENGTH = 2048;

    private DevLogText() {
    }

    /** {@code getString()}, capped, or {@code null} for no component. */
    public static String plain(Component component) {
        return component == null ? null : cap(component.getString());
    }

    /**
     * The component in the legacy {@code §} form. Every segment is written as {@code §r}, its colour
     * and its formats, then its text - so each segment stands alone and nothing depends on inheritance
     * having been worked out right. A colour with no legacy code is written {@code §#rrggbb}, which no
     * game reads but which keeps the colour in the capture. Section signs already in the text pass
     * through as sent.
     */
    public static String legacy(Component component) {
        if (component == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(64);
        component.visit((Style style, String text) -> {
            if (!text.isEmpty()) {
                out.append(codes(style)).append(text);
            }
            return out.length() > MAX_LENGTH ? Optional.of(Boolean.TRUE) : Optional.empty();
        }, Style.EMPTY);
        return cap(out.toString());
    }

    private static String codes(Style style) {
        StringBuilder out = new StringBuilder(8).append("§r");
        TextColor color = style.getColor();
        if (color != null) {
            String code = legacyCode(color);
            out.append(code != null ? code
                    : String.format(Locale.ROOT, "§#%06x", color.getValue() & 0xFFFFFF));
        }
        if (style.isObfuscated()) {
            out.append("§k");
        }
        if (style.isBold()) {
            out.append("§l");
        }
        if (style.isStrikethrough()) {
            out.append("§m");
        }
        if (style.isUnderlined()) {
            out.append("§n");
        }
        if (style.isItalic()) {
            out.append("§o");
        }
        return out.toString();
    }

    /** The {@code §x} code for a colour that has one, else {@code null}. */
    private static String legacyCode(TextColor color) {
        for (char code : "0123456789abcdef".toCharArray()) {
            ChatFormatting format = ChatFormatting.getByCode(code);
            if (format != null && color.equals(TextColor.fromLegacyFormat(format))) {
                return "§" + code;
            }
        }
        return null;
    }

    /** {@code value}, cut to {@link #MAX_LENGTH} characters with a marker saying it was cut. */
    public static String cap(String value) {
        if (value == null || value.length() <= MAX_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LENGTH) + "…[" + (value.length() - MAX_LENGTH) + " more]";
    }
}
