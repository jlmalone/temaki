# prototype — Phase 0 spike

The original throwaway proof-of-concept. It showed that an external script can drive an
interactive agent session running inside a `tmux` multiplexer and capture the result, by
sending keystrokes into the pane and reading the pane back out.

It is intentionally minimal and is **not** the product: there is no API server and no
marshaller, and turn-completion is guessed by "pane unchanged for a few ticks." The real
design replaces that heuristic with a small local marshaller model — see
[`../ROADMAP.md`](../ROADMAP.md).

Kept here as a record of what was proven, not as maintained code.

## Files
- `start-session.sh` — start a detached multiplexer session running the target agent.
- `send.sh` — send a prompt into the session, wait for the pane to settle, print it.

Set `TEMAKI_AGENT_CMD` to the terminal-agent command you want to wrap, e.g.:

```bash
export TEMAKI_AGENT_CMD="your-terminal-agent"
./start-session.sh
./send.sh "hello"
```
