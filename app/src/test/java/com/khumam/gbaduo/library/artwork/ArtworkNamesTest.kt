package com.khumam.gbaduo.library.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkNamesTest {

    @Test
    fun stemDropsOnlyTheExtension() {
        assertEquals("Pokemon - Emerald Version (USA, Europe)", ArtworkNames.stem("Pokemon - Emerald Version (USA, Europe).gba"))
        assertEquals("No extension", ArtworkNames.stem("No extension"))
        assertEquals("Mr. Driller", ArtworkNames.stem("Mr. Driller.zip"))
    }

    @Test
    fun retroArchReplacesItsForbiddenCharacters() {
        assertEquals("Mario _ Luigi_ Superstar Saga", ArtworkNames.retroArch("Mario & Luigi: Superstar Saga"))
        assertEquals("a_b_c_d_e_f_g_h_i_j_k", ArtworkNames.retroArch("a&b*c/d:e`f<g>h?i\\j|k"))
        assertEquals("Link's Awakening DX", ArtworkNames.retroArch("Link's Awakening DX"))
    }

    @Test
    fun normalizeKeepsLowercaseLettersAndDigits() {
        assertEquals("pokemonemerald2", ArtworkNames.normalize("Pokémon: Emerald #2!"))
        assertEquals("", ArtworkNames.normalize("--"))
    }

    @Test
    fun crcNameIsEightUppercaseHexDigits() {
        assertEquals("0000ABCD", ArtworkNames.crcName(0xABCD))
        assertEquals("FFFFFFFF", ArtworkNames.crcName(0xFFFFFFFFL))
    }

    @Test
    fun exactCandidatesAreStemThenRetroArchForm() {
        assertEquals(listOf("mario & luigi: superstar saga (usa)", "mario _ luigi_ superstar saga (usa)"),
            ArtworkNames.exactCandidates("Mario & Luigi: Superstar Saga (USA).gba"))
        assertEquals(listOf("wario land"), ArtworkNames.exactCandidates("Wario Land.gbc"))
    }

    @Test
    fun imageStemDropsTheCocoonSuffix() {
        assertEquals("Metroid Fusion (USA)", ArtworkNames.imageStem("Metroid Fusion (USA)__cocoon_edit_67_6c4d.jpg"))
        assertEquals("Metroid Fusion (USA)", ArtworkNames.imageStem("Metroid Fusion (USA).png"))
    }
}
