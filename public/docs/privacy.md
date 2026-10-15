# Your code, AI and local history

Field runs on your machine. You can edit and run Kotlin, make the first drawing, and use native
graphics without an AI key. This page describes Field's built-in services; code you write or
libraries you load can make their own network requests.

## What stays on your machine

Your documents and their backups live in your workspace. Field also keeps local settings, caches
and diagnostic logs. **Local code-use history** is an
on-by-default SQLite history of compiled and executed code: full source snapshots, document and
box names, compile diagnostics, exceptions, timings and launch events. Field and its AI tools use it to
find related code and to look into failures. It is not an upload to OpenEndedGroup or an audience analytics feed.

The database normally lives at `~/.afield/code-use/field-code-use.sqlite`. The **AI → Local code-use
history → Open browser** control on Welcome shows it. `AFIELD_CODE_USE_PATH` changes its location.
Stop Field before deleting that database and its `-wal` and `-shm` companions; Field can create a new
history when restarted. Deleting it does not delete document files or other logs.

Field analyses that history in a helper process on your machine. If you set
`AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL` to another server, the code it analyses is sent there instead.
**Quiet mode pauses background semantic analysis; it does not stop recording local code history.**
Source and diagnostics can contain private information, so review them before attaching logs to an issue.

## What optional AI sends

When you use AI chat, AI edits, completion or documentation assistance, the configured provider or
agent receives the request and its context. That can include selected code, surrounding document
content, conversation history, retrieved source/docs, diagnostic messages, and images you supply.
An agent's tools can read more code, files or local code-use history while answering a request;
tool results then become model context. A selected line is not a guarantee that only that line leaves
this machine. Review the tools and provider you enable for confidential work.

Providers and model/account settings appear in **Welcome → AI**. Requests use your own API key or
configured agent account and can incur that provider's charges. Provider retention and data handling
are governed by that provider's settings and terms. Field does not supply a shared API subscription.
Background documentation or tool-review tasks also use the configured agent when you start them.
If you want to try Field without any external model, leave AI unconfigured; nothing in
[your first five minutes](first_steps.md) needs it.

Saved provider keys are stored under `~/.afield/`, outside your documents. Conversation and tool
logs may also remain locally. Do not attach keys, private documents or unreviewed logs to a public bug report.
Optional speech transcription downloads a model and runs its transcription worker locally; sending
the resulting prompt to an AI assistant follows the same rules above.

## Downloads and network access

First installation downloads runtimes and dependencies. Optional model services can download models
or call their own services; each has separate requirements. Remote JS, URLs in your documents and
third-party libraries make their own requests, which nothing on this page covers.

By default Field's server only accepts connections from your own machine, and refuses requests made
by other websites open in your browser. If you open it to your local network, anyone who can reach it can run code on your machine
without logging in: read [server access](server_surface.md) before enabling it.

## License

Field's code is **AGPL-3.0-or-later**; see the repository's
[LICENSE](https://github.com/OpenEndedGroup/Field4/blob/main/LICENSE). Vendored components and
optional integrations can have their own licenses.
