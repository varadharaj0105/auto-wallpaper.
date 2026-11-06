# Auto Wallpaper

Changes the Home and lock screen wallpaper every time you lock the phone.

## How it works
- It's a **live wallpaper**. Android keeps it running as part of the home screen, so there's
  no background service, no notification and no battery-optimization exemption needed.
- When the screen turns off, it moves to the next picked image and draws it while the display
  is dark, so the new one is already showing when you turn the screen on.
- Images are decoded at screen size and photo rotation is respected.
- After a reboot it carries on by itself.

## Build
1. Open this folder in Android Studio (Hedgehog or newer) and let Gradle sync.
2. Plug in your phone with USB debugging on and press Run (or Build > Build APK).

## Use
1. Open **Auto Wallpaper** and tap **Pick wallpapers** (select as many as you like).
2. Tap **Set as wallpaper**, then choose **Home screen and lock screen**.
3. Lock and unlock: you'll see the next image.

Requires Android 9+. To change the set, just pick again. The new set shows from the next unlock.

## Note
Some phones (Samsung especially) only allow live wallpapers on the home screen, not the lock screen.
