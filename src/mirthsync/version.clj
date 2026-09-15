(ns mirthsync.version
  "Exposes the mirthsync version as a single source of truth. The value is
  read from the uberjar's own MANIFEST.MF (where Leiningen records
  Leiningen-Project-Version from project.clj), so it can never drift from
  the released artifact. When running from source (e.g. 'lein run') there
  is no such manifest and the version reports as \"dev\"."
  (:import [java.util.jar Manifest]
           [java.net URL]))

(defn- read-version
  "Returns Leiningen-Project-Version from the manifest whose Main-Class is
  mirthsync.core, or nil when no such manifest is on the classpath."
  []
  (let [^ClassLoader cl (.getContextClassLoader (Thread/currentThread))]
    (some (fn [^URL url]
            (with-open [is (.openStream url)]
              (let [attrs (.getMainAttributes (Manifest. is))]
                (when (= "mirthsync.core" (.getValue attrs "Main-Class"))
                  (.getValue attrs "Leiningen-Project-Version")))))
          (enumeration-seq (.getResources cl "META-INF/MANIFEST.MF")))))

(def ^String version
  "The mirthsync version string (e.g. \"3.6.0\"), or \"dev\" when run from source."
  (or (read-version) "dev"))
