(ns jepsen.postgres-drbd
  (:require [jepsen [cli :as cli]
             [tests :as tests]
             [postgres-db :as pdb]
             ]
            [jepsen.os.debian :as debian]
            )
  )

(defn postgres-test
  [opts]
  (merge tests/noop-test
         opts
         {
          :name "postgres"
          :os debian/os
          :db (pdb/k8s-db "single-node")
          :pure-generators true
          }
         opts))

(defn -main
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn postgres-test})
            args))
