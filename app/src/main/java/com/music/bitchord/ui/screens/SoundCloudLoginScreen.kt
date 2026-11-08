package com.music.bitchord.ui.screens

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri

private val soundCloudCookieOrigins = listOf(
    "https://soundcloud.com", "https://www.soundcloud.com", "https://m.soundcloud.com",
    "https://secure.soundcloud.com", "https://api-v2.soundcloud.com",
)
private const val SOUND_CLOUD_BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
private val captureDelaysMs = listOf(0L, 300L, 800L, 1_600L, 3_000L, 5_000L)

private const val READ_CLIENT_ID = """
(function(){
 try{var c=window.__sc_hydration;if(c){var m=JSON.stringify(c).match(/client_?[Ii]d['\"\s:]+['\"]([A-Za-z0-9]{16,})['\"]/);if(m)return m[1];}}catch(e){}
 try{if(window.SC&&SC.__serverProperties&&SC.__serverProperties.client_id)return SC.__serverProperties.client_id;}catch(e){}
 try{if(window.SC&&SC._config&&SC._config.client_id)return SC._config.client_id;}catch(e){}
 try{if(window.scHydrationPackages){var m=JSON.stringify(window.scHydrationPackages).match(/client_?[Ii]d['\"\s:]+['\"]([A-Za-z0-9]{16,})['\"]/);if(m)return m[1];}}catch(e){}
 try{var s=document.getElementsByTagName('script');for(var i=s.length-1;i>=0;i--){var m=(s[i].textContent||'').match(/client_?[Ii]d[\"']?\s*[:=]\s*[\"']([A-Za-z0-9]{16,})[\"']/i);if(m)return m[1];}}catch(e){}
 return '';
})()
"""

private const val READ_AUTH_STORAGE = """
(function(){var r=[];function d(s){try{for(var i=0;i<s.length;i++){var k=s.key(i),v=s.getItem(k);if(k&&v)r.push(k+'='+v)}}catch(e){}}
 d(localStorage);d(sessionStorage);try{if(window.SC&&SC.connector&&SC.connector.oauth_token)r.push('oauth_token='+SC.connector.oauth_token)}catch(e){}
 try{if(typeof window.__sc_token==='string')r.push('access_token='+window.__sc_token)}catch(e){}return r.join('\\n')})()
"""

private fun isAllowedSoundCloudPage(url: String?): Boolean {
    val uri = url?.toUri() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme == "https" && (host == "soundcloud.com" || host.endsWith(".soundcloud.com") ||
        host == "sndcdn.com" || host.endsWith(".sndcdn.com"))
}

private fun decodeJavascriptString(result: String?): String = result.orEmpty().trim()
    .takeIf { it != "null" }
    ?.removeSurrounding("\"")
    ?.replace("\\\"", "\"")
    ?.replace("\\\\", "\\")
    ?.replace("\\n", "\n")
    ?.replace("\\u003d", "=")
    ?.replace("\\u003D", "=")
    .orEmpty()

private fun extractToken(input: String): String? {
    val tokenNames = "oauth[_-]?token|access[_-]?token|soundcloud[_-]?oauth[_-]?token"
    return Regex("(?i)(?:$tokenNames)[\\\"'\\s:=]+[\\\"']?([A-Za-z0-9._~+/=%-]{16,})")
        .find(input)?.groupValues?.getOrNull(1)?.trimEnd('"', '\'', ';', ',', '\\')
        ?.takeIf { it.length >= 16 && it.none(Char::isWhitespace) }
}

private fun extractTokenFromUrl(url: String?): String? {
    if (!isAllowedSoundCloudPage(url)) return null
    val uri = url!!.toUri()
    return uri.getQueryParameter("oauth_token") ?: uri.getQueryParameter("access_token")
        ?: uri.fragment?.let(::extractToken)
}

/** Uses SoundCloud's own login page and reads only its OAuth/session values; password input is never accessed. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SoundCloudLoginScreen(
    onConnected: (oauthToken: String, clientId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var loaded by remember { mutableStateOf(false) }
    var captureAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var captureMessage by remember { mutableStateOf<String?>(null) }
    var activePoll by remember { mutableStateOf<Runnable?>(null) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                var webViewRef: WebView? = null
                var sent = false
                var latestToken: String? = null
                var latestClientId = ""
                var currentUrl: String? = null
                lateinit var capturePoll: Runnable
                fun finishCapture() {
                    val token = latestToken ?: return
                    sent = true
                    handler.removeCallbacks(capturePoll)
                    onConnected(token, latestClientId)
                }
                fun capture(view: WebView, pageUrl: String?) {
                    if (sent || !isAllowedSoundCloudPage(pageUrl)) return
                    CookieManager.getInstance().flush()
                    val cookieInputs = soundCloudCookieOrigins.mapNotNull(CookieManager.getInstance()::getCookie)
                    latestToken = extractTokenFromUrl(pageUrl)
                        ?: cookieInputs.firstNotNullOfOrNull(::extractToken)
                        ?: latestToken
                    view.evaluateJavascript(READ_CLIENT_ID) { rawClient ->
                        val client = decodeJavascriptString(rawClient).trim().trim('"')
                        if (client.length >= 16) latestClientId = client
                        view.evaluateJavascript(READ_AUTH_STORAGE) { rawStorage ->
                            val storage = decodeJavascriptString(rawStorage)
                            latestToken = latestToken ?: extractToken(storage)
                            if (!latestToken.isNullOrBlank()) finishCapture()
                        }
                    }
                }
                capturePoll = Runnable {
                    webViewRef?.let { capture(it, it.url ?: currentUrl) }
                    if (!sent) handler.postDelayed(capturePoll, 1_200L)
                }
                activePoll = capturePoll
                WebView(context).apply {
                    webViewRef = this
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // Match MetroFuse: SoundCloud blocks or degrades the stock
                    // Android WebView UA on some login/consent redirect paths.
                    settings.userAgentString = SOUND_CLOUD_BROWSER_USER_AGENT
                    if (Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = false
                    else if (Build.VERSION.SDK_INT >= 29) {
                        @Suppress("DEPRECATION")
                        settings.forceDark = WebSettings.FORCE_DARK_OFF
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        // MetroFuse allows the identity-provider redirects used
                        // by SoundCloud sign-in. We only inspect storage/cookies
                        // on SoundCloud-owned origins in capture().
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            currentUrl = url
                            capture(view, url)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            currentUrl = url
                            loaded = true
                            captureDelaysMs.forEach { delay -> handler.postDelayed({ capture(view, url) }, delay) }
                            handler.removeCallbacks(capturePoll)
                            handler.postDelayed(capturePoll, 300L)
                        }
                    }
                    captureAction = {
                        captureMessage = null
                        capture(this, currentUrl)
                        handler.postDelayed({
                            if (!sent && !latestToken.isNullOrBlank()) finishCapture()
                            else if (!sent) captureMessage = "Session not detected yet. Finish sign-in, then try again."
                        }, 1_200L)
                    }
                    loadUrl("https://soundcloud.com/signin")
                }
            },
            onRelease = {
                it.stopLoading()
                it.destroy()
                activePoll?.let(handler::removeCallbacks)
                activePoll = null
                captureAction = null
            },
        )
        if (!loaded) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        else androidx.compose.foundation.layout.Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            captureMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { captureAction?.invoke() }) { Text("I’m signed in — continue") }
        }
    }
}
