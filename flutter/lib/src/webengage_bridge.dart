import 'dart:async';

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
  void registerCallbacks({
    required void Function(Map<String, dynamic> data) onInAppPrepared,
    required void Function(String campaignId) onInAppDismissed,
    required void Function(WECampaignData data) onInlineCampaignPrepared,
  });

  /// Unregisters all previously registered callbacks.
  void unregisterCallbacks();

  /// Forwards screen navigation to WebEngage analytics.
  void navigateScreen(String name);

  /// Returns whether the underlying WebEngage SDK bridge is available.
  bool isAvailable();
}

/// Production [WebEngageBridge] backed by `webengage_flutter` and
/// `we_personalization_flutter`.
class WebEngageSdkBridge implements WebEngageBridge {
  /// Creates a production bridge backed by `webengage_flutter`.
  WebEngageSdkBridge({
    WebEngagePlugin? wePlugin,
    WEPersonalization? wePersonalization,
  })  : _wePlugin = wePlugin ?? WebEngagePlugin(),
        _wePersonalization = wePersonalization ?? WEPersonalization();

  final WebEngagePlugin _wePlugin;
  final WEPersonalization _wePersonalization;

  /// Monotonically-increasing counter used to generate stable per-session IDs
  /// for in-app campaigns. WebEngage's Flutter bridge does not expose the
  /// underlying `experimentId`, so we synthesise one here.
  int _counter = 0;

  /// The campaign ID injected for the most recently prepared in-app.
  /// Stored so the dismissal callback can forward the same ID.
  String? _activeInAppId;

  _DigiaWECampaignCallback? _campaignCallback;

  @override
  void registerCallbacks({
    required void Function(Map<String, dynamic> data) onInAppPrepared,
    required void Function(String campaignId) onInAppDismissed,
    required void Function(WECampaignData data) onInlineCampaignPrepared,
  }) {
    // NOTE: webengage_flutter (v1.7.0) does not expose Android's
    // InAppNotificationData.setShouldRender(false) to Dart callbacks.
    // So Digia can parse and render the payload, but native WebEngage in-app
    // UI cannot be suppressed from this bridge today.
    _wePlugin.setUpInAppCallbacks(
      (_, __) {},
      (_) {},
      (data) {
        final id = _activeInAppId;
        _activeInAppId = null;
        if (id != null) onInAppDismissed(id);
      },
      (data) {
        if (data == null) return;
        _counter++;
        final id = 'we_inapp_$_counter';
        _activeInAppId = id;
        // Inject a synthetic experimentId so the mapper and delegate have a
        // stable campaign ID without needing Digia backend support.
        onInAppPrepared(<String, dynamic>{...data, 'experimentId': id});
      },
    );

    _campaignCallback = _DigiaWECampaignCallback(onInlineCampaignPrepared);
    _wePersonalization.registerWECampaignCallback(_campaignCallback!);
  }

  @override
  void unregisterCallbacks() {
    // Replace in-app callbacks with no-ops — webengage_flutter has no
    // explicit deregister method.
    _wePlugin.setUpInAppCallbacks(
      (_, __) {},
      (_) {},
      (_) {},
      (_) {},
    );
    _activeInAppId = null;

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
