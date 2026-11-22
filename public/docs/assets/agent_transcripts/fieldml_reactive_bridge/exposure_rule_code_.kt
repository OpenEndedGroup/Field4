let measured = propDown "lightLevel" |> hostAsFloat
let mapped = 0.1 + 0.9 * measured * measured
setPropDown "mappedOpacity" mapped
