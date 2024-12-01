(ns jepsen.k8s.net
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :refer [info warn]]
    [jepsen [control :as c]
     [net :as net]]
    [jepsen.control.net :as control.net]
    [jepsen.net.proto :as p :refer [Net PartitionAll]]
    ))

(defn- behaviors->netem
  "Given a map of behaviors, returns a sequence of netem options."
  [behaviors]
  (->>
    ; :reorder requires :delay
    (if (and (:reorder behaviors)
             (not (:delay behaviors)))
      (assoc behaviors :delay (:delay net/all-packet-behaviors))
      behaviors)
    ; fill in all unspecified opts with default values
    (reduce (fn [acc [behavior opts]]
              (assoc acc behavior (merge (behavior net/all-packet-behaviors) opts)))
            {})
    ; build a tc cmd line combining all behaviors
    (reduce (fn [args [behavior {:keys [time jitter percent correlation distribution rate] :as _opts}]]
              (case behavior
                :delay
                (concat args [:delay time jitter correlation :distribution distribution])
                (:loss :corrupt :duplicate :reorder)
                (concat args [behavior percent correlation])
                :rate
                (concat args [:rate rate])))
            [])))



(defn- net-shape!
  "Shared convenience call for iptables/ipfilter. Shape the network with tc
  qdisc, netem, and filter(s) so target nodes have given behavior."
  [_net test targets behavior dev]
  (let [results (c/on-nodes test
                          (fn [test node]
                            (let [nodes   (set (:nodes test))
                                  targets (set targets)
                                  targets (if (contains? targets node)
                                            (disj nodes node)
                                            targets)
                                  ]
                              ; start with no qdisc
                              (net/qdisc-del dev)
                              (if (and (seq targets)
                                       (seq behavior))
                                ; node will need a prio qdisc, netem qdisc, and a filter per target
                                (do
                                  (c/su
                                    ; root prio qdisc, bands 1:1-3 are system default prio
                                    (c/exec :tc
                                          :qdisc :add :dev dev
                                          :root :handle "1:"
                                          :prio :bands 4 :priomap 1 2 2 2 1 2 0 0 1 1 1 1 1 1 1 1)
                                    ; band 1:4 is a netem qdisc for the behavior
                                    (c/exec :tc
                                          :qdisc :add :dev dev
                                          :parent "1:4" :handle "40:"
                                          :netem (behaviors->netem behavior))
                                    ; filter dst ip's to netem qdisc with behavior
                                    (doseq [target targets]
                                      (c/exec :tc
                                            :filter :add :dev dev
                                            :parent "1:0"
                                            :protocol :ip :prio :3 :u32 :match :ip :dst (control.net/ip target)
                                            :flowid "1:4")))
                                  targets)
                                ; no targets and/or behavior, so no qdisc/netem/filters
                                nil))))]
    ; return a more readable value
    (if (and (seq targets) (seq behavior))
      [:shaped   results :netem (vec (behaviors->netem behavior))]
      [:reliable results])))

(defn iptables-with-dev
  "Default iptables (assumes we control everything). Take network device as parameter."
  [dev]
  (reify Net
    (drop! [net test src dest]
      (c/on-nodes test [dest]
                (fn [test node]
                  (c/su (c/exec :iptables :-A :INPUT :-s (control.net/ip src) :-j
                            :DROP :-w)))))

    (heal! [net test]
      (c/with-test-nodes test
                       (c/su
                         (c/exec :iptables :-F :-w)
                         ; (exec :iptables :-X :-w)
                         )))

    (slow! [net test]
      (c/with-test-nodes test
                       (c/su (c/exec :tc :qdisc :add :dev dev :root :netem :delay :50ms
                                 :10ms :distribution :normal))))

    (slow! [net test {:keys [mean variance distribution]
                      :or   {mean         50
                             variance     10
                             distribution :normal}}]
      (c/with-test-nodes test
                       (c/su (c/exec :tc :qdisc :add :dev dev :root :netem :delay
                                 (str mean "ms")
                                 (str variance "ms")
                                 :distribution distribution))))

    (flaky! [net test]
      (c/with-test-nodes test
                       (c/su (c/exec :tc :qdisc :add :dev dev :root :netem :loss "20%"
                                 "75%"))))

    (fast! [net test]
      (c/with-test-nodes test
                       (try
                         (c/su (c/exec :tc :qdisc :del :dev dev :root))
                         (catch RuntimeException e
                           (if (re-find #"Error: Cannot delete qdisc with handle of zero."
                                        (.getMessage e))
                             nil
                             (throw e))))))

    (shape! [net test nodes behavior]
      (net-shape! net test nodes behavior dev))

    PartitionAll
    (drop-all! [net test grudge]
      (c/on-nodes test
                (keys grudge)
                (fn snub [_ node]
                  (when (seq (get grudge node))
                    (c/su (c/exec :iptables :-A :INPUT :-s
                              (->> (get grudge node)
                                   (map control.net/ip)
                                   (str/join ","))
                              :-j :DROP :-w))))))))


(defn- iptables-wrap
  [base]
  "Just delete iptables rules instead of chains"
  (reify Net
    (drop! [_ test src dest] (p/drop! base test src dest))

    (heal! [_ test]
      (c/with-test-nodes test (c/su (c/exec :iptables :-F :-w))))

    (slow! [_ test] (p/slow! base test))

    (slow! [_ test opts] (p/slow! base test opts))

    (flaky! [_ test] (p/flaky! base test))

    (fast! [_ test] (p/fast! base test))

    (shape! [_ test nodes behavior] (p/shape! base test nodes behavior))

    PartitionAll
    (drop-all! [_ test grudge]
      (info "Dropping all with grudge:" grudge)
      (p/drop-all! base test grudge))))

(def iptables (iptables-wrap (iptables-with-dev :eth1)))