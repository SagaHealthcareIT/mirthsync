(ns mirthsync.delete-orphaned-test
  (:require [mirthsync.actions :refer :all]
            [mirthsync.apis :as api]
            [mirthsync.cli :refer :all]
            [mirthsync.files :as mf]
            [mirthsync.http-client :as mhttp]
            [mirthsync.interfaces :as mi]
            [mirthsync.xml :as mxml]
            [mirthsync.fixture-tools :refer [build-path]]
            [clojure.test :refer :all]
            [clojure.java.io :as io]))

(deftest cli-integration
  (testing "delete-orphaned flag is properly parsed"
    (let [conf (config ["-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "-t" "foo" "--delete-orphaned" "pull"])]
      (is (= true (:delete-orphaned conf)))
      (is (= "pull" (:action conf)))))

  (testing "delete-orphaned defaults to false"
    (let [conf (config ["-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "-t" "foo" "pull"])]
      (is (= false (:delete-orphaned conf))))))

(deftest actions-integration
  (testing "orphan detection logic works correctly"
    (let [files [(java.io.File. (build-path "target" "test" "channel1.xml")) (java.io.File. (build-path "target" "test" "channel2.xml"))]
          expected-paths #{(build-path "target" "test" "channel1.xml")}
          target-path (build-path "target" "test")]
      (let [orphaned-files (#'mirthsync.actions/find-orphaned-files-in-list files expected-paths target-path)]
        (is (= 1 (count orphaned-files)))
        (is (= (.getAbsolutePath (java.io.File. (build-path "target" "test" "channel2.xml"))) (.getAbsolutePath ^java.io.File (first orphaned-files))))))))

(deftest capture-pre-pull-files-test
  (testing "root-level APIs only capture managed files, not user files"
    (let [app-conf {:api :configuration-map
                    :target (build-path "target" "test")}
          mock-managed-files [(io/file (build-path "target" "test" "ConfigurationMap.xml"))]
          mock-user-file (io/file (build-path "target" "test" "user-file.txt"))]
      (with-redefs [mi/local-path (fn [_ _] (str (build-path "target" "test") java.io.File/separator))  ; Root directory with trailing separator
                    mi/api-files (fn [_ _] mock-managed-files)]  ; Only returns managed files
        (let [result (capture-pre-pull-local-files app-conf)]
          (is (contains? (set (:pre-pull-local-files result)) (first mock-managed-files)))
          (is (= 1 (count (:pre-pull-local-files result))))))))

  (testing "different API types use different file capture strategies"
    ; Test the core logic: root APIs use mi/api-files, subdirectory APIs use all-files-seq
    ; We test this by seeing that the path normalization logic works correctly
    (let [root-api-conf {:api :configuration-map :target (build-path "target" "test")}
          managed-files [(io/file (build-path "target" "test" "managed.xml"))]]
      (with-redefs [mi/local-path (fn [_ _] (build-path "target" "test"))  ; Root directory
                    mi/api-files (fn [_ _] managed-files)]
        ; Root API should only capture managed files returned by mi/api-files
        (let [result (capture-pre-pull-local-files root-api-conf)]
          (is (= 1 (count (:pre-pull-local-files result))))
          (is (= managed-files (:pre-pull-local-files result)))))))

  (testing "path normalization handles trailing separators correctly"
    (let [app-conf {:api :resources
                    :target (build-path "target" "test")}]  ; No trailing separator
      (with-redefs [mi/local-path (fn [_ _] (str (build-path "target" "test") java.io.File/separator))  ; With trailing separator
                    mi/api-files (fn [_ _] [(io/file (build-path "target" "test" "Resources.xml"))])]
        (let [result (capture-pre-pull-local-files app-conf)]
          (is (= 1 (count (:pre-pull-local-files result)))))))))

(deftest cleanup-orphaned-files-with-pre-pull-test
  (testing "does not delete user files in root directory"
    ; User files should not be captured by root-level APIs in the first place
    (let [app-conf {:delete-orphaned true
                    :target (build-path "target" "test")
                    :pre-pull-local-files [(io/file (build-path "target" "test" "ConfigurationMap.xml"))]}  ; Only managed files
          apis [:configuration-map :resources]]
      (with-redefs [get-remote-expected-file-paths (fn [_] #{(build-path "target" "test" "ConfigurationMap.xml") (build-path "target" "test" "Resources.xml")})]
        (let [result (cleanup-orphaned-files-with-pre-pull app-conf apis)]
          ; Should find no orphaned files since all pre-pull files have corresponding expected paths
          (is (= app-conf result))))))

  (testing "correctly identifies and removes duplicate files from multiple APIs"
    (let [orphaned-file (io/file (build-path "target" "test" "Channels" "orphaned.xml"))
          app-conf {:delete-orphaned true
                    :target (build-path "target" "test")
                    :pre-pull-local-files [orphaned-file orphaned-file]}  ; Same file captured twice
          apis [:channel-groups :channels]
          deleted-files (atom [])]
      (with-redefs [get-remote-expected-file-paths (fn [_] #{(build-path "target" "test" "Channels" "valid.xml")})
                    delete-orphaned-files (fn [conf files]
                                           (reset! deleted-files files)
                                           conf)]
        (cleanup-orphaned-files-with-pre-pull app-conf apis)
        ; Should only have 1 unique file, not 2 duplicates
        (is (= 1 (count @deleted-files)))
        (is (= orphaned-file (first @deleted-files))))))

  (testing "deletes genuine orphans from API-managed directories"
    ; The key insight: user files in root won't be captured by root-level APIs,
    ; but orphaned files in subdirectories will be captured by subdirectory APIs
    (let [orphaned-file (io/file (build-path "target" "test" "Channels" "orphaned.xml"))
          app-conf {:delete-orphaned true
                    :target (build-path "target" "test")
                    :pre-pull-local-files [orphaned-file]}  ; Only contains files from subdirectory APIs
          apis [:channels]
          deleted-files (atom [])]
      (with-redefs [get-remote-expected-file-paths (fn [_] #{(build-path "target" "test" "Channels" "valid.xml")})
                    delete-orphaned-files (fn [conf files]
                                           (reset! deleted-files files)
                                           conf)]
        (cleanup-orphaned-files-with-pre-pull app-conf apis)
        ; Should delete the genuinely orphaned file
        (is (= 1 (count @deleted-files)))
        (is (= orphaned-file (first @deleted-files))))))

  (testing "shows warning when delete-orphaned is false"
    (let [orphaned-file (io/file (build-path "target" "test" "Channels" "orphaned.xml"))
          app-conf {:delete-orphaned false
                    :target (build-path "target" "test")
                    :pre-pull-local-files [orphaned-file]}
          apis [:channels]
          deleted-files (atom [])]
      (with-redefs [get-remote-expected-file-paths (fn [_] #{(build-path "target" "test" "Channels" "valid.xml")})
                    delete-orphaned-files (fn [conf files]
                                           (reset! deleted-files files)
                                           conf)]
        (let [result (cleanup-orphaned-files-with-pre-pull app-conf apis)]
          ; Should not call delete-orphaned-files
          (is (= 0 (count @deleted-files)))
          ; Should return original app-conf unchanged
          (is (= app-conf result)))))))

(deftest safe-delete-file-test
  (let [safe-delete-file? #'mirthsync.actions/safe-delete-file?]  ; Access private function
    (testing "allows deletion of files within target directory"
      (let [target-dir (build-path "target" "test")
            file-in-target (io/file (build-path "target" "test" "subdir" "file.txt"))]
        (is (safe-delete-file? file-in-target target-dir))))

    (testing "prevents deletion of files outside target directory"
      (let [target-dir (build-path "target" "test")
            file-outside (io/file "/nonexistent/unsafe/path/file.txt")]
        (is (not (safe-delete-file? file-outside target-dir)))))

    (testing "works correctly even when file does not exist"
      (let [target-dir (build-path "target" "test")
            non-existent-file (io/file (build-path "target" "test" "non-existent.txt"))]
        ; Should still validate path correctly even if file doesn't exist
        (is (safe-delete-file? non-existent-file target-dir))))))

(deftest find-orphaned-files-path-normalization-test
  (let [find-orphaned-files-in-list #'mirthsync.actions/find-orphaned-files-in-list]
    (testing "uses canonical paths for consistent comparison"
      (let [target-dir (build-path "target" "test")
            ; Create a file with a path that includes . or .. components
            file-with-dots (io/file (build-path "target" "test" "." "subdir" "file.xml"))
            canonical-path (.getCanonicalPath file-with-dots)
            ; Expected paths should use the canonical form
            expected-paths #{(str target-dir java.io.File/separator "subdir" java.io.File/separator "file.xml")}]
        ; File should NOT be considered orphaned since its canonical path matches expected
        (let [orphaned (find-orphaned-files-in-list [file-with-dots] expected-paths target-dir)]
          (is (= 0 (count orphaned))
              "File with . in path should not be orphaned when canonical path matches expected"))))

    (testing "handles paths consistently across multiple invocations"
      (let [target-dir (build-path "target" "test")
            test-file (io/file (build-path "target" "test" "Channels" "channel1.xml"))
            expected-paths #{(build-path "target" "test" "Channels" "channel1.xml")}]
        ; Run the same check twice - should get same result both times
        (let [first-result (find-orphaned-files-in-list [test-file] expected-paths target-dir)
              second-result (find-orphaned-files-in-list [test-file] expected-paths target-dir)]
          (is (= (count first-result) (count second-result))
              "Multiple invocations should yield consistent results")
          (is (= 0 (count first-result))
              "File matching expected path should not be orphaned"))))

    (testing "correctly identifies actual orphans with canonical paths"
      (let [target-dir (build-path "target" "test")
            orphan-file (io/file (build-path "target" "test" "Channels" "orphaned.xml"))
            valid-file (io/file (build-path "target" "test" "Channels" "valid.xml"))
            expected-paths #{(build-path "target" "test" "Channels" "valid.xml")}]
        (let [orphaned (find-orphaned-files-in-list [orphan-file valid-file] expected-paths target-dir)]
          (is (= 1 (count orphaned))
              "Should find exactly one orphaned file")
          (is (= (.getCanonicalPath orphan-file) (.getCanonicalPath (first orphaned)))
              "Should identify the correct orphaned file"))))))

;;;; Synthetic server: channels in groups of 30, each pulled in code disk mode
;;;; to its xml file plus 9 scripts.

(def ^:private channels-per-group 30)

(defn- group-name [g] (str "Group " g))
(defn- channel-name [n] (str "Channel " n))
(defn- destination-name [n] (str "To System " n))
(defn- channel-id [n] (format "00000000-0000-0000-0000-%012d" n))

(def ^:private channel-xml
  "<channel version='4.5.2'>
  <id>%1$s</id>
  <nextMetaDataId>2</nextMetaDataId>
  <name>%2$s</name>
  <revision>1</revision>
  <sourceConnector version='4.5.2'>
    <metaDataId>0</metaDataId>
    <name>sourceConnector</name>
    <properties class='com.mirth.connect.connectors.vm.VmReceiverProperties' version='4.5.2'>
      <pluginProperties/>
      <sourceConnectorProperties version='4.5.2'>
        <responseVariable>None</responseVariable>
        <respondAfterProcessing>true</respondAfterProcessing>
        <processingThreads>1</processingThreads>
        <queueBufferSize>1000</queueBufferSize>
      </sourceConnectorProperties>
    </properties>
    <transformer version='4.5.2'>
      <elements>
        <com.mirth.connect.plugins.javascriptstep.JavaScriptStep version='4.5.2'>
          <name>Map fields</name>
          <sequenceNumber>0</sequenceNumber>
          <enabled>true</enabled>
          <script>channelMap.put('source', '%2$s');</script>
        </com.mirth.connect.plugins.javascriptstep.JavaScriptStep>
      </elements>
      <inboundDataType>RAW</inboundDataType>
      <outboundDataType>RAW</outboundDataType>
    </transformer>
    <filter version='4.5.2'>
      <elements>
        <com.mirth.connect.plugins.javascriptrule.JavaScriptRule version='4.5.2'>
          <name>Accept</name>
          <sequenceNumber>0</sequenceNumber>
          <enabled>true</enabled>
          <script>return true;</script>
        </com.mirth.connect.plugins.javascriptrule.JavaScriptRule>
      </elements>
    </filter>
    <transportName>Channel Reader</transportName>
    <mode>SOURCE</mode>
    <enabled>true</enabled>
  </sourceConnector>
  <destinationConnectors>
    <connector version='4.5.2'>
      <metaDataId>1</metaDataId>
      <name>%3$s</name>
      <properties class='com.mirth.connect.connectors.js.JavaScriptDispatcherProperties' version='4.5.2'>
        <pluginProperties/>
        <destinationConnectorProperties version='4.5.2'>
          <queueEnabled>false</queueEnabled>
          <retryCount>0</retryCount>
          <threadCount>1</threadCount>
        </destinationConnectorProperties>
        <script>logger.info('%3$s');</script>
      </properties>
      <transformer version='4.5.2'>
        <elements>
          <com.mirth.connect.plugins.javascriptstep.JavaScriptStep version='4.5.2'>
            <sequenceNumber>0</sequenceNumber>
            <enabled>true</enabled>
            <script>msg = msg;</script>
          </com.mirth.connect.plugins.javascriptstep.JavaScriptStep>
        </elements>
        <inboundDataType>RAW</inboundDataType>
        <outboundDataType>RAW</outboundDataType>
      </transformer>
      <filter version='4.5.2'>
        <elements>
          <com.mirth.connect.plugins.javascriptrule.JavaScriptRule version='4.5.2'>
            <name>true</name>
            <sequenceNumber>0</sequenceNumber>
            <enabled>true</enabled>
            <script>return true;</script>
          </com.mirth.connect.plugins.javascriptrule.JavaScriptRule>
        </elements>
      </filter>
      <transportName>JavaScript Writer</transportName>
      <mode>DESTINATION</mode>
      <enabled>true</enabled>
    </connector>
  </destinationConnectors>
  <preprocessingScript>return message;</preprocessingScript>
  <postprocessingScript>return;</postprocessingScript>
  <deployScript>return;</deployScript>
  <undeployScript>return;</undeployScript>
  <exportData>
    <metadata>
      <enabled>true</enabled>
    </metadata>
  </exportData>
</channel>")

(defn- group-xml [g channel-ns]
  (str "<channelGroup version='4.5.2'><id>group-" g "</id><name>" (group-name g) "</name><channels>"
       (apply str (map #(str "<channel version='4.5.2'><id>" (channel-id %) "</id></channel>") channel-ns))
       "</channels></channelGroup>"))

(defn- synthetic-server
  "Server responses by api, holding channels 0 to n-channels - 1."
  [n-channels]
  {:channel-groups (str "<list>"
                        (apply str (map-indexed group-xml (partition-all channels-per-group (range n-channels))))
                        "</list>")
   :channels (str "<list>"
                  (apply str (map #(format channel-xml (channel-id %) (channel-name %) (destination-name %))
                                  (range n-channels)))
                  "</list>")})

(defn- synthetic-channel-files
  "Paths of the 10 files a code disk mode pull writes for channel n."
  [target n]
  (let [dir (build-path target "Channels" (group-name (quot n channels-per-group)) (channel-name n))
        destination (str "destinationConnector-" (destination-name n))]
    (cons (str dir ".xml")
          (map #(build-path dir (str % ".js"))
               ["sourceConnector-transformer-step-0-Map fields"
                "sourceConnector-filter-step-0-Accept"
                destination
                (str destination "-transformer-step-0")
                (str destination "-filter-step-0-true")
                "PreprocessingScript"
                "PostprocessingScript"
                "DeployScript"
                "UndeployScript"]))))

(defn- serve
  "Stands in for mhttp/fetch-all, answering from the synthetic server."
  [server]
  (fn [{:keys [api]} find-elements]
    (find-elements (mxml/to-zip (get server api)))))

(defn- canonical-paths [files]
  (set (map #(.getCanonicalPath (io/file %)) files)))

(deftest remote-expected-paths-test
  (let [target (build-path "target" "orphan-check-test")
        apis [:channel-groups :channels]
        delete-target #(doseq [f (reverse (file-seq (io/file target)))] (io/delete-file f true))]
    (delete-target)
    (with-redefs [mhttp/fetch-all (serve (synthetic-server 3))]
      (let [conf (api/iterate-apis {:target target :disk-mode "code"} apis api/preprocess-api)
            conf (api/iterate-apis conf apis download)
            pulled (filter #(.isFile ^java.io.File %) (file-seq (io/file target)))]
        (testing "the pull writes each channel's xml and scripts"
          (is (= (canonical-paths (cons (build-path target "Channels" (group-name 0) "index.xml")
                                        (mapcat #(synthetic-channel-files target %) (range 3))))
                 (canonical-paths pulled))))

        (testing "expected paths are the pulled files, not the scripts of the channels that follow"
          (is (= (canonical-paths pulled)
                 (canonical-paths (mapcat #(get-remote-expected-file-paths (assoc conf :api %)) apis)))))

        (testing "a stale script named like a later channel's script is an orphan"
          (let [stale (io/file (build-path target "Channels" (group-name 0) (channel-name 0)
                                           (str "destinationConnector-" (destination-name 1) ".js")))
                orphans (atom nil)]
            (spit stale "")
            (with-redefs [delete-orphaned-files (fn [conf files] (reset! orphans files) conf)]
              (cleanup-orphaned-files-with-pre-pull
               (assoc (api/iterate-apis conf apis capture-pre-pull-local-files) :delete-orphaned true)
               apis))
            (is (= [(.getCanonicalPath stale)] (map #(.getCanonicalPath ^java.io.File %) @orphans)))))))
    (delete-target)))

(deftest orphan-check-scaling-test
  (testing "300 channels x 10 files: each script is named once, not once per following channel"
    (let [target (build-path "target" "orphan-check-test")
          apis [:channel-groups :channels]
          n 300
          ;; Still on disk but no longer on the server
          deleted-channel (synthetic-channel-files target n)
          local-files (map io/file (concat (map #(build-path target "Channels" (group-name %) "index.xml")
                                                (range (quot n channels-per-group)))
                                           (mapcat #(synthetic-channel-files target %) (range (inc n)))))
          safe-name mf/safe-name
          safe-name-calls (atom 0)
          orphans (atom nil)]
      (with-redefs [mhttp/fetch-all (serve (synthetic-server n))
                    mf/safe-name (fn [name] (swap! safe-name-calls inc) (safe-name name))
                    delete-orphaned-files (fn [conf files] (reset! orphans files) conf)]
        (cleanup-orphaned-files-with-pre-pull
         (assoc (api/iterate-apis {:target target :disk-mode "code"} apis api/preprocess-api)
                :delete-orphaned true
                :pre-pull-local-files local-files)
         apis))
      (is (= 10 (count @orphans)))
      (is (= (canonical-paths deleted-channel) (canonical-paths @orphans)))
      (is (< @safe-name-calls (* 2 (count local-files)))
          "safe-name calls should grow linearly with the number of files"))))
