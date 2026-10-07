package com.example.f95updater

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.delay

/** Details of a download requested from inside the in-app browser. */
data class BrowserDownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val pageTitle: String?,
    val referrer: String?,
    val contentLength: Long,
)

private class PopupPrompt(val child: WebView, val url: String)

private class PopupPage(val webView: WebView, initialUrl: String) {
    var title by mutableStateOf("")
    var url by mutableStateOf(initialUrl)
}

private data class PopupHostDecision(val host: String, val allow: Boolean)

internal fun browserCompatibleUserAgent(defaultUserAgent: String): String =
    defaultUserAgent.replace(Regex(""";\s*wv\)""", RegexOption.IGNORE_CASE), ")")

private val BLOCKED_BROWSER_RESOURCE_HOSTS = setOf(
    "alaphoid.com",
    "coosync.com",
    "improperscrubbedvendor.com",
    "portalfluently.com",
    "profitablecpmratenetwork.com",
    "redgarto.com",
    "vertigovitalitywieldable.com",
)

private val KIMOCHI_TRUSTED_RESOURCE_HOSTS = setOf(
    "kimochi.info",
    "blogger.googleusercontent.com",
    "cdn.jsdelivr.net",
    "cdn.pncloudfl.com",
    "cdn.tailwindcss.com",
    "cdnjs.cloudflare.com",
    "fonts.googleapis.com",
    "fonts.gstatic.com",
)

private fun hostMatches(host: String, suffixes: Set<String>): Boolean =
    suffixes.any { suffix -> host == suffix || host.endsWith(".$suffix") }

internal fun isBrowserSubresourceBlocked(
    url: String,
    isForMainFrame: Boolean,
    referrer: String? = null,
): Boolean {
    if (isForMainFrame) return false
    val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    if (host.isBlank()) return false
    if (hostMatches(host, BLOCKED_BROWSER_RESOURCE_HOSTS)) return true

    val referrerHost = runCatching { URI(referrer.orEmpty()).host.orEmpty().lowercase() }.getOrDefault("")
    return hostMatches(referrerHost, setOf("kimochi.info")) &&
        !hostMatches(host, KIMOCHI_TRUSTED_RESOURCE_HOSTS)
}

private fun blockedBrowserResponse(
    request: WebResourceRequest,
    reportedHosts: MutableSet<String>,
): WebResourceResponse? {
    val referrer = request.requestHeaders.entries
        .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
        ?.value
    if (!isBrowserSubresourceBlocked(request.url.toString(), request.isForMainFrame, referrer)) return null
    val host = request.url.host.orEmpty().lowercase()
    if (reportedHosts.add(host)) AppLog.i("Browser", "Blocked ad-network resources from $host")
    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
}

@Composable
private fun BrowserQuickNavigation(
    onOpenCatalog: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TextButton(onClick = onOpenCatalog, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.MenuBook, contentDescription = null)
            Text("Catalog", modifier = Modifier.padding(start = 6.dp))
        }
        TextButton(onClick = onOpenDownloads, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Download, contentDescription = null)
            Text("Downloads", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.applyBrowserSettings() {
    // Fill the host container. Without explicit MATCH_PARENT params the WebView defaults to
    // WRAP_CONTENT inside the Compose AndroidView, so Chromium derives the CSS viewport height from
    // content height. Normal document-flow pages still work, but pages whose top-level layout is
    // position:absolute/inset:0 or 100vh (e.g. pixeldrain) collapse to zero height and render blank.
    layoutParams = android.view.ViewGroup.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
    )
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    // Some file hosts deliberately return false "file not found" pages when Android's WebView
    // marker is present, while accepting the otherwise-identical Chrome user agent.
    settings.userAgentString = browserCompatibleUserAgent(settings.userAgentString)
    // Allow user-initiated popups (routed through onCreateWindow into a separate, closable layer)
    // but block automatic script-driven ones.
    settings.setSupportMultipleWindows(true)
    settings.javaScriptCanOpenWindowsAutomatically = false
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
}

/**
 * Tears a WebView down without crashing the (possibly shared) renderer: stop loading, blank it,
 * detach it from its parent, then destroy. Calling destroy() while still attached can crash the
 * renderer process and take other WebViews (e.g. the main page) down with it.
 */
private fun WebView.safeDestroy() {
    runCatching {
        stopLoading()
        webViewClient = WebViewClient()
        webChromeClient = null
        setDownloadListener(null)
        loadUrl("about:blank")
    }
    runCatching { (parent as? ViewGroup)?.removeView(this) }
    runCatching { removeAllViews() }
    runCatching { destroy() }
}

/** True when [host] is covered by [allowlist] (exact host or a `.suffix` subdomain match). An empty
 *  allowlist matches nothing, so all popups are blocked. */
internal fun isPopupHostAllowed(host: String, allowlist: List<String>): Boolean {
    val h = normalizeBrowserHost(host) ?: return false
    return allowlist.any { entry ->
        val e = normalizeBrowserHost(entry).orEmpty()
        e.isNotEmpty() && (h == e || h.endsWith(".$e"))
    }
}

internal enum class PopupHostPolicy {
    Allowed,
    Blocked,
    Ask,
}

internal fun popupHostPolicy(
    host: String,
    allowlist: Collection<String>,
    blocklist: Collection<String>,
): PopupHostPolicy = when {
    isPopupHostAllowed(host, blocklist.toList()) -> PopupHostPolicy.Blocked
    isPopupHostAllowed(host, allowlist.toList()) -> PopupHostPolicy.Allowed
    else -> PopupHostPolicy.Ask
}

/**
 * Full-screen in-app browser used when the user enables "Open links inside AGM". Renders a
 * [WebView] that keeps its own cookies/session (so authenticated F95 downloads work) and forwards
 * any file download to [onStartDownload]; the actual [android.app.DownloadManager] job and its
 * completion handling live at a higher, app-UI scope so downloads survive this dialog closing.
 *
 * Popups: automatic (script-driven, no user gesture) popups are blocked and reported via
 * [onPopupBlocked]. A **user-initiated** popup whose host is not in [popupAllowlist] shows a
 * non-blocking dropdown prompt asking whether to open it; hosts in [popupAllowlist] open directly.
 * An opened popup appears as a **separate closable layer** on top of the current page, so closing
 * it returns to the underlying page.
 */
@Composable
fun InAppBrowser(
    url: String,
    popupAllowlist: List<String>,
    popupBlocklist: List<String>,
    onStartDownload: (BrowserDownloadRequest) -> String?,
    onAllowPopupHost: (String) -> Unit,
    onBlockPopupHost: (String) -> Unit,
    onOpenExternally: (String) -> Unit,
    onPopupBlocked: (String, Int) -> Unit,
    onOpenCatalog: () -> Unit,
    onOpenDownloads: () -> Unit,
    onUrlChanged: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf(url) }
    var currentUrl by remember { mutableStateOf(url) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    // Bumped to recreate the main WebView if its renderer process dies.
    var mainEpoch by remember { mutableStateOf(0) }

    val popupStack = remember { mutableStateListOf<PopupPage>() }
    var blockedCount by remember { mutableStateOf(0) }
    var downloadNotice by remember { mutableStateOf<String?>(null) }
    var pendingPopupChild by remember { mutableStateOf<WebView?>(null) }
    var pendingPopupUserGesture by remember { mutableStateOf(false) }
    var popupPrompt by remember { mutableStateOf<PopupPrompt?>(null) }
    var popupPromptSeconds by remember { mutableStateOf(5) }
    var popupPromptTimerPaused by remember { mutableStateOf(false) }
    var popupHostDecision by remember { mutableStateOf<PopupHostDecision?>(null) }

    fun startDownload(request: BrowserDownloadRequest) {
        onStartDownload(request)?.let { fileName ->
            downloadNotice = "Download started: $fileName"
        }
    }

    LaunchedEffect(downloadNotice) {
        if (downloadNotice != null) {
            delay(3500)
            downloadNotice = null
        }
    }

    fun reportBlocked(target: String) {
        blockedCount += 1
        onPopupBlocked(target, blockedCount)
    }

    fun syncNavState() {
        webViewRef?.let {
            canGoBack = it.canGoBack()
            canGoForward = it.canGoForward()
        }
    }

    fun closePopup(page: PopupPage? = popupStack.lastOrNull()) {
        if (page != null) popupStack.remove(page)
    }

    fun decidePopup(child: WebView, target: String) {
        val host = normalizeBrowserHost(Uri.parse(target).host.orEmpty()) ?: return
        if (child !== pendingPopupChild) return
        when (popupHostPolicy(host, popupAllowlist, popupBlocklist)) {
            PopupHostPolicy.Blocked -> {
                pendingPopupChild = null
                reportBlocked(target)
                child.post { child.safeDestroy() }
            }
            PopupHostPolicy.Allowed -> {
                pendingPopupChild = null
                popupStack += PopupPage(child, target)
            }
            PopupHostPolicy.Ask -> {
                if (pendingPopupUserGesture) {
                    pendingPopupChild = null
                    popupPromptSeconds = 5
                    popupPromptTimerPaused = false
                    popupPrompt = PopupPrompt(child, target)
                } else {
                    pendingPopupChild = null
                    reportBlocked(target)
                    child.post { child.safeDestroy() }
                }
            }
        }
    }

    fun acceptPopupPrompt() {
        val prompt = popupPrompt ?: return
        popupPrompt = null
        popupHostDecision = null
        popupStack += PopupPage(prompt.child, prompt.url)
    }

    fun declinePopupPrompt() {
        val prompt = popupPrompt ?: return
        popupPrompt = null
        popupHostDecision = null
        reportBlocked(prompt.url)
        prompt.child.post { prompt.child.safeDestroy() }
    }

    lateinit var requestPopup: (WebView, Boolean, Message) -> Boolean
    lateinit var buildPopupChild: (Context) -> WebView
    buildPopupChild = { ctx ->
        val child = WebView(ctx)
        child.applyBrowserSettings()
        child.webViewClient = object : WebViewClient() {
            private val reportedBlockedHosts = Collections.synchronizedSet(mutableSetOf<String>())

            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                blockedBrowserResponse(request, reportedBlockedHosts)
                    ?: super.shouldInterceptRequest(v, request)

            override fun onPageStarted(v: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                if (u == null) return
                popupStack.firstOrNull { it.webView === v }?.let {
                    it.url = u
                    return
                }
                decidePopup(v, u)
            }

            override fun onPageFinished(v: WebView, u: String?) {
                if (u != null) popupStack.firstOrNull { it.webView === v }?.url = u
            }

            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail?): Boolean {
                if (v === pendingPopupChild) pendingPopupChild = null
                popupStack.firstOrNull { it.webView === v }?.let(::closePopup)
                return true
            }
        }
        child.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(v: WebView, title: String?) {
                if (!title.isNullOrBlank()) {
                    popupStack.firstOrNull { it.webView === v }?.title = title
                }
            }

            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, msg: Message): Boolean =
                requestPopup(v, g, msg)

            override fun onCloseWindow(window: WebView) {
                popupStack.firstOrNull { it.webView === window }?.let(::closePopup)
            }
        }
        child.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, contentLength ->
            val page = popupStack.firstOrNull { it.webView === child }
            startDownload(
                BrowserDownloadRequest(
                    dlUrl,
                    userAgent,
                    contentDisposition,
                    mimeType,
                    page?.title?.ifBlank { null },
                    page?.url,
                    contentLength,
                ),
            )
        }
        child
    }
    requestPopup = { view, isUserGesture, resultMsg ->
        if (pendingPopupChild != null || popupPrompt != null || popupStack.size >= 8) {
            AppLog.w(
                "Browser",
                "Popup rejected before target resolution source=${view.url} " +
                    "gesture=$isUserGesture depth=${popupStack.size}",
            )
            false
        } else {
            val child = buildPopupChild(view.context)
            pendingPopupChild = child
            pendingPopupUserGesture = isUserGesture
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = child
            resultMsg.sendToTarget()
            AppLog.i(
                "Browser",
                "Popup requested source=${view.url} gesture=$isUserGesture depth=${popupStack.size + 1}",
            )
            true
        }
    }

    val mainChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) pageTitle = title
        }

        override fun onConsoleMessage(cm: android.webkit.ConsoleMessage): Boolean {
            if (cm.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                AppLog.w("Browser", "console ${cm.message()} @${cm.sourceId()}:${cm.lineNumber()}")
            }
            return super.onConsoleMessage(cm)
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean = requestPopup(view, isUserGesture, resultMsg)
    }

    LaunchedEffect(popupPrompt, popupPromptTimerPaused) {
        if (popupPrompt == null || popupPromptTimerPaused) return@LaunchedEffect
        while (popupPrompt != null && !popupPromptTimerPaused && popupPromptSeconds > 0) {
            delay(1_000)
            if (popupPrompt != null && !popupPromptTimerPaused) popupPromptSeconds--
        }
        if (popupPrompt != null && !popupPromptTimerPaused && popupPromptSeconds == 0) {
            declinePopupPrompt()
        }
    }

    // Popup children are held (not attached to an AndroidView) until promoted; make sure a pending
    // or prompted one is torn down if the browser closes before the user decides.
    val pendingChildRef by rememberUpdatedState(pendingPopupChild)
    val promptChildRef by rememberUpdatedState(popupPrompt?.child)
    DisposableEffect(Unit) {
        onDispose {
            pendingChildRef?.safeDestroy()
            promptChildRef?.safeDestroy()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Handle Back ourselves so it closes a popup / navigates history before dismissing.
            dismissOnBackPress = false,
        ),
    ) {
        BackHandler(enabled = true) {
            val popup = popupStack.lastOrNull()
            val main = webViewRef
            when {
                popupPrompt != null -> declinePopupPrompt()
                popup?.webView?.canGoBack() == true -> popup.webView.goBack()
                popup != null -> closePopup(popup)
                main != null && main.canGoBack() -> main.goBack()
                else -> onDismiss()
            }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                // --- Main page ---
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { webViewRef?.takeIf { it.canGoBack() }?.goBack() }, enabled = canGoBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        IconButton(onClick = { webViewRef?.takeIf { it.canGoForward() }?.goForward() }, enabled = canGoForward) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                pageTitle,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                currentUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onOpenExternally(currentUrl) }) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in default browser")
                        }
                        if (blockedCount > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 2.dp),
                            ) {
                                Icon(
                                    Icons.Default.Block,
                                    contentDescription = "Popups blocked",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    blockedCount.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close browser")
                        }
                    }
                    if (loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        HorizontalDivider()
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        key(mainEpoch) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        applyBrowserSettings()
                                        webViewClient = object : WebViewClient() {
                                            private val reportedBlockedHosts =
                                                Collections.synchronizedSet(mutableSetOf<String>())

                                            override fun shouldInterceptRequest(
                                                view: WebView,
                                                request: WebResourceRequest,
                                            ): WebResourceResponse? =
                                                blockedBrowserResponse(request, reportedBlockedHosts)
                                                    ?: super.shouldInterceptRequest(view, request)

                                            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                                                loading = true
                                                if (pageUrl != null) {
                                                    currentUrl = pageUrl
                                                    onUrlChanged(pageUrl)
                                                }
                                                syncNavState()
                                            }

                                            override fun onPageFinished(view: WebView, pageUrl: String?) {
                                                loading = false
                                                if (pageUrl != null) {
                                                    currentUrl = pageUrl
                                                    onUrlChanged(pageUrl)
                                                }
                                                syncNavState()
                                            }

                                            override fun doUpdateVisitedHistory(view: WebView, pageUrl: String?, isReload: Boolean) {
                                                syncNavState()
                                            }

                                            override fun onReceivedError(
                                                view: WebView,
                                                request: android.webkit.WebResourceRequest?,
                                                error: android.webkit.WebResourceError?,
                                            ) {
                                                if (request?.isForMainFrame == true) {
                                                    AppLog.w("Browser", "loadError ${error?.errorCode} ${error?.description} url=${request.url}")
                                                }
                                            }

                                            override fun onReceivedHttpError(
                                                view: WebView,
                                                request: android.webkit.WebResourceRequest?,
                                                errorResponse: android.webkit.WebResourceResponse?,
                                            ) {
                                                if (request?.isForMainFrame == true) {
                                                    AppLog.w("Browser", "httpError ${errorResponse?.statusCode} url=${request.url}")
                                                }
                                            }

                                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                                                // Keep the app alive if the page's renderer dies; drop any
                                                // popup and rebuild the page instead of crashing/closing.
                                                if (webViewRef === view) webViewRef = null
                                                popupStack.clear()
                                                mainEpoch++
                                                return true
                                            }
                                        }
                                        webChromeClient = mainChromeClient
                                        setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, contentLength ->
                                            startDownload(
                                                BrowserDownloadRequest(
                                                    dlUrl,
                                                    userAgent,
                                                    contentDisposition,
                                                    mimeType,
                                                    pageTitle,
                                                    currentUrl,
                                                    contentLength,
                                                ),
                                            )
                                        }
                                        webViewRef = this
                                        loadUrl(currentUrl.ifBlank { url })
                                    }
                                },
                                onRelease = { view ->
                                    if (webViewRef === view) webViewRef = null
                                    view.safeDestroy()
                                },
                            )
                        }
                    }
                    BrowserQuickNavigation(onOpenCatalog, onOpenDownloads)
                }

                popupStack.forEach { popup ->
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        popup.title.ifBlank { "Popup" },
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        popup.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = {
                                    if (popup.url.isNotBlank()) onOpenExternally(popup.url)
                                    closePopup(popup)
                                }) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Open popup in default browser")
                                }
                                IconButton(onClick = { closePopup(popup) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close popup")
                                }
                            }
                            HorizontalDivider()
                            key(popup) {
                                AndroidView(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    factory = { popup.webView },
                                    onRelease = { view -> view.safeDestroy() },
                                )
                            }
                            BrowserQuickNavigation(onOpenCatalog, onOpenDownloads)
                        }
                    }
                }

                popupPrompt?.let { prompt ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(12.dp),
                    ) {
                        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                            Text("Open this pop-up?", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Auto-blocking in $popupPromptSeconds second" +
                                    if (popupPromptSeconds == 1) "" else "s",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                prompt.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { declinePopupPrompt() }) { Text("Block once") }
                                TextButton(onClick = { acceptPopupPrompt() }) { Text("Open once") }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                val host = normalizeBrowserHost(Uri.parse(prompt.url).host.orEmpty()).orEmpty()
                                TextButton(
                                    enabled = host.isNotBlank(),
                                    onClick = {
                                        popupPromptTimerPaused = true
                                        popupHostDecision = PopupHostDecision(host, allow = false)
                                    },
                                ) { Text("Always block") }
                                TextButton(
                                    enabled = host.isNotBlank(),
                                    onClick = {
                                        popupPromptTimerPaused = true
                                        popupHostDecision = PopupHostDecision(host, allow = true)
                                    },
                                ) { Text("Always allow") }
                            }
                        }
                    }
                }

                downloadNotice?.let { notice ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 64.dp),
                    ) {
                        Text(
                            notice,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            popupHostDecision?.let { decision ->
                AlertDialog(
                    onDismissRequest = {
                        popupHostDecision = null
                        popupPromptTimerPaused = false
                    },
                    title = {
                        Text(if (decision.allow) "Always allow this host?" else "Always block this host?")
                    },
                    text = {
                        Text(
                            if (decision.allow) {
                                "${decision.host} and its subdomains will open pop-ups without asking."
                            } else {
                                "${decision.host} and its subdomains will be blocked without a countdown."
                            },
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (decision.allow) {
                                onAllowPopupHost(decision.host)
                                acceptPopupPrompt()
                            } else {
                                onBlockPopupHost(decision.host)
                                declinePopupPrompt()
                            }
                        }) {
                            Text(if (decision.allow) "Allow" else "Block")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            popupHostDecision = null
                            popupPromptTimerPaused = false
                        }) { Text("Cancel") }
                    },
                )
            }
        }
    }
}
