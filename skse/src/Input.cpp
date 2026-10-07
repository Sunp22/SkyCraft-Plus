#include "Game.h"

#include <SimpleIni.h>

namespace skycraft
{
	namespace
	{
		// DirectInput scan code (what Skyrim reports) -> SDL scancode / USB HID usage (what MC uses).
		constexpr auto kDikToSdl = [] {
			std::array<std::uint16_t, 256> t{};
			t[0x01] = 41;  // Esc
			for (int i = 0; i < 9; ++i) t[0x02 + i] = static_cast<std::uint16_t>(30 + i);  // 1-9
			t[0x0B] = 39;  // 0
			t[0x0C] = 45, t[0x0D] = 46, t[0x0E] = 42, t[0x0F] = 43;  // - = Backspace Tab
			t[0x10] = 20, t[0x11] = 26, t[0x12] = 8, t[0x13] = 21, t[0x14] = 23;  // Q W E R T
			t[0x15] = 28, t[0x16] = 24, t[0x17] = 12, t[0x18] = 18, t[0x19] = 19;  // Y U I O P
			t[0x1A] = 47, t[0x1B] = 48, t[0x1C] = 40, t[0x1D] = 224;               // [ ] Enter LCtrl
			t[0x1E] = 4, t[0x1F] = 22, t[0x20] = 7, t[0x21] = 9, t[0x22] = 10;     // A S D F G
			t[0x23] = 11, t[0x24] = 13, t[0x25] = 14, t[0x26] = 15;                // H J K L
			t[0x27] = 51, t[0x28] = 52, t[0x29] = 53, t[0x2A] = 225, t[0x2B] = 49;  // ; ' ` LShift backslash
			t[0x2C] = 29, t[0x2D] = 27, t[0x2E] = 6, t[0x2F] = 25, t[0x30] = 5;    // Z X C V B
			t[0x31] = 17, t[0x32] = 16, t[0x33] = 54, t[0x34] = 55, t[0x35] = 56;  // N M , . /
			t[0x36] = 229, t[0x37] = 85, t[0x38] = 226, t[0x39] = 44, t[0x3A] = 57;  // RShift KP* LAlt Space Caps
			for (int i = 0; i < 10; ++i) t[0x3B + i] = static_cast<std::uint16_t>(58 + i);  // F1-F10
			t[0x45] = 83, t[0x46] = 71;                                             // NumLock ScrollLock
			t[0x47] = 95, t[0x48] = 96, t[0x49] = 97, t[0x4A] = 86;                 // KP7 KP8 KP9 KP-
			t[0x4B] = 92, t[0x4C] = 93, t[0x4D] = 94, t[0x4E] = 87;                 // KP4 KP5 KP6 KP+
			t[0x4F] = 89, t[0x50] = 90, t[0x51] = 91, t[0x52] = 98, t[0x53] = 99;   // KP1 KP2 KP3 KP0 KP.
			t[0x56] = 100, t[0x57] = 68, t[0x58] = 69;                              // OEM102 F11 F12
			t[0x9C] = 88, t[0x9D] = 228, t[0xB5] = 84, t[0xB7] = 70, t[0xB8] = 230;  // KPEnter RCtrl KP/ PrtSc RAlt
			t[0xC5] = 72, t[0xC7] = 74, t[0xC8] = 82, t[0xC9] = 75, t[0xCB] = 80;  // Pause Home Up PgUp Left
			t[0xCD] = 79, t[0xCF] = 77, t[0xD0] = 81, t[0xD1] = 78, t[0xD2] = 73;  // Right End Down PgDn Insert
			t[0xD3] = 76, t[0xDB] = 227, t[0xDC] = 231, t[0xDD] = 101;             // Delete LWin RWin Menu
			return t;
		}();

		// Keys Skyrim keeps while Minecraft drives the player. Everything else is Minecraft's.
		constexpr std::uint32_t kDikEscape = 0x01;   // Skyrim journal / system menu (or closes an MC screen)
		constexpr std::uint32_t kDikConsole = 0x29;  // Skyrim console
		constexpr std::uint32_t kDikG = 0x22;        // Skyrim activate: doors, NPCs, containers, items
		constexpr std::uint32_t kDikH = 0x23;        // Skyrim wait (T is Minecraft chat)
		constexpr std::uint32_t kDikJ = 0x24;        // Skyrim quest journal
		constexpr std::uint32_t kDikM = 0x32;        // Skyrim map
		constexpr std::uint32_t kDikO = 0x18;        // Minecraft pause / options menu (Esc is Skyrim's)
		constexpr std::uint32_t kDikF9 = 0x43;       // Skyrim quickload

		// SkyCraft+ : extra keys from sSkyrimMenuKeys in SkyCraft.ini (e.g. Tab, I, P) that reach
		// Skyrim's own menus (Tab menu, inventory, magic) instead of Minecraft.
		std::array<bool, 256> extraMenuKeys{};
		// SkyCraft+ : mode switch. sModeToggleKey: Minecraft mode <-> Skyrim mode (0: none).
		std::uint32_t modeToggleKey = 0;

		// "Tab", "I", "F1", "0x0F" -> DirectInput scan code, or 0 if unknown.
		std::uint32_t DikFromName(std::string a_name)
		{
			std::ranges::transform(a_name, a_name.begin(), [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
			if (a_name.size() == 1) {
				static constexpr std::string_view kRows[] = { "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM" };
				static constexpr std::uint32_t    kRowStart[] = { 0x10, 0x1E, 0x2C };
				for (std::size_t r = 0; r < 3; ++r) {
					if (const auto i = kRows[r].find(a_name[0]); i != std::string_view::npos) {
						return kRowStart[r] + static_cast<std::uint32_t>(i);
					}
				}
				if (a_name[0] >= '1' && a_name[0] <= '9') {
					return 0x02 + static_cast<std::uint32_t>(a_name[0] - '1');
				}
				if (a_name[0] == '0') {
					return 0x0B;
				}
				return 0;
			}
			if (a_name == "TAB") {
				return 0x0F;
			}
			if (a_name.size() <= 3 && a_name[0] == 'F') {
				const int n = std::atoi(a_name.c_str() + 1);
				if (n >= 1 && n <= 10) {
					return 0x3B + static_cast<std::uint32_t>(n - 1);
				}
				if (n == 11 || n == 12) {
					return 0x57 + static_cast<std::uint32_t>(n - 11);
				}
				return 0;
			}
			if (a_name.starts_with("0X")) {
				const auto v = std::strtoul(a_name.c_str() + 2, nullptr, 16);
				return v > 0 && v < 256 ? static_cast<std::uint32_t>(v) : 0;
			}
			return 0;
		}

		void LoadControls()
		{
			CSimpleIniA ini;
			ini.SetUnicode();
			ini.LoadFile("Data/SKSE/Plugins/SkyCraft.ini");
			const std::string list = ini.GetValue("Controls", "sSkyrimMenuKeys", "");
			std::string       shown;
			std::size_t       start = 0;
			while (start <= list.size()) {
				const auto  end = std::min(list.find(',', start), list.size());
				std::string name = list.substr(start, end - start);
				start = end + 1;
				name.erase(0, name.find_first_not_of(" \t"));
				name.erase(name.find_last_not_of(" \t") + 1);
				if (name.empty()) {
					continue;
				}
				const auto code = DikFromName(name);
				if (!code) {
					logger::warn("sSkyrimMenuKeys: unknown key '{}' (use a letter, digit, F1-F12, Tab, or a scan code like 0x0F)", name);
				} else if (code == kDikG || code == kDikH || code == kDikO) {
					logger::warn("sSkyrimMenuKeys: '{}' is already a SkyCraft key; ignored", name);
				} else {
					extraMenuKeys[code] = true;
					shown += shown.empty() ? name : ", " + name;
				}
			}
			if (!shown.empty()) {
				logger::info("keys that open Skyrim menus: Esc, ~, J, M, F9, {}", shown);
			}

			State().skyrimHandsOption = ini.GetBoolValue("Controls", "bSkyrimHands", false);
			logger::info("Skyrim hands (equip Skyrim gear in a Minecraft hand): {}", State().skyrimHandsOption ? "on" : "off");

			// SkyCraft+ : the Nether and the End.
			State().minecraftWorldsOption = ini.GetBoolValue("Worlds", "bMinecraftWorlds", true);
			State().waitingCellId = static_cast<std::uint32_t>(ini.GetLongValue("Worlds", "iWaitingCell", 0x6F3EB));
			logger::info("Minecraft's own worlds (Nether, End): {} (waiting cell {:08X})", State().minecraftWorldsOption ? "on" : "off",
				State().waitingCellId.load());

			// SkyCraft+ : Skyrim enemies in the Nether and the End's outer islands.
			auto formList = [&](const char* a_key, const char* a_default) {
				std::vector<RE::FormID> ids;
				const std::string list = ini.GetValue("Worlds", a_key, a_default);
				std::size_t       start = 0;
				while (start <= list.size()) {
					const auto  end = std::min(list.find(',', start), list.size());
					std::string item = list.substr(start, end - start);
					start = end + 1;
					item.erase(0, item.find_first_not_of(" \t"));
					item.erase(item.find_last_not_of(" \t") + 1);
					if (!item.empty()) {
						ids.push_back(static_cast<RE::FormID>(std::strtoul(item.c_str(), nullptr, 0)));
					}
				}
				return ids;
			};
			State().skyrimEnemiesOption = ini.GetBoolValue("Worlds", "bSkyrimEnemies", true);
			State().netherEnemies = formList("sNetherEnemies", "0x1E79B, 0x25D1A, 0x25D1B, 0x23AA6, 0x2D2D4, 0x23AB0, 0x33973");
			State().endEnemies = formList("sEndEnemies", "0x23AA7, 0x23ABD, 0x10FCE5, 0x10EC90, 0x10EC8F, 0x23AA8, 0x23AB3");
			State().enemyCap = static_cast<int>(ini.GetLongValue("Worlds", "iEnemyCap", 6));
			State().enemySeconds = static_cast<float>(ini.GetDoubleValue("Worlds", "fEnemySeconds", 12.0));
			State().structureMarkersOption = ini.GetBoolValue("Worlds", "bStructureMarkers", true);
			logger::info("Minecraft's structures on Skyrim's map: {}", State().structureMarkersOption ? "on" : "off");
			logger::info("Skyrim enemies in the Nether and the End: {} ({} and {} kinds, up to {}, about every {:.0f} s)",
				State().skyrimEnemiesOption ? "on" : "off", State().netherEnemies.size(), State().endEnemies.size(), State().enemyCap, State().enemySeconds);

			const std::string toggle = ini.GetValue("Controls", "sModeToggleKey", "");
			if (!toggle.empty()) {
				const auto code = DikFromName(toggle);
				if (!code) {
					logger::warn("sModeToggleKey: unknown key '{}'; no mode switch", toggle);
				} else if (code == kDikG || code == kDikH || code == kDikO || code == kDikEscape || code == kDikConsole || extraMenuKeys[code]) {
					logger::warn("sModeToggleKey: '{}' already has a job; no mode switch", toggle);
				} else {
					modeToggleKey = code;
					logger::info("mode switch key: {}", toggle);
				}
			}
		}

		bool IsSkyrimMenuKey(std::uint32_t a_code)
		{
			return a_code == kDikEscape || a_code == kDikConsole || a_code == kDikJ || a_code == kDikM || a_code == kDikF9 ||
			       (a_code < extraMenuKeys.size() && extraMenuKeys[a_code]);
		}

		float lookDx = 0.0f;
		float lookDy = 0.0f;

		// G on something Skyrim can activate (door, NPC, container, item, furniture) activates it in
		// Skyrim. Furniture (chairs, beds, crafting stations, pull-bar levers) hands the player to
		// Skyrim while it's used (Game.cpp, SkyrimTakeover).
		void ActivateSkyrimTarget()
		{
			auto* pick = RE::CrosshairPickData::GetSingleton();
			auto* player = RE::PlayerCharacter::GetSingleton();
			if (!pick || !player) {
				return;
			}
			auto target = pick->GetActiveTarget().get();
			if (!target || target.get() == player) {
				logger::info("G: nothing to activate under the crosshair");
				return;
			}
			target->ActivateRef(player, 0, nullptr, 0, false);
			logger::info("activated {:08X} ({})", target->GetFormID(), target->GetDisplayFullName());
		}

		// SkyCraft+ : a right click with a Skyrim left hand goes to Minecraft when its crosshair is on
		// something used by hand (a chest, crafting table, door, villager...). Decided on the press
		// and kept until the release, for both the input sink and PlayerControls (whichever sees the
		// press first decides; the same event object within a few milliseconds is the same press).
		bool RightClickToMinecraft(RE::ButtonEvent* a_button)
		{
			static bool                  toMinecraft = false;
			static const RE::ButtonEvent* decidedFor = nullptr;
			static std::uint64_t         decidedAt = 0;
			if (a_button && a_button->IsDown()) {
				const auto now = GetTickCount64();
				if (a_button != decidedFor || now - decidedAt > 50) {
					decidedFor = a_button;
					decidedAt = now;
					toMinecraft = State().mcUseTarget;
				}
			}
			return toMinecraft;
		}

		// SkyCraft+ : Skyrim mode block use. Skyrim's own Activate (E by default, or whatever it's
		// bound to), pressed.
		bool IsActivatePress(RE::InputEvent* a_event)
		{
			if (a_event->GetEventType() != RE::INPUT_EVENT_TYPE::kButton) {
				return false;
			}
			auto* button = a_event->AsButtonEvent();
			if (!button->IsDown()) {
				return false;
			}
			auto* events = RE::UserEvents::GetSingleton();
			if (events && button->QUserEvent() == events->activate) {
				return true;
			}
			auto* controls = RE::ControlMap::GetSingleton();
			return controls && controls->GetMappedKey("Activate", button->GetDevice()) == button->GetIDCode();
		}

		// Skyrim mode: Activate on nothing Skyrim can activate uses the Minecraft block under the
		// crosshair instead (Minecraft's player looks the same way, see Game.cpp).
		void UseMinecraftBlockIfNothingElse()
		{
			auto* pick = RE::CrosshairPickData::GetSingleton();
			auto* player = RE::PlayerCharacter::GetSingleton();
			if (!pick || !player) {
				return;
			}
			if (auto target = pick->GetActiveTarget().get(); target && target.get() != player) {
				return;  // Skyrim activates that itself
			}
			Link::Get().PushInput(proto::kInUse, 0);
			logger::info("Skyrim mode: Activate on no Skyrim target; using the Minecraft block in view");
		}

		void OpenWaitMenu()
		{
			auto* player = RE::PlayerCharacter::GetSingleton();
			if (!player || !player->CanSleepWait(nullptr)) {
				return;  // CanSleepWait already showed Skyrim's reason (enemies nearby, ...)
			}
			if (auto* queue = RE::UIMessageQueue::GetSingleton()) {
				queue->AddMessage(RE::SleepWaitMenu::MENU_NAME, RE::UI_MESSAGE_TYPE::kShow, nullptr);
			}
		}

		class InputSink final : public RE::BSTEventSink<RE::InputEvent*>
		{
		public:
			static InputSink* Get()
			{
				static InputSink sink;
				return &sink;
			}

			RE::BSEventNotifyControl ProcessEvent(RE::InputEvent* const* a_event, RE::BSTEventSource<RE::InputEvent*>*) override
			{
				if (!a_event) {
					return RE::BSEventNotifyControl::kContinue;
				}
				auto& st = State();
				auto& link = Link::Get();
				// Menus that pause the game stop the per-frame update, so check them here.
				const bool menuOpen = Game::SkyrimMenuOpen();
				if (menuOpen && !st.skyrimMenuOpen) {
					Input::ReleaseAll();
				}
				if (menuOpen) {
					st.skyrimMenuOpen = true;
				}
				// A Minecraft screen opened from Skyrim mode (a chest, a crafting table) takes the input
				// like it does in Minecraft mode.
				const bool route = (st.puppeting || (st.skyrimDrives && st.mcScreenOpen)) && !menuOpen;

				for (auto* e = *a_event; e; e = e->next) {
					if (st.skyrimMode && st.skyrimDrives && !menuOpen && !st.mcScreenOpen && IsActivatePress(e)) {
						UseMinecraftBlockIfNothingElse();
					}
					// The mode switch key works in both modes (Skyrim mode doesn't route), never
					// reaches Minecraft, and waits while a menu or Minecraft screen is open.
					if (modeToggleKey && e->GetEventType() == RE::INPUT_EVENT_TYPE::kButton && e->GetDevice() == RE::INPUT_DEVICE::kKeyboard &&
						e->AsButtonEvent()->GetIDCode() == modeToggleKey) {
						if (e->AsButtonEvent()->IsDown() && !menuOpen && !st.mcScreenOpen) {
							Game::ToggleSkyrimMode();
						}
						continue;
					}
					switch (e->GetEventType()) {
					case RE::INPUT_EVENT_TYPE::kMouseMove:
						{
							if (!route) {
								break;
							}
							auto* mm = e->AsMouseMoveEvent();
							if (st.mcScreenOpen) {
								const int x = std::clamp(st.cursorX.load() + mm->mouseInputX, 0, st.viewportW.load() - 1);
								const int y = std::clamp(st.cursorY.load() + mm->mouseInputY, 0, st.viewportH.load() - 1);
								st.cursorX = x;
								st.cursorY = y;
								link.PushInput(proto::kInCursor, 0, x, y);
							} else {
								lookDx += static_cast<float>(mm->mouseInputX);
								lookDy += static_cast<float>(mm->mouseInputY);
							}
							break;
						}
					case RE::INPUT_EVENT_TYPE::kButton:
						{
							auto* button = e->AsButtonEvent();
							const bool down = button->IsDown();
							const bool up = button->IsUp();
							if (!route || (!down && !up)) {
								break;
							}
							const auto code = button->GetIDCode();
							if (button->GetDevice() == RE::INPUT_DEVICE::kKeyboard) {
								// With a Minecraft screen up (chat, inventory, options) every key is
								// Minecraft's, so typing works and Esc closes the screen.
								if (!st.mcScreenOpen) {
									if (IsSkyrimMenuKey(code)) {
										break;  // MenuControls (see the hook below) opens Skyrim's menu
									}
									if (code == kDikG || code == kDikH || code == kDikO) {
										if (down) {
											if (code == kDikG) {
												ActivateSkyrimTarget();
											} else if (code == kDikH) {
												OpenWaitMenu();
											} else {
												Input::ReleaseAll();
												link.PushInput(proto::kInOpenMenu, 0);
											}
										}
										break;
									}
									// SkyCraft+ : Skyrim's Shout key (Z, or whatever it's bound to) shouts in
									// Skyrim (PlayerControlsHook lets it through).
									if (auto* events = RE::UserEvents::GetSingleton(); events && button->QUserEvent() == events->shout) {
										break;
									}
								}
								if (const auto sdl = kDikToSdl[code & 0xFF]) {
									link.PushInput(proto::kInKey, sdl, down ? 1 : 0);
								}
							} else if (button->GetDevice() == RE::INPUT_DEVICE::kMouse) {
								if (code == 8 || code == 9) {
									if (down) {
										link.PushInput(proto::kInScroll, 0, code == 8 ? 120 : -120);
									}
									break;
								}
								// A hand Skyrim holds: its button is Skyrim's (PlayerControlsHook lets it through),
								// except right click on something Minecraft uses by hand (a chest, a villager).
								if (!st.mcScreenOpen && ((code == 0 && st.rightHandSkyrim) || (code == 1 && st.leftHandSkyrim && !RightClickToMinecraft(button)))) {
									break;
								}
								static constexpr std::uint16_t kButtons[8] = { 1, 3, 2, 4, 5, 0, 0, 0 };
								const std::uint16_t sdlButton = code < 8 ? kButtons[code] : std::uint16_t(0);
								if (sdlButton) {
									link.PushInput(proto::kInMouseButton, sdlButton, down ? 1 : 0);
								}
							}
							break;
						}
					case RE::INPUT_EVENT_TYPE::kChar:
						if (route && st.mcScreenOpen) {
							link.PushInput(proto::kInText, 0, static_cast<std::int32_t>(e->AsCharEvent()->keyCode));
						}
						break;
					default:
						break;
					}
				}
				return RE::BSEventNotifyControl::kContinue;
			}
		};

		// MenuControls opens Skyrim's menus (Tab, I, J, M, Wait, quicksave, ...). While Minecraft
		// drives the player only Esc, the console, M (map), J (journal), F9 (quickload) and the
		// keys in sSkyrimMenuKeys reach it (F5 is Minecraft's camera); while an MC screen is open
		// nothing does.
		struct MenuControlsHook
		{
			static RE::BSEventNotifyControl thunk(RE::MenuControls* a_this, RE::InputEvent* const* a_event, RE::BSTEventSource<RE::InputEvent*>* a_source)
			{
				auto& st = State();
				const bool mcScreenInSkyrimMode = st.skyrimDrives && st.mcScreenOpen;
				if (!a_event || !*a_event || !(st.puppeting || mcScreenInSkyrimMode) || st.skyrimMenuOpen || Game::SkyrimMenuOpen()) {
					return func(a_this, a_event, a_source);  // a Skyrim menu is up: all its input
				}
				std::vector<RE::InputEvent*> keep;
				std::vector<RE::InputEvent*> all;
				for (auto* e = *a_event; e; e = e->next) {
					all.push_back(e);
					if (st.mcScreenOpen) {
						continue;
					}
					if (e->GetEventType() == RE::INPUT_EVENT_TYPE::kButton && e->GetDevice() == RE::INPUT_DEVICE::kKeyboard) {
						if (IsSkyrimMenuKey(e->AsButtonEvent()->GetIDCode())) {
							keep.push_back(e);
						}
					}
				}
				if (keep.empty()) {
					return RE::BSEventNotifyControl::kContinue;
				}
				std::vector<RE::InputEvent*> savedNext;
				for (auto* e : all) {
					savedNext.push_back(e->next);
				}
				for (std::size_t i = 0; i < keep.size(); ++i) {
					keep[i]->next = i + 1 < keep.size() ? keep[i + 1] : nullptr;
				}
				RE::InputEvent* head = keep.front();
				const auto      result = func(a_this, &head, a_source);
				for (std::size_t i = 0; i < all.size(); ++i) {
					all[i]->next = savedNext[i];
				}
				return result;
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};

		// PlayerControls turns input into the Skyrim player's own actions: moving, looking,
		// attacking, jumping, sneaking, the camera. While Minecraft drives the player it gets none.
		// (Disabling Skyrim's player controls instead would also stop its NPCs fighting the player.)
		struct PlayerControlsHook
		{
			static RE::BSEventNotifyControl thunk(RE::PlayerControls* a_this, RE::InputEvent* const* a_event, RE::BSTEventSource<RE::InputEvent*>* a_source)
			{
				auto& st = State();
				const bool mcScreenInSkyrimMode = st.skyrimDrives && st.mcScreenOpen;
				if ((!st.minecraftOwnsPlayer && !mcScreenInSkyrimMode) || Game::SkyrimMenuOpen()) {
					return func(a_this, a_event, a_source);
				}
				// SkyCraft+ : hand ownership. A hand Skyrim holds still attacks, blocks and casts in
				// Skyrim: its Attack/Block event gets through (Minecraft never sees that button), unless
				// right click went to something Minecraft uses by hand. Shouts always get through.
				auto* events = RE::UserEvents::GetSingleton();
				if (!a_event || !*a_event || !events || st.mcScreenOpen) {
					return RE::BSEventNotifyControl::kContinue;
				}
				std::vector<RE::InputEvent*> keep;
				std::vector<RE::InputEvent*> all;
				for (auto* e = *a_event; e; e = e->next) {
					all.push_back(e);
					if (e->GetEventType() == RE::INPUT_EVENT_TYPE::kButton) {
						const auto& name = e->QUserEvent();
						if ((st.rightHandSkyrim && name == events->rightAttack) || name == events->shout ||
							(st.leftHandSkyrim && name == events->leftAttack && !RightClickToMinecraft(e->AsButtonEvent()))) {
							keep.push_back(e);
						}
					}
				}
				if (keep.empty()) {
					return RE::BSEventNotifyControl::kContinue;
				}
				std::vector<RE::InputEvent*> savedNext;
				for (auto* e : all) {
					savedNext.push_back(e->next);
				}
				for (std::size_t i = 0; i < keep.size(); ++i) {
					keep[i]->next = i + 1 < keep.size() ? keep[i + 1] : nullptr;
				}
				RE::InputEvent* head = keep.front();
				const auto      result = func(a_this, &head, a_source);
				for (std::size_t i = 0; i < all.size(); ++i) {
					all[i]->next = savedNext[i];
				}
				return result;
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};
	}

	namespace Input
	{
		void Install()
		{
			LoadControls();
			if (auto* devices = RE::BSInputDeviceManager::GetSingleton()) {
				devices->AddEventSink(InputSink::Get());
			}
			REL::Relocation<std::uintptr_t> vtbl{ RE::VTABLE_MenuControls[0] };
			MenuControlsHook::func = vtbl.write_vfunc(0x1, MenuControlsHook::thunk);
			REL::Relocation<std::uintptr_t> playerVtbl{ RE::VTABLE_PlayerControls[0] };
			PlayerControlsHook::func = playerVtbl.write_vfunc(0x1, PlayerControlsHook::thunk);
			logger::info("input hooks installed");
		}

		void ConsumeLook(float& a_dx, float& a_dy)
		{
			a_dx = lookDx;
			a_dy = lookDy;
			lookDx = lookDy = 0.0f;
		}

		void ReleaseAll()
		{
			Link::Get().PushInput(proto::kInReleaseAll, 0);
		}

		void SetActivatePromptKey(bool a_minecraftControls)
		{
			static std::uint16_t savedKey = 0xFFFF;
			auto*                controls = RE::ControlMap::GetSingleton();
			auto*                context = controls ? controls->controlMap[RE::UserEvents::INPUT_CONTEXT_ID::kGameplay] : nullptr;
			if (!context) {
				return;
			}
			for (auto& mapping : context->deviceMappings[RE::INPUT_DEVICE::kKeyboard]) {
				if (mapping.eventID != "Activate") {
					continue;
				}
				if (a_minecraftControls && savedKey == 0xFFFF) {
					savedKey = mapping.inputKey;
					mapping.inputKey = static_cast<std::uint16_t>(kDikG);
				} else if (!a_minecraftControls && savedKey != 0xFFFF) {
					mapping.inputKey = savedKey;
					savedKey = 0xFFFF;
				}
			}
		}
	}
}
