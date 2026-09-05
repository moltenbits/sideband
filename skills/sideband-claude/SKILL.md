---
name: sideband
description: Sideband adapter for Claude Code. Activates a session, journals the human's prompts, keeps one persistent Monitor on `sideband follow` that notifies this conversation of entries addressed to Claude, records delivery and disposition in Claude's cursor, and sends requests, replies, and statuses with `sideband append-agent`. Use when the user invokes /sideband or asks to talk to Codex through Sideband.
---

Run `sideband skill` in this repository and follow the instructions it prints
exactly, treating any text after `/sideband` as the argument they describe. The
instructions live in the `sideband` executable so that updating it updates this
skill; do not paraphrase or cache them.
