# Memory

OryxOS gives agents memory across three layers. Each layer has a different scope, lifetime, and storage backend. `MemoryService` is the unified facade that `ReActLoop` and `PromptBuilder` talk to — they do not access layers directly.

## Three layers

| Layer | Storage | Scope | In core phase |
| --- | --- | --- | --- |
| Session memory | SQLite `sessions.messages_json` | Current conversation only | Yes |
| Long-term memory | `.oryxos/memory/MEMORY.md` | Persists across sessions, per workspace | Yes |
| Episodic memory | Vector database (planned) | Semantic search across past sessions | No |

**Session memory** is the conversation history — the list of messages accumulated in the current session. It is serialized as JSON and stored in SQLite. `PromptBuilder` injects the last `max_history_turns` turns into every prompt. It accumulates automatically; agents do not write to it explicitly.

**Long-term memory** is an append-only Markdown file agents write to using the `save_memory` tool. It persists across sessions and is injected into every prompt. Agents use it to remember user preferences, project facts, and anything worth keeping past the end of a conversation.

**Episodic memory** is planned for the extension phase. It will index past conversations in a vector database and retrieve semantically relevant episodes at prompt-build time.

## Long-term memory (MEMORY.md)

`LongTermMemory` manages `.oryxos/memory/MEMORY.md` with three operations:

| Method | Behavior |
| --- | --- |
| `append(content, scope)` | Appends a timestamped entry, routed to the `core` or `archival` section |
| `load()` | Returns the core section in full plus the archival section, truncated if needed |
| `recallByKeyword(keyword)` | Returns matching lines **from the archival section only** (case-insensitive) |

The file has two sections:

| Section | Injected into every prompt | Searchable | Truncated |
| --- | --- | --- | --- |
| Core memory (`## 核心记忆`) | Yes, always and in full | No — it is always present, so there is nothing to look up | No |
| Archival memory (`## 归档记忆`) | Yes, truncated | Yes, via `recall_memory` | Yes — the most recent 4 000 characters are kept |

The 4 000-character limit keeps archival memory from consuming a disproportionate share of the context window. **Truncation happens on read only**: the file on disk stays append-only, so nothing the agent saved is silently deleted, and concurrent writers cannot corrupt each other. `load()` never throws either — a missing file, a missing section, or an I/O error degrades to an empty (or partial) result instead of breaking the conversation.

The interface is designed with an upgrade path to vector search in mind. `recallByKeyword` can be replaced with a semantic search implementation without changing `MemoryService` or any caller.

## save_memory and recall_memory tools

Agents interact with long-term memory through two built-in tools, not by writing to `MEMORY.md` directly.

**`save_memory`** — appends a piece of text to `MEMORY.md`. The agent decides what to save, when, and whether it belongs in core or archival memory. There is no automatic summarization in core phase.

```text
Tool: save_memory
Input: { "content": "User prefers concise responses, no bullet lists.", "scope": "archival" }
Effect: appends a timestamped entry to the matching section of MEMORY.md
```

`scope` is optional and defaults to `archival`. Use `core` for the handful of facts that should always be in context — who the user is, project background, hard constraints. An unrecognised value fails the call instead of silently writing to the wrong section.

**`recall_memory`** — searches the archival section of `MEMORY.md` by keyword and returns matching lines. Useful when the agent needs to check whether it already knows something before saving a duplicate.

```text
Tool: recall_memory
Input:  { "keyword": "prefers" }
Output: "User prefers concise responses, no bullet lists."
```

Both tools go through `ToolExecutor` and are recorded in `tool_invocations` like any other tool call.

## How memory is injected into prompts

`PromptBuilder` calls `MemoryService.loadLongTermMemory()` on every loop iteration and inserts the result as the second block of the system prompt, after the Profile identity and Bootstrap files:

```text
[1] system prompt  (identity + bootstrap + skill)
[2] long-term memory  ← core section in full + archival section, truncated at 4 000 chars
[3] conversation history (last N turns)
[4] available tools (JSON Schema)
```

The memory block is always present, even if `MEMORY.md` is empty. An empty file produces an empty string, which has no effect on the prompt.

## MEMORY.md vs USER.md

These two files look similar but serve opposite purposes:

| | `MEMORY.md` | `USER.md` |
| --- | --- | --- |
| Who writes it | The agent (via `save_memory`) | The user (manually) |
| OryxOS behavior | Read + write | Read only |
| Contents | Agent's accumulated observations and learned facts | User's initial preferences and context |
| Resets | Never (append-only; truncation only affects what is injected) | Never (managed by user) |
| Location | `.oryxos/memory/MEMORY.md` | `.oryxos/USER.md` |

`USER.md` is part of the Bootstrap group injected at position [1] in the prompt. `MEMORY.md` is injected at position [2]. Both are visible to the agent; only `MEMORY.md` can be modified by the agent.

## What is planned

- **Episodic memory** — index full session transcripts in a vector database; retrieve the top-k relevant episodes at prompt-build time
- **Vector search for recall** — replace `recallByKeyword` with embedding-based semantic search against `MEMORY.md` content
- **Memory summarization** — automatically compress old entries before truncation to preserve more signal in fewer characters
- **Per-agent memory namespacing** — separate `MEMORY.md` files per Profile, not per workspace
