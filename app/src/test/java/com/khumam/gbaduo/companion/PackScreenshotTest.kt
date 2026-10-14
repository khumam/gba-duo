package com.khumam.gbaduo.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import com.khumam.gbaduo.companion.style.CssParser
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.math.abs

/**
 * Renders the example packs in `docs-packs/examples` with their sample
 * snapshots, the way the companion screen does, and compares them with the
 * reference images in `src/test/screenshots`. Catches visual regressions in the
 * CSS renderer that position checks miss (colors, borders, gradients, text).
 *
 * After an intended visual change, re-record with:
 *   ./gradlew :app:testDebugUnitTest --tests '*PackScreenshotTest' -PrecordScreenshots
 * On a mismatch the actual image and a diff (changed pixels in red) are written
 * to `build/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The Thor's bottom screen is 1080x1240; half size keeps the reference images small.
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w360dp-h413dp-hdpi")
class PackScreenshotTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val examples = File("../docs-packs/examples")
    private val references = File("src/test/screenshots")
    private val output = File("build/screenshots")
    private val record = System.getProperty("gbaduo.recordScreenshots", "false") != "false"

    private fun renderPack(name: String, afterBuild: (View) -> Unit = {}): Bitmap {
        val packDir = File(examples, name)
        val manifest = PackManifest.parse(File(packDir, "manifest.json").readText())
        val builtIn = CssParser.parse(File("src/main/assets/${PackStyles.BUILT_IN_ASSET}").readText())
        val styles = PackStyles.forPack(manifest, packDir)
        styles.forEach { assertTrue("$name CSS warnings: ${it.warnings}", it.warnings.isEmpty()) }
        val layout = LayoutParser.parseWithWarnings(File(packDir, manifest.layoutFile).readText())
        assertTrue("$name layout warnings: ${layout.warnings}", layout.warnings.isEmpty())

        val renderer = WidgetRenderer(context, packDir, listOf(builtIn), styles)
        val view = renderer.build(layout.root)
        renderer.update(JsonTree.parseObject(File(packDir, "sample-snapshot.json").readText()))
        val metrics = context.resources.displayMetrics
        layout(view, metrics.widthPixels, metrics.heightPixels)
        afterBuild(view)
        layout(view, metrics.widthPixels, metrics.heightPixels)
        assertTrue("$name: renderer warnings ${renderer.warnings}", renderer.warnings.isEmpty())

        val bitmap = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK) // the companion screen's own background
        view.draw(canvas)
        return bitmap
    }

    private fun layout(view: View, width: Int, height: Int) {
        repeat(2) { // the second pass picks up the viewport-size restyle posted by the first
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            ShadowLooper.idleMainLooper()
        }
    }

    private fun View.child(vararg path: Int): View = path.fold(this) { v, i -> (v as ViewGroup).getChildAt(i) }

    private fun verify(name: String, actual: Bitmap) {
        val reference = File(references, "$name.png")
        if (record) {
            reference.parentFile?.mkdirs()
            reference.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        if (!reference.isFile) fail("No reference image for '$name'; record one with -PrecordScreenshots")
        val expected = BitmapFactory.decodeFile(reference.path)
        if (expected.width != actual.width || expected.height != actual.height) {
            save(name, actual, null)
            fail("'$name' is ${actual.width}x${actual.height}, reference is ${expected.width}x${expected.height}")
        }
        val diff = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
        var changed = 0
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                val a = actual.getPixel(x, y)
                val e = expected.getPixel(x, y)
                // Small per-channel differences are antialiasing noise between platforms.
                val differs = listOf(24, 16, 8, 0).any { shift -> abs((a ushr shift and 0xFF) - (e ushr shift and 0xFF)) > CHANNEL_TOLERANCE }
                if (differs) changed++
                diff.setPixel(x, y, if (differs) Color.RED else (e and 0x00FFFFFF) or 0x40000000)
            }
        }
        val allowed = actual.width * actual.height * PIXEL_TOLERANCE
        if (changed > allowed) {
            save(name, actual, diff)
            fail("'$name' differs from its reference in $changed pixels (allowed ${allowed.toInt()}); see ${output.absolutePath}")
        }
    }

    private fun save(name: String, actual: Bitmap, diff: Bitmap?) {
        output.mkdirs()
        File(output, "$name-actual.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        diff?.let { d -> File(output, "$name-diff.png").outputStream().use { d.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test
    fun classicPackWithoutCss() = verify("classic", renderPack("classic"))

    @Test
    fun cssPackPartyTab() = verify("party-css-party", renderPack("party-css"))

    @Test
    fun cssPackBagTab() = verify(
        "party-css-bag",
        renderPack("party-css") { viewport ->
            // viewport > .screen > tabs > tab-bar > second tab
            viewport.child(0, 1, 0, 1).performClick()
        },
    )

    private companion object {
        const val CHANNEL_TOLERANCE = 40
        const val PIXEL_TOLERANCE = 0.002f
    }
}
