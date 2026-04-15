# docs/user/

This folder is the published source for [rousecontext.com](https://rousecontext.com). It is served via GitHub Pages using Jekyll.

Everything here is written for end users — people installing and using the Android app, not contributors reading engineering design docs.

Engineering documentation (design docs, audits, workflow) lives one level up in `docs/`. That content is intentionally not published; if we want a topic to be public, we rewrite it for a user audience and add a page here.

## Layout

- `_config.yml` — Jekyll configuration (theme, title, nav defaults).
- `CNAME` — tells GitHub Pages to serve this site at the `rousecontext.com` apex.
- `index.md` — landing page.
- `security.md`, `privacy.md`, `integrations.md`, `troubleshooting.md`, `faq.md` — individual topic pages.

## Editing

Each page is a Markdown file with a small YAML front matter block at the top specifying the nav order. Keep pages short (aim for 200–500 words on the first pass). Prefer plain English over jargon, and unpack any technical term you do use.

## Publishing

GitHub Pages builds automatically when `main` changes. The Pages source is configured in the repository Settings → Pages to publish from `main` with the `/docs/user` folder. No build step locally is required for contributors editing content.
