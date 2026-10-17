// SkyCraft+ : Minecraft loot chests in Skyrim's dungeons
#include "Game.h"

// In a dungeon (cave, barrow, camp, fort, ruin...) Skyrim works out where Minecraft's loot chests
// go and Minecraft places each once per Minecraft world: one beside the dungeon's boss chest (the
// end) and one partway in (the middle), each on its navmesh along a wall, facing into the room. The
// tier (what's in them) comes from the dungeon's level: Skyrim's encounter zone, as its own loot.
namespace skycraft::LootSpots
{
	namespace
	{
		float timer = 0.0f;
		int   logged = 0;

		// Places with enemies to clear (Skyrim's location keywords).
		constexpr std::string_view kLootable[] = {
			"LocTypeDungeon", "LocTypeClearable", "LocTypeBanditCamp", "LocTypeDraugrCrypt", "LocTypeFalmerHive", "LocTypeDwarvenAutomatons",
			"LocTypeHagravenNest", "LocTypeForswornCamp", "LocTypeGiantCamp", "LocTypeMilitaryFort", "LocTypeVampireLair", "LocTypeWarlockLair",
			"LocTypeDragonPriestLair", "LocTypeSprigganGrove", "LocTypeAnimalDen", "LocTypeShipwreck", "LocTypeMine",
		};
		// Never in towns or anyone's home, even ones inside a dungeon's location.
		constexpr std::string_view kNever[] = {
			"LocTypeCity", "LocTypeTown", "LocTypeSettlement", "LocTypeDwelling", "LocTypeHouse", "LocTypePlayerHouse", "LocTypeInn", "LocTypeStore",
			"LocTypeHabitation", "LocTypeTemple", "LocTypeJail", "LocTypeCastle", "LocTypeGuild",
		};

		bool Lootable(const RE::BGSLocation* a_location)
		{
			if (!a_location) {
				return false;
			}
			for (auto* at = a_location; at; at = at->parentLoc) {
				if (std::ranges::any_of(kNever, [&](std::string_view a_keyword) { return at->HasKeywordString(a_keyword); })) {
					return false;
				}
			}
			return std::ranges::any_of(kLootable, [&](std::string_view a_keyword) { return a_location->HasKeywordString(a_keyword); });
		}

		// "plugin:id" (made in game: "~:formid").
		std::string KeyOf(const RE::TESForm* a_form)
		{
			auto* file = (a_form->GetFormID() >> 24) != 0xFF ? a_form->GetFile(0) : nullptr;
			if (!file) {
				return std::format("~:{:08X}", a_form->GetFormID());
			}
			const auto id = a_form->GetFormID() & (file->IsLight() ? 0xFFFu : 0xFFFFFFu);
			return std::format("{}:{:X}", file->GetFilename(), id);
		}

		bool IsBossContainer(RE::TESObjectREFR* a_ref)
		{
			auto* base = a_ref ? a_ref->GetBaseObject() : nullptr;
			if (!base || !base->Is(RE::FormType::Container) || a_ref->IsDisabled() || a_ref->IsDeleted()) {
				return false;
			}
			auto* type = a_ref->extraList.GetByType<RE::ExtraLocationRefType>();
			const char* id = type && type->locRefType ? type->locRefType->GetFormEditorID() : nullptr;
			return id && std::string_view(id) == "BossContainer";
		}

		// The dungeon's level: the encounter zone's, fixed when first entered (else the player's,
		// within its bounds), as Skyrim levels its own loot.
		int LevelOf(RE::PlayerCharacter* a_player, const RE::BGSEncounterZone* a_zone)
		{
			const int player = a_player->GetLevel();
			if (!a_zone) {
				return player;
			}
			if (a_zone->gameData.zoneLevel > 0) {
				return a_zone->gameData.zoneLevel;
			}
			int level = std::max<int>(player, a_zone->data.minLevel);
			if (a_zone->data.maxLevel > 0) {
				level = std::min<int>(level, a_zone->data.maxLevel);
			}
			return level;
		}

		std::uint8_t TierOf(int a_level, const RE::BGSLocation* a_location)
		{
			int tier = a_level <= 8 ? 1 : a_level <= 16 ? 2 : a_level <= 26 ? 3 : a_level <= 38 ? 4 : 5;
			if (a_location && a_location->HasKeywordString("LocTypeDragonPriestLair")) {
				++tier;
			}
			return static_cast<std::uint8_t>(std::clamp(tier, 1, 5));
		}

		// Where a chest can stand: beside a wall on the navmesh, facing into the room.
		struct Place
		{
			RE::NiPoint3 at;
			RE::NiPoint3 inward;
		};

		std::vector<Place> PlacesIn(const RE::TESObjectCELL* a_cell)
		{
			std::vector<Place> places;
			auto* navMeshes = a_cell ? a_cell->GetRuntimeData().navMeshes : nullptr;
			if (!navMeshes) {
				return places;
			}
			for (const auto& navMesh : navMeshes->navMeshes) {
				if (!navMesh) {
					continue;
				}
				const auto& vertices = navMesh->vertices;
				for (const auto& triangle : navMesh->triangles) {
					using Flag = RE::BSNavmeshTriangle::TriangleFlag;
					if (triangle.triangleFlags.any(Flag::kDeleted)) {
						continue;
					}
					if (triangle.vertices[0] >= vertices.size() || triangle.vertices[1] >= vertices.size() || triangle.vertices[2] >= vertices.size()) {
						continue;
					}
					const RE::NiPoint3 corner[3] = { vertices[triangle.vertices[0]].location, vertices[triangle.vertices[1]].location, vertices[triangle.vertices[2]].location };
					const RE::NiPoint3 middle = (corner[0] + corner[1] + corner[2]) / 3.0f;
					constexpr Flag kLink[3] = { Flag::kEdge0_Link, Flag::kEdge1_Link, Flag::kEdge2_Link };
					for (int e = 0; e < 3; ++e) {
						// An edge with nothing beyond it: a wall (or a drop).
						if (triangle.triangles[e] != 0xFFFF || triangle.triangleFlags.any(kLink[e])) {
							continue;
						}
						const RE::NiPoint3& a = corner[e];
						const RE::NiPoint3& b = corner[(e + 1) % 3];
						RE::NiPoint3        along = b - a;
						along.z = 0.0f;
						if (along.Length() < 80.0f || std::fabs(b.z - a.z) > 30.0f) {
							continue;  // too short for a chest, or a slope
						}
						const RE::NiPoint3 edge = (a + b) / 2.0f;
						RE::NiPoint3       inward = middle - edge;
						inward.z = 0.0f;
						const float length = inward.Length();
						if (length < 20.0f) {
							continue;  // a sliver
						}
						inward /= length;
						places.push_back({ edge + inward * 45.0f, inward });
					}
				}
			}
			return places;
		}

		// The place nearest wanted away from everything in avoid (doors, the boss chest, other chests).
		const Place* Pick(const std::vector<Place>& a_places, const RE::NiPoint3& a_wanted, float a_near, float a_far,
			const std::vector<std::pair<RE::NiPoint3, float>>& a_avoid)
		{
			const Place* best = nullptr;
			float        bestScore = std::numeric_limits<float>::max();
			for (const auto& place : a_places) {
				const float distance = place.at.GetDistance(a_wanted);
				if (distance < a_near || distance > a_far) {
					continue;
				}
				if (std::ranges::any_of(a_avoid, [&](const auto& a_keep) { return place.at.GetDistance(a_keep.first) < a_keep.second; })) {
					continue;
				}
				const float score = std::fabs(distance - a_near);
				if (score < bestScore) {
					bestScore = score;
					best = &place;
				}
			}
			return best;
		}

		proto::LootSpot Spot(const Place& a_place, std::uint32_t a_world, std::uint8_t a_tier, std::uint8_t a_kind, const std::string& a_key, const std::string& a_location)
		{
			proto::LootSpot spot{};
			const auto      mc = SkyToMc(a_place.at);
			spot.x = mc.x;
			spot.y = mc.y;
			spot.z = mc.z;
			spot.yaw = std::fmod(HeadingToMcYaw(std::atan2(a_place.inward.x, a_place.inward.y)) + 360.0f, 360.0f);
			spot.worldId = a_world;
			spot.tier = a_tier;
			spot.kind = a_kind;
			std::snprintf(spot.key, sizeof(spot.key), "%s", a_key.c_str());
			std::snprintf(spot.location, sizeof(spot.location), "%s", a_location.c_str());
			return spot;
		}

		std::uint32_t WorldOf(RE::PlayerCharacter* a_player, RE::TESObjectCELL* a_cell)
		{
			if (a_cell->IsInteriorCell()) {
				return a_cell->GetFormID();
			}
			auto* world = a_player->GetWorldspace();
			return world ? world->GetFormID() : 0;
		}

		void Work(RE::PlayerCharacter* a_player, std::vector<proto::LootSpot>& a_out)
		{
			auto* cell = a_player->GetParentCell();
			auto* location = a_player->GetCurrentLocation();
			if (!cell || !Lootable(location)) {
				return;
			}
			const std::string locationKey = KeyOf(location);
			const std::uint32_t world = WorldOf(a_player, cell);
			// Boss chests: in this interior, or (outside) in the camp or fort around the player.
			std::vector<RE::TESObjectREFR*> bosses;
			std::vector<RE::NiPoint3>       doors;
			auto look = [&](RE::TESObjectREFR* a_ref) {
				if (IsBossContainer(a_ref)) {
					bosses.push_back(a_ref);
				} else if (a_ref && a_ref->extraList.GetByType<RE::ExtraTeleport>()) {
					doors.push_back(a_ref->GetPosition());
				}
				return RE::BSContainer::ForEachResult::kContinue;
			};
			if (cell->IsInteriorCell()) {
				cell->ForEachReference(look);
			} else if (auto* tes = RE::TES::GetSingleton()) {
				tes->ForEachReferenceInRange(a_player, 6000.0f, look);
			}
			auto* zone = cell->GetRuntimeData().loadedData ? cell->GetRuntimeData().loadedData->encounterZone : nullptr;
			std::vector<std::pair<RE::NiPoint3, float>> avoid;
			for (const auto& door : doors) {
				avoid.emplace_back(door, 250.0f);
			}
			for (auto* boss : bosses) {
				avoid.emplace_back(boss->GetPosition(), 60.0f);
			}
			// The end: beside each boss chest (on the navmesh of the cell it's in).
			for (auto* boss : bosses) {
				const auto places = PlacesIn(boss->GetParentCell());
				const Place* place = Pick(places, boss->GetPosition(), 90.0f, 400.0f, avoid);
				if (!place) {
					continue;
				}
				const int level = LevelOf(a_player, boss->GetEncounterZone() ? boss->GetEncounterZone() : zone);
				const auto tier = static_cast<std::uint8_t>(std::min(5, TierOf(level, location) + 1));
				a_out.push_back(Spot(*place, world, tier, proto::kLootEnd, KeyOf(boss) + ":end", locationKey));
				avoid.emplace_back(place->at, 140.0f);
			}
			// The middle: inside only. Halfway from the way in to the boss chest, or (no boss chest in
			// this part) in the middle of this part, away from its doors.
			if (!cell->IsInteriorCell() || a_out.size() >= proto::kMaxLootSpots) {
				return;
			}
			const auto places = PlacesIn(cell);
			if (places.empty()) {
				return;
			}
			RE::NiPoint3 wanted{};
			if (!bosses.empty() && !doors.empty()) {
				const RE::NiPoint3 boss = bosses.front()->GetPosition();
				const RE::NiPoint3* nearest = &doors.front();
				for (const auto& door : doors) {
					if (door.GetDistance(boss) > nearest->GetDistance(boss)) {
						nearest = &door;  // the way in: the door furthest from the boss
					}
				}
				wanted = (*nearest + boss) / 2.0f;
				for (auto* b : bosses) {
					avoid.emplace_back(b->GetPosition(), 500.0f);
				}
			} else if (bosses.empty()) {
				for (const auto& place : places) {
					wanted += place.at;
				}
				wanted /= static_cast<float>(places.size());
			} else {
				return;  // a boss chest but no door: nothing to be halfway along
			}
			if (const Place* place = Pick(places, wanted, 0.0f, 6000.0f, avoid)) {
				const auto tier = TierOf(LevelOf(a_player, zone), location);
				a_out.push_back(Spot(*place, world, tier, proto::kLootMiddle, KeyOf(cell) + ":middle", locationKey));
			}
		}
	}

	void PerFrame(RE::PlayerCharacter* a_player, float a_delta)
	{
		timer -= a_delta;
		if (timer > 0.0f) {
			return;
		}
		timer = 2.0f;
		std::vector<proto::LootSpot> spots;
		if (State().lootChestsOption && !State().parked) {
			Work(a_player, spots);
		}
		static std::size_t lastCount = SIZE_MAX;
		static std::string lastFirst;
		const std::string first = spots.empty() ? std::string() : std::string(spots.front().key);
		if ((spots.size() != lastCount || first != lastFirst) && logged < 60) {
			++logged;
			for (const auto& spot : spots) {
				logger::info("loot chests: {} ({}) tier {} at ({:.1f}, {:.1f}, {:.1f}) facing {:.0f}, in {}", spot.key, spot.kind == proto::kLootEnd ? "end" : "middle",
					spot.tier, spot.x, spot.y, spot.z, spot.yaw, spot.location);
			}
		}
		lastCount = spots.size();
		lastFirst = first;
		Link::Get().WriteLootSpots(spots.data(), static_cast<std::uint32_t>(spots.size()));
	}
}
