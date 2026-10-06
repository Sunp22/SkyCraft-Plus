// SkyCraft+ : world switcher
package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FileUtil;

/**
 * Several Minecraft worlds to play Skyrim with. Each is a world of its own (inventory, levels,
 * builds, the ground dug out of Skyrim), and none is tied to a Skyrim save: loading another Skyrim
 * save keeps the world you're in, and the pause menu's "Minecraft worlds" button switches to
 * another, makes a new one or deletes one. The one in use is {@code world=} in
 * config/skycraft.properties (the original "SkyCraft" world by default).
 */
public final class WorldSwitcher {
	private static final String KEY = "world";
	/** Written into each SkyCraft world's folder, so the list shows them and not other saves. */
	private static final String MARKER = "skycraft-world.txt";
	private static final int PAGE = 6;

	private static String current = SkyCraft.WORLD_NAME;

	private WorldSwitcher() {
	}

	/** The SkyCraft world in use (its save folder's name). */
	public static String current() {
		return current;
	}

	/** One of SkyCraft's worlds (Minecraft's own "experimental settings" backup prompt is skipped for them). */
	public static boolean isSkyCraftWorld(String folder) {
		return folder.equals(SkyCraft.WORLD_NAME) || folder.equals(current) || Files.exists(savesDir().resolve(folder).resolve(MARKER));
	}

	private static Path config() {
		return FabricLoader.getInstance().getConfigDir().resolve("skycraft.properties");
	}

	private static Path savesDir() {
		return Minecraft.getInstance().getLevelSource().getBaseDir();
	}

	public static void register() {
		load();
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof PauseScreen pause && pause.showsPauseMenu() && minecraft.player != null) {
				Screens.getWidgets(screen).add(button(minecraft, screen));
			}
		});
	}

	private static Button button(Minecraft minecraft, Screen pause) {
		boolean own = minecraft.hasSingleplayerServer();
		boolean away = SkyClient.inMinecraftWorld(minecraft);
		Button button = Button.builder(Component.literal("Minecraft worlds..."), b -> minecraft.gui.setScreen(new WorldsScreen(pause)))
			.bounds(4, 28, 150, 20)
			.tooltip(Tooltip.create(Component.literal(!own ? "In a friend's world: type /leave to go back to your own first."
				: away ? "Go back to Skyrim's world first (out of the Nether or the End)."
				: "Switch to another Minecraft world, make a new one, or delete one. Your Skyrim save stays as it is.")))
			.build();
		button.active = own && !away;
		return button;
	}

	/** The SkyCraft worlds in the saves folder, the one in use first. */
	static List<String> worlds() {
		List<String> list = new ArrayList<>();
		list.add(current);
		try (Stream<Path> dirs = Files.list(savesDir())) {
			dirs.filter(Files::isDirectory)
				.map(p -> p.getFileName().toString())
				.filter(name -> !name.equals(current) && Files.exists(savesDir().resolve(name).resolve("level.dat")) && isSkyCraftWorld(name))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.forEach(list::add);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't list the worlds in {}", savesDir(), e);
		}
		return list;
	}

	/** Every client tick: the world in use is marked as SkyCraft's (once it's been made). */
	public static void tick(Minecraft minecraft) {
		var server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null) {
			return;
		}
		Path marker = savesDir().resolve(current).resolve(MARKER);
		if (!Files.exists(marker) && Files.exists(marker.getParent().resolve("level.dat"))) {
			try {
				Files.writeString(marker, "A SkyCraft world (listed in the pause menu's \"Minecraft worlds\").\n");
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't mark {}", marker, e);
			}
		}
	}

	/** Leaves this world for another (made if it doesn't exist yet): MirrorWorld opens it. */
	static void switchTo(Minecraft minecraft, String folder) {
		SkyCraft.LOG.info("SkyCraft: switching Minecraft world {} -> {}", current, folder);
		current = folder;
		save();
		MirrorWorld.reopen(minecraft, "Now playing in the Minecraft world \"" + folder + "\".");
	}

	static boolean delete(String folder) {
		if (folder.equals(current)) {
			return false;
		}
		try (var access = Minecraft.getInstance().getLevelSource().createAccess(folder)) {
			access.deleteLevel();
			SkyCraft.LOG.info("SkyCraft: deleted Minecraft world {}", folder);
			return true;
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't delete Minecraft world {}", folder, e);
			return false;
		}
	}

	private static void load() {
		Properties props = new Properties();
		try (var in = Files.newBufferedReader(config())) {
			props.load(in);
		} catch (NoSuchFileException e) {
			return;
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't read {}", config(), e);
			return;
		}
		String name = props.getProperty(KEY, "").trim();
		current = name.isEmpty() ? SkyCraft.WORLD_NAME : name;
		SkyCraft.LOG.info("SkyCraft: Minecraft world in use: {}", current);
	}

	/** Rewrites only its own line, keeping the file's comments and other settings. */
	private static void save() {
		Path file = config();
		String line = KEY + "=" + current;
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>(List.of("# SkyCraft"));
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).trim().startsWith(KEY + "=")) {
					lines.set(i, line);
					found = true;
				}
			}
			if (!found) {
				lines.add("# The Minecraft world in use (a folder in saves; the pause menu's \"Minecraft worlds\" switches it).");
				lines.add(line);
			}
			Files.createDirectories(file.getParent());
			Files.write(file, lines);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't save {}", file, e);
		}
	}

	/** The pause menu's "Minecraft worlds" screen. */
	static final class WorldsScreen extends Screen {
		private final Screen parent;
		private LinearLayout layout = LinearLayout.vertical().spacing(6);
		private int page;
		private String typedName = "";

		WorldsScreen(Screen parent) {
			super(Component.literal("Minecraft worlds"));
			this.parent = parent;
		}

		@Override
		protected void init() {
			List<String> worlds = worlds();
			int pages = Math.max(1, (worlds.size() + PAGE - 1) / PAGE);
			this.page = Math.min(this.page, pages - 1);
			this.layout = LinearLayout.vertical().spacing(6);
			this.layout.defaultCellSetting().alignHorizontallyCenter();
			this.layout.addChild(new StringWidget(this.title, this.font));
			this.layout.addChild(new StringWidget(Component.literal("Your Skyrim save stays as it is; only Minecraft's world changes."), this.font));
			for (int i = this.page * PAGE; i < Math.min(worlds.size(), (this.page + 1) * PAGE); i++) {
				String name = worlds.get(i);
				boolean inUse = name.equals(current);
				LinearLayout row = this.layout.addChild(LinearLayout.horizontal().spacing(4));
				Button play = row.addChild(Button.builder(Component.literal(inUse ? name + " (playing)" : name), b -> this.confirmSwitch(name)).width(200).build());
				play.active = !inUse;
				Button delete = row.addChild(Button.builder(Component.literal("Delete"), b -> this.confirmDelete(name)).width(60)
					.tooltip(Tooltip.create(Component.literal(inUse ? "Switch to another world first." : "Deletes this world for good."))).build());
				delete.active = !inUse;
			}
			if (pages > 1) {
				LinearLayout paging = this.layout.addChild(LinearLayout.horizontal().spacing(4));
				Button previous = paging.addChild(Button.builder(Component.literal("<"), b -> this.turn(-1)).width(20).build());
				paging.addChild(new StringWidget(Component.literal("Page " + (this.page + 1) + " of " + pages), this.font));
				Button next = paging.addChild(Button.builder(Component.literal(">"), b -> this.turn(1)).width(20).build());
				previous.active = this.page > 0;
				next.active = this.page < pages - 1;
			}
			LinearLayout create = this.layout.addChild(LinearLayout.horizontal().spacing(4));
			EditBox name = create.addChild(new EditBox(this.font, 200, 20, Component.literal("New world's name")));
			name.setMaxLength(32);
			name.setHint(Component.literal("New world's name"));
			name.setValue(this.typedName);
			name.setResponder(text -> this.typedName = text);
			create.addChild(Button.builder(Component.literal("Create"), b -> this.create(name.getValue())).width(60)
				.tooltip(Tooltip.create(Component.literal("Makes a new Minecraft world and switches to it. It starts with an empty inventory; Skyrim is unchanged.")))
				.build());
			this.layout.addChild(Button.builder(Component.literal("Done"), b -> this.onClose()).width(200).build());
			this.layout.visitWidgets(this::addRenderableWidget);
			this.repositionElements();
		}

		@Override
		protected void repositionElements() {
			this.layout.arrangeElements();
			FrameLayout.centerInRectangle(this.layout, this.getRectangle());
		}

		@Override
		public void onClose() {
			this.minecraft.gui.setScreen(this.parent);
		}

		private void turn(int by) {
			this.page += by;
			this.rebuildWidgets();
		}

		private void confirmSwitch(String name) {
			this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
				if (yes) {
					switchTo(this.minecraft, name);
				} else {
					this.minecraft.gui.setScreen(this);
				}
			}, Component.literal("Switch to \"" + name + "\"?"),
				Component.literal("This world is saved and closed, and \"" + name + "\" opens where your Skyrim character is. Your Skyrim save is unchanged.")));
		}

		private void confirmDelete(String name) {
			this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
				if (yes) {
					delete(name);
				}
				this.minecraft.gui.setScreen(this);
			}, Component.literal("Delete \"" + name + "\"?"),
				Component.literal("Its inventory, builds and everything dug out of Skyrim in it are gone for good."),
				Component.literal("Delete"), Component.literal("Cancel")));
		}

		private void create(String typed) {
			String wanted = typed.trim().isEmpty() ? "SkyCraft world" : typed.trim();
			String folder;
			try {
				folder = FileUtil.findAvailableName(savesDir(), wanted, "");
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't pick a folder for {}", wanted, e);
				return;
			}
			this.typedName = "";
			switchTo(this.minecraft, folder);
		}
	}
}
