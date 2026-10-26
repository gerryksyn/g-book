# G-Book

An Android ebook reader that reads to you. Built for low vision, from the first line of code.

**Landing page:** https://gerryksyn.github.io/g-book/ (once Pages is enabled)

---

## What it is

G-Book is a native Android reading app with text-to-speech built in. Large type, real
sentence highlighting, EPUB and PDF support, and nothing between you and the words.

I have low vision, so this is the reader I actually needed — then kept rebuilding every
time something got in the way of the words.

## Features

- **Reads aloud, properly.** TTS runs in a foreground service with a media session, so
  playback survives screen-off and shows a notification with cover art, chapter, and
  previous / play / next-sentence controls.
- **Sentence-level highlight.** The spoken sentence is lit in light purple and kept in
  view as the reader scrolls.
- **Type you actually control.** 16–44sp, weight and line spacing in quarter increments,
  serif or sans, centred or justified, one to three blank lines between paragraphs.
- **True black, real contrast.** AMOLED-black surface by default, with an ink-on-paper
  high-contrast mode when you want the opposite.
- **EPUB done right.** EPUB 3 navigation documents and EPUB 2 NCX both parse, the OPF
  spine decides reading order, chapter anchors are exact.
- **PDF, too.** Text is extracted for reading and speaking, first page rendered as cover.
- **One source of progress.** Reader, library, stats, notification, shares and CSV backups
  all read from the same model.
- **Stats that count listening.** Today's spoken time, current book, reading days this month.

## Download

| | |
|---|---|
| Version | 0.6.2 (versionCode 21) |
| Requires | Android 8.0 (API 26) or newer |
| Targets | Android 14 (API 34) |
| Package | `id.gerry.gbook` |
| Size | 24.2 MB |
| SHA-256 | `d17f25f367662c17a5d282fcb9a2a8e70cb6bc9b0f90ad3a1b17796fab4b713d` |

Grab `assets/apk/G-Book-v0.6.2-debug.apk` from this repo, or use the button on the
landing page. The APK is debug-signed and distributed outside any app store, so Android
will ask you to allow installs from this source.

## Repo layout

```
.
├── index.html          the landing page (single file, no build step)
├── assets/
│   ├── shots/          app screenshots, 700px WebP
│   ├── apk/            the signed debug APK + SHA256SUMS.txt
│   └── icon-*.png      favicon and social preview
├── .nojekyll           skip Jekyll processing on GitHub Pages
└── README.md
```

The page is plain HTML, CSS and a little vanilla JS. No framework, no dependencies,
no build step. Edit `index.html` and push.

## Tech

Kotlin · Jetpack Compose · Material 3 · MediaSessionCompat · PdfBox-Android · Gson ·
JUnit. minSdk 26, targetSdk 34, JVM 17. Unit tests cover EPUB navigation parsing,
chapter progress and the CSV backup format.

The Kotlin source lives outside this repo for now — this repo holds the page and the
build. Say hi if you want the source.

## Changelog

**0.6.2** — reader top bar reworked so Share and Settings stay fully visible; one-line
ellipsis on titles; bottom-origin slide-up on book open; motion timed to the highest
supported refresh rate; Library is the destination for every fresh launch.

**0.6.1** — durable exact TTS resume, restored sentence scrolled into view, unified
motion and ripple polish.

**0.6.0** — reader settings split into Page / TTS tabs; library detail typography
auto-sizes before ellipsizing.

**0.5.7** — Page and TTS settings separated, quarter-step weight and pitch, details sheet
adapts to locked font scale.

## Accessibility of the page itself

The landing page follows the same rules as the app: an A / A+ / A++ text size control that
remembers your choice, a skip link, visible focus rings, real alt text on every
screenshot, and `prefers-reduced-motion` support.

---

A personal project by Gerry. Free to use, works offline, collects nothing.
