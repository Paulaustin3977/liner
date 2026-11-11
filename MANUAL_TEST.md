# Liner — Manual Test Script

A step-by-step script for checking Liner by hand on a real device.

Liner's automated tests are the JVM unit tests in `app/src/test/` — they run
headless and are green on every build. The instrumentation tests in
`app/src/androidTest/` need a device or an emulator, and **the environment
that built Liner had neither, so they have never been executed**. They are
shipped as source and are expected to be run on a device. **This script is
therefore the only end-to-end verification of Liner that exists**, and it
should be run before Liner is trusted with somebody else's home screen.

---

## How to use this script

**What you need**

- An Android phone or tablet running Android 8.0 (API 26) or newer.
- The Liner APK (`liner-release.apk`, or `liner-debug.apk` — the debug build
  installs under a different package name, `dev.liner.launcher.debug`, so it
  can sit next to a release install; both are called "Liner" in the app list).
- About 30 minutes.
- A USB cable and `adb` for **two optional steps** (13.3 and 13.4). They are
  marked *(needs adb)*. If you cannot run them, say so in your report —
  they cover the error paths that cannot be reached from the UI alone.

**How to read a step**

Every step says what to do and then what you should see. A step passes when
what you see matches. Tick the box next to a step when it passes. Where the
app shows a piece of text, the script quotes it exactly — if you do not see
that text, the step failed.

**If you skip a step**

Say so in the report. "Skipped" is a legitimate result; "not run" is not.

---

## 1. Install and first run

- [ ] **1.1** Install the APK. With a cable: `adb install -r liner-release.apk`.
      Without one: copy the APK to the phone and tap it, allowing
      "install from unknown sources" if Android asks.
      **Expected:** Android reports that Liner was installed, and "Liner"
      appears in the app list.
- [ ] **1.2** Open Liner from the app list.
      **Expected:** the home screen appears — a large clock, the date under
      it, and the phone's wallpaper dimmed behind them. No app labels yet,
      because nothing has been chosen.
- [ ] **1.3** Look at the middle of the screen.
      **Expected:** the empty state reads "No apps on your home screen yet",
      with a "Choose apps" button under it.
- [ ] **1.4** Tap "Choose apps".
      **Expected:** Liner Settings opens on the **Apps** tab (the tab row at
      the top shows "Apps" as the selected tab).
- [ ] **1.5** Look under the clock.
      **Expected:** a card reads "Set Liner as your home app", with the line
      "Liner is not your default launcher yet. Choose it to use it as your
      home screen." and two buttons: **"Set as default"** and **"Not now"**.
      If Liner is *already* the default home app the card is absent — that
      is also correct.
- [ ] **1.6** Tap **"Not now"**.
      **Expected:** the card disappears, and the home screen is unchanged
      otherwise.
- [ ] **1.7** Close Liner and open it again.
      **Expected:** the card does **not** come back. It is a one-time prompt,
      and the dismissal is remembered in the layout document.

---

## 2. Set Liner as the default launcher

- [ ] **2.1** Tap the **Advanced** tab.
      **Expected:** the banner "Liner is not your default launcher." is
      shown, with the line "Your home screen shows only while Liner is open."
      and a blue **"Set as default launcher"** button. If Liner is *already*
      the default home app, the banner is absent — that is also correct.
- [ ] **2.2** Tap "Set as default launcher".
      **Expected:** Android 10 and newer show the system's default-home
      dialog with Liner in the list; tap "Set as default" there. Android 9 and
      older open the system home-app chooser; pick Liner and tap "Set".
- [ ] **2.3** Press the HOME button.
      **Expected:** Liner appears — not the phone's stock launcher.
- [ ] **2.4** Reopen Liner Settings — swipe up from the bottom edge of the
      home screen to open the drawer, then tap the gear icon in the
      bottom-right corner — and go to the **Advanced** tab.
      **Expected:** the banner is gone. Liner knows it is the default home
      app.
- [ ] **2.5** Open the drawer (swipe up from the bottom edge), tap any app,
      and then press BACK.
      **Expected:** you come back to Liner's home screen, with your layout
      intact.
- [ ] **2.6** Clear Liner's data — Settings → Apps → Liner → Storage → Clear
      data — and open Liner again.
      **Expected:** the first-run card under the clock is back, because
      clearing the data cleared the layout document, which is where the
      dismissal is recorded.
- [ ] **2.7** Tap **"Set as default"** on that card.
      **Expected:** the same system flow as 2.2 — the default-home dialog on
      Android 10 and newer, the home-app chooser on Android 9 and older.
      Choose Liner and confirm.
- [ ] **2.8** Come back to Liner.
      **Expected:** the card is gone. The prompt does not come back once
      Liner *is* the default home app, whether or not it was dismissed.

---

## 3. Choose the apps for the home screen

- [ ] **3.1** In Liner Settings, tap the **Apps** tab.
      **Expected:** a "Search apps" field at the top, a "Refresh" button
      beside it, the line "Checked apps appear on your home screen", a
      "Home screen order" block, and then the "All apps" list.
- [ ] **3.2** Tick the checkbox next to **three** apps you recognise (a
      browser, a messaging app and a music app, say). Tick the name itself
      if you prefer — it does the same thing.
      **Expected:** each tick adds a tick to the checkbox and the app joins
      the "Home screen order" block at the top of the tab.
- [ ] **3.3** Read the line at the bottom of the tab.
      **Expected:** it reads "Home order: 3 apps".
- [ ] **3.4** Open the drawer by **swiping up from the very bottom of the
      screen**, then tap the **gear icon** in the bottom-right corner.
      **Expected:** Liner Settings opens on the **Appearance** tab. (This is
      the only way into the settings from the home screen — there is no
      overflow menu there.)
- [ ] **3.5** Go back to the home screen (press BACK until Liner's home
      screen is showing).
      **Expected:** the three apps are listed under one another, in the order
      they were ticked.

---

## 4. The home screen and the clock

Appearance changes only show on the home screen: make the change in the
settings, then press BACK to return to Liner's home screen and look.

- [ ] **4.1** Read the clock at the top of the home screen.
      **Expected:** the current time in large type, with the date under it in
      the "EEEE, d MMMM" form (for example "Tuesday, 10 November").
- [ ] **4.2** Wait for the clock to reach the next minute.
      **Expected:** the time changes by itself, without a refresh and without
      the screen being touched.
- [ ] **4.3** Open Liner Settings → **Appearance** and set **Clock** to
      "small".
      **Expected:** going back to the home screen, the clock and the date are
      noticeably smaller.
- [ ] **4.4** Set **Clock** to "hidden".
      **Expected:** the clock and the date are gone from the home screen; the
      app list moves up to where the clock was.
- [ ] **4.5** Set **Clock** back to "large" and **Clock format** to "24h".
      **Expected:** the time is shown in 24-hour form ("14:35", not "2:35").
- [ ] **4.6** Set **Clock format** back to "system", and set **Date** to
      "none".
      **Expected:** the clock stays, the date line disappears.
- [ ] **4.7** Set **Date** to "long".
      **Expected:** the date now includes the year ("Tuesday, 10 November
      2026").
- [ ] **4.8** Set **Date** back to "medium".
      **Expected:** the year is gone again.

---

## 5. Edit mode: drag, rename, remove, undo

- [ ] **5.1** Go back to the home screen.
      **Expected:** your labels are there, in the order you left them.
- [ ] **5.2** Press and hold one of the app labels for about a second, and
      keep holding.
      **Expected:** a red strip appears at the bottom of the screen reading
      "Drop here to remove". Liner is now in edit mode.
- [ ] **5.3** Without lifting your finger, drag the label up or down to a new
      position.
      **Expected:** a thin horizontal line appears where the label would land
      if you let go. The list itself does not shuffle under your finger.
- [ ] **5.4** Lift your finger.
      **Expected:** the label is now in the position the line showed, and the
      other labels have moved to make room.
- [ ] **5.5** Press and hold the label again, drag it onto the red "Drop here
      to remove" strip, and let go.
      **Expected:** the label disappears from the home screen and a snackbar
      appears saying "Removed <the label>", with an **"Undo"** action. The
      snackbar is short-lived — tap "Undo" within a few seconds.
- [ ] **5.6** Tap "Undo".
      **Expected:** the label is back, in the same place it was removed from.
      (If the snackbar has already gone, the label stays removed — that is
      expected too; redo the removal and undo it faster.)
- [ ] **5.7** Long-press a label and lift your finger without moving.
      **Expected:** edit mode stays on — the red strip is still there.
- [ ] **5.8** Tap the label.
      **Expected:** a dialog opens, titled with the app's name, with a
      "Label" field containing the app's own name.
- [ ] **5.9** Clear the field, type "Music", and tap **"Save"**.
      **Expected:** the home screen now shows "Music" instead of the app's
      own name.
- [ ] **5.10** Tap the renamed label (not long-press) while NOT in edit mode.
      **Expected:** the app launches. The custom name is only a label — it
      does not change what the app is. Press HOME to come back to Liner.
- [ ] **5.11** Enter edit mode again and tap "Music".
      **Expected:** the dialog opens again with "Music" in the field. Clear the
      field and tap "Save".
      **Expected:** the label is back to the app's own name — an emptied field
      means "no custom name", not "an empty label".
- [ ] **5.12** Press BACK.
      **Expected:** edit mode ends — the red strip disappears. This BACK press
      only left edit mode; the home screen is still there.

---

## 6. Sections

- [ ] **6.1** Open Liner Settings → **Sections**.
      **Expected:** a "New section title (optional)" field, an "Add section"
      button, and the line "Tap a section to rename it or delete it."
- [ ] **6.2** Type "Daily" in the field and tap **"Add section"**.
      **Expected:** a section called "Daily" is listed, showing "0 apps". The
      title field is empty again, ready for the next section.
- [ ] **6.3** Add a second section with no title — type nothing, tap "Add
      section".
      **Expected:** a second section is listed as "Untitled".
- [ ] **6.4** Tap "Move to" next to one of the apps listed under a section.
      **Expected:** a "Move to section" dialog opens, listing the other
      sections by name and the one the app is already in excluded.
- [ ] **6.5** Pick "Daily".
      **Expected:** the app is now in the "Daily" section; the other section
      no longer lists it.
- [ ] **6.6** Go back to the home screen.
      **Expected:** the apps are grouped under a "DAILY" header (section
      titles are upper-case by default) with a hairline rule under it. The
      untitled section shows **no header at all** — its apps follow the
      previous section directly.
- [ ] **6.7** Long-press the "DAILY" header.
      **Expected:** a dialog titled "Section" opens, with a "Title" field
      containing "Daily".
- [ ] **6.8** Change the title to "Every day" and tap **"Save"**.
      **Expected:** the home screen header now reads "EVERY DAY".
- [ ] **6.9** Long-press the header again and tap **"Delete"**.
      **Expected:** the dialog asks "Delete this section and its N items?"
      (N is the number of apps in it).
- [ ] **6.10** Tap **"Cancel"**.
      **Expected:** the dialog closes and the section is untouched.
- [ ] **6.11** Long-press the header, tap "Delete", and then tap **"Delete"**
      in the confirmation.
      **Expected:** the section and its apps are gone from the home screen.
- [ ] **6.12** In Liner Settings → **Sections**, tap "Up" and "Down" beside a
      section.
      **Expected:** the section moves up and down the list, and the home
      screen's sections move with it. "Up" is disabled on the first section
      and "Down" on the last.

---

## 7. The drawer

- [ ] **7.1** On the home screen, swipe **up from the bottom edge** of the
      screen.
      **Expected:** the drawer opens over the home screen, showing a "Filter
      apps" field at the top and a grid of app icons, four across (three on
      a narrow screen), each with the app's name under it.
- [ ] **7.2** Look for the apps you ticked in §3.2.
      **Expected:** they are in the drawer too, under the app's own name —
      the drawer lists the real apps, the home screen lists your labels.
- [ ] **7.3** Type "ma" in the filter field.
      **Expected:** the list narrows to the apps whose name contains "ma",
      ignoring case. The list updates as you type.
- [ ] **7.4** Clear the filter and type a name that matches nothing, e.g.
      "zzzz".
      **Expected:** the list is replaced by the message "No apps match" — not
      an empty screen.
- [ ] **7.5** Clear the filter and tap an app.
      **Expected:** the app launches and the drawer closes behind it.
- [ ] **7.6** Open the drawer again and press BACK.
      **Expected:** the drawer closes and the home screen is showing.
- [ ] **7.7** Open the drawer and tap the **gear icon** in the bottom-right.
      **Expected:** Liner Settings opens on the Appearance tab, and the drawer
      is gone.

---

## 8. Settings → Appearance

Every change here takes effect on the home screen: make the change, press
BACK to return to Liner's home screen, look, then come back to the settings.

- [ ] **8.1** Open Liner Settings → **Appearance**.
      **Expected:** the controls are in this order: Background ("Style",
      and then "Colour" or "From"/"To"/"Angle" or "Dim"), Text colour, Font,
      Text size, Weight, Letter spacing, Row spacing, Side padding, Top and
      bottom padding, Alignment, Clock, Clock format, Date, Section title
      size, Section title colour, Section title case, Section title rule,
      Icons, Icon size, Drawer style, and a "Reset appearance" button.
- [ ] **8.2** Set **Style** to "gradient", then drag **Angle** to a different
      value.
      **Expected:** the home screen's background becomes a two-colour
      gradient, and it turns as the angle changes. 0° runs top to bottom,
      90° runs left to right, 180° runs bottom to top.
- [ ] **8.3** Set **Style** to "wallpaper" and drag **Dim**.
      **Expected:** the phone's own wallpaper shows through, dimmed by the
      amount set. At 0% the wallpaper is at full brightness.
- [ ] **8.4** Set **Style** to "solid" and pick a **Colour**.
      **Expected:** the home screen has that flat colour behind the text.
- [ ] **8.5** Set **Text size** to 48 (the maximum).
      **Expected:** the labels on the home screen are much larger.
- [ ] **8.6** Set **Alignment** to "center".
      **Expected:** the list is centred on the screen instead of starting at
      the left edge.
- [ ] **8.7** Set **Alignment** to "end".
      **Expected:** the list is right-aligned.
- [ ] **8.8** Set **Alignment** back to "start", and set **Icons** to "on".
      **Expected:** each label on the home screen now has the app's icon
      beside it. Liner is icon-free by default — this is an opt-in.
- [ ] **8.9** Drag **Icon size** and **Row spacing** and **Side padding**.
      **Expected:** the icons grow and shrink, the gap between labels
      changes, and the list moves away from the edges of the screen.
- [ ] **8.10** Set **Font** to "monospace", then to "serif".
      **Expected:** the labels change to that typeface.
- [ ] **8.11** Set **Section title case** to "as typed".
      **Expected:** the section header shows "Every day" instead of
      "EVERY DAY".
- [ ] **8.12** Set **Section title rule** to "off".
      **Expected:** the hairline under the section title disappears.
- [ ] **8.13** Set **Drawer style** to "text".
      **Expected:** the drawer lists the apps as a single column of names at
      18sp, instead of a grid of icons.
- [ ] **8.14** Tap **"Reset appearance"**.
      **Expected:** every appearance setting goes back to its default — the
      clock is large again, the icons are off, the background is the dimmed
      wallpaper, the alignment is "start". The apps on the home screen are
      untouched.

---

## 9. Settings → Apps

- [ ] **9.1** Open Liner Settings → **Apps**.
      **Expected:** the "Search apps" field, the "Home screen order" block and
      the "All apps" list are showing.
- [ ] **9.2** Type a letter in "Search apps".
      **Expected:** the "All apps" list narrows to the apps whose names
      contain that letter.
- [ ] **9.3** Clear the search, tick a fourth app, then untick it again, and
      go to the home screen to look.
      **Expected:** the app appears on the home screen and disappears from it
      again.
- [ ] **9.4** Untick an app that you had renamed in §5.9, then tick it again.
      **Expected:** the app comes back **with its custom label**, in the same
      place in the list. Un-ticking and re-ticking an app does not throw away
      the name you gave it.
- [ ] **9.5** Long-press an app in the "Home screen order" block and drag it
      up or down.
      **Expected:** a thin line shows where it will land; letting go moves it
      there. The "Home screen order" block is the same order the home screen
      shows.
- [ ] **9.6** Drag an app in the "Home screen order" block up over a section
      title.
      **Expected:** the app moves into the section above when you let go.
- [ ] **9.7** Tap **"Move to section"** beside an app that is on the home
      screen.
      **Expected:** the "Move to section" dialog opens; the app's current
      section is not offered.
- [ ] **9.8** Tap **"Hide"** beside an app.
      **Expected:** the button changes to "Show", and the app is no longer in
      the drawer — but it is still on the home screen.
- [ ] **9.9** Tap **"Show"** again.
      **Expected:** the app is back in the drawer.
- [ ] **9.10** Tap **"Refresh"**.
      **Expected:** the app list is queried again. Nothing visibly changes
      unless an app was installed while the settings were open.

---

## 10. Settings → Import / Export

- [ ] **10.1** Open Liner Settings → **Import / Export**.
      **Expected:** an "Export" heading with "Share layout as a file" and
      "Copy layout to clipboard", then an "Import" heading with "Import from a
      file" and "Import from clipboard", and the note "Importing replaces
      your current layout. Export first if you want a backup."
- [ ] **10.2** Tap **"Copy layout to clipboard"**.
      **Expected:** nothing visible happens — the layout is on the clipboard.
- [ ] **10.3** Go back to the home screen, rename one of the labels (as in
      §5.9), and come back to Liner Settings → **Import / Export**, so the
      current layout differs from the copy on the clipboard.
- [ ] **10.4** Tap **"Import from clipboard"**.
      **Expected:** a dialog titled "Import this layout?" opens, listing what
      the import would change — for example "Adds apps: com.example.foo" or
      "Replaces the theme". If nothing would change, it says "No visible
      changes".
- [ ] **10.5** Tap **"Cancel"**.
      **Expected:** the dialog closes and the layout is unchanged.
- [ ] **10.6** Tap **"Import from clipboard"** again and then tap
      **"Import"**.
      **Expected:** the dialog closes and the home screen matches the layout
      you copied in step 10.2 — the change you made in 10.3 is gone.
- [ ] **10.7** Tap **"Share layout as a file"**.
      **Expected:** the Android share sheet opens. The file it offers is named
      `liner-layout-<yyyyMMdd-HHmmss>.json` — for example
      `liner-layout-20261110-143512.json`.
- [ ] **10.8** Share the file to a place you can reach later (a file manager,
      a cloud drive, or your own computer).
      **Expected:** the file arrives and its contents are the layout document
      itself — the JSON starts with `{` and holds the sections and the theme,
      with no wrapper object around it.
- [ ] **10.9** Change the layout again, then use "Import from a file" and
      pick the file you exported.
      **Expected:** the summary dialog appears again, and importing restores
      the exported layout.

---

## 11. Settings → Advanced

- [ ] **11.1** Open Liner Settings → **Advanced**.
      **Expected:** the tab is showing — the default-launcher banner (unless
      Liner already is the default), the auto-prune switch and the "Reset
      everything" button.
- [ ] **11.2** Flip the switch beside "Remove uninstalled apps
      automatically".
      **Expected:** the sentence under it changes to "Uninstalled apps are
      removed from your home screen automatically." (from "Uninstalled apps
      stay on your home screen, greyed out.").
- [ ] **11.3** Turn the switch back off, so the rest of the script sees the
      default behaviour.
- [ ] **11.4** Tap **"Reset everything"**.
      **Expected:** a dialog asks "Reset everything?" and warns "Your apps,
      your sections and your theme go back to their defaults. This cannot be
      undone."
- [ ] **11.5** Tap **"Cancel"**.
      **Expected:** the dialog closes and nothing is reset.
- [ ] **11.6** Tap "Reset everything" again and then **"Reset"**.
      **Expected:** the home screen is empty again, back to "No apps on your
      home screen yet", and the theme is back to its defaults.
- [ ] **11.7** Re-add three apps (as in §3.2) before continuing.

---

## 12. Resilience

- [ ] **12.1** With three apps on the home screen, uninstall one of them from
      the phone's own app settings (not from Liner), then come back to Liner.
      **Expected:** its entry **stays** on the home screen, greyed out
      (dimmer than the others). Liner does not silently delete it.
- [ ] **12.2** Tap the greyed-out entry.
      **Expected:** a toast says "App not installed". Nothing launches, and
      Liner does not crash.
- [ ] **12.3** Go to Liner Settings → **Apps** and tap **"Refresh"**, then go
      back to the home screen.
      **Expected:** the uninstalled app is no longer in the "All apps" list —
      it is not installed any more — and its home entry is still there,
      still greyed out.
- [ ] **12.4** Turn on "Remove uninstalled apps automatically" in
      **Advanced**, then press BACK to return to the home screen.
      **Expected:** the uninstalled app's entry is gone from the home
      screen, because you asked for that. (The prune runs when Liner comes
      back to the foreground, so it is the return to the home screen that
      triggers it — not the switch itself.)
- [ ] **12.5** Turn the switch off again.
- [ ] **12.6** With a layout in place, use the phone's "Force stop" on Liner
      (Android Settings → Apps → Liner → Force stop), then open Liner again.
      **Expected:** the home screen is exactly as you left it — the same apps,
      the same order, the same custom names, the same theme.
- [ ] **12.7** Rotate the phone to landscape and back again.
      **Expected:** nothing is lost and nothing crashes. The layout is
      unchanged.
- [ ] **12.8** Open the drawer, type a filter, then rotate the phone.
      **Expected:** the filter text is still there after the rotation.

---

## 13. Failure handling

- [ ] **13.1** Copy some plain text that is **not** a layout — for example
      the sentence "this is not a layout" — to the clipboard.
      **Expected:** the text is on the clipboard. (Paste it into a message
      app to check that it really is there.)
- [ ] **13.2** Open Liner Settings → **Import / Export** and tap **"Import
      from clipboard"**.
      **Expected:** a dialog titled "Import failed" appears, with the message
      "That file is not readable as a Liner layout." The layout is **not**
      changed, and Liner does not crash.
- [ ] **13.3** *(needs adb — optional)* Corrupt the stored layout file and
      check that Liner survives it. This step needs the **debug** build and a
      cable:
      ```
      adb shell am force-stop dev.liner.launcher.debug
      adb shell run-as dev.liner.launcher.debug sh -c \
        'mkdir -p files/datastore && printf "not a preferences file" > files/datastore/liner_layout.preferences_pb'
      ```
      then open Liner from the app list.
      **Expected:** a toast says "Your saved layout could not be read, so
      Liner started with defaults.", the home screen is back to the default
      empty state, and Liner does not crash. The saved layout is lost — the
      stored file is unreadable, so there is nothing to recover from it.
      (The recovery copy Liner keeps in `files/liner-recovered-layout.json`
      is only written when the stored file can be read but the *document
      inside it* cannot be decoded — a case that can only be produced by
      hand-editing the stored file. The repository's handling of it is
      covered by the JVM unit tests.)
- [ ] **13.4** *(needs adb — optional)* Check that a failed save is
      reported and does not take Liner down. This step needs the **debug**
      build and a cable:
      ```
      adb shell run-as dev.liner.launcher.debug chmod 500 files/datastore
      ```
      Now change something on the home screen (rename an app).
      **Expected:** a toast says "Your changes could not be saved. Try
      again." The app keeps working — the label on screen still changes, and
      Liner does not crash. Then put the permissions back:
      ```
      adb shell run-as dev.liner.launcher.debug chmod 700 files/datastore
      ```
      and make another change.
      **Expected:** the change is saved, and no error toast appears.
- [ ] **13.5** *(optional)* Reject a layout from a newer version of Liner.
      Copy this JSON to the clipboard:
      ```
      {"version":99,"sections":[],"theme":{}}
      ```
      then use "Import from clipboard".
      **Expected:** a dialog titled "Import failed" says "That layout was
      made by a newer version of Liner, so this version cannot read it." The
      current layout is untouched.

---

## 14. Reporting a failure

If a step fails, copy the block below into your report and fill it in. A
screenshot or a screen recording of the moment is worth more than a
description of it.

```
Liner manual test report

Device:            (e.g. Pixel 7, Android 14)
Build tested:      (e.g. liner-release.apk, or "debug build from <commit>")
Tester / date:

Failed step:       (e.g. 5.5 — drag to the trash zone)
What I did:        (what you tapped, typed and dragged)
What I expected:   (what the script says should happen)
What happened:     (what actually happened)
Other steps that failed: (list the step numbers, or "none")

Steps skipped:     (e.g. 13.3, 13.4 — no adb available)
```

Also report, even if every step passed:

- Any step whose **expected result was not what the script says** — even if
  the app's behaviour looked reasonable.
- Any crash, ANR, or system dialog that appeared on its own.
- Anything in the app that felt wrong but did not have a step for it.

---

## What this script does not cover

- The automated instrumentation tests in `app/src/androidTest/` (home
  registration, app enumeration, the document round trip, repository
  persistence, the drawer list, the filter and icon loading, and — in
  `HomeInteractionTest` — a long-press drag that moves a row to the slot it
  was dropped on, and a swipe up from the bottom edge that opens the
  drawer). They are shipped unverified — no emulator was available when
  Liner was built — so running them on a device is a good companion to this
  script:
  ```
  ./gradlew :app:connectedDebugAndroidTest
  ```
- Fonts other than the four Liner offers, and the exact rendering of every
  background style on every wallpaper.
- Behaviour on Android versions older than 8.0, or on devices from
  manufacturers whose launchers are unusual.
