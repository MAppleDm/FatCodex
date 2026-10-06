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
 * crashed on its first message. (There is no catalog or dictionary in the APK any more: the user's own food database
 * is the only one, and the agent's prompt is all that ships.)
 */
@RunWith(AndroidJUnit4::class)
class BundledAssetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the model prompt and tool schema load from the packaged assets`() {
        val prompts = LocalModule.promptAssets(context)
        assertTrue(prompts.systemPrompt.isNotBlank())
        assertEquals("record_food", prompts.toolName)
        assertTrue("the agent's local rules ship too", prompts.localAgent.contains("search_foods"))
    }

    @Test fun `no food catalog or dictionary is packaged`() {
        val names = walk("")
        assertTrue("nothing named like a catalog: ${names.joinToString()}", names.none { it.contains("usda", true) || it.contains("foods_sr") || it.contains("ru_foods") })
    }

    @Test fun `the agent is told there is no built-in catalog`() {
        val rules = LocalModule.promptAssets(context).localAgent
        assertTrue(!rules.contains("usda:") && !rules.contains("USDA SR Legacy") && !rules.contains("\"ru:"))
    }

    @Test fun `no packaged asset has a name that the build rewrites`() {
        // a `.gz` asset would be decompressed and renamed; opening it by its source name would then fail
        val names = walk("")
        assertTrue(names.isNotEmpty())
        names.filter { it.endsWith(".gz") }.forEach { fail("asset $it would be rewritten by the build: do not use the .gz extension") }
        for (asset in listOf("parse/system_prompt.txt", "parse/tool_spec.json", "parse/local_agent.txt")) {
            try {
                context.assets.open(asset).close()
            } catch (e: FileNotFoundException) {
                fail("$asset is not in the packaged assets under its own name: ${names.joinToString()}")
            }
        }
    }

    private fun walk(dir: String): List<String> =
        context.assets.list(dir).orEmpty().flatMap { name ->
            val path = if (dir.isEmpty()) name else "$dir/$name"
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) listOf(path) else walk(path)
        }
}
