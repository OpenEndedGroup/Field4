import afield.io.mqtt.*

/*
Every temperature, whichever room it comes from: `+` stands for one level of the topic.
The callback runs on Field's main loop, so it can update outputs directly.
Re-running this box replaces the callback rather than adding a second one.
*/
var count = 0
mqtt.subscribe("sensors/+/temp") { topic, message ->
    count++
    markdown("**$topic** = ${message.number?.f2}  _(message $count)_")
}
