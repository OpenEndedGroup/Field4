# AI4 notifications

Toasts answer “what is happening that I need to notice?” The chat owns the running
explanation, tool results and next steps. Tool pool and diagnostic logs retain the
technical evidence.

`NotifyLog` always writes the complete diagnostic event to stdout, but only
deliberately authored `ai.user` events become toasts. `AiToastPolicy` suppresses
repeated messages for 15 seconds and limits ordinary activity to one update per
5 seconds. Attention messages bypass the activity throttle. State is bounded and
belongs to the bridge's notification sink, rather than a global map of tool arguments.

Both model bridges emit `toolActivity` for recognised actions and `userNotice` for
tool failures and automatic retry waits. Tool names, IDs, paths, JSON, response
bookkeeping, model reasoning and routine reads are not toast content. Unrecognised
tools remain visible in chat and Tool pool; their intent is not guessed from their
names. Registration is diagnostic logging only. Turn completion means a reply is
ready, not that every requested build or check succeeded.

When adding an activity message, describe the user's action and emit it at the
actual operation. Recovery messages say whether Field is retrying automatically;
terminal failures direct the user to the persistent chat. Keep technical errors in
that persistent record. A toast is never the only failure record.

Verification, 2026-10-03:

- `AiToastPolicyTest` and `AiRateLimitRecoveryTest`: ten tests passed.
- Real AI4 chat in a separate Field document invoked `ViewBoxOutputRequest` on a
  computed output. Browser observation and screenshot showed “Checking the view…”;
  the completed turn showed “Reply ready in the chat.” No response bookkeeping,
  tool-call IDs or bridge channel tags appeared in the observed toasts. The raw
  bridge events remained in the server log.
- Restarted the development host and restored all eleven existing house document
  providers. Their saved source files were unchanged.
