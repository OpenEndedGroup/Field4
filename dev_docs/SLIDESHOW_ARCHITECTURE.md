# Slideshow Architecture Diagram

## Component Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         Field4 Application                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─────────────────────┐          ┌──────────────────────────┐  │
│  │   Browser (JS)      │          │   Server (JVM/Kotlin)    │  │
│  │                     │          │                          │  │
│  │  ┌───────────────┐  │          │  ┌─────────────────────┐ │  │
│  │  │  Main.kt      │  │◄────────►│  │   Server.kt        │ │  │
│  │  │  (Handlers)   │  │ WebSocket│  │   (Handler)        │ │  │
│  │  └───────────────┘  │          │  └─────────────────────┘ │  │
│  │         │           │          │           │              │  │
│  │         │           │          │           ▼              │  │
│  │         ▼           │          │  ┌─────────────────────┐ │  │
│  │  ┌───────────────┐  │          │  │   SlideShow.kt     │ │  │
│  │  │  Monaco       │  │          │  │   (DSL & Logic)    │ │  │
│  │  │  Editor       │  │          │  └─────────────────────┘ │  │
│  │  └───────────────┘  │          │           │              │  │
│  │         │           │          │           │              │  │
│  │         ▼           │          │           ▼              │  │
│  │  ┌───────────────┐  │          │  ┌─────────────────────┐ │  │
│  │  │  Slide Note   │  │          │  │   Box               │ │  │
│  │  │  Overlay      │  │          │  │   (State)           │ │  │
│  │  │  (slideshow   │  │          │  └─────────────────────┘ │  │
│  │  │   .css)       │  │          │                          │  │
│  │  └───────────────┘  │          │                          │  │
│  │                     │          │                          │  │
│  └─────────────────────┘          └──────────────────────────┘  │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

## Message Flow

### 1. Starting a Slideshow (Server → Client)

```
┌──────────────┐
│ User Code    │  slideShow { ... }.start()
└──────┬───────┘
       │
       ▼
┌──────────────────────┐
│ SlideShowInstance    │  goToStep(0)
│ (SlideShow.kt)       │
└──────┬───────────────┘
       │
       │ Sends Message
       ▼
┌──────────────────────┐
│ "show-slide-note"    │  { html, uid, persistent }
│ Message              │
└──────┬───────────────┘
       │
       │ WebSocket
       ▼
┌──────────────────────┐
│ Main.kt              │  handlers.put("show-slide-note")
│ (Browser)            │
└──────┬───────────────┘
       │
       ▼
┌──────────────────────┐
│ DOM Manipulation     │  Create/Update overlay
│                      │  Add HTML content
│                      │  Setup link handlers
└──────────────────────┘
```

### 2. Navigating via Link (Client → Server → Client)

```
┌──────────────┐
│ User         │  Clicks <a href="#slideshow:next">
└──────┬───────┘
       │
       ▼
┌──────────────────────┐
│ Link Handler         │  Extract command: "next"
│ (Main.kt)            │
└──────┬───────────────┘
       │
       │ Sends Message
       ▼
┌──────────────────────┐
│ "slideshow-command"  │  { command: "next" }
│ Message              │
└──────┬───────────────┘
       │
       │ WebSocket
       ▼
┌──────────────────────┐
│ Server.kt            │  handlers["slideshow-command"]
│                      │  Find active slideshow
└──────┬───────────────┘
       │
       ▼
┌──────────────────────┐
│ SlideShowInstance    │  next()
│                      │  → goToStep(currentIndex + 1)
└──────┬───────────────┘
       │
       │ (Loops back to "Starting a Slideshow" flow)
       ▼
   [Shows next step]
```

### 3. Selecting a Box (Server → Client)

```
┌──────────────────────┐
│ SlideShowInstance    │  goToStep(n)
└──────┬───────────────┘
       │
       ▼
┌──────────────────────┐
│ step.targetBox?      │  selectBox(box, property, line)
└──────┬───────────────┘
       │
       │ Sends Message
       ▼
┌──────────────────────┐
│ "select-and-edit-box"│  { uid, property, scrollToLine,
│ Message              │    highlightLine }
└──────┬───────────────┘
       │
       │ WebSocket
       ▼
┌──────────────────────┐
│ Main.kt              │  handlers.put("select-and-edit-box")
└──────┬───────────────┘
       │
       ├─────────────────┐
       │                 │
       ▼                 ▼
┌──────────────┐  ┌──────────────────┐
│ Box          │  │ Monaco Editor    │
│ Selection    │  │ Navigation       │
│              │  │                  │
│ • Deselect   │  │ • revealLine     │
│   others     │  │ • setSelection   │
│ • Select     │  │ • highlight      │
│   target     │  │                  │
└──────────────┘  └──────────────────┘
```

## Data Structures

```
SlideShowInstance
├─ hostBox: Box
├─ steps: List<SlideStep>
├─ currentStepIndex: Int
└─ Methods:
   ├─ start()
   ├─ next()
   ├─ previous()
   ├─ goToStep(index)
   └─ stop()

SlideStep
├─ title: String
├─ noteHtml: String?
├─ targetBox: Box?
├─ targetProperty: String
├─ targetLine: Int?
├─ highlightLine: Int?
├─ persistent: Boolean
├─ onEnter: (suspend () -> Unit)?
└─ onExit: (suspend () -> Unit)?
```

## Message Types

```
SelectAndEditBox
├─ uid: String
├─ property: String = "code"
├─ scrollToLine: Int? = null
└─ highlightLine: Int? = null

ShowSlideNote
├─ html: String
├─ uid: String = ""
└─ persistent: Boolean = false

HideSlideNote
└─ dummy: Boolean = true

SlideShowCommand
└─ command: String  ("next" | "previous" | "start" | "stop" | <step#>)
```

## CSS Styling Hierarchy

```
#slide-note-overlay
├─ Position: fixed (top-right)
├─ Styling: dark background, blur, shadow
├─ Animation: slideIn from right
└─ Contents:
   ├─ HTML from step.noteHtml
   ├─ Links → #slideshow:* hrefs
   └─ Close button (if persistent)
```

## Lifecycle Flow

```
1. Author creates slideshow:
   box.slideShow { 
     step(...) { ... }
     step(...) { ... }
   }
   
2. Slideshow starts:
   slideShow.start()
   │
   └→ goToStep(0)
      │
      ├→ step.onEnter()  [optional callback]
      ├→ Send "show-slide-note" message
      └→ Send "select-and-edit-box" message

3. User navigates:
   Click link
   │
   └→ Send "slideshow-command" message
      │
      └→ Server receives command
         │
         ├→ step.onExit()  [optional callback]
         ├→ currentStepIndex++
         └→ goToStep(newIndex)  [back to step 2]

4. Slideshow ends:
   slideShow.stop()
   │
   ├→ step.onExit()
   └→ Send "hide-slide-note" message
```

## File Organization

```
Field4/
├── src/
│   ├── commonMain/kotlin/
│   │   └── rpc.kt                    [Message types]
│   ├── jvmMain/kotlin/afield/
│   │   ├── SlideShow.kt              [DSL & Logic]
│   │   ├── examples/
│   │   │   └── slideshow_example.kt  [Examples]
│   │   ├── Server.kt                 [Command handler]
│   │   └── Boxes.kt                  [Property persistence]
│   ├── jsMain/kotlin/afield/
│   │   └── Main.kt                   [UI handlers]
│   ├── jsMain/resources/
│   │   └── slideshow.css             [Overlay styling]
│   └── commonMain/resources/
│       └── index.html                [CSS import]
└── docs/
    ├── SLIDESHOW_FEATURE.md          [Full documentation]
    └── SLIDESHOW_QUICKSTART.md       [Quick start guide]
```
