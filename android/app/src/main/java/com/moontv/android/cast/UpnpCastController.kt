package com.moontv.android.cast

import android.content.Context
import android.util.Log
import com.yinnho.upnpcast.CastOptions
import com.yinnho.upnpcast.DLNACast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class UpnpCastController(context: Context) : DlnaController {
    init {
        DLNACast.init(context.applicationContext)
    }

    override suspend fun discover(timeoutMs: Long): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val renderers = coroutineScope {
            DLNACast.search(timeoutMs)
                .map { device ->
                    async {
                        val isRenderer = device.isTV || DlnaRendererProbe.isRenderer(device.id)
                        device.takeIf { isRenderer }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
        renderers
            .distinctBy { it.id }
            .map { DlnaDevice(it.id, it.name, it.address, true) }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun cast(device: DlnaDevice, media: CastMedia): Boolean =
        withContext(Dispatchers.IO) {
            DLNACast.clearProgressCache()
            val target = DLNACast.Device(device.id, device.name, device.address, device.isTv)
            for ((index, profile) in DlnaCastProfiles.forUrl(media.url).withIndex()) {
                if (index > 0) {
                    runCatching { DLNACast.stop() }
                    delay(COMPATIBILITY_RETRY_DELAY_MS)
                }

                val accepted = DLNACast.castToDevice(
                    device = target,
                    url = media.url,
                    title = media.title,
                    options = CastOptions(
                        metadata = if (profile.emptyMetadata) "" else null,
                        mimeType = profile.mimeType,
                    ),
                )
                Log.i(TAG, "DLNA cast profile ${profile.name}: accepted=$accepted")
                if (accepted) {
                    if (media.positionMs > 1_000) {
                        DLNACast.seek(media.positionMs)
                    }
                    return@withContext true
                }
            }
            false
        }

    override suspend fun play(): Boolean = withContext(Dispatchers.IO) { DLNACast.play() }

    override suspend fun pause(): Boolean = withContext(Dispatchers.IO) { DLNACast.pause() }

    override suspend fun stop(): Boolean = withContext(Dispatchers.IO) { DLNACast.stop() }

    override suspend fun seek(positionMs: Long): Boolean =
        withContext(Dispatchers.IO) { DLNACast.seek(positionMs.coerceAtLeast(0)) }

    override suspend fun playbackState(): RemotePlaybackState = withContext(Dispatchers.IO) {
        when (DLNACast.getPlaybackState()) {
            DLNACast.PlaybackState.IDLE -> RemotePlaybackState.IDLE
            DLNACast.PlaybackState.PLAYING -> RemotePlaybackState.PLAYING
            DLNACast.PlaybackState.PAUSED -> RemotePlaybackState.PAUSED
            DLNACast.PlaybackState.STOPPED -> RemotePlaybackState.STOPPED
            DLNACast.PlaybackState.BUFFERING -> RemotePlaybackState.BUFFERING
            DLNACast.PlaybackState.ERROR -> RemotePlaybackState.ERROR
        }
    }

    override suspend fun progress(): Pair<Long, Long>? =
        withContext(Dispatchers.IO) { DLNACast.getProgressRealtime() }

    override suspend fun volume(): Pair<Int?, Boolean?>? =
        withContext(Dispatchers.IO) { DLNACast.getVolume() }

    override fun close() {
        DLNACast.cleanup()
    }

    private companion object {
        const val TAG = "UpnpCastController"
        const val COMPATIBILITY_RETRY_DELAY_MS = 250L
    }
}
