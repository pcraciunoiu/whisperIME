# Publishing (F-Droid & GitHub Releases)

This branch uses application id **`org.speechtotext.input`** and display name **SpeechToText** so it installs alongside the upstream F-Droid app (`org.woheller69.whisper`).

## GitHub Actions — repository secrets

For **Build APK** (`.github/workflows/build.yml`), no secrets are required.

For **signed releases** (`.github/workflows/release.yml`), add these [repository secrets](https://docs.github.com/en/actions/security-guides/using-secrets-in-github-actions):

| Secret | Purpose |
|--------|---------|
| `KEYSTORE_BASE64` | Base64-encoded release keystore file (same bytes as your `.jks` / `.keystore`) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Signing key alias |
| `KEY_PASSWORD` | Key password (often same as keystore password) |

Encode locally: `base64 -w0 release.keystore` (GNU coreutils; on macOS omit `-w0` or use `base64 -i`).

**Triggers**

- Push a Git tag matching `v*` (e.g. `v3.8`) to build a **release** APK and attach it to a GitHub Release for that tag.
- **Actions → Build & Release Signed APK → Run workflow** builds a **prerelease** with name `v<versionName>-<shortSha>`.

F-Droid normally signs APKs with their own key; your upload key is mainly for GitHub/GitLab distribution and for optional [reproducible builds](https://f-droid.org/docs/Reproducible_Builds/) verification if you pursue that later.

## What F-Droid needs from you

F-Droid does not use a single “developer account” like a Play Console login. You submit apps by opening a merge request to **[fdroiddata](https://gitlab.com/fdroid/fdroiddata)** (or following the current [quick start guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)). Prepare:

1. **Source code URL** — Public Git repository (e.g. GitHub) with a **tag or commit** F-Droid can build from.
2. **Application id** — `org.speechtotext.input` (must match `applicationId` in `app/build.gradle`).
3. **License** — MIT (see `LICENSE`; retain upstream copyright notices as required).
4. **Build recipe fields** — Typically `gradle yes`, subdir `app` if needed, correct `versionCode` / tag; reviewers may ask for `ndk` / `gradle` versions matching your project.
5. **Metadata** — Summary, description, changelog, category, anti-features (e.g. `NonFreeNet` if you pull non-free network resources; models downloaded on demand are usually documented separately). This repo’s English fastlane metadata lives under `fastlane/metadata/android/en-US/`.
6. **Screenshots / icon** — Under `fastlane/metadata/android/en-US/images/` where applicable.

You will need a **GitLab.com account** (or whatever forge fdroiddata uses) to fork fdroiddata and open an MR. Forum discussion is optional for policy questions.

## Local release build without CI

Without `KEYSTORE_FILE` set, `assembleRelease` uses the **debug** signing config so you can verify the release build locally.
