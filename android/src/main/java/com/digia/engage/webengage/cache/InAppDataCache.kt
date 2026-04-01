package com.digia.engage.webengage.cache

import com.webengage.sdk.android.actions.render.InAppNotificationData

/**
 * In-memory implementation of [IInAppDataCache].
 *
 * Backed by a plain [MutableMap]; entries are evicted per-campaign on [remove] (post-dismiss) and
 * globally on [clear] (teardown).
 *
 * Swap this with an LRU or persistent cache by implementing [IInAppDataCache] and injecting it into
 * [WebEngagePlugin] — no other code changes required.
 */
internal class InAppDataCache : IInAppDataCache {

    private val store = mutableMapOf<String, InAppNotificationData>()

    override fun put(experimentId: String, data: InAppNotificationData) {
        store[experimentId] = data
    }

    override fun get(experimentId: String): InAppNotificationData? = store[experimentId]

    override fun remove(experimentId: String) {
        store.remove(experimentId)
    }

    override fun clear() = store.clear()

    override val count: Int
        get() = store.size

    override val experimentIds: List<String>
        get() = store.keys.toList()
}
