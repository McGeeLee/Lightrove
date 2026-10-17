package com.mcgeelee.photos.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Regresses B-frame seeking with the same decoder selection used in the media details screen. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoPlaybackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialSeekRendersMatchedPresentationTimestamp() {
        val renderedTimes = CopyOnWriteArrayList<Long>()
        val player = showVideo(renderedTimes)
        compose.waitUntil(timeoutMillis = 20_000L) { renderedTimes.isNotEmpty() }
        compose.runOnIdle {
            assertEquals("The visible frame must match the search hit", 5_000_000L, renderedTimes.first())
            assertEquals(5_000L, player.currentPosition)
        }
    }

    @Test fun seekingBackAfterPlaybackRendersMatchedFrameAgain() {
        val renderedTimes = CopyOnWriteArrayList<Long>()
        val player = showVideo(renderedTimes)
        compose.waitUntil(timeoutMillis = 20_000L) { renderedTimes.isNotEmpty() }
        compose.runOnIdle { player.play() }
        compose.waitUntil(timeoutMillis = 15_000L) { renderedTimes.any { it >= 7_000_000L } }
        compose.runOnIdle {
            player.pause()
            renderedTimes.clear()
            player.seekTo(5_000L)
        }
        compose.waitUntil(timeoutMillis = 15_000L) { renderedTimes.contains(5_000_000L) }
        compose.runOnIdle {
            assertEquals("Seeking back must update the rendered frame", 5_000_000L, renderedTimes.last())
            assertEquals(5_000L, player.currentPosition)
        }
    }

    private fun showVideo(renderedTimes: MutableList<Long>): ExoPlayer {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "video-seek-regression.mp4")
        instrumentation.context.assets.open("codex_test_scenes.mp4").use { input ->
            fixture.outputStream().use(input::copyTo)
        }
        lateinit var actualPlayer: ExoPlayer
        compose.setContent {
            val player = remember {
                createVideoPreviewPlayer(context).apply {
                    setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
                        renderedTimes += presentationTimeUs
                    }
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)), 5_000L)
                    playWhenReady = false
                    prepare()
                    actualPlayer = this
                }
            }
            AndroidView(factory = { PlayerView(it).apply { this.player = player } })
            DisposableEffect(player) { onDispose { player.release() } }
        }
        compose.waitForIdle()
        return actualPlayer
    }
}
