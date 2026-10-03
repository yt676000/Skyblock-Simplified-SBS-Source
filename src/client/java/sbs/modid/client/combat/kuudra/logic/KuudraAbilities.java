/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Team abilities: telling the party what you just cast, and drawing what they cast.
 *
 * <p><b>A Kuudra team's abilities are area effects nobody else can see.</b> A pool on the floor or a
 * buff cast at a spot is worth standing in, and there is no marker for it - the caster knows where it
 * went and nobody else does. So a cast is announced with its coordinates, and an announcement that
 * comes back the other way is drawn on the floor where it landed.
 *
 * <p><b>The pool marker only ever trusts numbers.</b> The incoming line is other players' text; what
 * is taken out of it is three integers and a spell name matched against a fixed list, and anything
 * that is not exactly that shape is ignored. A position far outside the arena is dropped as well,
 * because the only thing a bad coordinate can do is drag a circle across the screen.
 *
 * <p>The mana readout is the other half of the same idea: an area buff is worth its mana only if it
 * caught people, and how many it caught is something the caster can count and nobody else can.
 */
public final class KuudraAbilities {

    private static final KuudraAbilities INSTANCE = new KuudraAbilities();

    /** The spells worth a call-out. Matched exactly - an unknown spell is simply not announced. */
    private static final List<String> SPELLS =
            List.of("Spirit Spark", "Hollowed Rush", "Raging Wind", "Ichor Pool");

    /** Hypixel's own cast line. */
    private static final Pattern CAST = Pattern.compile("Casting Spell:\\s*(.+?)!");

    /** Hypixel's mana readout for the team-wide focus ability. */
    private static final Pattern MANA = Pattern.compile("Used Extreme Focus!\\s*\\((\\d+) Mana\\)");

    /** Our own announcement, coming back from a teammate: "Ichor Pool @ -100,78,-108". */
    private static final Pattern ANNOUNCE = Pattern.compile(
            "^Party\\s*>\\s*(?:\\[[^]]*]\\s*)?(\\w{2,16})\\s*:\\s*(.+?)\\s*@\\s*"
                    + "(-?\\d{1,4}),(-?\\d{1,4}),(-?\\d{1,4})");

    /** How long a pool marker stays on the floor. Roughly how long the pool itself lasts. */
    private static final long POOL_MS = 20_000L;

    /** Radius of the drawn pool ring, in blocks. */
    public static final double POOL_RADIUS = 8.0;

    /** How far from the arena a reported position may be before it is thrown away. */
    private static final double SANE_RANGE = 400.0;

    /** How close a teammate has to be to have been caught by the focus buff. */
    private static final double FOCUS_RADIUS = 5.0;

    /** One drawn pool: where it is and when it stops being drawn. */
    public record Pool(Vec3 center, long until) {
    }

    private final List<Pool> pools = new CopyOnWriteArrayList<>();

    private KuudraAbilities() {
    }

    public static KuudraAbilities getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.KuudraSettings cfg() {
        return ConfigManager.getInstance().get().kuudra;
    }

    /** The pools still worth drawing. Expired ones are dropped as a side effect. */
    public List<Pool> pools() {
        if (!pools.isEmpty()) {
            long now = System.currentTimeMillis();
            pools.removeIf(pool -> pool.until() <= now);
        }
        return pools;
    }

    public void clear() {
        pools.clear();
    }

    /** One colour-stripped chat line, from the module's chat hook. */
    public void onChat(String text) {
        SBSConfig.KuudraSettings cfg = cfg();
        if (!cfg.enabled || !KuudraTracker.getInstance().running()) {
            return;
        }
        Matcher cast = CAST.matcher(text);
        if (cast.find()) {
            onCast(cfg, cast.group(1).trim());
            return;
        }
        Matcher mana = MANA.matcher(text);
        if (mana.find()) {
            onManaDrain(cfg, mana.group(1));
            return;
        }
        Matcher announce = ANNOUNCE.matcher(text);
        if (announce.find()) {
            onTeammateCast(cfg, announce);
        }
    }

    /** We cast something: mark it locally, and tell the party where it went. */
    private void onCast(SBSConfig.KuudraSettings cfg, String spell) {
        String known = known(spell);
        if (known == null) {
            KuudraTracker.getInstance().log("unlisted spell cast: {}", spell);
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Vec3 at = player.position();
        if (known.equals("Ichor Pool")) {
            addPool(at);
        }
        if (cfg.abilityAnnounce) {
            KuudraSay.party(String.format(Locale.US, "%s @ %d,%d,%d",
                    known, (int) at.x, (int) at.y, (int) at.z));
        }
    }

    /** A teammate's announcement. Only Ichor Pool leaves anything on screen. */
    private void onTeammateCast(SBSConfig.KuudraSettings cfg, Matcher announce) {
        if (!cfg.ichorPoolMarkers) {
            return;
        }
        String spell = known(announce.group(2));
        if (spell == null || !spell.equals("Ichor Pool")) {
            return;
        }
        Vec3 at = new Vec3(Integer.parseInt(announce.group(3)),
                Integer.parseInt(announce.group(4)),
                Integer.parseInt(announce.group(5)));
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.position().distanceTo(at) > SANE_RANGE) {
            return;
        }
        addPool(at);
        KuudraTracker.getInstance().log("{} pooled at {},{},{}",
                announce.group(1), (int) at.x, (int) at.y, (int) at.z);
    }

    private void addPool(Vec3 at) {
        pools.add(new Pool(at, System.currentTimeMillis() + POOL_MS));
    }

    /**
     * How many teammates the focus buff actually caught.
     *
     * <p>Counted off the tab list rather than off the entity list: Kuudra's Hollow has NPC-shaped
     * player entities standing around in it, and a count that includes them turns a two-player hit
     * into a five-player brag.
     */
    private void onManaDrain(SBSConfig.KuudraSettings cfg, String mana) {
        if (!cfg.manaDrainAnnounce) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer self = minecraft.player;
        if (self == null || minecraft.level == null || minecraft.getConnection() == null) {
            return;
        }
        List<AbstractClientPlayer> caught = new ArrayList<>();
        for (AbstractClientPlayer other : minecraft.level.players()) {
            if (other == self || other.distanceTo(self) > FOCUS_RADIUS) {
                continue;
            }
            if (sbs.modid.client.core.player.RealPlayers.isRealPlayer(other)) {
                caught.add(other);
            }
        }
        KuudraSay.party("Extreme Focus: " + mana + " mana on " + caught.size()
                + (caught.size() == 1 ? " player" : " players"));
    }

    /** The listed spell whose name this is, or {@code null}. */
    private static String known(String spell) {
        for (String candidate : SPELLS) {
            if (candidate.equalsIgnoreCase(spell.trim())) {
                return candidate;
            }
        }
        return null;
    }
}
