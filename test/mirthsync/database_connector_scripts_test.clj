(ns mirthsync.database-connector-scripts-test
  "A Database Reader or Writer holds JavaScript in its select, update and query
  tags when useScript is true. A code-mode pull extracts that JavaScript to
  .js files and a push reads it back unchanged. With useScript false the tags
  hold SQL, which stays in the XML. Real disk layout and the real :channels
  code; only the server is stubbed."
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

;; XML escaped in the channel, plain text in the .js files
(def ^:private select-xml "// select &amp; check\nreturn 1 &lt; 2 &amp;&amp; 3 &gt; 2;")
(def ^:private select-js "// select & check\nreturn 1 < 2 && 3 > 2;")
(def ^:private update-js "// update")
(def ^:private query-xml "// write &amp; log\nif (a &lt; b) { logger.info('x'); }")
(def ^:private query-js "// write & log\nif (a < b) { logger.info('x'); }")
(def ^:private sql "SELECT id FROM t WHERE a &lt; 1")

(defn- channel-xml
  "A channel with a Database Reader source and a Database Writer destination."
  [id nm use-script select update query]
  (str "<channel><id>" id "</id><name>" nm "</name>"
       "<sourceConnector><name>sourceConnector</name>"
       "<properties class=\"com.mirth.connect.connectors.jdbc.DatabaseReceiverProperties\">"
       "<driver>org.postgresql.Driver</driver>"
       "<select>" select "</select><update>" update "</update>"
       "<useScript>" use-script "</useScript></properties>"
       "<transportName>Database Reader</transportName></sourceConnector>"
       "<destinationConnectors><connector><name>Writer</name>"
       "<properties class=\"com.mirth.connect.connectors.jdbc.DatabaseDispatcherProperties\">"
       "<driver>org.postgresql.Driver</driver>"
       "<query>" query "</query><useScript>" use-script "</useScript></properties>"
       "<transportName>Database Writer</transportName></connector></destinationConnectors>"
       "</channel>"))

(def ^:private channels-xml
  (str (channel-xml "a" "JS db" "true" select-xml "// update" query-xml)
       (channel-xml "b" "SQL db" "false" sql "UPDATE t SET a = 1" "INSERT INTO t VALUES (1)")))

(def ^:private group-xml
  (str "<channelGroup><id>g</id><name>Group</name><channels>"
       "<channel><id>a</id></channel><channel><id>b</id></channel>"
       "</channels></channelGroup>"))

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

(defn- raw-text
  "The text of the element at the path under loc, newlines kept. cdzx/text
  collapses whitespace."
  [loc & path]
  (some->> (apply cdzx/xml1-> loc path) cz/node :content (apply str)))

(defn- pushed [dir]
  (let [conf {:api :channels :target dir :restrict-to-path ""}]
    (mapv #(cz/xml-zip (cz/root %))
          (#'ma/file-locs conf (#'ma/restricted-files conf)))))

(ct/deftest database-connector-scripts-round-trip
  (let [dir (build-path "target" "database-connector-scripts-test")]
    (cpu/delete-recursive dir :allowed-dir "target")
    (try
      (pull! dir)

      (ct/testing "pull extracts the scripts only when useScript is true"
        (ct/is (= ["Channels/Group/JS db.xml"
                   "Channels/Group/JS db/destinationConnector-Writer.js"
                   "Channels/Group/JS db/sourceConnector-sourceConnector-select.js"
                   "Channels/Group/JS db/sourceConnector-sourceConnector-update.js"
                   "Channels/Group/SQL db.xml"
                   "Channels/Group/index.xml"]
                  (files-under dir))))

      (ct/testing "the extracted files hold the unescaped scripts"
        (ct/is (= select-js (slurp (str dir "/Channels/Group/JS db/sourceConnector-sourceConnector-select.js"))))
        (ct/is (= update-js (slurp (str dir "/Channels/Group/JS db/sourceConnector-sourceConnector-update.js"))))
        (ct/is (= query-js (slurp (str dir "/Channels/Group/JS db/destinationConnector-Writer.js")))))

      (ct/testing "SQL stays in the XML"
        (let [xml (slurp (str dir "/Channels/Group/SQL db.xml"))]
          (ct/is (str/includes? xml "<select>SELECT id FROM t WHERE a &lt; 1</select>"))
          (ct/is (str/includes? xml "<query>INSERT INTO t VALUES (1)</query>"))
          (ct/is (not (str/includes? xml "msync-fileref")))))

      (ct/testing "push reads the scripts back into the XML"
        (let [by-name (into {} (map (juxt #(mi/find-name :channels %) identity) (pushed dir)))
              js (by-name "JS db")]
          (ct/is (= select-js (raw-text js :sourceConnector :properties :select)))
          (ct/is (= update-js (raw-text js :sourceConnector :properties :update)))
          (ct/is (= query-js (raw-text js :destinationConnectors :connector :properties :query)))
          (ct/is (= "SELECT id FROM t WHERE a < 1"
                    (raw-text (by-name "SQL db") :sourceConnector :properties :select)))
          (ct/testing "with no file references left in the XML"
            (doseq [loc (vals by-name)]
              (ct/is (not (str/includes? (cdx/emit-str (cz/node loc)) "msync-fileref")))))))
      (finally
        (cpu/delete-recursive dir :allowed-dir "target")))))
