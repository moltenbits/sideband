---
name: sideband
description: Sideband adapter for Claude Code. Joins the discussion so entries addressed to Claude are pushed into this conversation, or listens with `sideband pending --wait --stream` when pushes are held; the prompt hook records the human's prompts; sends requests, replies, and acks with `sideband append`. Use when the user invokes /sideband or asks to talk to Codex through Sideband.
---

Run `sideband skill` in this repository and follow the instructions it prints
exactly, treating any text after `/sideband` as the argument they describe. The
instructions live in the `sideband` executable so that updating it updates this
skill; do not paraphrase or cache them.
