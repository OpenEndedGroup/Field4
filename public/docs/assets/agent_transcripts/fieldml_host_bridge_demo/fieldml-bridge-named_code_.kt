// Named arguments, which are also the only way to leave a Kotlin default alone.
//
// Write a RECORD where a tuple would go. Plain reflection sees neither a
// parameter's name nor its default -- `Img.index(x, y, c)` is all it can tell
// you -- so this goes through Kotlin's own reflection, and naming the arguments
// is what makes the defaults reachable: a positional call has no way to say
// which parameter it is skipping.
let Img = hostClass "afield.stdlib.Img"
let Imaging = hostClass "afield.stdlib.Imaging_fieldKt"

let img = hostNew {width = 2, height = 2, channels = 3, data = [
  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,
  0.0, 0.0, 0.0,  0.0, 0.0, 0.0
]} Img

// `Img.index(x, y, c = 0)`. The first line leaves `c` to Kotlin -- there is no
// positional call that can. The second shows that order is not position.
let defaulted = img |> hostCall "index" {x = 1, y = 0}
let reordered = img |> hostCall "index" {c = 2, y = 0, x = 1}

// An extension, three parameters, all with defaults: positionally you must write
// `("magma", hostNull, hostNull)`, and the two hostNulls are the tell that
// something is missing. Named, `lo` and `hi` really are computed from the image.
let gray = hostNew {width = 2, height = 2, channels = 1, data = [0.0, 0.25, 0.5, 1.0]} Img
let coloured = gray |> hostExt Imaging "colormap" {name = "magma"}

// A name reflection does not know is a compile error where it is written, and it
// names what you gave. Try changing `x` to `xx` above.
{
  defaulted = defaulted
  reordered = reordered
  colouredChannels = coloured |> hostGet "channels"
  note = "a record spreads by name; a tuple still spreads by position"
}
