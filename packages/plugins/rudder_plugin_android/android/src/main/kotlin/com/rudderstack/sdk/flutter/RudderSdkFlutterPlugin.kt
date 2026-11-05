package com.rudderstack.sdk.flutter

import android.app.Application
import android.content.Context
import com.rudderstack.sdk.kotlin.android.Analytics
import com.rudderstack.sdk.kotlin.android.models.reset.ResetEntries
import com.rudderstack.sdk.kotlin.android.models.reset.ResetOptions
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.serialization.json.JsonObject

/**
 * SPIKE (FLT-201): Android bridge for rudder_sdk_flutter on the Kotlin SDK.
 *
 * Keeps the existing "rudder_sdk_flutter" method-channel contract, so the current Dart
 * packages work unchanged. Replaces the legacy Java bridge and its own lifecycle,
 * session, and preference managers: lifecycle events and sessions now come from the
 * Kotlin SDK itself (FLT-204).
 *
 * Not in this spike: identity migration (FLT-501), legacy queue drain (FLT-506),
 * multiple instances (FLT-401), device-mode integrations (FLT-206), consent (FLT-412),
 * Flutter library stamp (FLT-420).
 */
class RudderSdkFlutterPlugin : FlutterPlugin, MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "rudder_sdk_flutter")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // The Analytics instance is process-wide and outlives the engine (hot restart,
        // add-to-app); only the channel is torn down.
        channel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val args = call.arguments.asMap().orEmpty()

        // Static methods: allowed before initialization, as in 3.4.
        when (call.method) {
            "initializeSDK" -> return run { initialize(args); result.success(null) }
            "putDeviceToken" -> return run {
                (args["deviceToken"] as? String)?.takeIf { it.isNotEmpty() }?.let { DeviceContextPlugin.deviceToken = it }
                result.success(null)
            }
            "putAdvertisingId" -> return run {
                (args["advertisingId"] as? String)?.takeIf { it.isNotEmpty() }?.let { DeviceContextPlugin.advertisingId = it }
                result.success(null)
            }
            "putAnonymousId" -> return run {
                // D12: deprecated; the Kotlin SDK has no anonymousId setter.
                BridgeLog.warn("putAnonymousId is deprecated and has no effect in Flutter 4.0.")
                result.success(null)
            }
        }

        val analytics = Companion.analytics ?: run {
            BridgeLog.error("RudderClient is not initialized. Initialize it before calling '${call.method}'.")
            result.success(null)
            return
        }

        when (call.method) {
            "identify" -> analytics.identify(
                userId = args["userId"] as? String ?: "",
                traits = buildTraits(args["traits"].asMap()),
                options = buildOption(args["options"].asMap()),
            )
            "track" -> analytics.track(
                name = args["eventName"] as String,
                properties = args["properties"].asMap().toJsonObject(),
                options = buildOption(args["options"].asMap()),
            )
            "screen" -> analytics.screen(
                screenName = args["screenName"] as String,
                category = args["category"] as? String ?: "",
                properties = args["properties"].asMap().toJsonObject(),
                options = buildOption(args["options"].asMap()),
            )
            "group" -> analytics.group(
                groupId = args["groupId"] as String,
                traits = buildTraits(args["groupTraits"].asMap()),
                options = buildOption(args["options"].asMap()),
            )
            "alias" -> analytics.alias(
                newId = args["newId"] as String,
                previousId = args["previousId"] as? String ?: "",
                options = buildOption(args["options"].asMap()),
            )
            "reset" -> {
                // 3.4 semantics: reset(clearAnonymousId) always clears userId and traits.
                // [verify] whether 3.4 also refreshed the session; the Kotlin default does.
                val clearAnonymousId = args["clearAnonymousId"] as? Boolean ?: false
                analytics.reset(ResetOptions(entries = ResetEntries(anonymousId = clearAnonymousId)))
            }
            "optOut" -> optOutPlugin?.isOptedOut = args["optOut"] as? Boolean ?: false
            "startSession" -> analytics.startSession(args["sessionId"].asLong())
            "endSession" -> analytics.endSession()
            "getSessionId" -> return result.success(analytics.sessionId)
            "getRudderContext" -> return result.success(
                // SPIKE: 3.4 returned the whole legacy context; the Kotlin SDK only exposes
                // identity. Enough for the documented use (traits, anonymousId).
                mapOf(
                    "traits" to (analytics.traits ?: JsonObject(emptyMap())).toPlatformValue(),
                    "anonymousId" to analytics.anonymousId,
                    "userId" to analytics.userId,
                )
            )
            // New in 4.0 (FLT-405); no Dart API yet.
            "flush" -> analytics.flush()
            else -> return result.notImplemented()
        }
        result.success(null)
    }

    private fun initialize(args: Map<String, Any?>) {
        if (Companion.analytics != null) {
            // Hot restart / second engine: keep the existing instance, as 3.4 did.
            return
        }
        val writeKey = args["writeKey"] as String
        val config = args["config"].asMap().orEmpty()

        // FLT-501 / FLT-506 go here, before the Analytics instance is created.

        val analytics = Analytics(buildConfiguration(context as Application, writeKey, config))
        val optOut = OptOutPlugin(context, writeKey)
        analytics.add(optOut)
        analytics.add(DeviceContextPlugin)

        Companion.analytics = analytics
        optOutPlugin = optOut

        if (!integrationFactories.isNullOrEmpty()) {
            BridgeLog.warn("Device-mode integrations aren't supported in this spike; ${integrationFactories?.size} ignored.")
        }
    }

    companion object {
        @Volatile private var analytics: Analytics? = null
        @Volatile private var optOutPlugin: OptOutPlugin? = null
        private var integrationFactories: MutableList<Any>? = null

        /**
         * Called by the rudder_integration_*_flutter packages. Typed as Any so they still
         * compile against this spike; the legacy factories can't be used with the Kotlin
         * SDK (FLT-601–FLT-604 replace them).
         */
        @JvmStatic
        fun addIntegration(integration: Any) {
            val list = integrationFactories ?: mutableListOf<Any>().also { integrationFactories = it }
            if (integration !in list) list.add(integration)
        }

        /** Called by rudder_plugin_db_encryption. D6: no effect; kept for the queue drain (FLT-506). */
        @JvmStatic
        fun setDBEncryption(@Suppress("UNUSED_PARAMETER") dbEncryption: Map<String, Any>?) {
            BridgeLog.warn("Database encryption isn't supported in Flutter 4.0.")
        }
    }
}
