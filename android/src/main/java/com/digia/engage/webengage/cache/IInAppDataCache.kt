package com.digia.engage.webengage.cache

import com.webengage.sdk.android.actions.render.InAppNotificationData

/**
 * Abstraction for WebEngage in-app notification data caching.
 *
 * Mirrors the MoEngage [ICampaignCache] pattern: when [onInAppNotificationPrepared] fires and
 * [InAppNotificationData.setShouldRender] is set to false (self-handled), the data object is stored
 * here keyed by its experimentId. The [WebEngageEventDispatcher] retrieves it later to call the
 * correct analytics track methods when the custom UI reports Impressed / Clicked / Dismissed.
 */
internal interface IInAppDataCache {

    /** Stores [data] keyed by [experimentId]. */
    fun put(experimentId: String, data: InAppNotificationData)

    /** Returns the cached [InAppNotificationData] for [experimentId], or `null` when absent. */
    fun get(experimentId: String): InAppNotificationData?

    /** Removes the entry for [experimentId] (call after campaign lifecycle ends on dismiss). */
    fun remove(experimentId: String)

    /** Evicts all entries (call during plugin teardown). */
    fun clear()

    /** Number of currently cached campaigns. */
    val count: Int

    /** Read-only list of currently cached experiment IDs (for diagnostics). */
    val experimentIds: List<String>
}
