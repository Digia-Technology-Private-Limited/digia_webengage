import 'package:digia_engage/digia_engage.dart';
import 'package:digia_engage_webengage/src/webengage_bridge.dart';

/// Forwards Digia lifecycle/screen events to WebEngage bridge methods.
class WebEngageEventBridge {
  /// Creates event bridge over the WebEngage SDK bridge.
  WebEngageEventBridge(this._bridge);

  final WebEngageBridge _bridge;

  /// Forwards screen names to WebEngage analytics.
  void forwardScreen(String name) {
    _bridge.navigateScreen(name);
  }

  /// Handles Digia experience events.
  ///
  /// WebEngage has no SDK-level equivalent of CleverTap's `dismissTemplate`.
  /// Once a WE in-app is triggered the SDK controls its own UI lifecycle, so
  /// all event types are no-ops.
  void notifyEvent(DigiaExperienceEvent event, InAppPayload payload) {}
}
