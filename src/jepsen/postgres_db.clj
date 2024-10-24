
(ns jepsen.postgres-db
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer :all]
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
    (do
      (warn "Cmd failed" result)
      (throw+
        (assoc result :type exc-type)
        nil ; cause
        "Command exited with non-zero status %d:\nSTDOUT:\n%s\n\nSTDERR:\n%s"
        exit
        (:out result)
        (:err result)))))


(defn node-ip-to-name
  [ip]
  (case ip
    "192.168.56.2" "postgres-server-1"
    "192.168.56.3" "postgres-server-2"
    "192.168.56.4" "postgres-server-3"
    ))

(defn node-name-to-ip
  [name]
  (case name
    "postgres-server-1" "192.168.56.2"
    "postgres-server-2" "192.168.56.3"
    "postgres-server-3" "192.168.56.4"
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

(defn try-one-node
  "Try to run command only on one node. If failed, try it on other nodes"
  [nodes cmd]
  (let [node (first nodes)]
    (try
      (c/on node (c/exec* cmd))
      (catch Exception e ;; If it throws an exception, catch it
        (warn "fail to execute cmd on node" cmd node)
        (try-one-node (rest nodes) cmd)))))

(defn vagrant-env
  []
  (let [env-vars (into {} (System/getenv))
        vagrant-dir (str (System/getProperty "user.dir") "/cluster")
        vagrant-file (str vagrant-dir "/Vagrantfile")
        updated-env-vars (assoc env-vars
                           "VAGRANT_VAGRANTFILE" vagrant-file
                           "VAGRANT_CWD" vagrant-dir
                           )]
    updated-env-vars))

(defn start-node
  [node]
  (info "Vagrant up node start " node)
  (unwrap-result ::vagrant-start-failed (sh "vagrant" "up" (node-ip-to-name node) :env (vagrant-env)))
  (info "Vagrant up node finished " node))

(defn kill-node
  [node]
  (info "kill db on node " node)
  (unwrap-result ::vagrant-halt-failed (sh "vagrant" "halt" "-f" (node-ip-to-name node) :env (vagrant-env)))
  (info "killed db on node " node))

(defn get-sub-processes
  [pid]
  (try
    (str/split-lines (c/exec* (str "pstree -p " pid " | grep -o '([0-9]\\+)' | grep -o '[0-9]\\+'")))
    (catch Exception e (warn "Error find subprocess" e) [])))



(defn kill-k3s-all
  "force kill k3s server and all containers including all subprocesses"
  []
  (c/su
    (let [pids (str/split-lines (c/exec "pgrep" "-f" "containerd-shim-runc-v2"))
          all-pids (mapcat get-sub-processes pids)]
      (c/exec "systemctl" "kill" "--signal=9" "k3s")
      (c/exec "systemctl" "stop" "k3s")
      (apply c/exec "kill" "-9" all-pids))))

(defn start-k3s
  []
  (c/su (c/exec "systemctl" "start" "--no-block" "k3s")))

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
      ; do not wait since pv will not be deleted before pvc
      (run-on-one-node node "kubectl" "delete" "--ignore-not-found=true" "--wait=false" "-f" "/home/vagrant/k8s.yaml")
      ; pvc created by stateful set template is not auto deleted above
      (run-on-one-node node "kubectl" "delete" "pvc" "--all")
      ; delete again after pvc is deleted
      (run-on-one-node node "kubectl" "delete" "--ignore-not-found=true" "-f" "/home/vagrant/k8s.yaml")
      (Thread/sleep 5000)
      (run-on-one-node node "kubectl" "wait" "--for=delete" "pods" "--all" "--timeout=600s")
      (Thread/sleep 5000)
      (c/su (c/exec "rm" "-rf" "/psql-data")))

    db/Primary
    (setup-primary! [db test node])
    (primaries [db test]
      (node-name-to-ip
        (try-one-node (:nodes test) "kubectl get pods  -l role=primary -o jsonpath=\"{.items[*].spec.nodeName}\"")))

    db/Kill

    (start! [_ test node]
      (start-k3s))

    (kill! [_ test node]
      (kill-k3s-all))))