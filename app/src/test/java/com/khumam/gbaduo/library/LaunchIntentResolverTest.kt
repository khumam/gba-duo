package com.khumam.gbaduo.library

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchIntentResolverTest {

    private class FakeIntent(
        val dataString: String? = null,
        val extras: Map<String, String> = emptyMap(),
    ) : LaunchIntentResolver.IntentAccessor {
        override fun dataString(): String? = dataString
        override fun extraString(key: String): String? = extras[key]
    }

    @Test
    fun resolvesFromContentUriWithDecodedPath() {
        val target = LaunchIntentResolver.resolveAccessor(
            FakeIntent(dataString = "content://com.android.externalstorage.documents/document/primary%3AROMs%2Fgame.gba"),
        )

        assertEquals("content://com.android.externalstorage.documents/document/primary%3AROMs%2Fgame.gba", target?.uriString)
        assertEquals("game.gba", target?.fileName)
    }

    @Test
    fun resolvesFromDataFileUri() {
        val target = LaunchIntentResolver.resolveAccessor(
            FakeIntent(dataString = "file:///sdcard/ROMs/pokemon.zip"),
        )

        assertEquals("file:///sdcard/ROMs/pokemon.zip", target?.uriString)
        assertEquals("pokemon.zip", target?.fileName)
    }

    @Test
    fun resolvesFromRomExtraPath() {
        val target = LaunchIntentResolver.resolveAccessor(
            FakeIntent(extras = mapOf("rom" to "/storage/emulated/0/ROMs/zelda.gba")),
        )

        assertEquals("file:///storage/emulated/0/ROMs/zelda.gba", target?.uriString)
        assertEquals("zelda.gba", target?.fileName)
        assertEquals("/storage/emulated/0/ROMs/zelda.gba", target?.path)
    }

    @Test
    fun resolvesFromUppercaseRomExtra() {
        val target = LaunchIntentResolver.resolveAccessor(
            FakeIntent(extras = mapOf("ROM" to "/sdcard/fusion.zip")),
        )

        assertEquals("file:///sdcard/fusion.zip", target?.uriString)
        assertEquals("fusion.zip", target?.fileName)
    }

    @Test
    fun resolvesFromPathExtra() {
        val target = LaunchIntentResolver.resolveAccessor(
            FakeIntent(extras = mapOf("path" to "/sdcard/game.gbc")),
        )

        assertEquals("file:///sdcard/game.gbc", target?.uriString)
        assertEquals("game.gbc", target?.fileName)
    }

    @Test
    fun resolvesBarePathWithUrlSpecialCharacters() {
        val target = LaunchIntentResolver.resolveAccessor(
            FakeIntent(extras = mapOf("ROM" to "/storage/9C33-6BBD/ROMs/gba/Pokemon #1 100%+.gba")),
        )

        assertEquals("Pokemon #1 100%+.gba", target?.fileName)
        assertEquals("/storage/9C33-6BBD/ROMs/gba/Pokemon #1 100%+.gba", target?.path)
    }

    @Test
    fun fileUriKeepsDecodedPathAndContentUriHasNone() {
        assertEquals(
            "/sdcard/ROMs/a b+c.gba",
            LaunchIntentResolver.resolveAccessor(FakeIntent(dataString = "file:///sdcard/ROMs/a%20b+c.gba"))?.path,
        )
        assertNull(LaunchIntentResolver.resolveAccessor(FakeIntent(dataString = "content://x/doc/game.gba"))?.path)
    }

    @Test
    fun mapsPathsToExternalStorageDocumentIds() {
        assertEquals("primary:ROMs/gba/a.gba", LaunchIntentResolver.documentIdForPath("/storage/emulated/0/ROMs/gba/a.gba"))
        assertEquals("primary:ROMs/a.gba", LaunchIntentResolver.documentIdForPath("/sdcard/ROMs/a.gba"))
        assertEquals("9C33-6BBD:ROMs/gba/a.zip", LaunchIntentResolver.documentIdForPath("/storage/9C33-6BBD/ROMs//gba/a.zip"))
        assertNull(LaunchIntentResolver.documentIdForPath("/data/data/x/a.gba"))
        assertNull(LaunchIntentResolver.documentIdForPath("/storage/9C33-6BBD/../emulated/0/a.gba"))
    }

    @Test
    fun findsTheLibraryFolderHoldingADocument() {
        val trees = listOf("primary:Download", "9C33-6BBD:ROMs/gba")
        assertEquals("9C33-6BBD:ROMs/gba", LaunchIntentResolver.coveringTree("9C33-6BBD:ROMs/gba/sub/a.gba", trees))
        assertNull(LaunchIntentResolver.coveringTree("9C33-6BBD:ROMs/gbaX/a.gba", trees))
        assertEquals("9C33-6BBD:", LaunchIntentResolver.coveringTree("9C33-6BBD:ROMs/a.gba", listOf("9C33-6BBD:")))
    }

    @Test
    fun rejectsNonRomExtension() {
        val target = LaunchIntentResolver.resolveAccessor(
            FakeIntent(dataString = "file:///sdcard/document.pdf"),
        )

        assertNull(target)
    }

    @Test
    fun rejectsEmptyIntent() {
        assertNull(LaunchIntentResolver.resolve(null as Intent?))
        assertNull(LaunchIntentResolver.resolveAccessor(null))
        assertNull(LaunchIntentResolver.resolveAccessor(FakeIntent()))
    }
}
