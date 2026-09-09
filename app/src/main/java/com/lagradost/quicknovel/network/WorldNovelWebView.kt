package com.lagradost.quicknovel.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * WebView persistant partagé entre la connexion (LoginActivity) et l'extraction du
 * texte des chapitres (WorldNovelProvider).
 *
 * world-novel.fr ne sert le texte qu'à une session « connectée + reCAPTCHA validé »,
 * stockée en localStorage + cookies du navigateur. Un WebView jetable par chapitre ne
 * partage pas cette session (localStorage propre à chaque instance) → le site renvoyait
 * la page « aperçu » au lieu du texte. En réutilisant la MÊME instance, la session est
 * conservée d'un chapitre à l'autre.
 *
 * ⚠️ Le WebView doit être créé ET utilisé sur le thread principal. Il est donc créé
 * paresseusement via [getWebView] (toujours appelé sur le main thread).
 */
class WorldNovelWebView private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: WorldNovelWebView? = null

        fun get(): WorldNovelWebView {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WorldNovelWebView().also { INSTANCE = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val jsBridge = object : Any() {
        @JavascriptInterface
        fun onElementFound(html: String) {
            onResult(html)
        }
    }

    @Volatile
    private var webView: WebView? = null
    @Volatile
    private var pendingScript: String? = null
    private var continuation: CancellableContinuation<String?>? = null
    private var timeoutRunnable: Runnable? = null

    /**
     * Crée (une seule fois) et retourne le WebView persistant.
     * DOIT être appelé sur le thread principal.
     */
    fun getWebView(context: Context): WebView {
        webView?.let { return it }
        return WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            @Suppress("DEPRECATION")
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(jsBridge, "NativeAndroid")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Réinjecte le script à chaque fin de page (gère aussi les redirections
                    // Cloudflare → contenu). pendingScript reste actif jusqu'à onResult.
                    pendingScript?.let { view?.evaluateJavascript(it, null) }
                }
            }
        }.also { webView = it }
    }

    /** Détache le WebView de son parent (fermeture de LoginActivity sans le détruire). */
    fun detachFromParent() {
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    private fun onResult(html: String) {
        val cont = continuation
        continuation = null
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        if (cont != null && cont.isActive) {
            cont.resume(html)
        }
    }

    /**
     * Charge [url] dans le WebView persistant (sur le main thread) et renvoie ce que le
     * script JS a envoyé via NativeAndroid.onElementFound(String). null en cas de timeout.
     */
    suspend fun extract(context: Context, url: String, script: String, timeoutMs: Long = 90_000): String? =
        suspendCancellableCoroutine { cont ->
            continuation = cont
            pendingScript = script
            val timeout = Runnable {
                val c = continuation
                continuation = null
                if (c != null && c.isActive) c.resume(null)
            }
            timeoutRunnable = timeout
            mainHandler.postDelayed(timeout, timeoutMs)
            mainHandler.post { getWebView(context).loadUrl(url) }
        }
}
