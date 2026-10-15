// SkyCraft+ : Skyrim's inventory in Minecraft's
package dev.skycraft.client;

import dev.skycraft.client.mixin.ContainerScreenAccessor;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim's inventory in Minecraft's. Skyrim's menus can't be open alongside Minecraft's inventory,
 * so Minecraft's inventory gets Skyrim's categories as buttons: items on the right (Weapons,
 * Apparel, Potions...), magic on the left (the schools, Shouts, Powers), Skyrim's skills above and
 * its map below. Each category opens a chest-like grid of those Skyrim things, drawn as Minecraft
 * stand-ins (SkyIcons), from the list Skyrim keeps in shared memory (SkyLink.readSkyInventory).
 * A click does what Skyrim's own menu would (equip in a hand, put on, drink, eat, read, make a
 * shout the voice's): sent to Skyrim as EV_SKY_ITEM. Tab and I still open Skyrim's own menus.
 */
public final class SkyItemsScreen extends Screen {
	private static final Identifier CHEST = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
	private static final int COLUMNS = 9, ROWS = 6, WIDTH = 176, HEIGHT = 17 + ROWS * 18 + 7;
	private static final int BUTTON_HEIGHT = 18;

	/** One of Skyrim's categories: a button by Minecraft's inventory and the screen it opens. */
	public enum Category {
		ALL_ITEMS("All", true, i -> i.kind() < Proto.SKY_SPELL),
		WEAPONS("Weapons", true, i -> i.kind() == Proto.SKY_WEAPON || i.kind() == Proto.SKY_AMMO),
		APPAREL("Apparel", true, i -> i.kind() == Proto.SKY_APPAREL),
		POTIONS("Potions", true, i -> i.kind() == Proto.SKY_POTION),
		SCROLLS("Scrolls", true, i -> i.kind() == Proto.SKY_SCROLL),
		FOOD("Food", true, i -> i.kind() == Proto.SKY_FOOD),
		INGREDIENTS("Ingredients", true, i -> i.kind() == Proto.SKY_INGREDIENT),
		BOOKS("Books", true, i -> i.kind() == Proto.SKY_BOOK),
		KEYS("Keys", true, i -> i.kind() == Proto.SKY_KEY),
		MISC("Misc", true, i -> i.kind() == Proto.SKY_MISC),
		ALL_MAGIC("All magic", false, i -> i.kind() >= Proto.SKY_SPELL),
		ALTERATION("Alteration", false, i -> i.kind() == Proto.SKY_SPELL && i.sub() == Proto.SCHOOL_ALTERATION),
		CONJURATION("Conjuration", false, i -> i.kind() == Proto.SKY_SPELL && i.sub() == Proto.SCHOOL_CONJURATION),
		DESTRUCTION("Destruction", false, i -> i.kind() == Proto.SKY_SPELL && i.sub() == Proto.SCHOOL_DESTRUCTION),
		ILLUSION("Illusion", false, i -> i.kind() == Proto.SKY_SPELL && i.sub() == Proto.SCHOOL_ILLUSION),
		RESTORATION("Restoration", false, i -> i.kind() == Proto.SKY_SPELL && i.sub() == Proto.SCHOOL_RESTORATION),
		SHOUTS("Shouts", false, i -> i.kind() == Proto.SKY_SHOUT),
		POWERS("Powers", false, i -> i.kind() == Proto.SKY_POWER);

		final String label;
		final boolean items;
		final Predicate<SkyLink.SkyItem> filter;

		Category(String label, boolean items, Predicate<SkyLink.SkyItem> filter) {
			this.label = label;
			this.items = items;
			this.filter = filter;
		}
	}

	private final Category category;
	private final List<SkyLink.SkyItem> shown = new ArrayList<>();
	private SkyLink.@Nullable SkyInventory inventory;
	private int generation = Integer.MIN_VALUE;
	private int scroll;
	private int left, top;

	public SkyItemsScreen(Category category) {
		super(Component.literal("Skyrim: " + category.label));
		this.category = category;
	}

	/** True while Skyrim should keep its inventory list current (SkyClient's MC_WANTS_SKY_INVENTORY). */
	public static boolean wantsSkyInventory(@Nullable Screen screen) {
		return screen instanceof SkyItemsScreen || screen instanceof InventoryScreen;
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (!(screen instanceof InventoryScreen inventory) || minecraft.player == null || !SkyLink.active()) {
				return;
			}
			var accessor = (ContainerScreenAccessor) inventory;
			List<Button> buttons = new ArrayList<>();
			addButtons(minecraft, width, height, accessor.skycraft$leftPos(), accessor.skycraft$topPos(), 176, 166, null, buttons);
			Screens.getWidgets(screen).addAll(buttons);
			// The recipe book moves the inventory over: the buttons follow it.
			int[] placedAt = { accessor.skycraft$leftPos() };
			ScreenEvents.beforeExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				int leftPos = accessor.skycraft$leftPos();
				if (leftPos != placedAt[0]) {
					int dx = leftPos - placedAt[0];
					placedAt[0] = leftPos;
					for (Button button : buttons) {
						button.setX(button.getX() + dx);
					}
				}
			});
		});
	}

	/**
	 * Skyrim's category buttons around a panel at (x, y) of w x h: items on its right, magic on its
	 * left, Skills above, Map below. The current category's button is greyed out.
	 */
	private static void addButtons(Minecraft minecraft, int width, int height, int x, int y, int w, int h, @Nullable Category current, List<Button> out) {
		int room = Math.min(x, width - x - w) - 6;
		if (room < 44) {
			return;  // too narrow a window (or too big a GUI scale) for them
		}
		int buttonWidth = Math.min(90, room);
		List<Category> items = new ArrayList<>(), magic = new ArrayList<>();
		for (Category c : Category.values()) {
			(c.items ? items : magic).add(c);
		}
		for (int side = 0; side < 2; side++) {
			List<Category> column = side == 0 ? items : magic;
			int bx = side == 0 ? x + w + 4 : x - 4 - buttonWidth;
			int by = y + h / 2 - column.size() * BUTTON_HEIGHT / 2;
			for (Category c : column) {
				Button button = Button.builder(Component.literal(c.label), b -> minecraft.gui.setScreen(new SkyItemsScreen(c)))
					.bounds(bx, by, buttonWidth, BUTTON_HEIGHT).build();
				button.active = c != current;
				out.add(button);
				by += BUTTON_HEIGHT;
			}
		}
		if (y >= 22) {
			out.add(Button.builder(Component.literal("Skills"), b -> openSkyrimMenu(minecraft, Proto.SKY_ACT_MENU_STATS))
				.bounds(x + w / 2 - 40, y - 21, 80, BUTTON_HEIGHT).build());
		}
		if (y + h + 21 <= height) {
			out.add(Button.builder(Component.literal("Map"), b -> openSkyrimMenu(minecraft, Proto.SKY_ACT_MENU_MAP))
				.bounds(x + w / 2 - 40, y + h + 3, 80, BUTTON_HEIGHT).build());
		}
	}

	private static void openSkyrimMenu(Minecraft minecraft, int which) {
		minecraft.gui.setScreen(null);
		SkyLink.pushEvent(Proto.EV_SKY_ITEM, 0, 0.0F, 0.0F, 0.0F, 0.0F, which);
	}

	@Override
	protected void init() {
		this.left = (this.width - WIDTH) / 2;
		this.top = (this.height - HEIGHT - 12) / 2;
		List<Button> buttons = new ArrayList<>();
		addButtons(this.minecraft, this.width, this.height, this.left, this.top, WIDTH, HEIGHT + 12, this.category, buttons);
		buttons.forEach(this::addRenderableWidget);
		this.refresh(true);
	}

	@Override
	public void tick() {
		this.refresh(false);
	}

	private void refresh(boolean force) {
		int now = SkyLink.skyInventoryGeneration();
		if (!force && now == this.generation) {
			return;
		}
		SkyLink.SkyInventory read = SkyLink.readSkyInventory();
		if (read == null) {
			return;
		}
		this.inventory = read;
		this.generation = read.generation();
		this.shown.clear();
		for (SkyLink.SkyItem item : read.items()) {
			if (this.category.filter.test(item)) {
				this.shown.add(item);
			}
		}
		this.shown.sort(Comparator.comparing((SkyLink.SkyItem i) -> i.name().toLowerCase()));
		this.scroll = Math.clamp(this.scroll, 0, this.maxScroll());
	}

	private int maxScroll() {
		return Math.max(0, (this.shown.size() + COLUMNS - 1) / COLUMNS - ROWS);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (this.minecraft.player != null) {
			this.minecraft.gui.setScreen(new InventoryScreen(this.minecraft.player));
		} else {
			super.onClose();
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.minecraft.options.keyInventory.matches(event)) {
			this.minecraft.gui.setScreen(null);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST, this.left, this.top, 0.0F, 0.0F, WIDTH, 17 + ROWS * 18, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST, this.left, this.top + 17 + ROWS * 18, 0.0F, 215.0F, WIDTH, 7, 256, 256);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.text(this.font, this.title, this.left + 8, this.top + 6, 0xFF404040, false);
		if (this.maxScroll() > 0) {
			String page = (this.scroll + 1) + "/" + (this.maxScroll() + 1);
			graphics.text(this.font, page, this.left + WIDTH - 8 - this.font.width(page), this.top + 6, 0xFF404040, false);
		}
		SkyLink.SkyItem hovered = null;
		for (int i = 0; i < COLUMNS * ROWS; i++) {
			int index = this.scroll * COLUMNS + i;
			if (index >= this.shown.size()) {
				break;
			}
			SkyLink.SkyItem item = this.shown.get(index);
			int x = this.left + 8 + (i % COLUMNS) * 18, y = this.top + 18 + (i / COLUMNS) * 18;
			if (equipped(item)) {
				graphics.fill(x, y, x + 16, y + 16, 0x6060C060);
			}
			ItemStack stack = SkyIcons.stack(item);
			graphics.item(stack, x, y);
			graphics.itemDecorations(this.font, stack, x, y, item.count() > 1 && item.kind() < Proto.SKY_SPELL ? String.valueOf(item.count()) : null);
			String mark = mark(item);
			if (mark != null) {
				graphics.text(this.font, mark, x, y, 0xFFFFFF60, true);
			}
			if (mouseX >= x - 1 && mouseX < x + 17 && mouseY >= y - 1 && mouseY < y + 17) {
				graphics.fill(x, y, x + 16, y + 16, 0x80FFFFFF);
				hovered = item;
			}
		}
		if (this.inventory == null) {
			graphics.centeredText(this.font, Component.literal("Waiting for Skyrim..."), this.left + WIDTH / 2, this.top + 60, 0xFF404040);
		} else if (this.shown.isEmpty()) {
			graphics.centeredText(this.font, Component.literal("Nothing here"), this.left + WIDTH / 2, this.top + 60, 0xFF404040);
		}
		if (this.inventory != null) {
			String carry = this.category.items
				? String.format("Weight %.0f/%.0f   Gold %d", this.inventory.carried(), this.inventory.carryMax(), this.inventory.gold())
				: "Left click: right hand   Right click: left hand";
			graphics.centeredText(this.font, Component.literal(carry), this.left + WIDTH / 2, this.top + HEIGHT + 3, 0xFFFFFFFF);
		}
		if (hovered != null) {
			graphics.setComponentTooltipForNextFrame(this.font, tooltip(hovered), mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (scrollY != 0.0) {
			this.scroll = Math.clamp(this.scroll - (int) Math.signum(scrollY), 0, this.maxScroll());
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int column = (int) Math.floor((event.x() - this.left - 7) / 18.0), row = (int) Math.floor((event.y() - this.top - 17) / 18.0);
		if (column >= 0 && column < COLUMNS && row >= 0 && row < ROWS && event.x() >= this.left + 7 && event.y() >= this.top + 17) {
			int index = (this.scroll + row) * COLUMNS + column;
			if (index < this.shown.size()) {
				click(this.shown.get(index), event.button() == 1);
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	/** What Skyrim's own menu would do with a left (right hand) or right (left hand) click. */
	private static void click(SkyLink.SkyItem item, boolean rightButton) {
		int action = switch (item.kind()) {
			case Proto.SKY_WEAPON, Proto.SKY_SPELL, Proto.SKY_SCROLL -> rightButton && !item.has(Proto.ITEM_TWO_HANDED) ? Proto.SKY_ACT_LEFT : Proto.SKY_ACT_RIGHT;
			case Proto.SKY_APPAREL, Proto.SKY_AMMO -> Proto.SKY_ACT_RIGHT;
			case Proto.SKY_MISC -> item.sub() == 2 ? Proto.SKY_ACT_LEFT : 0;  // a torch: the left hand's
			case Proto.SKY_POTION, Proto.SKY_FOOD, Proto.SKY_INGREDIENT, Proto.SKY_BOOK, Proto.SKY_SHOUT, Proto.SKY_POWER -> Proto.SKY_ACT_USE;
			default -> 0;
		};
		if (action != 0) {
			SkyLink.pushEvent(Proto.EV_SKY_ITEM, item.formId(), 0.0F, 0.0F, 0.0F, 0.0F, action);
		}
	}

	private static boolean equipped(SkyLink.SkyItem item) {
		return item.has(Proto.ITEM_RIGHT_HAND | Proto.ITEM_LEFT_HAND | Proto.ITEM_WORN | Proto.ITEM_VOICE);
	}

	/** The corner mark of an equipped thing: which hand (R, L, or both), worn (E), the voice's (V). */
	private static @Nullable String mark(SkyLink.SkyItem item) {
		boolean right = item.has(Proto.ITEM_RIGHT_HAND), leftHand = item.has(Proto.ITEM_LEFT_HAND);
		if (right && leftHand) {
			return "RL";
		}
		if (right) {
			return "R";
		}
		if (leftHand) {
			return "L";
		}
		if (item.has(Proto.ITEM_WORN)) {
			return "E";
		}
		return item.has(Proto.ITEM_VOICE) ? "V" : null;
	}

	private static List<Component> tooltip(SkyLink.SkyItem item) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(item.name()).withStyle(item.has(Proto.ITEM_ENCHANTED) ? ChatFormatting.AQUA : ChatFormatting.WHITE));
		switch (item.kind()) {
			case Proto.SKY_WEAPON, Proto.SKY_AMMO -> lines.add(gray("Damage " + Math.round(item.stat())));
			case Proto.SKY_APPAREL -> lines.add(gray((item.has(Proto.ITEM_HEAVY) ? "Heavy armor, " : item.has(Proto.ITEM_LIGHT) ? "Light armor, " : "") + "armor " + Math.round(item.stat())));
			case Proto.SKY_POTION, Proto.SKY_FOOD -> {
				if (item.stat() > 0.0F) {
					lines.add(gray("Magnitude " + Math.round(item.stat()) + (item.stat2() > 0.0F ? " for " + Math.round(item.stat2()) + " s" : "")));
				}
			}
			case Proto.SKY_SPELL -> lines.add(gray("Magicka " + Math.round(item.stat()) + (item.stat2() > 0.0F ? ", lasts " + Math.round(item.stat2()) + " s" : "")));
			case Proto.SKY_SHOUT -> lines.add(gray(item.count() + (item.count() == 1 ? " word" : " words") + " unlocked" + (item.stat2() > 0.0F ? ", recharge " + Math.round(item.stat2()) + " s" : "")));
			default -> {
			}
		}
		if (item.kind() < Proto.SKY_SPELL) {
			lines.add(gray(String.format("Weight %s   Value %d", trim(item.weight()), item.value())));
		}
		if (item.has(Proto.ITEM_TWO_HANDED)) {
			lines.add(gray("Two-handed"));
		}
		if (item.has(Proto.ITEM_STOLEN)) {
			lines.add(Component.literal("Stolen").withStyle(ChatFormatting.RED));
		}
		String state = item.has(Proto.ITEM_RIGHT_HAND) && item.has(Proto.ITEM_LEFT_HAND) ? "In both hands"
			: item.has(Proto.ITEM_RIGHT_HAND) ? "In the right hand" : item.has(Proto.ITEM_LEFT_HAND) ? "In the left hand"
			: item.has(Proto.ITEM_WORN) ? (item.kind() == Proto.SKY_AMMO ? "Readied" : "Worn") : item.has(Proto.ITEM_VOICE) ? "In use" : null;
		if (state != null) {
			lines.add(Component.literal(state).withStyle(ChatFormatting.GREEN));
		}
		String hint = switch (item.kind()) {
			case Proto.SKY_WEAPON, Proto.SKY_SPELL, Proto.SKY_SCROLL -> item.has(Proto.ITEM_TWO_HANDED) ? "Click: equip" : "Left click: right hand, right click: left hand";
			case Proto.SKY_APPAREL -> item.has(Proto.ITEM_WORN) ? "Click: take off" : "Click: put on";
			case Proto.SKY_AMMO -> "Click: ready";
			case Proto.SKY_POTION -> item.sub() == Proto.P_POISON ? "Click: apply to your weapon" : "Click: drink";
			case Proto.SKY_FOOD, Proto.SKY_INGREDIENT -> "Click: eat";
			case Proto.SKY_BOOK -> item.sub() == 1 ? "Click: learn the spell" : "Read it in Skyrim's inventory (I)";
			case Proto.SKY_SHOUT, Proto.SKY_POWER -> "Click: use it (shout key)";
			case Proto.SKY_MISC -> item.sub() == 2 ? "Click: hold it in the left hand" : null;
			default -> null;
		};
		if (hint != null) {
			lines.add(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
		}
		return lines;
	}

	private static Component gray(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	private static String trim(float value) {
		return value == Math.rint(value) ? String.valueOf(Math.round(value)) : String.format("%.1f", value);
	}
}
