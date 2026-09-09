package com.lagradost.quicknovel

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.quicknovel.network.WorldNovelWebView

/**
 * Écran de connexion / validation pour world-novel.fr (Victorian Novel House).
 * Ouvre le WebView PERSISTANT partagé : l'utilisateur passe Cloudflare, se connecte
 * et/ou valide le reCAPTCHA. La session (localStorage + cookies) reste dans la MÊME
 * instance de WebView, donc l'extraction des chapitres en hérite ensuite.
 */
class LoginActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "WorldNovel — connecte-toi / valide le reCAPTCHA puis « Fermer »"
            textSize = 14f
            setPadding(24, 16, 24, 16)
            gravity = Gravity.CENTER
        }

        val closeButton = Button(this).apply {
            text = "Fermer"
            setOnClickListener { finish() }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(closeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val webView: WebView = WorldNovelWebView.get().getWebView(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(layout)

        webView.loadUrl(intent.getStringExtra("url") ?: "https://world-novel.fr/")
    }

    override fun onDestroy() {
        // Détache le WebView mais le conserve en mémoire (session partagée).
        WorldNovelWebView.get().detachFromParent()
        super.onDestroy()
    }
}
