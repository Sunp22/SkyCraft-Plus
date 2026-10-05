// SkyCraft+ : living world
package dev.skycraft.world;

import dev.skycraft.link.Proto;

/**
 * What the host's Skyrim says about time, place and weather, for the integrated server (time and
 * weather sync, spawning). The client sets it every frame from Skyrim's state.
 */
public final class SkyWorld {
	private static volatile boolean valid;
	private static volatile float gameHour;
	private static volatile int placeFlags;

	private SkyWorld() {
	}

	public static void update(boolean inGame, float hour, int flags) {
		gameHour = hour;
		placeFlags = flags;
		valid = inGame;
	}

	public static void clear() {
		valid = false;
	}

	/** Skyrim is in game and has said where the player is. */
	public static boolean valid() {
		return valid;
	}

	/** Skyrim's hour of the day (0..24). */
	public static float hour() {
		return gameHour;
	}

	/** Skyrim's night: Minecraft's monsters come out (its sky is dark from about 8pm to 5am). */
	public static boolean night() {
		float h = gameHour;
		return h >= 20.0F || h < 5.0F;
	}

	public static boolean interior() {
		return (placeFlags & Proto.PLACE_INTERIOR) != 0;
	}

	public static boolean settlement() {
		return (placeFlags & Proto.PLACE_SETTLEMENT) != 0;
	}

	/** A city or town: villagers live there. */
	public static boolean town() {
		return (placeFlags & Proto.PLACE_TOWN) != 0;
	}

	public static boolean dungeon() {
		return (placeFlags & Proto.PLACE_DUNGEON) != 0;
	}

	public static boolean rain() {
		return (placeFlags & Proto.PLACE_RAIN) != 0;
	}

	public static boolean snow() {
		return (placeFlags & Proto.PLACE_SNOW) != 0;
	}
}
