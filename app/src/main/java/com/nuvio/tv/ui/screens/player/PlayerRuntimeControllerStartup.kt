package com.nuvio.tv.ui.screens.player

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

internal fun PlayerRuntimeController.attachHostActivity(activity: Activity?) {
    hostActivityRef = activity?.let { WeakReference(it) }
}

internal fun PlayerRuntimeController.startInitialPlaybackIfNeeded() {
    if (initialPlaybackStarted) return

    initialPlaybackStarted = true

    // Persist binge group from navigation args so that subsequent plays
    // (from CW, Details, or next-episode) can reuse the same source group.
    val bg = navigationArgs.bingeGroup
    val cid = contentId
    if (bg != null && cid != null) {
        scope.launch(kotlinx.coroutines.NonCancellable) {
            bingeGroupCacheDataStore.save(cid, bg)
        }
    }

    Log.d("PlayerStartup", "startInitialPlayback: streamUrl=${initialStreamUrl.take(80)}")
    preparePlaybackBeforeStart(
        url = currentStreamUrl,
        headers = currentHeaders,
        loadSavedProgress = !navigationArgs.startFromBeginning
    )
}

internal fun PlayerRuntimeController.currentHostActivity(): Activity? {
    return hostActivityRef?.get()
}
