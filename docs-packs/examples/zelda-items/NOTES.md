# Example: equip items (a pack that changes the game)

This pack shows the shape of a companion pack that writes to the game's memory:
the manifest's `"permissions": ["memory-write"]` (formatVersion 3), a tap that calls
`actions.equip`, and `mem.write8` checking its result.

**Its addresses are made up.** Before a pack writes anything, find the real ones.

## Finding addresses

1. **Start from a disassembly or decompilation if the game has one.** The Minish Cap
   has [zeldaret/tmc](https://github.com/zeldaret/tmc): `linker.ld` gives the RAM
   address of each global (`gSave`, `gPlayerState`...) and the headers give the
   offsets inside each struct. pret's projects do the same for Pokémon
   (pokeemerald, pokefirered...). Addresses can differ between the US, EU and JP
   releases: check the version your game code matches.
2. **No decompilation? Search memory.** In a desktop emulator with a memory search
   (mGBA's "Search memory", or a cheat search), note a value you can change in the game
   (the item on B, your rupees), change it in the game, and narrow the search to the
   addresses that changed the same way. Repeat until one is left.
3. **Read before you write.** Show the address in `update()` first and watch it follow
   the game for a while: through a save and reload, a map change, a cutscene. Only an
   address that always holds what you think it does is safe to write.
4. **Find a "safe to act" condition.** Most games have a moment when changing a value
   is harmless (in the field, no menu, no cutscene) and moments when it isn't. Find a
   value that tells them apart and check it before writing, like `SAFE_FLAG` here.
5. **Write the smallest thing.** One byte the game already reads, set to a value the
   game itself would set. Don't write pointers, code, or values the game never uses.

## What the app guarantees, whatever the script does

- Writes only happen inside a tap's action, never from `update()`, `init()` or on their own.
- Only writable RAM: GBA EWRAM `0x02000000-0x0203FFFF` and IWRAM `0x03000000-0x03007FFF`;
  GB/GBC work RAM `0xC000-0xDFFF` and high RAM `0xFF80-0xFFFE`. Never ROM, I/O, video
  memory or the save itself.
- At most 64 bytes per call, 256 per tap.
- Only after the player turned on **Settings > Packs can change games** and allowed this
  pack for this game. A save state is made right before the first write
  (**Load state > Before pack changes**).
- Every write is logged: `adb logcat -s Companion`.
