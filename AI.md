# AI.md — temaki

Canonical agent guide for this repository. `CLAUDE.md`, `AGENTS.md`, and `GEMINI.md` are
thin redirects to this file.

## What this is
`temaki` wraps an interactive terminal agent (TUI / REPL) in an OpenAI-compatible API,
using a small local marshaller model to detect turn-completion and extract clean
responses. See [`README.md`](README.md) for the overview and [`ROADMAP.md`](ROADMAP.md)
for the plan. The repo is currently design + a Phase-0 spike under `prototype/`.

## Repo conventions
- **No AI attribution** anywhere — no `Co-Authored-By: <AI>`, no "Generated with …", no
  robot emoji, no AI/model name in commits, code, or docs.
- **License:** MIT, © Salient Vision Technologies, LLC.
- **Public repo — strict hygiene.** Never commit secrets, API keys, tokens, personal
  identifiers, private hostnames or IP addresses, or internal infrastructure details. The
  bridge holds no keys of its own. Files matching `*.local.*` are intentionally gitignored
  and stay on the author's machine — do not commit them or echo their contents.
- **Layout:** `README.md` (overview) · `ROADMAP.md` (phases) · `prototype/` (the original
  spike — unmaintained reference, not the product) · `LICENSE`.
- **Commits:** imperative and concise; scope-prefix when it helps.
