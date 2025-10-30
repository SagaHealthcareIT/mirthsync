;; Custom logging implementation replacing clojure.tools.logging + logback
;; Provides a minimal API surface compatible with the usage patterns
;; within this project while directing output to stdout/stderr and
;; highlighting errors in red.

(ns mirthsync.logging
  (:require [clojure.string :as str])
  (:import [java.io PrintWriter]))

(def ^:private level-order [:trace :debug :info :warn :error])
(def ^:private level-rank (zipmap level-order (range)))
(defonce ^:private current-level (atom :info))

(def ^:dynamic *out-writer*
  (PrintWriter. System/out true))

(def ^:dynamic *err-writer*
  (PrintWriter. System/err true))

(def ^:private ansi-reset "\u001b[0m")
(def ^:private ansi-red "\u001b[31m")
(def ^:private ansi-yellow "\u001b[33m")
(def ^:private ansi-blue "\u001b[34m")
(def ^:private ansi-cyan "\u001b[36m")

(def ^:private color-enabled?
  (let [no-color (some-> (System/getenv "NO_COLOR") str/lower-case)]
    (not (contains? #{"1" "true" "yes" "on"} no-color))))

(def ^:private level->config
  {:trace {:label "TRACE" :color ansi-cyan :stream :out :prefix? true}
   :debug {:label "DEBUG" :color ansi-blue :stream :out :prefix? true}
   :info  {:label "INFO"  :color nil       :stream :out :prefix? false}
   :warn  {:label "WARN"  :color ansi-yellow :stream :err :prefix? true}
   :error {:label "ERROR" :color ansi-red :stream :err :prefix? true}})

(defn set-level!
  "Set the minimum level that will be emitted. Accepts a keyword or string.
  Levels (least to most severe): :trace, :debug, :info, :warn, :error."
  [level]
  (let [normalized (cond
                     (keyword? level) level
                     (string? level) (-> level str/lower-case keyword)
                     :else level)]
    (when-not (contains? level-rank normalized)
      (throw (ex-info (str "Unknown log level: " level)
                      {:level level})))
    (reset! current-level normalized)))

(defn level []
  @current-level)

(defn set-verbosity!
  "Set the logging threshold given a verbosity count (e.g. from repeated -v flags).
  0 => :info, 1 => :debug, 2+ => :trace."
  [verbosity]
  (let [threshold (cond
                    (nil? verbosity) :info
                    (<= verbosity 0) :info
                    (= verbosity 1) :debug
                    :else :trace)]
    (set-level! threshold)))

(defn enabled? [level]
  (>= (level-rank level) (level-rank @current-level)))

(defn ansi-wrap [color s]
  (if (and color-enabled? color)
    (str color s ansi-reset)
    s))

(defn padded-label [label]
  (format "%-5s" label))

(defn render-throwable [^Throwable t]
  (let [sw (java.io.StringWriter.)
        pw (java.io.PrintWriter. sw)]
    (.printStackTrace t pw)
    (.toString sw)))

(defn render-arg [arg]
  (cond
    (instance? Throwable arg)
    (render-throwable arg)

    (string? arg)
    arg

    :else
    (pr-str arg)))

(defn join-args [args]
  (if (seq args)
    (->> args (map render-arg) (str/join " "))
    ""))

(defn emit! [level message]
  (let [{:keys [label color stream prefix?]} (get level->config level {:stream :out})
        writer (if (= stream :err) *err-writer* *out-writer*)
        prefix (when prefix?
                 (str (ansi-wrap color (padded-label label)) ": "))
        line (str prefix message)]
    (binding [*out* writer]
      (println line)
      (flush))))

(defn log* [level message-fn]
  (when (enabled? level)
    (emit! level (message-fn))))

(defmacro log
  "Log at the provided level keyword. Accepts the same argument patterns
  used by the original clojure.tools.logging macros (variadic args)."
  [level & args]
  `(let [level# ~level]
     (log* level# (fn [] (join-args (list ~@args))))))

(defmacro logf
  "Log using a format string and arguments."
  [level fmt & args]
  `(let [level# ~level]
     (log* level# (fn [] (apply format ~fmt (list ~@args))))))

(defmacro trace [& args]
  `(log :trace ~@args))

(defmacro debug [& args]
  `(log :debug ~@args))

(defmacro info [& args]
  `(log :info ~@args))

(defmacro warn [& args]
  `(log :warn ~@args))

(defmacro error [& args]
  `(log :error ~@args))

(defmacro tracef [fmt & args]
  `(logf :trace ~fmt ~@args))

(defmacro debugf [fmt & args]
  `(logf :debug ~fmt ~@args))

(defmacro infof [fmt & args]
  `(logf :info ~fmt ~@args))

(defmacro warnf [fmt & args]
  `(logf :warn ~fmt ~@args))

(defmacro errorf [fmt & args]
  `(logf :error ~fmt ~@args))

(defmacro spy
  "Log the value of an expression at the given level (default :debug) and
  return the value."
  ([expr]
   `(spy :debug ~expr))
  ([level expr]
   `(let [value# ~expr
          level# ~level]
      (when (enabled? level#)
        (emit! level# (join-args (list value#))))
      value#)))

(defmacro spyf
  "Log the value of an expression with a format string at the given level
  (default :debug) and return the value."
  ([fmt expr]
   `(spyf :debug ~fmt ~expr))
  ([level fmt expr]
   `(let [value# ~expr
          level# ~level]
      (when (enabled? level#)
        (emit! level# (apply format ~fmt (list value#))))
      value#)))

(defn with-log-writers*
  "Execute `f` while binding the logging and standard output writers to the
  provided values (if non-nil)."
  [out-writer err-writer f]
  (let [out# (or out-writer *out-writer*)
        err# (or err-writer *err-writer*)]
    (binding [*out-writer* out#
              *err-writer* err#
              *out* out#
              *err* err#]
      (f))))

(defmacro with-log-writers
  "Bind logging STDOUT/STDERR writers for the enclosed body. Accepts either
  java.io.Writer instances or nil to keep current bindings."
  [out-writer err-writer & body]
  `(with-log-writers* ~out-writer ~err-writer (fn [] ~@body)))

