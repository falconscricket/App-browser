package com.quickapps.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView
        private set
    private lateinit var progressBar: ProgressBar
    private lateinit var btnDesktop: ImageButton
    private lateinit var btnDebug: ImageButton

    private val PERMISSION_REQUEST_CODE = 101

    // The three destinations. Add/remove here if needed.
    private val whatsappUrl = "https://web.whatsapp.com/"
    private val telegramUrl = "https://web.telegram.org/a/"
    private val discordUrl = "https://discord.com/app"

    // Same key used to obfuscate assets/alpha.dat at build time (see README).
    private val assetKey = "AlphaMicBoost2026SecureKey!"

    // Kiwi-extension style: desktop mode is ON by default across all 3 sites,
    // and the header button lets the user flip the whole WebView back to the
    // normal mobile layout without leaving the app.
    private var desktopMode = true

    // ---- "Attach photo/video" support (WhatsApp/Telegram/Discord file & camera pickers) ----
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraCaptureUri: Uri? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    // TEMPORARY — testing only, backs the in-app Debug button (see README)
    private val consoleLog = StringBuilder()

    // Handle to the currently-installed "run before any page script" override
    // (see installClientHintOverride below), so it can be swapped out when
    // the desktop/mobile toggle flips.
    private var clientHintScriptHandler: ScriptHandler? = null

    // A WhatsApp/Telegram/Discord call's mic/camera request that's waiting on
    // a live Android runtime-permission prompt (see onPermissionRequest fix).
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private lateinit var callPermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Lets this app's WebView be inspected from a PC at chrome://inspect
        // (USB debugging) — useful to see the page's real console errors if
        // something still doesn't work, instead of guessing blind.
        WebView.setWebContentsDebuggingEnabled(true)
        showWebViewDiagnostics()

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        btnDesktop = findViewById(R.id.btnDesktop)
        btnDebug = findViewById(R.id.btnDebug)

        registerFileChooserLauncher()
        registerCallPermissionLauncher()
        setupWebView()
        setupRefreshButton()
        setupDesktopToggle()
        setupDebugButton()
        requestAllPermissions()

        // Exposes AlphaNative.getItem/setItem to the injected JS, backed by
        // SharedPreferences instead of per-origin localStorage — this is what
        // keeps the license/device-ID the same across WhatsApp/Telegram/Discord.
        // Also exposes AlphaNative.nativeFetch so the license-server calls run
        // as a plain Android network request instead of a page fetch() — see
        // "Fix: license approval only registering on Telegram" in the README.
        webView.addJavascriptInterface(AlphaNativeBridge(this), "AlphaNative")

        findViewById<android.widget.Button>(R.id.btnWhatsapp).setOnClickListener {
            webView.loadUrl(whatsappUrl)
        }
        findViewById<android.widget.Button>(R.id.btnTelegram).setOnClickListener {
            webView.loadUrl(telegramUrl)
        }
        findViewById<android.widget.Button>(R.id.btnDiscord).setOnClickListener {
            webView.loadUrl(discordUrl)
        }

        // Default landing page
        webView.loadUrl(whatsappUrl)
    }

    // Real desktop-Chrome and real mobile-Chrome UA strings — neither one
    // contains the "wv" WebView marker that stock WebView adds by default.
    // WhatsApp Web / Discord specifically look for that marker and show a
    // "please update your browser" banner (and sometimes block calling)
    // when they see it, even though the WebView itself is fully current.
    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        // Force the desktop site (not mobile) on all 3 apps by default, and
        // always send a "real browser" UA — never the raw WebView default —
        // so no site ever shows an "update your browser" / unsupported-browser
        // warning or degrades/blocks calling because of it.
        applyUserAgent(settings)

        // settings.userAgentString above only rewrites the classic UA string.
        // Modern Chromium (incl. the WebView engine) separately reports
        // "Android WebView" as a brand through User-Agent *Client Hints*.
        // There are actually two different places this shows up, and both
        // have to be fixed together or the page and the server disagree
        // with each other about what browser this is:
        //  1. The real network-level Sec-CH-UA headers Chromium sends with
        //     every request (including the WebSocket call-signaling
        //     connection) — this is what the *server* sees, and is almost
        //     certainly why the call button was untappable: the server-side
        //     session gets tagged as "unsupported browser" from the very
        //     first request, before any of our page JavaScript runs, so no
        //     JS-only fix can touch it. WebSettingsCompat.setUserAgentMetadata
        //     (below) is the only API that actually rewrites these headers.
        //  2. navigator.userAgentData as read by the *page's* own JS (the
        //     "please update your browser" popup) — installClientHintOverride
        //     handles this part, and is kept as a fallback for older WebView
        //     builds that don't support setUserAgentMetadata.
        applyUserAgentMetadata(desktopMode)
        installClientHintOverride()

        // Render the page at desktop width, scaled to fit the screen,
        // instead of the (broken/mobile) layout WebView guesses at by default
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // Desktop pages are laid out for big screens, so their default text
        // becomes tiny once scaled down to a phone width — bump it back up.
        settings.textZoom = 130

        // Let the user pinch-zoom in/out to adjust further (no on-screen +/- buttons)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Best-effort fallback for devices whose WebView build doesn't
                // support DOCUMENT_START_SCRIPT — runs a little late (page
                // scripts may already be executing) but still helps in practice.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(buildClientHintScript(desktopMode), null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Runs on every page load, on all 3 sites — no separate
                // "connect the extension" step needed, it's always active.
                injectAlphaBundle()
            }

            // If the page fails to load (dropped connection, DNS hiccup, etc.)
            // the top-bar refresh button reloads the last URL cleanly
            // instead of getting stuck.
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            }

            // TEMPORARY — testing only: captures the page's own
            // console.log/warn/error output (including uncaught JS
            // exceptions, which Chromium auto-logs as console errors) so it
            // can be reviewed from the in-app Debug button without a PC.
            override fun onConsoleMessage(cm: android.webkit.ConsoleMessage): Boolean {
                val level = cm.messageLevel().name
                consoleLog.append("[$level] ${cm.message()} (${cm.sourceId()}:${cm.lineNumber()})\n")
                if (consoleLog.length > 20000) consoleLog.delete(0, consoleLog.length - 20000)
                return true
            }

            // Mic + camera capture requests coming from the page (WhatsApp/
            // Telegram/Discord asking for permission during a voice/video
            // call). Bug fix: this used to call request.grant(...)
            // unconditionally — but WebView's grant() only actually works if
            // the *Android* RECORD_AUDIO/CAMERA permission was already
            // approved by the user. If that runtime dialog was ever denied
            // or skipped, grant() silently does nothing, getUserMedia()
            // rejects on the page, and the call button looks like it does
            // nothing when tapped (no visible error). Now it checks the real
            // OS permission first, asks for it live if missing, and only
            // grants the web request once it's actually been approved.
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val neededAndroidPerms = request.resources.mapNotNull {
                        when (it) {
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                            else -> null
                        }
                    }.distinct()

                    val stillMissing = neededAndroidPerms.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }

                    if (stillMissing.isEmpty()) {
                        request.grant(request.resources)
                    } else {
                        pendingWebPermissionRequest = request
                        callPermissionLauncher.launch(stillMissing.toTypedArray())
                    }
                }
            }

            // "Attach photo/video" / "set profile picture" buttons on
            // WhatsApp/Telegram/Discord web open the phone's own gallery or
            // camera through this callback — without it, tapping those
            // buttons does nothing.
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val acceptTypes = params.acceptTypes
                val wantsImageOrVideo = acceptTypes.isEmpty() ||
                    acceptTypes.any { it.contains("image") || it.contains("video") || it == "*/*" }

                val intents = mutableListOf<Intent>()

                // Live camera photo capture, saved via FileProvider so the
                // camera app can write straight to it.
                if (wantsImageOrVideo) {
                    val photoFile = createMediaFile("jpg")
                    val photoUri = FileProvider.getUriForFile(
                        this@MainActivity, "$packageName.fileprovider", photoFile
                    )
                    cameraCaptureUri = photoUri
                    val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    if (captureIntent.resolveActivity(packageManager) != null) {
                        intents.add(captureIntent)
                    }
                }

                // Regular gallery/file picker, filtered to whatever type the
                // page asked for (image, video, or any file).
                val mimeType = when {
                    acceptTypes.any { it.contains("video") } && acceptTypes.none { it.contains("image") } -> "video/*"
                    acceptTypes.any { it.contains("image") } && acceptTypes.none { it.contains("video") } -> "image/*"
                    else -> "*/*"
                }
                val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = mimeType
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }

                val chooser = Intent.createChooser(pickIntent, "Select or capture").apply {
                    if (intents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
                    }
                }

                fileChooserLauncher.launch(chooser)
                return true
            }
        }
    }

    private fun applyUserAgent(settings: android.webkit.WebSettings) {
        settings.userAgentString = if (desktopMode) desktopUserAgent else mobileUserAgent
    }

    /**
     * One-time on-screen diagnostic (no PC/USB needed): shows which WebView
     * build is actually running the app and whether it supports the two
     * APIs the calling-detection fixes depend on. If USER_AGENT_METADATA
     * shows "NO" here, that fix silently did nothing on this phone and
     * explains why "browser doesn't support calling" kept appearing.
     */
    private fun showWebViewDiagnostics() {
        val msg = buildDiagnosticsText()
        android.util.Log.i("AlphaDiag", msg)
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun buildDiagnosticsText(): String {
        val pkg = WebViewCompat.getCurrentWebViewPackage(this)
        val pkgInfo = if (pkg != null) "${pkg.packageName} v${pkg.versionName}" else "unknown"
        val uaMetaOk = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        val docStartOk = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        return "WebView: $pkgInfo\nUA-Metadata fix: ${if (uaMetaOk) "YES" else "NO"}\nDoc-start fix: ${if (docStartOk) "YES" else "NO"}\nCurrent URL: ${if (::webView.isInitialized) webView.url else "n/a"}"
    }

    /**
     * TEMPORARY — testing only. Opens a scrollable panel with the same info
     * showWebViewDiagnostics() toasts, plus every console.log/warn/error the
     * page has produced (including uncaught JS exceptions), with a Copy
     * button — so this can be pasted straight into a message with no PC or
     * USB debugging needed. Safe to delete the whole button + this function
     * + onConsoleMessage above once calling is confirmed working — see
     * README "Remove the debug button".
     */
    private fun setupDebugButton() {
        btnDebug.setOnClickListener {
            val fullText = buildDiagnosticsText() + "\n\n---- Console log ----\n" +
                (if (consoleLog.isEmpty()) "(empty — no page console output captured yet)" else consoleLog.toString())

            val scrollView = android.widget.ScrollView(this)
            val textView = android.widget.TextView(this).apply {
                text = fullText
                setTextIsSelectable(true)
                setPadding(32, 24, 32, 24)
                textSize = 12f
            }
            scrollView.addView(textView)

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Alpha debug log")
                .setView(scrollView)
                .setPositiveButton("Copy") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Alpha debug log", fullText))
                    android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Clear") { _, _ -> consoleLog.setLength(0) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    /**
     * The real fix for the call button being untappable: rewrites the
     * *actual* Sec-CH-UA network headers Chromium sends (not just the JS
     * navigator.userAgentData a page can read) via the androidx.webkit
     * setUserAgentMetadata API — the only public way to do this. Requires
     * the USER_AGENT_METADATA WebView feature; silently does nothing on
     * older WebView builds that lack it (installClientHintOverride's JS
     * shim still covers the page-JS side either way).
     */
    private fun applyUserAgentMetadata(desktop: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val chromeVersion = "126.0.6478.126"
        val brands = listOf(
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Not/A)Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build(),
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Chromium").setMajorVersion("126").setFullVersion(chromeVersion).build(),
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Google Chrome").setMajorVersion("126").setFullVersion(chromeVersion).build()
        )
        val metadata = UserAgentMetadata.Builder()
            .setBrandVersionList(brands)
            .setFullVersion(chromeVersion)
            .setPlatform(if (desktop) "Windows" else "Android")
            .setPlatformVersion(if (desktop) "15.0.0" else "14")
            .setArchitecture(if (desktop) "x86" else "")
            .setModel("")
            .setMobile(!desktop)
            .setBitness(64)
            .setWow64(false)
            .build()
        WebSettingsCompat.setUserAgentMetadata(webView.settings, metadata)
    }

    /**
     * Registers (or re-registers, after a desktop/mobile toggle) a script
     * that runs before *any* page JavaScript, on every future page load,
     * overriding navigator.userAgentData so it reports as a real desktop or
     * mobile Chrome instead of "Android WebView". Requires the
     * DOCUMENT_START_SCRIPT WebView feature (falls back to a same-content
     * evaluateJavascript() call in onPageStarted when unavailable — see
     * above — which is slightly less reliable but still helps).
     */
    private fun installClientHintOverride() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        clientHintScriptHandler?.remove()
        clientHintScriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            buildClientHintScript(desktopMode),
            setOf("*")
        )
    }

    /** navigator.userAgentData shim — brands list never contains "Android WebView". */
    private fun buildClientHintScript(desktop: Boolean): String {
        val platform = if (desktop) "Windows" else "Android"
        val mobile = if (desktop) "false" else "true"
        val chromeVersion = "126.0.6478.126"
        return """
            (function() {
              try {
                var brands = [
                  { brand: "Not/A)Brand", version: "24" },
                  { brand: "Chromium", version: "126" },
                  { brand: "Google Chrome", version: "126" }
                ];
                var uaData = {
                  brands: brands,
                  mobile: $mobile,
                  platform: "$platform",
                  toJSON: function () { return { brands: brands, mobile: $mobile, platform: "$platform" }; },
                  getHighEntropyValues: function () {
                    return Promise.resolve({
                      brands: brands,
                      mobile: $mobile,
                      platform: "$platform",
                      platformVersion: "$platform" === "Windows" ? "15.0.0" : "14.0.0",
                      architecture: "x86",
                      bitness: "64",
                      model: "",
                      uaFullVersion: "$chromeVersion",
                      fullVersionList: brands.map(function (b) {
                        var v = (b.brand === "Google Chrome" || b.brand === "Chromium") ? "$chromeVersion" : b.version;
                        return { brand: b.brand, version: v };
                      })
                    });
                  }
                };
                Object.defineProperty(navigator, 'userAgentData', {
                  get: function () { return uaData; },
                  configurable: true
                });
              } catch (e) {}

              // Real desktop/mobile Chrome exposes a window.chrome object
              // (window.chrome.runtime etc.) that a bare WebView normally
              // does not — some sites use its absence as a "not a real
              // Chrome" signal. Add a minimal stand-in.
              try {
                if (!window.chrome || !window.chrome.runtime) {
                  window.chrome = window.chrome || {};
                  window.chrome.runtime = window.chrome.runtime || {};
                  window.chrome.app = window.chrome.app || { isInstalled: false };
                  window.chrome.csi = window.chrome.csi || function () { return {}; };
                  window.chrome.loadTimes = window.chrome.loadTimes || function () { return {}; };
                }
              } catch (e) {}

              // Real Chrome always lists these 5 built-in PDF-viewer entries
              // in navigator.plugins (a well-known, deliberately identical
              // list Chrome ships for privacy reasons) — a bare WebView
              // normally reports zero plugins, which some sites also read
              // as a "not a real browser" signal.
              try {
                if (navigator.plugins && navigator.plugins.length === 0) {
                  var pluginNames = [
                    "PDF Viewer", "Chrome PDF Viewer", "Chromium PDF Viewer",
                    "Microsoft Edge PDF Viewer", "WebKit built-in PDF"
                  ];
                  var fakePlugins = pluginNames.map(function (n) {
                    return { name: n, filename: "internal-pdf-viewer", description: "Portable Document Format", length: 2 };
                  });
                  fakePlugins.item = function (i) { return fakePlugins[i]; };
                  fakePlugins.namedItem = function (n) { return fakePlugins.filter(function (p) { return p.name === n; })[0]; };
                  Object.defineProperty(navigator, 'plugins', {
                    get: function () { return fakePlugins; },
                    configurable: true
                  });
                }
              } catch (e) {}
            })();
        """.trimIndent()
    }

    /**
     * Header button: flips the whole WebView (all 3 sites) between the
     * desktop UA/layout and the normal mobile one — the "switch to desktop
     * site" toggle, same idea as the per-site option in Kiwi Browser, but
     * applied once for WhatsApp/Telegram/Discord together since they all
     * share this one WebView instance.
     */
    private fun setupDesktopToggle() {
        updateDesktopButton()
        btnDesktop.setOnClickListener {
            desktopMode = !desktopMode
            applyUserAgent(webView.settings)
            applyUserAgentMetadata(desktopMode)
            installClientHintOverride()
            updateDesktopButton()
            webView.reload()
        }
    }

    private fun updateDesktopButton() {
        btnDesktop.setBackgroundResource(
            if (desktopMode) R.drawable.bg_btn_desktop_on else R.drawable.bg_btn_desktop_off
        )
        btnDesktop.alpha = if (desktopMode) 1.0f else 0.6f
    }

    /** Visible refresh icon in the header — manual reload (drag/pull-to-refresh removed). */
    private fun setupRefreshButton() {
        val btnRefresh = findViewById<ImageButton>(R.id.btnRefresh)
        btnRefresh.setOnClickListener {
            val spin = RotateAnimation(
                0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            )
            spin.duration = 450
            btnRefresh.startAnimation(spin)
            webView.reload()
        }
    }

    /** Injects the Alpha mic-boost/voice-changer engine into the currently loaded page. */
    private fun injectAlphaBundle() {
        try {
            val js = readObfuscatedAsset("alpha.dat")
            webView.evaluateJavascript(js, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Reads and XOR-decodes an asset so the engine source isn't stored as plain readable text in the APK. */
    private fun readObfuscatedAsset(name: String): String {
        val bytes = assets.open(name).use { it.readBytes() }
        val keyBytes = assetKey.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            out[i] = (bytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    /** Registers the launcher that handles the result of the camera/gallery chooser above. */
    private fun registerFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            val results: Array<Uri>? = when {
                result.resultCode != RESULT_OK -> null
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                cameraCaptureUri != null -> arrayOf(cameraCaptureUri!!)
                else -> null
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            cameraCaptureUri = null
        }
    }

    /**
     * Registers the launcher used by the onPermissionRequest fix above: when
     * WhatsApp/Telegram/Discord asks for mic/camera mid-call and the Android
     * permission isn't granted yet, this shows the real system prompt on the
     * spot and only then tells the page's PermissionRequest whether it's
     * allowed — instead of silently failing like it used to.
     */
    private fun registerCallPermissionLauncher() {
        callPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val request = pendingWebPermissionRequest
            pendingWebPermissionRequest = null
            if (request == null) return@registerForActivityResult

            val allGranted = results.values.all { it }
            if (allGranted) {
                request.grant(request.resources)
            } else {
                request.deny()
            }
        }
    }

    private fun createMediaFile(extension: String): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: cacheDir
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "ALPHA_${timestamp}.$extension")
    }

    /** Requests every runtime permission the app declares, in one go, on first launch. */
    private fun requestAllPermissions() {
        val candidates = mutableListOf(
            // Calling (voice/video)
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            // Contacts (invite / sync flows)
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ granular photo/video/audio media permissions
            candidates.add(Manifest.permission.READ_MEDIA_IMAGES)
            candidates.add(Manifest.permission.READ_MEDIA_VIDEO)
            candidates.add(Manifest.permission.READ_MEDIA_AUDIO)
            candidates.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            candidates.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            candidates.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            candidates.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val needed = candidates.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

/**
 * JS-callable bridge backed by a single SharedPreferences file that is
 * shared by the whole app — unlike WebView localStorage, which is scoped
 * per-origin (so web.whatsapp.com, web.telegram.org and discord.com each
 * used to see a different store, and therefore a different device ID).
 */
class AlphaNativeBridge(private val activity: MainActivity) {
    private val prefs = activity.getSharedPreferences("alpha_native_store", 0)

    @JavascriptInterface
    fun getItem(key: String): String? = prefs.getString(key, null)

    @JavascriptInterface
    fun setItem(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /**
     * Runs the license-server activate/check call as a plain Android HTTP
     * request instead of a page fetch(). This is the actual fix for
     * "approval only shows as connected on Telegram": the license calls
     * were made with the page's own fetch(), which WhatsApp Web and
     * Discord's Content-Security-Policy silently block for any host that
     * isn't theirs (Telegram Web's CSP happens to be loose enough to let it
     * through, which is why only that one "worked"). A request made from
     * Kotlin isn't a page fetch at all, so no site's CSP can see or block
     * it, and the same device ID/approval now really is shared by all 3.
     */
    @JavascriptInterface
    fun nativeFetch(requestId: String, url: String, method: String, bodyJson: String?) {
        Thread {
            var code = -1
            var body = ""
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doInput = true
                if (bodyJson != null && method.equals("POST", ignoreCase = true)) {
                    conn.doOutput = true
                    val os: OutputStream = conn.outputStream
                    os.write(bodyJson.toByteArray(Charsets.UTF_8))
                    os.flush()
                    os.close()
                }
                code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            } catch (e: Exception) {
                code = -1
                body = ""
            }
            deliverFetchResult(requestId, code, body)
        }.start()
    }

    private fun deliverFetchResult(requestId: String, code: Int, body: String) {
        activity.runOnUiThread {
            val js = "window.__alphaNativeFetchResult && window.__alphaNativeFetchResult(" +
                JSONObject.quote(requestId) + "," + code + "," + JSONObject.quote(body) + ");"
            activity.webView.evaluateJavascript(js, null)
        }
    }
}
