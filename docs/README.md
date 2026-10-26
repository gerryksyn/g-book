# docs/: the G-Book landing page

This folder is the published site. GitHub Pages serves it from `main` / `docs`.

**Live:** https://gerryksyn.github.io/g-book/

```
docs/
├── index.html      the whole page: inline CSS and JS, no build step
├── .nojekyll       skip Jekyll processing
├── assets/
│   ├── shots/      app screenshots (700px WebP)
│   ├── apk/        the shipped APK + SHA256SUMS.txt
│   └── icon-*.png  favicon, touch icon, social preview
└── README.md
```

## Editing

Edit `index.html` directly and push to `main`. Pages rebuilds in under a minute. There is
no framework, no npm install, no compile step. What you write is what ships.

Keep paths relative (`assets/...`, not `/assets/...`) so the site keeps working under the
`/g-book/` subpath.

## Things to keep intact

- **`.nojekyll`**: without it, Jekyll eats files starting with an underscore.
- **The A / A+ / A++ text size control.** The audience is low-vision readers; the page has
  to be resizable.
- **Alt text on every screenshot** and the skip link at the top of the body.
- **`prefers-reduced-motion`** handling: the scroll reveals and the floating phone must
  stay switchable off.
- **`docs/` must not be renamed.** Pages is configured to serve this folder.

## Replacing the APK on a new release

1. Drop the new `G-Book-vX.Y.Z.apk` into `assets/apk/` and update `SHA256SUMS.txt`.
2. Update the version strings in `index.html` (hero chips, download card, SHA-256, changelog).
3. Update the table in the root `README.md`.
4. Commit and push.

Old APKs pile up in git history permanently, so delete the previous one from
`assets/apk/` unless you specifically want to keep it downloadable.
