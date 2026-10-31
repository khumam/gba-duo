<div align="center">

<img src="art/banner.png" alt="GBA Duo: play GBA, GBC and GB games with a companion on the bottom screen of the AYN Thor" width="100%">

<h3>Play on the top screen. Your game's stats, guides and more on the bottom one.</h3>

<p>
  <a href="https://github.com/khumam/gba-duo/releases/latest"><img src="https://img.shields.io/github/v/release/khumam/gba-duo?label=release&color=6c4ab6" alt="Latest GBADuo release"></a>
  <a href="https://github.com/khumam/gba-duo/releases"><img src="https://img.shields.io/github/downloads/khumam/gba-duo/total?label=downloads&color=e3a008" alt="Total downloads"></a>
</p>

<p>
  <strong><a href="https://github.com/khumam/gba-duo/releases/latest">Download</a></strong>
  · <strong><a href="#features">Features</a></strong>
  · <strong><a href="#companion-packs">Companion packs</a></strong>
  · <strong><a href="#make-your-own-pack">Make your own pack</a></strong>
  · <strong><a href="https://github.com/khumam/gba-duo/issues">Report a problem</a></strong>
</p>

</div>

GBADuo is a Game Boy Advance, Game Boy Color and Game Boy player for dual-screen
Android handhelds. The game runs on the screen you open it on (usually the top
one), and the other screen is a **companion**: a screen built from a pack that reads your game's memory as you
play and shows what matters, like your party and bag in Pokémon FireRed or your
rucksack and friendships in Harvest Moon. Packs can also draw the game's own world:
the live map in Metroid and Golden Sun, and a **Wide view** that fills the sides of
the picture with more of the map. Packs are plain files (a layout, CSS and a Lua
script), so anyone can build one for their favorite game.

## Devices

| Device    | Status                                                                     |
| --------- | -------------------------------------------------------------------------- |
| AYN Thor  | Tested, made for it                                                        |
| Others    | Should run on any dual-screen Android 11+ device (arm64), not yet tested   |

The companion needs a second screen. Open GBADuo on either screen: the game
plays there and the companion takes the other one.

## Install

1. Download `gbaduo-<version>.apk` from the
   [latest release](https://github.com/khumam/gba-duo/releases/latest) on the device.
2. Open it. If Android asks, allow your browser or file manager to install apps.
3. Open GBADuo and tap **+** to add a ROM folder. Every `.gba`, `.gbc` and `.gb`
   file in it shows up in your library. You can add more folders, or remove
   one, under **ROM folders**; removing a folder never deletes its files.

**No games are included.** Use your own legally dumped ROMs.

<details>
<summary><strong>Updates</strong></summary>

GBADuo doesn't check for updates. Download the newer APK from the
[releases page](https://github.com/khumam/gba-duo/releases) and install it over
the old one; your saves stay.

</details>

## Features

### The companion (bottom screen)

- **Packs per game** - tap **⋮** next to a game in your library to choose its
  companion pack. Each game remembers its own pack.
- **Get packs in the app** - **⋮ > Companion pack > Get from the web…** lists the
  packs for that game, installs the one you pick and flags updates. Or use
  **Import pack…** to add one from a `.zip`.
- **Live** - the pack's Lua script reads the game's memory several times a second,
  so the screen follows along as you play.
- **Touch** - tabs, buttons and pages you can tap through; the controller keeps
  driving the game whichever screen has focus.
- **Pages that open by themselves** - a pack can switch pages when something happens
  in the game, like FireRed's battle page opening when a battle starts and closing
  when it ends.
- **Live maps** - packs can draw the game's map straight from memory: your explored
  map in Metroid, the world in The Minish Cap, the area around you in Golden Sun.

### Wide view

The GBA picture is 240x160, so on a wide screen it leaves black bars. **Wide view**
paints the game's world there instead, built from the game's own map and tiles, so it
scrolls with you. Turn it on with the **WIDE** button on the companion screen (it is a
per-game setting).

- Works with Pokémon FireRed and LeafGreen (app 0.1.9), The Minish Cap (0.1.10) and
  Golden Sun (0.1.11), using the matching packs.
- It paints the map only. Characters and NPCs appear as they walk onto the real picture.
- If the app's picture doesn't match what the game draws (another ROM version, a
  screen fade), the margins hide themselves instead of showing garbage.
- Tested on US ROMs and on the AYN Thor only.

### The player (top screen)

- **Save states** - 9 slots per game, each showing when it was saved.
- **In-game saves** - kept per game, next to its save states.
- **Save folder** - optionally mirror every game's saves to a folder you pick (SD
  card, a synced folder), so backups and moving to another device are file copies.
- **Fast-forward** - hold **R2**; 2x, 3x or 4x, picked in the menu.
- **Shaders** - Off, GBA Color, LCD Grid, Scanlines and Scale2x.
- **Tilt-shift** - a soft blur at the top and bottom of the picture (Off, Light, Strong).
- **Screen options** - integer scaling, aspect ratio (Original, 4:3, 16:9, Stretch) and
  pixel-perfect output.
- **Performance overlay** - FPS, or FPS and CPU, over the game (Settings).

Press **L2** or **BACK** in a game for the menu: resume, save state, load state,
fast-forward speed, shader, tilt-shift, integer scaling and quit.

### Your saves

Each game has its own folder, named by platform and ROM checksum, so it follows the
game wherever the ROM file is:

```
<platform>/<Title> [<code>-<CRC32>]/
    game.sav          the game's own save
    states/slotN.ss   save states
    pack.json         the companion pack picked for it
```

They live in the app's private storage. **Settings > Save folder** keeps a copy of
all of them in a folder you choose with the system folder picker:

- Games still play from the private copy. It's copied to the folder after each in-game
  save and save state, on pause and on quit, and newer saves already in the folder
  (from another device) are taken in when a game starts.
- The first time, every save is copied over and checked before the folder is used.
- If a save changed in both places, the newer one is kept and the other is saved next
  to it as `game.sav.conflict-<time>`. Nothing is overwritten silently.
- If the folder can't be reached (SD card out), the game plays from the private copy
  and the folder catches up once it's back.
- Companion packs and the ROM list stay private.

To back up, copy the folder. To move to another device, copy it there and pick it as
the save folder: each game finds its saves by checksum, whatever the file is called.

### Controls

These are the defaults. Change them in **Settings > Controller**.

| Thor        | Game              |
| ----------- | ----------------- |
| D-pad       | D-pad             |
| A / B       | A / B             |
| L1 / R1     | L / R             |
| Start       | Start             |
| Select      | Select            |
| R2 (hold)   | Fast-forward      |
| L2          | Quick menu        |
| BACK        | Quick menu (always) |

- **Button mapping** lists every game button and the Thor button that presses it. Pick a
  row, press a button, done. A button used elsewhere swaps places with it. **Reset to
  default** restores the table above.
- **Hotkeys** has seven actions: open the quick menu, fast-forward (hold or toggle),
  quick save state, quick load state, next and previous save slot, and exit game. Each
  is one button, or the **hotkey button** plus a button. The hotkey button is none by
  default. While you hold it nothing reaches the game; a button it shares with a game
  button reaches the game when you let go.
- BACK always opens the quick menu, so you can't lock yourself out.
- A and B now match the labels on the Thor's buttons. To swap them back, use
  Settings > Controller > Button mapping.

### Artwork

By default every game is drawn as a cartridge with a text label. **Settings > Scraping**
puts cover art on the label. Choose where covers come from under **Scraping › Providers**; a scrape tries them in this order:

- **An artwork folder** another launcher filled (ES-DE, RetroArch thumbnails, or a plain
  folder of images named like your ROMs). GBADuo only reads it. Images are matched by the
  ROM's file name, its title, game code or checksum; screenshots, logos and other
  platforms' folders are ignored.
- **SteamGridDB**, with your own free API key (steamgriddb.com > profile > Preferences >
  API). Game titles are sent to steamgriddb.com to find the cover.

**Scrape library** finds art for every game, folder first. Nothing goes online until you
press it. It skips games that already have a cover and games whose cover you removed.
In **Library > Game settings** you can search SteamGridDB for one game and choose its
cover, use the artwork folder, remove the cover, or reset the game so scraping can set
it again.

## Companion packs

Packs live in their own repo,
[gba-duo-companions](https://github.com/khumam/gba-duo-companions). Install one from
the app with **⋮ > Companion pack > Get from the web…**, or download a pack's `.zip`
from its [releases](https://github.com/khumam/gba-duo-companions/releases) and add it
with **⋮ > Import pack…**.

| Game                                          | Pack                                   | Shows                                                                                              |
| --------------------------------------------- | -------------------------------------- | -------------------------------------------------------------------------------------------------- |
| Pokémon FireRed (US)                          | `pokemon-firered-companion` (+ `-v`, `-minimal`) | Town map, party, bag, Pokédex, trainer card and a battle page with both Pokémon's live stats; WIDE |
| Pokémon LeafGreen (US)                        | `pokemon-leafgreen-companion` (+ `-v`, `-minimal`) | The same pages for LeafGreen; WIDE                                                              |
| Metroid: Zero Mission                         | `metroid-zero-mission-companion`       | Explored map, suit and beams (tap to switch), area progress, bosses and tanks                      |
| Metroid Fusion                                | `metroid-fusion-companion`             | Live map, upgrades, sector progress, bosses and your next objective                                |
| The Legend of Zelda: The Minish Cap           | `zelda-the-minish-cap-companion`       | World map, items, Kinstones and quest progress; WIDE                                               |
| The Legend of Zelda: Oracle of Ages           | `zelda-oracle-of-ages-companion`       | Era maps, seeds, all 64 rings, hearts and essences (early version)                                 |
| Harvest Moon: Friends of Mineral Town (EU/US) | `harvest-moon-fomt-companion`          | Gold, date and time, stamina, held tool and rucksack, affection, Harvest Sprites' work             |
| Golden Sun (US)                               | `golden-sun-companion`                 | Party, Djinn, Psynergy and an area map that follows your character; WIDE (early preview)           |

Pokémon FireRed and LeafGreen come in three looks each. The pack list has versions,
game codes and per-pack notes. Want a pack for another game?
[Open an issue](https://github.com/khumam/gba-duo/issues), or make one.

## Make your own pack

A pack is a folder with:

- `manifest.json` - its id, name and which games it's for (game code or CRC32);
- `layout.json` - the screen: boxes, text, images, bars, lists, tabs and pages;
- `style.css` - real CSS: flex and grid layout, colors, borders, shadows, fonts and transitions;
- `main.lua` - an `update()` that reads the game's memory and returns the values the layout shows.
  Its `actions` run on taps; with the `memory-write` permission and the player's
  consent they can also change the game (see [Changing the game](docs-packs/companion-pages.md#changing-the-game)).
  `gfx.layers` draws the game's live map from memory as an image.
- `game.lua` (optional) - a script that runs against the game every frame. It describes
  the game's world for **Wide view** and, with consent, can write memory
  ([game script](docs-packs/game-script.md)).

The guides are in [`docs-packs/`](docs-packs/):
[styling the companion](docs-packs/companion-styling.md),
[pages and buttons](docs-packs/companion-pages.md) and the
[game script and Wide view](docs-packs/game-script.md), with examples in
[`docs-packs/examples`](docs-packs/examples).

<details>
<summary><strong>Preview a pack in the browser</strong></summary>

The [companion simulator](https://github.com/khumam/gba-duo-companion-simulator)
renders a pack the way the app does. Open a pack folder, edit its layout, CSS and
sample data side by side, and see the result straight away. It doesn't run the
pack's Lua, so you feed it sample data in `preview-data.json`.

</details>

## Privacy

GBADuo has no accounts, ads or analytics. It only goes online when you ask it to: for
RetroAchievements while you're logged in, and for SteamGridDB when you search or scrape
artwork (game titles only). Your games, saves and packs never leave your device, unless
you pick a save folder that syncs somewhere.

<details>
<summary><strong>AI tooling note</strong></summary>

This project uses AI-assisted development tools, such as Claude Code, to help with code generation, refactoring, and documentation. All changes are still reviewed by the maintainer and tested before release.

</details>

## Building from source

You need JDK 17, the Android SDK and NDK `27.2.12479018`, and the mGBA submodule:

```sh
git clone --recurse-submodules https://github.com/khumam/gba-duo.git
cd gba-duo
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

## Credits

<details>
<summary>mGBA, Lua, AndroidX</summary>

- [mGBA](https://mgba.io) by Vicki Pfau (endrift) and contributors runs the games (MPL 2.0;
  source in [`engine/mgba`](https://github.com/mgba-emu/mgba)).
- [Lua](https://www.lua.org) by PUC-Rio (MIT) runs the companion packs' scripts.
- AndroidX, Jetpack Compose, Material Components and Kotlin (Apache 2.0).
- The Harvest Moon pack's memory map comes from
  [Data Crystal](https://datacrystal.tcrf.net/wiki/Harvest_Moon:_Friends_of_Mineral_Town/RAM_map)
  and samurai goroh's code guide; the FireRed pack's from the
  [pret](https://github.com/pret) decompilation and the Ironmon-Tracker.

</details>

## License

GBADuo is free software under the [GNU GPL v3](LICENSE): you can share and change
it, and anything built from it must stay open source under the same license.

GBADuo is a fan project, not affiliated with or endorsed by Nintendo, Game Freak,
Creatures, The Pokémon Company, Marvelous or Camelot. Pokémon, Harvest Moon, Metroid,
The Legend of Zelda, Golden Sun and all related names are trademarks of their respective owners; Game Boy, Game Boy Color and Game
Boy Advance are trademarks of Nintendo. AYN Thor belongs to its maker. No games, BIOS
or game art are included: use your own legally dumped ROMs.
