(ns mirthsync.delete-orphaned-test
  (:require [mirthsync.actions :refer :all]
            [mirthsync.cli :refer :all]
            [mirthsync.interfaces :as mi]
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
      (with-redefs [mi/local-path (fn [_ _] (str (build-path "target" "test") "/"))  ; Root directory with trailing slash
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
          (is (= managed-files (:pre-pull-local-files result))))))))

  (testing "path normalization handles trailing slashes correctly"
    (let [app-conf {:api :resources
                    :target (build-path "target" "test")}]  ; No trailing slash
      (with-redefs [mi/local-path (fn [_ _] (str (build-path "target" "test") "/"))  ; With trailing slash
                    mi/api-files (fn [_ _] [(io/file (build-path "target" "test" "Resources.xml"))])]
        (let [result (capture-pre-pull-local-files app-conf)]
          (is (= 1 (count (:pre-pull-local-files result))))))))

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
