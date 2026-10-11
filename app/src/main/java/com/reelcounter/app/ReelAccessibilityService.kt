package com.reelcounter.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

/**
 * Watches the Instagram app and counts a reel once it has stayed on screen for
 * MIN_DWELL_MS. A reel is identified by "author|caption start" (read from Instagram's
 * view IDs). If those IDs aren't found (Instagram changes them often), it falls back to
 * counting one reel per settled vertical swipe inside the Reels viewer.
 */
class ReelAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var currentSig: String? = null
    private var lastCountedSig: String? = null
    private var pendingCount: Runnable? = null
    private var lastEval = 0L
    private val trailingEval = Runnable { evaluate(fromScroll = false) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != IG_PKG) return
        val isScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        val now = SystemClock.uptimeMillis()

        // Always re-check once things go quiet, so the final state after a swipe is seen.
        handler.removeCallbacks(trailingEval)
        handler.postDelayed(trailingEval, TRAILING_MS)

        if (isScroll || now - lastEval >= THROTTLE_MS) {
            lastEval = now
            evaluate(isScroll)
        }
    }

    private fun evaluate(fromScroll: Boolean) {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != IG_PKG) {
            leaveReels(); return
        }
        if (DEBUG_DUMP && fromScroll) dump(root, 0)

        val author = firstText(root, AUTHOR_IDS)
        val caption = firstText(root, CAPTION_IDS)
        val inReels = author != null ||
            REEL_VIEW_IDS.any { root.findAccessibilityNodeInfosByViewId("$IG_PKG:id/$it").isNotEmpty() } ||
            reelsTabSelected(root)

        if (!inReels) { leaveReels(); return }

        if (author != null) {
            val sig = author + "|" + (caption ?: "").take(30)
            if (sig != currentSig) {
                currentSig = sig
                scheduleCount(sig, author, MIN_DWELL_MS)
            }
        } else if (fromScroll) {
            // Fallback: no IDs available -> one count per settled swipe.
            val sig = "scroll@" + SystemClock.uptimeMillis()
            currentSig = sig
            scheduleCount(sig, null, SETTLE_MS)
        }
    }

    private fun scheduleCount(sig: String, author: String?, delayMs: Long) {
        pendingCount?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (sig != lastCountedSig) {
                lastCountedSig = sig
                val ts = System.currentTimeMillis()
                io.execute { ReelDb.get(applicationContext).insert(ts, author) }
            }
        }
        pendingCount = r
        handler.postDelayed(r, delayMs)
    }

    private fun leaveReels() {
        pendingCount?.let { handler.removeCallbacks(it) }
        pendingCount = null
        currentSig = null
    }

    private fun firstText(root: AccessibilityNodeInfo, ids: List<String>): String? {
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId("$IG_PKG:id/$id").firstOrNull() ?: continue
            val t = (node.text ?: node.contentDescription)?.toString()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    private fun reelsTabSelected(root: AccessibilityNodeInfo): Boolean =
        root.findAccessibilityNodeInfosByText("Reels").any { it.isSelected }

    /** Debug helper: logs every view id/text on screen so you can adapt the ID lists below. */
    private fun dump(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 30) return
        val id = node.viewIdResourceName
        if (id != null) {
            Log.d(TAG, "  ".repeat(depth) + id + " | " + node.text + " | " + node.contentDescription)
        }
        for (i in 0 until node.childCount) dump(node.getChild(i), depth + 1)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReelCounter"
        private const val IG_PKG = "com.instagram.android"

        private const val MIN_DWELL_MS = 1500L   // reel must stay this long to count
        private const val SETTLE_MS = 1500L      // fallback mode: wait after last scroll event
        private const val TRAILING_MS = 350L
        private const val THROTTLE_MS = 250L

        /** Set to true, swipe through reels, then read Logcat (tag "ReelCounter") to find current IDs. */
        private const val DEBUG_DUMP = false

        // Instagram's resource IDs are unofficial and change between versions. Edit if needed.
        private val AUTHOR_IDS = listOf("clips_author_username", "clips_author_info_component")
        private val CAPTION_IDS = listOf("clips_caption_component", "clips_caption_text")
        private val REEL_VIEW_IDS = listOf("clips_viewer_view_pager", "clips_swipe_refresh_container")
    }
}
