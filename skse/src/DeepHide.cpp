// SkyCraft+ : performance: Skyrim's world isn't drawn deep underground
#include "Game.h"

// Deep down a mine (24 blocks and more under Skyrim's land, Minecraft tells) nothing of Skyrim's
// world can be seen: it was drawn anyway, every frame, and covered up by the blocks. Its placed
// objects (buildings, rocks, trees) and its distant land are hidden meanwhile and shown again on
// the way up. Characters stay (the player's own body, NPCs), and so does the land under the
// player: its dug-out holes are cut meshes of their own (DigMesh) that this mustn't get in the way of.
namespace skycraft::DeepHide
{
	namespace
	{
		std::vector<RE::NiPointer<RE::NiAVObject>> hidden;
		bool                                       active = false;
		float                                      sweepTimer = 0.0f;

		void Hide(RE::NiAVObject* a_obj)
		{
			if (!a_obj || a_obj->GetFlags().any(RE::NiAVObject::Flag::kHidden)) {
				return;  // already hidden (by Skyrim, or by us on an earlier sweep)
			}
			a_obj->GetFlags().set(RE::NiAVObject::Flag::kHidden);
			hidden.emplace_back(a_obj);
		}

		void Sweep(RE::PlayerCharacter* a_player)
		{
			auto* tes = RE::TES::GetSingleton();
			if (!tes || tes->interiorCell || !tes->gridCells) {
				return;
			}
			if (auto& lod = RE::BGSTerrainManager::GetLODRoot(); lod) {
				Hide(lod.get());
			}
			auto* grid = tes->gridCells;
			for (std::uint32_t x = 0; x < grid->length; ++x) {
				for (std::uint32_t y = 0; y < grid->length; ++y) {
					auto* cell = grid->GetCell(x, y);
					if (!cell || !cell->IsAttached()) {
						continue;
					}
					cell->ForEachReference([&](RE::TESObjectREFR* a_ref) {
						if (!a_ref || a_ref == a_player || a_ref->IsActor() || a_ref->IsDisabled() || !a_ref->Is3DLoaded()) {
							return RE::BSContainer::ForEachResult::kContinue;
						}
						// A node only: a reference that's one bare mesh may be cut by DigMesh, which hides
						// and shows that mesh itself.
						auto* root = a_ref->Get3D();
						if (root && root->AsNode()) {
							Hide(root);
						}
						return RE::BSContainer::ForEachResult::kContinue;
					});
				}
			}
		}

		void ShowAgain()
		{
			for (auto& obj : hidden) {
				obj->GetFlags().reset(RE::NiAVObject::Flag::kHidden);
			}
			hidden.clear();
		}
	}

	void PerFrame(RE::PlayerCharacter* a_player, bool a_deep, float a_delta)
	{
		a_deep = a_deep && State().hideWorldDeepOption;
		if (!a_deep) {
			if (active) {
				logger::info("deep underground: Skyrim's world shown again ({} objects)", hidden.size());
				ShowAgain();
				active = false;
			}
			return;
		}
		if (!active) {
			active = true;
			sweepTimer = 0.0f;
		}
		// Again now and then: cells and objects load in while the player moves underground.
		if ((sweepTimer -= a_delta) <= 0.0f) {
			sweepTimer = 1.0f;
			const auto before = hidden.size();
			Sweep(a_player);
			if (before == 0 && !hidden.empty()) {
				logger::info("deep underground: Skyrim's world not drawn ({} objects hidden)", hidden.size());
			}
		}
	}
}
