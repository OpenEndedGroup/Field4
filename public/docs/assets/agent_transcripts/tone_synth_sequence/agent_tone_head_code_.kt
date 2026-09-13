// The head that plays the sound. It loads Tone.js and connects over the RemoteJS
// transport; the // {{ tone }} box below compiles to JS and runs in here.
display("tone-head", """
<iframe
  src="/extensions/remote_ar/index.html?debug=1"
  allow="xr-spatial-tracking; fullscreen; autoplay"
  data-afield-autosize="off"
  style="display:block; width:640px; height:400px; border:0; background:#000">
</iframe>
""".trimIndent())
displayOpen("tone-head")
