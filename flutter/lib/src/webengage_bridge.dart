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
  Future<void> trackSystemEvent({
    required String eventName,
    required Map<String, dynamic> systemData,
    required Map<String, dynamic> eventData,
  });

  /// Returns whether the underlying WebEngage SDK bridge is available.
  bool isAvailable();

  /// Triggers the dismiss flow for a campaign from the Dart side.
  ///
  /// Mirrors the logic of an `onInAppDismissed` MethodChannel message and calls
  /// through to the [onInAppDismissed] callback registered via [registerCallbacks].
  ///
  /// Must be called when the custom Digia UI is dismissed, because WebEngage
  /// never fires its native dismiss callback for suppressed campaigns.
  ///
  /// **Gate behaviour**: the gate entry for [campaignId] is released after a
  /// short delay ([_gateReleaseDelay]) rather than immediately. This absorbs
  /// the async echo re-evaluation that `notification_close` triggers in
  /// WebEngage (`notification_close` → `eventRuleCode` → `onInAppPrepared`).
  /// After the delay the gate opens so the campaign can show again in a future
  /// session if WE frequency rules permit.
  void notifyDismissed(String campaignId);

  Duration get gateReleaseDelay;
}

/// Production [WebEngageBridge] backed by `webengage_flutter` and
/// `we_personalization_flutter`.
///
/// On both Android and iOS, `DigiaSuppressPlugin` registers as the native
/// in-app notification delegate, optionally suppresses WebEngage's own UI, and
/// forwards `onInAppPrepared` / `onInAppDismissed` to Dart via the
/// `plugins.digia.tech/webengage_suppress` MethodChannel.
///
/// A Dart-side plugin gate ([_activeExperimentIds]) mirrors the Android
/// `ConcurrentHashMap` gate, blocking duplicate `onInAppPrepared` callbacks
/// for campaigns that are already active.
///
/// Gate lifecycle:
/// - Closed on first `onInAppPrepared` for an experimentId.
/// - Released with a short delay after `notifyDismissed` to absorb the async
///   echo re-evaluation that `notification_close` triggers in WebEngage.
/// - Also cleared fully on `unregisterCallbacks` (session/plugin teardown).
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

  // Plugin-level gate: tracks experimentIds whose Digia sheet is currently active.
  // .add() returns false if the ID is already present (duplicate fire) → block.
  // Released with a short delay after dismiss to absorb the async echo re-evaluation
  // that notification_close triggers. Cleared fully on unregisterCallbacks.
  final _activeExperimentIds = <String>{};

  // How long to keep the gate closed after dismiss to absorb the WE echo
  // re-evaluation. notification_close → eventRuleCode → onInAppPrepared arrives
  // within milliseconds; 1 s is a generous safety window.
  static const _gateReleaseDelay = Duration(seconds: 1);

  void Function(String)? _onInAppDismissed;

  @override
  Duration get gateReleaseDelay => _gateReleaseDelay;

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
    _onInAppDismissed = onInAppDismissed;
    _suppressChannel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onInAppPrepared':
          final data = Map<String, dynamic>.from(call.arguments as Map);
          final experimentId = data['experimentId'] as String?;
          // Gate check: .add() returns false when already present → duplicate, block.
          if (experimentId != null && !_activeExperimentIds.add(experimentId))
            return;
          onInAppPrepared(data);
        case 'onInAppDismissed':
          final data = Map<String, dynamic>.from(call.arguments as Map);
          final id = data['experimentId'] as String?;
          // For WE-native campaigns the native dismiss fires here — release immediately.
          if (id != null) _activeExperimentIds.remove(id);
          if (id != null) onInAppDismissed(id);
      }
    });

    _campaignCallback = _DigiaWECampaignCallback(onInlineCampaignPrepared);
    _wePersonalization.registerWECampaignCallback(_campaignCallback!);
  }

  @override
  void unregisterCallbacks() {
    _suppressChannel.setMethodCallHandler(null);
    _activeExperimentIds.clear();
    _onInAppDismissed = null;

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
  Future<void> trackSystemEvent({
    required String eventName,
    required Map<String, dynamic> systemData,
    required Map<String, dynamic> eventData,
  }) async {
    await _suppressChannel.invokeMethod<void>('trackSystemEvent', {
      'eventName': eventName,
      'systemData': systemData,
      'eventData': eventData,
    });
  }

  @override
  bool isAvailable() => true;

  @override
  void notifyDismissed(String campaignId) {
    if (campaignId.isEmpty) return;
    _onInAppDismissed?.call(campaignId);
    // Release the gate after a short delay to absorb the async echo re-evaluation
    // that notification_close triggers (notification_close → eventRuleCode →
    // onInAppPrepared arrives within milliseconds). After the delay the campaign
    // can show again in a future session per WE frequency rules.
    Future.delayed(
        _gateReleaseDelay, () => _activeExperimentIds.remove(campaignId));
  }
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
