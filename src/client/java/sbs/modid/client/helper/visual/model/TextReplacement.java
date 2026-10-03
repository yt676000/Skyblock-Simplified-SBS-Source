/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.model;

/**
 * One Text Editor rule: every occurrence of {@code from} is replaced by {@code to} wherever text
 * is displayed (item names, tooltips, chat). Plain Gson POJO for the config.
 */
public final class TextReplacement {

    public String from = "";
    public String to = "";
    public boolean enabled = true;

    public TextReplacement() {
    }

    public TextReplacement(String from, String to) {
        this.from = from;
        this.to = to;
    }
}
