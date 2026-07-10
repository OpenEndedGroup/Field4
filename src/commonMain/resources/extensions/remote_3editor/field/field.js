console.log(" **Field is booting up** ");

var _field = {};
var frame = 0
var lastUpdatedAt = -1
var thatImageBuffer
var SCENE
var CAMERA
var _r
var globlSourceCache
var FIELD
var globalCodeName

__beginService()

_field.log = function (s) {
        _field.send("log", s);
};

_field.error = function (s) {
        _field.send("error", encodeURIComponent(s));
};

_field.evalRequestWS = function (id, onupdate) {

        _field.id = id;

        _field.pathname = new URL(document.URL).hostname
        var alternative = getQueryVariable("field")
        if (alternative)
                _field.pathname = alternative;

        FIELD = _field.pathname

        let port = ""
        if (document.location.port)
                port = ":" + document.location.port

        if (document.location.protocol == "http:")
                _field.socket = new WebSocket("ws://" + _field.pathname + port+"/ws");
        else
                _field.socket = new WebSocket("wss://" + _field.pathname + port+"/ws");

        _field.socket.binaryType = "arraybuffer";

        _field.send = function (address, obj) {
                var t = JSON.stringify({
                        address: address,
                        payload: obj,
                        from: _field.id
                })
                // console.log("SEND ", obj)
                _field.socket.send(t)
        };

        _field.socket.onopen = function (e) {
                _field.isOpen = true
                console.log(" sending initialize")
                _field.send("initialize", id)
        };

        print = (x) => {

                safe = function(n)
                {
                        if (n && n.length && n.length>120000) return "[... "+(""+n).substring(0, 500)+" +"+(n.length)+" characters long ...]";
                        else if (typeof(n)=='undefined') return n;
                        else return "<json>"+n;
                }

                safe2 = function(n)
                {
                        if (n && n.length && n.length>400) return "[... <i>+"+(""+n).substring(0, 390)+" +"+(n.length)+"</i> ...]";
                        else if (typeof(n)=='undefined') return n;
                        else return "<json>"+n;
                }


                if (typeof __sandbox__ !== 'undefined' && __sandbox__.__returnTo) {
                        _field.send(__sandbox__.__returnTo, {
                                message: safe(betterPretty(x, 2, "JSON", false)),
                                kind: "SUCCESS"
                        })
                } else {
                        _field.send(_field.globalReturnTo, {
                                message: safe(betterPretty(x, 2, "JSON", false)),
                                kind: "SUCCESS"
                        })
                }
        }

        console.log(" -- setting up logging support ")
        __superglobal.print = print
        __superglobal.log = print

        window.print = print
        window.log = print

        _field.socket.onmessage = function (e) {

                // this needs to be per geometry not globally and not per channel
                // if (lastUpdatedAt == frame)
                //     return

                // lastUpdatedAt = frame

                if (ArrayBuffer.prototype.isPrototypeOf(e.data)) {
                        _field.arraybuffer = e.data
                        // console.log(e.data)
                        var d = new DataView(e.data)
                        // console.log("-> d= ")
                        // console.log(d)
                        if (d.byteLength < 64) return
                        var dim = d.getUint32(0, true)
                        var typeCode = String.fromCharCode(d.getUint16(4, true))
                        var nameLen = d.getUint8(6, true)
                        var elementDim = d.getUint32(7, true)

                        // console.log(d)
                        // console.log(dim)
                        // console.log(elementDim)
                        // console.log(typeCode)
                        // console.log(nameLen)

                        var name = ""
                        for (var q = 0; q < nameLen; q++) {
                                name += String.fromCharCode(d.getUint16(11 + q * 2, true))
                        }

                        // console.log(" channel is :")
                        // console.log(name)

                        updateBuffer(e.data, d, name, typeCode, dim, elementDim, 128)
                } else {
                        let edata = JSON.parse(e.data)
                        _field.globalReturnTo = edata.returnTo
                        _field.__var0 = edata.__var0
                        _r = undefined
                        __superglobal._r = undefined // this means that writes to _r go into window.
                        __superglobal._field = _field

                        var code = edata.code + "\n//# sourceURL=" + edata.codeName + "\n";
                        let sourceCache = {};
                        sourceCache[edata.codeName] = edata.codeName
                        globlSourceCache = sourceCache
                        if (edata.codeName &&  edata.codeName.indexOf("|")!=-1) {
                                context = edata.codeName.split("|")[1]
                        }
                        //console.log(context)


                        if (!code) return

                        globalCodeName = edata.codeName

                        console.log("CODE", code, edata.noSandbox, e.data)
                        // try {
                        var ret
                        if (edata.noSandbox)
                                ret = window.eval(code);
                        else {
                                if (!edata.code) return

                                if (edata.code.trim().split("\n").length < 2) {
                                        console.log(" single line run")
                                        ret = runInSandbox(context, code);
                                } else {
                                        console.log(" multi-line generator run", edata.code.trim().split("\n").length, edata.code.trim())

                                        ret = runInSandbox_generator(context, code);
                                        if (ret) {
                                                var gen = ret()
                                                var v = gen.next()
                                                ret = v.value
                                                if (!v.done) {
                                                        console.log(" generator is go ")

                                                        var task = new Task(edata.codeName, function () {
                                                                return "SKIP"
                                                        }, gen.next.bind(gen), function () {
                                                        }, function () {
                                                                _field.send(edata.returnTo, {
                                                                        message: "OK",
                                                                        kind: "RUN-STOP",
                                                                        altName: ""
                                                                })
                                                        }, function (e) {
                                                                StackTrace.fromError(e, {sourceCache: sourceCache}).then(stack => {

                                                                        // altFileName = filename
                                                                        altLineNumber = stack[0].lineNumber || -1

                                                                        for (var s of stack) {
                                                                                if (s.fileName && s.fileName.startsWith("box")) {
                                                                                        console.log(" possible box filename output :", s.fileName, s.lineNumber)
                                                                                        // altFileName = s.fileName
                                                                                        altLineNumber = s.lineNumber
                                                                                        break;
                                                                                }
                                                                        }

                                                                        _field.send(edata.returnTo, {
                                                                                message: e.message,
                                                                                line: (altLineNumber || -1),
                                                                                kind: "ERROR"
                                                                        })
                                                                        console.log(stack)
                                                                })
                                                        })
                                                        if (typeof (edata.timeStart) != 'undefined')
                                                                task.configureTimeRemapping(edata.timeStart, edata.timeEnd)

                                                        _field.send(edata.returnTo, {
                                                                message: "OK",
                                                                kind: "RUN-START",
                                                                altName: ""
                                                        })
                                                }
                                        }

                                }
                        }

                        if (ret)
                            // _field.send(edata.returnTo, { message: safe(pretty(ret, 2, "PRINT", true) ), kind: "SUCCESS" })
                                _field.send(edata.returnTo, {
                                        message: /*safe*/"<json>" + (betterPretty(ret, 2, "JSON", false)),
                                        kind: "SUCCESS"
                                })
                        else
                                _field.send(edata.returnTo, {message: "OK", kind: "SUCCESS"})


                        if (typeof (_r) != 'undefined' && edata.launchable) {
                                if (_r.length == 3) {
                                        var task = new Task(edata.codeName, _r[0], _r[1], _r[2], function () {
                                                    _field.send(edata.returnTo, {
                                                            message: "OK",
                                                            kind: "RUN-STOP",
                                                            altName: ""
                                                    })
                                            }, function (e) {
                                                    StackTrace.fromError(e, {sourceCache: sourceCache}).then(stack => {
                                                            // altFileName = filename
                                                            altLineNumber = stack[0].lineNumber || -1

                                                            for (var s of stack) {
                                                                    if (s.fileName && s.fileName.startsWith("box")) {
                                                                            console.log(" possible box filename output :", s.fileName, s.lineNumber)
                                                                            // altFileName = s.fileName
                                                                            altLineNumber = s.lineNumber
                                                                            break;
                                                                    }
                                                            }

                                                            _field.send(edata.returnTo, {
                                                                    message: e.message,
                                                                    line: (altLineNumber || -1),
                                                                    kind: "ERROR"
                                                            })
                                                            console.log(stack)
                                                    })
                                            }
                                        )
                                        if (typeof (edata.timeStart) != 'undefined')
                                                task.configureTimeRemapping(edata.timeStart, edata.timeEnd)
                                } else {
                                        var task = launch(_r, "", false)
                                        if (typeof (edata.timeStart) != 'undefined')
                                                task.configureTimeRemapping(edata.timeStart, edata.timeEnd)

                                }

                                _field.send(edata.returnTo, {message: "OK", kind: "RUN-START", altName: ""})

                        }
                        if (onupdate) onupdate()
                        // }
                        // catch (e) {
                        // 	if (e.name && e.name=="SyntaxError") throw e // syntax errors have no line number for eval

                        // 	StackTrace.fromError(e, { sourceCache: sourceCache }).then(stack => {
                        // 		_field.send(edata.returnTo, { message: e.message, line: stack[0].lineNumber, kind: "ERROR" })
                        // 		console.log(stack)
                        // 	})
                        // }
                }

                // _field.send("alive", id)
        };

        _field.socket.onclose = function (e) {
                console.log("closed ?", e)
                // _messageBus.publish("status", "<span class='error'>Disconnected from Field</span>");
                document.title = "Field / DISCONNECTED (reload the page?)";
                _field.isOpen = false

                // start it up again ?
                setTimeout(() => {
                        console.log(" trying to reconnect on close")
                        if (!_field.isOpen)
                                _field.evalRequestWS(_field.id)
                }, 1000)
        };

        _field.socket.onerror = function (e) {
                // _messageBus.publish("status", "<span class='error'>Disconnected from Field</span>");
                document.title = "Field / DISCONNECTED (reload the page?)";
                _field.isOpen = false


                // // start it up again
                // setTimeout(() => {
                //     console.log(" trying to reconnect on error")
                //     if (!_field.isOpen)
                //         _field.evalRequestWS(_field.id)
                // }, 1000)
        }
};

var betterPretty = (a, b, c, d) => {

        if (typeof (a) == "undefined") return "(undefined)";
        try {
                if (a.description) return a.description()
        } catch (e) {
                console.error(" error in tostring for ", a)
                console.error(e)
                console.error(" falling through ")
        }

        return pretty(a, b, c, d).replace(/circular reference to \[object Object\]/g, "'[circular reference]'")
}


window.onerror = (message, filename, lineno, colno, e) => {
        console.log(" global error handler called ")
        console.log(message, filename, lineno, colno, e)

        console.log(e);

        // if (e && e.__proto__.name === "SyntaxError") {
        // 	_field.send(_field.globalReturnTo, { message: message, line: lineno, kind: "ERROR" })
        // }
        // else
        if (e)
                StackTrace.fromError(e, {sourceCache: globlSourceCache}).then(stack => {

                        altFileName = filename
                        // altLineNumber = stack[0].lineNumber || -1
                        altLineNumber = lineno

                        for (var s of stack) {
                                console.log(s.fileName)
                                if (s.fileName && s.fileName.startsWith("box")) {
                                        console.log(" possible box filename output :", s.fileName, s.lineNumber)
                                        altFileName = s.fileName
                                        altLineNumber = s.lineNumber
                                        break;
                                }
                        }

                        _field.send(_field.globalReturnTo, {
                                message: e.message,
                                line: (altLineNumber || -1),
                                kind: "ERROR"
                        })

                        console.log(stack)
                })
        else {
                console.log(" no actual error object")
                _field.send(_field.globalReturnTo, {message: message, line: lineno, kind: "ERROR"})
        }
        if (e) {
                console.log(" about to send field error " + e.message + " on line :" + e.stack)
                // _field.error(e.message + " on line:" + e.stack);
        }
};


function getQueryVariable(variable) {
        var query = window.location.search.substring(1);
        var vars = query.split('&');
        for (var i = 0; i < vars.length; i++) {
                var pair = vars[i].split('=');
                if (decodeURIComponent(pair[0]) == variable) {
                        return decodeURIComponent(pair[1]);
                }
        }
        console.log('Query variable %s not found', variable);
}



