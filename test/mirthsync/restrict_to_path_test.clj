(ns mirthsync.restrict-to-path-test
  "--restrict-to-path (MS-1): a path that names an item exactly takes only
  that item and what is under it; a partial name keeps the original
  string-prefix match. No server needed."
  (:require [clojure.data.zip.xml :as cdzx]
            [clojure.java.io :as io]
            [clojure.test :as ct]
            [mirthsync.actions :as ma]
            [mirthsync.cross-platform-utils :as cpu]
            [mirthsync.files :as mf]
            [mirthsync.fixture-tools :refer [build-path]]
            [mirthsync.http-client :as mhttp]
            [mirthsync.interfaces :as mi]
            [mirthsync.xml :as mx]))

(def target "t")

(defn- p [& parts] (apply build-path target parts))

(defn- matching [restrict candidates]
  (let [matches? (mf/restrict-matcher target restrict candidates)]
    (set (filter matches? candidates))))

(ct/deftest exact-item-takes-only-that-item
  (ct/testing "code disk mode: a channel is a directory"
    (let [files [(p "Channels" "G" "ADT" "channel.xml")
                 (p "Channels" "G" "ADT" "Source" "transformer.js")
                 (p "Channels" "G" "ADT Inbound" "channel.xml")
                 (p "Channels" "G" "ADTX" "channel.xml")]]
      (ct/is (= #{(p "Channels" "G" "ADT" "channel.xml")
                  (p "Channels" "G" "ADT" "Source" "transformer.js")}
                (matching (build-path "Channels" "G" "ADT") files)))))

  (ct/testing "items disk mode: a channel is <name>.xml"
    (let [files [(p "Channels" "G" "ADT.xml")
                 (p "Channels" "G" "ADT Inbound.xml")]]
      (ct/is (= #{(p "Channels" "G" "ADT.xml")}
                (matching (build-path "Channels" "G" "ADT") files)))
      (ct/is (= #{(p "Channels" "G" "ADT.xml")}
                (matching (build-path "Channels" "G" "ADT.xml") files)))))

  (ct/testing "a group takes only its own folder, not a group whose name starts the same"
    (let [files [(p "Channels" "Lab" "index.xml")
                 (p "Channels" "Lab" "A" "channel.xml")
                 (p "Channels" "Lab Archive" "B" "channel.xml")]]
      (ct/is (= #{(p "Channels" "Lab" "index.xml")
                  (p "Channels" "Lab" "A" "channel.xml")}
                (matching (build-path "Channels" "Lab") files))))))

(ct/deftest partial-name-keeps-the-prefix-match
  (let [files [(p "Channels" "G" "ADT" "channel.xml")
               (p "Channels" "G" "ADT Inbound" "channel.xml")
               (p "Channels" "G" "ORM" "channel.xml")]]
    (ct/is (= #{(p "Channels" "G" "ADT" "channel.xml")
                (p "Channels" "G" "ADT Inbound" "channel.xml")}
              (matching (build-path "Channels" "G" "AD") files)))
    (ct/testing "and a name with no item at all matches nothing new"
      (ct/is (= #{} (matching (build-path "Channels" "G" "Q") files))))))

(ct/deftest empty-restrict-matches-everything
  (let [files [(p "Channels" "G" "ADT" "channel.xml") (p "CodeTemplates" "L" "x.xml")]]
    (ct/is (= (set files) (matching "" files)))))

(defn- temp-target []
  (let [dir (build-path "target" "restrict-to-path-test")]
    (cpu/delete-recursive dir :allowed-dir "target")
    dir))

(ct/deftest push-takes-only-the-named-channel
  ;; local-locs (the push side) against real files on disk.
  (let [dir (temp-target)
        files (for [name ["ADT" "ADT Inbound"]]
                (let [f (io/file (build-path dir "Channels" "G" name "channel.xml"))]
                  (io/make-parents f)
                  (spit f (str "<channel><id>" name "</id></channel>"))
                  f))]
    (try
      (with-redefs [mi/api-files (fn [_ _] files)
                    mi/should-skip? (fn [_ _ _] false)]
        (let [locs (#'ma/local-locs {:api :channels
                                     :target dir
                                     :restrict-to-path (build-path "Channels" "G" "ADT")})]
          (ct/is (= 1 (count locs)))))
      (finally (cpu/delete-recursive dir :allowed-dir "target")))))

(ct/deftest pull-judges-exactness-against-the-server
  ;; download (the pull side): the server has ADT and ADT Inbound; nothing is
  ;; on disk yet, and only ADT is written.
  (let [dir (temp-target)
        path-of (fn [el-loc]
                  (build-path dir "Channels" "G"
                              (cdzx/xml1-> el-loc :name cdzx/text) "channel.xml"))]
    (try
      (with-redefs [mhttp/fetch-all (fn [_ _]
                                      (map #(mx/to-zip (str "<channel><name>" % "</name></channel>"))
                                           ["ADT" "ADT Inbound"]))
                    mhttp/api-url (fn [_] "https://example.invalid/api")
                    mi/should-skip? (fn [_ _ _] false)
                    mi/pre-node-action (fn [_ app-conf] app-conf)
                    mi/file-path (fn [_ app-conf] (path-of (:el-loc app-conf)))
                    mi/deconstruct-node (fn [_ fpath _] [[fpath "<channel/>"]])]
        (ma/download {:api :channels
                      :target dir
                      :restrict-to-path (build-path "Channels" "G" "ADT")
                      :disk-mode "code"})
        (ct/is (.exists (io/file (build-path dir "Channels" "G" "ADT" "channel.xml"))))
        (ct/is (not (.exists (io/file (build-path dir "Channels" "G" "ADT Inbound" "channel.xml"))))))
      (finally (cpu/delete-recursive dir :allowed-dir "target")))))
