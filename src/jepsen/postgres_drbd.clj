(ns jepsen.postgres-drbd
  (:require [jepsen.cli :as cli]
            [jepsen.tests :as tests]
            )
  )

(defn postgres-test
  [opts]
  (merge tests/noop-test
         {:pure-generators true}
         opts))

(defn -main
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn postgres-test})
            args))
