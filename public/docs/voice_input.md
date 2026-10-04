# Talking to AI4

You can speak a prompt to an AI chat instead of typing it. Hold the push-to-talk key, say what you
want, and let go. Field transcribes the clip on your own machine with Whisper, puts the text in
the chat and sends it. Nothing you say leaves the computer until it is text in the chat, and from
there it goes wherever your typed prompts go ([AI Overview](aiFeatures.md)).

## Install the voice dependencies

Speech recognition is an optional Python dependency group. Install it once into the virtual
environment Field uses, from the Field checkout:

```bash
uv sync --inexact --group voice
```

On an Apple-silicon Mac this installs `mlx-whisper`, which runs Whisper `large-v3-turbo` on the GPU.
On Linux, Windows and Intel Macs it installs `faster-whisper`: with an NVIDIA GPU (and the CUDA 12
and cuDNN 9 runtimes) it runs `large-v3-turbo`; on a CPU it uses the smaller English model
`small.en`, because the large one is too slow to wait for.

The first time you talk, the model is downloaded from Hugging Face (about 1.6 GB for
`large-v3-turbo`). After that it is cached and works offline.

## Hold the key and speak

| Platform | Push-to-talk key |
| --- | --- |
| macOS | Right Option |
| Windows, Linux | Right Ctrl |

Hold the key anywhere in Field, speak, and release. Recording starts the moment you press, so you
can start talking straight away, and keeps going for a moment after release so the last word is
not cut off.

- **Release with Shift held** to put the transcript in the chat input without sending it, so you
  can edit it first.
- **A quick tap** (under a quarter of a second) is ignored, and so is any take where you press
  another key while holding the push-to-talk key. Option-letter and Ctrl-C keep working.
- Each chat also has a **microphone button** beside its input. Hold it to talk into that chat
  specifically.

Right Alt is not the default outside macOS because on Windows and on most non-US Linux keyboard
layouts it is AltGr, which types characters. To use another key, set it from the browser console
(or the Electron developer tools) with its `KeyboardEvent.code`:

```js
localStorage.setItem("fieldVoiceKey", "F13")
```

and reload the page.

## Which chat hears you

The push-to-talk key talks to one chat, and that chat stays the same until you pick another:

- the last box you **selected** that has an AI chat open, or
- the last chat you **typed into**,

whichever happened most recently. Selecting a box with no chat leaves the target where it was.
If no chat is open at all, Field opens one for the selected box. With nothing selected and no
chat, Field asks you to select a box first. While a chat is recording or transcribing, its
microphone button and outline show it.

## Saying code out loud

Whisper hears "from context", not `fromContext`. Field handles that in two places.

**Before transcription**, Whisper is primed with the words this conversation is already using:
identifiers from the chat so far (the recent half counts double) and from the box's code, the
names of the boxes on the canvas, and anything inside backticks. Words that look like code
(`camelCase`, `PascalCase`, `snake_case`, a name followed by `(`) are the ones picked. Up to 60 are
sent.

For a clip that said "Use from context to find the renderer, then call module here and set the
fragment shader", Whisper `large-v3-turbo` on an M1 Max produced:

| Primed with | Transcript |
| --- | --- |
| nothing | Use from context to find the renderer, then call module here and set the fragment shader. |
| `fromContext, Renderer, moduleHere, fragment` | Use `fromContext` to find the `Renderer`, then call `moduleHere` and set the fragment shader. |

To add words of your own that should always be recognized, list them, separated by commas or
newlines:

```js
localStorage.setItem("fieldVoiceVocabulary", "GpuRadixSort, RenderToTexture, Lygia")
```

**After transcription**, a spoken prompt is marked as dictated when it is sent. The model is told
that it is reading a speech-to-text transcript that may contain recognition errors, to read it for
what was meant using the code and the conversation, and to say which reading it took when a
correction changes what it would do. The chat history keeps your words as Whisper heard them.

## How fast it is

On an M1 Max with `mlx-whisper`, a five-second clip took about 0.37 s to transcribe once the model
was loaded. Loading the model from the local cache took about 4 s. Field starts loading it as soon
as you first press the key, so most of that happens while you are still talking. The first use on
a new machine also waits for the download.

Clips are transcribed one at a time, so a second take started while the first is being
transcribed waits its turn.

## When it does not work

Field reports a problem as a notification titled **AI4 voice**.

**"Voice input unavailable ... no Whisper backend is installed"**: the `voice` group is not in the
Python environment Field is using. Run `uv sync --inexact --group voice` in the checkout. Field
uses, in order, the interpreter named by the `FIELD_VOICE_PYTHON` environment variable, the one
Field's [Python boxes](python.md) use, and the checkout's `.venv`.

**"Microphone unavailable: macOS is blocking Field4 from the microphone"**: turn Field4 on in
System Settings → Privacy & Security → Microphone, then restart Field4. The Field4 app asks for
the microphone the first time you talk; if you said no, this is where to change it. If Field was
started from a terminal, macOS may list the terminal rather than Field4.

**"this browser refused the microphone"**: you are using Field in a browser. Allow the microphone
for the site from the permission icon in the address bar, or use the Field4 app.

**On Windows**, turn on Settings → Privacy & security → Microphone → "Let desktop apps access your
microphone". **On Linux**, check that PipeWire or PulseAudio is running and lists an input
(`pactl list short sources`); in a Flatpak or Snap, grant the app audio input.

**"no microphone found"** or **"the microphone is in use"**: check the system's sound input, and
close other apps that are holding the microphone.

**"The voice worker stopped"**: the recognizer process exited. The next press starts it again.

To use a different Whisper model, set `FIELD_VOICE_MODEL` before starting Field: a Hugging Face
repository for `mlx-whisper`, or a model name
for `faster-whisper` (for example `medium.en`).

??? aside "Where the numbers on this page come from"
    The transcripts and timings were measured on 2026-10-04 by speaking the sentence with macOS
    `say`, converting it to 16 kHz float32 with `afconvert`, and sending it through
    `python/fieldvoice/whisper_worker.py` with and without a vocabulary prompt, twice each; both
    runs gave the same text and 368–375 ms. Model load (from a warm Hugging Face cache) was
    3.8 s. A synthetic voice is easier than a person in a room, so read the timing as a floor and
    the priming result as the shape of the effect rather than its size. The browser half
    (recording, the key handling, target choice) was read from `voiceInput.js` and
    `outputareas2.js`, not exercised with a real microphone. `tools/test_whisper_worker.py` pins
    the worker's line protocol with a stub backend.
