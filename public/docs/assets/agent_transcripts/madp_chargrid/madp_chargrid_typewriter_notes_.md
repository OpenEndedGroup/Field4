# C64 typewriter

A Commodore 64 boot screen typed in **stream** mode, then 10 PRINT forever. `type()` is an ordinary
local `suspend fun`: `SET` waits a tick and `delay` more `waitFrame()`s slow it down, while
`print` and `setText` write at once. The charset is printable ASCII (`CHARGRID_ASCII`), so
`glyphIndex(ch)` finds any key.
