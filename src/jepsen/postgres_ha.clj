(ns jepsen.postgres-ha
  (:require
    [clojure.tools.logging :refer [info warn]]
    [clojure [edn :as edn]
     [pprint :refer [pprint]]
     [string :as str]]
    [jepsen [cli :as cli]
     [tests :as tests]
     [checker :as checker]
     [generator :as gen]
     [postgres-db :as pdb]]
    [jepsen.os.debian :as debian]
    [jepsen.postgres [append :as append]
     [ledger :as ledger]
     [nemesis :as nemesis]]
    )
  )

(def short-isolation
  {:strict-serializable "Strict-1SR"
   :serializable        "S"
   :strong-snapshot-isolation "Strong-SI"
   :snapshot-isolation  "SI"
   :repeatable-read     "RR"
   :read-committed      "RC"
   :read-uncommitted    "RU"})

(def workloads
  {:append      append/workload
   :ledger      ledger/workload
   :none        (fn [_] tests/noop-test)})

(def all-workloads
  "A collection of workloads we run by default."
  (remove #{:none} (keys workloads)))

(def workloads-expected-to-pass
  "A collection of workload names which we expect should actually pass."
  (remove #{} all-workloads))

(def all-nemeses
  "Combinations of nemeses for tests"
  [[]
   [:pause :kill :partition :clock]])


(def special-nemeses
  "A map of special nemesis names to collections of faults"
  {:none []
   :all  [:pause :kill :partition :clock]})

(defn parse-nemesis-spec
  "Takes a comma-separated nemesis string and returns a collection of keyword
  faults."
  [spec]
  (->> (str/split spec #",")
       (map keyword)
       (mapcat #(get special-nemeses % [%]))))


(defn postgres-test
  [opts]
  (let [workload-name (:workload opts)
        workload      ((workloads workload-name) opts)
        db (pdb/k8s-db (:cluster opts))
        nemesis       (nemesis/nemesis-package
                        {:db        db
                         :nodes     (:nodes opts)
                         :faults    (:nemesis opts)
                         :partition {:targets [:primaries]}
                         :pause     {:targets [nil :one :primaries :majority :all]}
                         :kill      {:targets [nil :one :primaries :majority :all]}
                         :interval  (:nemesis-interval opts)})]
    (merge tests/noop-test
           opts
           {
            :name (str "postgres" (name workload-name)
                       " " (short-isolation (:isolation opts)) " ("
                       (short-isolation (:expected-consistency-model opts)) ")"
                       " " (str/join "," (map name (:nemesis opts))))
            :pure-generators true
            :os debian/os
            :db db
            :checker (checker/compose
                       {:perf       (checker/perf
                                      {:nemeses (:perf nemesis)})
                        :clock      (checker/clock-plot)
                        :stats      (checker/stats)
                        :exceptions (checker/unhandled-exceptions)
                        :workload   (:checker workload)})
            :client    (:client workload)
            :nemesis   (:nemesis nemesis)
            ; :leave-db-running? true
            :generator (gen/phases
                         (->> (:generator workload)
                              (gen/stagger (/ (:rate opts)))
                              (gen/nemesis (:generator nemesis))
                              (gen/time-limit (:time-limit opts))))}
           )))


(defn all-test-options
  "Takes base cli options, a collection of nemeses, workloads, and a test count,
  and constructs a sequence of test options."
  [cli nemeses workloads]
  (for [n nemeses, w workloads, _ (range (:test-count cli))]
    (assoc cli
      :nemesis   n
      :workload  w)))


(defn all-tests
  "Turns CLI options into a sequence of tests."
  [test-fn cli]
  (let [nemeses   (if-let [n (:nemesis cli)] [n]  all-nemeses)
        workloads (if-let [w (:workload cli)] [w]
                                              (if (:only-workloads-expected-to-pass cli)
                                                workloads-expected-to-pass
                                                all-workloads))]
    (->> (all-test-options cli nemeses workloads)
         (map test-fn))))



(def cli-opts
  "Additional CLI options"

  [["-i" "--isolation LEVEL" "What level of isolation we should set: serializable, repeatable-read, etc."
    :default :serializable
    :parse-fn keyword
    :validate [#{:read-uncommitted
                 :read-committed
                 :repeatable-read
                 :serializable}
               "Should be one of read-uncommitted, read-committed, repeatable-read, or serializable"]]

   [nil "--expected-consistency-model MODEL" "What level of isolation do we *expect* to observe? Defaults to the same as --isolation."
    :default nil
    :parse-fn keyword]

   [nil "--key-count NUM" "Number of keys in active rotation."
    :default  10
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]

   [nil "--nemesis FAULTS" "A comma-separated list of nemesis faults to enable"
    :parse-fn parse-nemesis-spec
    :validate [(partial every? #{:pause :kill :partition :clock :member})
               "Faults must be pause, kill, partition, clock, or member, or the special faults all or none."]]

   [nil "--max-txn-length NUM" "Maximum number of operations in a transaction."
    :default  4
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]

   [nil "--max-writes-per-key NUM" "Maximum number of writes to any given key."
    :default  256
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]

   [nil "--nemesis-interval SECS" "Roughly how long between nemesis operations."
    :default 5
    :parse-fn read-string
    :validate [pos? "Must be a positive number."]]

   [nil "--postgres-password PASS" "What password should we use to connect to postgres?"
    :default "testpassword"]

   [nil "--postgres-sslmode MODE" "What sslmode should we use to connect to postgres: require, disable?"
    :default "disable"
    :parse-fn str
    :validate [#{"require" "disable"}
               "Should be one of require, or disable"]]

   [nil "--postgres-port NUMBER" "What port should we connect to when talking to postgres?"
    :default 30020
    :parse-fn parse-long]

   [nil "--postgres-user NAME" "What username should we use to connect to postgres? Only use this with --existing-postgres, or you'll probably confuse the Stolon setup."
    :default "postgres"]

   [nil "--prepare-threshold INT" "Passes a prepareThreshold option to the JDBC spec."
    :parse-fn parse-long]

   ["-r" "--rate HZ" "Approximate request rate, in hz"
    :default 100
    :parse-fn read-string
    :validate [pos? "Must be a positive number."]]

   ["-v" "--version STRING" "What version of Stolon should we test?"
    :default "0.16.0"]

   [nil "--cluster NAME" "Which postgres cluster to test."
    :parse-fn str
    :validate [#{"single-node" "patroni"}
               "Should be one of single-node, or patroni"
               ]
    ]

   ["-w" "--workload NAME" "What workload should we run?"
    :default :append
    :parse-fn keyword
    :validate [workloads (cli/one-of workloads)]]
   ])


(defn opt-fn
  "Transforms CLI options before execution."
  [parsed]
  (update-in parsed [:options :expected-consistency-model]
             #(or % (get-in parsed [:options :isolation]))))


(defn -main
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn postgres-test
                                         :opt-spec cli-opts
                                         :opt-fn opt-fn
                                         })
                   (cli/test-all-cmd {:tests-fn (partial all-tests postgres-test)
                                      :opt-spec cli-opts
                                      :opt-fn   opt-fn})
                   (cli/serve-cmd))
            args))
