import 'dart:async';

import 'package:flutter/services.dart';
import 'package:we_personalization_flutter/we_personalization_flutter.dart';
import 'package:webengage_flutter/webengage_flutter.dart';

/// Abstraction over WebEngage SDK interactions used by the plugin.
abstract class WebEngageBridge {
  /// Registers in-app notification callbacks and the inline campaign callback.
  ///
  /// [onInAppPrepared] is called with an enriched data map that includes an
  /// injected `experimentId` key for campaign correlation. Only Digia campaigns
  /// are forwarded — the native layer identifies and suppresses them per-campaign.
  /// [onInAppDismissed] is called with the previously injected campaign ID.
  /// [onInlineCampaignPrepared] fires when an inline WE campaign is received.
  void registerCallbacks({
    required void Function(Map<String, dynamic> data) onInAppPrepared,
    required void Function(String campaignId) onInAppDismissed,
    required void Function(WECampaignData data) onInlineCampaignPrepared,
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
/// On both Android and iOS, `DigiaSuppressPlugin` registers as the native
/// in-app notification delegate, optionally suppresses WebEngage's own UI, and
/// forwards `onInAppPrepared` / `onInAppDismissed` to Dart via the
/// `plugins.digia.tech/webengage_suppress` MethodChannel.
///
/// Deduplication (ignoring repeated `onInAppPrepared` callbacks for the same
/// active campaign) is performed in the native plugin on both platforms so no
/// extra state is needed here.
class WebEngageSdkBridge implements WebEngageBridge {
  /// Creates a production bridge backed by `webengage_flutter`.
  ///
  /// [suppressChannel] is injectable for testing; defaults to the
  /// `plugins.digia.tech/webengage_suppress` channel wired by
  /// `DigiaSuppressPlugin` on both Android and iOS.
  WebEngageSdkBridge({
    WEPersonalization? wePersonalization,
    MethodChannel? suppressChannel,
  })  : _wePersonalization = wePersonalization ?? WEPersonalization(),
        _suppressChannel = suppressChannel ??
            const MethodChannel('plugins.digia.tech/webengage_suppress');

  final WEPersonalization _wePersonalization;
  final MethodChannel _suppressChannel;

  _DigiaWECampaignCallback? _campaignCallback;

  @override
  void registerCallbacks({
    required void Function(Map<String, dynamic> data) onInAppPrepared,
    required void Function(String campaignId) onInAppDismissed,
    required void Function(WECampaignData data) onInlineCampaignPrepared,
  }) {
    // DigiaSuppressPlugin (Android + iOS) identifies campaign type natively:
    //   - Digia campaigns  → suppressed + forwarded here via this channel.
    //   - Normal campaigns → WebEngage SDK renders them; not forwarded here.
    // No global suppressRendering toggle is needed.
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

    _campaignCallback = _DigiaWECampaignCallback(onInlineCampaignPrepared);
    _wePersonalization.registerWECampaignCallback(_campaignCallback!);
  }

  @override
  void unregisterCallbacks() {
    _suppressChannel.setMethodCallHandler(null);

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
