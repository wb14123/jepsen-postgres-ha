(ns jepsen.k8s.net
  (:require
    [clojure.tools.logging :refer [info warn]]
    [jepsen [control :as c]
     [net :as net]]
    [jepsen.net.proto :as p :refer [Net PartitionAll]]
    ))

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

(def iptables (iptables-wrap net/iptables))