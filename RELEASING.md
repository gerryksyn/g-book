# Releasing G-Book

The repeatable process for shipping a new version: build it, sign it, publish it, verify it.
Every command here was actually run to produce v0.6.2.

---

## One-time setup (already done on this machine)

| What | Where |
|---|---|
| JDK 17+ | `C:/Program Files/Android/Android Studio/jbr` (JDK 21 works) |
| Android SDK | `%LOCALAPPDATA%/Android/Sdk` |
| GitHub CLI | `C:/Program Files/GitHub CLI/gh.exe`. **Not on PATH in Git Bash**, so use the full path |
| Release keystore | outside the repo, e.g. `~/keystores/gbook-release.jks` |

Open a fresh terminal and set these once per session:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
GH="/c/Program Files/GitHub CLI/gh.exe"
BT="$LOCALAPPDATA/Android/Sdk/build-tools/34.0.0"
```

`keystore.properties` must exist in the repo root (git-ignored):

```properties
storeFile=/absolute/path/to/gbook-release.jks
storePassword=…
keyAlias=gbook
keyPassword=…
```

If that file is missing the build still succeeds, but the APK comes out **unsigned**. Check the
signature in step 3 before you publish anything.

---

## 1. Bump the version

Two numbers in `app/build.gradle.kts`:

```kotlin
versionCode = 21        // always +1, never reuse. Android refuses to "downgrade".
versionName = "0.6.2"   // what users see
```

`versionCode` must increase for every release you publish, including re-uploads. Android compares
it, not the name.

## 2. Build and test

```bash
./gradlew clean :app:testDebugUnitTest :app:assembleRelease
```

First build after a `clean` on a cold Gradle cache took **34 minutes** here. Later builds are far
faster. Don't assume it's hung.

Output: `app/build/outputs/apk/release/app-release.apk`

## 3. Verify the artifact (do not skip this)

```bash
APK=app/build/outputs/apk/release/app-release.apk

# is it signed, and by whom?
"$BT/apksigner.bat" verify --print-certs "$APK" | grep "certificate DN"
# expect: CN=Gerry, OU=G-Book, O=G-Book, ...

# is it the right package and version, and not debuggable?
"$BT/aapt2.exe" dump badging "$APK" | grep -E "^package|debuggable"

sha256sum "$APK"
stat -c "%s bytes" "$APK"
```

The certificate DN must say `CN=Gerry`. If it says `CN=Android Debug`, the signing config didn't
load. Fix that before publishing, because a debug-signed build cannot be upgraded from.

## 4. Update the site

Copy the APK in under its release name, and drop the previous one:

```bash
cp "$APK" docs/assets/apk/G-Book-vX.Y.Z.apk
rm docs/assets/apk/G-Book-v<previous>.apk
cd docs/assets/apk && sha256sum G-Book-vX.Y.Z.apk | sed 's/ \*/  /' > SHA256SUMS.txt && cd -
```

Then update these strings in `docs/index.html`:

| Where | What |
|---|---|
| hero chip | `<b>18.6 MB</b> &middot; offline` |
| hero download link | `href="assets/apk/G-Book-vX.Y.Z.apk"` |
| download card button | `href` **and** the visible filename |
| `File size` row | `18.6 MB` |
| `SHA-256` block | the new hash |
| changelog | new entry at the top, previous one loses nothing |

And in `README.md`: the `**Download:**` line, the `Size` and `SHA-256` rows, and a new changelog
entry. `docs/README.md` explains the APK swap if you forget.

Old APKs stay in git history forever, so delete the previous one rather than accumulating 18 MB
per release.

## 5. Commit

```bash
git add -A
git status --short          # confirm no keystore.properties, no *.jks, no local.properties
git commit -m "G-Book vX.Y.Z"
git push origin main
```

If the push times out, run it in the background. The repo is ~55 MB and a cold push can take
longer than a foreground timeout.

## 6. Tag and publish the release

```bash
"$GH" release create vX.Y.Z \
  --title "G-Book vX.Y.Z" \
  --notes-file /tmp/notes.md \
  --target main \
  "docs/assets/apk/G-Book-vX.Y.Z.apk" \
  "docs/assets/apk/SHA256SUMS.txt"
```

The release notes should always state: what changed, how to install, the SHA-256, the signing
identity, plus a note that an uninstall is required if the key changed.

## 7. Verify what you actually published

HTTP 200 is not proof. Check the content:

```bash
# the page really is the new page
curl -s https://gerryksyn.github.io/g-book/ | grep -o "<title>[^<]*</title>"

# the APK on the page is the new APK
curl -sI https://gerryksyn.github.io/g-book/assets/apk/G-Book-vX.Y.Z.apk | grep -i content-length

# and it is byte-identical to what you built
curl -sL -o /tmp/a.apk https://github.com/gerryksyn/g-book/releases/download/vX.Y.Z/G-Book-vX.Y.Z.apk
sha256sum /tmp/a.apk && sha256sum "docs/assets/apk/G-Book-vX.Y.Z.apk"
```

Then open the page in a browser and look at it.

---

## Checklist

- [ ] `versionCode` incremented
- [ ] `./gradlew clean :app:testDebugUnitTest :app:assembleRelease` → BUILD SUCCESSFUL
- [ ] Certificate DN is `CN=Gerry`, not `CN=Android Debug`
- [ ] `debuggable` absent from `aapt2 dump badging`
- [ ] New APK copied to `docs/assets/apk/`, old one deleted
- [ ] `SHA256SUMS.txt` regenerated
- [ ] `docs/index.html`: size, hash, filename, links, changelog
- [ ] `README.md`: download line, size, hash, changelog
- [ ] `git status` shows no key material
- [ ] Pushed, Pages rebuilt, live page shows the new version
- [ ] Release published with APK + checksums
- [ ] Downloaded the published APK and hashed it against the local build
- [ ] **Installed it on a real phone**

That last one is the only step that proves the build runs. Everything above proves it is *correct*;
none of it proves it *works*.

---

## When something goes wrong

| Symptom | Cause and fix |
|---|---|
| "App not installed" on a phone that already has G-Book | Signature mismatch. The installed copy was signed with a different key. Back up progress, uninstall, reinstall, restore. |
| Release APK is unsigned | `keystore.properties` missing or has a bad path. Check `storeFile`. |
| `compileSdk 34 not found` | AGP can download it if SDK licences are accepted; otherwise install platform 34 in Android Studio's SDK Manager. |
| Build sits for 30+ minutes with no output | Normal on a cold cache, since Compose compilation is slow. Check `app/build/intermediates` is growing before assuming a hang. |
| Page serves the wrong content, or a Jekyll-generated title like `G-Book \| g-book` | Pages source is wrong, or the build predates a config change. `PUT` the source, then `POST repos/{owner}/{repo}/pages/builds` to force a rebuild. |
| `gh: command not found` | It is not on PATH. Use `"/c/Program Files/GitHub CLI/gh.exe"`. |
| Push times out | Run it in the background; ~55 MB repo. |
| `git push` rejected, non-fast-forward | Someone (or a web edit) changed the remote. `git pull --rebase` then push. |

---

## Things that must never be committed

- `keystore.properties`
- `*.jks`, `*.keystore`
- `local.properties`

All three are in `.gitignore`. Losing the keystore means no one can ever update the app in place
again; leaking it means someone else can publish an update Android will happily install over yours.
