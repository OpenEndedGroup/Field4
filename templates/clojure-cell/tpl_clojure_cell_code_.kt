;; A Clojure box: run it, or select a form and run only that.
(def state (atom {:freq 3.0}))

(defn redraw [{:keys [freq]}]
  (display "main" (format "<b>%.1f Hz</b>" freq)))

(slider "frequency" #(swap! state assoc :freq %) :min 1 :max 10 :step 0.1 :value 3.0)
(add-watch state :redraw (fn [_ _ _ now] (redraw now)))
(redraw @state)
