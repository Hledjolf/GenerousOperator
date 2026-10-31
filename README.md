# Generous Operator

One-click gifts for server operators, on Hytale. Pick a player, or everyone online, pick a kit, and press Give. The recipient is told who sent it, and every gift goes in a log. Kits come from catalogs that any mod can ship.

It's built on [CommandDeck](https://www.curseforge.com/hytale/mods/commanddeck): a gift runs as the person giving it, with their own permissions, so it can never do more than they could.

## Requirements

- A Hytale server (or singleplayer), tested on **0.7.5**.
- **CommandDeck 1.1.0** or later.
- Optional: **Mod Settings Manager**, to change the settings in a window instead of `config.json`.

## Install

Put `GenerousOperator-1.0.0.jar` and `CommandDeck-1.1.0.jar` in the server's `mods` folder (singleplayer: `%APPDATA%\Hytale\UserData\Mods\`), then start the server.

## Use

`/generousoperator` opens the window. On the **Give** tab:

1. **To:** a player online, or **Everyone online**.
2. Choose a kit on the left. The kits are grouped and searchable. A kit that can't be given to that player right now stays in the list, dimmed, with the reason ("already received", "cap reached", "cooldown 2m").
3. The right side shows what the kit may contain, what the recipient will see, and the command that does the same thing. Press **Give**.

A gift to **everyone online** asks once for the whole batch, skips players who can't receive it (and says why), and can be stopped partway. The **Log** tab shows every gift: who gave what to whom, the items given, and the random roll's seed.

Everything the window does can also be typed (`/gift` works too, unless another mod has it):

| Command | Does |
| --- | --- |
| `/generousoperator <player> <kit>` | Give a kit to a player. |
| `/generousoperator all <kit>` | Give it to everyone online (asks to confirm). |
| `/generousoperator confirm` | Confirm what's waiting (within 60 seconds). |
| `/generousoperator kits` | The kits you can give. |
| `/generousoperator log [player]` | The last gifts, or one player's. |
| `/generousoperator reload` | Read the catalogs and settings again. |

Generous Operator 1.0 ships these kits, in two groups:

- **Supplies:** healing potions in four tiers (**Healing Potions I** to **IV**: 5 Lesser, Small, plain or Greater Health Potions, healing 30% to 75% each) and food in three (**Camp Rations**, **Cook's Basket** and **Feast Hamper**, from campfire food to pies).
- **Examples:** **Give an apple**, the smallest catalog entry there is.

Add your own as catalog files (below), and other mods can ship theirs.

## Permissions

| Node | Lets you |
| --- | --- |
| `generousoperator.give` | Give gifts and read the log. |
| `generousoperator.edit` | Reload the catalogs. |
| `commanddeck.use` | Needed too: gifts run through CommandDeck. |
| `commanddeck.run_others` | Needed to give to anyone but yourself. |

Operators have them all. A gift's items are given with `/give`, so the giver also needs the game's own permission for it.

## Settings

In `mods/Hledjolf_GenerousOperator/config.json`, or in Mod Settings Manager's window. All off by default, and every change applies at once:

- **Limits:** gifts per giver per day, gifts per player per day, and a wait between gifts to the same player. The day starts at the server's local midnight.
- **Messages:** hide who gave it (the recipient sees "An operator"; the log keeps the real name), and announce gifts to everyone.
- **Kits:** the deepest table nesting (8) and the most items in one roll (10,000).
- **Server presents:** which mods may give gifts from the server, each with an on/off switch, a power cap and whether it may give kits the power curve can't rate. In 1.0 that's PlaytestGamified; more mods get switches as they add rewards.

## Your own kits

Put a catalog file (any name ending in `.json`) in `mods/Hledjolf_GenerousOperator/catalogs/` and type `/generousoperator reload`:

```json
{
  "format": "generousoperator.catalog",
  "format_version": "1.0",
  "kits": [
    { "id": "welcome", "name": "Welcome Pack", "group": "Server", "oncePerPlayer": true,
      "items": [ { "id": "Plant_Fruit_Apple", "count": 5 }, { "id": "Ingredient_Bar_Copper", "count": 10 } ] }
  ]
}
```

Kits can be random tables (chances, picks, dice counts, nested tables), can be given once per player, and can be exempt from the limits. The whole format is in `docs/catalog/README.md`.

## For mod authors

- **Ship kits:** put `generousoperator/catalog.json` in your mod's jar, in the same format. Without Generous Operator installed, it's never read, so your mod doesn't depend on it.
- **Give kits from your mod:** the public API, `generousoperator.api` (`GenerousOperatorApi.get()`, then `check` and `give`). A gift can be given as a player (through CommandDeck, with that player's permissions) or from the server.
- **Gifts from the server** (rewards, quest prizes) are allowed only from mods the operator trusts: each known mod has its own switch, power cap and "unrated kits" switch in the settings. A trusted mod may give its own kits, Generous Operator's and the server's. To get switches for your mod, open an issue. An item's power is the earliest world zone where it can be had in the vanilla game (1 to 4), and a kit's power is its strongest item's, on its best roll. Kits with items the curve can't rate (modded items, for now) need the operator's explicit approval.

## Building

Java 25 and Gradle (the wrapper is included). The build needs two jars it never bundles: Hytale's server
(`HytaleServer.jar`, found in the default launcher install, or `-PhytaleServerJar=<path>`) and CommandDeck 1.1.0
(`-PcommandDeckJar=<path>`). `-PpowerJson=<path>` names the power curve the build turns into the item zone table;
without it, every item is unrated as a server present.

```
./gradlew build -PcommandDeckJar=<path to CommandDeck-1.1.0.jar>
```

The jar is in `build/mods/`.

## License

MIT. See `LICENSE`.
