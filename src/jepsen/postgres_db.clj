
(ns jepsen.postgres-db
  (:require [clojure.tools.logging :refer :all]
            [clojure.java.shell :refer [sh]]
            [slingshot.slingshot :refer [throw+]]
            [jepsen [db :as db]
             [control :as c]
             ])
  )


; copied from jepsen.k8s
(defn- unwrap-result
  "Throws when shell returned with nonzero exit status."
  [exc-type {:keys [exit] :as result}]
  (if (zero? exit)
    result
    (throw+
      (assoc result :type exc-type)
      nil ; cause
      "Command exited with non-zero status %d:\nSTDOUT:\n%s\n\nSTDERR:\n%s"
      exit
      (:out result)
      (:err result))))


(defn node-ip-to-name
  [ip]
  (case ip
    "192.168.56.2" "postgres-server-1"
    "192.168.56.3" "postgres-server-2"
    "192.168.56.4" "postgres-server-3"
    ))

(defn run-on-one-node
  "Just runt he command on one node"
  [ip & commands]
  (if (= ip "192.168.56.2")
    (do
      (info "Run cmd" commands)
      (c/su (c/exec commands)))
    (info "Ignore cmd since it should just run on one node ...")
    ))

(defn k8s-db
  [k8s-dir]
  (reify db/DB

    (setup! [_ test node]
      (info "Setup node" node)
      (c/upload (str "./cluster/" k8s-dir "/k8s.yaml") "/home/vagrant/k8s.yaml")
      (c/su (c/exec "mkdir" "-p" "/psql-data"))
      (run-on-one-node node "kubectl" "create" "-f" "/home/vagrant/k8s.yaml")
      (Thread/sleep 5000)
      (run-on-one-node node "kubectl" "wait" "--for=condition=Ready" "pods" "--all" "--timeout=600s")
      (Thread/sleep 10000))

    (teardown! [_ test node]
      (info "Teardown node" node)
      (c/upload (str "./cluster/" k8s-dir "/k8s.yaml") "/home/vagrant/k8s.yaml")
      (run-on-one-node node "kubectl" "delete" "--ignore-not-found=true" "-f" "/home/vagrant/k8s.yaml")
      (Thread/sleep 5000)
      (run-on-one-node node "kubectl" "wait" "--for=delete" "pods" "--all" "--timeout=600s")
      (c/su (c/exec "rm" "-rf" "/psql-data")))

    db/Kill

    (start! [_ test node]
      (unwrap-result (sh "vagrant" "up" (node-ip-to-name node)) ::vagrant-start-failed))

    (kill! [_ test node]
      (unwrap-result (sh "vagrant" "halt" "-f" (node-ip-to-name node)) ::vagrant-halt-failed))))