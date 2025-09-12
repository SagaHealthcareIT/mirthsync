(ns mirthsync.state-integration-test
  (:require [mirthsync.state :as state]
            [mirthsync.actions :as actions]
            [mirthsync.fixture-tools :refer :all]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.set])
  (:import java.io.File))

(defn state-file-path
  "Get the path to the state file for a target directory"
  [target-dir]
  (str target-dir File/separator ".mirthsync-state.json"))

(defn state-file-exists?
  "Check if state file exists"
  [target-dir]
  (.exists (io/file (state-file-path target-dir))))

(defn load-state-file
  "Load and parse the state file"
  [target-dir]
  (when (state-file-exists? target-dir)
    (-> (state-file-path target-dir)
        slurp
        (json/read-str :key-fn keyword))))

(defn count-entities-in-state
  "Count total entities across all APIs in a state"
  [state]
  (reduce (fn [total [api entities]]
            (+ total (count entities)))
          0
          state))

(deftest test-state-file-operations-integration
  (testing "State file operations work correctly in integration scenarios"
    (let [repo-dir (str "/tmp/mirthsync-integration-test-" (System/currentTimeMillis))]
      (try
        ;; Ensure clean directory
        (ensure-directory-exists repo-dir)
        
        ;; Manually create a test state file (simulating what a pull would do)
        (let [test-state {:channels {"ch-1" {:id "ch-1" :name "Test Channel" :file-path "/test/path.xml" :api :channels}}
                          :code-templates {"ct-1" {:id "ct-1" :name "Test Template" :file-path "/test/template.xml" :api :code-templates}}}]
          
          ;; Save state (simulating successful pull)
          (is (= true (state/save-sync-state repo-dir test-state)))
          
          ;; Verify state file was created
          (is (state-file-exists? repo-dir)
              "State file should be created after save")
          
          ;; Load and verify state
          (let [loaded-state (load-state-file repo-dir)
                entity-count (count-entities-in-state loaded-state)]
            
            (is (> entity-count 0)
                "Loaded state should contain entities")
            
            ;; Verify state structure
            (is (contains? loaded-state :channels)
                "State should contain channels")
            (is (contains? loaded-state :code-templates)
                "State should contain code templates")
            
            ;; Test state consistency
            (is (= 2 entity-count)
                "Should have exactly 2 entities (1 channel + 1 template)")))
        
        (finally
          ;; Cleanup
          (when (.exists (io/file repo-dir))
            (let [dir (io/file repo-dir)]
              (doseq [^java.io.File file (reverse (file-seq dir))]
                (.delete file)))))))))

(deftest test-state-persistence-across-different-modes
  (testing "State file format is consistent across different usage patterns"
    (let [base-dir (str "/tmp/mirthsync-mode-test-" (System/currentTimeMillis))]
      (try
        ;; Test different state configurations that would come from different disk modes
        (doseq [[mode test-state] {"backup" {:channels {"ch-1" {:id "ch-1" :name "Channel from backup" :file-path "/backup/ch1.xml" :api :channels}}}
                                   "groups" {:channels {"ch-1" {:id "ch-1" :name "Channel from groups" :file-path "/groups/Group1/ch1.xml" :api :channels}}}
                                   "items"  {:channels {"ch-1" {:id "ch-1" :name "Channel from items" :file-path "/items/ch1.xml" :api :channels}}}
                                   "code"   {:channels {"ch-1" {:id "ch-1" :name "Channel from code" :file-path "/code/ch1-code.js" :api :channels}}}}]
          (let [mode-dir (str base-dir "-" mode)]
            (ensure-directory-exists mode-dir)
            
            ;; Save state for this mode
            (is (= true (state/save-sync-state mode-dir test-state))
                (str "State save should succeed for mode: " mode))
            
            ;; Verify state file created
            (is (state-file-exists? mode-dir)
                (str "State file should exist for mode: " mode))
            
            ;; Load and verify state structure is correct
            (let [loaded-state (load-state-file mode-dir)]
              (is (= 1 (count-entities-in-state loaded-state))
                  (str "Should have 1 entity for mode: " mode))
              (is (contains? loaded-state :channels)
                  (str "Should contain channels for mode: " mode)))))
        
        (finally
          ;; Cleanup all mode directories
          (doseq [mode ["backup" "groups" "items" "code"]]
            (let [mode-dir (str base-dir "-" mode)]
              (when (.exists (io/file mode-dir))
                (let [dir (io/file mode-dir)]
                  (doseq [^java.io.File file (reverse (file-seq dir))]
                    (.delete file))))))))))

(deftest test-state-with-path-restrictions
  (testing "State tracking works correctly with different path-based filtering scenarios"
    (let [repo-dir (str "/tmp/mirthsync-restrict-test-" (System/currentTimeMillis))]
      (try
        (ensure-directory-exists repo-dir)
        
        ;; Test scenario: state with only channels (simulating --restrict-to-path Channels/)
        (let [channels-only-state {:channels {"ch-1" {:id "ch-1" :name "Test Channel" :file-path "/Channels/test.xml" :api :channels}
                                              "ch-2" {:id "ch-2" :name "Test Channel 2" :file-path "/Channels/test2.xml" :api :channels}}}]
          
          (state/save-sync-state repo-dir channels-only-state)
          (let [loaded-state (load-state-file repo-dir)]
            (is (= 2 (count-entities-in-state loaded-state))
                "Should have 2 channel entities")
            (is (contains? loaded-state :channels)
                "Should contain channels")
            (is (not (contains? loaded-state :code-templates))
                "Should not contain code templates when only channels pulled")))
        
        ;; Test scenario: expanding to include more entity types
        (let [expanded-state {:channels {"ch-1" {:id "ch-1" :name "Test Channel" :file-path "/Channels/test.xml" :api :channels}
                                         "ch-2" {:id "ch-2" :name "Test Channel 2" :file-path "/Channels/test2.xml" :api :channels}}
                              :code-templates {"ct-1" {:id "ct-1" :name "Test Template" :file-path "/CodeTemplates/test.xml" :api :code-templates}}}]
          
          (state/save-sync-state repo-dir expanded-state)
          (let [loaded-state (load-state-file repo-dir)]
            (is (= 3 (count-entities-in-state loaded-state))
                "Should have 3 total entities after expansion")
            (is (contains? loaded-state :channels)
                "Should still contain channels")
            (is (contains? loaded-state :code-templates)
                "Should now contain code templates")))
        
        (finally
          (when (.exists (io/file repo-dir))
            (let [dir (io/file repo-dir)]
              (doseq [^java.io.File file (reverse (file-seq dir))]
                (.delete file)))))))))

(deftest test-state-file-format-validation
  (testing "State file contains properly structured data regardless of how it's created"
    (let [repo-dir (str "/tmp/mirthsync-format-test-" (System/currentTimeMillis))]
      (try
        (ensure-directory-exists repo-dir)
        
        ;; Create a comprehensive state with multiple entity types
        (let [comprehensive-state {:channels {"ch-1" {:id "ch-1" :name "Test Channel" :file-path "/path1.xml" :api :channels}
                                              "ch-2" {:id "ch-2" :name "Test Channel 2" :file-path "/path2.xml" :api :channels}}
                                   :code-templates {"ct-1" {:id "ct-1" :name "Test Template" :file-path "/tmpl1.xml" :api :code-templates}
                                                    "ct-2" {:id "ct-2" :name "Test Template 2" :file-path "/tmpl2.xml" :api :code-templates}}
                                   :global-scripts {"gs-1" {:id "gs-1" :name "Test Script" :file-path "/script1.js" :api :global-scripts}}}]
          
          (state/save-sync-state repo-dir comprehensive-state)
          (let [loaded-state (load-state-file repo-dir)]
            
            ;; Verify overall structure
            (is (map? loaded-state) "State should be a map")
            (is (= 5 (count-entities-in-state loaded-state)) "Should have 5 total entities")
            
            ;; Check entity structure for each API
            (doseq [[api entities] loaded-state]
              (is (keyword? api) (str "API key should be keyword: " api))
              (is (map? entities) (str "Entities should be a map for API: " api))
              
              ;; Check individual entity structure
              (doseq [[entity-id entity-data] entities]
                (is (string? entity-id) (str "Entity ID should be string: " entity-id))
                (is (map? entity-data) (str "Entity data should be a map: " entity-data))
                
                ;; Required fields
                (is (contains? entity-data :id) (str "Entity should have :id field: " entity-data))
                (is (contains? entity-data :name) (str "Entity should have :name field: " entity-data))
                (is (contains? entity-data :file-path) (str "Entity should have :file-path field: " entity-data))
                (is (contains? entity-data :api) (str "Entity should have :api field: " entity-data))
                
                ;; Field types and consistency
                (is (= (:id entity-data) entity-id) "Entity ID should match map key")
                (is (keyword? (:api entity-data)) "Entity API should be keyword")
                (is (string? (:file-path entity-data)) "Entity file-path should be string")))))
        
        (finally
          (when (.exists (io/file repo-dir))
            (let [dir (io/file repo-dir)]
              (doseq [^java.io.File file (reverse (file-seq dir))]
                (.delete file)))))))))

(deftest test-state-error-recovery-scenarios
  (testing "State tracking handles various error conditions gracefully"
    (let [repo-dir (str "/tmp/mirthsync-error-test-" (System/currentTimeMillis))]
      (try
        (ensure-directory-exists repo-dir)
        
        ;; Test 1: Corrupted state file
        (testing "Handles corrupted state file"
          (spit (state-file-path repo-dir) "invalid json content")
          
          ;; Loading corrupted state should return empty map
          (let [recovered-state (state/load-sync-state repo-dir)]
            (is (= {} recovered-state) "Should return empty map for corrupted state")))
        
        ;; Test 2: Missing state file
        (testing "Handles missing state file"
          (.delete (io/file (state-file-path repo-dir)))
          (let [empty-state (state/load-sync-state repo-dir)]
            (is (= {} empty-state) "Should return empty map for missing state file")))
        
        ;; Test 3: State file recovery after error
        (testing "Can create new state after error"
          (let [recovery-state {:channels {"recovery-ch" {:id "recovery-ch" :name "Recovery Channel" :file-path "/recovery.xml" :api :channels}}}]
            (is (= true (state/save-sync-state repo-dir recovery-state)) "Should be able to save state after recovery")
            (let [loaded-recovery (state/load-sync-state repo-dir)]
              (is (= 1 (count-entities-in-state loaded-recovery)) "Should have recovered with 1 entity"))))
        
        (finally
          (when (.exists (io/file repo-dir))
            (let [dir (io/file repo-dir)]
              (doseq [^java.io.File file (reverse (file-seq dir))]
                (.delete file)))))))))

(deftest test-state-consistency-across-operations
  (testing "State remains consistent across multiple save/load cycles"
    (let [repo-dir (str "/tmp/mirthsync-consistency-test-" (System/currentTimeMillis))]
      (try
        (ensure-directory-exists repo-dir)
        
        (let [states (atom [])
              base-state {:channels {"ch-1" {:id "ch-1" :name "Consistent Channel" :file-path "/consistent.xml" :api :channels}}}]
          
          ;; Perform multiple save/load cycles
          (dotimes [cycle 5]
            (let [cycle-state (assoc base-state :cycle cycle)]
              (state/save-sync-state repo-dir cycle-state)
              (let [loaded-state (state/load-sync-state repo-dir)]
                (swap! states conj loaded-state))))
          
          ;; All loaded states should be consistent
          (is (= 5 (count @states)) "Should have 5 state snapshots")
          
          ;; Entity structure should be consistent
          (doseq [loaded-state @states]
            (is (= 1 (count-entities-in-state loaded-state)) "Each state should have 1 entity")
            (is (contains? loaded-state :channels) "Each state should contain channels")
            (is (= "ch-1" (get-in loaded-state [:channels :ch-1 :id])) "Channel ID should be consistent")))
        
        (finally
          (when (.exists (io/file repo-dir))
            (let [dir (io/file repo-dir)]
              (doseq [^java.io.File file (reverse (file-seq dir))]
                (.delete file)))))))))

(deftest test-configuration-map-state-handling
  (testing "State tracking correctly handles configuration map scenarios"
    (let [repo-dir-with-config (str "/tmp/mirthsync-config-with-" (System/currentTimeMillis))
          repo-dir-without-config (str "/tmp/mirthsync-config-without-" (System/currentTimeMillis))]
      (try
        ;; Scenario with configuration map
        (ensure-directory-exists repo-dir-with-config)
        (let [state-with-config {:channels {"ch-1" {:id "ch-1" :name "Channel" :file-path "/channel.xml" :api :channels}}
                                 :configuration-map {"config-1" {:id "config-1" :name "Config" :file-path "/config.xml" :api :configuration-map}}}]
          
          (state/save-sync-state repo-dir-with-config state-with-config)
          (let [loaded-with-config (load-state-file repo-dir-with-config)]
            (is (= 2 (count-entities-in-state loaded-with-config)) "Should have 2 entities with config map")
            (is (contains? loaded-with-config :configuration-map) "Should contain configuration map")))
        
        ;; Scenario without configuration map
        (ensure-directory-exists repo-dir-without-config)
        (let [state-without-config {:channels {"ch-1" {:id "ch-1" :name "Channel" :file-path "/channel.xml" :api :channels}}}]
          
          (state/save-sync-state repo-dir-without-config state-without-config)
          (let [loaded-without-config (load-state-file repo-dir-without-config)]
            (is (= 1 (count-entities-in-state loaded-without-config)) "Should have 1 entity without config map")
            (is (not (contains? loaded-without-config :configuration-map)) "Should not contain configuration map")))
        
        ;; Both should have proper core structure
        (let [with-config-state (load-state-file repo-dir-with-config)
              without-config-state (load-state-file repo-dir-without-config)]
          (is (contains? with-config-state :channels) "Both should have channels (with config)")
          (is (contains? without-config-state :channels) "Both should have channels (without config)"))
        
        (finally
          (when (.exists (io/file repo-dir-with-config))
            (let [dir (io/file repo-dir-with-config)]
              (doseq [^java.io.File file (reverse (file-seq dir))]
                (.delete file))))
          (when (.exists (io/file repo-dir-without-config))
            (let [dir (io/file repo-dir-without-config)]
              (doseq [^java.io.File file (reverse (file-seq dir))]
                (.delete file))))))))))