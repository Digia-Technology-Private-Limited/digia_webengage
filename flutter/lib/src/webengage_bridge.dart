import 'dart:async';

import 'package:flutter/foundation.dart'
    show TargetPlatform, defaultTargetPlatform;
import 'package:flutter/services.dart';
import 'package:we_personalization_flutter/we_personalization_flutter.dart';
import 'package:webengage_flutter/webengage_flutter.dart';

/// Abstraction over WebEngage SDK interactions used by the plugin.
abstract class WebEngageBridge {
  /// Registers in-app notification callbacks and the inline campaign callback.
  ///
  /// [onInAppPrepared] is called with an enriched data map that includes an
  /// injected `experimentId` key for campaign correlation.
  /// [onInAppDismissed] is called with the previously injected campaign ID.
  /// [onInlineCampaignPrepared] fires when an inline WE campaign is received.
  ///
  /// When [suppressRendering] is `true` (Android only), the native plugin calls
  /// `InAppNotificationData.setShouldRender(false)` so WebEngage's own in-app
  /// UI is blocked and only Digia's rendering runs. Defaults to `false`.
  void registerCallbacks({
    required void Function(Map<String, dynamic> data) onInAppPrepared,
    required void Function(String campaignId) onInAppDismissed,
    required void Function(WECampaignData data) onInlineCampaignPrepared,
    bool suppressRendering = false,
  });

  /// Unregisters all previously registered callbacks.
  void unregisterCallbacks();

  /// Forwards screen navigation to WebEngage analytics.
  void navigateScreen(String name);

  /// Tracks a system event with the given name, system data and event data.
  void trackSystemEvent({
    required String eventName,
    required Map<String, dynamic> systemData,
    required Map<String, dynamic> eventData,
  });

  /// Returns whether the underlying WebEngage SDK bridge is available.
  bool isAvailable();
}

/// Production [WebEngageBridge] backed by `webengage_flutter` and
/// `we_personalization_flutter`.
///
/// ## Why `_counter` and `_activeInAppId`?
///
/// `webengage_flutter` v1.7.0 forwards in-app callbacks from the native layer
/// via a MethodChannel, but it does **not** include the native
/// `InAppNotificationData.experimentId` in the Dart data map — that field
/// lives on the Java/Kotlin object and is never serialised across the channel.
///
/// ### `_counter`
/// Because the Dart `onInAppPrepared` map has no stable campaign ID, we
/// synthesise one (`we_inapp_1`, `we_inapp_2`, …) using a session-scoped
/// monotonic counter. The counter increments on every `onPrepared` call so
/// each triggered campaign gets a unique, consistent ID that Digia's delegate
/// can track for the lifetime of the app session.
///
/// ### `_activeInAppId`
/// The `onInAppDismissed` callback suffers the same problem: the data map it
/// receives contains the raw in-app content but no experiment ID. Because
/// WebEngage shows in-app notifications **serially** (at most one on screen at
/// a time), we can store the last synthesised ID from `onPrepared` and forward
/// that same ID when `onDismissed` fires. `_activeInAppId` is set to the new
/// ID on `onPrepared` and cleared (returned) on `onDismissed`, so it always
/// refers to the currently-active in-app, if any.
///
/// ### Rendering suppression (Android)
/// On Android, `DigiaSuppressPlugin` registers a native
/// `InAppNotificationCallbacks`, calls `setShouldRender(false)` to block
/// WebEngage's own UI, then sends the enriched payload to Dart via
/// `plugins.digia.tech/webengage_suppress`. When running on Android this
/// bridge listens on that channel and the `_counter`/`_activeInAppId`
/// workarounds are **not used** — the real `experimentId` arrives from
/// native. On iOS (and other non-Android platforms) the
/// `webengage_flutter.setUpInAppCallbacks` fallback path with synthesised
/// IDs is still used because no native suppress layer exists there yet.
class WebEngageSdkBridge implements WebEngageBridge {
  /// Creates a production bridge backed by `webengage_flutter`.
  ///
  /// [suppressChannel] is injectable for testing; defaults to the
  /// `plugins.digia.tech/webengage_suppress` channel wired by
  /// `DigiaSuppressPlugin` on the Android side.
  WebEngageSdkBridge({
    WebEngagePlugin? wePlugin,
    WEPersonalization? wePersonalization,
    MethodChannel? suppressChannel,
  })  : _wePlugin = wePlugin ?? WebEngagePlugin(),
        _wePersonalization = wePersonalization ?? WEPersonalization(),
        _suppressChannel = suppressChannel ??
            const MethodChannel('plugins.digia.tech/webengage_suppress');

  final WebEngagePlugin _wePlugin;
  final WEPersonalization _wePersonalization;
  final MethodChannel _suppressChannel;

  // Synthesised IDs used on non-Android platforms only (see class-level doc).
  int _counter = 0;
  String? _activeInAppId;

  _DigiaWECampaignCallback? _campaignCallback;

  @override
  void registerCallbacks({
    required void Function(Map<String, dynamic> data) onInAppPrepared,
    required void Function(String campaignId) onInAppDismissed,
    required void Function(WECampaignData data) onInlineCampaignPrepared,
    bool suppressRendering = false,
  }) {
    if (defaultTargetPlatform == TargetPlatform.android) {
      // Android: DigiaSuppressPlugin delivers the real experimentId from
      // native and has already suppressed the WebEngage UI via setShouldRender.
      // unawaited(
      _suppressChannel.invokeMethod<void>(
        'configure',
        {'suppressRendering': suppressRendering},
      );
      // );
      _suppressChannel.setMethodCallHandler((call) async {
        switch (call.method) {
          case 'onInAppPrepared':
            final data = Map<String, dynamic>.from(call.arguments as Map);
            onInAppPrepared(data);
          case 'onInAppDismissed':
            final data = Map<String, dynamic>.from(call.arguments as Map);
            final id = data['experimentId'] as String?;
            if (id != null) onInAppDismissed(id);
        }
      });
    } else {
      // Non-Android (iOS): use webengage_flutter with synthesised IDs.
      // Parameter order (webengage_flutter v1.7.0):
      //   1. onInAppClick    — not needed
      //   2. onInAppShown    — not needed
      //   3. onInAppDismiss  — forward stored ID, clear _activeInAppId
      //   4. onInAppPrepared — synthesise ID via _counter
      // _wePlugin.setUpInAppCallbacks(
      //   (_, __) {},
      //   (_) {},
      //   (data) {
      //     final id = _activeInAppId;
      //     _activeInAppId = null;
      //     if (id != null) onInAppDismissed(id);
      //   },
      //   (data) {
      //     if (data == null) return;
      //     _counter++;
      //     final id = 'we_inapp_$_counter';
      //     _activeInAppId = id;
      //     onInAppPrepared(<String, dynamic>{...data, 'experimentId': id});
      //   },
      // );
    }

    _campaignCallback = _DigiaWECampaignCallback(onInlineCampaignPrepared);
    _wePersonalization.registerWECampaignCallback(_campaignCallback!);
  }

  @override
  void unregisterCallbacks() {
    if (defaultTargetPlatform == TargetPlatform.android) {
      _suppressChannel.setMethodCallHandler(null);
    } else {
      // Replace with no-ops — webengage_flutter has no explicit deregister.
      _wePlugin.setUpInAppCallbacks((_, __) {}, (_) {}, (_) {}, (_) {});
      _activeInAppId = null;
    }

    // Replace the inline callback with the empty base class — there is no
    // deregisterWECampaignCallback on WEPersonalization in Flutter.
    _wePersonalization.registerWECampaignCallback(WECampaignCallback());
    _campaignCallback = null;
  }

  @override
  void navigateScreen(String name) {
    unawaited(WebEngagePlugin.trackScreen(name));
  }

  @override
  void trackSystemEvent({
    required String eventName,
    required Map<String, dynamic> systemData,
    required Map<String, dynamic> eventData,
  }) {
    final attributes = <String, dynamic>{
      ...systemData,
      ...eventData,
    };
    unawaited(WebEngagePlugin.trackEvent(eventName, attributes));
  }

  @override
  bool isAvailable() => true;
}

/// Minimal [WECampaignCallback] subclass that forwards [onCampaignPrepared]
/// delivering only campaigns with non-empty [WECampaignData.weCampaignContent].
class _DigiaWECampaignCallback extends WECampaignCallback {
  _DigiaWECampaignCallback(this._onPrepared);

  final void Function(WECampaignData data) _onPrepared;

  @override
  void onCampaignPrepared(WECampaignData data) {
    final customData = data.weCampaignContent?.customData;
    if (customData != null && customData.isNotEmpty) {
      _onPrepared(data);
    }
  }
}
