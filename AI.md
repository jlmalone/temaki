<!-- ATTRIBUTION-GUARD:v1 -->
> ⛔ **NO ATTRIBUTION (non-negotiable).** Never write authorship or credit of any kind into commits, PRs, code, or docs: no `Co-Authored-By`, no AI / model / agent name, no `🤖 Generated with…`, no `Author:` / `Prepared by:` / `Reviewed by:`. Say nothing about attribution at all. If this repo already contains any, flag it. Full rule: top of `~/AI.md`.

# AI.md — temaki

Canonical agent guide for this repository. `CLAUDE.md`, `AGENTS.md`, and `GEMINI.md` are
thin redirects to this file.

## What this is
`temaki` wraps an interactive terminal agent (TUI / REPL) in an OpenAI-compatible API,
using a small local marshaller model to detect turn-completion and extract clean
responses. See [`README.md`](README.md) for the overview and [`ROADMAP.md`](ROADMAP.md)
for the plan. The marshaller core (Phase 1) is implemented in Kotlin under `src/`; the
session driver and server are next. `prototype/` holds the original Phase-0 spike.

## Repo conventions
- **No AI attribution** anywhere — no `Co-Authored-By: <AI>`, no "Generated with …", no
  robot emoji, no AI/model name in commits, code, or docs.
- **License:** MIT, © Salient Vision Technologies, LLC.
- **Public repo — strict hygiene.** Never commit secrets, API keys, tokens, personal
  identifiers, private hostnames or IP addresses, or internal infrastructure details. The
  bridge holds no keys of its own. Files matching `*.local.*` are intentionally gitignored
  and stay on the author's machine — do not commit them or echo their contents.
- **Layout:** `README.md` (overview) · `ROADMAP.md` (phases) · `src/` (the product —
  Kotlin/Gradle, JDK 21) · `prototype/` (the original spike — unmaintained reference, not the
  product) · `.env.example` (config template; real `.env` is gitignored) · `LICENSE`.
- **Build:** `./gradlew test` (hermetic — fixtures only, no model/agent needed),
  `./gradlew installDist` → `build/install/marshal/bin/marshal`.
- **Stack:** Kotlin/JVM, Gradle (wrapper pinned), Ktor (server, later phases). Marshaller calls a
  **local** OpenAI-compatible endpoint only; the bridge holds no keys and sends pane content
  nowhere else.
- **Commits:** imperative and concise; scope-prefix when it helps.
