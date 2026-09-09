package com.lagradost.quicknovel.providers

import android.content.Intent
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.LoginActivity
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.network.WorldNovelWebView
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.util.Coroutines
import org.jsoup.nodes.Element

/**
 * world-novel.fr = "Victorian Novel House" (web novels FR).
 * Next.js App Router + Cloudflare (challenge Turnstile) + backend App Engine/GCS.
 *
 * VERSION DIAGNOSTIC : le texte des chapitres est chargé côté client via un appel API
 * non identifié. loadHtml() ouvre donc la page dans un WebView (qui passe Cloudflare
 * et exécute le JS) et capture toutes les requêtes réseau, affichées en clair pour
 * récupérer l'endpoint de contenu.
 */
class WorldNovelProvider : MainAPI() {
    override val name = "WorldNovel (VN)"
    override val mainUrl = "https://world-novel.fr"
    override val lang = "fr"
    override val usesCloudFlareKiller = true
    override val iconId = R.drawable.icon_worldnovel
    override val hasMainPage = true
    override val rateLimitTime = 1500L

    // === CATALOGUE (page d'accueil, SSR) ===
    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): HeadMainPageResponse {
        val doc = app.get(mainUrl).document
        val novels = doc.select("a[href^=\"/oeuvres/\"]")
            .mapNotNull(::cardFromElement)
            .distinctBy { it.url }
        return HeadMainPageResponse(mainUrl, novels)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val doc = app.get(mainUrl).document
        val all = doc.select("a[href^=\"/oeuvres/\"]")
            .mapNotNull(::cardFromElement)
            .distinctBy { it.url }
        return all.filter { it.name.lowercase().contains(q) }.ifEmpty { null }
    }

    // === FICHE (données dans le payload RSC) ===
    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text
        val oeuvre = extractOeuvre(html) ?: return null
        val title = (oeuvre["title"] as? String)?.trim() ?: return null
        val description = oeuvre["description"] as? String
        val image = oeuvre["image"] as? String
        val author = oeuvre["auteur"] as? String
        val genre = oeuvre["genre"] as? String

        val slug = url.trimEnd('/').substringAfterLast('/')
        val chapters = mutableListOf<ChapterData>()
        val volumes = oeuvre["volumes"] as? List<*> ?: emptyList<Any>()
        for (v in volumes) {
            val vm = v as? Map<*, *> ?: continue
            val volumeId = vm["volumeId"] as? String ?: continue
            val chaps = vm["chapters"] as? List<*> ?: continue
            for (c in chaps) {
                val cm = c as? Map<*, *> ?: continue
                val cid = cm["id"] as? String ?: continue
                val chapterUrl = "$mainUrl/lecture/$slug/volumes/${encodeURIComponent(volumeId)}/chapitres/${encodeURIComponent(cid)}"
                chapters.add(newChapterData(cid, chapterUrl))
            }
        }
        // Le site liste les chapitres du plus récent au plus ancien → on inverse pour l'ordre de lecture
        chapters.reverse()

        return newStreamResponse(title, url, chapters) {
            posterUrl = fixUrlNull(image)
            synopsis = description
            this.author = author
            if (!genre.isNullOrBlank()) tags = genre.split(',').map { it.trim() }
        }
    }

    // === LECTURE (extraction du texte via WebView — Cloudflare + Firebase/Firestore) ===
    override suspend fun loadHtml(url: String): String? {
        val text = extractChapterText(url) ?: return null
        if (text.startsWith("__JUNK__")) {
            val rest = text.removePrefix("__JUNK__|")
            val escaped = rest.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return "🔍 Texte obfusqué détecté (le site a inséré des caractères parasites).\n\nEnvoie-moi EN CAPTURE le code ci-dessous pour que je déchiffre :\n\n$escaped"
        }
        if (text.startsWith("__RECAPTCHA__")) {
            launchLogin(url)
            return "⚠️ Le site demande une validation reCAPTCHA pour ce chapitre.\n\nL'écran de validation s'est ouvert sur ce chapitre : coche « Je ne suis pas un robot », attends que le texte charge, puis « Fermer » et rouvre ce chapitre."
        }
        if (text.startsWith("__LOGIN__") || text.contains("connecter à un compte", ignoreCase = true)) {
            launchLogin(url)
            return "⚠️ Connecte-toi à world-novel.fr pour lire ce chapitre.\n\nL'écran de connexion s'est ouvert : connecte-toi puis rouvre ce chapitre."
        }
        if (text.startsWith("__PREVIEW__")) {
            launchLogin(url)
            return "⚠️ Le texte ne s'est pas chargé (session expirée ?).\n\nL'écran s'est ouvert sur ce chapitre : reconnecte-toi, puis « Fermer » et rouvre ce chapitre."
        }
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return escaped.replace("\n", "<br>")
    }

    private fun launchLogin(url: String? = null) {
        Coroutines.runOnMainThread {
            val ctx = BaseApplication.context ?: return@runOnMainThread
            val intent = Intent(ctx, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!url.isNullOrBlank()) putExtra("url", url)
            }
            runCatching { ctx.startActivity(intent) }
        }
    }

    private suspend fun extractChapterText(url: String): String? {
        val script = """
            (function() {
                function clickCf() {
                    var isCf = document.querySelector('#challenge-form') || document.querySelector('#challenge-running') || document.querySelector('#cf-challenge-running');
                    if (!isCf) return;
                    var tok = document.querySelector('[name="cf-turnstile-response"]')?.value || document.querySelector('#cf-chl-widget-multi-token')?.value;
                    var btn = document.querySelector('#challenge-form button[type="submit"], .antibot-btn-success') || document.querySelector('#challenge-form input[type="submit"]');
                    if (tok && btn) { btn.click(); }
                    else { if (!window.__cfTry) window.__cfTry = 0; if (window.__cfTry < 15) { window.__cfTry++; setTimeout(clickCf, 1000); } }
                }
                function clickRecaptcha() {
                    // v3 / v2 invisible : déclencher l'exécution programmatique
                    try {
                        if (window.grecaptcha && typeof grecaptcha.execute === 'function') {
                            if (grecaptcha.execute.length === 0) { grecaptcha.execute(); }
                        }
                    } catch (e) {}
                    // v2 checkbox : tenter le clic sur le conteneur (best effort, iframe cross-origin)
                    var r = document.querySelector('.g-recaptcha, div[data-sitekey], [data-callback]');
                    if (r) { try { r.click(); } catch (e) {} }
                    try {
                        var f = document.querySelector('iframe[src*="recaptcha/api2/anchor"]');
                        if (f && f.contentDocument) {
                            var cb = f.contentDocument.querySelector('#recaptcha-anchor, .recaptcha-checkbox-border, .recaptcha-checkbox');
                            if (cb) cb.click();
                        }
                    } catch (e) {}
                }
                function hasRecaptcha() {
                    return !!document.querySelector('.g-recaptcha, iframe[src*="recaptcha"], [data-sitekey]');
                }
                function isNoise(el) {
                    var c = ((el.className || '') + ' ' + (el.id || '')).toLowerCase();
                    return c.indexOf('nav') >= 0 || c.indexOf('header') >= 0 || c.indexOf('footer') >= 0 ||
                           c.indexOf('menu') >= 0 || c.indexOf('setting') >= 0 || c.indexOf('sidebar') >= 0 ||
                           c.indexOf('drawer') >= 0 || c.indexOf('aside') >= 0 || c.indexOf('toolbar') >= 0 ||
                           c.indexOf('modal') >= 0;
                }
                var BOILER = ['tous droits réservés', 'distribution, même partielle', 'aperçu partiel', 'acheter les versions', 'valider le recaptcha', 'commentaires sont de retour', 'toute distribution'];
                function isBoilerplate(t) {
                    var low = t.toLowerCase();
                    for (var b = 0; b < BOILER.length; b++) { if (low.indexOf(BOILER[b]) >= 0) return true; }
                    return false;
                }
                function extract() {
                    // 1) paragraphes réels (hors boilerplate légal / reCAPTCHA / commentaires)
                    var ps = document.querySelectorAll('p');
                    var out = '';
                    for (var i = 0; i < ps.length; i++) {
                        var t = (ps[i].innerText || '').trim();
                        if (t.length < 2 || isBoilerplate(t)) continue;
                        out += t + '\n\n';
                    }
                    if (out.trim().length > 200) return out;
                    // 2) conteneur au texte le plus long (hors bruit ET hors boilerplate)
                    var best = null, bestLen = 0;
                    var all = document.querySelectorAll('div, article, section, main');
                    for (var i = 0; i < all.length; i++) {
                        var el = all[i];
                        if (isNoise(el)) continue;
                        var t = (el.innerText || '').trim();
                        var lines = t.split('\n');
                        var clean = [];
                        for (var j = 0; j < lines.length; j++) {
                            var line = lines[j].trim();
                            if (line.length < 2 || isBoilerplate(line)) continue;
                            clean.push(line);
                        }
                        var cleaned = clean.join('\n');
                        if (cleaned.length > bestLen && cleaned.length < 100000) { best = cleaned; bestLen = cleaned.length; }
                    }
                    if (best && bestLen > 200) return best;
                    return '';
                }
                function junkRatio(t) {
                    var junk = 0, total = 0;
                    function isLetter(x) { return (x >= 65 && x <= 90) || (x >= 97 && x <= 122) || (x >= 192 && x <= 255); }
                    for (var i = 0; i < t.length; i++) {
                        var c = t.charCodeAt(i);
                        if (c < 32) continue;
                        total++;
                        var prev = i > 0 ? t.charCodeAt(i - 1) : 0;
                        var next = i < t.length - 1 ? t.charCodeAt(i + 1) : 0;
                        // chiffre entouré de lettres = parasite
                        if (c >= 48 && c <= 57 && isLetter(prev) && isLetter(next)) junk++;
                        // majuscule entourée de minuscules = parasite
                        else if (c >= 65 && c <= 90 && prev >= 97 && prev <= 122 && next >= 97 && next <= 122) junk++;
                    }
                    return junk / Math.max(total, 1);
                }
                function findJunkEl() {
                    var all = document.querySelectorAll('div, p, article, section');
                    var best = null, bestJr = 0.02;
                    for (var i = 0; i < all.length; i++) {
                        var el = all[i];
                        var t = (el.innerText || '').trim();
                        if (t.length < 150 || t.length > 50000) continue;
                        var jr = junkRatio(t);
                        if (jr > bestJr) { best = el; bestJr = jr; }
                    }
                    return best;
                }
                function deobfuscate() {
                    // retire les <span> courts (1-3 chars) masqués par CSS : cause la plus
                    // fréquente des caractères parasites anti-copie.
                    var spans = document.querySelectorAll('span');
                    for (var i = 0; i < spans.length; i++) {
                        var s = spans[i];
                        var t = (s.textContent || '').trim();
                        if (t.length < 1 || t.length > 3) continue;
                        var cs = null;
                        try { cs = getComputedStyle(s); } catch (e) {}
                        var style = (s.getAttribute('style') || '').toLowerCase();
                        var cls = (s.className || '').toString().toLowerCase();
                        var hidden = false;
                        if (cs) hidden = cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.fontSize) === 0 || cs.opacity === '0';
                        if (!hidden) hidden = style.indexOf('display:none') >= 0 || style.indexOf('display: none') >= 0 || style.indexOf('font-size:0') >= 0 || style.indexOf('font-size: 0') >= 0 || style.indexOf('opacity:0') >= 0 || style.indexOf('opacity: 0') >= 0;
                        var suspicious = cls.indexOf('mangle') >= 0 || cls.indexOf('junk') >= 0 || cls.indexOf('noise') >= 0 || cls.indexOf('hidden') >= 0 || cls.indexOf('obfus') >= 0 || cls.indexOf('scramble') >= 0 || cls.indexOf('glitch') >= 0 || cls.indexOf('token') >= 0;
                        if (hidden || suspicious) { if (s.parentNode) s.parentNode.removeChild(s); }
                    }
                }
                var maxLen = 0, tries = 0;
                function check() {
                    tries++;
                    if (document.querySelector('#challenge-form, #challenge-running, #cf-challenge-running')) clickCf();
                    if (hasRecaptcha()) clickRecaptcha();
                    var txt = document.body ? document.body.innerText : '';
                    if (txt.length > maxLen) maxLen = txt.length;
                    var story = extract();
                    var hasStory = story.trim().length > 200;
                    if (hasStory) {
                        if (junkRatio(story) > 0.05) {
                            deobfuscate();
                            var cleanStory = extract();
                            if (cleanStory.trim().length > 200 && junkRatio(cleanStory) < 0.05) {
                                NativeAndroid.onElementFound(cleanStory);
                            } else {
                                var jel = findJunkEl();
                                NativeAndroid.onElementFound('__JUNK__|ratio=' + Math.round(junkRatio(story) * 100) + '|HTML=' + (jel ? jel.innerHTML.substring(0, 4000) : 'none'));
                            }
                        } else {
                            NativeAndroid.onElementFound(story);
                        }
                        return;
                    }
                    var isRecap = txt.indexOf('valider le recaptcha') >= 0;
                    var isLogin = txt.indexOf('connecter à un compte') >= 0;
                    var isPreview = txt.indexOf('acheter la version') >= 0 || txt.indexOf('acheter les versions') >= 0;
                    // Laisse ~20 s au contenu Firestore, puis conclut à un blocage.
                    var timeout = tries >= 10;
                    if (timeout) {
                        if (isRecap) {
                            NativeAndroid.onElementFound('__RECAPTCHA__|' + maxLen);
                        } else if (isLogin) {
                            NativeAndroid.onElementFound('__LOGIN__');
                        } else if (isPreview) {
                            NativeAndroid.onElementFound('__PREVIEW__|' + maxLen);
                        } else {
                            NativeAndroid.onElementFound('DIAG|bodyLen=' + txt.length + '|maxLen=' + maxLen + '|iframes=' + document.querySelectorAll('iframe').length + '|pCount=' + document.querySelectorAll('p').length + '|hasRecaptcha=' + (hasRecaptcha() ? 'yes' : 'no') + '|TEXTE=' + txt.substring(0, 4000));
                        }
                        return;
                    }
                    setTimeout(check, 2000);
                }
                setTimeout(check, 2000);
            })();
        """.trimIndent()

        val ctx = BaseApplication.context ?: return null
        return try {
            WorldNovelWebView.get().extract(ctx, url, script)
        } catch (e: Exception) {
            null
        }
    }

    // === HELPERS ===
    private fun cardFromElement(a: Element): SearchResponse? {
        val href = a.attr("href")
        if (!href.startsWith("/oeuvres/")) return null
        val img = a.selectFirst("img") ?: return null
        val title = img.attr("alt").ifBlank { return null }
        val cover = img.attr("src").ifBlank { null }
        return newSearchResponse(title.trim(), href) {
            posterUrl = fixUrlNull(cover)
        }
    }

    private fun encodeURIComponent(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '-' || ch == '_' || ch == '.' || ch == '!' ||
                    ch == '~' || ch == '*' || ch == '\'' || ch == '(' || ch == ')' -> sb.append(ch)
                else -> {
                    for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                        sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun extractOeuvre(html: String): Map<String, Any?>? {
        val blockRegex = Regex("""self\.__next_f\.push\(\[1,\s*"(.*?)"\]\)""", RegexOption.DOT_MATCHES_ALL)
        for (m in blockRegex.findAll(html)) {
            val decoded = unescapeJson(m.groupValues[1]) ?: continue
            val key = "\"oeuvre\":{"
            val idx = decoded.indexOf(key)
            if (idx < 0) continue
            val json = extractBalanced(decoded, idx + "\"oeuvre\":".length) ?: continue
            return parseJsonMap(json)
        }
        return null
    }

    private fun unescapeJson(s: String): String? {
        return try {
            JSON_MAPPER.readValue("\"$s\"", String::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractBalanced(s: String, start: Int): String? {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonMap(json: String): Map<String, Any?>? {
        return try {
            JSON_MAPPER.readValue(json, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val JSON_MAPPER = ObjectMapper()
    }
}
