# Reel Counter

For releases -> <a href="https://github.com/Arun-K-kumar/reels\_tracker/releases/tag/v1.0.0">Click Here</a>

Counts Instagram Reels you watch (via an Android AccessibilityService) and shows analytics on the home screen.
Everything is stored locally in SQLite. Nothing is uploaded.

## Build

1. Open this folder in Android Studio (Koala or newer). Let Gradle sync (it downloads Gradle 8.7).
2. Run on a phone (Android 8.0+).
3. Open the app -> "Enable tracking" -> turn on **Reel Counter** in Accessibility settings.
On Android 13+ sideloaded apps may need: App info -> ⋮ -> "Allow restricted settings" first.
4. Open Instagram and scroll Reels. The count updates live.

## How a reel is counted

A reel counts once it has been on screen for 1.5 s (fast flicks don't count). Replaying the same reel doesn't recount.

## If counts stay at 0 or are off

Instagram's internal view IDs change between versions. In ReelAccessibilityService.kt:

* set `DEBUG\\\_DUMP = true`, install, scroll a few reels
* run `adb logcat -s ReelCounter` and look for the IDs that hold the username/caption
* update AUTHOR\_IDS / CAPTION\_IDS / REEL\_VIEW\_IDS
Without any matching IDs it falls back to counting one reel per settled swipe in the Reels tab.





