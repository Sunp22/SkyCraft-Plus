// SkyCraft+ : Minecraft's structures on Skyrim's map and compass
#include "Game.h"

#include <fstream>

// Minecraft's surface structures in Skyrim's open world (SkyStructures on the Minecraft side, which
// writes their spots to %LOCALAPPDATA%\SkyCraft\structures.txt) get map markers of their own: a
// map marker reference (the MapMarker static, as Skyrim's own locations use) placed in Tamriel's
// persistent cell with map marker data (name, Skyrim icon, seen, fast travel), and listed with the
// player's current map markers, which the world map and the compass draw. They're saved with the
// game like any placed reference; on each load the ones there are found again (by name) rather
// than made twice. Not marked: the woodland mansion, buried treasure (underground), anything
// underground.
namespace skycraft::MapMarkers
{
	namespace
	{
		constexpr RE::FormID kMapMarkerStatic = 0x10;  // MapMarker
		constexpr RE::FormID kTamriel = 0x3C;
		constexpr const char* kSuffix = " (Minecraft)";

		struct Spot
		{
			std::string      name;
			RE::MARKER_TYPE  icon;
			RE::NiPoint3     pos;
		};

		std::filesystem::file_time_type fileTime{};
		bool                            appliedThisGame = false;
		float                           timer = 3.0f;

		std::filesystem::path File()
		{
			wchar_t local[MAX_PATH]{};
			if (::ExpandEnvironmentStringsW(L"%LOCALAPPDATA%\\SkyCraft", local, MAX_PATH) == 0) {
				return {};
			}
			return std::filesystem::path(local) / "structures.txt";
		}

		/** Skyrim's icon for a structure, or kNone for one that isn't marked. */
		RE::MARKER_TYPE IconFor(const std::string& a_structure)
		{
			using M = RE::MARKER_TYPE;
			if (a_structure.find("village") != std::string::npos) {
				return M::kSettlement;
			}
			if (a_structure.find("pillager_outpost") != std::string::npos) {
				return M::kImperialTower;
			}
			if (a_structure.find("shipwreck") != std::string::npos) {
				return M::kShipwreck;
			}
			if (a_structure.find("pyramid") != std::string::npos || a_structure.find("trail_ruins") != std::string::npos ||
				a_structure.find("ocean_ruin") != std::string::npos) {
				return M::kNordicRuin;
			}
			if (a_structure.find("ruined_portal") != std::string::npos) {
				return M::kShrine;
			}
			if (a_structure.find("swamp_hut") != std::string::npos) {
				return M::kShack;
			}
			if (a_structure.find("igloo") != std::string::npos) {
				return M::kNordicDwelling;
			}
			if (a_structure.find("monument") != std::string::npos) {
				return M::kLandmark;
			}
			return M::kNone;  // the mansion, buried treasure, anything else
		}

		std::vector<Spot> Read(const std::filesystem::path& a_file)
		{
			std::vector<Spot> spots;
			std::ifstream     in(a_file);
			std::string       line;
			while (std::getline(in, line)) {
				std::vector<std::string> f;
				std::size_t              start = 0;
				for (std::size_t bar; (bar = line.find('|', start)) != std::string::npos; start = bar + 1) {
					f.push_back(line.substr(start, bar - start));
				}
				f.push_back(line.substr(start));
				if (f.size() != 5) {
					continue;
				}
				const auto icon = IconFor(f[1]);
				if (icon == RE::MARKER_TYPE::kNone) {
					continue;
				}
				// "Plains village 3" -> "Plains village (Minecraft)"
				std::string name = f[0];
				const auto  lastSpace = name.find_last_of(' ');
				if (lastSpace != std::string::npos && name.find_first_not_of("0123456789", lastSpace + 1) == std::string::npos) {
					name.erase(lastSpace);
				}
				const double x = std::strtod(f[2].c_str(), nullptr), y = std::strtod(f[3].c_str(), nullptr), z = std::strtod(f[4].c_str(), nullptr);
				spots.push_back({ name + kSuffix, icon, McToSky(x + 0.5, std::isfinite(y) ? y : 0.0, z + 0.5) });
			}
			return spots;
		}

		RE::MapMarkerData* MarkerOf(RE::TESObjectREFR* a_ref)
		{
			auto* extra = a_ref ? a_ref->extraList.GetByType<RE::ExtraMapMarker>() : nullptr;
			return extra ? extra->mapData : nullptr;
		}

		bool Ours(RE::TESObjectREFR* a_ref)
		{
			auto* data = MarkerOf(a_ref);
			const char* name = data ? data->locationName.fullName.c_str() : nullptr;
			const std::size_t n = name ? std::strlen(name) : 0, s = std::strlen(kSuffix);
			return n > s && std::strcmp(name + n - s, kSuffix) == 0;
		}

		std::uint64_t Key(const RE::NiPoint3& a_pos)
		{
			// Within a few blocks: the same spot.
			const auto qx = std::int64_t(std::floor(a_pos.x / 512.0f)), qy = std::int64_t(std::floor(a_pos.y / 512.0f));
			return (std::uint64_t(qx) << 32) ^ (std::uint64_t(qy) & 0xFFFFFFFFull);
		}

		RE::TESObjectREFR* Make(RE::PlayerCharacter* a_player, RE::TESBoundObject* a_base, RE::TESWorldSpace* a_tamriel, RE::TESObjectREFR* a_anchor,
			const Spot& a_spot)
		{
			auto placed = a_player->PlaceObjectAtMe(a_base, true);
			auto* ref = placed.get();
			if (!ref) {
				return nullptr;
			}
			// TESObjectREFR::MoveTo_Impl (SE 56227, AE 56626), private in CommonLib: into Tamriel's
			// persistent cell, at the structure.
			using MoveToFn = void (*)(RE::TESObjectREFR*, const RE::ObjectRefHandle&, RE::TESObjectCELL*, RE::TESWorldSpace*, const RE::NiPoint3&, const RE::NiPoint3&);
			static REL::Relocation<MoveToFn> moveTo{ RELOCATION_ID(56227, 56626) };
			const RE::ObjectRefHandle anchorHandle = a_anchor->CreateRefHandle();
			moveTo(ref, anchorHandle, a_anchor->GetParentCell(), a_tamriel, a_spot.pos, RE::NiPoint3());

			auto* data = static_cast<RE::MapMarkerData*>(RE::malloc(sizeof(RE::MapMarkerData)));
			std::memset(static_cast<void*>(data), 0, sizeof(RE::MapMarkerData));
			*reinterpret_cast<std::uintptr_t*>(&data->locationName) = RE::VTABLE_TESFullName[0].address();
			std::construct_at(&data->locationName.fullName, a_spot.name.c_str());
			data->flags.set(RE::MapMarkerData::Flag::kVisible, RE::MapMarkerData::Flag::kCanTravelTo);
			data->type = a_spot.icon;
			auto* extra = RE::BSExtraData::Create<RE::ExtraMapMarker>();
			extra->mapData = data;
			ref->extraList.Add(extra);
			return ref;
		}

		void Apply(RE::PlayerCharacter* a_player, const std::vector<Spot>& a_spots)
		{
			auto* tamriel = RE::TESForm::LookupByID<RE::TESWorldSpace>(kTamriel);
			auto* base = RE::TESForm::LookupByID<RE::TESBoundObject>(kMapMarkerStatic);
			if (!tamriel || !tamriel->persistentCell || !base) {
				return;
			}
			auto& markers = a_player->GetPlayerRuntimeData().currentMapMarkers;
			// Ours already made (listed, or saved in Tamriel's persistent cell and not listed yet).
			std::unordered_map<std::uint64_t, RE::TESObjectREFR*> have;
			for (auto& handle : markers) {
				auto ref = handle.get();
				if (ref && Ours(ref.get())) {
					have.emplace(Key(ref->GetPosition()), ref.get());
				}
			}
			RE::TESObjectREFR* anchor = nullptr;
			int                relisted = 0;
			std::vector<RE::TESObjectREFR*> bare;  // ours from a save that kept the reference but not its marker
			tamriel->persistentCell->ForEachReference([&](RE::TESObjectREFR* a_ref) {
				if (!a_ref || a_ref->GetWorldspace() != tamriel) {
					return RE::BSContainer::ForEachResult::kContinue;
				}
				if (!anchor && !Ours(a_ref)) {
					anchor = a_ref;
				}
				auto* refBase = a_ref->GetBaseObject();
				if (refBase && refBase->GetFormID() == kMapMarkerStatic && (a_ref->GetFormID() >> 24) == 0xFF && !MarkerOf(a_ref) && !a_ref->IsDisabled()) {
					bare.push_back(a_ref);
				}
				if (Ours(a_ref) && have.emplace(Key(a_ref->GetPosition()), a_ref).second) {
					markers.push_back(a_ref->CreateRefHandle());
					++relisted;
				}
				return RE::BSContainer::ForEachResult::kContinue;
			});
			for (auto* ref : bare) {
				ref->Disable();
				ref->SetDelete(true);
			}
			if (!anchor) {
				return;
			}
			int made = 0;
			for (const auto& spot : a_spots) {
				if (have.contains(Key(spot.pos))) {
					continue;
				}
				if (auto* ref = Make(a_player, base, tamriel, anchor, spot)) {
					markers.push_back(ref->CreateRefHandle());
					have.emplace(Key(spot.pos), ref);
					++made;
				}
			}
			logger::info("map markers: {} Minecraft structures marked ({} new, {} found again in the save, {} bare ones removed)", a_spots.size(), made, relisted, bare.size());
		}
	}

	void OnGameLoaded()
	{
		appliedThisGame = false;
		timer = 3.0f;
	}

	void PerFrame(RE::PlayerCharacter* a_player, float a_delta)
	{
		if (!State().structureMarkersOption || (timer -= a_delta) > 0.0f) {
			return;
		}
		timer = 5.0f;
		auto* ws = a_player->GetWorldspace();
		auto* ui = RE::UI::GetSingleton();
		if (!ws || ws->GetFormID() != kTamriel || !a_player->GetParentCell() || !a_player->Is3DLoaded() || !ui ||
			ui->IsMenuOpen(RE::LoadingMenu::MENU_NAME) || State().parked) {
			return;
		}
		const auto       file = File();
		std::error_code  ec;
		const auto       time = std::filesystem::last_write_time(file, ec);
		if (ec || (appliedThisGame && time == fileTime)) {
			return;
		}
		const auto spots = Read(file);
		if (spots.empty()) {
			return;
		}
		fileTime = time;
		appliedThisGame = true;
		Apply(a_player, spots);
	}
}
