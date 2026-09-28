---
name: sideband
description: Sideband adapter for Claude Code. Joins the discussion so entries addressed to Claude are pushed into this conversation on their own, with nothing to poll or wait on; the prompt hook records the human's prompts; sends requests, replies, and acks with `sideband append`. Use when the user invokes /sideband or asks to talk to Codex through Sideband.
allowed-tools: Bash(sideband skill)
---

Follow the Sideband instructions below exactly, treating any text after
`/sideband` as the argument they describe. `sideband skill` prints them from
the executable, so updating it updates this skill.

!`sideband skill`
