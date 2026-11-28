# Individual home-screen shortcuts and recovery

Hail 1.13.8 restores **Settings → Add all apps to Home screen** as a batch of separate launcher icons. Each icon retains its own position and Hail's unfreeze-and-open action. The action enumerates launchable apps in the current Android profile, including disabled apps and Hail-managed hidden launcher apps. It does not add a collection widget.

## One start, one app at a time

Android sends each shortcut request to the current launcher for approval. A plain loop cannot approve those requests and may cause a launcher to ignore earlier pending requests. Hail therefore keeps exactly one request in flight and waits for its completed placement before advancing.

For automatic placement, enable **Hail shortcut assistant** once in Android Accessibility settings. After setup, tapping **Add all apps to Home screen** starts the batch. A progress screen and ongoing notification provide pause and cancellation controls. Existing shortcut IDs reported as pinned by Android are skipped.

The assistant is optional. It only handles a recognized Add confirmation in the selected launcher during an automatic batch the user started. It checks the resolved shortcut-confirmation activity, the requested app label, and a single visible, enabled positive control. The Launcher3 adapter also checks Hail's publisher label and obtains the Add label from the launcher's own localized resources. A restricted standard-dialog adapter requires Android's positive-button ID and unambiguous publisher and target labels.

Nova has its own adapter because its `widget_appName` contains Nova's application label, not the publisher. The adapter requires Nova's exact `AddItemActivity`, the installed Nova label, the exact pending shortcut label, the localized visible Shortcut marker, no visible widget dimensions, and the named localized Add button. These must be distinct, read-only preview views. An ordinary Nova edit dialog or widget confirmation cannot pass this contract. Numeric resource IDs are resolved from the installed package rather than hardcoded. The contract was inspected in unchanged official Nova 8.0.18 and 8.1.1 beta APKs; this is not evidence of the version installed on any particular user's phone.

If a launcher hides its confirmation from Accessibility or uses an unsupported screen, the batch pauses. **Continue manually** preserves the queue and lets the user approve the remaining icons. Hail does not guess screen coordinates, click unrelated Add buttons, grant permissions, or modify a launcher's private database. It declares that this optional automation service is not an accessibility tool for disability support.

Android still requires the user to enable the service. The first setup therefore includes Android's own permission steps; one-tap batch placement applies after that setup on a supported launcher.

## Completion and recovery

- A batch and every attempt have distinct persisted identifiers. An immutable, explicit callback targets a non-exported receiver.
- Automatic completion requires Android's pinned-shortcut readback and observation that the approved confirmation actually closed. A callback or a verified assistant click establishes approval; the latter lets an omitted callback recover after dismissal. An existing shortcut can receive an immediate callback before placement, so a callback alone is never completion evidence.
- The queue persists atomically outside Activity saved state. Process loss restores it paused until the user chooses Resume.
- Timeouts pause rather than automatically submitting the same shortcut again. Resume and Retry reconcile pinned IDs before issuing another request.
- Locking the screen, changing launcher, disabling the assistant, unexpected screens, and storage errors stop automatic progression. Stale results and cancellation actions cannot mutate a different batch.
- Existing individual shortcuts retain their stable package IDs and private Hail launch action. Refreshing shortcut metadata preserves those IDs.

A launcher process restart normally retains its own pinned records. Clearing launcher data or restoring an incomplete launcher backup can erase its layout. Android's publisher-side pinned-record list includes pins held by any launcher; it does not identify which launcher holds each ID or expose private desktop coordinates. After switching launchers, the batch may therefore report an existing shortcut that has no icon in the current Home. **Refresh Home screen shortcuts** repairs surviving metadata. To place a missing icon explicitly, long-press that app in Hail Home and choose **Add to Home screen**. Hail keeps the same shortcut ID; requesting every existing ID again automatically could create duplicate icons.

## Nova backups and Android backups

These are separate restore paths. The inspected Nova 8.0.18 and 8.1.1 beta builds save native shortcut references as publisher package, shortcut ID and Android user. Their backup does not contain Hail's actual launch action. When loading a restored layout, Nova first looks for an existing native pinned record, then a published dynamic/manifest shortcut. If neither exists, it drops the unresolved layout row. Ordinary app icons can still restore because their launchable activities remain installed.

Hail's bulk app shortcuts use stable package-name IDs, but they are pin-only records rather than a published menu of every installed app. Android caps dynamic shortcuts per activity; publishing all 284 as dynamic shortcuts is not a viable substitute. Marking a shortcut long-lived also only makes it eligible for caching, not permanently retained.

Hail 1.13.9 enables Android's backup eligibility for shortcut metadata. Ordinary Hail app files and preferences stay excluded through `fullBackupContent="false"` and the existing complete cloud/device-transfer exclusions. This corrects the publisher-side flag that caused Android to omit shortcut action details and reject restoration. It does not change what an older backup already contains. The private action activity remains non-exported; Android's native shortcut service can legitimately dispatch it as Hail, and making it public would not reconstruct a missing native record.

Android does not include shortcut bitmap icons in its backup. The explicit refresh action regenerates bounded icons as well as metadata for recognized surviving Hail pins, preserving IDs and tag/action semantics. A one-time foreground repair uses a marker outside backup storage and only records completion after all updates succeed. Partial or rate-limited repairs remain retryable.

For a Nova-only backup whose native records are gone:

1. Keep the original Nova backup, install the same Hail edition and target apps in the correct Android profile, and make Nova the default Home app.
2. Restore Hail's settings backup if needed. Recreate shortcuts with custom tags or actions first, using their original definitions. Tagged app-launch shortcuts share their package-name ID with the standard app shortcut; creating the standard shortcut first would lose the original tag behavior.
3. Run **Add all apps to Home screen** to recreate the remaining standard app shortcut IDs. The batch skips all IDs Android reports as pinned, including IDs held by another launcher. For an icon still missing in Nova, use the app's **Add to Home screen** action in Hail Home to request placement explicitly.
4. Reapply the original Nova backup. Nova can match the restored native IDs to the positions saved in its file.

This sequence follows the inspected Nova loader's lookup behavior; it still needs an actual Nova backup/restore device run before claiming end-to-end verification. It cannot recover positions after the only original backup is lost, restore a different Android profile's records, or guarantee repair when an older backup used different shortcut IDs. A same-device Nova restore with native pins still present is a separate case and should be captured with diagnostics if it fails.

The widget introduced in 1.13.7 remains registered so an upgrade does not remove widgets users already placed. It can still be selected from the launcher's widget picker, but the bulk Settings action now creates individual icons.

## Verification

The release gates exercise complete automatic batches on the Android 16 standard launcher and, in a separate fresh Google APIs emulator, official Nova 8.0.18. Each long run installs 284 fixture apps and starts the production queue once. The tests do not press the launcher's Add buttons. They verify actual pinned shortcuts, distinct native icon views, unchanged widget IDs, cancellation behavior, and disk-backed recovery. Nova's normal localized Skip setup action is permitted only before the batch; the gate verifies the original APK checksum/signature, ARM translation support, resolved default Home and resumed Nova Home. Failure of either launcher gate prevents publication. Pure tests cover completion evidence, stale results, long queues, item 115 recovery, and the assistant's strict screen matching. Physical-device behavior still needs device-specific validation.

## Platform references

- [ShortcutManager pinning and launcher approval](https://developer.android.com/reference/android/content/pm/ShortcutManager)
- [PinItemRequest and repeated placement of an existing shortcut](https://developer.android.com/reference/android/content/pm/LauncherApps.PinItemRequest)
- [LauncherApps and separate pinned sets for each launcher](https://developer.android.com/reference/android/content/pm/LauncherApps)
- [AccessibilityService lifecycle and configuration](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [AOSP shortcut request validation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/ShortcutRequestPinProcessor.java)
- [Nova official distribution channels](https://novalauncher.com/beta/)

## Signing and release verification

Android CI tests the source and builds the optimized APK. The release readiness job selects repository-secret signing when configured; otherwise it explicitly waits for offline signing. The offline publisher verifies the successful main run, exact unsigned artifact, existing fork certificate, APK version, signed checksum, and downloaded uploaded assets before publishing a release on the tested main commit.
