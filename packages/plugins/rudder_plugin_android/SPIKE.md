# Spike: Android bridge on the Kotlin SDK

Throwaway spike for the Flutter 4.0 re-wrap plan. Not for release. Purpose: measure the real effort behind M2 (Android bridge) and surface surprises before committing to estimates.

**Status: written, not compiled or run.** Reviewed against Kotlin SDK `main` @ `48fe6df` (v2.1.0) signatures. First step for whoever picks it up: build the example app and fix compile errors.

## What changed

| Before (3.4) | After (spike) |
|---|---|
| `com.rudderstack.android.sdk:core [1.28.1, 2.0.0)` + gson | `com.rudderstack.sdk.kotlin:android:2.1.0` |
| `minSdk 16`, Java 8 | `minSdk 21`, Java 17 (D4) |
| 9 Java files, ~1,130 lines, incl. bridge-owned lifecycle, session, and prefs managers | 3 Kotlin files: router, mappers, bridge plugins |
| Lifecycle events and sessions emitted by the bridge | Emitted by the Kotlin SDK (FLT-204) |

The Dart method-channel contract (`rudder_sdk_flutter`, same method names and argument keys) is unchanged, so the current Dart packages run against it as-is.

## Covered

- `initializeSDK`, `identify`, `track`, `screen`, `group`, `alias`, `reset`, `startSession`, `endSession`, `getSessionId`, `getRudderContext` (identity only), `flush` (new, no Dart API yet)
- Config mapping, including legacy `flushQueueSize` / `sleepTimeOut` → flush policies, and warnings for options with no equivalent (`dbCountThreshold`, `configRefreshInterval`, `autoCollectAdvertId`, `dataResidencyServer`)
- Traits keep the legacy serialized key names (`firstname`, `createdat`, `postalcode`, …) so payloads don't change shape
- `optOut` emulation, persisted per write key (D7)
- `putDeviceToken` / `putAdvertisingId` written back into `context.device.token`, `advertisingId`, `adTrackingEnabled` (FLT-005 finding)
- `putAnonymousId` deprecated with a warning (D12)

## Not covered

- Identity migration and legacy queue drain (FLT-501, FLT-506) — see `flt-003-migration-design.md`. **Upgrading a 3.4 install to this spike resets identity and sends `Application Installed` again.**
- Device-mode integrations: `addIntegration` is accepted but ignored. The `rudder_integration_*_flutter` packages still pull the legacy SDK, so an app using them ends up with both SDKs.
- Multiple instances, consent, Flutter library stamp, logger callback
- iOS

## To verify when it builds

1. Late init: `Application Opened` / `Installed` fire correctly when Flutter initializes after `Application.onCreate` (FLT-204).
2. Payload capture vs. 3.4 for the open items in `flt-005-payload-diff.md` §6.
3. `reset()` session behavior vs. 3.4.
4. R8 with the Kotlin SDK's consumer rules in a release build.
