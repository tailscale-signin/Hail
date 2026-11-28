# App risk guidance

The Apps tab shows advisory package ratings offline, followed by the reason. Tap the reason
to expand or collapse it in place. No confirmation popup is added by the rating system.
Package actions, permissions and freeze-mode behavior are independent of the displayed rating.

| Upstream rating | Hail label | Meaning |
| --- | --- | --- |
| Recommended | Recommended | Community removal recommendation; required features can still vary by device. |
| Advanced | Caution | Check the description and feature dependencies before disabling. |
| Expert | Risky | Dependencies or substantial feature loss require particular care. |
| Unsafe | Unsafe | Known risk to essential functions or system stability. |
| Missing or unrecognized | Unknown | Hail has no usable community assessment for this exact package. |

System app names are yellow. Unsafe packages and essential device components are red,
including while frozen, selected, or retained outside the current profile. Other app names
use the normal theme color. These ratings describe disabling/removal risk; they are not
malware verdicts, proof of safety, or a claim that every unsafe package will cause a bootloop.

## Community source and attribution

Canta uses the Universal Android Debloater Next Generation list:

- [Canta's source configuration](https://github.com/samolego/Canta/blob/d40f8b868b299372807de90adcc1632501acd70c/composeApp/src/commonMain/kotlin/io/github/samolego/canta/data/bloat/BloatRepository.kt)
- [UAD-ng snapshot](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/blob/61fda8ba7b07aff0fb1351e705455ae3961fb868/resources/assets/uad_lists.json)
- [UAD-ng GPL-3.0 license](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/blob/61fda8ba7b07aff0fb1351e705455ae3961fb868/LICENSE)

The bundled snapshot contains 5,399 exact package entries: 3,018 Recommended, 1,160 Advanced,
923 Expert and 298 Unsafe. The JSON and descriptions are unmodified and compressed with gzip
in `app/src/main/assets/app-risk/uad_lists.json.gzip`. The `.gzip` suffix is intentional:
Android's asset packager automatically expands `.gz` files and removes their suffix.
`app/src/main/assets/app-risk/source.json` records the upstream commit, uncompressed SHA-256,
category counts and source URL. The license is included beside the data and credited in About.
No Canta application code is copied.

The catalog is read once on the background refresh path. It requires no internet connection,
does not upload installed-package names, and stays consistent for a released app version.
Missing or invalid data falls back to Unknown plus the local critical-component checks.

## Device-specific overrides

The currently selected Home app, current input method and active device administrators are
marked Unsafe with a concrete explanation of the function that can be lost. These roles are
refreshed with the app list, so changing the default does not permanently mark the old app.
Known core Android package names and system packages running as Android's system UID also
override an absent or incorrect lower community rating. Exact package matching is used;
the `com.android.*` prefix or the word `framework` in a package name is not sufficient.

## Updating the data

Review the upstream changes first, then import a complete immutable commit:

```sh
python3 scripts/update-app-risk-data.py FULL_40_CHARACTER_COMMIT_SHA
```

The script validates the schema/categories, stores the unmodified JSON as a deterministic
`.gzip` asset, and records its provenance. Update `AppRisks.SOURCE_COMMIT`, the counts in this
document and the About attribution to match the reviewed import. Run `AppRiskTest` and
`AppRiskPresentationTest`; the Android tests exercise the packaged catalog and recycled row
colors, including selected/frozen critical apps and the removed inactive-profile label.
Also inspect the built APK: it must contain `assets/app-risk/uad_lists.json.gzip`, and
decompressing that ZIP entry must match the source SHA-256 and package count in `source.json`.
