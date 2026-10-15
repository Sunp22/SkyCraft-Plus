// SkyCraft+ : Skyrim's inventory in Minecraft's
#include "Game.h"

// Skyrim's menus can't be open alongside Minecraft's inventory, so Minecraft draws Skyrim's items,
// spells, shouts and powers in screens of its own (SkyItemsScreen on the Minecraft side) from the
// list kept here in shared memory (proto::SkyInventory), and sends each click back (kEvSkyItem):
// equip in a hand, put on, drink, eat, read, make a shout or power the voice's. Equipping goes
// through ActorEquipManager as Skyrim's own menu does, so hand mixing and the rest follow as usual.
namespace skycraft::SkyInventory
{
	namespace
	{
		std::vector<proto::SkyItem> items;
		float                       timer = 0.0f;
		bool                        dirty = true;
		std::uint64_t               lastHash = 0;
		std::uint32_t               generation = 0;

		void SetName(proto::SkyItem& a_item, const char* a_name)
		{
			std::snprintf(a_item.name, sizeof(a_item.name), "%s", a_name ? a_name : "");
		}

		bool Contains(std::string_view a_text, std::string_view a_part)
		{
			return std::search(a_text.begin(), a_text.end(), a_part.begin(), a_part.end(), [](char a, char b) {
				return std::tolower(static_cast<unsigned char>(a)) == std::tolower(static_cast<unsigned char>(b));
			}) != a_text.end();
		}

		// What a weapon or piece of apparel is made of, from its material keyword (WeapMaterialIron,
		// ArmorMaterialDaedric, DLC2ArmorMaterialStalhrimHeavy...).
		std::uint8_t MaterialOf(RE::BGSKeywordForm* a_keywords)
		{
			if (!a_keywords) {
				return proto::kSkyMatNone;
			}
			static constexpr std::pair<std::string_view, proto::SkyMaterial> kMaterials[] = {
				{ "Daedric", proto::kSkyMatDaedric }, { "Dragon", proto::kSkyMatDragon }, { "Ebony", proto::kSkyMatEbony },
				{ "Stalhrim", proto::kSkyMatStalhrim }, { "Glass", proto::kSkyMatGlass }, { "Elven", proto::kSkyMatElven },
				{ "Dwarven", proto::kSkyMatDwarven }, { "Orcish", proto::kSkyMatOrcish }, { "Silver", proto::kSkyMatSilver },
				{ "Gold", proto::kSkyMatGold }, { "Steel", proto::kSkyMatSteel }, { "Imperial", proto::kSkyMatSteel },
				{ "Scaled", proto::kSkyMatSteel }, { "Iron", proto::kSkyMatIron }, { "Draugr", proto::kSkyMatIron },
				{ "Chitin", proto::kSkyMatElven }, { "Falmer", proto::kSkyMatElven }, { "Leather", proto::kSkyMatLeather },
				{ "Hide", proto::kSkyMatLeather }, { "Fur", proto::kSkyMatLeather }, { "Stormcloak", proto::kSkyMatLeather },
				{ "Forsworn", proto::kSkyMatWood }, { "Wood", proto::kSkyMatWood }, { "Chain", proto::kSkyMatChain },
			};
			for (std::uint32_t i = 0; i < a_keywords->numKeywords; ++i) {
				auto* keyword = a_keywords->keywords[i];
				const std::string_view id = keyword ? keyword->GetFormEditorID() : "";
				if (!Contains(id, "Material")) {
					continue;
				}
				for (const auto& [part, material] : kMaterials) {
					if (Contains(id, part)) {
						return material;
					}
				}
			}
			return proto::kSkyMatNone;
		}

		std::uint8_t WeaponType(RE::TESObjectWEAP* a_weapon)
		{
			using T = RE::WEAPON_TYPE;
			switch (a_weapon->GetWeaponType()) {
			case T::kOneHandSword:
				return proto::kSkySword;
			case T::kOneHandDagger:
				return proto::kSkyDagger;
			case T::kOneHandAxe:
				{
					const std::string_view name = a_weapon->GetName();
					if (Contains(name, "pickaxe") || Contains(name, "pick axe")) {
						return proto::kSkyPickaxe;
					}
					if (Contains(name, "woodcutter")) {
						return proto::kSkyWoodAxe;
					}
					return proto::kSkyWarAxe;
				}
			case T::kOneHandMace:
				return proto::kSkyMace;
			case T::kTwoHandSword:
				return proto::kSkyGreatsword;
			case T::kTwoHandAxe:
				return a_weapon->HasKeywordString("WeapTypeWarhammer") ? proto::kSkyWarhammer : proto::kSkyBattleaxe;
			case T::kBow:
				return proto::kSkyBow;
			case T::kCrossbow:
				return proto::kSkyCrossbow;
			case T::kStaff:
				return proto::kSkyStaff;
			default:
				return proto::kSkyHand;
			}
		}

		std::uint8_t ApparelSlot(RE::TESObjectARMO* a_armor)
		{
			using S = RE::BGSBipedObjectForm::BipedObjectSlot;
			const auto slots = a_armor->GetSlotMask();
			if (slots.any(S::kShield)) {
				return proto::kSkyShield;
			}
			if (slots.any(S::kBody)) {
				return proto::kSkyBody;
			}
			if (slots.any(S::kHead, S::kHair)) {
				return proto::kSkyHead;
			}
			if (slots.any(S::kCirclet)) {
				return proto::kSkyCirclet;
			}
			if (slots.any(S::kHands)) {
				return proto::kSkyHands;
			}
			if (slots.any(S::kFeet)) {
				return proto::kSkyFeet;
			}
			if (slots.any(S::kRing)) {
				return proto::kSkyRing;
			}
			if (slots.any(S::kAmulet)) {
				return proto::kSkyAmulet;
			}
			return proto::kSkyBody;
		}

		// The effect a spell, potion or scroll is mostly about.
		RE::Effect* MainEffect(RE::MagicItem* a_magic)
		{
			auto* effect = a_magic ? a_magic->GetCostliestEffectItem() : nullptr;
			return effect && effect->baseEffect ? effect : nullptr;
		}

		std::uint8_t ElementOf(RE::Effect* a_effect)
		{
			switch (a_effect ? a_effect->baseEffect->data.resistVariable : RE::ActorValue::kNone) {
			case RE::ActorValue::kResistFire:
				return proto::kSkyFire;
			case RE::ActorValue::kResistFrost:
				return proto::kSkyFrost;
			case RE::ActorValue::kResistShock:
				return proto::kSkyShock;
			default:
				return 0;
			}
		}

		void AddItem(RE::PlayerCharacter* a_player, RE::TESBoundObject* a_object, std::int32_t a_count, RE::InventoryEntryData* a_entry, std::int32_t& a_gold)
		{
			if (a_object->GetFormID() == 0xF) {  // Gold
				a_gold += a_count;
				return;
			}
			proto::SkyItem item{};
			item.formId = a_object->GetFormID();
			item.count = a_count;
			const char* name = a_entry ? a_entry->GetDisplayName() : nullptr;
			SetName(item, name && *name ? name : a_object->GetName());
			if (a_entry) {
				item.weight = a_entry->GetWeight();
				item.value = a_entry->GetValue();
				item.flags |= a_entry->IsEnchanted() ? proto::kSkyItemEnchanted : 0;
				item.flags |= a_entry->IsFavorited() ? proto::kSkyItemFavorite : 0;
				item.flags |= a_entry->IsQuestObject() ? proto::kSkyItemQuest : 0;
				item.flags |= !a_entry->IsOwnedBy(a_player, true) ? proto::kSkyItemStolen : 0;
			} else {
				item.weight = a_object->GetWeight();
				item.value = a_object->GetGoldValue();
			}
			const bool right = a_entry && a_entry->IsWorn(false);
			const bool left = a_entry && a_entry->IsWorn(true);

			switch (a_object->GetFormType()) {
			case RE::FormType::Weapon:
				{
					auto* weapon = a_object->As<RE::TESObjectWEAP>();
					item.kind = proto::kSkyWeapon;
					item.sub = WeaponType(weapon);
					item.material = MaterialOf(weapon);
					item.stat = static_cast<float>(weapon->GetAttackDamage());
					item.flags |= weapon->IsTwoHandedSword() || weapon->IsTwoHandedAxe() || weapon->IsBow() || weapon->IsCrossbow() ? proto::kSkyItemTwoHanded : 0;
					item.flags |= (right ? proto::kSkyItemRightHand : 0) | (left ? proto::kSkyItemLeftHand : 0);
					break;
				}
			case RE::FormType::Armor:
				{
					auto* armor = a_object->As<RE::TESObjectARMO>();
					item.kind = proto::kSkyApparel;
					item.sub = ApparelSlot(armor);
					item.material = MaterialOf(armor);
					item.stat = armor->GetArmorRating();
					item.flags |= armor->IsHeavyArmor() ? proto::kSkyItemHeavy : armor->IsLightArmor() ? proto::kSkyItemLight : 0;
					item.flags |= right || left ? proto::kSkyItemWorn : 0;
					break;
				}
			case RE::FormType::AlchemyItem:
				{
					auto* potion = a_object->As<RE::AlchemyItem>();
					auto* effect = MainEffect(potion);
					item.kind = potion->IsFood() ? proto::kSkyFood : proto::kSkyPotion;
					if (potion->IsPoison()) {
						item.sub = proto::kSkyPoison;
					} else if (effect) {
						switch (effect->baseEffect->data.primaryAV) {
						case RE::ActorValue::kHealth:
							item.sub = proto::kSkyPotionHealth;
							break;
						case RE::ActorValue::kMagicka:
							item.sub = proto::kSkyPotionMagicka;
							break;
						case RE::ActorValue::kStamina:
							item.sub = proto::kSkyPotionStamina;
							break;
						default:
							break;
						}
					}
					if (effect) {
						item.stat = effect->GetMagnitude();
						item.stat2 = static_cast<float>(effect->GetDuration());
					}
					break;
				}
			case RE::FormType::Ingredient:
				item.kind = proto::kSkyIngredient;
				break;
			case RE::FormType::Book:
				{
					auto* book = a_object->As<RE::TESObjectBOOK>();
					item.kind = proto::kSkyBook;
					item.sub = book->TeachesSpell() ? 1 : book->IsNoteScroll() ? 2 : 0;
					item.flags |= book->IsRead() ? proto::kSkyItemRead : 0;
					break;
				}
			case RE::FormType::KeyMaster:
				item.kind = proto::kSkyKey;
				break;
			case RE::FormType::SoulGem:
				item.kind = proto::kSkyMisc;
				item.sub = 1;
				item.flags |= a_entry && a_entry->GetSoulLevel() != RE::SOUL_LEVEL::kNone ? proto::kSkyItemFilled : 0;
				break;
			case RE::FormType::Light:
				item.kind = proto::kSkyMisc;
				item.sub = 2;
				item.flags |= (right ? proto::kSkyItemRightHand : 0) | (left ? proto::kSkyItemLeftHand : 0);
				break;
			case RE::FormType::Ammo:
				item.kind = proto::kSkyAmmo;
				item.sub = a_object->As<RE::TESAmmo>()->IsBolt() ? 1 : 0;
				item.stat = a_object->As<RE::TESAmmo>()->GetRuntimeData().data.damage;
				item.flags |= right || left ? proto::kSkyItemWorn : 0;
				break;
			case RE::FormType::Scroll:
				item.kind = proto::kSkyScroll;
				item.flags |= (right ? proto::kSkyItemRightHand : 0) | (left ? proto::kSkyItemLeftHand : 0);
				break;
			case RE::FormType::Misc:
				item.kind = proto::kSkyMisc;
				item.sub = a_object->GetFormID() == 0xA ? 3 : 0;  // Lockpick
				break;
			default:
				return;
			}
			items.push_back(item);
		}

		void AddSpell(RE::PlayerCharacter* a_player, RE::SpellItem* a_spell, std::unordered_set<RE::FormID>& a_seen)
		{
			if (!a_spell || !a_seen.insert(a_spell->GetFormID()).second) {
				return;
			}
			using T = RE::MagicSystem::SpellType;
			const auto type = a_spell->GetSpellType();
			if (type != T::kSpell && type != T::kPower && type != T::kLesserPower) {
				return;
			}
			proto::SkyItem item{};
			item.formId = a_spell->GetFormID();
			item.count = 1;
			SetName(item, a_spell->GetName());
			auto* effect = MainEffect(a_spell);
			if (type == T::kSpell) {
				item.kind = proto::kSkySpell;
				item.sub = 0xFF;
				switch (effect ? effect->baseEffect->GetMagickSkill() : RE::ActorValue::kNone) {
				case RE::ActorValue::kAlteration:
					item.sub = proto::kSkyAlteration;
					break;
				case RE::ActorValue::kConjuration:
					item.sub = proto::kSkyConjuration;
					break;
				case RE::ActorValue::kDestruction:
					item.sub = proto::kSkyDestruction;
					break;
				case RE::ActorValue::kIllusion:
					item.sub = proto::kSkyIllusion;
					break;
				case RE::ActorValue::kRestoration:
					item.sub = proto::kSkyRestoration;
					break;
				default:
					break;
				}
				item.material = ElementOf(effect);
				item.stat = a_spell->CalculateMagickaCost(a_player);
			} else {
				item.kind = proto::kSkyPower;
				item.sub = type == T::kLesserPower ? 1 : 0;
			}
			if (effect) {
				item.stat2 = static_cast<float>(effect->GetDuration());
			}
			const auto& runtime = a_player->GetActorRuntimeData();
			item.flags |= runtime.selectedSpells[RE::Actor::SlotTypes::kRightHand] == a_spell ? proto::kSkyItemRightHand : 0;
			item.flags |= runtime.selectedSpells[RE::Actor::SlotTypes::kLeftHand] == a_spell ? proto::kSkyItemLeftHand : 0;
			item.flags |= runtime.selectedPower == a_spell ? proto::kSkyItemVoice : 0;
			items.push_back(item);
		}

		void AddShout(RE::PlayerCharacter* a_player, RE::TESShout* a_shout, std::unordered_set<RE::FormID>& a_seen)
		{
			if (!a_shout || !a_seen.insert(a_shout->GetFormID()).second) {
				return;
			}
			proto::SkyItem item{};
			item.formId = a_shout->GetFormID();
			item.kind = proto::kSkyShout;
			SetName(item, a_shout->GetName());
			for (const auto& variation : a_shout->variations) {
				if (variation.word && (variation.word->formFlags & RE::TESForm::RecordFlags::kUnlocked) != 0) {
					++item.count;
					item.stat2 = variation.recoveryTime;
				}
			}
			item.flags |= a_player->GetActorRuntimeData().selectedPower == a_shout ? proto::kSkyItemVoice : 0;
			items.push_back(item);
		}

		void AddSpellList(RE::PlayerCharacter* a_player, RE::TESSpellList::SpellData* a_list, std::unordered_set<RE::FormID>& a_seen)
		{
			if (!a_list) {
				return;
			}
			for (std::uint32_t i = 0; a_list->spells && i < a_list->numSpells; ++i) {
				AddSpell(a_player, a_list->spells[i], a_seen);
			}
			for (std::uint32_t i = 0; a_list->shouts && i < a_list->numShouts; ++i) {
				AddShout(a_player, a_list->shouts[i], a_seen);
			}
		}

		void Write(RE::PlayerCharacter* a_player)
		{
			items.clear();
			std::int32_t gold = 0;
			for (auto& [object, data] : a_player->GetInventory()) {
				auto& [count, entry] = data;
				if (object && count > 0 && object->GetPlayable() && items.size() < proto::kMaxSkyItems) {
					AddItem(a_player, object, count, entry.get(), gold);
				}
			}
			std::unordered_set<RE::FormID> seen;
			if (auto* base = a_player->GetActorBase()) {
				AddSpellList(a_player, base->actorEffects, seen);
			}
			if (auto* race = a_player->GetRace()) {
				AddSpellList(a_player, race->actorEffects, seen);
			}
			for (auto* spell : a_player->GetActorRuntimeData().addedSpells) {
				AddSpell(a_player, spell, seen);
			}
			if (items.size() > proto::kMaxSkyItems) {
				items.resize(proto::kMaxSkyItems);
			}
			const float carried = a_player->GetTotalCarryWeight();
			const float carryMax = a_player->AsActorValueOwner()->GetActorValue(RE::ActorValue::kCarryWeight);

			// The generation changes only when something did (Minecraft rebuilds its screens then).
			std::uint64_t hash = 1469598103934665603ull;
			auto mix = [&](const void* a_bytes, std::size_t a_size) {
				for (std::size_t i = 0; i < a_size; ++i) {
					hash = (hash ^ static_cast<const std::uint8_t*>(a_bytes)[i]) * 1099511628211ull;
				}
			};
			mix(items.data(), items.size() * sizeof(proto::SkyItem));
			mix(&gold, sizeof(gold));
			mix(&carried, sizeof(carried));
			mix(&carryMax, sizeof(carryMax));
			if (hash != lastHash) {
				lastHash = hash;
				++generation;
			}
			Link::Get().WriteSkyInventory(items.data(), static_cast<std::uint32_t>(items.size()), generation, gold, carried, carryMax);
		}

		const RE::BGSEquipSlot* Slot(RE::DEFAULT_OBJECTS::DEFAULT_OBJECT a_which)
		{
			auto* defaults = RE::BGSDefaultObjectManager::GetSingleton();
#pragma push_macro("GetObject")
#undef GetObject  // (Windows' GetObjectA)
			return defaults ? defaults->GetObject<RE::BGSEquipSlot>(a_which) : nullptr;
#pragma pop_macro("GetObject")
		}

		// The copy to equip (the one worn, to take it off; else an enchanted or tempered one first),
		// as Skyrim's menu passes its extra data.
		RE::ExtraDataList* ExtraFor(RE::PlayerCharacter* a_player, RE::TESBoundObject* a_object, bool a_worn, bool a_left)
		{
			auto inventory = a_player->GetInventory([a_object](RE::TESBoundObject& a_candidate) { return &a_candidate == a_object; });
			const auto it = inventory.find(a_object);
			if (it == inventory.end() || !it->second.second || !it->second.second->extraLists) {
				return nullptr;
			}
			RE::ExtraDataList* other = nullptr;
			for (auto* list : *it->second.second->extraLists) {
				if (!list) {
					continue;
				}
				const bool worn = list->HasType(a_left ? RE::ExtraDataType::kWornLeft : RE::ExtraDataType::kWorn);
				if (a_worn && worn) {
					return list;
				}
				if (!a_worn && !list->HasType(RE::ExtraDataType::kWorn) && !list->HasType(RE::ExtraDataType::kWornLeft) && !other) {
					other = list;
				}
			}
			return a_worn ? nullptr : other;
		}

		void Notify(const char* a_text)
		{
			RE::SendHUDMessage::ShowHUDMessage(a_text);
		}
	}

	void Apply(RE::PlayerCharacter* a_player, const proto::McEvent& a_event)
	{
		dirty = true;
		auto* ui = RE::UIMessageQueue::GetSingleton();
		if (a_event.flags == proto::kSkyItemMenuStats || a_event.flags == proto::kSkyItemMenuMap) {
			if (ui) {
				ui->AddMessage(a_event.flags == proto::kSkyItemMenuStats ? RE::StatsMenu::MENU_NAME : RE::MapMenu::MENU_NAME, RE::UI_MESSAGE_TYPE::kShow, nullptr);
			}
			return;
		}
		auto* form = RE::TESForm::LookupByID(a_event.formId);
		auto* equip = RE::ActorEquipManager::GetSingleton();
		if (!form || !equip) {
			return;
		}
		const bool left = a_event.flags == proto::kSkyItemLeft;
		const auto* handSlot = Slot(left ? RE::DEFAULT_OBJECTS::kLeftHandEquip : RE::DEFAULT_OBJECTS::kRightHandEquip);
		auto&      runtime = a_player->GetActorRuntimeData();

		if (auto* shout = form->As<RE::TESShout>()) {
			equip->EquipShout(a_player, shout);
			logger::info("Skyrim items: {} is the voice's", shout->GetName());
			return;
		}
		if (auto* spell = form->As<RE::SpellItem>()) {
			using T = RE::MagicSystem::SpellType;
			if (spell->GetSpellType() != T::kSpell) {
				equip->EquipSpell(a_player, spell, Slot(RE::DEFAULT_OBJECTS::kVoiceEquip));
				logger::info("Skyrim items: {} is the voice's", spell->GetName());
			} else if (runtime.selectedSpells[left ? RE::Actor::SlotTypes::kLeftHand : RE::Actor::SlotTypes::kRightHand] == spell) {
				a_player->DeselectSpell(spell);
				logger::info("Skyrim items: {} taken out of the {} hand", spell->GetName(), left ? "left" : "right");
			} else {
				equip->EquipSpell(a_player, spell, handSlot);
				logger::info("Skyrim items: {} in the {} hand", spell->GetName(), left ? "left" : "right");
			}
			return;
		}
		auto* object = form->As<RE::TESBoundObject>();
		if (!object || a_player->GetItemCount(object) <= 0) {
			return;
		}
		switch (object->GetFormType()) {
		case RE::FormType::Weapon:
		case RE::FormType::Light:
		case RE::FormType::Scroll:
			{
				const bool twoHanded = object->Is(RE::FormType::Weapon) && WeaponType(object->As<RE::TESObjectWEAP>()) >= proto::kSkyGreatsword &&
				                       WeaponType(object->As<RE::TESObjectWEAP>()) != proto::kSkyStaff && WeaponType(object->As<RE::TESObjectWEAP>()) <= proto::kSkyCrossbow;
				const bool torch = object->Is(RE::FormType::Light);
				const bool inLeft = a_player->GetEquippedObject(true) == object, inRight = a_player->GetEquippedObject(false) == object;
				const bool here = twoHanded ? inRight || inLeft : (torch ? inLeft : left ? inLeft : inRight);
				const auto* slot = twoHanded ? nullptr : torch ? Slot(RE::DEFAULT_OBJECTS::kLeftHandEquip) : handSlot;
				if (here) {
					equip->UnequipObject(a_player, object, ExtraFor(a_player, object, true, torch || (!twoHanded && left)), 1, slot);
					logger::info("Skyrim items: {} unequipped", object->GetName());
				} else {
					equip->EquipObject(a_player, object, ExtraFor(a_player, object, false, false), 1, slot);
					logger::info("Skyrim items: {} equipped ({})", object->GetName(), twoHanded ? "both hands" : torch || left ? "left hand" : "right hand");
				}
				return;
			}
		case RE::FormType::Armor:
		case RE::FormType::Ammo:
			{
				auto* worn = ExtraFor(a_player, object, true, false);
				if (worn) {
					equip->UnequipObject(a_player, object, worn, 1, nullptr);
					logger::info("Skyrim items: {} taken off", object->GetName());
				} else {
					equip->EquipObject(a_player, object, ExtraFor(a_player, object, false, false), 1, nullptr);
					logger::info("Skyrim items: {} put on", object->GetName());
				}
				return;
			}
		case RE::FormType::AlchemyItem:
		case RE::FormType::Ingredient:
			equip->EquipObject(a_player, object, nullptr, 1, nullptr);
			logger::info("Skyrim items: {} used", object->GetName());
			return;
		case RE::FormType::Book:
			{
				auto* book = object->As<RE::TESObjectBOOK>();
				if (book->TeachesSpell() || book->TeachesSkill()) {
					auto* spell = book->TeachesSpell() ? book->GetSpell() : nullptr;
					if (spell && a_player->HasSpell(spell)) {
						Notify("You already know that spell.");
						return;
					}
					book->Read(a_player);
					if (spell) {
						a_player->RemoveItem(object, 1, RE::ITEM_REMOVE_REASON::kRemove, nullptr, nullptr);
					}
					logger::info("Skyrim items: {} read", object->GetName());
				} else {
					Notify("Open Skyrim's inventory to read this.");
				}
				return;
			}
		default:

			return;
		}
	}

	void PerFrame(RE::PlayerCharacter* a_player, bool a_wanted, float a_delta)
	{
		timer -= a_delta;
		if (!a_wanted) {
			dirty = true;  // fresh the moment Minecraft's inventory opens
			return;
		}
		if (!dirty && timer > 0.0f) {
			return;
		}
		dirty = false;
		timer = 0.25f;
		Write(a_player);
	}
}
