(ns mirthsync.name-encoding-round-trip-test
  "Item names that safe-name encodes, including a percent sign and a Windows
  reserved name, survive a code-mode pull and push: pull writes the encoded
  file names, and push reads those files back into the original names and
  scripts. Real disk layout and the real :channel-groups / :channels code;
  only the server is stubbed. File names never reach the server, so a stubbed
  server covers everything that decides them."
  (:require [clojure.data.xml :as cdx]
            [clojure.data.zip.xml :as cdzx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as ct]
            [clojure.zip :as cz]
            [mirthsync.actions :as ma]
            [mirthsync.apis]
            [mirthsync.cross-platform-utils :as cpu]
            [mirthsync.fixture-tools :refer [build-path]]
            [mirthsync.http-client :as mhttp]
            [mirthsync.interfaces :as mi]
            [mirthsync.xml :as mx])
  (:import java.io.File))

(def ^:private group-name "Lab: 100%")
(def ^:private rule-name "Accept if \"x\" is 50%")
(def ^:private rule-script "return msg['x'] == '50%';")

(def ^:private group-xml
  (str "<channelGroup><id>g</id><name>" group-name "</name><channels>"
       "<channel><id>a</id></channel><channel><id>b</id></channel>"
       "</channels></channelGroup>"))

(defn- channel-xml [id nm rule]
  (str "<channel><id>" id "</id><name>" nm "</name>"
       "<sourceConnector><name>sourceConnector</name><filter><elements>"
       (when rule
         (str "<com.mirth.connect.plugins.javascriptrule.JavaScriptRule>"
              "<name>" rule "</name><sequenceNumber>0</sequenceNumber>"
              "<script>" rule-script "</script>"
              "</com.mirth.connect.plugins.javascriptrule.JavaScriptRule>"))
       "</elements></filter></sourceConnector>"
       "<preprocessingScript>// " nm "</preprocessingScript></channel>"))

(def ^:private channels-xml
  (str (channel-xml "a" "50% off: ADT" rule-name)
       (channel-xml "b" "AUX" nil)))

(defn- files-under [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile ^File %))
       (map #(str/replace (subs (str %) (inc (count dir))) File/separator "/"))
       sort
       vec))

(defn- pull! [dir]
  (with-redefs [mhttp/api-url (fn [_] "https://example.invalid/api")
                mhttp/fetch-all (fn [{:keys [api]} find-elements]
                                  (find-elements
                                   (mx/to-zip
                                    (case api
                                      :channel-groups (str "<list>" group-xml "</list>")
                                      :channels (str "<list>" channels-xml "</list>")))))]
    (let [conf {:target dir :restrict-to-path "" :disk-mode "code" :force true
                :server-groups (cz/xml-zip (cdx/element :set nil (cdx/parse-str group-xml)))}
          conf (ma/download (assoc conf :api :channel-groups))]
      (ma/download (assoc conf :api :channels)))))

(defn- pushed [dir api]
  (mapv #(cz/xml-zip (cz/root %))
        (#'ma/local-locs {:api api :target dir :restrict-to-path ""})))

(ct/deftest encoded-names-round-trip
  (let [dir (build-path "target" "name-encoding-round-trip-test")]
    (cpu/delete-recursive dir :allowed-dir "target")
    (try
      (pull! dir)

      (ct/testing "pull writes the encoded names"
        (ct/is (= ["Channels/Lab%3A 100%25/50%25 off%3A ADT.xml"
                   "Channels/Lab%3A 100%25/50%25 off%3A ADT/PreprocessingScript.js"
                   "Channels/Lab%3A 100%25/50%25 off%3A ADT/sourceConnector-filter-step-0-Accept if %22x%22 is 50%25.js"
                   "Channels/Lab%3A 100%25/AU%58.xml"
                   "Channels/Lab%3A 100%25/AU%58/PreprocessingScript.js"
                   "Channels/Lab%3A 100%25/index.xml"]
                  (files-under dir))))

      (ct/testing "push reads them back under the original names"
        (ct/is (= [group-name] (mapv #(mi/find-name :channel-groups %) (pushed dir :channel-groups))))
        (let [channels (pushed dir :channels)
              by-name (into {} (map (juxt #(mi/find-name :channels %) identity) channels))
              adt (by-name "50% off: ADT")]
          (ct/is (= #{"50% off: ADT" "AUX"} (set (keys by-name))))
          (ct/is (= "// 50% off: ADT" (cdzx/xml1-> adt :preprocessingScript cdzx/text)))
          (ct/is (= "// AUX" (cdzx/xml1-> (by-name "AUX") :preprocessingScript cdzx/text)))
          (ct/is (= rule-name
                    (cdzx/xml1-> adt :sourceConnector :filter :elements
                                 :com.mirth.connect.plugins.javascriptrule.JavaScriptRule :name cdzx/text)))
          (ct/is (= rule-script
                    (cdzx/xml1-> adt :sourceConnector :filter :elements
                                 :com.mirth.connect.plugins.javascriptrule.JavaScriptRule :script cdzx/text)))
          (ct/testing "with no file references left in the XML"
            (doseq [loc channels]
              (ct/is (not (str/includes? (cdx/emit-str (cz/node loc)) "msync-fileref")))))))
      (finally
        (cpu/delete-recursive dir :allowed-dir "target")))))
