// SkyCraft+ : Minecraft's structures in Skyrim's world
package dev.skycraft.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Skyrim's open world (Tamriel) as read from the player's own Skyrim.esm: the land's height in
 * every cell, its water, and where Skyrim's buildings and trees stand. From it, fixed spots for
 * Minecraft's surface structures: on ground flat enough for each, dry and clear of Skyrim's
 * buildings for villages, outposts, temples and the mansion; deep water for the monument, ruins and
 * wrecks; shores for beached wrecks, buried treasure and the witch hut. The same Skyrim.esm gives
 * the same spots for everyone. Nothing of it is kept on disk or shipped (it's Skyrim's own data).
 *
 * Plain Java (no Minecraft): {@code java SkyrimMap.java <Skyrim.esm>} prints the spots.
 */
public final class SkyrimMap {
	/** Skyrim units per Minecraft block (proto kUnitsPerBlock). */
	public static final double UNITS = 70.0;
	private static final int TAMRIEL = 0x3C;
	private static final int CELL_UNITS = 4096;
	private static final int GRID = 33;  // land heights per cell side, 128 units apart

	/** A Minecraft structure's fixed spot: its own name, the structure (minecraft:...), Minecraft x/z and ground y. */
	public record Site(String name, String structure, double x, double z, double groundY) {
	}

	private final Map<Long, float[]> heights = new HashMap<>();  // cell -> 33x33 land heights (Skyrim units)
	private final Map<Long, Cell> cells = new HashMap<>();
	private final List<Site> sites = new ArrayList<>();
	private float defaultWater = -14000.0f;

	private static final class Cell {
		int x, y;
		float water = Float.NaN;
		boolean land;
		final List<float[]> obstacles = new ArrayList<>();  // Skyrim buildings, walls, cliffs, doors (x, y)
		final List<float[]> trees = new ArrayList<>();
	}

	private SkyrimMap() {
	}

	public List<Site> sites() {
		return sites;
	}

	/** Skyrim's sea level, in Minecraft's y. */
	public double seaLevel() {
		return defaultWater / UNITS;
	}

	/** The land's height (Minecraft y) at a Minecraft x/z near a site; NaN where it isn't kept. */
	public double groundAt(double x, double z) {
		double h = heightUnits(x * UNITS, -z * UNITS);
		return Double.isNaN(h) ? h : h / UNITS;
	}

	/** Water's surface (Minecraft y) over a Minecraft x/z. */
	public double waterAt(double x, double z) {
		return waterUnits(x * UNITS, -z * UNITS) / UNITS;
	}

	private static long key(int x, int y) {
		return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
	}

	private static int cellOf(double units) {
		return Math.floorDiv((int) Math.floor(units), CELL_UNITS);
	}

	private double heightUnits(double sx, double sy) {
		int cx = cellOf(sx), cy = cellOf(sy);
		float[] h = heights.get(key(cx, cy));
		if (h == null) {
			return Double.NaN;
		}
		double gx = (sx - cx * (double) CELL_UNITS) / 128.0, gy = (sy - cy * (double) CELL_UNITS) / 128.0;
		int ix = Math.min((int) gx, GRID - 2), iy = Math.min((int) gy, GRID - 2);
		double fx = gx - ix, fy = gy - iy;
		double a = h[iy * GRID + ix], b = h[iy * GRID + ix + 1], c = h[(iy + 1) * GRID + ix], d = h[(iy + 1) * GRID + ix + 1];
		return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
	}

	private double waterUnits(double sx, double sy) {
		Cell c = cells.get(key(cellOf(sx), cellOf(sy)));
		return c == null || Float.isNaN(c.water) ? defaultWater : c.water;
	}

	// ---- reading Skyrim.esm --------------------------------------------------------------------

	private MappedByteBuffer buf;
	private final Set<Integer> obstacleBases = new HashSet<>();
	private final Set<Integer> treeBases = new HashSet<>();
	private final Map<Integer, Cell> cellsByForm = new HashMap<>();

	public static SkyrimMap read(Path esm) throws IOException {
		SkyrimMap map = new SkyrimMap();
		try (FileChannel ch = FileChannel.open(esm)) {
			map.buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
			map.buf.order(ByteOrder.LITTLE_ENDIAN);
			map.walk(0, (int) ch.size(), false, -1);
		}
		map.buf = null;
		map.cellsByForm.clear();
		map.choose();
		map.keepHeightsNearSites();
		return map;
	}

	private String type(int p) {
		return new String(new byte[] { buf.get(p), buf.get(p + 1), buf.get(p + 2), buf.get(p + 3) }, StandardCharsets.US_ASCII);
	}

	private byte[] data(int p, int size, int flags) throws IOException {
		byte[] d = new byte[size];
		buf.get(p + 24, d);
		if ((flags & 0x40000) == 0) {
			return d;
		}
		int length = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).getInt();
		Inflater inflater = new Inflater();
		inflater.setInput(d, 4, d.length - 4);
		byte[] out = new byte[length];
		try {
			inflater.inflate(out);
		} catch (DataFormatException e) {
			throw new IOException(e);
		} finally {
			inflater.end();
		}
		return out;
	}

	/** Groups in order; inside Tamriel's (WRLD children, label 0x3C) its cells and their children are read. */
	private void walk(int start, int end, boolean tamriel, int cell) throws IOException {
		int p = start;
		while (p < end) {
			String t = type(p);
			int size = buf.getInt(p + 4);
			if (t.equals("GRUP")) {
				int label = buf.getInt(p + 8), groupType = buf.getInt(p + 12);
				String top = type(p + 8);
				boolean wanted = groupType != 0 || top.equals("STAT") || top.equals("TREE") || top.equals("DOOR") || top.equals("MSTT") || top.equals("WRLD");
				if (wanted && (groupType != 1 || label == TAMRIEL)) {
					int children = groupType == 6 ? label : groupType == 8 || groupType == 9 || groupType == 10 ? cell : -1;
					walk(p + 24, p + size, tamriel || (groupType == 1 && label == TAMRIEL), children);
				}
				p += size;
				continue;
			}
			int flags = buf.getInt(p + 8), form = buf.getInt(p + 12);
			switch (t) {
				case "STAT", "MSTT" -> {
					if (footprint(data(p, size, flags)) >= 1000) {
						obstacleBases.add(form);  // buildings, walls, cliffs (not the rocks and clutter strewn everywhere)
					}
				}
				case "DOOR" -> obstacleBases.add(form);
				case "TREE" -> treeBases.add(form);
				case "WRLD" -> {
					if (form == TAMRIEL) {
						readWorld(data(p, size, flags));
					}
				}
				case "CELL" -> {
					if (tamriel) {
						readCell(form, data(p, size, flags));
					}
				}
				case "LAND" -> {
					if (tamriel && cell != -1) {
						readLand(cell, data(p, size, flags));
					}
				}
				case "REFR" -> {
					if (tamriel && cell != -1) {
						readRef(cell, data(p, size, flags));
					}
				}
				default -> {
				}
			}
			p += 24 + size;
		}
	}

	private interface Sub {
		void at(String type, ByteBuffer b, int pos, int len);
	}

	private static void subrecords(byte[] d, Sub sub) {
		ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
		int extended = -1;
		while (b.remaining() >= 6) {
			byte[] tb = new byte[4];
			b.get(tb);
			String st = new String(tb, StandardCharsets.US_ASCII);
			int len = b.getShort() & 0xFFFF;
			if (st.equals("XXXX")) {
				extended = b.getInt(b.position());
				b.position(b.position() + len);
				continue;
			}
			if (extended >= 0) {
				len = extended;
				extended = -1;
			}
			int pos = b.position();
			if (pos + len > d.length) {
				return;
			}
			sub.at(st, b, pos, len);
			b.position(pos + len);
		}
	}

	private static int footprint(byte[] d) {
		int[] out = { 0 };
		subrecords(d, (t, b, pos, len) -> {
			if (t.equals("OBND") && len >= 12) {
				out[0] = Math.max(b.getShort(pos + 6) - b.getShort(pos), b.getShort(pos + 8) - b.getShort(pos + 2));
			}
		});
		return out[0];
	}

	private void readWorld(byte[] d) {
		subrecords(d, (t, b, pos, len) -> {
			if (t.equals("DNAM") && len >= 8) {
				defaultWater = b.getFloat(pos + 4);
			}
		});
	}

	private void readCell(int form, byte[] d) {
		Cell c = new Cell();
		boolean[] grid = { false };
		subrecords(d, (t, b, pos, len) -> {
			if (t.equals("XCLC") && len >= 8) {
				c.x = b.getInt(pos);
				c.y = b.getInt(pos + 4);
				grid[0] = true;
			} else if (t.equals("XCLW") && len >= 4) {
				float w = b.getFloat(pos);
				c.water = Math.abs(w) < 1.0e6f ? w : Float.NaN;  // (FLT_MAX: no water of its own)
			}
		});
		if (grid[0]) {
			cellsByForm.put(form, c);
			cells.put(key(c.x, c.y), c);
		}
	}

	private void readLand(int cellForm, byte[] d) {
		Cell c = cellsByForm.get(cellForm);
		if (c == null) {
			return;
		}
		subrecords(d, (t, b, pos, len) -> {
			if (t.equals("VHGT") && len >= 4 + GRID * GRID) {
				float[] h = new float[GRID * GRID];
				float offset = b.getFloat(pos);
				for (int row = 0; row < GRID; row++) {
					offset += d[pos + 4 + row * GRID];
					float value = offset;
					for (int col = 0; col < GRID; col++) {
						if (col > 0) {
							value += d[pos + 4 + row * GRID + col];
						}
						h[row * GRID + col] = value * 8.0f;
					}
				}
				heights.put(key(c.x, c.y), h);
				c.land = true;
			}
		});
	}

	private void readRef(int cellForm, byte[] d) {
		Cell c = cellsByForm.get(cellForm);
		if (c == null) {
			return;
		}
		int[] base = { 0 };
		float[] at = null;
		float[][] pos = { null };
		subrecords(d, (t, b, p, len) -> {
			if (t.equals("NAME") && len >= 4) {
				base[0] = b.getInt(p);
			} else if (t.equals("DATA") && len >= 12) {
				pos[0] = new float[] { b.getFloat(p), b.getFloat(p + 4) };
			}
		});
		if (pos[0] == null) {
			return;
		}
		if (obstacleBases.contains(base[0])) {
			c.obstacles.add(pos[0]);
		} else if (treeBases.contains(base[0])) {
			c.trees.add(pos[0]);
		}
	}

	// ---- choosing the spots ---------------------------------------------------------------------

	/** The ground in a circle (Skyrim units): its heights' range, dryness, and what stands on it. */
	private record Ground(boolean dry, boolean wet, double min, double max, double mean, double depth, int obstacles, int trees, boolean shore) {
	}

	private Ground ground(double cx, double cy, double radius) {
		boolean dry = true, wet = true, any = false;
		double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0, depth = Double.POSITIVE_INFINITY;
		int n = 0, above = 0, below = 0;
		double step = Math.max(128.0, radius / 8.0);
		for (double dx = -radius; dx <= radius; dx += step) {
			for (double dy = -radius; dy <= radius; dy += step) {
				if (dx * dx + dy * dy > radius * radius) {
					continue;
				}
				double h = heightUnits(cx + dx, cy + dy);
				if (Double.isNaN(h)) {
					return null;
				}
				double w = waterUnits(cx + dx, cy + dy);
				any = true;
				if (h < w + 96) {
					dry = false;
					below++;
				} else {
					above++;
				}
				if (h > w - 96) {
					wet = false;
				}
				depth = Math.min(depth, w - h);
				min = Math.min(min, h);
				max = Math.max(max, h);
				sum += h;
				n++;
			}
		}
		if (!any) {
			return null;
		}
		int obstacles = 0, trees = 0;
		double reach = radius + 700.0;  // ten blocks round it too
		for (int x = cellOf(cx - reach); x <= cellOf(cx + reach); x++) {
			for (int y = cellOf(cy - reach); y <= cellOf(cy + reach); y++) {
				Cell c = cells.get(key(x, y));
				if (c == null) {
					continue;
				}
				for (float[] o : c.obstacles) {
					if (sq(o[0] - cx) + sq(o[1] - cy) <= reach * reach) {
						obstacles++;
					}
				}
				for (float[] t : c.trees) {
					if (sq(t[0] - cx) + sq(t[1] - cy) <= radius * radius) {
						trees++;
					}
				}
			}
		}
		return new Ground(dry, wet, min, max, sum / n, depth, obstacles, trees, above > 0 && below > 0);
	}

	private static double sq(double v) {
		return v * v;
	}

	private interface Score {
		/** Higher is better; NaN: not here. */
		double of(Ground g, double x, double y);
	}

	/** One kind of structure: how far it reaches (blocks), how many more after the first, where it goes. */
	private record Kind(String name, String structure, double radiusBlocks, int extra, Score score) {
	}

	private void choose() {
		double sea = defaultWater;
		List<Kind> kinds = List.of(
			// Big and rare first.
			new Kind("Woodland mansion", "minecraft:mansion", 40, 0, flat(15, 40)),
			new Kind("Ocean monument", "minecraft:monument", 32, 0, deep(32)),
			new Kind("Snowy village", "minecraft:village_snowy", 48, 2, (g, x, y) -> g.mean > sea + 16000 ? flat(25, 40).of(g, x, y) : Double.NaN),
			new Kind("Taiga village", "minecraft:village_taiga", 48, 2, (g, x, y) -> g.trees >= 25 ? flat(25, 999).of(g, x, y) + Math.min(g.trees, 80) * 0.02 : Double.NaN),
			new Kind("Plains village", "minecraft:village_plains", 48, 3, flat(25, 12)),
			new Kind("Savanna village", "minecraft:village_savanna", 48, 1, flat(25, 20)),
			new Kind("Desert village", "minecraft:village_desert", 48, 1, flat(25, 4)),
			new Kind("Pillager outpost", "minecraft:pillager_outpost", 22, 4, flat(16, 30)),
			new Kind("Desert temple", "minecraft:desert_pyramid", 14, 0, flat(6, 2)),
			new Kind("Jungle temple", "minecraft:jungle_pyramid", 10, 0, (g, x, y) -> g.trees >= 6 ? flat(8, 999).of(g, x, y) + Math.min(g.trees, 30) * 0.05 : Double.NaN),
			new Kind("Witch hut", "minecraft:swamp_hut", 10, 0, (g, x, y) -> g.shore && g.obstacles == 0 && g.mean < sea + 2500 && g.max - g.min < 700 ? 5 : Double.NaN),
			new Kind("Igloo", "minecraft:igloo", 8, 0, (g, x, y) -> g.mean > sea + 22000 ? flat(5, 999).of(g, x, y) + g.mean / 10000.0 : Double.NaN),
			new Kind("Ruined portal", "minecraft:ruined_portal_desert", 8, 0, flat(8, 999)),  // partly buried
			new Kind("Overgrown ruined portal", "minecraft:ruined_portal_jungle", 8, 0, flat(8, 999)),  // on the surface
			new Kind("Trail ruins", "minecraft:trail_ruins", 15, 0, flat(10, 999)),
			new Kind("Buried treasure", "minecraft:buried_treasure", 3, 0, (g, x, y) -> g.shore && g.obstacles == 0 ? 1 : Double.NaN),
			new Kind("Shipwreck", "minecraft:shipwreck", 12, 0, deep(8)),
			new Kind("Beached shipwreck", "minecraft:shipwreck_beached", 12, 0, (g, x, y) -> g.shore && g.obstacles == 0 ? 2 : Double.NaN),
			new Kind("Ocean ruins", "minecraft:ocean_ruin_cold", 10, 1, deep(10)));
		// Candidate spots: every half cell inside the part of Tamriel one can walk to.
		List<double[]> spots = new ArrayList<>();
		for (Cell c : cells.values()) {
			if (c.land && playable(c.x, c.y)) {
				for (int i = 0; i < 2; i++) {
					for (int j = 0; j < 2; j++) {
						spots.add(new double[] { c.x * (double) CELL_UNITS + 1024 + i * 2048, c.y * (double) CELL_UNITS + 1024 + j * 2048 });
					}
				}
			}
		}
		spots.sort(Comparator.<double[]>comparingDouble(s -> s[0]).thenComparingDouble(s -> s[1]));  // the same order everywhere
		List<double[]> chosen = new ArrayList<>();
		for (int round = 0; round < 2; round++) {
			for (Kind kind : kinds) {
				int count = round == 0 ? 1 : kind.extra;
				for (int i = 0; i < count; i++) {
					double[] best = null;
					double bestScore = Double.NEGATIVE_INFINITY;
					for (double[] s : spots) {
						if (tooClose(chosen, s[0], s[1])) {
							continue;
						}
						Ground g = ground(s[0], s[1], kind.radiusBlocks * UNITS);
						if (g == null) {
							continue;
						}
						double score = kind.score.of(g, s[0], s[1]);
						if (!Double.isNaN(score) && score > bestScore) {
							bestScore = score;
							best = s;
						}
					}
					if (best == null) {
						break;
					}
					chosen.add(best);
					sites.add(new Site(kind.name + (round == 0 ? "" : " " + (i + 2)), kind.structure, best[0] / UNITS, -best[1] / UNITS, Double.NaN));
				}
			}
		}
	}

	/** Dry ground no more than {@code rangeBlocks} up and down, clear of Skyrim's buildings, with few enough trees. */
	private static Score flat(double rangeBlocks, int maxTrees) {
		return (g, x, y) -> {
			double range = (g.max - g.min) / UNITS;
			if (!g.dry || g.obstacles > 0 || g.trees > maxTrees || range > rangeBlocks) {
				return Double.NaN;
			}
			return 10.0 - range / rangeBlocks * 5.0 - g.trees * 0.02;
		};
	}

	/** Under water at least {@code blocks} deep all over. */
	private static Score deep(double blocks) {
		return (g, x, y) -> g.wet && g.depth >= blocks * UNITS ? 10.0 - Math.abs(g.depth / UNITS - blocks * 1.5) * 0.05 : Double.NaN;
	}

	/**
	 * Inside the part of Tamriel one can walk to (its outer cells are mountains and sea behind
	 * Skyrim's invisible borders), in cells.
	 */
	private static boolean playable(int x, int y) {
		return x >= -50 && x <= 50 && y >= -32 && y <= 32;
	}

	/** At least about 450 blocks from every other spot. */
	private static boolean tooClose(List<double[]> chosen, double x, double y) {
		double spacing = 450.0 * UNITS;
		for (double[] o : chosen) {
			if (sq(o[0] - x) + sq(o[1] - y) < spacing * spacing) {
				return true;
			}
		}
		return false;
	}

	/** Only the land within three cells of a spot is kept (for building there); the rest goes. */
	private void keepHeightsNearSites() {
		Set<Long> keep = new HashSet<>();
		for (Site s : sites) {
			int cx = cellOf(s.x * UNITS), cy = cellOf(-s.z * UNITS);
			for (int dx = -3; dx <= 3; dx++) {
				for (int dy = -3; dy <= 3; dy++) {
					keep.add(key(cx + dx, cy + dy));
				}
			}
		}
		heights.keySet().retainAll(keep);
		List<Site> withGround = new ArrayList<>();
		for (Site s : sites) {
			withGround.add(new Site(s.name, s.structure, s.x, s.z, groundAt(s.x, s.z)));
		}
		sites.clear();
		sites.addAll(withGround);
		for (Cell c : cells.values()) {
			c.obstacles.clear();
			c.trees.clear();
		}
	}

	public static void main(String[] args) throws IOException {
		long t0 = System.nanoTime();
		SkyrimMap map = read(Path.of(args[0]));
		System.out.printf("read in %.1f s; sea level y %.1f; %d spots%n", (System.nanoTime() - t0) / 1e9, map.seaLevel(), map.sites().size());
		for (Site s : map.sites()) {
			System.out.printf("%-22s %-30s x %7.0f z %7.0f ground y %6.1f water y %6.1f%n", s.name(), s.structure(), s.x(), s.z(), s.groundY(),
				map.waterAt(s.x(), s.z()));
		}
	}
}
