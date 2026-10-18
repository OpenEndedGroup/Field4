;; The same controls a Kotlin box draws, calling back into Clojure.
(def state (atom {:freq 3.0 :square false}))

(defn redraw [{:keys [freq square]}]
  (display "main" (format "<b>%s</b> at %.1f Hz" (if square "square" "sine") freq)))

(slider "frequency" #(swap! state assoc :freq %) :min 1 :max 10 :step 0.1 :value 3.0)
(checkbox "square wave" #(swap! state assoc :square %))

(add-watch state :redraw (fn [_ _ _ now] (redraw now)))
(redraw @state)
