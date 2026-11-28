# Shizuku integration

## Upstream audit

Checked against official upstream sources on 2026-11-28. The application/server and client API have independent version numbers.

| Component | Published version checked | Decision |
| --- | --- | --- |
| Shizuku client API and provider | 13.1.5 | Keep both at the latest published Maven version. No newer published alpha or beta appears in the official metadata. |
| Shizuku application/server | 13.6.0 | The device gate installs the upstream APK and verifies its pinned checksum. |
| Sui | 13.5.1 | Keep the provider's supported automatic Sui initialization. |
| Core library desugaring | 2.1.5 | Enable the latest published Google Maven version for the retained Android 6/API 23 minimum. |

The API repository's development branch has newer commits, but the released API source and current `Shizuku.java` differ only in documentation corrections at this audit. An unmerged change or an unreleased branch is not a published replacement dependency. Review new published previews when they provide a relevant fix and pass the same compatibility gates.

The existing `ShizukuProvider` manifest entry uses the upstream `INTERACT_ACROSS_USERS_FULL` protection. It already initializes Sui when available. Hail does not start Shizuku, grant itself permission, install a privileged Android service, or change device-wide debugging configuration as part of normal app startup.

## Connection and permission lifecycle

`ShizukuConnection` registers a sticky binder-received listener, a binder-dead listener and a permission-result observer once for the application process. It distinguishes a stopped service, an unsupported old protocol, a requestable permission, a remembered denial, and a ready service. Settings explains the current condition and whether the service runs as root or ADB shell.

Received and replaced binders invalidate the old command connection. Binder death immediately clears readiness. State reads carry a connection generation so an old read or old permission callback cannot restore a stale connection. Returning to Hail refreshes the state, and connection changes notify the package lists so an earlier unavailable state can be rechecked.

Only an explicit Settings action requests permission. Requests are serialized, use distinct request codes, and remove their temporary listeners on success, cancellation, timeout, failure or binder death. Changing the selected working mode or destroying the Settings view cancels that screen's request; a late result cannot overwrite the newer choice. A permanent denial opens the normal Shizuku management path instead of repeatedly asking for permission.

## Package operations and commands

Freeze/enable continues to use `ShizukuBinderWrapper` with Android's package APIs, current-user scope, batch enablement where supported, and observed-state verification. A returned Binder call alone is not proof that an OEM applied the requested state. The root and ADB identities retain their actual Android permissions; connecting through Shizuku does not make an ADB service root.

The command path uses the public `bindUserService` API rather than the deprecated raw `IShizukuService.newProcess` route. A non-daemon worker exists for an explicit command, runs under the existing Shizuku identity, bounds waiting/output, and is cleaned up afterward. It never retries a submitted mutation after losing the connection. Uninstall/restore commands carry the current Android user explicitly, and ordinary freeze/enable does not pass through a shell.

## Validation and limits

The standard device suite includes real Shizuku permission and package-state tests, a UserService command/large-output/rebind scenario, and real API listener add/remove checks. The latter directly exercises the listener methods requiring desugared Java APIs on API 23. Running it on API 36 establishes behavior there; it does not substitute for an API 23 runtime check. The build must preserve the UserService constructor through R8 and include the desugared implementation.

Device gates and unsigned builds are not publication or proof that a particular phone has passed. Record the exact build, server version, Android version and launcher with actual test evidence.

## Primary references

- [Official Shizuku API guide and changelog](https://github.com/RikkaApps/Shizuku-API)
- [Released API Maven metadata](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/api/maven-metadata.xml)
- [Released provider Maven metadata](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/provider/maven-metadata.xml)
- [Shizuku 13.6.0 release](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)
- [Sui 13.5.1 release](https://github.com/RikkaApps/Sui/releases/tag/v13.5.1)
- [Android core library desugaring](https://developer.android.com/studio/write/java8-support)
- [Google desugaring library metadata](https://dl.google.com/dl/android/maven2/com/android/tools/desugar_jdk_libs/maven-metadata.xml)
