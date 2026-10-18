package dev.dietapp.ui.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.MainDispatcherRule
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

private const val APK_URL = "https://github.com/MAppleDm/FatCodex/releases/download/v0.3.0/FatCodex-0.3.0.apk"
private val release = UpdateRelease("v0.3.0", "Новая версия", UpdateApk("FatCodex-0.3.0.apk", APK_URL, 123))

@RunWith(AndroidJUnit4::class)
class UpdateTest {
    @get:Rule val main = MainDispatcherRule()

    private fun json() = release.json()
    private fun problem(expected: UpdateProblem, action: () -> Unit) {
        try { action(); fail("Expected $expected") } catch (error: UpdateException) { assertEquals(expected, error.problem) }
    }

    @Test fun `versions compare numerically and reject unstable or malformed tags`() {
        assertTrue(AppVersion.parse("v0.10.0")!! > AppVersion.parse("0.9.0")!!)
        assertTrue(AppVersion.parse("1.0.0")!! > AppVersion.parse("0.99.99")!!)
        assertEquals(AppVersion.parse("0.3.0"), AppVersion.parse("v0.3.0+build.42"))
        listOf("latest", "0.3.0-beta", "0.03.0", "0.3", "0.3.0.1", "9999999999999999999999999.0.0").forEach { assertNull(it, AppVersion.parse(it)) }
    }

    @Test fun `stable GitHub release and optional sha256 survive persistence`() {
        val json = json()
        json.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:" + "ab".repeat(32))
        val parsed = UpdateRelease.parse(json)
        assertEquals("ab".repeat(32), parsed.apk!!.sha256)
        assertEquals(parsed, UpdateRelease.parse(parsed.json()))
        assertEquals(release, UpdateRelease.parse(json()))
    }

    @Test fun `drafts and prereleases are never offered`() {
        problem(UpdateProblem.Release) { UpdateRelease.parse(json().put("draft", true)) }
        problem(UpdateProblem.Release) { UpdateRelease.parse(json().put("prerelease", true)) }
    }

    @Test fun `a release without an APK still provides notes and a release page`() {
        assertNull(UpdateRelease.parse(json().put("assets", JSONArray())).apk)
        val json = json()
        val assets = json.getJSONArray("assets")
        assets.put(JSONObject(assets.getJSONObject(0).toString()).put("name", "another-arm64.apk"))
        assertNull(UpdateRelease.parse(json).apk)
        assets.put(JSONObject(assets.getJSONObject(0).toString()).put("name", "FatCodex-universal.apk"))
        assertEquals("FatCodex-universal.apk", UpdateRelease.parse(json).apk!!.name)
    }

    @Test fun `APK URLs cannot redirect the user to another repository or cleartext host`() {
        listOf("http://github.com/MAppleDm/FatCodex/releases/download/v1/a.apk", "https://evil.example/a.apk",
            "https://github.com/other/app/releases/download/v1/a.apk", "https://user:pass@github.com/MAppleDm/FatCodex/releases/download/v1/a.apk").forEach { url ->
            val json = json()
            json.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url)
            problem(UpdateProblem.Release) { UpdateRelease.parse(json) }
        }
    }

    @Test fun `bad sizes and malformed checksum cannot be downloaded`() {
        val json = json()
        json.getJSONArray("assets").getJSONObject(0).put("size", -1)
        problem(UpdateProblem.Release) { UpdateRelease.parse(json) }
        json.getJSONArray("assets").getJSONObject(0).put("size", 100).put("digest", "sha256:bad")
        problem(UpdateProblem.Release) { UpdateRelease.parse(json) }
    }

    @Test fun `checking is explicit and distinguishes current available and absent releases`() {
        val fake = FakeUpdates()
        val model = UpdateViewModel(fake)
        assertEquals(0, fake.checks)
        assertEquals(UpdatePhase.Idle, model.state.value.phase)
        model.onCheck()
        assertEquals(UpdatePhase.Available, model.state.value.phase)
        fake.latest = release.copy(tag = "v0.2.0")
        model.onCheck()
        assertEquals(UpdatePhase.Current, model.state.value.phase)
        fake.latest = release.copy(tag = "v0.1.0")
        model.onCheck()
        assertEquals(UpdatePhase.Current, model.state.value.phase)
        fake.latest = null
        model.onCheck()
        assertEquals(UpdatePhase.NoReleases, model.state.value.phase)
    }

    @Test fun `network and rate limit errors are visible and retryable`() {
        val fake = FakeUpdates()
        val model = UpdateViewModel(fake)
        fake.failure = IOException("offline")
        model.onCheck()
        assertEquals(UpdateProblem.Network, model.state.value.problem)
        fake.failure = UpdateException(UpdateProblem.RateLimit)
        model.onCheck()
        assertEquals(UpdateProblem.RateLimit, model.state.value.problem)
        fake.failure = null
        model.onCheck()
        assertEquals(UpdatePhase.Available, model.state.value.phase)
    }

    @Test fun `double tap checks only once while a request is in flight`() {
        val fake = FakeUpdates().apply { waitForCheck = CompletableDeferred() }
        val model = UpdateViewModel(fake)
        model.onCheck()
        model.onCheck()
        assertEquals(1, fake.checks)
        assertEquals(UpdatePhase.Checking, model.state.value.phase)
        fake.waitForCheck!!.complete(Unit)
        assertEquals(UpdatePhase.Available, model.state.value.phase)
    }

    @Test fun `download is verified and verified again on install, canceled installer can be retried`() {
        val fake = FakeUpdates()
        val model = UpdateViewModel(fake)
        model.onCheck()
        model.onDownload()
        assertEquals(UpdatePhase.Ready, model.state.value.phase)
        assertEquals(1, fake.downloads)
        assertEquals(1, fake.verifications)
        var opened = 0
        model.onInstall { opened++ }
        assertEquals(1, opened)
        assertEquals(2, fake.verifications)
        model.onInstallProblem(UpdateProblem.Permission)
        model.onInstall { opened++ }
        assertEquals(2, opened)
        assertNull(model.state.value.problem)
        model.onCancel()
        assertNull(fake.saved)
        assertEquals(UpdatePhase.Available, model.state.value.phase)
    }

    @Test fun `corruption or a changed signing key removes the file and never opens the installer`() {
        val fake = FakeUpdates()
        val model = UpdateViewModel(fake)
        model.onCheck()
        model.onDownload()
        fake.verificationFailure = UpdateException(UpdateProblem.Signature)
        model.onInstall { fail("must not install") }
        assertEquals(UpdateProblem.Signature, model.state.value.problem)
        assertEquals(UpdatePhase.Available, model.state.value.phase)
        assertNull(fake.saved)
        fake.verificationFailure = UpdateException(UpdateProblem.Integrity)
        model.onDownload()
        assertEquals(UpdateProblem.Integrity, model.state.value.problem)
        assertNull(fake.saved)
    }

    @Test fun `a saved download is restored, an already installed update is cleaned up`() {
        val fake = FakeUpdates().apply { saved = PendingUpdate(4, release) }
        assertEquals(UpdatePhase.Ready, UpdateViewModel(fake).state.value.phase)
        fake.saved = PendingUpdate(5, release.copy(tag = "v0.2.0"))
        assertEquals(UpdatePhase.Idle, UpdateViewModel(fake).state.value.phase)
        assertNull(fake.saved)
    }

    @Test fun `a canceled in-flight download cannot later become installable and double taps do not enqueue twice`() {
        val fake = FakeUpdates().apply { waitForTransfer = CompletableDeferred() }
        val model = UpdateViewModel(fake)
        model.onCheck()
        model.onDownload()
        model.onDownload()
        assertEquals(1, fake.downloads)
        assertEquals(UpdatePhase.Downloading, model.state.value.phase)
        model.onCancel()
        fake.waitForTransfer!!.complete(Unit)
        assertEquals(UpdatePhase.Available, model.state.value.phase)
        assertEquals(0, fake.verifications)
        assertNull(fake.saved)
    }

    private fun packageInfo(code: Int = 3, version: String = "0.3.0", key: String = "abcd") = PackageInfo().apply {
        packageName = "dev.dietapp"
        @Suppress("DEPRECATION")
        versionCode = code
        versionName = version
        applicationInfo = ApplicationInfo().apply { minSdkVersion = 26 }
        signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(key))) }
    }

    @Test fun `package validation protects application id version code Android version and signing key`() {
        val installed = packageInfo(2, "0.2.0")
        validatePackage(packageInfo(), installed, release)
        problem(UpdateProblem.Incompatible) { validatePackage(packageInfo().apply { packageName = "other.app" }, installed, release) }
        problem(UpdateProblem.Incompatible) { validatePackage(packageInfo(code = 2), installed, release) }
        problem(UpdateProblem.Incompatible) { validatePackage(packageInfo(version = "0.4.0"), installed, release) }
        problem(UpdateProblem.Incompatible) { validatePackage(packageInfo().apply { applicationInfo!!.minSdkVersion = 99 }, installed, release) }
        problem(UpdateProblem.Signature) { validatePackage(packageInfo(key = "cdef"), installed, release) }
        problem(UpdateProblem.Signature) { validatePackage(packageInfo().apply { signingInfo = null }, installed, release) }
    }

    @Test fun `valid signing key rotation is supported`() {
        val installed = packageInfo()
        val candidate = packageInfo(key = "cdef")
        shadowOf(candidate.signingInfo!!).setPastSigningCertificates(arrayOf(Signature("abcd"), Signature("cdef")))
        @Suppress("DEPRECATION")
        installed.versionCode = 2
        validatePackage(candidate, installed, release)
    }

    @Test fun `system download persists and reports running paused and completed states`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = GitHubUpdates(context)
        repository.cancel()
        val pending = repository.download(release)
        assertEquals(pending, GitHubUpdates(context).pending())
        val manager = context.getSystemService(android.app.DownloadManager::class.java)
        val request = shadowOf(shadowOf(manager).getRequest(pending.id))
        request.setStatus(android.app.DownloadManager.STATUS_RUNNING)
        request.setBytesSoFar(50)
        request.setTotalSize(123)
        assertEquals(UpdateTransfer.Progress(50, 123), repository.transfer(pending))
        request.setStatus(android.app.DownloadManager.STATUS_PAUSED)
        assertEquals(UpdateTransfer.Progress(50, 123, paused = true), repository.transfer(pending))
        request.setStatus(android.app.DownloadManager.STATUS_SUCCESSFUL)
        assertEquals(UpdateTransfer.Complete, repository.transfer(pending))
        repository.cancel()
        assertNull(repository.pending())
    }

    @Test fun `installer content URI grants only read access`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.getExternalFilesDir(null), "updates/update.apk")
        // FileProvider's canonical-path checks assume Android '/' separators, which a Windows JVM cannot supply.
        val intent = if (File.separatorChar == '/') UpdateInstaller.intent(context, file)
            else UpdateInstaller.intent(android.net.Uri.parse("content://${context.packageName}.files/updates/update.apk"))
        assertEquals("content", intent.data!!.scheme)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test fun `wrong file size and digest are rejected before Android reads the APK`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = GitHubUpdates(context)
        val file = File(context.getExternalFilesDir(null), "updates/update.apk")
        file.parentFile!!.mkdirs()
        file.writeText("not an apk")
        try {
            try { repository.verifiedFile(PendingUpdate(1, release)); fail("size") }
            catch (e: UpdateException) { assertEquals(UpdateProblem.Integrity, e.problem) }
            val badDigest = release.copy(apk = release.apk!!.copy(size = file.length(), sha256 = "00".repeat(32)))
            try { repository.verifiedFile(PendingUpdate(1, badDigest)); fail("digest") }
            catch (e: UpdateException) { assertEquals(UpdateProblem.Integrity, e.problem) }
        } finally { file.delete() }
    }
}

private class FakeUpdates : UpdateRepository {
    var latest: UpdateRelease? = release
    var failure: Exception? = null
    var verificationFailure: Exception? = null
    var waitForCheck: CompletableDeferred<Unit>? = null
    var waitForTransfer: CompletableDeferred<Unit>? = null
    var saved: PendingUpdate? = null
    var checks = 0
    var downloads = 0
    var verifications = 0
    override suspend fun latest(): UpdateRelease? { checks++; waitForCheck?.await(); failure?.let { throw it }; return latest }
    override fun pending() = saved
    override fun download(release: UpdateRelease): PendingUpdate { downloads++; return PendingUpdate(1, release).also { saved = it } }
    override suspend fun transfer(pending: PendingUpdate): UpdateTransfer { waitForTransfer?.await(); return UpdateTransfer.Complete }
    override suspend fun verifiedFile(pending: PendingUpdate): File { verifications++; verificationFailure?.let { throw it }; return File("update.apk") }
    override fun cancel() { saved = null }
}
