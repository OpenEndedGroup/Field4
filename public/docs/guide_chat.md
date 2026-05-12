# Guide Chat

Guide chat is the optional service behind `withChat()` in a slideshow step.

## What it does

Guide chat adds a discussion thread to a step:

- `withChat()` creates a slide-specific thread.
- `withChat(id = "templateId")` creates a shared thread across template instances.
- messages are persisted on the server and reloaded for later viewers,
- updates are pushed live to connected clients.

## Architecture

Guide chat has four parts:

1. `server/guide_chat_server.py`
   Runs the Flask-based chat service and persists messages as JSON.
2. `src/jvmMain/kotlin/afield/server/chat.kt`
   Bridges Field's JVM server to the chat service.
3. `src/jsMain/kotlin/afield/Main.kt`
   Renders the collapsible chat UI inside slideshow notes.
4. `src/jvmMain/kotlin/afield/SlideShow.kt`
   Passes `slideshowId` and slide-number metadata from the guide runtime.

## Setup

Install the Python dependencies:

```diffstatic {data-lang=bash}
cd server
pip install -r requirements.txt
```

Start the chat server:

```diffstatic {data-lang=bash}
cd server
python3 guide_chat_server.py
```

Optional environment variables:

- `CHAT_SERVER_PORT`: port number, default `5002`
- `CHAT_SERVER_DEBUG`: debug mode, default `true`
- `CHAT_SERVER_URL`: URL used by Field's JVM server, default `http://localhost:5002`

Then run Field normally:

```diffstatic {data-lang=bash}
./gradlew runJvm
```

## Using it from a guide

```diffstatic {data-lang=kotlin}
:::static
slideShow {
    slideshowId("intro-guide")

    step("Introduction") {
        markdownNote(
            """
            ## Welcome
            This slide has a discussion thread.
            """.trimIndent()
        )
        withChat()
    }

    step("Shared discussion") {
        markdownNote(
            """
            ## Shared thread
            This thread is shared across template instances.
            """.trimIndent()
        )
        withChat(id = "templateId")
    }
}
```

Only steps that call `withChat()` render a discussion disclosure.

## Username handling

The frontend reads `username` from the URL query string:

```diffstatic {data-lang=text}
https://localhost:9092/?file=myguide&username=student1
```

Without that parameter, messages are posted as `anonymous`.

## Storage layout

Messages are stored under `chat_data/` in per-slideshow JSON files:

```diffstatic {data-lang=text}
chat_data/
  slideshow_box123_1704182400000/
    slide_0.json
    slide_1.json
```

Each file contains an array of message records:

```diffstatic {data-lang=json}
[
  {
    "username": "student1",
    "message": "Can you explain this step?",
    "timestamp": "2024-01-02T12:34:56.789Z"
  }
]
```

## Failure mode

If the chat server is down:

- the slideshow still renders,
- `withChat()` still produces the UI shell,
- but message load and post requests fail.

Chat is deliberately optional. The slideshow system does not depend on it.

## Related docs

- [interactive_guides.md](interactive_guides.md)
- `dev_docs/GUIDE_CHAT_SYSTEM.md`
