// SkyCraft+ : footsteps
#include "Game.h"

// Skyrim's footsteps come from its walk and run animations (each foot down: the boots' footstep
// sound for the ground under them, and a noise the AI hears). Minecraft moves the player instead,
// so there were none: silent to the player and to every enemy. Here a step is taken every so far
// Minecraft's player walks on the ground: the worn boots' (or bare feet's) Skyrim footstep for the
// ground's material, at its sound level, heard by Skyrim's AI as a footstep would be (so sneaking,
// Muffle and light boots matter again).
namespace skycraft::Footsteps
{
	namespace
	{
		double lastX = 0.0, lastZ = 0.0;
		bool   tracking = false;
		double travel = 0.0;
		bool   left = false;
		int    logged = 0;

		RE::BGSFootstepSet* FootstepSetOf(RE::TESObjectARMO* a_armor)
		{
			if (!a_armor) {
				return nullptr;
			}
			for (auto* addon : a_armor->armorAddons) {
				if (addon && addon->footstepSet) {
					return addon->footstepSet;
				}
			}
			return nullptr;
		}

		RE::BGSFootstep* StepOf(RE::BGSFootstepSet* a_set, std::uint32_t a_type, bool a_left)
		{
			for (std::uint32_t type : { a_type, std::uint32_t(RE::ACTOR_MOVEMENT_TYPES::kRun), std::uint32_t(RE::ACTOR_MOVEMENT_TYPES::kWalk) }) {
				const auto& entries = a_set->entries[type];
				RE::BGSFootstep* any = nullptr;
				for (auto* step : entries) {
					if (!step || !step->impactSet) {
						continue;
					}
					any = any ? any : step;
					const std::string_view tag = step->tag.c_str();
					if (tag == (a_left ? "FootLeft" : "FootRight")) {
						return step;
					}
				}
				if (any) {
					return any;
				}
			}
			return nullptr;
		}

		void Step(RE::PlayerCharacter* a_player, bool a_sneaking)
		{
			auto* set = FootstepSetOf(a_player->GetWornArmor(RE::BGSBipedObjectForm::BipedObjectSlot::kFeet));
			if (!set) {
				set = FootstepSetOf(a_player->GetSkin(RE::BGSBipedObjectForm::BipedObjectSlot::kFeet));
			}
			auto* controller = a_player->GetCharController();
			auto  material = controller ? controller->surfaceMaterial : RE::MATERIAL_ID::kNone;
			if (material == RE::MATERIAL_ID::kNone) {
				material = RE::MATERIAL_ID::kDirt;
			}
			left = !left;
			const RE::NiPoint3 at = a_player->GetPosition();
			RE::BGSImpactData* impact = nullptr;
			if (auto* step = set ? StepOf(set, a_sneaking ? RE::ACTOR_MOVEMENT_TYPES::kSneak : RE::ACTOR_MOVEMENT_TYPES::kRun, left) : nullptr) {
				if (auto* type = RE::BGSMaterialType::GetMaterialType(material)) {
					if (auto it = step->impactSet->impactMap.find(type); it != step->impactSet->impactMap.end()) {
						impact = it->second;
					}
				}
			}
			bool played = false;
			if (auto* descriptor = impact && impact->sound1 ? impact->sound1->soundDescriptor : nullptr) {  // (the sound itself, not its form)
				RE::BSSoundHandle sound;
				if (auto* audio = RE::BSAudioManager::GetSingleton(); audio && audio->GetSoundHandle(sound, descriptor, 0x10)) {
					sound.SetPosition(at);
					if (auto* body = a_player->Get3D(false)) {
						sound.SetObjectToFollow(body);
					}
					sound.SetVolume(1.0f);
					played = sound.Play();
				}
			}
			// The noise: the footstep's own sound level (sneaking: silent), as Muffle and the like scale it.
			const auto level = a_sneaking ? RE::SOUND_LEVEL::kSilent : impact ? impact->data.soundLevel : RE::SOUND_LEVEL::kNormal;
			const float noise = std::max(0.0f, a_player->AsActorValueOwner()->GetActorValue(RE::ActorValue::kMovementNoiseMult));
			const auto value = static_cast<std::int32_t>(static_cast<float>(RE::AIFormulas::GetSoundLevelValue(level)) * noise);
			if (auto* process = a_player->GetActorRuntimeData().currentProcess; process && value > 0) {
				process->SetActorsDetectionEvent(a_player, at, value, nullptr);
			}
			if (logged < 6) {
				logged++;
				logger::info("footsteps: {} on material {:X}: {}, noise {}", set ? std::format("{:08X}", set->GetFormID()) : std::string("(no footstep set)"), static_cast<std::uint32_t>(material),
					impact && impact->sound1 ? (played ? "sound played" : "sound didn't play") : "no sound", value);
			}
		}
	}

	void PerFrame(RE::PlayerCharacter* a_player, const proto::McState& a_mc, bool a_driving)
	{
		const bool walking = a_driving && State().minecraftFootsteps && (a_mc.flags & proto::kMcOnGround) &&
		                     !(a_mc.flags & (proto::kMcFlying | proto::kMcSwimming | proto::kMcDead | proto::kMcScreenOpen));
		if (!walking) {
			tracking = false;
			return;
		}
		if (!tracking) {
			tracking = true;
			lastX = a_mc.x, lastZ = a_mc.z;
			travel = 0.0;
			return;
		}
		const double dx = a_mc.x - lastX, dz = a_mc.z - lastZ;
		lastX = a_mc.x, lastZ = a_mc.z;
		const double moved = std::sqrt(dx * dx + dz * dz);
		if (moved > 2.0) {  // a teleport
			travel = 0.0;
			return;
		}
		travel += moved;
		const bool   sneaking = (a_mc.flags & proto::kMcSneaking) != 0;
		const double stride = sneaking ? 0.8 : (a_mc.flags & proto::kMcSprinting) ? 1.4 : 1.05;  // blocks a step
		if (travel >= stride) {
			travel = std::fmod(travel, stride);
			Step(a_player, sneaking);
		}
	}
}
