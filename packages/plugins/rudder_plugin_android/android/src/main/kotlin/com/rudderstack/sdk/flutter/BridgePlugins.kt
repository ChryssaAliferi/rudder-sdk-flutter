package com.rudderstack.sdk.flutter

import android.content.Context
import android.util.Log
import com.rudderstack.sdk.kotlin.core.Analytics
import com.rudderstack.sdk.kotlin.core.internals.models.Event
import com.rudderstack.sdk.kotlin.core.internals.plugins.Plugin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * SPIKE: bridge-owned Kotlin SDK plugins that keep 3.4 behavior the Kotlin SDK
 * dropped. See FLT-005 (payload diff) and D7 (opt-out emulation).
 */

internal object BridgeLog {
    private const val TAG = "RudderFlutter"
    fun warn(message: String) = Log.w(TAG, message)
    fun error(message: String) = Log.e(TAG, message)
}

/**
 * D7 / FLT-409: emulates the legacy `optOut(bool)` API, which the Kotlin SDK doesn't have.
 *
 * Runs first (PreProcess) and drops every event while opted out. The flag is persisted
 * per write key so it survives restarts.
 *
 * SPIKE gaps: device-mode integrations still initialize while opted out; the legacy
 * `rl_opt_status` value isn't migrated yet (FLT-504).
 */
internal class OptOutPlugin(context: Context, writeKey: String) : Plugin {
    override val pluginType = Plugin.PluginType.PreProcess
    override lateinit var analytics: Analytics

    private val prefs = context.getSharedPreferences("rl_prefs_flutter_v4-$writeKey", Context.MODE_PRIVATE)

    @Volatile
    var isOptedOut: Boolean = prefs.getBoolean(KEY, false)
        set(value) {
            field = value
            prefs.edit().putBoolean(KEY, value).apply()
        }

    override suspend fun intercept(event: Event): Event? = if (isOptedOut) null else event

    private companion object {
        const val KEY = "opt_out"
    }
}

/**
 * FLT-005 §2 / FLT-207: the Kotlin SDK no longer writes `context.device.token`,
 * `advertisingId` or `adTrackingEnabled`. 3.4 apps set these through `putDeviceToken`
 * and `putAdvertisingId`; this plugin writes them back with the same keys so push and
 * attribution destinations keep receiving them.
 *
 * Runs OnProcess, after the SDK's DeviceInfoPlugin (PreProcess), and merges into the
 * existing `context.device` object.
 */
internal object DeviceContextPlugin : Plugin {
    override val pluginType = Plugin.PluginType.OnProcess
    override lateinit var analytics: Analytics

    // Static, like in 3.4: these can be set before the SDK is initialized.
    @Volatile var deviceToken: String? = null
    @Volatile var advertisingId: String? = null

    override suspend fun intercept(event: Event): Event {
        val token = deviceToken
        val adId = advertisingId
        if (token == null && adId == null) return event

        val device = event.context["device"]?.jsonObject.orEmpty().toMutableMap()
        token?.let { device["token"] = JsonPrimitive(it) }
        adId?.let {
            device["advertisingId"] = JsonPrimitive(it)
            device["adTrackingEnabled"] = JsonPrimitive(true)
        }
        event.context = JsonObject(event.context + ("device" to JsonObject(device)))
        return event
    }
}
