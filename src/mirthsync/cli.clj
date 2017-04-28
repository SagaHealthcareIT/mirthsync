(ns mirthsync.cli
  (:require [clojure.string :as string])
  (:import java.net.URL))

(def cli-options
  [["-s" "--server SERVER_URL" "Full HTTP(s) url of the Mirth Connect server"
    :default "https://localhost:8443"
    :validate-fn #(.URL %)]
   ["-a" "--action ACTION" "Action can either be 'fetch' or 'push'"
    :validate-fn (fn [arg] (#{"fetch" "push"} arg))]
   ["-v" nil "Verbosity level; may be specified multiple times to increase value"
    :id :verbosity
    :default 0
    :assoc-fn (fn [m k _] (update-in m [k] inc))]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Usage: mirthsync [options]"
        ""
        "Options:"
        options-summary]
       (string/join \newline)))
