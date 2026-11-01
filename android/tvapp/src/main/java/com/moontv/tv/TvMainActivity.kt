package com.moontv.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moontv.tv.databinding.ActivityTvMainBinding
import java.util.Locale

class TvMainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTvMainBinding
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var orientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    private var mainFrameFailed = false

    private val preferences by lazy {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityTvMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterImmersiveMode()
        setupWebView()
        setupErrorPanel()
        setupBackNavigation()

        loadServer(preferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, false)
        binding.webView.addJavascriptInterface(CookieBridge(), COOKIE_BRIDGE_NAME)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (!userAgentString.contains(TV_USER_AGENT_MARKER)) {
                userAgentString = "$userAgentString $TV_USER_AGENT_MARKER/${BuildConfig.VERSION_NAME}"
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.pageProgress.isVisible = !mainFrameFailed && newProgress in 1..99
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(
                        TAG,
                        "WebView ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} " +
                            consoleMessage.message(),
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                mainFrameFailed = false
                binding.webView.isVisible = true
                binding.errorPanel.isVisible = false
                binding.pageProgress.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.pageProgress.isVisible = false
                if (!mainFrameFailed) {
                    installTvNavigation()
                    binding.webView.requestFocus()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url ?: return false
                if (target.scheme in setOf("http", "https")) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, target)) }
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                mainFrameFailed = true
                binding.pageProgress.isVisible = false
                binding.webView.isVisible = false
                binding.errorMessage.text = error?.description?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.load_failed_message)
                binding.errorPanel.isVisible = true
                binding.retryButton.requestFocus()
            }
        }
    }

    private fun setupErrorPanel() {
        binding.retryButton.setOnClickListener {
            binding.errorPanel.isVisible = false
            binding.webView.isVisible = true
            binding.webView.reload()
        }
        binding.settingsButton.setOnClickListener { showServerDialog() }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exitFullscreen()) return
                binding.webView.evaluateJavascript(
                    "window.__moonTvTvExitFullscreen?window.__moonTvTvExitFullscreen():false;",
                ) { handled ->
                    if (handled == "true") return@evaluateJavascript
                    when {
                        binding.errorPanel.isVisible -> finish()
                        binding.webView.canGoBack() -> binding.webView.goBack()
                        else -> finish()
                    }
                }
            }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (binding.errorPanel.isVisible && event.keyCode !in setOf(
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SETTINGS,
            )
        ) {
            return super.dispatchKeyEvent(event)
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            -> {
                showServerDialog()
                true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            -> {
                dispatchRemoteKey("MediaPlayPause")
                true
            }

            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                dispatchRemoteKey("ArrowLeft", forcePlayer = true)
                true
            }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                dispatchRemoteKey("ArrowRight", forcePlayer = true)
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> consumeRemoteKey("ArrowLeft")
            KeyEvent.KEYCODE_DPAD_RIGHT -> consumeRemoteKey("ArrowRight")
            KeyEvent.KEYCODE_DPAD_UP -> consumeRemoteKey("ArrowUp")
            KeyEvent.KEYCODE_DPAD_DOWN -> consumeRemoteKey("ArrowDown")
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> consumeRemoteKey("Enter")

            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun consumeRemoteKey(key: String): Boolean {
        dispatchRemoteKey(key)
        return true
    }

    private fun dispatchRemoteKey(key: String, forcePlayer: Boolean = false) {
        val quotedKey = org.json.JSONObject.quote(key)
        binding.webView.evaluateJavascript(
            "window.__moonTvTvHandleKey&&window.__moonTvTvHandleKey($quotedKey,$forcePlayer);",
            null,
        )
    }

    private fun installTvNavigation() {
        binding.webView.evaluateJavascript(TV_NAVIGATION_SCRIPT, null)
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        orientationBeforeFullscreen = requestedOrientation
        (view.parent as? ViewGroup)?.removeView(view)
        binding.fullscreenContainer.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        binding.webView.isVisible = false
        binding.fullscreenContainer.isVisible = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        enterImmersiveMode()
    }

    private fun exitFullscreen(): Boolean {
        val view = fullscreenView ?: return false
        fullscreenView = null
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.isVisible = false
        binding.webView.isVisible = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = orientationBeforeFullscreen
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        enterImmersiveMode()
        return true
    }

    private fun showServerDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.server_url_hint)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(preferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL))
            setSelection(text.length)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.server_url_title)
            .setMessage(R.string.server_url_help)
            .setView(input)
            .setNegativeButton("取消", null)
            .setNeutralButton("清理缓存", null)
            .setPositiveButton("保存", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                binding.webView.clearCache(true)
                binding.webView.reload()
                Toast.makeText(this, "网页缓存已清理", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = normalizeServerUrl(input.text.toString())
                if (normalized == null) {
                    input.error = getString(R.string.server_url_error)
                    return@setOnClickListener
                }
                val previous = preferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL)
                preferences.edit { putString(KEY_SERVER_URL, normalized) }
                loadServer(normalized, clearHttpCache = previous != normalized)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun loadServer(serverUrl: String, clearHttpCache: Boolean = false) {
        binding.errorPanel.isVisible = false
        binding.webView.isVisible = true
        if (clearHttpCache) binding.webView.clearCache(true)
        binding.webView.loadUrl(resolveStartUrl(serverUrl))
    }

    private fun resolveStartUrl(serverUrl: String): String {
        val hasAuthCookie = CookieManager.getInstance().getCookie(serverUrl)
            .orEmpty()
            .split(';')
            .any { cookie ->
                val parts = cookie.trim().split('=', limit = 2)
                parts.firstOrNull() == "auth" && parts.getOrNull(1).orEmpty().isNotBlank()
            }
        if (hasAuthCookie) return serverUrl

        return serverUrl.toUri().buildUpon()
            .appendPath("login")
            .appendQueryParameter("redirect", "/")
            .build()
            .toString()
    }

    private fun normalizeServerUrl(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri: Uri = withScheme.toUri()
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return null
        }
        return uri.toString()
    }

    override fun onDestroy() {
        exitFullscreen()
        binding.webView.stopLoading()
        binding.webView.removeJavascriptInterface(COOKIE_BRIDGE_NAME)
        binding.webView.webChromeClient = null
        binding.webView.webViewClient = WebViewClient()
        binding.webView.destroy()
        super.onDestroy()
    }

    private class CookieBridge {
        @JavascriptInterface
        fun flush() {
            CookieManager.getInstance().flush()
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "moontv_tv"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_SERVER_URL = "https://moontv-58u.pages.dev"
        private const val TV_USER_AGENT_MARKER = "MoonTVTV"
        private const val COOKIE_BRIDGE_NAME = "MoonTVCookieBridge"
        private const val TAG = "MoonTVTV"

        private val TV_NAVIGATION_SCRIPT =
            """
            (() => {
              if (window.__moonTvTvNavigationInstalled) {
                window.__moonTvTvRefreshFocus?.();
                return;
              }
              window.__moonTvTvNavigationInstalled = true;
              document.documentElement.classList.add('moontv-tv');

              const style = document.createElement('style');
              style.id = 'moontv-tv-focus-style';
              style.textContent = `
                .moontv-tv *:focus { outline: none !important; }
                .moontv-tv .moontv-tv-focused {
                  outline: 4px solid #22c55e !important;
                  outline-offset: 4px !important;
                  box-shadow: 0 0 0 7px rgba(34,197,94,.30) !important;
                  border-radius: 8px !important;
                }
                .moontv-tv .art-video-player.moontv-tv-focused {
                  outline-offset: -4px !important;
                }
                body.moontv-tv-player-fullscreen { overflow: hidden !important; }
                .moontv-tv .art-video-player.moontv-tv-native-fullscreen {
                  position: fixed !important;
                  inset: 0 !important;
                  width: 100vw !important;
                  height: 100vh !important;
                  max-width: none !important;
                  max-height: none !important;
                  z-index: 2147483647 !important;
                  border-radius: 0 !important;
                  background: #000 !important;
                }
              `;
              document.head.appendChild(style);

              const selector = [
                'a[href]', 'button', 'input', 'select', 'textarea',
                '[role="button"]', '[role="tab"]', '[tabindex]',
                '.cursor-pointer', '.art-video-player'
              ].join(',');
              const playerControlSelector = '.art-control:not(.art-control-time)';

              const visible = (el) => {
                if (!(el instanceof HTMLElement) || el.hidden || el.hasAttribute('disabled')) return false;
                const css = getComputedStyle(el);
                if (css.display === 'none' || css.visibility === 'hidden' || Number(css.opacity) === 0) return false;
                const rect = el.getBoundingClientRect();
                return rect.width >= 8 && rect.height >= 8 && rect.bottom > 0 && rect.right > 0 &&
                  rect.top < innerHeight && rect.left < innerWidth;
              };

              const activeScope = () => {
                const overlays = Array.from(document.querySelectorAll('body *')).filter((el) => {
                  if (!(el instanceof HTMLElement) || !visible(el)) return false;
                  const css = getComputedStyle(el);
                  const rect = el.getBoundingClientRect();
                  return css.position === 'fixed' && css.pointerEvents !== 'none' &&
                    rect.width >= innerWidth * .45 && rect.height >= innerHeight * .45 &&
                    Boolean(el.querySelector(selector));
                });
                overlays.sort((a, b) => {
                  const az = Number.parseInt(getComputedStyle(a).zIndex, 10) || 0;
                  const bz = Number.parseInt(getComputedStyle(b).zIndex, 10) || 0;
                  return az - bz;
                });
                return overlays.length ? overlays[overlays.length - 1] : document;
              };

              const candidates = (root = activeScope()) => {
                const active = document.activeElement;
                const query = active instanceof Element && active.closest('.art-control')
                  ? selector + ',' + playerControlSelector
                  : selector;
                return Array.from(root.querySelectorAll(query)).filter(visible);
              };
              const markFocused = (el) => {
                document.querySelectorAll('.moontv-tv-focused').forEach((item) => item.classList.remove('moontv-tv-focused'));
                if (!(el instanceof HTMLElement)) return;
                if (!el.matches('a,button,input,select,textarea,[tabindex]')) el.tabIndex = 0;
                el.classList.add('moontv-tv-focused');
                try { el.focus({ preventScroll: true }); } catch (_) { el.focus(); }
                el.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
              };

              const initialFocus = () => {
                const scope = activeScope();
                const active = document.activeElement;
                if (active instanceof HTMLElement && active !== document.body && scope.contains(active) && visible(active)) {
                  markFocused(active);
                  return;
                }
                const items = candidates(scope);
                const preferred = items.find((el) => el.matches('header a,nav a,[role="tab"],input')) || items[0];
                if (preferred) markFocused(preferred);
              };

              const move = (key) => {
                const scope = activeScope();
                const items = candidates(scope);
                if (!items.length) return;
                let current = document.activeElement;
                if (!(current instanceof HTMLElement) || !scope.contains(current) || !visible(current)) {
                  markFocused(items[0]);
                  return;
                }
                const from = current.getBoundingClientRect();
                const fx = from.left + from.width / 2;
                const fy = from.top + from.height / 2;
                let best = null;
                let bestScore = Number.POSITIVE_INFINITY;
                for (const item of items) {
                  if (item === current || current.contains(item) || item.contains(current)) continue;
                  const rect = item.getBoundingClientRect();
                  const x = rect.left + rect.width / 2;
                  const y = rect.top + rect.height / 2;
                  const dx = x - fx;
                  const dy = y - fy;
                  const primary = key === 'ArrowLeft' ? -dx : key === 'ArrowRight' ? dx : key === 'ArrowUp' ? -dy : dy;
                  if (primary <= 4) continue;
                  const secondary = key === 'ArrowLeft' || key === 'ArrowRight' ? Math.abs(dy) : Math.abs(dx);
                  const score = primary + secondary * 2.25;
                  if (score < bestScore) {
                    bestScore = score;
                    best = item;
                  }
                }
                if (best) markFocused(best);
              };

              const sendPlayerKey = (key) => {
                const mappedKey = key === 'MediaPlayPause' ? ' ' : key;
                const code = mappedKey === ' ' ? 'Space' : mappedKey;
                document.dispatchEvent(new KeyboardEvent('keydown', {
                  key: mappedKey,
                  code,
                  bubbles: true,
                  cancelable: true
                }));
              };

              let tvFullscreenOrigin = null;
              let tvFullscreenNext = null;
              const setTvFullscreen = (player, enabled) => {
                if (!player) return false;
                if (enabled && player.parentElement !== document.body) {
                  tvFullscreenOrigin = player.parentNode;
                  tvFullscreenNext = player.nextSibling;
                  document.body.appendChild(player);
                } else if (!enabled && tvFullscreenOrigin?.isConnected) {
                  const anchor = tvFullscreenNext?.parentNode === tvFullscreenOrigin ? tvFullscreenNext : null;
                  tvFullscreenOrigin.insertBefore(player, anchor);
                  tvFullscreenOrigin = null;
                  tvFullscreenNext = null;
                }
                player.classList.toggle('moontv-tv-native-fullscreen', enabled);
                document.body.classList.toggle('moontv-tv-player-fullscreen', enabled);
                if (enabled) markFocused(player);
                return enabled;
              };

              window.__moonTvTvExitFullscreen = () => {
                const player = document.querySelector('.art-video-player.moontv-tv-native-fullscreen');
                if (!player) return false;
                setTvFullscreen(player, false);
                markFocused(player);
                return true;
              };

              window.__moonTvTvHandleKey = (key, forcePlayer = false) => {
                const active = document.activeElement;
                const player = document.querySelector('.art-video-player');
                const playerHostFocused = active instanceof Element && active.matches('.art-video-player');
                const playerControlFocused = active instanceof Element && Boolean(active.closest('.art-control'));
                const tvFullscreen = Boolean(player?.classList.contains('moontv-tv-native-fullscreen'));
                const fullscreen = Boolean(document.fullscreenElement || document.webkitFullscreenElement || tvFullscreen);

                if (forcePlayer || key === 'MediaPlayPause') {
                  if (player) sendPlayerKey(key === 'MediaPlayPause' ? 'MediaPlayPause' : key);
                  return;
                }
                if (player && !playerControlFocused && (fullscreen || playerHostFocused)) {
                  if (key === 'Enter') sendPlayerKey('MediaPlayPause');
                  else if (key === 'ArrowUp') {
                    setTvFullscreen(player, !tvFullscreen);
                  } else if (key === 'ArrowDown') {
                    const playControl = player.querySelector('.art-control-playAndPause');
                    if (playControl) markFocused(playControl);
                  } else sendPlayerKey(key);
                  return;
                }
                if (key === 'Enter') {
                  if (active instanceof HTMLElement && active !== document.body) {
                    const clickTarget = active.matches('a[href],button,input,select,textarea,[role="button"],[role="tab"]')
                      ? active
                      : active.querySelector('a[href],button,[role="button"],svg');
                    const target = clickTarget || active;
                    if (target instanceof HTMLButtonElement && target.type === 'submit' && target.form) {
                      target.form.requestSubmit(target);
                    } else if (target instanceof HTMLElement) {
                      target.click();
                    }
                  } else initialFocus();
                  return;
                }
                move(key);
              };

              window.__moonTvTvRefreshFocus = initialFocus;
              document.addEventListener('focusin', (event) => {
                if (event.target instanceof HTMLElement) markFocused(event.target);
              });
              let refreshTimer = 0;
              new MutationObserver(() => {
                if (!document.querySelector('.art-video-player.moontv-tv-native-fullscreen')) {
                  document.body.classList.remove('moontv-tv-player-fullscreen');
                }
                clearTimeout(refreshTimer);
                refreshTimer = setTimeout(initialFocus, 80);
              }).observe(document.body, { childList: true, subtree: true });
              setTimeout(initialFocus, 250);
            })();
            """.trimIndent()
    }
}
