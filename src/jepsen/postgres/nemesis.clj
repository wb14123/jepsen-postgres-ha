(ns jepsen.postgres.nemesis
  "Nemeses for Stolon"
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer [info warn]]
            [dom-top.core :refer [real-pmap]]
            [jepsen [nemesis :as n]
                    [net :as net]
                    [util :as util]]
            [jepsen.generator :as gen]
            [jepsen.nemesis [combined :as nc]
                            [time :as nt]]))

(defn nemesis-package
  "Constructs a nemesis and generators for Stolon."
  [opts]
  (info "nemesis opts:" opts)
  (let [opts (update opts :faults set)]
    (nc/nemesis-package opts)))


(defn packet-package
  "A nemesis and generator package that disrupts packets,
   e.g. delay, loss, corruption, etc.

   Opts:
   ```clj
   {:packet
    {:targets      ; A collection of node specs, e.g. [:one, :all]
     :behaviors [  ; A collection of network behaviors that disrupt packets, e.g.:
      {}                         ; no disruptions
      {:delay {}}                ; delay packets by default amount
      {:corrupt {:percent :33%}} ; corrupt 33% of packets
      ; delay packets by default values, plus duplicate 25% of packets
      {:delay {},
       :duplicate {:percent :25% :correlation :80%}}]}}
  ```
  See [[jepsen.net/all-packet-behaviors]].

  Additional options as for [[nemesis-package]]."
  [opts]
  (let [needed?   ((:faults opts) :packet)
        db        (:db opts)
        targets   (:targets   (:packet opts) (nc/node-specs db))
        behaviors (:behaviors (:packet opts) [{}])
        start {:type  :info
               :f     :start-packet
               :value [(rand-nth targets) (rand-nth behaviors)]}
        stop      {:type  :info
                   :f     :stop-packet
                   :value nil}
        gen       (gen/once start)]
    {:generator       (when needed? gen)
     :final-generator (when needed? stop)
     :nemesis         (nc/packet-nemesis db)
     :perf            #{{:name  "packet"
                         :start #{:start-packet}
                         :stop  #{:stop-packet}
                         :color "#D1E8A0"}}}))



(defn db-package
  "A nemesis and generator package for acting on a single DB. Options are from
  nemesis-package."
  [opts]
  (let [needed? (some #{:kill :pause} (:faults opts))
        db        (:db opts)
        kill-targets  (:targets (:kill opts)  (nc/node-specs db))
        start  {:type :info, :f :start, :value :all}
        kill   {:type   :info
                :f      :kill
                :value  (rand-nth kill-targets)}
        {:keys [_ final-generator]} (nc/db-generators opts)
        generator (gen/cycle [
                              (gen/sleep 30)
                              (gen/once kill)
                              (gen/sleep 60)
                              (gen/once start)
                              ]
                             )
        nemesis   (nc/db-nemesis (:db opts))]
    {:generator       (when needed? generator)
     :final-generator (when needed? final-generator)
     :nemesis         nemesis
     :perf #{{:name   "kill"
              :start  #{:kill}
              :stop   #{:start}
              :color  "#E9A4A0"}
             {:name   "pause"
              :start  #{:pause}
              :stop   #{:resume}
              :color  "#A0B1E9"}}}))


(defn nemesis-packages
  "Just like nemesis-package, but returns a collection of packages, rather than
  the combined package, so you can manipulate it further before composition."
  [opts]
  (let [faults   (set (:faults opts [:partition :packet :kill :pause :clock :file-corruption]))
        opts     (assoc opts :faults faults)]
    [
     (packet-package opts)
     (db-package opts)]))

(defn slow-kill-package
  [opts]
  (nc/compose-packages (nemesis-packages opts))
  )


