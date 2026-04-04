import 'package:digia_engage/api/models/diagnostic_report.dart';
import 'package:digia_engage/digia_engage.dart';
import 'package:digia_webengage_plugin/src/webengage_bridge.dart';
import 'package:digia_webengage_plugin/src/webengage_event_bridge.dart';
import 'package:digia_webengage_plugin/src/webengage_payload_mapper.dart';
import 'package:we_personalization_flutter/we_personalization_flutter.dart';

/// WebEngage CEP plugin implementation for Digia Flutter SDK.
///
/// Hooks into the WebEngage in-app notification lifecycle (via
/// `webengage_flutter`) and the WE Personalization inline campaign callback
/// (via `we_personalization_flutter`) to forward Digia-configured payloads to
/// the active [DigiaCEPDelegate].
///
/// Since the Digia backend is not yet available, [InAppPayload.id] is derived
/// from WebEngage campaign identifiers so that the delegate can render
/// directly without a CampaignStore lookup.
class DigiaWebEngagePlugin implements DigiaCEPPlugin {
  /// Creates the WebEngage Digia CEP plugin.
  DigiaWebEngagePlugin({
    WebEngageBridge? bridge,
    WebEngagePayloadMapper? mapper,
  }) : this._(
          bridge: bridge ?? WebEngageSdkBridge(),
          mapper: mapper ?? WebEngagePayloadMapper(),
        );

  DigiaWebEngagePlugin._({
    required WebEngageBridge bridge,
    required WebEngagePayloadMapper mapper,
  })  : _bridge = bridge,
        _mapper = mapper,
        _events = WebEngageEventBridge(bridge);

  final WebEngageBridge _bridge;
  final WebEngagePayloadMapper _mapper;
  final WebEngageEventBridge _events;

  DigiaCEPDelegate? _delegate;

  @override
  String get identifier => 'webengage';

  @override
  void setup(DigiaCEPDelegate delegate) {
    _delegate = delegate;
    _bridge.registerCallbacks(
      onInAppPrepared: _handleInAppPrepared,
      onInAppDismissed: _handleInAppDismissed,
      onInlineCampaignPrepared: _handleInlinePrepared,
    );
  }

  @override
  void forwardScreen(String name) {
    _events.forwardScreen(name);
  }

  /// Registers a WebEngage WE Personalization placeholder for inline slots.
  ///
  /// Not part of [DigiaCEPPlugin]; call when wiring [DigiaSlot] to WebEngage
  /// property ids. Returns a handle for [deregisterPlaceholder].
  int? registerPlaceholder(String screenName, String propertyId) {
    return WEPersonalization().registerWEPlaceholderCallback(
      propertyId,
      0,
      screenName,
    );
  }

  /// Deregisters a placeholder registered via [registerPlaceholder].
  void deregisterPlaceholder(int id) {
    WEPersonalization().deregisterWEPlaceholderCallbackById(id);
  }

  @override
  void notifyEvent(DigiaExperienceEvent event, InAppPayload payload) {
    _events.notifyEvent(event, payload);
  }

  @override
  void teardown() {
    _bridge.unregisterCallbacks();
    _delegate = null;
  }

  @override
  DiagnosticReport healthCheck() {
    final delegateReady = _delegate != null;
    final bridgeReady = _bridge.isAvailable();
    final healthy = delegateReady && bridgeReady;
    return DiagnosticReport(
      isHealthy: healthy,
      issue: healthy ? null : 'webengage plugin not fully wired',
      resolution: healthy
          ? null
          : 'Call Digia.register(DigiaWebEngagePlugin()) after Digia.initialize().',
      metadata: <String, dynamic>{
        'identifier': identifier,
        'delegateAttached': delegateReady,
        'bridgeAvailable': bridgeReady,
      },
    );
  }

  void _handleInAppPrepared(Map<String, dynamic> data) {
    final activeDelegate = _delegate;
    if (activeDelegate == null) return;
    _mapper.map(data).forEach(activeDelegate.onCampaignTriggered);
  }

  void _handleInAppDismissed(String campaignId) {
    if (campaignId.isEmpty) return;
    _delegate?.onCampaignInvalidated(campaignId);
  }

  void _handleInlinePrepared(WECampaignData data) {
    final activeDelegate = _delegate;
    if (activeDelegate == null) return;
    final payload = _mapper.mapInline(data);
    if (payload != null) activeDelegate.onCampaignTriggered(payload);
  }
}
