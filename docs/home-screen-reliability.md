# Home-screen grid and shortcut reliability

Hail 1.13.7 replaces the sequential bulk pin queue with a platform collection widget. Android's `requestPinShortcut` is per shortcut, and the launcher decides whether to prompt for each. Repeated requests may be ignored by the host. Hail does not bypass launcher approval or edit a launcher's private layout database.

The primary Settings action requests one `AppWidgetManager.requestPinAppWidget` operation. The host can show one approval for the whole grid. A successful request return value does not mean the grid was placed: only the pin callback or registered widget IDs establish that. Hosts without in-app widget pinning use their normal Widgets picker.

The grid obtains current-profile launchable apps from PackageManager, with disabled launcher activities included for Hail's thaw-and-launch path. It does not list packages solely because retained metadata exists. `RemoteViewsService` loads cells on demand rather than sending hundreds of icon bitmaps in a single Binder transaction. Each cell targets Hail's existing private action activity and its normal biometric setting.

Widget adapters and pending intents use stable, per-widget data URIs. Cell IDs derive from the package name. Restored widget IDs cause a rebind; app upgrades, reboot and package changes trigger refresh. Existing individual pinned shortcuts retain their IDs and launch options. A transient package lookup failure never removes a pin.

A launcher process restart should preserve its widget binding and pinned records. Clearing launcher data, uninstalling a launcher or restoring an incomplete launcher backup can erase its own layout; Hail cannot silently reconstruct another app's private layout. Settings provides refresh for surviving records and one-request grid placement when the host no longer owns a Hail grid.

## Platform references

- [ShortcutManager pinning and launcher approval](https://developer.android.com/reference/android/content/pm/ShortcutManager)
- [Collection widgets and RemoteViewsService](https://developer.android.com/develop/ui/views/appwidgets/collections)
- [Requesting one widget pin](https://developer.android.com/develop/ui/views/appwidgets/discoverability)

## Signing and release verification

Android CI always tests the source and builds the optimized APK. A release readiness job selects the existing repository-secret signing route when configured. Otherwise it explicitly reports that publication is waiting for offline signing; it does not report a published release. The offline publisher must verify the successful main-source run, exact unsigned artifact checksum, existing fork certificate, APK version, signed checksum and downloaded uploaded assets before publishing a release on that verified main commit.
