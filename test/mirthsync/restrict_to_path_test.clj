(ns mirthsync.restrict-to-path-test
  "--restrict-to-path (MS-1): a path that names an item exactly takes only
  that item and what is under it; a partial name keeps the original
  string-prefix match. One run decides which, for every api. Real disk
  layouts and the real :channel-groups / :channels code; only the server is
  stubbed."
  (:require [clojure.data.xml :as cdx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as ct]
            [clojure.zip :as cz]
            [mirthsync.actions :as ma]
            [mirthsync.cross-platform-utils :as cpu]
            [mirthsync.files :as mf]
            [mirthsync.fixture-tools :refer [build-path]]
            [mirthsync.http-client :as mhttp]
            [mirthsync.interfaces :as mi]
            [mirthsync.logging :as log]
            [mirthsync.xml :as mx])
  (:import [java.io File StringWriter]))

;; ---------------------------------------------------------------- matcher

(defn- matching [restrict exact? candidates]
  (set (filter (mf/restrict-matcher "t" restrict exact?) candidates)))

(defn- t [& parts] (apply build-path "t" parts))

(ct/deftest exact-takes-only-the-item
  (let [files [(t "Channels" "G" "ADT.xml")
               (t "Channels" "G" "ADT" "PreprocessingScript.js")
               (t "Channels" "G" "ADT Inbound.xml")
               (t "Channels" "G" "ADT Inbound" "PreprocessingScript.js")
               (t "Channels" "G" "ADTX.xml")]
        adt #{(t "Channels" "G" "ADT.xml") (t "Channels" "G" "ADT" "PreprocessingScript.js")}]
    (ct/is (mf/restrict-names-item? "t" (build-path "Channels" "G" "ADT") files))
    (ct/is (= adt (matching (build-path "Channels" "G" "ADT") true files)))
    (ct/testing "the <name>.xml form takes the item's script folder too"
      (ct/is (= adt (matching (build-path "Channels" "G" "ADT.xml") true files))))))

(ct/deftest partial-name-keeps-the-prefix-match
  (let [files [(t "Channels" "G" "ADT.xml") (t "Channels" "G" "ADT Inbound.xml") (t "Channels" "G" "ORM.xml")]]
    (ct/is (not (mf/restrict-names-item? "t" (build-path "Channels" "G" "AD") files)))
    (ct/is (= #{(t "Channels" "G" "ADT.xml") (t "Channels" "G" "ADT Inbound.xml")}
              (matching (build-path "Channels" "G" "AD") false files)))))

(ct/deftest empty-restrict-matches-everything
  (let [files [(t "Channels" "G" "ADT.xml") (t "CodeTemplates" "L" "x.xml")]]
    (ct/is (not (mf/restrict-names-item? "t" "" files)))
    (ct/is (= (set files) (matching "" false files)))
    (ct/is (= (set files) (matching "" true files)))))

;; ---------------------------------------------------------------- fixtures

(defn- fresh-dir [name]
  (let [dir (build-path "target" "restrict-to-path-test" name)]
    (cpu/delete-recursive dir :allowed-dir "target")
    dir))

(defn- put! [dir m]
  (doseq [[rel content] m]
    (let [f (io/file dir rel)]
      (io/make-parents f)
      (spit f content))))

(defn- files-under [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile ^File %))
       (map #(str/replace (subs (str %) (inc (count dir))) File/separator "/"))
       sort
       vec))

(defn- grp [id nm chans]
  (str "<channelGroup><id>" id "</id><name>" nm "</name><channels>"
       (apply str (map #(str "<channel><id>" % "</id></channel>") chans))
       "</channels></channelGroup>"))

(defn- chan [id nm & [disabled?]]
  (str "<channel><id>" id "</id><name>" nm "</name>"
       (when disabled? "<exportData><metadata><enabled>false</enabled></metadata></exportData>")
       "<preprocessingScript>// " nm "</preprocessingScript></channel>"))

(defn- server-groups [groups]
  (cz/xml-zip (apply cdx/element :set nil (map #(cdx/parse-str (apply grp %)) groups))))

(defn- pull!
  "Runs channel-groups then channels, as a pull does, against a stubbed
  server. Returns the files written, and the app-conf after both apis."
  [dir mode restrict groups chans & {:as opts}]
  (with-redefs [mhttp/api-url (fn [_] "https://example.invalid/api")
                mhttp/fetch-all (fn [{:keys [api]} find-elements]
                                  (find-elements
                                   (mx/to-zip
                                    (case api
                                      :channel-groups (str "<list>" (apply str (map #(apply grp %) groups)) "</list>")
                                      :channels (str "<list>" (apply str (map #(apply chan %) chans)) "</list>")))))]
    (let [conf (merge {:target dir :restrict-to-path restrict :disk-mode mode :force true
                       :server-groups (server-groups groups)}
                      opts)
          conf (ma/download (assoc conf :api :channel-groups))
          conf (ma/download (assoc conf :api :channels))]
      [(files-under dir) conf])))

(defn- push-names [dir api restrict]
  (let [conf {:api api :target dir :restrict-to-path restrict}]
    (mapv #(mi/find-name api %) (#'ma/file-locs conf (#'ma/restricted-files conf)))))

;; ---------------------------------------------------------------- push

(ct/deftest push-takes-only-the-named-channel
  (let [dir (fresh-dir "push-code")]
    (try
      (put! dir {"Channels/G/ADT.xml" (chan "a" "ADT")
                 "Channels/G/ADT/PreprocessingScript.js" "// ADT"
                 "Channels/G/ADT Inbound.xml" (chan "b" "ADT Inbound")})
      (ct/is (= ["ADT"] (push-names dir :channels (build-path "Channels" "G" "ADT"))))
      (ct/is (= ["ADT"] (push-names dir :channels (build-path "Channels" "G" "ADT.xml"))))
      (ct/is (= #{"ADT" "ADT Inbound"} (set (push-names dir :channels (build-path "Channels" "G" "AD")))))
      (finally (cpu/delete-recursive dir :allowed-dir "target")))))

(ct/deftest push-of-a-group-leaves-a-group-whose-name-starts-the-same
  ;; Lab is empty; its channels api has no Lab file of its own, but the run
  ;; still treats Channels/Lab as the exact group.
  (let [dir (fresh-dir "push-group")]
    (try
      (put! dir {"Channels/Lab/index.xml" (grp "g1" "Lab" [])
                 "Channels/Lab Archive/index.xml" (grp "g2" "Lab Archive" ["b"])
                 "Channels/Lab Archive/B.xml" (chan "b" "B")})
      (ct/is (= ["Lab"] (push-names dir :channel-groups (build-path "Channels" "Lab"))))
      (ct/is (= [] (push-names dir :channels (build-path "Channels" "Lab"))))
      (finally (cpu/delete-recursive dir :allowed-dir "target")))))

;; ---------------------------------------------------------------- pull

(def ^:private one-group [["g" "G" ["a" "b"]]])
(def ^:private adt-pair [["a" "ADT"] ["b" "ADT Inbound"]])

(ct/deftest pull-takes-only-the-named-channel-and-its-scripts
  ;; Platform separators, as the CLI's parse-fn leaves them.
  (doseq [restrict [(build-path "Channels" "G" "ADT") (build-path "Channels" "G" "ADT.xml")]]
    (let [dir (fresh-dir "pull-exact")
          [files conf] (pull! dir "code" restrict one-group adt-pair)]
      (ct/is (= ["Channels/G/ADT.xml" "Channels/G/ADT/PreprocessingScript.js"] files) restrict)
      (ct/testing "the matcher does not leak out of the pull"
        (ct/is (not (contains? conf :restrict-match?))))
      (cpu/delete-recursive dir :allowed-dir "target"))))

(ct/deftest pull-of-a-partial-name-keeps-the-prefix-match
  (let [dir (fresh-dir "pull-prefix")
        [files] (pull! dir "code" (build-path "Channels" "G" "AD") one-group adt-pair)]
    (ct/is (= ["Channels/G/ADT Inbound.xml" "Channels/G/ADT Inbound/PreprocessingScript.js"
               "Channels/G/ADT.xml" "Channels/G/ADT/PreprocessingScript.js"]
              files))
    (cpu/delete-recursive dir :allowed-dir "target")))

(ct/deftest pull-of-an-empty-group-leaves-a-group-whose-name-starts-the-same
  (let [dir (fresh-dir "pull-group")
        [files] (pull! dir "code" (build-path "Channels" "Lab")
                       [["g1" "Lab" []] ["g2" "Lab Archive" ["b"]]] [["b" "B"]])]
    (ct/is (= ["Channels/Lab/index.xml"] files))
    (cpu/delete-recursive dir :allowed-dir "target")))

(ct/deftest groups-mode-pull-skips-items-outside-the-restrict
  ;; groups disk mode writes each channel into its group's index.xml, read
  ;; back first; a group outside the restrict has no index on disk.
  (let [dir (fresh-dir "pull-groups-mode")
        [files] (pull! dir "groups" (build-path "Channels" "Lab")
                       [["g1" "Lab" ["a"]] ["g2" "Lab Archive" ["b"]] ["g3" "Other" ["c"]]]
                       [["a" "A"] ["b" "B"] ["c" "C"]])]
    (ct/is (= ["Channels/Lab/index.xml"] files))
    (ct/is (str/includes? (slurp (io/file dir "Channels" "Lab" "index.xml")) "<name>A</name>"))
    (cpu/delete-recursive dir :allowed-dir "target")))

;; ---------------------------------------------------------------- matched nothing

(defn- push!
  "Runs the channels push against a stubbed upload. Returns the names
  uploaded and the app-conf after the api."
  [dir restrict & {:as opts}]
  (let [uploaded (atom [])
        conf (with-redefs-fn {#'ma/upload-node (fn [conf]
                                                 (swap! uploaded conj (mi/find-name :channels (:el-loc conf)))
                                                 conf)}
               #(ma/upload (merge {:api :channels :target dir :restrict-to-path restrict} opts)))]
    [@uploaded conf]))

(ct/deftest push-records-whether-the-restrict-matched
  (let [dir (fresh-dir "push-matched")]
    (try
      (put! dir {"Channels/G/ADT.xml" (chan "a" "ADT")
                 "Channels/G/OFF.xml" (chan "b" "OFF" :disabled)})
      (let [[uploaded conf] (push! dir (build-path "Channels" "G" "ADT"))]
        (ct/is (= ["ADT"] uploaded))
        (ct/is (:restrict-found-items? conf)))
      (let [[uploaded conf] (push! dir (build-path "Channels" "G" "AUX"))]
        (ct/is (= [] uploaded))
        (ct/is (not (:restrict-found-items? conf))))
      (ct/testing "a disabled item --skip-disabled leaves out still counts as matched"
        (let [[uploaded conf] (push! dir (build-path "Channels" "G" "OFF") :skip-disabled true)]
          (ct/is (= [] uploaded))
          (ct/is (:restrict-found-items? conf))))
      (finally (cpu/delete-recursive dir :allowed-dir "target")))))

(ct/deftest pull-records-whether-the-restrict-matched
  (let [dir (fresh-dir "pull-matched")]
    (try
      (let [[files conf] (pull! dir "code" (build-path "Channels" "G" "ADT") one-group adt-pair)]
        (ct/is (seq files))
        (ct/is (:restrict-found-items? conf)))
      (cpu/delete-recursive dir :allowed-dir "target")
      (let [[files conf] (pull! dir "code" (build-path "Channels" "G" "AUX") one-group adt-pair)]
        (ct/is (= [] files))
        (ct/is (not (:restrict-found-items? conf))))
      (cpu/delete-recursive dir :allowed-dir "target")
      (ct/testing "a match by an earlier api is kept when a later one matches nothing"
        (let [[files conf] (pull! dir "code" (build-path "Channels" "G" "index.xml") one-group adt-pair)]
          (ct/is (= ["Channels/G/index.xml"] files))
          (ct/is (:restrict-found-items? conf))))
      (cpu/delete-recursive dir :allowed-dir "target")
      (ct/testing "a disabled item --skip-disabled leaves out still counts as matched"
        (let [[files conf] (pull! dir "code" (build-path "Channels" "G" "OFF")
                                  [["g" "G" ["c"]]] [["c" "OFF" :disabled]] :skip-disabled true)]
          (ct/is (= [] files))
          (ct/is (:restrict-found-items? conf))))
      (finally (cpu/delete-recursive dir :allowed-dir "target")))))

(defn- warning-for [conf]
  (let [err (StringWriter.)]
    (log/with-log-writers (StringWriter.) err
      (ct/is (= conf (ma/warn-if-restrict-matched-nothing conf))))
    (str err)))

(ct/deftest warns-when-the-restrict-matched-nothing
  (let [restrict (build-path "Channels" "G" "AUX")]
    (ct/is (str/includes? (warning-for {:action "push" :restrict-to-path restrict})
                          (str "--restrict-to-path '" restrict "' matched no items, so nothing was pushed.")))
    (ct/is (str/includes? (warning-for {:action "pull" :restrict-to-path restrict})
                          "so nothing was pulled."))
    (ct/testing "no warning when the restrict matched, or when there is none"
      (ct/is (= "" (warning-for {:action "pull" :restrict-to-path restrict :restrict-found-items? true})))
      (ct/is (= "" (warning-for {:action "push" :restrict-to-path ""}))))))
