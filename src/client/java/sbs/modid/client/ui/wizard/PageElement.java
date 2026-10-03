/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard;

import net.minecraft.resources.Identifier;

/**
 * What a page is made of. Deliberately a small, closed set.
 *
 * <p>{@link Setting} is the only interactive element, and it names an existing config row rather
 * than describing a control - so the wizard cannot grow a second settings widget system that has to
 * be kept themed, keyboard-reachable and scale-correct in parallel with the real one.
 */
public sealed interface PageElement {

    /** A sub-heading inside the page. */
    record Heading(String text) implements PageElement {
    }

    /** A paragraph. Wrapped to the content width at render time. */
    record Text(String body) implements PageElement {
    }

    /** A bundled texture, drawn at the given size and scaled down to fit a narrow viewport. */
    record Image(Identifier texture, int width, int height) implements PageElement {
    }

    /**
     * A live config control, addressed as {@code moduleId:rowId} - the same id the favourites list
     * and the config search resolve through, so a row that moves keeps working and a row that is
     * deleted is detected rather than drawn broken.
     */
    record Setting(String optionId) implements PageElement {
    }

    /**
     * The grid of clickable UI-style tiles, each drawn in the style it offers.
     *
     * <p>Its own element rather than a {@link Setting} pointing at the UI Style row, because the
     * question "which of these do you want" is answered by looking at them, and a dropdown of eight
     * names shows nothing. The row still exists in the config for changing it later; this is the
     * first-run version of the same choice.
     */
    record StylePicker() implements PageElement {
    }
}
