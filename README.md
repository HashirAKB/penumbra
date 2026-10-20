# Penumbra

**A free, open-source drawing and coloring app for Android.** Pressure-sensitive brushes, layers,
endless coloring pages, photo-to-drawing, stroke-by-stroke replay and trace-along lessons — on any
Android 10+ phone or tablet, with or without a stylus. No account, no ads, no tracking, and your
drawings are saved in an open file format.

> Built as an open alternative to Samsung's PENUP for people who don't have (or don't want to be
> tied to) a Galaxy device. Penumbra is an independent project, not affiliated with or endorsed by
> Samsung. It was written from scratch from public descriptions of what PENUP does; it contains no
> Samsung code, artwork or content.

## Install

Download **Penumbra-nightly.apk** from the [Nightly release](../../releases/tag/nightly) on your
phone and open it (allow "install unknown apps" for your browser when asked). Every push to `main`
builds a new nightly; each one installs over the last and keeps your drawings.

## What it does

### Drawing
- **12 brushes** with real pen behavior: ink pen, fineliner, pencil and soft graphite (tilt the pen
  to shade with the side of the lead), marker, brush pen, calligraphy nib, watercolor, airbrush,
  crayon, spray can and a pixel-art pen — plus hard/soft erasers and a smudge blender.
- **Pressure, tilt and orientation** from S Pen, Wacom-style and USI pens; finger strokes get a
  simulated pressure from drawing speed.
- **Low-latency inking:** unbuffered input, every historical touch sample, and Android 14's motion
  prediction to draw ahead of the pen.
- **Palm rejection:** once a pen is seen, fingers pan/zoom/rotate and palms are ignored
  (configurable).
- **S Pen extras:** hover to preview the brush size; hold the side button to erase or pick a color.
- **Stabilizer** for smooth lines, and **hold-to-snap** — pause at the end of a stroke and it becomes
  a clean line, circle or ellipse.
- **Symmetry:** mirror, four-way, radial (2–24 segments) and kaleidoscope modes.
- **Layers** (up to 64, depending on canvas size and memory) with opacity, 17 blend modes, alpha
  lock, lock, duplicate, merge, reorder, clear; adjustable paper color.
- Move / scale / rotate a layer, unlimited-feeling undo (memory-budgeted), two-finger-tap undo,
  three-finger-tap redo, long-press eyedropper with a color loupe.
- Pinch zoom (5%–6400%), rotation that snaps to right angles, pixel-crisp rendering when zoomed in.
- Works with **Samsung DeX** / keyboard and mouse: scroll-wheel zoom and shortcuts
  (Ctrl+Z, Ctrl+Shift+Z, B, E, S, G, I, `[`, `]`, 0).

### Coloring
- **Endless coloring pages** generated on the device in five styles — mandala, stained glass,
  geometric quilt, spirograph rosette and flower bloom. Shuffle for new ones any time.
- **Smart fill** that closes small gaps in line art so color doesn't leak, and tucks color under
  anti-aliased lines so there's no white halo.
- Palettes (basics, pastel, skin tones, earth, tropical), recent colors, HSV wheel and hex entry.

### Photo drawing
- Use a photo as a faded, locked layer to trace over.
- Turn a photo into **clean line art**, a **pencil sketch**, or **paint-by-regions** outlines, with
  live preview and detail/strength sliders — then color it like a coloring page.

### Replay, lessons and timelapse
- Every drawing records how it was made. **Replay** any drawing stroke by stroke, at up to 16× speed,
  and scrub through it.
- **Trace along:** turn any drawing — yours or one a friend sends you — into a step-by-step lesson.
  The next stroke is shown as a dashed guide, the brush and color switch automatically, and it
  advances when you lift the pen.
- **Built-in lessons** (warm-up drills, mandala, flowers, spirograph, stained glass) are generated
  procedurally, so there's always a fresh one.
- Export a **timelapse video** (MP4) of any drawing.

### Files and sharing
- Drawings are saved as **OpenRaster (.ora)** — layers, names, opacity, visibility and blend modes
  open in **Krita, GIMP and MyPaint**. The stroke history travels inside the file, so replay and
  trace-along work on any device.
- Export PNG to your gallery, share images, share .ora files, open .ora/PNG/JPEG from other apps.
- Autosave, atomic saves, and Android backup / device-transfer of your drawings.

## Compared with PENUP

Based on Samsung's public description of PENUP's features.

| | PENUP | Penumbra |
|---|---|---|
| Runs on | Samsung Galaxy devices, Windows | Any Android 10+ device |
| Account | Samsung account for community features | None |
| Drawing with pressure | ✓ | ✓ plus tilt shading, prediction, hover preview |
| Coloring | Curated designs | Unlimited generated designs + gap-closing fill |
| Live drawing / tracing | Trace along tutorial videos | Trace along *any* drawing, step by step |
| Photo drawing | ✓ | ✓ outline, sketch and region styles |
| Drafts across devices | `.penup` files, Samsung-only | `.ora`, opens in Krita/GIMP/MyPaint |
| Timelapse export | — | ✓ MP4 |
| Social feed, challenges, drawing comments | ✓ | Planned (phase 2) |
| Source code | Closed | GPL-3.0 |

## Roadmap

- **Phase 2 — community:** a self-hostable gallery server with following, challenges and drawing
  replies, so nobody depends on one company's servers.
- Selection tools (lasso, rectangle) and copy/paste.
- Brush editor for custom brushes; import/export brush packs.
- Reference image window; animation onion-skinning.
- F-Droid release with reproducible builds.

## Building

Requires JDK 17 and the Android SDK (API 35).

```sh
./gradlew testDebugUnitTest assembleDebug     # tests + debug APK
./gradlew assembleRelease                     # minified release APK
```

The app uses only the Android framework — no AndroidX or third-party libraries — so the APK is
tiny and the code is easy to read:

- `core/` — pure Kotlin, unit-tested on the JVM: brush dab generation, stabilizer, shape
  recognition, flood fill, line-art filters, coloring-page and lesson generators, the stroke journal.
- `engine/` — layers, compositing, undo history, dab rendering, replay and video encoding.
- `io/` — OpenRaster read/write, gallery storage, export and sharing.
- `ui/` — canvas view (input, gestures, live stroke preview), panels, screens.

`test/compile-android.sh` type-checks every source file against the Android API stubs without
Gradle, which is handy in constrained environments.

### Signing

Nightly builds are signed with a **public test key** (`keystore/nightly.jks`) so updates install
over each other. Because the key is public, treat nightlies as test builds. For your own releases,
set `PENUMBRA_KEYSTORE`, `PENUMBRA_KEYSTORE_PASSWORD`, `PENUMBRA_KEY_ALIAS` and
`PENUMBRA_KEY_PASSWORD` to sign with a private key.

## License

GPL-3.0 — see [LICENSE](LICENSE). You're free to use, study, change and share Penumbra; derivative
works must stay open source.
