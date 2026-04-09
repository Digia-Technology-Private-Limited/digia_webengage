import 'dart:convert';

import 'package:digia_engage/digia_engage.dart';
import 'package:we_personalization_flutter/we_personalization_flutter.dart';

/// Maps WebEngage callback payloads to Digia trigger payloads.
class WebEngagePayloadMapper {
  /// Maps a WE in-app notification data map (with injected `experimentId`)
  /// to a list of Digia [InAppPayload]s.
  ///
  /// Produces one inline payload when `type == "inline"`, or one nudge payload
  /// when `command` and `viewId` are present.
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

    final type = _str(normalized['type'])?.toLowerCase();
    final command = _str(normalized['command'])?.toUpperCase();
    final viewId = _str(normalized['viewId']) ?? '';
    final variationId = _str(normalized['variationId']) ?? '';

    if (type == 'inline') {
      final placementKey = _str(normalized['placementKey']) ?? '';
      if (placementKey.isNotEmpty && viewId.isNotEmpty) {
        payloads.add(
          InAppPayload(
            id: campaignId,
            content: <String, dynamic>{
              'type': 'inline',
              'screenId': screenId,
              'placementKey': placementKey,
              'viewId': viewId,
              'args': args,
            },
            cepContext: <String, dynamic>{
              'experimentId': campaignId,
              'campaignId': campaignId,
              if (variationId.isNotEmpty) 'variationId': variationId,
            },
          ),
        );
      }
    } else if (command != null && command.isNotEmpty && viewId.isNotEmpty) {
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

    final screenId =
        _str(customData['screenId'] ?? customData['screen_id']) ?? '';
    final componentId = _str(customData['componentId']) ?? '';
    if (componentId.isEmpty) return null;
    final args = _normalizeArgs(customData['args']);

    final metadata = data.weCampaignContent?.customData ?? {};
    final variationId = _str(metadata['variationId']) ?? '';
    final propertyId = _str(metadata['propertyId']) ?? targetViewId;

    return InAppPayload(
      id: '$campaignId:$targetViewId',
      content: <String, dynamic>{
        'type': 'inline',
        'screenId': screenId,
        'placementKey': targetViewId,
        'viewId': componentId,
        'args': args,
      },
      cepContext: <String, dynamic>{
        'experimentId': campaignId,
        'campaignId': campaignId,
        'propertyId': propertyId,
        if (variationId.isNotEmpty) 'variationId': variationId,
      },
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

    final htmlContract = _extractContractFromHtml(raw);
    if (htmlContract != null) {
      return <String, dynamic>{...raw, ...htmlContract};
    }

    return raw;
  }

  Map<String, dynamic>? _extractContractFromHtml(Map<String, dynamic> raw) {
    // Source 1: <digia> attribute-based tag (matches Android HtmlDigiaTagContractSource)
    final digiaTagRegex = RegExp(
      r'<digia\b([^/]*)/?>',
      caseSensitive: false,
    );
    for (final text in _collectTextCandidates(raw)) {
      for (final match in digiaTagRegex.allMatches(text)) {
        final attrs = _parseHtmlAttributes(match.group(1) ?? '');
        if (attrs.isEmpty) continue;
        final contract = _parseContractFromMap(attrs, source: 'html_digia_tag');
        if (contract != null) return contract;
      }
    }

    // Source 2: <script digia-payload> tag (legacy)
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

  /// Parses `key="value"` attribute pairs from HTML tag attribute string.
  /// Normalizes kebab-case / underscore_case attribute names to camelCase,
  /// matching Android's `DigiaTagParser.mapAttributes` behaviour.
  Map<String, dynamic> _parseHtmlAttributes(String attrsString) {
    final result = <String, dynamic>{};
    final attrRegex =
        RegExp(r'''(\w[\w-]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|(\S+)))?''');
    for (final match in attrRegex.allMatches(attrsString)) {
      final rawKey = match.group(1) ?? '';
      final value = match.group(2) ?? match.group(3) ?? match.group(4) ?? '';
      if (rawKey.isEmpty) continue;
      final key = _normalizeAttrKey(rawKey);
      result[key] = _htmlUnescape(value);
    }
    return result;
  }

  String _normalizeAttrKey(String key) {
    switch (key) {
      case 'view-id':
      case 'view_id':
        return 'viewId';
      case 'placement-key':
      case 'placement_key':
      case 'placement':
        return 'placementKey';
      case 'screen-id':
      case 'screen_id':
        return 'screenId';
      default:
        return key;
    }
  }

  Map<String, dynamic>? _parseContractFromMap(
    Map<String, dynamic> raw, {
    required String source,
  }) {
    final type = _str(raw['type'])?.toLowerCase();
    final command = _normalizeCommand(_str(raw['command']));
    final resolvedCommand = type == 'inline' ? null : command;

    // At least one routing signal must exist
    if (type == null && resolvedCommand == null) return null;

    final viewId = _str(raw['viewId']);
    if (viewId == null) return null;

    final placementKey = _str(raw['placementKey']);

    // Inline requires placementKey
    if (type == 'inline' && placementKey == null) return null;

    final screenId = _str(raw['screenId'] ?? raw['screen_id']);
    return <String, dynamic>{
      if (type != null) 'type': type,
      if (resolvedCommand != null) 'command': resolvedCommand,
      'viewId': viewId,
      'args': _normalizeArgs(raw['args']),
      'digiaContractSource': source,
      if (placementKey != null) 'placementKey': placementKey,
      if (screenId != null) 'screenId': screenId,
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
