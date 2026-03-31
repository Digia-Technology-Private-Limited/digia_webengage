# Digia + WebEngage (Flutter)

Setup guide for Flutter apps using `digia_engage_webengage`.

---

## User identification

Call at login and sign-out:

```dart
// At login (after successful auth)
await AnalyticsService().identifyUser(
  userId,
  email: user.email,
  firstName: user.firstName,
  lastName: user.lastName,
);

// At sign-out
await AnalyticsService().logoutUser();
```

---

## Screen name alignment

WebEngage inline campaigns target by **screen name** and **property id**. Use the exact strings below when creating campaigns in the WebEngage dashboard.

| Screen | Screen name (Flutter) | Placement key |
|--------|------------------------|---------------|
| Home | `home-page` | `digia_homepage_slot` |
| Product detail | `product-detail-page` | `digia_pdp_slot` |
| Cart | `cart` | — |
| Search | `search-page` | — |
| Brands | `brands-page` | — |
| Account | `account-page` | — |

Ensure `Digia.setCurrentScreen()` / `DigiaScreen(name)` and `DigiaSlot(placementKey)` use these values. `DigiaNavigatorObserver` forwards route names from `RouteSettings(name: ...)`.
