# digia_webengage_plugin

A Flutter plugin that bridges WebEngage self-handled in-app campaigns with Digia's rendering engine.

## Installation

Add to `pubspec.yaml`:

```yaml
dependencies:
  digia_webengage_plugin: ^1.0.0-beta.1
  digia_engage: ^1.1.0
  webengage_flutter: ^1.7.0
  we_personalization_flutter: ^1.1.1
```

## Quick Start

```dart
import 'package:digia_webengage_plugin/digia_webengage_plugin.dart';
import 'package:digia_engage/digia_engage.dart';
import 'package:webengage_flutter/webengage_flutter.dart';

void main() async {
  // Initialize WebEngage
    WebEngagePlugin _webEngagePlugin = new WebEngagePlugin();
  // Initialize Digia
  await Digia.initialize(DigiaConfig(apiKey: 'your-api-key'));

  // Register plugin
  Digia.register(DigiaWebEngagePlugin());

  runApp(const MyApp());
}
```

## Setup

1. Configure WebEngage following their [Flutter SDK docs](https://docs.webengage.com/docs/flutter-getting-started)
2. Initialize Digia SDK with your credentials
3. Register the plugin with Digia after both are initialized
4. Use `Digia.forwardScreen()` when navigating to trigger campaign delivery

## How It Works

WebEngage Campaign → Plugin bridges to Digia → Digia renders → Events sent back to WebEngage

## Architecture

The plugin consists of:

- **DigiaWebEngagePlugin**: Main plugin class implementing `DigiaCEPPlugin`
- **WebEngageSdkBridge**: Bridges WebEngage SDK callbacks to Dart
- **WebEngageEventBridge**: Forwards events back to WebEngage analytics
- **WebEngagePayloadMapper**: Maps WebEngage data to Digia payload format
<!-- 
## Inline Campaigns

For inline/personalization campaigns:

```dart
// Register placeholder when creating DigiaSlot
final placeholderId = plugin.registerPlaceholder('screen_name', 'property_id');

// Later deregister when view is disposed
plugin.deregisterPlaceholder(placeholderId);
``` -->

## Troubleshooting

**Campaigns not appearing?**
- Verify plugin registered: `Digia.register(DigiaWebEngagePlugin())`
- Ensure WebEngage initialized before Digia
- Check iOS deployment target ≥ 12.0, Android API ≥ 21
- Check campaign eligibility in WebEngage dashboard

**Build/Setup Issues?**
- Run `flutter clean && flutter pub get`
- Verify all dependencies in pubspec.yaml

## Platforms

- **iOS**: ✅ 12.0+
- **Android**: ✅ API 21+

## License

MIT License - see [LICENSE](LICENSE)

## More Info

- See [CHANGELOG.md](CHANGELOG.md) for version history
- [Digia Engage](https://pub.dev/packages/digia_engage) - Core plugin interface
- [WebEngage Flutter](https://pub.dev/packages/webengage_flutter) - WebEngage SDK
- Issues: [GitHub Issues](https://github.com/Digia-Technology-Private-Limited/digia_engage_webengage/issues)
