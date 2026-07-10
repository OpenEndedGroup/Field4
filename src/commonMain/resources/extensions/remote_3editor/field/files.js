
var mapDirectory = (dirname, to) => {
    _field.send("files.map", { mapFrom: to, mapTo: dirname })
}
mapDirectory.__doc__ = 'The `mapDirectory` function exposes some part of your desktop computer (the machine running Field) to devices connected to our webserver. For example `mapDirectory("/Users/marc/Desktop", "/assets")`.'
__superglobal.mapDirectory = mapDirectory

