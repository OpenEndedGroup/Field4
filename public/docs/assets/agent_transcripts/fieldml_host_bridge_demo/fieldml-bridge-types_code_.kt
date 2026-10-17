let Img = hostClass "afield.media.image.Img"
let img = hostNew (8, 4, 1, tabulate (fun i -> toFloat i / 32.0) 32) Img

// Try it: put the caret after the quote in hostGet "" and ask for
// completion — it offers Img's members with their types. Then misspell
// one (widht) and the box reports a type error before it runs.
{
  width = img.width
  height = img.height
  samples = img.pixelCount
  scalar = img.isScalar
  area = img.width * img.height
  first = img |> hostCall "at" (0, 0, 0)
  boxName = thisBox |> hostGet "name"
}