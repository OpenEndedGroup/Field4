;; `context` looks on this box, then on its parents: a property by name,
;; a Kotlin variable by name, or the nearest value of a class.
(println "speed" (context :speed))
(println "palette" (vec (context "palette")))
(println "missing" (context :no-such-thing 1.0))
