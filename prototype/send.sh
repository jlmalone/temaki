#!/bin/bash
# Send a prompt into the running session, wait for the pane to settle, print the capture.
# NOTE: settle-detection here is a naive placeholder. The product replaces it with a
# local marshaller model that understands turn-state and extracts the clean response.
set -euo pipefail

SESSION="${TEMAKI_SESSION:-temaki}"
[ -z "${1:-}" ] && { echo "usage: send.sh <prompt>" >&2; exit 1; }
PROMPT="$1"

tmux send-keys -t "$SESSION:main" C-c
sleep 0.5
tmux send-keys -t "$SESSION:main" "$PROMPT"
sleep 0.3
tmux send-keys -t "$SESSION:main" Enter
sleep 1.0

prev=""
stable=0
while [ "$stable" -lt 4 ]; do
  sleep 1.5
  cur="$(tmux capture-pane -t "$SESSION:main" -p)"
  if [ "$cur" == "$prev" ]; then
    stable=$((stable + 1))
  else
    stable=0
    prev="$cur"
  fi
done

tmux capture-pane -t "$SESSION:main" -p
