/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */
package sbs.modid.client.helper.loadouts;

/**
 * Whether a worn armour piece contradicts the piece a loadout has stored, on extracted facts (uuid,
 * dye item, look) so the rules are tested without item stacks.
 *
 * <ul>
 *   <li>A missing piece on either side, or a worn piece without its SkyBlock uuid, is
 *       <b>no evidence</b> - not a mismatch. Hypixel sends some worn pieces without their
 *       custom data for a moment; treating that as "different" was one source of the flicker.</li>
 *   <li>Both uuids known: they decide alone.</li>
 *   <li>Only the worn one known: the look decides.</li>
 * </ul>
 */
final class PieceCompare {

    enum Verdict { SAME, NO_EVIDENCE, UUID, LOOK }

    private PieceCompare() {
    }

    static Verdict compare(boolean wornEmpty, boolean storedEmpty, String wornUuid, String storedUuid,
                           boolean sameLook) {
        if (wornEmpty || storedEmpty || wornUuid == null || wornUuid.isEmpty()) {
            return Verdict.NO_EVIDENCE;
        }
        if (storedUuid != null && !storedUuid.isEmpty()) {
            return wornUuid.equals(storedUuid) ? Verdict.SAME : Verdict.UUID;
        }
        return sameLook ? Verdict.SAME : Verdict.LOOK;
    }

    /**
     * Whether two pieces' dye counts as the same look. An animated dye (custom_data
     * {@code dye_item}, e.g. {@code DYE_BLACK_ICE}) rewrites {@code dyed_color} about once a second
     * - comparing that colour called the same boots new every second and rewrote the whole cache
     * each time. With a dye item on either side the dye items are compared instead; without one
     * the colour is the dye.
     */
    static boolean sameDye(String wornDyeItem, String storedDyeItem, Integer wornColor, Integer storedColor) {
        boolean wornAnimated = wornDyeItem != null && !wornDyeItem.isEmpty();
        boolean storedAnimated = storedDyeItem != null && !storedDyeItem.isEmpty();
        if (wornAnimated || storedAnimated) {
            return wornAnimated && storedAnimated && wornDyeItem.equals(storedDyeItem);
        }
        return java.util.Objects.equals(wornColor, storedColor);
    }
}
