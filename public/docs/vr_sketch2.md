# VR Production Sketch 2, in Field·4

This is the first XR drawing exercise for Field. It starts from the `field.sketch2` template, opens the OpenXR graphics path, and gets one controller drawing an `FLine` in space.

The goal is modest: get a line on the headset display, understand the loop that produces it, then change one thing.

## Getting to XR "hello world"

Start by [running Field](survivalGuide.md). The server prompt usually offers a link to the last opened document:

![](assets/screenshot_22.png)

You can open that document, or start a new one with a URL of this form:

```diffstatic {data-lang=text}
https://localhost:9092/index.html?file=MY_AWESOME_NEW_FILE
```

A new document starts as an empty canvas:

![](assets/screenshot_23.png)

Press `F1`, start typing the template name, and insert `field.sketch2`:

![](assets/insertTemplate.apng)

After insertion, the document should look close to this read-only preview:

<iframe
  src="/offline.html?file=/field.sketch2.edn"
  title="Offline Viewer"
  style="width:100%; height:720px; border:1px solid #444; border-radius:8px; background:#111;"
  loading="lazy"
  allowfullscreen>
</iframe>

If Field is running, the preview's "Open in Field4" button opens a fresh document with the template inserted.

## Running it

Once the template is loaded, run the box named `run me!`. Alt/option-click and hold on the box. It should turn striped green while it starts. Give it a few seconds; the box is opening the XR and graphics resources.

The box named `YOUR CODE` is where the drawing code goes.

The starter loop is short enough to read line by line:

```diffstatic {data-lang=kotlin}
:::static
lines.clear() // clear the list of lines to draw

loop { // do this forever (or until we stop this box)
    
  var f = FLine()    // make a new line for our drawing

  while(stereo.controllerState().triggerValue > 0.0) { // while the trigger is down
    
    var at = stereo.controllerState().position // get its position

    f.lineTo(at) // extend the drawing to this position

    _print(at) // print out the position (so we know what's going on)

    lines["ourLine"] = f // put this line onto the screen under the name "ourLine"

    frame() // WAIT for a frame to come out of the screen
  }

  while(stereo.controllerState().triggerValue <= 0.0) { // while the trigger isn't down
    frame() // WAIT for a frame to come out of the screen
    _print("waiting...") // and print waiting so we know what's going on
  }
}
```

Launch this code with `alt-up` in the editor, or with an `alt-swipe-up` on the box. Stop it with `alt-down`.

## Sketch 2, then

The exercise is to make one intervention in the loop. Good first changes, roughly from easiest to more open-ended:

* Multiple lines: draw more than one line.

* Color: set the color of a line, or part of a line.

* Place: draw somewhere else, or draw a mirrored copy.

* Texture: make the mark thicker, rougher, or more tangled.

* Drawing dynamics: smooth the line as it is drawn, or make it spiky.

* Animation dynamics: keep the line moving after drawing stops.

Most of those changes need parameters. A color needs red, green, blue, and opacity. Spikiness needs a strength. Texture needs density or scale. The useful question is where those numbers come from.

* Direct sensed values: controller position, trigger squeeze, controller orientation, the other controller, and the head pose.

* Computed values: hand velocity, distance between hands, distance from hand to head, whether one hand is pointing at the other, and so on.

One complete sketch might be: opacity comes from drawing speed, finished lines fall toward the floor, and each line keeps some of the direction it had while being drawn. Another might mirror strokes around the axis between the two hands. Another might use the speed of one hand to set the thickness of the other.

The example cookbook is this document:

<iframe
  src="/offline.html?file=/field.sketch2_examples.edn"
  title="Offline Viewer"
  style="width:100%; height:720px; border:1px solid #444; border-radius:8px; background:#111;"
  loading="lazy"
  allowfullscreen>
</iframe>

It includes output examples and small computations such as speed. On a machine without XR support, use the no-XR version instead:

<iframe
  src="/offline.html?file=/field.sketch2_examples_noXR.edn"
  title="Offline Viewer"
  style="width:100%; height:720px; border:1px solid #444; border-radius:8px; background:#111;"
  loading="lazy"
  allowfullscreen>
</iframe>
