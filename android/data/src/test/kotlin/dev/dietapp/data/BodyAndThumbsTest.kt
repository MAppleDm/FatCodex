package dev.dietapp.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dietapp.data.domain.Activity
import dev.dietapp.data.domain.Energy
import dev.dietapp.data.domain.MessageSource
import dev.dietapp.data.domain.Sex
import dev.dietapp.data.media.PhotoThumbs
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The formula, what the person said about themselves, and the small picture that stays in the chat under a photo. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real decoding: bytes that are not a picture are not one
class BodyAndThumbsTest {
    private lateinit var env: TestEnv
    private val day = LocalDate.parse("2026-09-30")
    private val now = ZonedDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneOffset.UTC)

    @Before fun setUp() {
        env = TestEnv()
    }

    @After fun tearDown() = env.close()

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    // ---------- the formula ----------

    @Test fun `the energy at rest follows Mifflin-St Jeor, for a man and for a woman`() {
        // 10 * 80 + 6.25 * 180 - 5 * 30 + 5
        assertEquals(1780.0, Energy.bmr(Sex.Male, 30, 180, 80.0), 0.001)
        // 10 * 60 + 6.25 * 165 - 5 * 30 - 161
        assertEquals(1320.25, Energy.bmr(Sex.Female, 30, 165, 60.0), 0.001)
    }

    @Test fun `a day costs the energy at rest times the activity, to ten kcal`() {
        val e = Energy.estimate(Sex.Male, 30, 180, 80.0, Activity.Moderate)
        assertEquals(1780, e.bmr)
        assertEquals("1780 * 1.55 = 2759", 2760, e.tdee)
        assertEquals(1.55, e.factor, 0.0)
        assertEquals(1320, Energy.estimate(Sex.Female, 30, 165, 60.0, Activity.Sedentary).bmr)
        assertEquals("1320.25 * 1.2 = 1584.3", 1580, Energy.estimate(Sex.Female, 30, 165, 60.0, Activity.Sedentary).tdee)
    }

    @Test fun `more activity never costs less`() {
        val days = Activity.entries.map { Energy.estimate(Sex.Male, 40, 175, 75.0, it).tdee }
        assertEquals(days.sorted(), days)
        assertEquals(1.2, Activity.Sedentary.factor, 0.0)
        assertEquals(1.9, Activity.Extreme.factor, 0.0)
    }

    // ---------- what the person said ----------

    @Test fun `nothing is known at first, and the first-run questions are still to ask`() = runTest {
        val s = env.body.state.first()
        assertNull(s.sex); assertNull(s.age); assertNull(s.heightCm); assertNull(s.activity); assertNull(s.weightKg); assertNull(s.estimate)
        assertFalse(env.body.asked.first())
    }

    @Test fun `the estimate appears only when everything is known`() = runTest {
        env.body.setSex(Sex.Male)
        env.body.setAge(30)
        env.body.setHeight(180)
        env.body.setActivity(Activity.Moderate)
        assertNull("no weight yet", env.body.state.first().estimate)
        env.body.setWeight(80.0)
        val s = env.body.state.first()
        assertEquals(30, s.age)
        assertEquals(80.0, s.weightKg!!, 0.0)
        assertEquals(2760, s.estimate!!.tdee)
    }

    @Test fun `the age moves on by itself, because the year of birth is what is kept`() = runTest {
        env.body.setAge(30)
        assertEquals(30, env.body.state.first().age)
        env.clock.now = java.time.Instant.parse("2027-09-30T05:15:00Z") // a year later
        assertEquals(31, env.body.state.first().age)
    }

    @Test fun `the weight is the diary's, so a weigh-in made in the chat changes the estimate`() = runTest {
        env.body.setSex(Sex.Female); env.body.setAge(30); env.body.setHeight(165); env.body.setActivity(Activity.Light)
        env.body.setWeight(60.0)
        val before = env.body.state.first().estimate!!.tdee
        env.localDiary.addWeight(day, 70.0, env.clock.instant().plusSeconds(60)) // "вес 70"
        val after = env.body.state.first()
        assertEquals(70.0, after.weightKg!!, 0.0)
        assertEquals("(10 * 60 + 6.25 * 165 - 150 - 161) * 1.375 = 1815", 1820, before)
        assertEquals("ten kilos more: (1420.25) * 1.375 = 1953", 1950, after.estimate!!.tdee)
    }

    // ---------- the goal: what a day costs, corrected ----------

    private suspend fun answered(adjustment: Int = 0) {
        env.body.setSex(Sex.Male); env.body.setAge(30); env.body.setHeight(180); env.body.setActivity(Activity.Moderate)
        env.body.setWeight(80.0)
        if (adjustment != 0) env.body.setAdjustment(adjustment)
    }

    @Test fun `there is no goal until everything is known, then it is what a day costs`() = runTest {
        assertNull(env.body.state.first().goal)
        assertNull(env.diary.observeProfile().first().calorieGoal)
        answered()
        assertEquals(2760, env.body.state.first().goal)
        assertEquals("the diary's goal is the same one", 2760, env.diary.observeProfile().first().calorieGoal)
    }

    @Test fun `the correction moves the goal, and is kept`() = runTest {
        answered(adjustment = -500)
        val s = env.body.state.first()
        assertEquals(-500, s.adjustment)
        assertEquals(2260, s.goal)
        assertFalse(s.goalLimited)
        assertEquals("it survives a restart", -500, dev.dietapp.data.local.BodyStore(env.context).profile.value.adjustment)
        env.body.setAdjustment(300)
        assertEquals(3060, env.diary.observeProfile().first().calorieGoal)
    }

    @Test fun `the goal follows the weight, because what a day costs does`() = runTest {
        answered(adjustment = -500)
        env.localDiary.addWeight(day, 70.0, env.clock.instant().plusSeconds(60))
        // BMR 1680, times 1.55 = 2604 -> 2600, less 500
        assertEquals(2100, env.diary.observeProfile().first().calorieGoal)
    }

    @Test fun `the correction is held to a sensible range`() = runTest {
        answered()
        env.body.setAdjustment(-5000)
        assertEquals(-1000, env.body.state.first().adjustment)
        env.body.setAdjustment(5000)
        assertEquals(1000, env.body.state.first().adjustment)
    }

    @Test fun `the goal never goes below the floor, and says so`() = runTest {
        env.body.setSex(Sex.Female); env.body.setAge(60); env.body.setHeight(150); env.body.setActivity(Activity.Sedentary)
        env.body.setWeight(45.0)
        // 10 * 45 + 6.25 * 150 - 300 - 161 = 926.5, times 1.2 = 1111.8
        val s = env.body.state.first()
        assertEquals(1110, s.estimate!!.tdee)
        assertEquals(1200, s.goal)
        assertTrue(s.goalLimited)
        env.body.setAdjustment(-300)
        assertEquals("a correction down changes nothing below the floor", 1200, env.body.state.first().goal)
    }

    @Test fun `a goal of a few thousand is allowed, an absurd one is held at the ceiling`() = runTest {
        env.body.setSex(Sex.Male); env.body.setAge(20); env.body.setHeight(220); env.body.setActivity(Activity.Extreme)
        env.body.setWeight(250.0)
        // 2500 + 1375 - 100 + 5 = 3780, times 1.9 = 7182
        val s = env.body.state.first()
        assertEquals(7180, s.estimate!!.tdee)
        assertEquals(6000, s.goal)
        assertFalse(s.goalLimited)
    }

    @Test fun `changing the weight again the same day corrects the weigh-in, it does not pile up new ones`() = runTest {
        env.body.setWeight(80.0)
        env.body.setWeight(79.0)
        env.body.setWeight(78.5)
        assertEquals(1, env.db.weights().all().size)
        assertEquals(78.5, env.db.weights().all().single().kg, 0.0)
    }

    @Test fun `finishing or skipping the questions is remembered`() = runTest {
        env.body.finishOnboarding()
        assertTrue(env.body.asked.first())
    }

    @Test fun `it all goes with erase all`() = runTest {
        env.body.setSex(Sex.Male); env.body.setAge(30); env.body.setHeight(180); env.body.setActivity(Activity.High)
        env.body.setAdjustment(-400)
        env.body.finishOnboarding()
        env.auth.logout()
        val s = env.body.state.first()
        assertNull(s.sex); assertNull(s.age); assertNull(s.heightCm); assertNull(s.activity)
        assertEquals(0, s.adjustment)
        assertFalse(env.body.asked.first())
    }

    // ---------- the small picture in the chat ----------

    @Test fun `a photo message keeps a small picture of itself`() = runTest {
        env.localDiary.sendMessage(null, jpeg(1024, 768), day, now, MessageSource.Photo)
        val id = env.db.messages().observeDay(day.toString()).first().single().id
        val thumb = env.localDiary.thumbnail(id)
        assertNotNull(thumb)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(thumb, 0, thumb!!.size, bounds)
        assertTrue("a thumbnail, not the photo: ${bounds.outWidth}x${bounds.outHeight}", maxOf(bounds.outWidth, bounds.outHeight) <= PhotoThumbs.MAX_SIDE)
    }

    @Test fun `a message without a photo has no picture`() = runTest {
        env.localDiary.sendMessage("гречка 200 г", null, day, now, MessageSource.Text)
        val id = env.db.messages().observeDay(day.toString()).first().single().id
        assertNull(env.localDiary.thumbnail(id))
    }

    @Test fun `bytes that are not a picture leave nothing behind`() = runTest {
        val thumbs = PhotoThumbs(Files.createTempDirectory("t").toFile())
        assertFalse(thumbs.save("x", byteArrayOf(1, 2, 3)))
        assertNull(thumbs.read("x"))
    }

    @Test fun `taking back a message takes its picture too`() = runTest {
        env.localDiary.sendMessage(null, jpeg(800, 600), day, now, MessageSource.Photo)
        val id = env.db.messages().observeDay(day.toString()).first().single().id
        assertNotNull(env.localDiary.thumbnail(id))
        env.localDiary.discardOutbox(id)
        assertNull(env.localDiary.thumbnail(id))
    }

    @Test fun `erase all deletes the pictures`() = runTest {
        env.localDiary.sendMessage(null, jpeg(800, 600), day, now, MessageSource.Photo)
        val id = env.db.messages().observeDay(day.toString()).first().single().id
        env.auth.logout()
        assertNull(env.localDiary.thumbnail(id))
    }
}
