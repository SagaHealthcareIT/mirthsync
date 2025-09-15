(ns mirthsync.delete-orphaned-test
  (:require [mirthsync.actions :refer :all]
            [mirthsync.cli :refer :all]
            [mirthsync.interfaces :as mi]
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
    (let [files [(java.io.File. "target/test/channel1.xml") (java.io.File. "target/test/channel2.xml")]
          expected-paths #{"target/test/channel1.xml"}
          target-path "target/test"]
      (let [orphaned-files (#'mirthsync.actions/find-orphaned-files-in-list files expected-paths target-path)]
        (is (= 1 (count orphaned-files)))
        (is (= (.getAbsolutePath (java.io.File. "target/test/channel2.xml")) (.getAbsolutePath (first orphaned-files))))))))

(deftest capture-pre-pull-files-test
  (testing "root-level APIs only capture managed files, not user files"
    (let [app-conf {:api :configuration-map
                    :target "target/test"}
          mock-managed-files [(io/file "target/test/ConfigurationMap.xml")]
          mock-user-file (io/file "target/test/user-file.txt")]
      (with-redefs [mi/local-path (fn [_ _] "target/test/")  ; Root directory with trailing slash
                    mi/api-files (fn [_ _] mock-managed-files)]  ; Only returns managed files
        (let [result (capture-pre-pull-local-files app-conf)]
          (is (contains? (set (:pre-pull-local-files result)) (first mock-managed-files)))
          (is (= 1 (count (:pre-pull-local-files result))))))))

  (testing "different API types use different file capture strategies"
    ; Test the core logic: root APIs use mi/api-files, subdirectory APIs use all-files-seq
    ; We test this by seeing that the path normalization logic works correctly
    (let [root-api-conf {:api :configuration-map :target "target/test"}
          managed-files [(io/file "target/test/managed.xml")]]
      (with-redefs [mi/local-path (fn [_ _] "target/test")  ; Root directory
                    mi/api-files (fn [_ _] managed-files)]
        ; Root API should only capture managed files returned by mi/api-files
        (let [result (capture-pre-pull-local-files root-api-conf)]
          (is (= 1 (count (:pre-pull-local-files result))))
          (is (= managed-files (:pre-pull-local-files result))))))))

  (testing "path normalization handles trailing slashes correctly"
    (let [app-conf {:api :resources
                    :target "target/test"}]  ; No trailing slash
      (with-redefs [mi/local-path (fn [_ _] "target/test/")  ; With trailing slash
                    mi/api-files (fn [_ _] [(io/file "target/test/Resources.xml")])]
        (let [result (capture-pre-pull-local-files app-conf)]
          (is (= 1 (count (:pre-pull-local-files result))))))))

(deftest cleanup-orphaned-files-with-pre-pull-test
  (testing "does not delete user files in root directory"
    ; User files should not be captured by root-level APIs in the first place
    (let [app-conf {:delete-orphaned true
                    :target "target/test"
                    :pre-pull-local-files [(io/file "target/test/ConfigurationMap.xml")]}  ; Only managed files
          apis [:configuration-map :resources]]
      (with-redefs [get-remote-expected-file-paths (fn [_] #{"target/test/ConfigurationMap.xml" "target/test/Resources.xml"})]
        (let [result (cleanup-orphaned-files-with-pre-pull app-conf apis)]
          ; Should find no orphaned files since all pre-pull files have corresponding expected paths
          (is (= app-conf result))))))

  (testing "correctly identifies and removes duplicate files from multiple APIs"
    (let [orphaned-file (io/file "target/test/Channels/orphaned.xml")
          app-conf {:delete-orphaned true
                    :target "target/test"
                    :pre-pull-local-files [orphaned-file orphaned-file]}  ; Same file captured twice
          apis [:channel-groups :channels]
          deleted-files (atom [])]
      (with-redefs [get-remote-expected-file-paths (fn [_] #{"target/test/Channels/valid.xml"})
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
    (let [orphaned-file (io/file "target/test/Channels/orphaned.xml")
          app-conf {:delete-orphaned true
                    :target "target/test"
                    :pre-pull-local-files [orphaned-file]}  ; Only contains files from subdirectory APIs
          apis [:channels]
          deleted-files (atom [])]
      (with-redefs [get-remote-expected-file-paths (fn [_] #{"target/test/Channels/valid.xml"})
                    delete-orphaned-files (fn [conf files]
                                           (reset! deleted-files files)
                                           conf)]
        (cleanup-orphaned-files-with-pre-pull app-conf apis)
        ; Should delete the genuinely orphaned file
        (is (= 1 (count @deleted-files)))
        (is (= orphaned-file (first @deleted-files))))))

  (testing "shows warning when delete-orphaned is false"
    (let [orphaned-file (io/file "target/test/Channels/orphaned.xml")
          app-conf {:delete-orphaned false
                    :target "target/test"
                    :pre-pull-local-files [orphaned-file]}
          apis [:channels]
          deleted-files (atom [])]
      (with-redefs [get-remote-expected-file-paths (fn [_] #{"target/test/Channels/valid.xml"})
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
      (let [target-dir "target/test"
            file-in-target (io/file "target/test/subdir/file.txt")]
        (is (safe-delete-file? file-in-target target-dir))))

    (testing "prevents deletion of files outside target directory"
      (let [target-dir "target/test"
            file-outside (io/file "/nonexistent/unsafe/path/file.txt")]
        (is (not (safe-delete-file? file-outside target-dir)))))

    (testing "works correctly even when file does not exist"
      (let [target-dir "target/test"
            non-existent-file (io/file "target/test/non-existent.txt")]
        ; Should still validate path correctly even if file doesn't exist
        (is (safe-delete-file? non-existent-file target-dir))))))
