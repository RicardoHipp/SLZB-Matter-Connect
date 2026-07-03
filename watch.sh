#!/bin/bash
export PATH="$HOME/.local/bin:$PATH"
cd ~/SLZB-Matter-Connect

RUN_ID=$(gh run list --limit 1 --json databaseId -q '.[0].databaseId')
echo "Beobachte Workflow-Run $RUN_ID (mit Strg+C abbrechen)..."
gh run watch "$RUN_ID"
