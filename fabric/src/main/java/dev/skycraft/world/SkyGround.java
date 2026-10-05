// SkyCraft+ : the Minecraft underground under Skyrim
package dev.skycraft.world;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Where Skyrim's land is, on a 4-block grid, from the terrain triangles Skyrim has sent (kept until
 * the world changes). The underground lines up under a smoothed version of it (SkyUnderground).
 */
public final class SkyGround {
	private static final int GRID = 4;
	private static final int SMOOTH_RADIUS = 24; // blocks
	private static final ConcurrentHashMap<Long, Float> HEIGHT = new ConcurrentHashMap<>();

	private SkyGround() {
	}

	private static long key(int gx, int gz) {
		return ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
	}

	/** A triangle of Skyrim's collision arrived: if it's land, note its height on the grid. */
	static void record(SkyTri t) {
		if (!t.terrain) {
			return;
		}
		int gx0 = (int) Math.ceil(t.minX / GRID), gx1 = (int) Math.floor(t.maxX / GRID);
		int gz0 = (int) Math.ceil(t.minZ / GRID), gz1 = (int) Math.floor(t.maxZ / GRID);
		for (int gx = gx0; gx <= gx1; gx++) {
			for (int gz = gz0; gz <= gz1; gz++) {
				double h = t.heightAt(gx * GRID, gz * GRID);
				if (!Double.isNaN(h)) {
					HEIGHT.merge(key(gx, gz), (float) h, Math::max);
				}
			}
		}
	}

	static void clear() {
		HEIGHT.clear();
	}

	/**
	 * A point at least {@code margin} blocks under Skyrim's land, by the lowest of the sixteen grid
	 * heights around it (all of them known: a gap, like a hole in the land, says nothing).
	 */
	public static boolean under(double x, double y, double z, double margin) {
		int cx = (int) Math.floor(x / GRID), cz = (int) Math.floor(z / GRID);
		for (int dx = -1; dx <= 2; dx++) {
			for (int dz = -1; dz <= 2; dz++) {
				Float h = HEIGHT.get(key(cx + dx, cz + dz));
				if (h == null || y > h - margin) {
					return false;
				}
			}
		}
		return true;
	}

	/** Skyrim's land around (x, z), averaged over SMOOTH_RADIUS blocks; NaN where none is known. */
	public static double smoothed(int x, int z) {
		int r = SMOOTH_RADIUS / GRID;
		int cx = Math.floorDiv(x, GRID), cz = Math.floorDiv(z, GRID);
		double sum = 0.0;
		int n = 0;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > r * r) {
					continue;
				}
				Float h = HEIGHT.get(key(cx + dx, cz + dz));
				if (h != null) {
					sum += h;
					n++;
				}
			}
		}
		return n >= 8 ? sum / n : Double.NaN;
	}
}
