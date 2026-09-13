(ns field.core
  "Field, from inside a Clojure box.

  Every Clojure box gets this namespace `require`d and `refer`red into it, so the names below are
  just there — no `:require`, no aliasing. See `initNsIfNeeded` in
  `src/jvmMain/kotlin/afield/clojure/ClojureInterface.kt`.

  It is small on purpose. Clojure's Java interop already reaches all of Field: `(.getChildren (box))`
  walks the document, `(afield.graphics.FLine.)` makes a line, `(.moveTo f 0 0 0)` builds it. What
  interop is bad at is Kotlin's two favourite shapes — an extension function, whose class name is a
  file name, and a default argument, which is not a default at all from outside Kotlin. Those are
  what this wraps. If a verb reads well as raw interop, it is deliberately not here."
  (:import [afield Box]
           [afield.clojure ClojureInterface]
           [afield.graphics FLine]))

(defn ^Box box
  "The Box this code is running in, or nil outside a box.

  Everything Field can do to a box, you can do to this: `(.getName (box))`, `(.getChildren (box))`,
  `(.setPropertyRaw (box) \"name\" \"renamed\" true)`."
  []
  ClojureInterface/thisBox)

(defn display
  "Show `v` in one of this box's output channels — the disclosure panels under it.

  `v` is rendered by Field's own value renderer, so a map arrives as a table, an FLine as a drawing,
  a string of HTML as HTML. With no channel it goes to `output`."
  ([v] (display "output" v))
  ([channel v] (.display (box) ^String channel v)))

(defn as-lines
  "`ls` as a java.util.List of FLine, accepting a single line as well as a sequence of them."
  [ls]
  (if (instance? FLine ls) [ls] (vec ls)))

(defn lines
  "Draw FLines into an output channel of this box.

  `(lines ls)` sizes the drawing from the geometry; `:fit true` with `:w`/`:h` scales and centres it
  into that box instead, which is what you want when the numbers in the geometry are not pixels.

      (lines [(polyline [[0 0] [100 100]])] :w 300 :h 300 :fit true)"
  [ls & {:keys [channel w h fit] :or {channel "lines" w -1 h -1 fit false}}]
  (ClojureInterface/lines (box) (as-lines ls) channel (double w) (double h) (boolean fit)))

(defn polyline
  "An FLine through `points`, each `[x y]` or `[x y z]`.

  The rest of FLine — cubics, per-node colour, transforms — is FLine's own API, by interop."
  [points]
  (ClojureInterface/polyline points))

(defn color
  "Set `line`'s colour, as RGBA in 0..1, and return the line. Alpha included: lines blend."
  ([line [r g b a]] (color line r g b a))
  ([line r g b a] (ClojureInterface/colour line (double r) (double g) (double b) (double a))))

(defn thickness
  "Set `line`'s stroke width in pixels and return the line."
  [line width]
  (ClojureInterface/thickness line (double width)))

(defn animate
  "A per-frame animation that calls `f` once per frame. **Return it** to start it.

  The rule is the Kotlin one: building an animation runs nothing, and returning it as the value of
  the box is what launches it. So this has to be the last form, and you have to *launch* the box
  rather than run it.

      (def n 200)                          ; once, when you launch
      (animate (fn [] (redraw n (frame))))  ; once per frame after that

  Inside `f`, `(frame)` is the frame number and `(end)` stops the animation. A throw inside `f` is
  reported against the line this `animate` is written on, and ends the animation."
  [f]
  (ClojureInterface/animate f))

(defn frame
  "The frame number of the animation being run, or 0 outside one."
  []
  (ClojureInterface/frameNumber))

(defn end
  "Stop the animation whose frame is running. Does nothing outside a frame."
  []
  (ClojureInterface/endAnimation))

(defn prop
  "Read a property of this box (or of `b`). Properties are the box's own storage: `name`, `code`,
  `frame`, and anything you put there yourself."
  ([name] (prop (box) name))
  ([^Box b name] (.getPropertyRaw b ^String name nil)))

(defn prop!
  "Write a property of this box (or of `b`) and return the value.

  The write is synced to the front end, so `(prop! \"name\" \"renamed\")` retitles the box you are
  looking at. Properties survive a save: this is how a box keeps state between runs."
  ([name value] (prop! (box) name value))
  ([^Box b name value] (.setPropertyRaw b ^String name value true) value))
