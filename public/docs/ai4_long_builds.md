# Let AI4 Wait for Long Builds

A [derived build](studies.md#build-derived-results-without-blocking-edits) can take minutes or
hours: a checked mesh, a fabrication export, a solver run. AI4 can start one, end its turn, and
pick the same conversation up again when the build has finished. Nothing is sent to the model
while it waits, so a forty-minute build costs a request to start it and a request to read the
result, not one request every few minutes to ask whether it is done yet.

## Start a build and let the agent wait

This box defines one slow stage. It estimates π from sixty billion random points, sixty batches of
a billion, and reports its progress after each batch:

```diffstatic {data-lang=kotlin}
val builds = studyBuilds("estimate", listOf(
    StudyBuildStage("pi", "Estimate π", "pi-v1") { context ->
        val random = java.util.SplittableRandom(4)
        val batches = 60
        val perBatch = 1_000_000_000
        var inside = 0L
        for (batch in 1..batches) {
            repeat(perBatch) {
                val x = random.nextDouble()
                val y = random.nextDouble()
                if (x * x + y * y < 1.0) inside++
            }
            context.checkCancelled()
            context.progress(batch / batches.toDouble(), "$batch of $batches batches")
        }
        val samples = batches.toLong() * perBatch
        val estimate = 4.0 * inside / samples
        context.file("estimate.txt").writeText("$estimate\n")
        StudyBuildProduct(listOf("estimate.txt"),
            facts = mapOf("samples" to samples, "estimate" to estimate, "error" to Math.abs(estimate - Math.PI)))
    }
)) { emptyMap() }
showStudyBuilds("estimate")
```

Run the box to get its build card, open the box's AI4 chat (`command/ctrl-4`), and ask for the
build:

> Run the π estimate build on this box. It takes a few minutes; tell me what it finds.

The agent inspects the runner, starts the stage, and then calls `AwaitStudyBuildsRequest` with the
job it started. That ends its turn. Saying the build will take a while helps: the agent cannot know
in advance, and for a short build it may watch it from inside the turn instead.

## While it waits

The chat ends with a row that says what the conversation is waiting for, and the latest time it
will carry on. The row shimmers for as long as the wait lasts. The build card goes on showing the
build's progress.

![the Estimate box with its build card, which reads "Estimate π" and "Building", with a progress bar a fifth of the way along, 37 seconds elapsed and "13 of 60 batches". Below it the box's AI Chat: the request to run the π estimate build; the agent saying it will check the build runner, start the estimate and wait for its result; a folded line of four tool calls; the agent saying the build is queued to sample 60 billion random points; a folded line of one tool call; and last an open row, "Waiting for 1 build · no model calls", naming the attempt and saying that the conversation resumes when they finish, or at 18:12 at the latest, and that /stop-waiting stops waiting while the builds keep running](assets/ai4_long_builds/waiting.png)

You can go on working while it waits, in the document or in the chat. A message you send starts an
ordinary turn, and the wait carries on behind it. To stop waiting, type `/stop-waiting` in the
chat: the build keeps running, and nothing resumes the conversation when it finishes. Clearing the
chat, rewinding it or switching its branch stops the wait too.

The agent chooses how long to wait at most, four hours unless it asks for another limit, and never
more than 48.

## What the conversation resumes with

When every awaited job has finished, whether it succeeded, failed, was cancelled or was
interrupted, or when the time limit passes, Field writes a message into the chat and starts the
next turn with it. The chat labels it **Field**, since you did not write it. For each job it gives:

- its status, and for a success whether its result is still the current one;
- a failure's message and suggestions;
- the facts the stage returned in its `StudyBuildProduct`;
- the stage's [result notes](studies.md#build-derived-results-without-blocking-edits), when it has a
  `resultNotes` adapter.

When the time limit passes first, the message says how many jobs are still running; they go on
running. The waiting row stays in the chat, folded, as a record of the wait. The agent carries on
from where it stopped:

![the same box after the build. The card reads "Current", with a Rebuild button. In the chat the waiting row is folded to its title, "Waiting for 1 build · no model calls". Under it, a message labelled Field: "The build you awaited has ended, after 2 min. No model call was made while it ran.", listing the attempt as succeeded, the current result, with its facts: 60000000000 samples, estimate 3.1415993772 and its error. Then the agent's reply: the build succeeded in about 2 minutes and its result is current, with the samples, the estimated π and its absolute error from π, about 0.000214%](assets/ai4_long_builds/resumed.png)

If a turn is already running in that box when the builds finish, because you sent a message in the
meantime, the outcome goes to that turn instead, the way a message you type while the agent works
does: the agent reads it after its current step.

## No model calls while it waits

Field does the waiting. It reads the build runner every five seconds and makes no request until the
builds are done. Compare `WaitStudyBuildRequest`, which an agent can call to
watch a job from inside its turn: each call returns after at most fifteen minutes, and every call
after that is another model request, which sends the whole conversation again.

Every request Field makes to OpenAI, OpenRouter or Anthropic is recorded, with its time, in
`~/.afield/logs/ai_usage/`. `python3 tools/ai_usage.py --hours 1` counts them by document, so you
can check the cost of a long build for yourself.

## Seeing a suspended turn from outside

A script or another agent can ask a chat for its state. The `ai-chat-history` request's reply
carries `running`, true while a turn is running in that box, and `suspendedOn`, which is present
while the chat waits: when the wait began (`since`), when it ends at the latest (`resumesBy`), and
each awaited job with its runner, stage and last seen status. The backdoor prints the same, with the
chat's last reply:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json --attach-existing \
  --document long-builds --command 'open long-builds' --command 'ai-chat Estimate'
```

Add `--stop-waiting` after `ai-chat Estimate` to end the wait, as `/stop-waiting` does.

## When Field restarts

The wait is saved with the chat, in the document. When Field restarts, the wait starts again as
soon as the document is opened. A build that was running when Field stopped cannot finish, because
its runner stopped with Field, so the conversation resumes with that job marked interrupted and the
agent can start it again. Field gives the runner two minutes after the document opens to come back
(its box running again, by hand or by `autorun`); after that it reads what the runner last recorded.

## Where AI4 can wait

`AwaitStudyBuildsRequest` is offered in a box's AI4 chat, on OpenAI, OpenRouter and Anthropic
alike. It is not offered where nothing would resume the conversation: the editor's inline requests,
the document chat, a headless agent task. There the agent watches a build with
`WaitStudyBuildRequest`. A [tool program](ai4_experiments.md#many-calls-at-once) cannot call it
either, since only a call the model makes itself can end its turn.

See also: [Studies](studies.md), [AI features](aiFeatures.md),
[Experimenting with AI4](ai4_experiments.md).

??? aside "How these figures were made"
    By `tools/capture_ai4_long_builds.py`, on a Field it starts in a temporary workspace. It runs the
    box above, sends the request as a live panel turn (OpenAI `gpt-6.1-sol`, low reasoning effort),
    typed into the chat in the backdoor's browser, and captures the box and chat while the turn waits
    and again after Field has resumed it. It writes the second figure only if the request ledger for
    that Field shows the conversation's two turns and no request between the moment the first turn
    ended waiting and the moment the build finished. On the run of 2026-10-09 that published these
    figures, the two turns made six requests and the build ran 169 seconds.
