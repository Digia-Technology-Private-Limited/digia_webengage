import 'dart:convert';

import 'package:digia_engage/digia_engage.dart';
import 'package:we_personalization_flutter/we_personalization_flutter.dart';

/// Maps WebEngage callback payloads to Digia trigger payloads.
class WebEngagePayloadMapper {
  /// Maps a WE in-app notification data map (with injected `experimentId`)
  /// to a list of Digia [InAppPayload]s.
  ///
  /// Produces one nudge payload when `command` and `viewId` are present.
  List<InAppPayload> map(Map<String, dynamic> data) {
    final normalized = _normalizeWithDigiaContract(data);
    final campaignId = _str(
          normalized['experimentId'] ??
              normalized['id'] ??
              normalized['campaignId'],
        ) ??
        '';
    final screenId =
        _str(normalized['screenId'] ?? normalized['screen_id']) ?? '*';
    if (campaignId.isEmpty) {
      return const <InAppPayload>[];
    }

    final args = _normalizeArgs(normalized['args']);
    final payloads = <InAppPayload>[];

    final command = _str(normalized['command'])?.toUpperCase();
    final viewId = _str(normalized['viewId']);
    if (command != null &&
        command.isNotEmpty &&
        viewId != null &&
        viewId.isNotEmpty) {
      payloads.add(
        InAppPayload(
          id: campaignId,
          content: <String, dynamic>{
            'command': command,
            'viewId': viewId,
            'screenId': screenId,
            'args': args,
          },
          cepContext: <String, dynamic>{'experimentId': campaignId},
        ),
      );
    }

    return payloads;
  }

  /// Maps a WE inline campaign data object to a single Digia [InAppPayload]
  /// for a [DigiaSlot] / placement to render.
  InAppPayload? mapInline(WECampaignData data) {
    final campaignId = data.campaignId ?? '';
    final targetViewId = data.targetViewId ?? '';
    if (campaignId.isEmpty || targetViewId.isEmpty) return null;

    final customData =
        Map<String, dynamic>.from(data.weCampaignContent?.customData ?? {});
    if (customData.isEmpty) return null;

    final screenId =
        _str(customData['screenId'] ?? customData['screen_id']) ?? '';
    final componentId = _str(customData['componentId']) ?? '';
    final args = _normalizeArgs(customData['args']);

    return InAppPayload(
      id: '$campaignId:$targetViewId',
      content: <String, dynamic>{
        'command': 'SHOW_INLINE',
        'screenId': screenId,
        'placementKey': targetViewId,
        'componentId': componentId,
        'args': args,
      },
      cepContext: <String, dynamic>{'campaignId': campaignId},
    );
  }

  String? _str(Object? value) {
    if (value == null) return null;
    final s = value.toString().trim();
    return s.isEmpty ? null : s;
  }

  Map<String, dynamic> _normalizeArgs(Object? value) {
    if (value is Map<String, dynamic>) return value;
    if (value is Map) {
      return <String, dynamic>{
        for (final e in value.entries)
          if (e.key is String) e.key as String: e.value,
      };
    }
    if (value is String) {
      final decoded = _tryDecodeJsonObject(value);
      return decoded ?? const <String, dynamic>{};
    }
    return const <String, dynamic>{};
  }

  Map<String, dynamic> _normalizeWithDigiaContract(Map<String, dynamic> raw) {
    final topLevel = _parseContractFromMap(raw, source: 'top_level');
    if (topLevel != null) return <String, dynamic>{...raw, ...topLevel};

    final scriptContract = _extractContractFromScript(raw);
    if (scriptContract != null) {
      return <String, dynamic>{...raw, ...scriptContract};
    }

    return raw;
  }

  Map<String, dynamic>? _extractContractFromScript(Map<String, dynamic> raw) {
    final scriptRegex = RegExp(
      '<script[^>]*digia-payload[^>]*>(.*?)</script>',
      caseSensitive: false,
      dotAll: true,
    );

    for (final text in _collectTextCandidates(raw)) {
      for (final match in scriptRegex.allMatches(text)) {
        final body = _htmlUnescape(match.group(1)?.trim() ?? '');
        if (body.isEmpty) continue;
        final decoded = _tryDecodeJsonObject(body);
        if (decoded == null) continue;
        final contract = _parseContractFromMap(decoded, source: 'html_script');
        if (contract != null) return contract;
      }
    }

    return null;
  }

  Map<String, dynamic>? _parseContractFromMap(
    Map<String, dynamic> raw, {
    required String source,
  }) {
    final command = _normalizeCommand(_str(raw['command']));
    final viewId = _str(raw['viewId']);
    if (command == null || viewId == null) return null;

    final screenId = _str(raw['screenId'] ?? raw['screen_id']);
    return <String, dynamic>{
      'command': command,
      'viewId': viewId,
      if (screenId != null) 'screenId': screenId,
      'args': _normalizeArgs(raw['args']),
      'digiaContractSource': source,
    };
  }

  String? _normalizeCommand(String? value) {
    final normalized = value?.toUpperCase();
    if (normalized == 'SHOW_DIALOG' || normalized == 'SHOW_BOTTOM_SHEET') {
      return normalized;
    }
    return null;
  }

  List<String> _collectTextCandidates(Object? node) {
    final out = <String>[];
    final queue = <Object?>[node];
    while (queue.isNotEmpty) {
      final current = queue.removeAt(0);
      if (current is String) {
        if (current.contains('<')) out.add(current);
      } else if (current is Map) {
        queue.addAll(current.values);
      } else if (current is List) {
        queue.addAll(current);
      }
    }
    return out;
  }

  Map<String, dynamic>? _tryDecodeJsonObject(String value) {
    try {
      final decoded = jsonDecode(value);
      if (decoded is Map<String, dynamic>) return decoded;
      if (decoded is Map) {
        return <String, dynamic>{
          for (final e in decoded.entries)
            if (e.key is String) e.key as String: e.value,
        };
      }
      return null;
    } on FormatException {
      return null;
    }
  }

  String _htmlUnescape(String value) {
    return value
        .replaceAll('&quot;', '"')
        .replaceAll('&#34;', '"')
        .replaceAll('&apos;', "'")
        .replaceAll('&#39;', "'")
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .replaceAll('&amp;', '&');
  }
}
