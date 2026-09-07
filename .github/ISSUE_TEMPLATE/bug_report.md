---
name: Bug report
about: Something behaves incorrectly
title: ''
labels: bug
assignees: ''
---

**What happened**

<!-- and what you expected instead -->

**Steps to reproduce**

1.
2.
3.

**Device**

- Model:
- Android version:
- Seamless version: <!-- Settings, or the tag you built from -->

**Which part of the app**

<!-- Shorts feed / ordinary player / library / settings -->

---

**If a video will not play**

This is the most useful thing you can include. Open the video, tap the screen, then use the
three-dot menu → **Info** and paste what it says — especially the codec lines. If an error
appeared on the black screen, include that text too: it distinguishes a codec the device
cannot decode from a clip that decoded fine and rendered nothing, which are different bugs
with different fixes.

**Logs, if you can get them**

```
adb logcat -s Seamless/ShortsAdapter Seamless/PlayerActivity Seamless/MediaOps
```
