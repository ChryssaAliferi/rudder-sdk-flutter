package com.rudderstack.sdk.flutter

import android.app.Application
import com.rudderstack.sdk.kotlin.android.Configuration
import com.rudderstack.sdk.kotlin.android.SessionConfiguration
import com.rudderstack.sdk.kotlin.core.internals.logger.Logger
import com.rudderstack.sdk.kotlin.core.internals.models.ExternalId
import com.rudderstack.sdk.kotlin.core.internals.models.RudderOption
import com.rudderstack.sdk.kotlin.core.internals.policies.CountFlushPolicy
import com.rudderstack.sdk.kotlin.core.internals.policies.FlushPolicy
import com.rudderstack.sdk.kotlin.core.internals.policies.FrequencyFlushPolicy
import com.rudderstack.sdk.kotlin.core.internals.policies.StartupFlushPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * SPIKE: conversion between Flutter method-channel arguments (StandardMessageCodec maps,
 * lists, and primitives) and the Kotlin SDK's types. Replaces RudderConfigParser,
 * RudderOptionsParser and RudderTraitsParser.
 */

@Suppress("UNCHECKED_CAST")
internal fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is List<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

internal fun Map<String, Any?>?.toJsonObject(): JsonObject =
    this?.let { JsonObject(it.mapValues { (_, v) -> v.toJsonElement() }) } ?: JsonObject(emptyMap())

/** For returning SDK values to Dart (getRudderContext). */
internal fun JsonElement?.toPlatformValue(): Any? = when (this) {
    null, is JsonNull -> null
    is JsonObject -> mapValues { (_, v) -> v.toPlatformValue() }
    is JsonArray -> map { it.toPlatformValue() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull
        else -> doubleOrNull ?: content
    }
}

internal fun Any?.asLong(): Long? = (this as? Number)?.toLong()

/**
 * Maps the Dart `RudderConfig` map onto the Kotlin SDK [Configuration].
 *
 * Keys come from rudder_plugin_interface's RudderConfig.toMapMobile().
 */
internal fun buildConfiguration(
    application: Application,
    writeKey: String,
    config: Map<String, Any?>,
): Configuration {
    val logLevel = when ((config["logLevel"] as? Number)?.toInt()) {
        5 -> Logger.LogLevel.VERBOSE
        4 -> Logger.LogLevel.DEBUG
        3 -> Logger.LogLevel.INFO
        2 -> Logger.LogLevel.WARN
        1 -> Logger.LogLevel.ERROR
        else -> Logger.LogLevel.NONE
    }

    // FLT-407: legacy flushQueueSize / sleepTimeOut (seconds) map onto flush policies.
    val flushPolicies = buildList<FlushPolicy> {
        add(StartupFlushPolicy())
        (config["flushQueueSize"] as? Number)?.toInt()?.let { add(CountFlushPolicy(flushAt = it)) }
            ?: add(CountFlushPolicy())
        (config["sleepTimeOut"] as? Number)?.toLong()?.let { add(FrequencyFlushPolicy(flushIntervalInMillis = it * 1000)) }
            ?: add(FrequencyFlushPolicy())
    }

    // FLT-411 / FLT-005 §4: no equivalent in the Kotlin SDK.
    listOf("dbCountThreshold", "configRefreshInterval", "autoCollectAdvertId").forEach { key ->
        if (config[key] != null) BridgeLog.warn("'$key' has no effect on Flutter 4.0 (Android) and will be removed.")
    }
    if ((config["dataResidencyServer"] as? String) == "EU") {
        BridgeLog.warn("'dataResidencyServer' isn't supported on Flutter 4.0 (Android). Set dataPlaneUrl to your EU data plane URL.")
    }

    return Configuration(
        writeKey = writeKey,
        application = application,
        dataPlaneUrl = config["dataPlaneUrl"] as String,
        controlPlaneUrl = (config["controlPlaneUrl"] as? String)
            ?: com.rudderstack.sdk.kotlin.core.Configuration.DEFAULT_CONTROL_PLANE_URL,
        trackApplicationLifecycleEvents = config["trackLifecycleEvents"] as? Boolean ?: true,
        trackDeepLinks = config["trackDeepLinks"] as? Boolean ?: true,
        trackActivities = config["recordScreenViews"] as? Boolean ?: false,
        collectDeviceId = config["collectDeviceId"] as? Boolean ?: true,
        // D8: new default is false. SPIKE passes the Dart value through; the Dart default
        // (currently true) must change in rudder_plugin_interface for 4.0.
        gzipEnabled = config["gzip"] as? Boolean ?: false,
        flushPolicies = flushPolicies,
        logLevel = logLevel,
        sessionConfiguration = SessionConfiguration(
            automaticSessionTracking = config["autoSessionTracking"] as? Boolean ?: true,
            sessionTimeoutInMillis = config["sessionTimeoutInMillis"].asLong() ?: 300_000L,
        ),
    )
}

/** Maps the Dart `RudderOption` map onto [RudderOption]. FLT-417 widens integrations and custom context to any JSON. */
internal fun buildOption(options: Map<String, Any?>?): RudderOption {
    if (options == null) return RudderOption()
    @Suppress("UNCHECKED_CAST")
    val externalIds = (options["externalIds"] as? List<Map<String, Any?>>).orEmpty().mapNotNull {
        val type = it["type"] as? String
        val id = it["id"] as? String
        if (type != null && id != null) ExternalId(type = type, id = id) else null
    }
    return RudderOption(
        integrations = options["integrations"].asMap().toJsonObject(),
        externalIds = externalIds,
        customContext = options["customContexts"].asMap().toJsonObject(),
    )
}

/**
 * Maps the Dart `RudderTraits` map onto a traits [JsonObject].
 *
 * Keeps the legacy Android SDK's serialized key names (`firstname`, `createdat`,
 * `postalcode`, …) so `context.traits` / `traits` don't change shape on upgrade (FLT-005).
 * `extras` are flattened to the top level, as in 3.4.
 */
internal fun buildTraits(traits: Map<String, Any?>?): JsonObject {
    if (traits == null) return JsonObject(emptyMap())
    val out = linkedMapOf<String, Any?>()
    val rename = mapOf(
        "age" to "age", "birthday" to "birthday", "createdAt" to "createdat",
        "description" to "description", "email" to "email", "firstName" to "firstname",
        "gender" to "gender", "id" to "id", "lastName" to "lastname", "name" to "name",
        "phone" to "phone", "title" to "title", "userName" to "username",
    )
    rename.forEach { (dartKey, legacyKey) ->
        val value = traits[dartKey] ?: return@forEach
        out[legacyKey] = if (dartKey == "age") (value as? String)?.toIntOrNull() ?: value else value
    }
    traits["address"].asMap()?.let { a ->
        out["address"] = mapOf(
            "city" to a["city"], "country" to a["country"], "postalcode" to a["postalCode"],
            "state" to a["state"], "street" to a["street"],
        ).filterValues { it != null }
    }
    traits["company"].asMap()?.let { c ->
        out["company"] = mapOf("name" to c["name"], "id" to c["id"], "industry" to c["industry"])
            .filterValues { it != null }
    }
    traits["extras"].asMap()?.let { out.putAll(it) }
    return out.toJsonObject()
}
