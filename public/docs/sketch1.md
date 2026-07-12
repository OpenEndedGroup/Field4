
<link rel="stylesheet" href="assets/theme/darcula.css">
<link rel="stylesheet" href="assets/theme/material.css">
<link rel="stylesheet" href="assets/codemirror.css">
<script src="assets/codemirror.js"></script>
<script src="assets/mode/javascript/javascript.js"></script>
<script src="assets/mode/htmlmixed/htmlmixed.js"></script>
<script src="assets/mode/xml/xml.js"></script>
<script src="assets/mode/css/css.js"></script>

# Sketch 1 — a warm up

This page is the _easiest_ gateway to completing Sketch1 — write some code, and put something into an AR space. At the very least it cobbles together the technology stack required to have a webpage _lead to_ AR. This right now is working as a portal to a webserver that takes the code below, wraps it up in a bespoke webpage and serves that, and any associated resources, up to a device when you click on the "go" button below. The "language" for writing code here is [AFrame](https://aframe.io/) which is a wonderful component based graphics framework. Partly what we are rehearsing here is grappling with a large project — its documentation, its legibility by LLMs, and the "debug" cycle that we can build around it.

Notes:

1. This little trick uses the [Variant Launch AR](https://launch.variant3d.com/) app — which (if you must know) wraps a iOS WKWebView — a version of Safari that is embeddable in apps — in just enough secret sauce connect Apple's proprietary AKRit to a WebXR polyfill. You'll likely want to go install that now from the App Store. Androids — likely to "just work" assuming that WebXR is working on your phone.

2. the "go" button will take your browser to a page that has your code in it, and, optionally, a resource uploaded to it. This is useful because the Variant Launch app doesn't support file uploads (right now). So whatever you upload, has to be _before_ the page loads. 

3. the "qr code" button takes you to a screen with a qr code on it — that code takes you to your page. This indirection is useful if you don't want to write the code on the device doing the AR.

4. this _isn't_ how I do AR. I use Field so dynamically send code to running browsers. That's on offer, but this is 10x easier to set up.

<hr>
<form id="form" action="https://untidy.openendedgroup.com/ann" method="POST" enctype="multipart/form-data">
    <a href="https://aframe.io/">AFrame</a> code
    <textarea id="code" name="code"
        style="width:100%;height:500px;font-family: 'Iosevka', 'Iosevka Web', fixed;font-size:80%">
<a-scene xr-mode-ui="enabled: true;XRMode: ar"
    device-orientation-permission-ui="enabled: false"
    webxr="requiredFeatures:hit-test,local-floor;referenceSpaceType:local-floor;">

    <a-sphere position="0 1.25 -5" radius="1.25" color="#EF2D5E" shadow></a-sphere>
    
    <a-cylinder position="1 0.75 -3" radius="0.5" height="1.5" color="#FFC65D" shadow></a-cylinder>
    
    <a-box position="-1 0.5 -3" rotation="0 45 0" color="#4CC3D9" shadow></a-box>
</a-scene>
    </textarea>

    <hr>
    <label for="picture">Upload an asset / photograph</label>
    <input id="picture" type="file" name="picture" accept="image/*" capture="environment"/>
    <label for="name">And give it a name</label>
    <input id="name" type="text" name="name" value="image"/>
    <br>
    <br>
    <label for="picture"></label><small>You can write <code>{{ name }}</code> in the code above to refer to this image, that way you can texture a plane in space with something that you upload.</small>
    <input type="submit" formaction="https://untidy.openendedgroup.com/ann" style="text-align: right;float:right;padding:20px;" value="Go" onclick="submitting()">
    <input type="submit" formaction="https://untidy.openendedgroup.com/ann_qr" style="text-align: right;float:right;padding:20px;" value="Get QR code" onclick="submitting()">
</form>
<hr>

<script>

     editor = CodeMirror.fromTextArea(document.getElementById("code"), {
                mode: "htmlmixed",
                theme: "material",
                font: "Iosevka Web",
                lineWrapping: true,
                lineNumbers: true,
                styleActiveLine: true,
                matchBrackets: true,

                extraKeys: {
                        "Ctrl-Space": "autocomplete"
                },
        });

    editor.getWrapperElement().style["font-size"] = "12px";

</script>

## some starting places

### a plane in space with an image on it:

```diffstatic {data-lang=html}
<a-scene xr-mode-ui="enabled: true;XRMode: ar"
    device-orientation-permission-ui="enabled: false"
    webxr="requiredFeatures:hit-test,local-floor;referenceSpaceType:local-floor;">

<a-assets><img id="someImage" src="{{ image }}"></a-assets>
<a-plane width=1 height=1 opacity=0.6 src="#someImage"></a-plane>
    
</a-scene>
```
