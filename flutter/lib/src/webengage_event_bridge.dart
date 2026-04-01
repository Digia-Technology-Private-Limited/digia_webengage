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

  /// Dispatches a Digia experience event to the corresponding WebEngage
  /// analytics system-event API.
  ///
  /// Routing is driven by `payload.content['type']`:
  /// - `'inline'` → `app_personalization_*` system events (WE Personalization).
  /// - Otherwise → `notification_*` system events (WE in-app).
  void notifyEvent(DigiaExperienceEvent event, InAppPayload payload) {
    final type = payload.content['type'] as String?;
    if (type == 'inline') {
      _dispatchInlineEvent(event, payload);
    } else {
      _dispatchInAppEvent(event, payload);
    }
  }

  void _dispatchInAppEvent(DigiaExperienceEvent event, InAppPayload payload) {
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

    _bridge.trackSystemEvent(
      eventName: eventName,
      systemData: systemData,
      eventData: const <String, dynamic>{},
    );
  }

  void _dispatchInlineEvent(DigiaExperienceEvent event, InAppPayload payload) {
    final String eventName;
    if (event is ExperienceImpressed) {
      eventName = 'app_personalization_view';
    } else if (event is ExperienceClicked) {
      eventName = 'app_personalization_click';
    } else {
      // No inline dismiss system event in WebEngage
      return;
    }

    final experimentId = _str(
          payload.cepContext['campaignId'] ??
              payload.cepContext['experimentId'],
        ) ??
        payload.id.split(':').first;
    final parts = payload.id.split(':');
    final propertyId = _str(payload.cepContext['propertyId']) ??
        (payload.content['placementKey'] as String?) ??
        (parts.length > 1 ? parts.last : payload.id);
    final variationId = _str(payload.cepContext['variationId']) ?? payload.id;

    final systemData = <String, dynamic>{
      'experiment_id': experimentId,
      'p_id': propertyId,
      'id': variationId,
    };
    if (event is ExperienceClicked) {
      final cta = event.elementId?.trim();
      if (cta != null && cta.isNotEmpty) systemData['call_to_action'] = cta;
    }

    final args = payload.content['args'];
    final eventData = args is Map
        ? <String, dynamic>{
            for (final e in args.entries)
              if (e.key is String) e.key as String: e.value,
          }
        : const <String, dynamic>{};

    _bridge.trackSystemEvent(
      eventName: eventName,
      systemData: systemData,
      eventData: eventData,
    );
  }

  String? _str(Object? value) {
    if (value == null) return null;
    final s = value.toString().trim();
    return s.isEmpty ? null : s;
  }
}
