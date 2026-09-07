# Phone screenshots

Drop PNGs here named `1.png`, `2.png`, `3.png` … in the order you want them shown.
F-Droid reads this directory directly; Google Play needs the same images uploaded through
its console.

Capture from a connected device:

```bash
adb exec-out screencap -p > 1.png
```

Worth showing, roughly in this order:

1. The shorts feed mid-playback — it is the reason the app exists
2. The library grid
3. The ordinary player with its controls visible
4. Settings, showing the theme and accent options

Requirements: PNG or JPEG, between 320px and 3840px on the long edge. Two minimum for
Play; four or five tells the story better.

Delete this file once real screenshots are in place.
