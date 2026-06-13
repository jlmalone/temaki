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

Early, and under active construction. The **marshaller** — the core that reads a live pane
and decides turn-state while extracting the clean response — is built and tested (Phase 1).
The live session driver and the OpenAI-compatible server come next. See
[`ROADMAP.md`](ROADMAP.md) for the plan and [`prototype/`](prototype/) for the original
proof-of-concept that demonstrated the round-trip.

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

## Build & develop

Requires a JDK 21 toolchain. The marshaller and its CLI need no model and no live agent —
they run entirely against checked-in fixtures, so the suite is hermetic.

```bash
./gradlew test            # unit + fixture-replay suite
./gradlew installDist     # build the `marshal` CLI
build/install/marshal/bin/marshal replay src/test/resources/fixtures/bc-arithmetic
```

`marshal replay <fixtureDir>` classifies a captured turn and prints the state plus the
extracted response. Pass `--llm` to route the verdict through a local model instead of the
deterministic heuristic; configure the endpoint with the `TEMAKI_MARSHALLER_*` variables
documented in [`.env.example`](.env.example). Pane content is only ever sent to that local
endpoint.

`marshal drive --agent "<cmd>" --prompt "<text>"` drives a *live* session: it launches the
agent command in a tmux pane, injects each prompt, and reports the marshaller's verdict — try
`bc -q` or `python3 -q` to watch the full round-trip with no paid backend. Repeat `--prompt`
for sequential turns.

## License

MIT — see [`LICENSE`](LICENSE).
