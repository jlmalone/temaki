# temaki — roadmap

Status: ✅ done · 🔨 in progress · ⬜ planned

## Phase 0 — Spike ✅
Prove that an external process can drive an interactive agent session inside a terminal
multiplexer and observe a real result, round-trip. See [`prototype/`](prototype/).

**Outcome:** confirmed. A prompt sent into a multiplexed session produced a real,
observable side effect captured back out. Turn-completion was guessed by a naive
"pane unchanged for N ticks" heuristic — good enough to prove the concept, not enough to
ship.

## Phase 1 — Marshaller ✅
Replace the naive heuristic with a small **local model** that:
- classifies session state — `working | awaiting_input | done | error`, and
- extracts the response payload from the raw pane (strips ANSI, spinners, prompts).

Pluggable model; runs locally; this is the core of the project.

**Outcome:** shipped. One `Marshaller.assess(prompt, paneSnapshots) -> Assessment` interface with
two implementations behind it: a deterministic `HeuristicMarshaller` (pane-stability + ready/
continuation-prompt detection + open-input and error recognition; always available, no network) and
an `LlmMarshaller` that asks a local OpenAI-compatible model for a structured JSON verdict and falls
back to the heuristic on any failure. ANSI/OSC stripping and carriage-return resolution live in
`Ansi`. Verified against checked-in fixtures captured from real `bc` and `python3 -q` sessions plus
synthetic messy-TUI panes; `marshal replay <fixtureDir>` prints the verdict for any capture.

## Phase 2 — Server ✅
`POST /v1/chat/completions` and `GET /v1/models`. Token-gated, bound to loopback.
Streaming via SSE. One in-flight turn per backend session, with a request queue.

**Outcome:** shipped, on top of a live session driver. The driver spawns a tmux session running
the target agent directly as the pane process (no shell-prompt noise), injects a prompt, polls
`capture-pane`, and feeds the growing snapshot series to the marshaller until a terminal state —
trusting a verdict only once the pane has changed from the pre-send baseline. The Ktor server
(loopback-bound, bearer-gated) exposes `GET /v1/models` and `POST /v1/chat/completions`
(non-streaming and SSE), serializing turns per backend session through a mutex. A request maps to
one turn (last user message injected) and returns an OpenAI-shaped completion; the marshaller
verdict rides along in a client-ignored `x_temaki` field. Verified live: `curl` against a
tmux-wrapped `bc -q` returns a well-formed completion (and SSE stream); `marshal serve` /
`marshal drive` exercise it end to end against free local REPLs (`bc`, `python3 -q`).

## Phase 3 — Session lifecycle ⬜
Spawn / attach / detach / health-check / restart. Multiple concurrent sessions.
Per-session configuration.

## Phase 4 — Adapters ⬜
A per-agent adapter contract: how to inject a prompt, how to recognize the ready prompt,
how to read a response. Config-driven, so adding a new terminal agent is a config file
rather than code.

## Phase 5 — Integration & examples ⬜
Drop-in backend for OpenAI-compatible orchestrators; worked examples and tests.

## Non-goals
- GUI / desktop automation — applications you own should expose their own control API.
- Embedding secrets or per-user keys anywhere in the bridge.
