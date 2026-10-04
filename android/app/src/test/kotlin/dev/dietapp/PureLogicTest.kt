package dev.dietapp

import dev.dietapp.media.ImageSizing
import dev.dietapp.voice.VoiceDecision
import dev.dietapp.voice.VoiceFallbackPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFallbackPolicyTest {
    @Test fun `problems with the on-device recogniser fall back to the server`() {
        // SERVER, CLIENT, BUSY, TOO_MANY_REQUESTS, SERVER_DISCONNECTED, LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE, CANNOT_CHECK_SUPPORT
        listOf(4, 5, 8, 10, 11, 12, 13, 14).forEach {
            assertEquals("error $it", VoiceDecision.UseServer, VoiceFallbackPolicy.decide(it))
        }
    }

    @Test fun `unknown future error codes also fall back`() {
        assertEquals(VoiceDecision.UseServer, VoiceFallbackPolicy.decide(99))
    }

    @Test fun `silence and no match are just told to the user`() {
        assertEquals(VoiceDecision.Tell(Texts.VOICE_NO_MATCH), VoiceFallbackPolicy.decide(6))
        assertEquals(VoiceDecision.Tell(Texts.VOICE_NO_MATCH), VoiceFallbackPolicy.decide(7))
    }

    @Test fun `no permission asks for it`() {
        assertEquals(VoiceDecision.AskPermission, VoiceFallbackPolicy.decide(9))
    }

    @Test fun `a broken microphone does not fall back to recording with the same microphone`() {
        assertEquals(VoiceDecision.Tell(Texts.VOICE_AUDIO), VoiceFallbackPolicy.decide(3))
    }

    @Test fun `network errors are not retried through another network path`() {
        assertEquals(VoiceDecision.Tell(Texts.VOICE_NO_NETWORK), VoiceFallbackPolicy.decide(1))
        assertEquals(VoiceDecision.Tell(Texts.VOICE_NO_NETWORK), VoiceFallbackPolicy.decide(2))
    }
}

class VoiceWithoutServerTest {
    private val local = Texts.let { dev.dietapp.data.repo.LOCAL_SPEECH_MESSAGE }

    @Test fun `with no server a recogniser problem is told, not recorded for a server that does not exist`() {
        listOf(4, 5, 8, 10, 11, 12, 13, 14, 99).forEach {
            assertEquals("error $it", VoiceDecision.Tell(local), VoiceFallbackPolicy.decide(it, serverAvailable = false))
        }
    }

    @Test fun `no network on the recogniser points to the offline pack when there is no server`() {
        assertEquals(VoiceDecision.Tell(local), VoiceFallbackPolicy.decide(1, serverAvailable = false))
        assertEquals(VoiceDecision.Tell(local), VoiceFallbackPolicy.decide(2, serverAvailable = false))
    }

    @Test fun `silence, permission and microphone errors are the same with or without a server`() {
        listOf(3, 6, 7, 9).forEach {
            assertEquals("error $it", VoiceFallbackPolicy.decide(it), VoiceFallbackPolicy.decide(it, serverAvailable = false))
        }
    }
}

class ImageSizingTest {
    @Test fun `a large landscape photo is scaled to 1024 on the long side`() {
        assertEquals(1024 to 768, ImageSizing.fit(4000, 3000))
    }

    @Test fun `a large portrait photo is scaled the same way`() {
        assertEquals(768 to 1024, ImageSizing.fit(3000, 4000))
    }

    @Test fun `small images are never enlarged`() {
        assertEquals(800 to 600, ImageSizing.fit(800, 600))
        assertEquals(1024 to 1024, ImageSizing.fit(1024, 1024))
    }

    @Test fun `aspect ratio is kept and sides never collapse to zero`() {
        val (w, h) = ImageSizing.fit(10_000, 10)
        assertEquals(1024, w)
        assertTrue(h >= 1)
        assertEquals(1024 to 683, ImageSizing.fit(1500, 1000))
    }

    @Test fun `rotation is normalised`() {
        assertEquals(90, ImageSizing.normalizeRotation(90))
        assertEquals(270, ImageSizing.normalizeRotation(-90))
        assertEquals(0, ImageSizing.normalizeRotation(360))
        assertEquals(180, ImageSizing.normalizeRotation(540))
    }

    @Test fun `the quality is the agreed 80 percent`() {
        assertEquals(80, ImageSizing.JPEG_QUALITY)
        assertEquals(1024, ImageSizing.MAX_SIDE)
    }
}
