# Changelog

## 1.0.0 (2026-10-30), Hytale 0.7.5

First release.

- **The window** (`/generousoperator`): choose a player or everyone online, then a kit. It shows what the kit may contain, what the recipient will see, and the equivalent command. Kits that can't be given right now stay in the list, dimmed, with the reason. A **Log** tab shows every gift, with Give again.
- **Commands:** `/generousoperator <player|all> <kit>`, `confirm`, `kits`, `log [player]` and `reload`. `/gift` is an alias when no other mod has taken it.
- **Gifts run as the giver,** through CommandDeck, with the giver's own permissions.
- **Everyone online:** one confirmation for the whole batch. Players who can't receive the kit are skipped with the reason, and a batch can be stopped. A kit that CommandDeck asks to confirm is given one player at a time.
- **Limits** (all optional): gifts per giver per day, gifts per player per day, and a wait between gifts to the same player. A gift that was only partly delivered still counts.
- **Messages:** the recipient is told who sent the gift, or "An operator" if you prefer; an optional announcement to everyone.
- **The gift log** (`gifts.log`): time, giver, recipient, kit, the items given and the roll's seed.
- **Catalogs:** kits as item lists or random tables (chances, picks, dice counts, nested tables across catalogs), once per player, exempt from limits, seasonal variants. Your own in `catalogs/`; other mods ship theirs in their jars. One bad entry never stops the rest.
- **Server presents:** other mods can give kits from the server through the public API, only from mods the operator trusts, capped by a power rating from the game's own progression.
- **Settings** in `config.json`, or in Mod Settings Manager's window; every change applies at once.
- Kits: healing potions in four tiers (Healing Potions I to IV), food in three (Camp Rations, Cook's Basket, Feast Hamper), and an example, Give an apple.
