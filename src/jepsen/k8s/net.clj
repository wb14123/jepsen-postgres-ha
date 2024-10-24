(ns jepsen.k8s.net
  (:require (jepsen [control :as c]
                    [net :as net])
            [jepsen.net.proto :refer [Net PartitionAll]]
            ))

(defn- iptables-wrap
  [net]
  "Just delete iptables rules instead of chains"
  (reify Net
    (drop! [_ test src dest] (net/drop! net test src dest))

    (heal! [_ test]
      (c/with-test-nodes test (c/su (-> (c/exec :iptables :-L)))))

    (slow! [_ test] (net/slow! net test))

    (slow! [_ test opts] (net/slow! net test opts))

    (flaky! [_ test] (net/flaky! net test))

    (fast! [_ test] (net/fast! net test))

    (shape! [_ test nodes behavior] (net/shape! net test nodes behavior))

    PartitionAll
    (drop-all! [_ test grudge] (net/drop-all! net test grudge))))

(def iptables (iptables-wrap net/iptables))