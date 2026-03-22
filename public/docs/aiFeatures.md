# AI Features

Field aims to have a complete set of AI-facing features, even when it might not be so clear as to what that might mean yet. This page collects them together.

## AI Chat about this box

`command/ctrl-4` (or "AI Chat (Panel)") opens an output area on this box where you can chat to GPT-5.3 about the code in this box. This agent has access to a full complement of tools for reading and editing your code, its properties and the internal files and documents that make up Field.

![](assets/screenshot_47.png)

This agent tends to work very hard, especially at the beginning of the conversation. You can follow it's innermost thoughts and tool calls in the terminal.

Image based attachments are supported — just paste an image into the text box and let the Agent see the results of the code.

![](assets/screenshot_53.png)

This means that 'can you make the thick lines a little more red' really is something you can have a concrete conversation about.

## AI 'Inline' chat

`command/ctrl-3` (or "AI Inline Chat") let's you indicate a spot in your code that you'd like some help with:

![](assets/screenshot_46.png)

That should, quite quickly, give you:

```diffstatic {data-lang=kotlin}
var v = listOf<Vec2>()

v = List(100) { Vec2(Math.random().toFloat(), Math.random().toFloat()) }
```

If there are significant changes to your code, Field will bundle these into a provisional edit:

![](assets/screenshot_48.png)

Green lines are additions and red deletions. This may include changes to other properties associated with a box (GLSL shader code), and it might include other boxes. You can review and accept or reject changes with the mouse or a command:

![](assets/screenshot_49.png)

## AI Auto-complete

`ctrl-space` (show autocomplete) will, in Field, typically do a type-based analysis of what kinds of things can complete here (Kotlin's internal compiler for Kotlin, the Jedi package for Python etc.); but in the background it also fires off a request for up to 3 AI completions and updates the completion list with them when they arrive:

![](assets/screenshot_50.png)

In our testing these things seem to be particularly good at GLSL code, often one-shotting large chunks of the standard issue "Book of Shaders"-level shader repertoire. 

## Just fix the error, damnit. 

This one is my favorite:

![](assets/screenshot_51.png)

Yields:

![](assets/screenshot_52.png)

And a quick `f1`-"Accept All Provisional (Everywhere)" gets your code working. In addition to the code and the usual set of tools, this agent gets the error message on the screen as part of it's prompt.

# Behind the scenes

Behind the scene's there's a couple of things going on. First of all, for any of this to work, you'll need your own OpenAI platform key. You can introduce this into the terminal that you are running Field from. On Mac, you can `export OPENAI_API_KEY=sk-proj-QU_auyboSk_OpQmTfJ37HiQLxTXV4XQaNLrgDB93XY8ZioJIdzLYWxXqivnH63LsmyP0X1wSHmT3BlbkFJfZF3ZXoRj7R1ETrBOS1MLHJ2iItWSlqd24opyhfLslAucbXZO4tgguXPd329RAORBF7A0CT8A` (that's not a real key). Alternatively you can run:

```diffstatic {data-lang=kotlin}
System.setProperty("OPENAI_API_KEY", "your key here")
```

Early on in your session.

Field will also open a filestore in that OpenAI's account, upload most of it's source and documentation to it, to allow agents to search it quickly. It will keep this filestore in sync when you upgrade your Field. So, the first time you open Field with AI extensions, expect some traffic.

