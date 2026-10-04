package dev.dietapp.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.di.LocalModule
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The other local-mode tests read the assets straight from the repository. This one goes through a real
 * AssetManager over what the build actually packages, which is what the phone sees. It exists because the Android
 * Gradle Plugin turns `foo.tsv.gz` into a decompressed `foo.tsv`: every test passed and the app would still have
 * crashed on its first message.
 */
@RunWith(AndroidJUnit4::class)
class BundledAssetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the food catalog loads from the packaged assets`() {
        val catalog = AssetCatalogSource(context).get()
        assertTrue("catalog has ${catalog.size} foods", catalog.size > 7_000)
        assertTrue(catalog.search(listOf("chicken breast")).isNotEmpty())
    }

    @Test fun `the russian dictionary loads from the packaged assets`() {
        val lexicon = LocalModule.lexicon(context)
        assertTrue("dictionary has ${lexicon.entries.size} entries", lexicon.entries.size > 200)
        assertTrue(lexicon.find("гречка") != null)
    }

    @Test fun `the model prompt and tool schema load from the packaged assets`() {
        val prompts = LocalModule.promptAssets(context)
        assertTrue(prompts.systemPrompt.isNotBlank())
        assertEquals("record_food", prompts.toolName)
    }

    @Test fun `no packaged asset has a name that the build rewrites`() {
        // a `.gz` asset would be decompressed and renamed; opening it by its source name would then fail
        val names = walk("")
        assertTrue(names.isNotEmpty())
        names.filter { it.endsWith(".gz") }.forEach { fail("asset $it would be rewritten by the build: do not use the .gz extension") }
        try {
            context.assets.open("foods_sr_legacy.tsv.gzip").close()
        } catch (e: FileNotFoundException) {
            fail("the catalog is not in the packaged assets under its own name: ${names.joinToString()}")
        }
    }

    private fun walk(dir: String): List<String> =
        context.assets.list(dir).orEmpty().flatMap { name ->
            val path = if (dir.isEmpty()) name else "$dir/$name"
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) listOf(path) else walk(path)
        }
}
