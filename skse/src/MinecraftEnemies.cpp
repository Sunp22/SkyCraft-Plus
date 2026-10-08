// SkyCraft+ : Skyrim enemies in Minecraft's own worlds
#include "Game.h"

#include <random>

// While Minecraft's player is in the Nether, or out among the End's outer islands (its cities),
// Skyrim's player waits in an empty cell following it (Game.cpp, MinecraftWorlds), and Skyrim's
// enemies are brought into that cell around them: Dremora and flame atronachs in the Nether; frost
// atronachs, wispmothers and Dwarven centurions in the End. Skyrim's leveled lists pick them to the
// player's level. They stand on Minecraft's blocks (DigPhysics adds them as surfaces) and fight as
// usual, but the cell has no navmesh, so Skyrim's AI can't walk them anywhere: one that's fighting
// and out of reach is stepped towards the player here. Leaving that world clears them all (bodies
// too), and any left in the cell from a save.
namespace skycraft::MinecraftEnemies
{
	namespace
	{
		constexpr float kMinBlocks = 12.0f;       // spawned out of arm's reach...
		constexpr float kMaxBlocks = 26.0f;       // ...but where the player will meet them
		constexpr float kForgetBlocks = 48.0f;    // left this far behind: brought round again
		constexpr float kEndCityBlocks = 900.0f;  // the End's outer islands start about here from its centre
		constexpr float kStepBlocks = 4.0f;       // blocks a second when stepped towards the player
		constexpr float kReachBlocks = 2.2f;      // close enough to swing
		constexpr float kRangedBlocks = 18.0f;    // casters and archers fight from here

		struct Spawned
		{
			RE::ObjectRefHandle handle;
			RE::NiPoint3        last;
			float               still = 0.0f;  // seconds it hasn't moved by itself
			float               age = 0.0f;    // seconds since it was brought in (for one look at how it shows)
			float               callOut = 0.0f;  // seconds until it's set on the player again, if it isn't fighting
			double              floor = 0.0;     // Minecraft y it last stood at, alive
			float               deadFor = 0.0f;
			bool                laidDown = false;  // its body held where it fell
		};
		std::vector<Spawned> spawned;
		float                timer = 8.0f;
		bool                 wasThere = false;
		std::mt19937         rng{ std::random_device{}() };

		float Uniform(float a_lo, float a_hi) { return std::uniform_real_distribution<float>(a_lo, a_hi)(rng); }

		/**
		 * The actor a leveled list gives at the player's level (lists in lists followed). Placing the
		 * list itself left a broken half-actor, nameless and never shown, that crashed Skyrim later.
		 */
		RE::TESNPC* Resolve(RE::TESForm* a_form, std::uint16_t a_level, int a_depth = 0)
		{
			if (!a_form || a_depth > 8) {
				return nullptr;
			}
			if (auto* npc = a_form->As<RE::TESNPC>()) {
				return npc;
			}
			auto* list = a_form->As<RE::TESLevCharacter>();
			if (!list || list->entries.empty()) {
				return nullptr;
			}
			// Those at or under the player's level (the highest of them only, unless the list says all);
			// none: the lowest.
			std::uint16_t best = 0, lowest = 0xFFFF;
			for (const auto& e : list->entries) {
				lowest = std::min(lowest, e.level);
				if (e.level <= a_level) {
					best = std::max(best, e.level);
				}
			}
			const bool all = (list->llFlags & RE::TESLeveledList::kCalculateFromAllLevelsLTOrEqPCLevel) != 0;
			std::vector<RE::TESForm*> picks;
			for (const auto& e : list->entries) {
				const bool fits = best == 0 && lowest > a_level ? e.level == lowest : (all ? e.level <= a_level : e.level == best);
				if (fits && e.form) {
					picks.push_back(e.form);
				}
			}
			if (picks.empty()) {
				return nullptr;
			}
			return Resolve(picks[std::uniform_int_distribution<std::size_t>(0, picks.size() - 1)(rng)], a_level, a_depth + 1);
		}

		/**
		 * Out of the way for good, without Disable: in the waiting cell (no navmesh) Disable rebuilds
		 * an actor's movement controller half-made and Skyrim crashed (SkyrimSE+0x7964f2). Far under
		 * the cell instead, and deleted when the cell is let go.
		 */
		void PutAway(RE::TESObjectREFR* a_ref)
		{
			auto pos = a_ref->GetPosition();
			pos.z -= 60000.0f;
			a_ref->SetPosition(pos);
			a_ref->SetDelete(true);
		}

		/**
		 * The enemies in the waiting cell, alive or dead: ours, from now or from a save. Never a
		 * follower, a summon or anyone else friendly that came along with the player.
		 */
		void ClearCell(RE::PlayerCharacter* a_player)
		{
			std::vector<RE::TESObjectREFR*> gone;
			if (auto* cell = a_player->GetParentCell()) {
				cell->ForEachReference([&](RE::TESObjectREFR* a_ref) {
					auto* actor = a_ref ? a_ref->As<RE::Actor>() : nullptr;
					if (actor && actor != a_player && !actor->IsPlayerTeammate() && !actor->IsCommandedActor() &&
						(actor->IsDead() || actor->IsHostileToActor(a_player))) {
						gone.push_back(a_ref);
					}
					return RE::BSContainer::ForEachResult::kContinue;
				});
			}
			for (auto* ref : gone) {
				PutAway(ref);
			}
			if (!gone.empty()) {
				logger::info("Skyrim enemies: {} cleared from the waiting cell", gone.size());
			}
			spawned.clear();
		}

		/** Ground a body fits on near the player: a solid block, and three free above it. */
		bool FindSpot(double a_x, double a_y, double a_z, RE::NiPoint3& a_out)
		{
			for (int tries = 0; tries < 24; ++tries) {
				const float angle = Uniform(0.0f, 6.2831853f);
				const float distance = Uniform(kMinBlocks, kMaxBlocks);
				const int   x = int(std::floor(a_x + std::cos(angle) * distance));
				const int   z = int(std::floor(a_z + std::sin(angle) * distance));
				const int   top = int(std::floor(a_y)) + 8;
				for (int y = top; y >= top - 20; --y) {
					if (NpcBlocks::SolidAt(x, y - 1, z) && !NpcBlocks::BlocksBody(x, y, z) && !NpcBlocks::BlocksBody(x, y + 1, z) &&
						!NpcBlocks::BlocksBody(x, y + 2, z)) {
						a_out = McToSky(x + 0.5, y, z + 0.5);
						return true;
					}
				}
			}
			return false;
		}

		void Spawn(RE::PlayerCharacter* a_player, const std::vector<RE::FormID>& a_kinds, double a_x, double a_y, double a_z)
		{
			if (a_kinds.empty()) {
				return;
			}
			const auto id = a_kinds[std::uniform_int_distribution<std::size_t>(0, a_kinds.size() - 1)(rng)];
			auto*      base = Resolve(RE::TESForm::LookupByID(id), a_player->GetLevel());
			if (!base) {
				logger::warn("Skyrim enemies: no actor from form {:08X}", id);
				return;
			}
			RE::NiPoint3 at;
			if (!FindSpot(a_x, a_y, a_z, at)) {
				return;
			}
			auto ref = a_player->PlaceObjectAtMe(base, false);
			if (!ref) {
				return;
			}
			ref->SetPosition(at);
			const auto face = McToSky(a_x, a_y, a_z) - at;
			ref->data.angle.z = std::atan2(face.x, face.y);
			spawned.push_back({ ref->CreateRefHandle(), at, 0.0f, 0.0f });
			const auto mc = SkyToMc(at);
			auto* placed = ref->GetBaseObject();
			logger::info("Skyrim enemies: {} ({:08X}) at {:.0f} {:.0f} {:.0f}", placed ? placed->GetName() : "?", id, mc.x, mc.y, mc.z);
		}

		/**
		 * No navmesh here: Skyrim's AI plans no path, so a fighter out of reach stands still. Once it
		 * has stood still a moment, it's walked straight at the player over Minecraft's blocks (up a
		 * one-block step; not into walls).
		 */
		void Steer(RE::PlayerCharacter* a_player, Spawned& a_s, RE::Actor* a_actor, float a_delta)
		{
			const auto pos = a_actor->GetPosition();
			const float moved = pos.GetDistance(a_s.last);
			a_s.last = pos;
			if (!a_actor->IsInCombat()) {
				a_s.still = 0.0f;
				return;
			}
			const auto  here = SkyToMc(pos);
			const auto  goal = SkyToMc(a_player->GetPosition());
			const double dx = goal.x - here.x, dz = goal.z - here.z;
			const double distance = std::sqrt(dx * dx + dz * dz);
			auto shoots = [](RE::TESForm* a_held) {
				auto* weapon = a_held ? a_held->As<RE::TESObjectWEAP>() : nullptr;
				return a_held && (a_held->Is(RE::FormType::Spell) || (weapon && weapon->IsBow()));
			};
			const bool   ranged = shoots(a_actor->GetEquippedObject(false)) || shoots(a_actor->GetEquippedObject(true));
			const double keep = ranged ? kRangedBlocks : kReachBlocks;
			a_s.still = moved < 0.5f * float(proto::kUnitsPerBlock) * a_delta ? a_s.still + a_delta : 0.0f;  // under half a block a second
			if (distance <= keep || a_s.still < 0.6f || distance < 0.01) {
				return;
			}
			const double step = std::min<double>(kStepBlocks * a_delta, distance - keep);
			const double nx = here.x + dx / distance * step, nz = here.z + dz / distance * step;
			const int    bx = int(std::floor(nx)), bz = int(std::floor(nz)), by = int(std::floor(here.y + 0.05));
			double       ny = here.y;
			if (NpcBlocks::BlocksBody(bx, by, bz) || NpcBlocks::BlocksBody(bx, by + 1, bz)) {
				if (NpcBlocks::BlocksBody(bx, by + 1, bz) || NpcBlocks::BlocksBody(bx, by + 2, bz) || NpcBlocks::BlocksBody(bx, by + 3, bz)) {
					return;  // a wall: it waits there (the player can come to it, or it shoots)
				}
				ny = by + 1.0;  // a one-block step up
			}
			a_actor->SetPosition(McToSky(nx, ny, nz), true);
			a_s.last = a_actor->GetPosition();  // our step isn't its own moving
			a_actor->data.angle.z = std::atan2(float(dx), float(-dz));
		}
	}

	void PerFrame(RE::PlayerCharacter* a_player, float a_delta, bool a_parked, std::uint32_t a_world, double a_x, double a_y, double a_z)
	{
		auto&      st = State();
		const bool endCities = a_world == proto::kDimEnd && std::sqrt(a_x * a_x + a_z * a_z) > kEndCityBlocks;
		const bool here = a_parked && st.skyrimEnemiesOption && (a_world == proto::kDimNether || endCities);
		if (here != wasThere) {
			wasThere = here;
			// A clean cell: none left over from a save or an earlier visit (the dead stay lootable
			// until then).
			if (a_parked) {
				ClearCell(a_player);
			} else {
				spawned.clear();
			}
			timer = Uniform(4.0f, 10.0f);
		}
		if (!here || a_player->IsDead()) {
			return;
		}
		// Keep the living, near ones; the dead stay where they fell (lootable) until the player leaves.
		int alive = 0;
		const auto me = a_player->GetPosition();
		for (auto it = spawned.begin(); it != spawned.end();) {
			auto  ref = it->handle.get();
			auto* actor = ref ? ref->As<RE::Actor>() : nullptr;
			if (!actor || actor->IsDisabled()) {
				it = spawned.erase(it);
				continue;
			}
			if (actor->GetPosition().GetDistance(me) > kForgetBlocks * float(proto::kUnitsPerBlock) && !(actor->IsDead() && it->deadFor < 20.0f)) {
				// Left behind: the living come round again near the player (like a new one); the dead
				// stay where they fell.
				RE::NiPoint3 at;
				if (actor->IsDead() || !FindSpot(a_x, a_y, a_z, at)) {
					it = spawned.erase(it);
					continue;
				}
				actor->SetPosition(at, true);
				it->last = actor->GetPosition();
				it->still = 0.0f;
			}
			const float before = it->age;
			it->age += a_delta;
			if (before < 3.0f && it->age >= 3.0f) {
				auto* root = actor->Get3D();
				logger::info("Skyrim enemies: {} after 3 s: 3D {}, {} visible, {:.0f} blocks away", actor->GetName(), root ? "loaded" : "not loaded",
					root && !root->GetFlags().any(RE::NiAVObject::Flag::kHidden) ? "" : "not", actor->GetPosition().GetDistance(me) / float(proto::kUnitsPerBlock));
			}
			if (!actor->IsDead()) {
				++alive;
				it->floor = SkyToMc(actor->GetPosition()).y;
				// Nothing here lets Skyrim's AI notice the player (an empty cell, no navmesh): one that
				// isn't fighting stood where it came in. Set on the player, it comes (Steer).
				if (!actor->IsInCombat() && (it->callOut -= a_delta) <= 0.0f) {
					it->callOut = 2.0f;
					if (actor->GetPosition().GetDistance(me) < kForgetBlocks * float(proto::kUnitsPerBlock)) {
						actor->StartCombat(a_player);
					}
				}
				Steer(a_player, *it, actor, a_delta);
			} else if (it->deadFor < 20.0f) {
				// A body is a ragdoll, which Minecraft's blocks don't hold up (they hold up the living:
				// DigPhysics). Watched by its pelvis (the reference stays where it died while the body
				// falls): down by the floor it stood on, it's made keyframed; sunk under it anyway, the
				// whole body is lifted back (for a while: then it's left as it lies).
				const float before = it->deadFor;
				it->deadFor += a_delta;
				auto* root = actor->Get3D();
				RE::NiAVObject* pelvis = nullptr;
				if (root) {
					for (const char* bone : { "NPC Pelvis [Pelv]", "NPC COM [COM ]", "Pelvis", "NPC Root [Root]" }) {
						if ((pelvis = root->GetObjectByName(bone)) != nullptr) {
							break;
						}
					}
				}
				const double low = pelvis ? SkyToMc(pelvis->world.translate).y : SkyToMc(actor->GetPosition()).y;
				if (!it->laidDown && (low < it->floor + 0.35 || it->deadFor > 4.0f)) {
					it->laidDown = true;
					// Forced: unforced, the ragdoll went on falling.
					const bool held = root && root->SetMotionType(RE::hkpMotion::MotionType::kKeyframed, true, true, true);
					logger::info("Skyrim enemies: {} keyframed: {}", actor->GetName(), held ? "yes" : "no");
					logger::info("Skyrim enemies: {} down, held (pelvis {:.2f}, floor {:.2f}, {})", actor->GetName(), low, it->floor, pelvis ? "by its pelvis" : "no pelvis");
				}
				if (it->laidDown && low < it->floor - 0.25) {
					auto at = actor->GetPosition();
					at.z += float((it->floor + 0.1 - low) * proto::kUnitsPerBlock);
					actor->SetPosition(at, true);
				}
				if (before < 6.0f && it->deadFor >= 6.0f) {
					logger::info("Skyrim enemies: {}'s body 6 s on: pelvis {:.2f}, floor {:.2f}", actor->GetName(), low, it->floor);
				}
			}
			++it;
		}
		timer -= a_delta;
		if (timer <= 0.0f) {
			timer = st.enemySeconds * Uniform(0.6f, 1.4f);
			if (alive < st.enemyCap) {
				Spawn(a_player, a_world == proto::kDimNether ? st.netherEnemies : st.endEnemies, a_x, a_y, a_z);
			}
		}
	}
}

// Skyrim's projectiles only hit Skyrim's world: other people's arrows, bolts and fireballs flew
// through Minecraft's blocks (enemies in the Nether shot through its walls and floors). One that
// passes through a solid block between two frames stops there.
namespace skycraft::MinecraftEnemies
{
	namespace
	{
		std::unordered_map<RE::FormID, RE::NiPoint3> lastShotPos;
	}

	void StopShotsAtBlocks(RE::PlayerCharacter* a_player)
	{
		auto* manager = RE::Projectile::Manager::GetSingleton();
		if (!manager) {
			return;
		}
		std::vector<RE::NiPointer<RE::Projectile>> blocked;
		std::unordered_map<RE::FormID, RE::NiPoint3> seen;
		{
			RE::BSSpinLockGuard guard(manager->projectileLock);
			for (auto* list : { &manager->unlimited, &manager->limited }) {
				for (auto& handle : *list) {
					auto projectile = handle.get();
					if (!projectile || projectile->GetProjectileRuntimeData().shooter.get().get() == a_player) {
						continue;
					}
					const auto id = projectile->GetFormID();
					const auto pos = projectile->GetPosition();
					seen[id] = pos;
					const auto prev = lastShotPos.find(id);
					if (prev == lastShotPos.end()) {
						continue;
					}
					const auto   a = SkyToMc(prev->second), b = SkyToMc(pos);
					const double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
					const double length = std::sqrt(dx * dx + dy * dy + dz * dz);
					const int    steps = std::clamp(int(length * 4.0), 1, 400);
					for (int i = 1; i <= steps; ++i) {
						const double t = double(i) / steps;
						if (NpcBlocks::SolidAt(int(std::floor(a.x + dx * t)), int(std::floor(a.y + dy * t)), int(std::floor(a.z + dz * t)))) {
							blocked.push_back(projectile);
							break;
						}
					}
				}
			}
		}
		lastShotPos = std::move(seen);
		for (auto& projectile : blocked) {
			static int logged = 0;
			if (logged++ < 10) {
				logger::info("shots: {:X} stopped by a Minecraft block", projectile->GetFormID());
			}
			lastShotPos.erase(projectile->GetFormID());
			projectile->Kill();
		}
	}
}
