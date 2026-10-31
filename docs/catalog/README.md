# Generous Operator catalog sidecar (format 1.0)

Generous Operator gives **kits** to players: a server operator picks a player and a kit, or another mod asks for one through the API. A **catalog sidecar** is a small JSON file your mod ships to add kits to Generous Operator's catalog. Without Generous Operator installed, the file is simply never read, so your mod doesn't need to depend on it.

## Where it goes

- **Your mod:** `generousoperator/catalog.json` inside the mod's jar (next to CommandDeck's `commanddeck/commands.json`, if you ship one).
- **The server's own kits:** any `*.json` file in `mods/Hledjolf_GenerousOperator/catalogs/`. These are the kits the operator writes, or makes in the kit editor. Their source is always `server`.

Catalogs are read when the server starts, and on a reload. A problem is written to the server log with its file and JSON path. **One bad entry never stops the rest from loading.**

## A complete example

```json
{
  "format": "generousoperator.catalog",
  "format_version": "1.0",
  "mod": { "id": "Hledjolf:CalendarSeasons", "name": "Calendar and Seasons", "version": "1.1.0" },
  "kits": [
    {
      "id": "cs-christmas-stocking",
      "name": "Christmas Stocking",
      "description": "A stocking with something small inside.",
      "group": "Holidays",
      "occasion": "christmas",
      "tags": ["seasonal"],
      "message": "{giver} filled your Christmas stocking!",
      "rolls": [
        { "items": [ { "id": "Deco_Christmas_Stocking", "count": 1 } ] },
        { "items": [ { "id": "Food_Candy_Cane", "count": "1d4+1" } ] },
        { "chance": 25, "table": "seasonal-trinket" }
      ]
    },
    {
      "id": "cs-new-year-fireworks",
      "name": "New Year's Box",
      "group": "Holidays",
      "occasion": "new-years-day",
      "items": [ { "id": "Furniture_Lantern", "count": 3 } ]
    }
  ],
  "tables": [
    {
      "id": "seasonal-trinket",
      "rolls": [
        { "pick": 1, "from": [
          { "items": [ { "id": "Deco_Kweebec_Plush" } ] },
          { "items": [ { "id": "WinterHoliday_Snowman" } ] },
          { "weight": 2, "items": [ { "id": "Deco_Nest" } ] }
        ] }
      ]
    }
  ]
}
```

(The item ids are illustrations. Use the game's own item ids, the ones `/give` takes.)

## Top level

| Field | | |
| --- | --- | --- |
| `format` | required | Always `"generousoperator.catalog"`. |
| `format_version` | required | `"1.0"`. See "Versions". |
| `mod` | recommended | `id` (your mod's id as the game knows it), `name`, `version`. Generous Operator takes the source of your kits from the mod the file ships in, not from this field; a mismatch is logged. |
| `kits` | | Giftable kits (below). |
| `tables` | | Tables that exist only to be nested in kits. They can't be given on their own. |

## Kits

Every kit has an `id` and a `name`, and **exactly one** form of contents:

| Form | Field | Given as a person | Given from the server |
| --- | --- | --- | --- |
| **Item list** | `items` (a plain list) or `rolls` (a random table) | Yes: becomes `/give` lines, run through CommandDeck with the giver's permissions | Yes, from mods the operator trusts: put straight into the inventory, no commands |
| **Command preset** | `preset`: a CommandDeck preset id from your CommandDeck sidecar | Yes, through CommandDeck | **Never**: commands given with the server's rights would be an escalation |

Most kits should be item lists. Use a preset only when a gift has to run commands (a teleport, an effect).

| Field | Default | |
| --- | --- | --- |
| `id` | required | 1 to 64 characters: lowercase letters, digits, `_` and `-`, starting with a letter or digit. Unique within your catalog (kits and tables share one set of ids). Other catalogs and callers refer to it as `<your mod id>/<id>`, such as `Hledjolf:CalendarSeasons/cs-christmas-stocking`. |
| `name` | required | Shown in the window, the recipient's message and the log. |
| `description` | | Shown in the window. |
| `items` | | A plain item list: `[{ "id": ..., "count": ... }]`. |
| `rolls` | | A random table (below). |
| `preset` | | A CommandDeck preset id. |
| `group` | `"Other"` | The window's group: `Boosters`, `Builder`, `Holidays`, `Life`, `Server`, `Playtest`, or your own. A group is a label: it grants nothing. |
| `kind` | | Within a group, such as `resources`, `finished-goods`, `seeds`, `homebuilder`, `prefab`. |
| `tier` | | A whole number; the window sorts by it. |
| `occasion` | | An occasion id, such as `christmas` (Calendar & Seasons' ids). When that occasion is on, the window shows the kit first, and a caller's list of seasonal variants picks it. |
| `biome` | | For homebuilder kits. |
| `tags` | `[]` | Free labels (`seasonal`, `trophy`, `food`) that help callers find kits. Tags never grant anything. |
| `limits` | `true` | `false` exempts the kit from the operator's daily caps and cooldown (for playtest gear, so a test can be re-run). It's logged either way. |
| `oncePerPlayer` | `false` | `true`: each player can receive this kit once, ever (welcome packs, boosters). |
| `message` | `"{giver} sent you <name>!"` | The recipient's chat line. `{giver}`, `{kit}` and `{recipient}` are filled in. `{giver}` reads "An operator" when the server hides operators' names, and "The server" for a server present. |

## Random tables

`rolls` is a list of lines. Each line is one of two kinds:

**A chance line** happens with a chance, in percent (default 100: always):

```json
{ "chance": 25, "items": [ { "id": "Ingredient_Bar_Copper", "count": "2d6" } ] }
{ "chance": 10, "table": "gems-minor" }
```

**A pick line** picks `pick` choices (a number or dice) by weight. Without `"repeat": true`, each choice comes up at most once per pick line.

```json
{ "pick": 1, "from": [
  { "weight": 6, "items": [ { "id": "Potion_Health_Lesser" } ] },
  { "weight": 3, "items": [ { "id": "Potion_Health_Small" } ] },
  { "weight": 1, "table": "Hledjolf:CalendarSeasons/seasonal-trinket" }
] }
```

A line or a choice gives `items`, rolls on a `table`, or both. `weight` defaults to 1. Any line may have a `note`, which is ignored.

**Counts** are a whole number or dice: `2d6`, `d8`, `1d4+1`, `1d6-1`, `3d6x10` (roll 3d6, multiply by 10). The default is 1. A count never goes below zero.

**A table reference** is a bare id (a kit or table in the same catalog) or `<mod id>/<id>` for any other catalog's. Any visible table may be nested, and command presets can't be.

A plain `items` list is just a table whose lines all happen, so there's one format, not two.

### What Generous Operator checks

- **Item ids** are checked against the game's live item registry, modded items included. An unknown id is left out of the kit, with a warning; the kit still loads.
- **Table references** that don't resolve are left out, with a warning.
- **Loops** (A rolls on B, B rolls on A) are refused: every table on the loop is skipped, and the log names the loop.
- **Depth:** a table may nest up to 8 tables deep by default (an operator setting).
- **Size:** one roll gives at most 10,000 items by default (an operator setting), counted one by one.

### How rolling works for players

- The window's preview shows what a kit **may contain**: each item's range ("2–12 × Copper Bar"), not a sample roll.
- Every gift's log line records the items actually given and the roll's seed, so a disputed roll can be replayed.
- `oncePerPlayer` applies to the kit, not to what came out of it.
- A roll can come up empty. That still counts as the gift (so an unlucky table can't be farmed), and the recipient is told.

## Server presents: who may give what from the server

A mod can ask Generous Operator to give a kit **from the server** (rewards, quest prizes), rather than as a person. That's allowed only from mods the server operator trusts. Each known calling mod has its own settings (in Mod Settings Manager's window):

- **may give presents:** on or off (off by default);
- **the kits it may give:** its own, Generous Operator's and the server's; every catalog the kit's rolls can reach has to be one of those, not just the top one;
- a **power cap**, rated on the kit's best possible roll. An item's power is the earliest world zone where it can be had in the vanilla game (1 to 4), and a kit's power is its strongest item's.

Items the curve can't rate (every modded item) make the kit **unrated**, and an unrated kit is a server present only if the operator has switched on that mod's "unrated kits". By default, nothing may be given from the server.

So: **ship item lists if your kits might be rewards**, and expect operators to approve them before they're given automatically. To get settings for your mod, open an issue.

## Versions

`format_version` is `major.minor`. A minor version only adds optional fields, so a 1.0 catalog stays valid in every 1.x. A catalog with a newer minor version than this Generous Operator knows still loads; fields it doesn't know are ignored. A newer major version is refused, with a message.

## License

MIT, like the rest of Generous Operator's published code. Copy the example into your mod and change it however you like.
