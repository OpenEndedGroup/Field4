__superglobal.kvtable = new Map()

var sendData = (key, value) => {
    __superglobal.kvtable.set(key, value)
    _field.send("kv.receive", {"id":_field.id, "name":key, "value":value})
}

sendData.__doc__ = '`sendData("key", "value")` sets "key" to "value" on all other connected browsers.'

var sendCode = (key, value) => {

    var v2 = eval(value)
    __superglobal.kvtable.set(key, v2)

    _field.send("kv.receive", {"id":_field.id, "name":key, "value":"#$#"+value})
}

sendCode.__doc__ = '`sendCode("key", "some code")` runs "some code" and also sends that code to run everywhere else'

__superglobal.sendData = sendData

__superglobal.kv = {}
window.__kv = __superglobal.kv
window.kv = __superglobal.kvtable

var onData = new Proxy(new Map(), idempotencyHandler(function (n, is, was) { }));
__superglobal.onData = onData

__superglobal.kv.receive = (key, value) => {
    console.log(" inside kv receive ", key, value)

    if (value && value.startsWith && value.startsWith("#$#"))
    {
        var v2 = eval(value.substring(3))
        __superglobal.kvtable.set(key, v2)
        dispatchTo({"key":key, "value":value}, onData, "onData")
    }
    else
    {
        __superglobal.kvtable.set(key, value)
        dispatchTo({"key":key, "value":value}, onData, "onData")
    }
}
