;; The marker lands on line 6, inside mean, where the division failed --
;; not on line 8, where mean was called.
(def readings [])

(defn mean [values]
  (/ (reduce + values) (count values)))

(mean readings)
