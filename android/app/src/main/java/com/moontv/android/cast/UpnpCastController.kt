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
        var renderers = discoverOnce(timeoutMs)
        if (renderers.isEmpty()) {
            delay(DISCOVERY_RETRY_DELAY_MS)
            renderers = discoverOnce(DISCOVERY_RETRY_TIMEOUT_MS)
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
            var attemptIndex = 0
            val candidateUrls = mutableListOf(media.url)
            var candidateIndex = 0
            while (candidateIndex < candidateUrls.size) {
                val candidateUrl = candidateUrls[candidateIndex]
                for (profile in DlnaCastProfiles.forUrl(candidateUrl)) {
                    if (attemptIndex > 0) {
                        runCatching { DLNACast.stop() }
                        delay(COMPATIBILITY_RETRY_DELAY_MS)
                    }

                    var accepted = DLNACast.castToDevice(
                        device = target,
                        url = candidateUrl,
                        title = media.title,
                        options = CastOptions(
                            metadata = if (profile.emptyMetadata) "" else null,
                            mimeType = profile.mimeType,
                        ),
                    )
                    if (!accepted) {
                        delay(DELAYED_PLAY_RETRY_MS)
                        accepted = DLNACast.play()
                    }
                    Log.i(
                        TAG,
                        "DLNA cast attempt=$attemptIndex profile=${profile.name}: accepted=$accepted",
                    )
                    if (accepted) {
                        if (media.positionMs > 1_000) {
                            DLNACast.seek(media.positionMs)
                        }
                        return@withContext true
                    }
                    attemptIndex += 1
                }
                if (candidateIndex == 0) {
                    HlsVariantResolver.resolveVariant(media.url)?.let(candidateUrls::add)
                }
                candidateIndex += 1
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

    private suspend fun discoverOnce(timeoutMs: Long): List<DLNACast.Device> = coroutineScope {
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

    private companion object {
        const val TAG = "UpnpCastController"
        const val COMPATIBILITY_RETRY_DELAY_MS = 250L
        const val DELAYED_PLAY_RETRY_MS = 1_000L
        const val DISCOVERY_RETRY_DELAY_MS = 300L
        const val DISCOVERY_RETRY_TIMEOUT_MS = 3_500L
    }
}
