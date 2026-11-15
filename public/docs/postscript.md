# PostScript in a Box

PostScript is the page-description language from Adobe's laser printers: a stack language, like
Forth, whose programs draw. Field runs it in a box. A PostScript box keeps a stack and a set of
dictionaries between runs, as a REPL does, and draws whatever it strokes and fills into the box's
`postscript` panel as Field lines (`FLine`s).

The interpreter is a Kotlin reimplementation of the one John Earnest builds in
[Let's Write a PostScript Interpreter](https://beyondloom.com/blog/postscript.html), which covers
the language: tokens, the operand and dictionary stacks, procedures, arrays and control flow. Field
adds the half that post leaves out, the graphics state and the path operators, so a program can draw.

## Making a PostScript box

Set a box's Main Language to **PostScript** (the command palette's *Set Main Language: PostScript*)
and the boxes connected under it are PostScript boxes. The root itself stays Kotlin.

Running the whole box starts from an empty stack and an empty page. Running a selection keeps
both, so after a run that defined `double`, selecting `3 double` and running it answers `6`. A run
that finishes cleanly ends with `ok` and the stack it left, the top value in bold:

```diffstatic_runner {data-lang=postscript screenshot="assets/postscript/double.png" capture="editor" outputs="inline" wait="2" padding="10" timeout="180" embed=true name="postscript-double" w="900" h="200" alt="A three-line PostScript box: a comment, /double {2 mul} def, and 3 double. Under the last line the box's marker reads ok · <1> 6, with 6 in bold."}
% This is a line comment
/double {2 mul} def
3 double
```

`/double` pushes a name without running it, `{2 mul}` is a procedure collected but not run, and
`def` binds one to the other in the current dictionary. `3 double` then pushes 3 and runs the
procedure.

## The language

Tokens are separated by whitespace, and `[ ] { }` stand alone, so `[[1 2][3 4]]` needs no spaces.
`%` starts a comment that runs to the end of the line, `/name` is a literal name, and `(text)` is a
string, with nested parentheses and backslash escapes. Numbers are integers or reals; `16#FF` is
hexadecimal.

`[` pushes a mark and `]` collects everything above it into an array, so code inside the brackets
runs: `[2 3 add]` is `[5]`. `==` prints a value the way it would be written, and `=` prints a string
without its parentheses.

```diffstatic_runner {data-lang=postscript screenshot="assets/postscript/arrays.png" capture="editor" outputs="inline" wait="2" padding="10" timeout="180" embed=true name="postscript-arrays" w="900" h="300" alt="A PostScript box defining fib and reverse and printing three arrays with ==. Under its first line the printed output reads [0 1 1 2 3 5 8 13 21 34 55] (marked x2, printed twice) and [33 22 11]; ok follows the last line."}
/fib {dup 2 ge {1 sub dup 1 sub fib exch fib add} {} ifelse} def
[0 1 10 {fib} for] ==
[0 1 9 {2 copy add} repeat] ==

/reverse {[exch dup length 1 sub -1 0 {1 index exch get exch} for pop]} def
[11 22 33] reverse ==
```

These are the post's own examples, and Field's tests check each gives the answer the post gives.

The operators:

| Kind | Operators |
| --- | --- |
| Stack | `pop exch dup copy index roll clear count mark [ ] cleartomark counttomark` |
| Arithmetic | `add sub mul div idiv mod neg abs floor ceiling round truncate sqrt sin cos atan exp ln log rand srand cvi cvr` |
| Comparison and logic | `eq ne gt ge lt le and or xor not true false` |
| Control | `exec if ifelse for repeat loop exit forall bind cvx cvlit type` |
| Dictionaries | `def load store dict begin end currentdict known where` |
| Arrays and strings | `array length get put aload astore getinterval cvs` |
| Output | `= == print pstack stack` |

Angles are in degrees, as everywhere in PostScript. `loop` runs until `exit`, and a run that takes
more than twenty million steps is stopped rather than left to hang Field, with a message saying so.

## Drawing

The graphics operators work on a current path in a coordinate system set by a transformation matrix.
`moveto`, `lineto`, `curveto` and `arc` add to the path in the current coordinates; `rlineto`,
`rmoveto` and `rcurveto` are relative to the current point. `stroke` draws the path as a line,
`fill` fills it, and either one then starts a new path. `translate`, `rotate` and `scale` move the
coordinate system, and `gsave` / `grestore` save and restore it along with the colour, line width
and path.

| Kind | Operators |
| --- | --- |
| Path | `newpath moveto rmoveto lineto rlineto curveto rcurveto arc arcn closepath currentpoint` |
| Painting | `stroke fill eofill showpage` |
| Coordinates | `translate rotate scale initmatrix gsave grestore` |
| Style | `setlinewidth currentlinewidth setgray setrgbcolor sethsbcolor` |

PostScript's y axis points up, and Field's canvas points down, so the drawing is flipped on the way
out and reads the way it would on paper. Line width scales with the coordinate system, as it does on
a printer. PostScript's default colour is black, which vanishes against Field's dark panel, so the
examples here set a colour first.

Recursion is where PostScript drawing earns its keep. `branch` draws a line, moves to its end, and
calls itself twice, once rotated each way, inside `gsave` / `grestore` so that each call returns to
where it started:

```diffstatic_runner {data-lang=postscript screenshot="assets/postscript/tree.png" capture="box" open-outputs=true trim=true wait="2" wait-output=true wait-output-kind="display" timeout="180" embed=true name="postscript-tree" w="820" h="80" alt="The postscript panel of a box: a light green recursive tree, one trunk splitting into two branches at each level, eight levels deep, the branches shortening as they go, spreading into a rounded crown."}
/branch { % length depth branch
  dup 0 gt {
    exch dup 0 0 moveto 0 exch lineto stroke
    dup 0 exch translate
    0.7 mul exch 1 sub
    gsave 25 rotate 2 copy branch grestore
    gsave -25 rotate branch grestore
  } { pop pop } ifelse
} def

0.55 0.85 0.4 setrgbcolor
2 setlinewidth
300 50 translate
100 8 branch
```

Ghostscript (`gs`) draws the same program to the same place: a test runs both and compares their
bounding boxes, which agree to within a point once Ghostscript's allowance for line width is taken
into account. The test is skipped where Ghostscript is not installed.

A rosette is one curve drawn again after each turn of the coordinate system. `repeat` runs the
procedure 36 times, and `rotate` accumulates, so each petal is ten degrees on from the last; the
colour turns with it through `sethsbcolor`:

```diffstatic_runner {data-lang=postscript screenshot="assets/postscript/rosette.png" capture="box" open-outputs=true trim=true wait="2" wait-output=true wait-output-kind="display" timeout="180" embed=true name="postscript-rosette" w="820" h="80" alt="The postscript panel of a box: thirty-six looping petals drawn around a common centre, each ten degrees on from the last, their colour running once round the colour wheel from red through green and blue back to red."}
200 200 translate
1.5 setlinewidth
0 1 35 {
  36 div 1 1 sethsbcolor
  newpath 0 0 moveto 120 0 60 40 0 60 curveto stroke
  10 rotate
} for
```

## Errors

An error is marked on the line of the token that raised it, with PostScript's own name for it where
there is one (`stack underflow`, `undefined`, `nocurrentpoint`). What the box drew before the error
is still drawn.

```diffstatic_runner {data-lang=postscript screenshot="assets/postscript/error.png" capture="editor" outputs="inline" expect-error=true wait="2" padding="10" timeout="180" embed=true name="postscript-error" w="900" h="200" alt="A three-line PostScript box. Under line 2, 10 10 lineto, a red band reads: nocurrentpoint: lineto needs a current point (moveto first)."}
newpath
10 10 lineto
stroke
```

## Where to go next

- [Let's Write a PostScript Interpreter](https://beyondloom.com/blog/postscript.html), John Earnest's
  post, which this interpreter follows for the language.
- The [PostScript Language Reference](https://www.adobe.com/jp/print/postscript/pdfs/PLRM.pdf),
  Adobe's third edition, for what each operator does on a real printer.
- [Forth Examples](forth_examples.md), Field's other stack language, which runs every frame and
  plays steps.
- [Lua in a Box](lua.md), whose `lines(...)` draws into the same kind of panel.
