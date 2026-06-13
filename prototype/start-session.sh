#!/bin/bash
# Start a detached tmux session running the target terminal agent.
# Configure the agent command via TEMAKI_AGENT_CMD.
set -euo pipefail

SESSION="${TEMAKI_SESSION:-temaki}"
AGENT_CMD="${TEMAKI_AGENT_CMD:?set TEMAKI_AGENT_CMD to the terminal-agent command to wrap}"

tmux kill-session -t "$SESSION" 2>/dev/null || true
tmux new-session -d -s "$SESSION" -n main
tmux send-keys -t "$SESSION:main" "$AGENT_CMD" Enter
echo "started tmux session '$SESSION' running: $AGENT_CMD"
