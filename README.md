# temaki

**Wrap any interactive terminal agent in an OpenAI-compatible API.**

Many capable AI agents run as an interactive terminal program — a TUI or REPL — and
expose no clean programmatic interface. `temaki` drives such a session through a terminal
multiplexer and presents it as a standard OpenAI-style `POST /v1/chat/completions`
endpoint, so any OpenAI-compatible client, script, or orchestrator can talk to it like an
ordinary model backend.

The hard part of bridging a terminal UI is *reading* it: raw panes are full of ANSI
control codes, spinners, partial redraws, and prompts, and there is no clean "the turn is
finished" signal. `temaki` delegates that to a small **local marshaller model** that
watches the session and answers two questions —

- *Is the agent working, waiting for input, done, or errored?*
- *What is the actual response text, stripped of the terminal noise?*

— turning a messy live pane into a clean completion.

> **The name.** A *temaki* is a hand-rolled sushi cone. `temaki` hand-rolls a raw terminal
> session into something you can pick up and consume through a normal API.

## Status

Early — **design + spike only.** This repository currently holds the design
(`README.md`, `ROADMAP.md`) and the original proof-of-concept (`prototype/`) that
demonstrated the round-trip. The productized server and marshaller described in the
roadmap are not built yet.

## How it works (target design)

```
OpenAI-compatible client
        │  POST /v1/chat/completions
        ▼
   temaki server ──► terminal multiplexer ──► interactive agent (TUI / REPL)
        ▲                                            │
        └────────── marshaller model ◄───────────────┘
              turn-state detection + response extraction
```

## Why

- Give terminal-only agents a programmatic, **stateful** interface — the session stays
  warm across calls, with no per-call cold start.
- Compose several different agents behind **one uniform API**.
- Drive tools that ship **no SDK**, for automation and integration testing.
- Stay **local-first** — the bridge itself has no cloud dependency and holds no keys.

## Non-goals

- Not a scraper for graphical apps. An application you control should expose its own
  control API; `temaki` exists for interactive programs you *don't* control and can't
  modify.
- Not a place for secrets. The bridge embeds no API keys or per-user credentials.

## License

MIT — see [`LICENSE`](LICENSE).
