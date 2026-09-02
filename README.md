# ALPHA INJECTIOR — Android project

A WebView-based app with 3 buttons (WhatsApp, Telegram, Discord) that opens
their web versions and auto-injects your "Alpha Mic Boost" engine
(volume boost, EQ, noise gate, distortion, echo, 5-voice changer) into
those pages, so it works exactly like your Chrome extension did — but
packaged as a standalone app instead of a shareable/clonable extension file.

## What was changed from the extension

Your extension's `content.js`, `license.js`, `config.js` and `inject.js`
are used almost verbatim. The only thing replaced is the handful of
`chrome.*` extension-API calls (`chrome.storage`, `chrome.runtime.getURL`,
`chrome.runtime.getManifest`) with a small compatibility shim
(`app/src/main/assets/alpha_bundle.js`, top of file) so the exact same
logic runs inside a plain WebView:

- `chrome.storage.local` → backed by the WebView's `localStorage`
- `chrome.runtime.getURL('icons/...')` → the two icon PNGs are embedded
  as base64 data URLs directly in the bundle
- `chrome.runtime.getURL('inject.js')` → `inject.js` (your audio engine,
  which had zero `chrome.*` dependencies) is inlined as a plain function
  and called directly instead of being loaded as a separate extension
  script — this also avoids any page Content-Security-Policy issues on
  sites like Discord.

Your license/admin-approval flow (`ALPHA_BACKEND_URL`, device ID,
approve/block) works exactly as before — nothing about that was touched.

## How to build the APK

1. Install **Android Studio** (free, from developer.android.com) if you
   don't have it.
2. Open Android Studio → **Open** → select this `QuickApps` folder.
3. Let it sync (first time it downloads Gradle + the Android SDK
   automatically — needs internet, takes a few minutes).
4. Menu: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. When it finishes, click the **locate** link in the notification, or
   find the file at:
   `app/build/outputs/apk/debug/app-debug.apk`
6. Copy that `.apk` to your phone and install it (enable "install from
   unknown sources" if asked).

That's a debug APK — fine for personal use / sharing directly. If you
ever want to publish it on the Play Store, Android Studio can also
generate a signed **release** APK/AAB (Build → Generate Signed Bundle/APK).

## Notes

- The app requests microphone permission on first launch, and
  auto-grants in-page mic requests from WhatsApp/Telegram/Discord (so
  the user isn't shown a second browser-style permission popup — remove
  the `onPermissionRequest` auto-grant in `MainActivity.kt` if you'd
  rather the user confirm each time).
- Want more/fewer apps on the top bar? Edit the URLs and buttons in
  `activity_main.xml` + `MainActivity.kt` — same pattern for any other
  web app.
## Note on file protection

`alpha_bundle.js` is no longer stored as plain text — it's now
`assets/alpha.dat`, XOR-obfuscated at build time and decoded at runtime
in `MainActivity.kt` (`readObfuscatedAsset`). Opening the APK in a zip
tool (MT Manager etc.) now shows unreadable binary garbage instead of
your source, so casual copying is blocked. Being upfront: this is a
deterrent, not unbreakable encryption — someone determined enough to
reverse-engineer the app could still recover the key from the compiled
code. For a browser extension file this level of protection is normal
and enough to stop casual sharing/cloning.

## Fix: one device ID across WhatsApp/Telegram/Discord

`alphaBuyerUuid` used to be stored via `chrome.storage.local`, shimmed to
plain WebView `localStorage`. localStorage is scoped **per origin**, and
WhatsApp/Telegram/Discord are three different origins — so each site
silently generated and stored its own device ID, and the admin had to
approve three separate IDs for what was really one physical device
(this is why one app could show "approved" while another still showed
"waiting for admin approval").

Fixed by adding `AlphaNativeBridge`, a `@JavascriptInterface` backed by a
single Android `SharedPreferences` file shared by the whole app (not
scoped per-origin). `chrome.storage.local` now reads/writes through that
bridge when it's available, falling back to `localStorage` otherwise. One
device ID, one admin approval, works on all three apps.

## Fix: approval only ever "took" on Telegram

Even with one shared device ID, the app-side status still only showed as
approved on Telegram and stayed on "waiting for admin approval" on
WhatsApp/Discord. Cause: `activate()`/`checkStatus()` called `fetch()`
against `ALPHA_BACKEND_URL` — and that `fetch()` runs inside the loaded
page's own JavaScript, so it's bound by that page's
`Content-Security-Policy: connect-src` rule. WhatsApp Web's and Discord's
CSPs only allow outgoing requests to their own domains and silently
reject/throw on anything else, which the code was quietly swallowing as
`status: 'offline'`. Telegram Web's CSP happens to be loose enough to let
the request through, which is why that was the only one that ever worked.

Fixed by adding `AlphaNative.nativeFetch(...)` to `AlphaNativeBridge` in
`MainActivity.kt`: it performs the HTTP call from plain Kotlin
(`HttpURLConnection` on a background thread) and delivers the result back
into the page via `window.__alphaNativeFetchResult(...)`. A request made
from Kotlin isn't a page `fetch()` at all, so no site's CSP can see or
block it. `alpha_bundle.js`'s `activate`/`checkStatus` now go through a
small `alphaFetch()` helper that uses `AlphaNative.nativeFetch` when it's
available (i.e. always, inside this app) and falls back to a normal
`fetch()` otherwise, so the exact same bundle would still work if it were
ever loaded as a plain browser extension again.

## Other changes in this build

- **Renamed** the app to **ALPHA INJECTIOR** (`strings.xml` / manifest);
  the launcher icon, the round icon, the adaptive-icon foreground and the
  in-app header logo were all regenerated from the gold "Alpha Sound Mic"
  artwork you supplied.
- **Black + gold theme** (`colors.xml` / `themes.xml`) to match the mic
  logo — dark status/navigation bar, gold header text and icons, WhatsApp/
  Telegram/Discord buttons kept their brand colors but darkened, with a
  thin gold outline.
- **Desktop-site toggle button** in the header (next to refresh): a single
  tap flips the WebView — all three apps, since they share one instance —
  between the forced-desktop user agent and the normal mobile one, the
  same idea as the per-site "Desktop site" switch in Kiwi Browser, just
  applied to the whole app-as-an-extension instead of one tab at a time.
  It defaults to ON since desktop mode was already the point of this app.
- **All permissions added** — mic, camera, phone state, contacts, and
  (Android 13+) granular photos/videos/audio media permissions, all
  requested together on first launch (`AndroidManifest.xml` +
  `requestAllPermissions()`).
- **"Attach photo/video" now actually works** — added
  `onShowFileChooser(...)` to the `WebChromeClient`, so the attach/profile-
  picture buttons on WhatsApp/Telegram/Discord open the phone's real camera
  or gallery picker (via a `FileProvider`) instead of doing nothing.
- **No more "please update your browser" warning** — the WebView's default
  user-agent includes a `wv` marker that WhatsApp/Discord specifically
  detect and warn about (and sometimes use to downgrade/block calling). The
  app now always sends a real Chrome desktop/mobile UA (never the raw
  WebView default), in both desktop and mobile toggle states.

## Fix: "Your browser doesn't support calling" popup during WhatsApp calls

Even after the UA-string fix above, WhatsApp Business's call screen kept
showing this. Reason: `settings.userAgentString` only rewrites the
*classic* `navigator.userAgent` string. Since Chrome 89, Chromium (which is
what WebView runs on) separately reports browser identity through
**User-Agent Client Hints** — `navigator.userAgentData.brands` — and the
Android WebView engine always includes an `"Android WebView"` brand there,
completely independent of whatever UA string you set. WhatsApp's "does
this browser support calling" check reads `userAgentData`, not the old UA
string, so it kept seeing "Android WebView" and blocking it.

Fixed by injecting a small script — via
`WebViewCompat.addDocumentStartJavaScript` (androidx.webkit) — that
overrides `navigator.userAgentData` (brands, `getHighEntropyValues()`,
etc.) with real desktop/mobile Chrome values, *before* WhatsApp's own
scripts run on the page. It re-registers itself whenever the desktop/mobile
toggle is flipped, so it always matches the current UA string. On very old
WebView builds that don't support `DOCUMENT_START_SCRIPT`, the app falls
back to running the same override in `onPageStarted` (slightly less
reliable, since a couple of page scripts may already have run, but still
helps).

One thing spoofing can't fix: if the phone's actual **Android System
WebView** (Settings → Apps → Android System WebView, or Chrome, whichever
one is set as your WebView provider) is genuinely very outdated, some
WebRTC calling internals may be missing for real — no UA override can add
capability that isn't there. Worth updating it from the Play Store once,
independent of anything this app does.

## Fix: tapping Call did nothing at all (no popup, nothing)

`onPermissionRequest` used to call `request.grant(request.resources)`
unconditionally. That WebView-level `grant()` only actually works if the
*Android* `RECORD_AUDIO`/`CAMERA` permission was already approved by the
user — if that runtime dialog got denied, dismissed, or was never shown
(e.g. Android only shows one permission dialog at a time and a later one
can get skipped), `grant()` silently does nothing, `getUserMedia()` rejects
inside the page with no visible error, and the call button looks like it
just doesn't respond.

Fixed: `onPermissionRequest` now checks the real Android permission first;
if it's missing, it shows the live system permission prompt right then
(via a new `callPermissionLauncher`) and only grants the web request once
the user actually taps Allow — denying it cleanly otherwise instead of
leaving the page hanging. If you still don't see a permission prompt at
all when tapping Call, check Settings → Apps → ALPHA INJECTIOR →
Permissions and make sure Microphone (and Camera, for video calls) are set
to Allow — if they were ever denied, Android may need that reset by hand
before the in-app prompt will show again.

## Fix: the call button couldn't be tapped at all (worked fine in Kiwi Browser)

The earlier `navigator.userAgentData` fix (installClientHintOverride) only
rewrites what the *page's own JavaScript* can read. It does **not** touch
the real `Sec-CH-UA` / `Sec-CH-UA-Mobile` / `Sec-CH-UA-Platform` HTTP
headers Chromium actually sends over the network with every request —
including the WebSocket connection WhatsApp uses to negotiate a call.
WhatsApp's server reads *those* headers, sees "Android WebView" from the
very first request (before any page JS runs), and tags that session as
unsupported for calling server-side — which shows up as a call button that
renders normally but simply doesn't respond to taps, no popup, nothing to
catch in a JS console error either. Kiwi Browser worked because it's a
real standalone Chromium browser build, so it never sends that WebView
marker in the first place — nothing to spoof.

Fixed by using `WebSettingsCompat.setUserAgentMetadata(...)` (from
`androidx.webkit`), which is the one public API that rewrites the actual
outgoing Client Hints headers, not just the JS-readable copy — so the
network layer and the page-JS layer now agree with each other and with the
UA string, all three saying "desktop/mobile Chrome" consistently. Requires
the `USER_AGENT_METADATA` WebView feature (recent WebView builds); on
older ones it's skipped and only the JS-side override applies.

Also turned on `WebView.setWebContentsDebuggingEnabled(true)`, so if
something still misbehaves you (or I) can plug the phone into a PC, open
`chrome://inspect` in desktop Chrome, and see the exact page console
errors instead of guessing — much faster to diagnose than screenshots
alone.

Also added a one-time **on-screen diagnostic** (no PC needed): on launch,
the app now shows a toast (and a matching Logcat line tagged `AlphaDiag`)
with the actual WebView package/version running the app and whether it
supports `USER_AGENT_METADATA` / `DOCUMENT_START_SCRIPT`. If
`USER_AGENT_METADATA` says NO, the Client-Hints network fix above silently
did nothing on that phone — which is the single most useful thing to know
before chasing this further.

## TEMPORARY: in-app Debug button (no PC needed)

Added a bug-icon button in the header (leftmost of the three icons). Tap
it for a scrollable panel with the diagnostics above **plus every
console.log/warn/error the current page has produced** (including
uncaught JS exceptions — Chromium logs those to console automatically),
captured via `onConsoleMessage` in the `WebChromeClient`. A **Copy** button
puts the whole thing on the clipboard so it can be pasted straight into a
message — no USB/PC debugging needed at all.

**Remove the debug button** once calling is confirmed working — it's
testing-only and shouldn't ship in a real release:
1. `activity_main.xml` — delete the `btnDebug` `<ImageButton>` block.
2. `MainActivity.kt` — delete: the `btnDebug` field + its `findViewById`
   line, `setupDebugButton()`'s call in `onCreate`, the `consoleLog`
   field, the `onConsoleMessage` override in `WebChromeClient`, and the
   `setupDebugButton()` function itself (everything between it and
   `buildDiagnosticsText()`/`showWebViewDiagnostics()`, which can stay or
   go too).
3. Delete `drawable/ic_debug.xml` and the `debug_button` string.

## Removed: the "make and manage phone calls" permission

`CALL_PHONE`/`READ_PHONE_STATE` were added earlier as a "might help with
calling" guess. They don't — WhatsApp/Telegram/Discord voice & video calls
run entirely over WebRTC (mic/camera), never the phone's native dialer.
All that permission did was show Android's system "Allow ALPHA INJECTIOR
to make and manage phone calls?" dialog, which has nothing to do with the
in-app call feature and was just confusing. Removed both from the manifest
and the launch permission list.

## Removed: drag/pull-to-refresh

The `SwipeRefreshLayout` (swipe-down-to-reload gesture) was firing when
dragging inside WhatsApp/Telegram/Discord's own chat lists, interrupting
normal scrolling. It's been removed — the WebView is now a plain container
with no gesture on top of it. Use the refresh icon button in the header
for a manual reload; it still works exactly the same as before.

## Updating without losing your WhatsApp login every time

Reinstalling only wipes the WhatsApp/Telegram/Discord login (and every
other stored app setting) when Android treats it as a **fresh install**
rather than an **update**. Two things need to both hold for an APK to
install as an update (keeping all data) instead of wiping everything:

1. **Never uninstall the old version first.** Just tap "Install" on the
   new APK directly over the existing app — same package name
   (`com.quickapps.launcher`) + same signing key = Android merges it in
   as an update automatically. Uninstalling (or letting the installer
   uninstall-then-reinstall) is what wipes WebView's storage and forces
   the QR-login again.
2. **The new build's `versionCode` must be higher than what's currently
   installed**, or Android/the installer may refuse the update (or some
   installers fall back to uninstall+reinstall instead of erroring out).
   Bumped it to `4` in this build (was `3`) — every future build I send
   will keep incrementing it for the same reason.

As long as both of those hold and you're always installing (not
reinstalling-from-scratch) on the same phone, WhatsApp/Telegram/Discord
should all stay logged in across updates from here on.

## Building without a PC — GitHub Actions

A `.github/workflows/build.yml` is included. Pushing this project to a
GitHub repo builds a debug APK in GitHub's cloud (no PC/Android Studio
needed) — download the result from the Actions tab. Steps, doable entirely
from a phone browser:

1. github.com → sign in (free account) → **New repository** (any name).
2. In the empty repo: **Add file → Upload files** → pick the
   `ALPHA_INJECTIOR.zip` I send you → commit to `main`.
3. **Add file → Create new file** → for the filename type
   `.github/workflows/build.yml` → paste in the contents of that file from
   this project → commit to `main` (this commit triggers the first build
   automatically).
4. **Actions** tab → wait for the run to go green (a few minutes).
5. Open the finished run → under **Artifacts**, download
   `ALPHA-INJECTIOR-apk` (a zip containing the `.apk`).
6. Extract that zip on the phone and tap the `.apk` to install (allow
   "install unknown apps" if asked).

For every future update: upload the new zip over the old one (same
filename, commit) — the workflow reruns automatically and a fresh APK
shows up under Actions → Artifacts each time.

## Fix: found via the in-app debug log — the call button really was untappable

The debug log ruled out every server/network theory: modern WebView
(v151), both UA fixes active, and **no JS error at all** when tapping
Call — meaning the tap never reached WhatsApp's own button in the first
place. Root cause, found by reading the injected mic-boost UI's own CSS:
both floating panels it adds to the page —
`#amb-lock-host` (the "waiting for admin approval" card) and `#amb-host`
(the main ALPHA control bubble/panel) — were positioned at
`position:fixed; top:70px; right:12px`. That's the exact same corner
where WhatsApp Web puts its own video-call / voice-call / search icons in
the chat header, and with `z-index:2147483647` (the maximum possible
value) sitting on top, every tap in that whole region was landing on our
own panel instead of WhatsApp's buttons underneath it — silently, no
console error, because as far as the browser's concerned the tap worked
fine, just on the wrong element.

Fixed by moving both panels to `bottom:110px; right:12px` instead —
clear of both the header (where the call buttons are) and the message
input box at the very bottom. The control panel is still fully draggable
by its header if you ever want to move it elsewhere.

## Still blocked: "your browser is out of date" on actually starting a call

With the overlay out of the way, tapping Call now genuinely reaches
WhatsApp's own call code — and it responds with its "please update your
browser" message anyway, even though both the network-level
(`setUserAgentMetadata`) and page-JS-level (`userAgentData`) Client Hints
fixes are active. That means WhatsApp's browser-support check isn't
relying on Client Hints alone.

Added one more layer, addressing the next two most common "is this a real
Chrome browser" signals sites check for: a bare WebView normally has no
`window.chrome` object (real Chrome browsers always expose
`window.chrome.runtime` etc.) and normally reports `navigator.plugins` as
empty (real desktop/mobile Chrome always lists 5 built-in PDF-viewer
plugin entries, by design, for privacy reasons). Both are now shimmed
alongside the Client Hints override.

**Being upfront:** this is now the fourth distinct browser-identity check
addressed, and each one that gets fixed can just reveal a further one
underneath — WhatsApp treats blocking calls from wrapped/embedded browsers
as an anti-abuse measure, and there isn't a public list of everything it
checks. If Call still doesn't work after this build, the debug-log
Console output from the exact moment the "update your browser" message
appears is the next thing to check — but it's worth knowing this could
turn out to be a wall we can't fully spoof our way past.
