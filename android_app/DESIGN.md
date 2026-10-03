# Smart Notif - Pearl + Cobalt

Selected by the user on 2026-10-03: direction B from `design-review/2026-10-03/directions.html`.

## Visual direction

A light pearl device companion with restrained frosted glass, cobalt actions,
and clear groups of controls. The matching dark theme uses blue graphite,
pale cobalt actions, and the same typography and hierarchy.

- Light canvas: #EEF0F4; action: #245BC6; primary text: #18202C.
- Dark canvas: #151C29; action: #AECFFF; primary text: #F3F6FC.
- Success, warning, and failure colors are separate from the action color.
- Both themes use semantic color resources for surfaces, edges, inputs,
  switches, disabled controls, ripple, navigation, and system bars.

## Typography and icons

Plus Jakarta Sans is bundled in regular, medium, semibold, and bold weights.
The font family declares framework and AppCompat attributes to support API24.
Headings use 22sp semibold; device identity 19sp; sections 16-17sp;
control labels 15-16sp; supporting copy 14sp; metadata 12sp.
Technical MAC addresses and payload data may use native monospace.

Google Material outlined icons provide the navigation and functional glyphs.
Launcher icons use a vector memory chip with pearl/cobalt coloring, including
an adaptive and monochrome layer. No emoji or network-loaded font is needed.
Font and icon licenses are included in `app/src/main/assets`.

## Material

`GlassBackdropView` builds the static pearl/blue canvas and caches a blurred
bitmap at quarter resolution when viewport size changes. `GlassPanel` and
`GlassSurfaceDrawable` sample the blurred canvas in window coordinates.
They do not capture app content or other applications. The glass has a
white tint, subtle edge, and restrained elevation. Dark glass uses tinted
blue graphite. Main panels use 22dp radius, insets 18dp, buttons 14dp.

## Layout

- Beranda (page heading: Smart Notif): device identity, connection status, direct find/manage action,
  actual service/permission readiness, four notification filters, and a
  short latest-activity preview.
- Alat: message test, time/battery, weather, and navigation. The last three
  modules expand on demand. Shared send feedback sits above the modules.
- Pengaturan: foreground service controls, notification access, restricted
  settings help, application info, and Bluetooth management.
- Activity detail: full log list and clear control; the on-screen back button
  and Android Back return to Beranda.

Each main tab owns its own scroll surface. Content width is capped at 680dp;
the navigation width at 440dp. Bottom clearance follows measured navigation
height. Buttons have at least 52dp height; switches at least 52dp.
At fontScale >= 1.5 the decorative hero icon is hidden, giving the device
heading full width. Text, buttons, and panels grow vertically.

## Interaction and feedback

Tab transitions are short and respect system animation settings. Current tab
and expandable panels survive activity recreation. Permission-granted scan
requests resume without requiring a second press. Settings reflect actual
foreground service and notification access state.

Test submissions are labeled queued or sending, then updated by the BLE
write-result callback. A successful write is described as sent to ESP32;
only the existing device ACK reports that OLED display succeeded. Empty
messages show a field error. Latest activity is a short preview; full sender
and message text remain available in activity detail. Duplicate payload
results update the oldest pending log entry first.

## Verification and limits

See `anti-slop/audit-007-2026-10-03.md` for automated and native checks.
`design-review/2026-10-03/native/index.html` shows real Android emulator
screenshots; the earlier directions board remains conceptual.

The emulator is API28 and does not provide a working BLE adapter. Actual
ESP32 transport and target-OLED behavior require physical hardware.
Lint still reports the project's existing expired targetSdk32 finding and
other repository warnings; dependency or target-SDK migration is outside
this visual change.
