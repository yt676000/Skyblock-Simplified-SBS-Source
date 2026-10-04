/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

import net.minecraft.core.BlockPos;

/**
 * A named world position the pathfinder can route to.
 *
 * <p>A plain mutable POJO rather than a record because Gson (de)serialises it straight out of the
 * config, exactly like {@code CommandKeybind} and the other persisted settings objects.
 *
 * <p>The dimension is stored alongside the coordinates so a waypoint dropped in the Dwarven Mines is
 * not rendered — or routed to — while standing on the Hub.
 */
public final class Waypoint {

    /** Shown in the world and in the list. */
    public String name = "Waypoint";

    public int x;
    public int y;
    public int z;

    /** The dimension key this waypoint belongs to (e.g. {@code minecraft:overworld}). */
    public String dimension = "";

    /**
     * Who owns this waypoint: {@link #SOURCE_QUEST} for one the Quest Guide placed, {@code null} for
     * one the player dropped themselves.
     *
     * <p>A tag rather than a name prefix, so routing can prefer a quest objective over a personal
     * marker without parsing labels – and so renaming a waypoint cannot change how it is treated.
     */
    public String source;

    /** {@link #source} value for waypoints the Quest Guide manages. */
    public static final String SOURCE_QUEST = "quest";

    /** {@link #source} value for the Recipe Viewer's "path to this NPC" waypoint. */
    public static final String SOURCE_NPC = "npc";

    /**
     * {@link #source} value for the NPC module's scoreboard-objective marker. Kept distinct from
     * {@link #SOURCE_NPC} so the two features can be on at once without clearing each other's
     * waypoint - each only ever removes its own.
     */
    public static final String SOURCE_OBJECTIVE = "objective";

    /** {@link #source} value for the place you clicked on the SkyBlock map. */
    public static final String SOURCE_MAP = "map";

    /**
     * {@link #source} value for a point from a shipped location preset (the Galatea honey trees,
     * hives and beacons).
     *
     * <p>One source for every preset group rather than one per group: the whole set is republished
     * in a single {@code setTransient} call whenever the island or the player's overrides change, and
     * a set swap cannot leave a stale marker behind. Which group a point came from is answered by the
     * preset data, not by this tag.
     */
    public static final String SOURCE_PRESET = "preset";

    /**
     * {@link #source} value for a marker on a known Gemzie spawn spot in the Critter Safari.
     *
     * <p>A tag of its own rather than {@link #SOURCE_PRESET}, and not by preference:
     * {@link WaypointStore#setTransient} replaces a source's <i>whole</i> set in one call, which is
     * exactly what makes a stale marker impossible - so two publishers sharing one tag would erase
     * each other's markers every time either of them ran.
     */
    public static final String SOURCE_GEMZIE = "gemzie";

    /**
     * {@link #source} value for a temporary marker the player dropped with the ping keybind.
     *
     * <p>Its own tag for the reason {@link #SOURCE_GEMZIE} has one: {@link WaypointStore#setTransient}
     * replaces a source's whole set in one call, so two publishers sharing a tag erase each other.
     *
     * <p>A ping is the one marker set that <b>moves and expires</b>. Its publisher mutates these
     * objects in place between publishes - position while it follows an entity, {@link #opacity}
     * while it fades - because re-publishing invalidates the pathfinder, which at tick rate would
     * mean a search that restarts forever. Nothing here is ever persisted: a ping is dead in twelve
     * seconds and has no business in {@code config.json}.
     */
    public static final String SOURCE_PING = "ping";

    /**
     * {@link #source} value for a Fairy Soul the router may target.
     *
     * <p>Only the <i>routable candidates</i> carry this - the module draws every soul itself, with
     * collected/uncollected styling the generic waypoint renderer has no concept of.
     */
    public static final String SOURCE_FAIRY_SOUL = "fairysoul";

    /**
     * {@link #source} value for a dungeon secret the router may target.
     *
     * <p>The same arrangement as {@link #SOURCE_FAIRY_SOUL} and for the same reason: only the
     * <i>routable candidates</i> carry this, while the Secret Routes module draws every point itself
     * with per-type icons, order numbers and descriptions the generic renderer knows nothing about.
     */
    public static final String SOURCE_DUNGEON_SECRET = "dungeonsecret";

    /**
     * {@link #source} value for the place a mining commission is done.
     *
     * <p>A candidate rather than a named destination, for the same reason as the two above: several
     * commissions run at once, all of them are handed to one multi-goal search, and "the nearest" has
     * to mean nearest <i>by route</i> - the quarry thirty blocks away through a cliff is further than
     * the mine a hundred blocks down the tunnel you are already standing in.
     */
    public static final String SOURCE_COMMISSION = "commission";

    /**
     * {@link #source} value for a place the Hideyho is known to hide - a candidate the router may
     * target while a round of hide-and-seek is running.
     *
     * <p>The same arrangement as {@link #SOURCE_FAIRY_SOUL}: the whole set goes to one multi-goal
     * search, so "the next spot" means the next one <i>by route</i> - the only ordering worth having
     * when the payout is a step function of how long the search took.
     *
     * <p><b>Published with {@link #throughWalls} {@code false}</b>, unlike every other marker set in
     * the mod. This one is a walk between hiding places, not an alert about something behind cover:
     * a marker shining through the terrain collapses the walk into a straight line at the critter,
     * which is exactly what the feature was asked not to do.
     */
    public static final String SOURCE_HIDEYHO = "hideyho";

    /**
     * {@link #source} value for a confirmed Griffin burrow - one the game's own particles named.
     *
     * <p>Separate from {@link #SOURCE_DIANA_GUESS} because the two make different claims and the
     * player has to be able to tell them apart at a glance: this one is a burrow that is there, that
     * one is somewhere a burrow might be. Also because {@link WaypointStore#setTransient} replaces a
     * source's whole set in one call, and the two sets are rebuilt on different events.
     */
    public static final String SOURCE_DIANA_BURROW = "dianaburrow";

    /**
     * {@link #source} value for a place the Diana toolkit thinks a burrow is - fitted from the
     * spade's arc or from an arrow, and not yet confirmed by anything.
     *
     * <p>Carries the runners-up as well as the live guess when the player asks for them, since they
     * are republished together and a set swap must not leave half a chain behind.
     */
    public static final String SOURCE_DIANA_GUESS = "dianaguess";

    /**
     * {@link #source} value for a rare mythological creature - seen, or shared by the party.
     *
     * <p>Its own tag rather than riding on either of the two above: it expires on a timer neither of
     * them has, and a creature marker outliving the burrow it came out of is correct.
     */
    public static final String SOURCE_DIANA_CREATURE = "dianacreature";

    /**
     * {@link #source} value for the Diana appearance preview: one sample marker per marker type,
     * published only while the preview is switched on and a settings screen is open.
     *
     * <p>Its own tag so clearing the samples can never touch a real burrow, guess or creature.
     */
    public static final String SOURCE_DIANA_PREVIEW = "dianapreview";

    /**
     * {@link #source} value for a Crystal Hollows structure marker: one you found, or one another SBS
     * player in the same lobby found and shared.
     *
     * <p>A landmark set, not a destination. The dev route skips it, or it would route to whichever
     * structure happens to be nearest.
     */
    public static final String SOURCE_HOLLOWS = "hollows";

    /**
     * {@link #source} value for the Jungle Temple cheese spot, placed at a fixed offset from the
     * Kalhuiki Door Guardian during a Crystal Nucleus run. A landmark, never routed to.
     */
    public static final String SOURCE_TEMPLE_CHEESE = "templecheese";

    /**
     * {@link #source} value for the target picked on the Crystal Hollows map - a structure or one of
     * the player's own markers.
     *
     * <p>Published with {@link #routable} {@code false}: the map draws a marker and a direction, never
     * a route. Its own tag for the reason {@link #SOURCE_GEMZIE} has one.
     */
    public static final String SOURCE_CH_MAP = "chmap";

    /**
     * Whether a walking route to this waypoint is possible at all. {@code true} for everything the
     * player places by hand; the NPC module sets it {@code false} for a marker on another island of
     * the same coordinate space - visible on the horizon thanks to remembered terrain, but reached
     * by warping rather than walking, so the pathfinder must not be sent after it.
     *
     * <p>Defaults to {@code true} so waypoints written by older builds keep routing.
     */
    public boolean routable = true;

    /**
     * Whether the marker stays visible with a wall in the way.
     *
     * <p>The world overlay has no depth test - it projects world points onto the HUD - so seeing
     * through walls is what this renderer does by default, and every waypoint has always behaved
     * that way. The flag exists for callers whose marker is a destination rather than an alert (the
     * map's), where a player may prefer it to disappear behind terrain instead of hovering over it.
     *
     * <p>Defaults to {@code true}: that is the existing behaviour, and it is what an absent field in
     * an older config deserialises to.
     */
    public boolean throughWalls = true;

    /**
     * Whether the label carries a distance readout ("Bank §7182m").
     *
     * <p>Per waypoint rather than one global switch, because the answer differs by purpose: a marker
     * you are walking to wants the countdown, a dozen static markers just want their names. Defaults
     * to {@code true}, the behaviour every waypoint had before the flag existed.
     */
    public boolean showDistance = true;

    /**
     * How far away this marker is still drawn, in blocks; {@code 0} means no limit.
     *
     * <p>Per waypoint rather than one global setting, because the answer is a property of the set:
     * a handful of landmarks want to be visible across the island, while a dense set inside one
     * cavern is noise once you have left the room it belongs to. Distance-culling the whole set from
     * the publisher instead would mean republishing as the player walks - a set swap every few ticks
     * to express something the renderer can decide per frame for free.
     *
     * <p>Defaults to {@code 0}, so a waypoint that says nothing about distance draws at any range
     * exactly as every waypoint did before this field existed.
     */
    public int maxDistance;

    /**
     * A second line drawn under the label, or empty for none.
     *
     * <p>For state that belongs to the marker but is not its name - the honey tree timer's
     * remaining time is the first user. A field on the waypoint rather than a suffix on
     * {@link #name} because the two are not the same thing: the name is identity the player may
     * rename, this is a readout its publisher rewrites several times a second, and folding them
     * together would mean a publisher editing the player's label.
     *
     * <p><b>Publishers may mutate this in place between publishes</b>, which is what lets a
     * countdown tick without re-publishing the set (re-publishing invalidates the pathfinder, and
     * at tick rate that is a search that restarts forever - see {@link #SOURCE_PING}).
     *
     * <p>Empty by default, so a waypoint that says nothing about a sub-label draws exactly as every
     * waypoint did before this field existed.
     */
    public String subLabel = "";

    /**
     * The sub-label's own colour as {@code RRGGBB}, or empty to draw it in the marker's colour.
     *
     * <p>Separate from {@link #colorHex} because the sub-label is frequently the part whose colour
     * carries meaning - running versus ready - while the marker itself keeps the group's colour.
     */
    public String subLabelColorHex = "";

    /**
     * This waypoint's own colour as {@code RRGGBB}, or empty to use the global preset.
     *
     * <p>Exists because a publisher may own several sets that have to be told apart at a glance - the
     * honey preset groups draw trees and hives on one island - and the renderer resolved one colour
     * for every marker in the world until this field. Empty is the default, so a waypoint that says
     * nothing about colour keeps drawing in {@code pathfinding.waypointColor} exactly as before.
     */
    public String colorHex = "";

    /**
     * How strongly this marker is drawn, as a percentage of the alpha the renderer would otherwise
     * pick for each of its parts.
     *
     * <p><b>A multiplier rather than one absolute alpha</b>, because there is no single alpha to
     * override: {@link PathRenderer} chooses a different one for the marker's bloom shell, its core,
     * every slice of the beam and the label. A publisher asking for a fainter marker is asking for
     * all of those to be fainter in proportion, which is what scaling them does and what setting them
     * to one number would not.
     *
     * <p>Defaults to 100 - fully as before - so a waypoint written by an older build, or by any
     * caller that says nothing about opacity, draws exactly as it always did.
     */
    public int opacity = 100;

    /**
     * The distance in blocks below which this marker fades out, or {@code 0} to never fade.
     *
     * <p>For markers on something you walk up to and then interact with: at that point the beam and
     * the label are in front of what you came for, and the marker has already done its job. The fade
     * is linear from full at {@code fadeWithin} blocks to nothing at zero, so it reads as the marker
     * getting out of the way rather than blinking off.
     *
     * <p>Defaults to {@code 0}, which is the behaviour every waypoint had before this field existed.
     */
    public int fadeWithin = 0;

    /**
     * How the marker box is drawn. {@link MarkerBox#OUTLINE} - the default, and what an absent field
     * in an older config deserialises to - is the box every waypoint always had. A {@code null} from
     * a hand-edited file is read as the default by the renderer.
     */
    public MarkerBox box = MarkerBox.OUTLINE;

    /** Whether the light beam above the marker is drawn. Defaults to {@code true}, as before. */
    public boolean beam = true;

    /**
     * How large the label is drawn. {@link MarkerLabelSize#NORMAL} - the default - is the size every
     * label had before this field existed; {@code null} is read as it.
     */
    public MarkerLabelSize labelSize = MarkerLabelSize.NORMAL;

    /**
     * The 0..1 factor every alpha this marker is drawn with gets multiplied by: {@link #opacity},
     * further reduced by the near-fade once the camera is inside {@link #fadeWithin} blocks.
     *
     * <p>Lives here rather than in the renderer so the two fields are documented and applied in the
     * same place; the renderer asks once per marker per frame and scales what it was going to draw.
     */
    public double alphaScale(double distance) {
        double scale = Math.max(0, Math.min(100, opacity)) / 100.0;
        if (fadeWithin > 0) {
            scale *= Math.max(0.0, Math.min(1.0, distance / fadeWithin));
        }
        return scale;
    }

    /** Gson needs a no-arg constructor. */
    public Waypoint() {
    }

    /**
     * The colour to draw this marker in: {@link #colorHex} when it parses, else {@code fallback} -
     * which the caller passes as the global preset, so an unset waypoint is indistinguishable from
     * one written before the field existed.
     */
    public int rgb(int fallback) {
        Integer own = sbs.modid.client.core.render.OverlayColor.parseHex(colorHex);
        return own == null ? fallback : own;
    }

    public Waypoint(String name, BlockPos pos, String dimension) {
        this(name, pos, dimension, null);
    }

    public Waypoint(String name, BlockPos pos, String dimension, String source) {
        this.name = name;
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.dimension = dimension;
        this.source = source;
    }

    /** Whether the Quest Guide placed this waypoint. */
    public boolean isQuest() {
        return SOURCE_QUEST.equals(source);
    }

    /** Whether the Recipe Viewer's NPC locator placed this waypoint. */
    public boolean isNpc() {
        return SOURCE_NPC.equals(source);
    }

    /** Whether the NPC module's scoreboard-objective tracker placed this waypoint. */
    public boolean isObjective() {
        return SOURCE_OBJECTIVE.equals(source);
    }

    /** Whether this is the SkyBlock map's "take me to the place I clicked" marker. */
    public boolean isMap() {
        return SOURCE_MAP.equals(source);
    }

    /** Whether this point came from a shipped location preset. */
    public boolean isPreset() {
        return SOURCE_PRESET.equals(source);
    }

    /** Whether this is a marker on a known Gemzie spawn spot in the Critter Safari. */
    public boolean isGemzie() {
        return SOURCE_GEMZIE.equals(source);
    }

    /** Whether this is a Crystal Hollows structure marker (found or shared this lobby). */
    public boolean isHollowsStructure() {
        return SOURCE_HOLLOWS.equals(source);
    }

    /** Whether this is the Jungle Temple cheese spot. */
    public boolean isTempleCheese() {
        return SOURCE_TEMPLE_CHEESE.equals(source);
    }

    /** Whether this is the Crystal Hollows map's picked target. */
    public boolean isHollowsTarget() {
        return SOURCE_CH_MAP.equals(source);
    }

    /** Whether this is one of the Diana toolkit's markers: a burrow, a guess, a creature or a sample. */
    public boolean isDiana() {
        return SOURCE_DIANA_BURROW.equals(source) || SOURCE_DIANA_GUESS.equals(source)
                || SOURCE_DIANA_CREATURE.equals(source) || SOURCE_DIANA_PREVIEW.equals(source);
    }

    /** Whether this is a temporary marker dropped with the ping keybind. */
    public boolean isPing() {
        return SOURCE_PING.equals(source);
    }

    /** Whether this is a Fairy Soul the router may target. */
    public boolean isFairySoul() {
        return SOURCE_FAIRY_SOUL.equals(source);
    }

    /** Whether this is a dungeon secret the router may target. */
    public boolean isDungeonSecret() {
        return SOURCE_DUNGEON_SECRET.equals(source);
    }

    /** Whether this is the destination of a mining commission the router may target. */
    public boolean isCommission() {
        return SOURCE_COMMISSION.equals(source);
    }

    /** Whether this is a Hideyho hiding-spot candidate. */
    public boolean isHideyho() {
        return SOURCE_HIDEYHO.equals(source);
    }

    /**
     * Whether this waypoint is one of a candidate set a multi-goal search picks from, rather than a
     * named destination on its own.
     *
     * <p>Asked in three places that must agree: the dev waypoint list must not draw these (their own
     * module does, with styling this renderer has no concept of), and neither the dev router nor the
     * nearest-marker rule may pick one by straight-line distance - doing so quietly defeats the
     * multi-goal search whose entire purpose is that "nearest" means <i>by route</i>.
     */
    public boolean isRouteCandidate() {
        return isFairySoul() || isDungeonSecret() || isCommission() || isHideyho();
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    /** Whether this waypoint belongs to the given dimension (an empty dimension matches anything). */
    public boolean inDimension(String key) {
        return dimension == null || dimension.isEmpty() || dimension.equals(key);
    }

    /** "Waypoint (12, 70, -34)" – what the list and the world label show. */
    public String summary() {
        return name + " (" + x + ", " + y + ", " + z + ")";
    }
}
