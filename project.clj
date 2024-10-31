(defproject jepsen.postgres-ha "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [jepsen "0.3.8-SNAPSHOT"]
                 ;[jepsen "0.3.5"]
                 [org.postgresql/postgresql "42.7.2"]
                 [seancorfield/next.jdbc "1.2.659"]
                 [clj-wallhack "1.0.1"]
                 ]
  :main jepsen.postgres-ha
  :repl-options {:init-ns jepsen.postgres-ha})
