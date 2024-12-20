
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
  "Try to run command only on one node. If failed, try it on other nodes."
  [nodes cmd]
  (if (empty? nodes)
    nil
    (let [node (first nodes)]
      (try
        (c/on node (c/su (c/exec* cmd)))
        (catch Exception e ;; If it throws an exception, catch it
          ; (warn "fail to execute cmd on node" cmd node e)
          (try-one-node (rest nodes) cmd))))))

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
  [kill-server?]
  (c/su
    (let [pids (str/split-lines (c/exec "pgrep" "-f" "containerd-shim-runc-v2"))
          all-pids (mapcat get-sub-processes pids)]
      (if kill-server? (c/exec "systemctl" "kill" "--signal=9" "k3s"))
      (if kill-server? (c/exec "systemctl" "stop" "k3s"))
      (apply c/exec "kill" "-9" all-pids))))

(defn start-k3s
  [block?]
  (let [block-flag (if block? nil "--no-block")]
    (info "start k3s ... block: " block?)
    (c/su (c/exec "systemctl" "start" block-flag "k3s"))))

(defn delete-patroni-endpoints
  [node]
  (run-on-one-node node "kubectl" "delete" "endpoints" "--ignore-not-found=true" "patronidemo-sync")
  (run-on-one-node node "kubectl" "delete" "endpoints" "--ignore-not-found=true" "patronidemo-config")
  (run-on-one-node node "kubectl" "delete" "endpoints" "--ignore-not-found=true" "patronidemo-repl")
  )

(defn k8s-db
  [k8s-dir]
  (reify db/DB

    (setup! [_ test node]
      (info "Setup node" node)
      (start-k3s true)
      (c/upload (str "./cluster/" k8s-dir "/k8s.yaml") "/home/vagrant/k8s.yaml")
      (c/su (c/exec "mkdir" "-p" "/psql-data"))
      (run-on-one-node node "kubectl" "create" "-f" "/home/vagrant/k8s.yaml")
      (Thread/sleep 5000)
      (run-on-one-node node "kubectl" "wait" "--for=condition=Ready" "pods" "--all" "--timeout=600s")
      (Thread/sleep 10000))

    (teardown! [_ test node]
      (info "Teardown node" node)
      (start-k3s true)
      (c/upload (str "./cluster/" k8s-dir "/k8s.yaml") "/home/vagrant/k8s.yaml")
      ; do not wait since pv will not be deleted before pvc
      (run-on-one-node node "kubectl" "delete" "--ignore-not-found=true" "--wait=false" "-f" "/home/vagrant/k8s.yaml")
      ; pvc created by stateful set template is not auto deleted above
      (run-on-one-node node "kubectl" "delete" "pvc" "--all")
      ; delete again after pvc is deleted
      (run-on-one-node node "kubectl" "delete" "--ignore-not-found=true" "-f" "/home/vagrant/k8s.yaml")
      (Thread/sleep 5000)
      (run-on-one-node node "kubectl" "wait" "--for=delete" "pods" "--all" "--timeout=600s")
      (Thread/sleep 20000)
      (delete-patroni-endpoints node) ; sometimes the endpoints are not deleted
      (c/su (c/exec "rm" "-rf" "/psql-data"))
      (Thread/sleep 5000)
      )

    db/Primary
    (setup-primary! [db test node])
    (primaries [db test]
      (let [n (try-one-node (:nodes test) "kubectl get endpoints patronidemo -o jsonpath=\"{.subsets[*].addresses[*].nodeName}\"")]
        (if (nil? n)
        []
        (map node-name-to-ip (str/split n #" ")))))

    db/Kill

    (start! [_ test node]
      (start-k3s true))

    (kill! [_ test node]
      (kill-k3s-all true))))