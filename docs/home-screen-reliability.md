# Individual home-screen shortcuts and recovery

Hail 1.13.8 restores **Settings → Add all apps to Home screen** as a batch of separate launcher icons. Each icon retains its own position and Hail's unfreeze-and-open action. The action enumerates launchable apps in the current Android profile, including disabled apps and Hail-managed hidden launcher apps. It does not add a collection widget.

## One start, one app at a time

Android sends each shortcut request to the current launcher for approval. A plain loop cannot approve those requests and may cause a launcher to ignore earlier pending requests. Hail therefore keeps exactly one request in flight and waits for its completed placement before advancing.

For automatic placement, enable **Hail shortcut assistant** once in Android Accessibility settings. After setup, tapping **Add all apps to Home screen** starts the batch. A progress screen and ongoing notification provide pause and cancellation controls. Existing shortcut IDs reported as pinned by Android are skipped.

The assistant is optional. It only handles a recognized Add confirmation in the selected launcher during an automatic batch the user started. It checks the resolved shortcut-confirmation activity, Hail's publisher label, the requested app label, and a single visible, enabled positive control. The Launcher3 adapter obtains the Add label from the launcher's own localized resources. A restricted standard-dialog adapter requires Android's positive-button ID and unambiguous publisher and target labels.

If a launcher hides its confirmation from Accessibility or uses an unsupported screen, the batch pauses. **Continue manually** preserves the queue and lets the user approve the remaining icons. Hail does not guess screen coordinates, click unrelated Add buttons, grant permissions, or modify a launcher's private database. It declares that this optional automation service is not an accessibility tool for disability support.

Android still requires the user to enable the service. The first setup therefore includes Android's own permission steps; one-tap batch placement applies after that setup on a supported launcher.

## Completion and recovery

- A batch and every attempt have distinct persisted identifiers. An immutable, explicit callback targets a non-exported receiver.
- Automatic completion joins the launcher callback, Android's pinned-shortcut readback, and the assistant's observation that the approved confirmation actually closed. An existing shortcut can receive an immediate callback before placement, so a callback alone is never completion evidence.
- The queue persists atomically outside Activity saved state. Process loss restores it paused until the user chooses Resume.
- Timeouts pause rather than automatically submitting the same shortcut again. Resume and Retry reconcile pinned IDs before issuing another request.
- Locking the screen, changing launcher, disabling the assistant, unexpected screens, and storage errors stop automatic progression. Stale results and cancellation actions cannot mutate a different batch.
- Existing individual shortcuts retain their stable package IDs and private Hail launch action. Refreshing shortcut metadata preserves those IDs.

A launcher process restart normally retains its own pinned records. Clearing launcher data or restoring an incomplete launcher backup can erase its layout. Android's pinned-record API does not expose another launcher's private desktop coordinates, so Hail cannot reconstruct a deleted layout or prove that a stale record has a visible icon. **Refresh Home screen shortcuts** repairs surviving metadata; the individual app menu remains available for explicit re-placement.

The widget introduced in 1.13.7 remains registered so an upgrade does not remove widgets users already placed. It can still be selected from the launcher's widget picker, but the bulk Settings action now creates individual icons.

## Verification

The release gate exercises a complete automatic batch on the Android 16 launcher after a single Settings action. The test does not press the launcher's Add buttons. It verifies actual pinned shortcuts, distinct native icon views, unchanged widget IDs, cancellation behavior, and disk-backed recovery. Pure tests cover the three completion gates, stale results, a 300-package queue, and the assistant's strict screen matching. A physical manufacturer's launcher still needs device-specific validation.

## Platform references

- [ShortcutManager pinning and launcher approval](https://developer.android.com/reference/android/content/pm/ShortcutManager)
- [PinItemRequest and repeated placement of an existing shortcut](https://developer.android.com/reference/android/content/pm/LauncherApps.PinItemRequest)
- [AccessibilityService lifecycle and configuration](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [AOSP shortcut request validation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/ShortcutRequestPinProcessor.java)

## Signing and release verification

Android CI tests the source and builds the optimized APK. The release readiness job selects repository-secret signing when configured; otherwise it explicitly waits for offline signing. The offline publisher verifies the successful main run, exact unsigned artifact, existing fork certificate, APK version, signed checksum, and downloaded uploaded assets before publishing a release on the tested main commit.
