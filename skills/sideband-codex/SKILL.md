---
name: sideband
description: Sideband adapter for Codex. Join once per session so the Sideband discussion can push entries into this conversation with `codex queue`; record the human's prompts with `sideband capture-human`; send with `sideband append-agent`. No listener to run. Use when the user invokes $sideband or asks to talk to Claude through Sideband.
---

Run `sideband skill` in this repository and follow the instructions it prints
exactly, treating any text after `$sideband` as the argument they describe. The
instructions live in the `sideband` executable so that updating it updates this
skill; do not paraphrase or cache them.
