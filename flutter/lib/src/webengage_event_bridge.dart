import 'package:digia_engage/digia_engage.dart';
import 'package:digia_webengage_plugin/src/webengage_bridge.dart';

/// Forwards Digia lifecycle/screen events to WebEngage bridge methods.
class WebEngageEventBridge {
  /// Creates event bridge over the WebEngage SDK bridge.
  WebEngageEventBridge(this._bridge);

  final WebEngageBridge _bridge;

  /// Forwards screen names to WebEngage analytics.
  void forwardScreen(String name) {
    _bridge.navigateScreen(name);
  }

  /// Dispatches a Digia experience event to the corresponding WebEngage
  /// analytics system-event API.
  ///
  /// Routing is driven by `payload.content['type']`:
  /// - `'inline'` → `app_personalization_*` system events (WE Personalization).
  /// - Otherwise → `notification_*` system events (WE in-app).
  Future<void> notifyEvent(DigiaExperienceEvent event, InAppPayload payload) {
    final type = payload.content['type'] as String?;
    if (type == 'inline') {
      return _dispatchInlineEvent(event, payload);
    } else {
      return _dispatchInAppEvent(event, payload);
    }
  }

  Future<void> _dispatchInAppEvent(
      DigiaExperienceEvent event, InAppPayload payload) async {
    final String eventName;
    if (event is ExperienceImpressed) {
      eventName = 'notification_view';
    } else if (event is ExperienceDismissed) {
      eventName = 'notification_close';
    } else if (event is ExperienceClicked) {
      eventName = 'notification_click';
    } else {
      return;
    }

    final experimentId = _str(
          payload.cepContext['experimentId'] ??
              payload.cepContext['campaignId'],
        ) ??
        payload.id.split(':').first;
    final variationId = _str(payload.cepContext['variationId']) ?? payload.id;

    final systemData = <String, dynamic>{
      'experiment_id': experimentId,
      'id': variationId,
    };
    if (event is ExperienceClicked) {
      final cta = event.elementId?.trim();
      if (cta != null && cta.isNotEmpty) systemData['call_to_action'] = cta;
    }

    await _bridge.trackSystemEvent(
      eventName: eventName,
      systemData: systemData,
      eventData: const <String, dynamic>{},
    );

    if (event is ExperienceImpressed) {
      // Release the Dart-side plugin gate so the next queued campaign can show.
      _bridge.notifyDismissed(experimentId);
    }
  }

  Future<void> _dispatchInlineEvent(
      DigiaExperienceEvent event, InAppPayload payload) async {
    if (event is ExperienceDismissed) return;

    final experimentId = _str(
          payload.cepContext['experimentId'] ??
              payload.cepContext['campaignId'],
        ) ??
        payload.id.split(':').first;
    final variationId = _str(payload.cepContext['variationId']) ?? payload.id;
    final systemData = <String, dynamic>{
      'experiment_id': experimentId,
      'id': variationId,
    };

    if (event is ExperienceImpressed) {
      await _bridge.trackSystemEvent(
        eventName: 'notification_view',
        systemData: systemData,
        eventData: const <String, dynamic>{},
      );
      await _bridge.trackSystemEvent(
        eventName: 'notification_close',
        systemData: systemData,
        eventData: const <String, dynamic>{},
      );
      // Release the Dart-side plugin gate so the next queued campaign can show.
      _bridge.notifyDismissed(experimentId);
    } else if (event is ExperienceClicked) {
      final cta = event.elementId?.trim();
      if (cta != null && cta.isNotEmpty) systemData['call_to_action'] = cta;
      await _bridge.trackSystemEvent(
        eventName: 'notification_click',
        systemData: systemData,
        eventData: const <String, dynamic>{},
      );
    }
  }

  String? _str(Object? value) {
    if (value == null) return null;
    final s = value.toString().trim();
    return s.isEmpty ? null : s;
  }
}
