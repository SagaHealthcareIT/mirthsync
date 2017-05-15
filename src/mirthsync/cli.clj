(ns mirthsync.cli
  (:require [clojure.string :as string]
            [clojure.tools
             [cli :refer [parse-opts]]])
  (:import java.net.URL))

(def ^:dynamic *verbosity* 0)

(defn output
  "Print the message if the verbosity level is high enough"
  ([message]
   (output 0 message))
  ([level message]
   (when (<= level *verbosity*) (println message))))

(def cli-options
  [["-s" "--server SERVER_URL" "Full HTTP(s) url of the Mirth Connect server"
    :default "https://localhost:8443/api"
    :validate-fn #(URL. %)]
   
   ["-u" "--username USERNAME" "Username used for authentication"
    :default "admin"]

   ["-p" "--password PASSWORD" "Password used for authentication"
    :default ""]

   ["-f" "--force" "Overwrite any conflicting files in the target directory"]

   ["-t" "--target TARGET_DIR" "Base directory used for pushing or pulling files"
    :default "."]

   ["-v" nil "Verbosity level; may be specified multiple times to increase value"
    :id :verbosity
    :default 0
    :assoc-fn (fn [m k _] (update-in m [k] inc))]

   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Usage: mirthsync [options] action"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  push     Push local code to remote"
        "  pull     Pull remote code to local"]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the
  program should exit (with a error message, and optional ok status),
  or a map indicating the action the program should take and the
  options provided."
  [args]
  ;; (log/debug args)
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]

    ;; set the verbosity to use for output from the parsed options
    (alter-var-root #'*verbosity* (constantly (:verbosity options)))

    ;; decide what to return
    (cond
      ;; help => exit OK with usage summary
      (:help options)
      {:exit-message (usage summary) :ok? true}

      ;; errors => exit with description of errors
      errors
      {:exit-message (str (error-msg errors) (usage summary))}

      ;; custom validation on arguments
      (and (= 1 (count arguments))
           (#{"pull" "push"} (first arguments)))
      {:action (first arguments) :options options}
      
      ;; failed custom validation => exit with usage summary
      :else
      {:exit-message (usage summary)})))
