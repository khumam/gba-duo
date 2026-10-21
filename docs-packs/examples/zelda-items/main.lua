-- Example: a companion pack that changes the game when you tap it.
--
-- !! The addresses below are MADE UP. They show the shape of a pack, not a real
-- !! memory map: running this on a real game would write to the wrong place.
-- !! See NOTES.md for how to find real addresses before you write anything.
--
-- The rules the app enforces (not this script):
-- * mem.write* only works inside an action (a tap), never in update() or init().
-- * Only writable RAM (GBA EWRAM/IWRAM, GB/GBC work RAM and high RAM).
-- * At most 64 bytes per call and 256 bytes per tap.
-- * The manifest asks for "memory-write", the player turned on "Packs can change
--   games" in Settings, and allowed this pack for this game when it first asked.
-- Every write returns true, or false and a reason.

local B_SLOT = 0x02001234     -- made up: the item id on the B button
local SAFE_FLAG = 0x02001240  -- made up: 1 while the player is free to act (not in a cutscene, menu...)

local ITEMS = {
  { id = 0x11, name = "Gust Jar" },
  { id = 0x13, name = "Mole Mitts" },
  { id = 0x14, name = "Roc's Cape" },
}

local NAMES = {}
for _, item in ipairs(ITEMS) do NAMES[item.id] = item.name end

function update()
  local b = mem.u8(B_SLOT)
  return {
    equippedB = NAMES[b] or string.format("item %02X", b),
    items = ITEMS,
  }
end

actions = {}

-- onClick: { "call": "equip", "args": ["B", "{id}"] }
function actions.equip(button, itemId)
  if button ~= "B" or not NAMES[itemId] then
    return { set = { message = "Unknown item" } }
  end
  -- Only change the game when it's safe to: the pack decides when, the app decides whether.
  if mem.u8(SAFE_FLAG) ~= 1 then
    return { set = { message = "Not now: finish what you're doing first" } }
  end
  local ok, why = mem.write8(B_SLOT, itemId)
  if not ok then
    -- Refused by the app: no permission yet, not RAM, too large...
    return { set = { message = "Couldn't equip: " .. why } }
  end
  return { set = { message = NAMES[itemId] .. " is on B" } }
end
