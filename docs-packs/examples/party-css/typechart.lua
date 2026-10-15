-- Generation III type chart (Ruby/Sapphire/Emerald/FireRed/LeafGreen), for
-- a detail page's weaknesses and resistances. Load it from a pack script with
--   local typechart = pack.require("typechart")

local M = {}

M.colors = {
    Normal = "#a8a878", Fire = "#f08030", Water = "#6890f0", Electric = "#f8d030",
    Grass = "#78c850", Ice = "#98d8d8", Fighting = "#c03028", Poison = "#a040a0",
    Ground = "#e0c068", Flying = "#a890f0", Psychic = "#f85888", Bug = "#a8b820",
    Rock = "#b8a038", Ghost = "#705898", Dragon = "#7038f8", Dark = "#705848",
    Steel = "#b8b8d0",
}

-- Attacking type order used by matchups().
M.types = {
    "Normal", "Fire", "Water", "Electric", "Grass", "Ice", "Fighting", "Poison", "Ground",
    "Flying", "Psychic", "Bug", "Rock", "Ghost", "Dragon", "Dark", "Steel",
}

-- chart[attacking][defending] when it isn't 1.
local chart = {
    Normal = { Rock = 0.5, Ghost = 0, Steel = 0.5 },
    Fire = { Fire = 0.5, Water = 0.5, Grass = 2, Ice = 2, Bug = 2, Rock = 0.5, Dragon = 0.5, Steel = 2 },
    Water = { Fire = 2, Water = 0.5, Grass = 0.5, Ground = 2, Rock = 2, Dragon = 0.5 },
    Electric = { Water = 2, Electric = 0.5, Grass = 0.5, Ground = 0, Flying = 2, Dragon = 0.5 },
    Grass = { Fire = 0.5, Water = 2, Grass = 0.5, Poison = 0.5, Ground = 2, Flying = 0.5, Bug = 0.5, Rock = 2, Dragon = 0.5, Steel = 0.5 },
    Ice = { Fire = 0.5, Water = 0.5, Grass = 2, Ice = 0.5, Ground = 2, Flying = 2, Dragon = 2, Steel = 0.5 },
    Fighting = { Normal = 2, Ice = 2, Poison = 0.5, Flying = 0.5, Psychic = 0.5, Bug = 0.5, Rock = 2, Ghost = 0, Dark = 2, Steel = 2 },
    Poison = { Grass = 2, Poison = 0.5, Ground = 0.5, Rock = 0.5, Ghost = 0.5, Steel = 0 },
    Ground = { Fire = 2, Electric = 2, Grass = 0.5, Poison = 2, Flying = 0, Bug = 0.5, Rock = 2, Steel = 2 },
    Flying = { Electric = 0.5, Grass = 2, Fighting = 2, Bug = 2, Rock = 0.5, Steel = 0.5 },
    Psychic = { Fighting = 2, Poison = 2, Psychic = 0.5, Dark = 0, Steel = 0.5 },
    Bug = { Fire = 0.5, Grass = 2, Fighting = 0.5, Poison = 0.5, Flying = 0.5, Psychic = 2, Ghost = 0.5, Dark = 2, Steel = 0.5 },
    Rock = { Fire = 2, Ice = 2, Fighting = 0.5, Ground = 0.5, Flying = 2, Bug = 2, Steel = 0.5 },
    Ghost = { Normal = 0, Psychic = 2, Ghost = 2, Dark = 0.5, Steel = 0.5 },
    Dragon = { Dragon = 2, Steel = 0.5 },
    Dark = { Fighting = 0.5, Psychic = 2, Ghost = 2, Dark = 0.5, Steel = 0.5 },
    Steel = { Fire = 0.5, Water = 0.5, Electric = 0.5, Ice = 2, Rock = 2, Steel = 0.5 },
}

local LABELS = { [4] = "4×", [2] = "2×", [0.5] = "½×", [0.25] = "¼×", [0] = "0×" }

-- Damage multiplier of an `attacking` move against a Pokémon of `defending` types.
function M.multiplier(attacking, defending)
    local row = chart[attacking] or {}
    local m = 1
    for _, t in ipairs(defending) do
        local v = row[t]
        if v ~= nil then m = m * v end
    end
    return m
end

-- Lists ready for a layout: each entry is { name, color, mult, label }.
-- weak: takes more than 1×; resist: less than 1× but not 0; immune: 0×.
function M.matchups(defending)
    local weak, resist, immune = {}, {}, {}
    local order = {}
    for i, attacking in ipairs(M.types) do
        local m = M.multiplier(attacking, defending)
        if m ~= 1 then
            local entry = { name = attacking, color = M.colors[attacking], mult = m, label = LABELS[m] or (m .. "×") }
            order[entry] = i
            if m == 0 then
                immune[#immune + 1] = entry
            elseif m > 1 then
                weak[#weak + 1] = entry
            else
                resist[#resist + 1] = entry
            end
        end
    end
    -- Strongest first (4× before 2×, ¼× before ½×), then in type order.
    table.sort(weak, function(a, b)
        if a.mult ~= b.mult then return a.mult > b.mult end
        return order[a] < order[b]
    end)
    table.sort(resist, function(a, b)
        if a.mult ~= b.mult then return a.mult < b.mult end
        return order[a] < order[b]
    end)
    return weak, resist, immune
end

return M
