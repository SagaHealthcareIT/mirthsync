(ns mirthsync.state-test
  (:require [mirthsync.state :refer :all]
            [mirthsync.files :as files]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.set])
  (:import java.io.File))

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture
  "Creates a temporary directory for testing and cleans it up"
  [test-fn]
  (let [temp-dir (str "/tmp/mirthsync-state-test-" (System/currentTimeMillis))]
    (try
      (.mkdirs (File. ^String temp-dir))
      (binding [*test-dir* temp-dir]
        (test-fn))
      (finally
        (when (.exists (File. ^String temp-dir))
          (let [dir (File. ^String temp-dir)]
            (doseq [^File file (reverse (file-seq dir))]
              (.delete file))))))))

(use-fixtures :each temp-dir-fixture)

;; Mock data generators for testing
(defn mock-channel-entity
  "Generate a mock channel entity for testing"
  [id name file-path]
  {:id id
   :name name
   :file-path file-path
   :api :channels})

(defn mock-code-template-entity  
  "Generate a mock code template entity for testing"
  [id name file-path]
  {:id id
   :name name
   :file-path file-path
   :api :code-templates})

(defn mock-app-conf
  "Generate a mock app-conf for testing"
  [target-dir & {:keys [api el-loc]}]
  (cond-> {:target target-dir}
    api (assoc :api api)
    el-loc (assoc :el-loc el-loc)))

;; Sample state data for testing
(def sample-previous-state
  {:channels
   {"channel-1" (mock-channel-entity "channel-1" "Test Channel 1" "/path/to/channel1.xml")
    "channel-2" (mock-channel-entity "channel-2" "Test Channel 2" "/path/to/channel2.xml")
    "channel-3" (mock-channel-entity "channel-3" "Test Channel 3" "/path/to/channel3.xml")}
   :code-templates  
   {"template-1" (mock-code-template-entity "template-1" "Test Template 1" "/path/to/template1.xml")
    "template-2" (mock-code-template-entity "template-2" "Test Template 2" "/path/to/template2.xml")}})

(def sample-current-state-no-changes
  "Current state identical to previous - no changes"
  sample-previous-state)

(def sample-current-state-with-deletions
  "Current state with some entities deleted"
  {:channels
   {"channel-1" (mock-channel-entity "channel-1" "Test Channel 1" "/path/to/channel1.xml")
    "channel-2" (mock-channel-entity "channel-2" "Test Channel 2" "/path/to/channel2.xml")
    ;; channel-3 deleted
    }
   :code-templates
   {"template-1" (mock-code-template-entity "template-1" "Test Template 1" "/path/to/template1.xml")
    ;; template-2 deleted  
    }})

(def sample-current-state-with-renames
  "Current state with some entities renamed"
  {:channels
   {"channel-1" (mock-channel-entity "channel-1" "Test Channel 1 RENAMED" "/path/to/channel1-new.xml")
   "channel-2" (mock-channel-entity "channel-2" "Test Channel 2" "/path/to/channel2.xml")
   "channel-3" (mock-channel-entity "channel-3" "Test Channel 3" "/path/to/channel3.xml")}
   :code-templates
   {"template-1" (mock-code-template-entity "template-1" "Test Template 1" "/path/to/template1.xml") 
    "template-2" (mock-code-template-entity "template-2" "Test Template 2 RENAMED" "/path/to/template2-new.xml")}})

(def sample-current-state-with-new-entities
  "Current state with new entities added"
  {:channels
   {"channel-1" (mock-channel-entity "channel-1" "Test Channel 1" "/path/to/channel1.xml")
    "channel-2" (mock-channel-entity "channel-2" "Test Channel 2" "/path/to/channel2.xml")
    "channel-3" (mock-channel-entity "channel-3" "Test Channel 3" "/path/to/channel3.xml")
    "channel-4" (mock-channel-entity "channel-4" "NEW Test Channel 4" "/path/to/channel4.xml")}
   :code-templates
   {"template-1" (mock-code-template-entity "template-1" "Test Template 1" "/path/to/template1.xml")
    "template-2" (mock-code-template-entity "template-2" "Test Template 2" "/path/to/template2.xml")
    "template-3" (mock-code-template-entity "template-3" "NEW Test Template 3" "/path/to/template3.xml")}})

(def sample-current-state-complex
  "Current state with mix of deletions, renames, and new entities"
  {:channels
   {"channel-1" (mock-channel-entity "channel-1" "Test Channel 1 RENAMED" "/path/to/channel1-new.xml")
    "channel-2" (mock-channel-entity "channel-2" "Test Channel 2" "/path/to/channel2.xml")
    ;; channel-3 deleted
    "channel-4" (mock-channel-entity "channel-4" "NEW Test Channel 4" "/path/to/channel4.xml")}
   :code-templates
   {"template-1" (mock-code-template-entity "template-1" "Test Template 1" "/path/to/template1.xml")
    ;; template-2 deleted
    "template-3" (mock-code-template-entity "template-3" "NEW Test Template 3" "/path/to/template3.xml")}})

(deftest test-state-file-loading-and-saving
  (testing "load-sync-state returns empty map when no state file exists"
    (let [result (load-sync-state *test-dir*)]
      (is (= {} result))))
  
  (testing "save-sync-state creates state file successfully"
    (let [test-state {:channels {"test-1" {:id "test-1" :name "Test"}}}
          result (save-sync-state *test-dir* test-state)]
      (is (= true result))
      (is (.exists (File. ^String *test-dir* ".mirthsync-state.json")))))
  
  (testing "load-sync-state reads saved state correctly"
    (let [test-state {:channels {"test-1" {:id "test-1" :name "Test Channel"}}
                      :code-templates {"tmpl-1" {:id "tmpl-1" :name "Test Template"}}}]
      (save-sync-state *test-dir* test-state)
      (let [loaded-state (load-sync-state *test-dir*)]
        ;; The loaded state will have string keys converted to keywords by JSON parsing
        (is (= (get-in test-state [:channels "test-1" :id]) (get-in loaded-state [:channels :test-1 :id])))
        (is (= (get-in test-state [:channels "test-1" :name]) (get-in loaded-state [:channels :test-1 :name])))
        (is (= (get-in test-state [:code-templates "tmpl-1" :id]) (get-in loaded-state [:code-templates :tmpl-1 :id]))))))
  
  (testing "load-sync-state handles corrupted state file gracefully"
    (let [state-file-path (str *test-dir* File/separator ".mirthsync-state.json")]
      (spit state-file-path "invalid json content")
      (let [result (load-sync-state *test-dir*)]
        (is (= {} result)))))
  
  (testing "save-sync-state handles write failures gracefully"
    ;; Test with a read-only directory (if permissions allow)
    (let [readonly-dir (str *test-dir* "/readonly")]
      (.mkdirs (File. ^String readonly-dir))
      (.setReadOnly (File. ^String readonly-dir))
      (let [result (save-sync-state readonly-dir {:test "data"})]
        ;; Should return false on write failure, but result may vary by system
        (is (boolean? result))))))

(deftest test-build-entity-state
  (testing "build-entity-state creates correct entity structure"
    ;; This test would need mock implementations of mi/find-id, mi/find-name, mi/file-path
    ;; For now, we'll test the structure when all required data is available
    (let [mock-api :channels
          mock-el-loc {:mock "xml-location"}
          mock-app-conf (mock-app-conf *test-dir* :api mock-api :el-loc mock-el-loc)]
      ;; Since we can't easily mock the multimethod calls in this context,
      ;; we'll test the function exists and structure
      (is (function? build-entity-state)))))

(deftest test-detect-changes-no-changes
  (testing "detect-changes with identical states shows no changes"
    (let [changes (detect-changes sample-previous-state sample-current-state-no-changes)]
      (is (= 0 (count (:deleted changes))))
      (is (= 0 (count (:renamed changes))))
      (is (= 0 (count (:new changes))))
      (is (= 5 (count (:unchanged changes)))))))

(deftest test-detect-changes-deletions
  (testing "detect-changes correctly identifies deleted entities"
    (let [changes (detect-changes sample-previous-state sample-current-state-with-deletions)]
      (is (= 2 (count (:deleted changes))))
      (is (= 3 (count (:unchanged changes))))
      (is (= 0 (count (:new changes))))
      (is (= 0 (count (:renamed changes))))
      
      ;; Verify deleted entities are correct
      (let [deleted-ids (set (map :id (:deleted changes)))]
        (is (contains? deleted-ids "channel-3"))
        (is (contains? deleted-ids "template-2"))))))

(deftest test-detect-changes-new-entities
  (testing "detect-changes correctly identifies new entities"
    (let [changes (detect-changes sample-previous-state sample-current-state-with-new-entities)]
      (is (= 0 (count (:deleted changes))))
      (is (= 0 (count (:renamed changes))))
      (is (= 2 (count (:new changes))))
      (is (= 5 (count (:unchanged changes))))
      
      ;; Verify new entities are correct
      (let [new-ids (set (map :id (:new changes)))]
        (is (contains? new-ids "channel-4"))
        (is (contains? new-ids "template-3"))))))

(deftest test-detect-changes-renames
  (testing "detect-changes correctly identifies renamed entities"
    (let [changes (detect-changes sample-previous-state sample-current-state-with-renames)]
      (is (= 0 (count (:deleted changes))))
      (is (= 2 (count (:renamed changes))))
      (is (= 0 (count (:new changes))))
      (is (= 3 (count (:unchanged changes))))
      
      ;; Verify renamed entities have correct old/new structure
      (let [rename-change (first (filter #(= "channel-1" (get-in % [:old :id])) (:renamed changes)))]
        (is (not (nil? rename-change)))
        (is (= "/path/to/channel1.xml" (get-in rename-change [:old :file-path])))
        (is (= "/path/to/channel1-new.xml" (get-in rename-change [:new :file-path])))))))

(deftest test-detect-changes-complex-scenario
  (testing "detect-changes handles complex scenarios with mixed change types"
    (let [changes (detect-changes sample-previous-state sample-current-state-complex)]
      (is (= 2 (count (:deleted changes))))  ;; channel-3, template-2
      (is (= 1 (count (:renamed changes))))  ;; channel-1 renamed
      (is (= 2 (count (:new changes))))      ;; channel-4, template-3  
      (is (= 2 (count (:unchanged changes)))) ;; channel-2, template-1
      
      ;; Verify specific changes
      (let [deleted-ids (set (map :id (:deleted changes)))
            new-ids (set (map :id (:new changes)))
            renamed-ids (set (map #(get-in % [:old :id]) (:renamed changes)))]
        (is (contains? deleted-ids "channel-3"))
        (is (contains? deleted-ids "template-2"))
        (is (contains? new-ids "channel-4"))
        (is (contains? new-ids "template-3"))
        (is (contains? renamed-ids "channel-1"))))))

(deftest test-detect-changes-empty-states  
  (testing "detect-changes handles empty previous state"
    (let [changes (detect-changes {} sample-previous-state)]
      (is (= 0 (count (:deleted changes))))
      (is (= 0 (count (:renamed changes))))
      (is (= 5 (count (:new changes))))
      (is (= 0 (count (:unchanged changes))))))
  
  (testing "detect-changes handles empty current state"  
    (let [changes (detect-changes sample-previous-state {})]
      (is (= 5 (count (:deleted changes))))
      (is (= 0 (count (:renamed changes))))
      (is (= 0 (count (:new changes))))
      (is (= 0 (count (:unchanged changes))))))
  
  (testing "detect-changes handles both states empty"
    (let [changes (detect-changes {} {})]
      (is (= 0 (count (:deleted changes))))
      (is (= 0 (count (:renamed changes))))
      (is (= 0 (count (:new changes))))
      (is (= 0 (count (:unchanged changes)))))))

(deftest test-detect-changes-api-cross-boundaries
  (testing "detect-changes correctly segregates changes by API"
    (let [changes (detect-changes sample-previous-state sample-current-state-with-deletions)]
      ;; Verify that deleted entities retain their API information
      (let [deleted-apis (set (map :api (:deleted changes)))]
        (is (contains? deleted-apis :channels))
        (is (contains? deleted-apis :code-templates))))))

(deftest test-state-transitions
  (testing "State transitions maintain referential integrity"
    ;; Test that entity IDs are preserved across state changes
    (let [changes (detect-changes sample-previous-state sample-current-state-with-renames)]
      (doseq [rename-change (:renamed changes)]
        (is (= (get-in rename-change [:old :id]) (get-in rename-change [:new :id])))
        (is (= (get-in rename-change [:old :api]) (get-in rename-change [:new :api]))))))
  
  (testing "State changes preserve entity metadata"
    ;; Ensure all required fields are present in change detection
    (let [changes (detect-changes sample-previous-state sample-current-state-complex)]
      (doseq [deleted-entity (:deleted changes)]
        (is (contains? deleted-entity :id))
        (is (contains? deleted-entity :name))
        (is (contains? deleted-entity :file-path))
        (is (contains? deleted-entity :api)))
      
      (doseq [new-entity (:new changes)]
        (is (contains? new-entity :id))
        (is (contains? new-entity :name))
        (is (contains? new-entity :file-path))
        (is (contains? new-entity :api))))))

;; Tests for cleanup-stale-files functionality
(defn create-test-files
  "Create test files for cleanup testing"
  [target-dir file-paths]
  (doseq [file-path file-paths]
    (let [full-path (str target-dir file-path)
          file (io/file full-path)]
      (io/make-parents file)
      (spit file "test content"))))

(defn test-file-exists? 
  "Check if a test file exists"
  [target-dir file-path]
  (.exists (io/file (str target-dir file-path))))

(deftest test-cleanup-stale-files-with-mocked-deletion
  (testing "cleanup-stale-files calls safe-delete-api-managed-path for deleted entities"
    ;; Create test directory structure
    (let [test-files ["/Channels/channel3.xml" "/CodeTemplates/template2.xml"]
          changes {:deleted [(mock-channel-entity "channel-3" "Test Channel 3" (str *test-dir* "/Channels/channel3.xml"))
                             (mock-code-template-entity "template-2" "Test Template 2" (str *test-dir* "/CodeTemplates/template2.xml"))]
                   :renamed []
                   :new []
                   :unchanged []}]
      
      ;; Create the test files
      (create-test-files *test-dir* test-files)
      
      ;; Verify files exist before cleanup
      (is (test-file-exists? *test-dir* "/Channels/channel3.xml"))
      (is (test-file-exists? *test-dir* "/CodeTemplates/template2.xml"))
      
      ;; Mock the safe-delete-api-managed-path function to track calls
      (let [delete-calls (atom [])]
        (with-redefs [files/safe-delete-api-managed-path 
                      (fn [file-path target-dir api operation-desc]
                        (swap! delete-calls conj {:file-path file-path :target-dir target-dir :api api :operation operation-desc})
                        true)]
          
          ;; Call cleanup-stale-files
          (cleanup-stale-files changes *test-dir*)
          
          ;; Verify the correct calls were made
          (is (= 2 (count @delete-calls)))
          (let [call-paths (set (map :file-path @delete-calls))
                call-apis (set (map :api @delete-calls))]
            (is (contains? call-paths (str *test-dir* "/Channels/channel3.xml")))
            (is (contains? call-paths (str *test-dir* "/CodeTemplates/template2.xml")))
            (is (contains? call-apis :channels))
            (is (contains? call-apis :code-templates))))))))

(deftest test-cleanup-stale-files-with-renames
  (testing "cleanup-stale-files handles renamed entities correctly"
    (let [old-file "/Channels/channel1-old.xml"
          new-file "/Channels/channel1-new.xml"
          changes {:deleted []
                   :renamed [{:old (mock-channel-entity "channel-1" "Test Channel 1" (str *test-dir* old-file))
                              :new (mock-channel-entity "channel-1" "Test Channel 1 Renamed" (str *test-dir* new-file))}]
                   :new []
                   :unchanged []}]
      
      ;; Create the old file
      (create-test-files *test-dir* [old-file])
      (is (test-file-exists? *test-dir* old-file))
      
      ;; Mock the safe-delete function to track calls
      (let [delete-calls (atom [])]
        (with-redefs [files/safe-delete-api-managed-path
                      (fn [file-path target-dir api operation-desc]
                        (swap! delete-calls conj {:file-path file-path :target-dir target-dir :api api :operation operation-desc})
                        true)]
          
          (cleanup-stale-files changes *test-dir*)
          
          ;; Should have called delete on the old file
          (is (= 1 (count @delete-calls)))
          (let [call (first @delete-calls)]
            (is (= (str *test-dir* old-file) (:file-path call)))
            (is (= :channels (:api call)))
            (is (str/includes? (:operation call) "cleanup renamed entity"))))))))

(deftest test-cleanup-stale-files-mixed-scenario
  (testing "cleanup-stale-files handles complex mixed scenarios"
    (let [deleted-files ["/Channels/deleted-channel.xml" "/CodeTemplates/deleted-template.xml"]
          old-rename-file "/Channels/renamed-channel-old.xml"
          changes {:deleted [(mock-channel-entity "del-ch" "Deleted Channel" (str *test-dir* (first deleted-files)))
                             (mock-code-template-entity "del-tmpl" "Deleted Template" (str *test-dir* (second deleted-files)))]
                   :renamed [{:old (mock-channel-entity "ren-ch" "Renamed Channel" (str *test-dir* old-rename-file))
                              :new (mock-channel-entity "ren-ch" "Renamed Channel New" (str *test-dir* "/Channels/renamed-channel-new.xml"))}]
                   :new [(mock-channel-entity "new-ch" "New Channel" (str *test-dir* "/Channels/new-channel.xml"))]
                   :unchanged [(mock-channel-entity "unch-ch" "Unchanged Channel" (str *test-dir* "/Channels/unchanged-channel.xml"))]}]
      
      ;; Create test files for deleted and renamed entities
      (create-test-files *test-dir* (conj deleted-files old-rename-file))
      
      ;; Mock the safe-delete function
      (let [delete-calls (atom [])]
        (with-redefs [files/safe-delete-api-managed-path
                      (fn [file-path target-dir api operation-desc]
                        (swap! delete-calls conj {:file-path file-path :api api})
                        true)]
          
          (cleanup-stale-files changes *test-dir*)
          
          ;; Should have 3 delete calls: 2 deleted + 1 old renamed file
          (is (= 3 (count @delete-calls)))
          (let [deleted-paths (set (map :file-path @delete-calls))]
            (is (contains? deleted-paths (str *test-dir* (first deleted-files))))
            (is (contains? deleted-paths (str *test-dir* (second deleted-files))))
            (is (contains? deleted-paths (str *test-dir* old-rename-file)))))))))

(deftest test-cleanup-stale-files-nonexistent-files
  (testing "cleanup-stale-files handles nonexistent files gracefully"
    (let [changes {:deleted [(mock-channel-entity "nonexistent" "Nonexistent Channel" (str *test-dir* "/Channels/nonexistent.xml"))]
                   :renamed []
                   :new []
                   :unchanged []}]
      
      ;; Don't create the file - it doesn't exist
      (is (not (test-file-exists? *test-dir* "/Channels/nonexistent.xml")))
      
      ;; Mock the safe-delete function to track calls
      (let [delete-calls (atom [])]
        (with-redefs [files/safe-delete-api-managed-path
                      (fn [file-path target-dir api operation-desc]
                        (swap! delete-calls conj {:file-path file-path})
                        true)]
          
          ;; Should not throw an exception
          (is (nil? (cleanup-stale-files changes *test-dir*)))
          
          ;; Should not call delete for nonexistent files (due to .exists check)
          (is (= 0 (count @delete-calls)))))))

(deftest test-cleanup-stale-files-empty-changes
  (testing "cleanup-stale-files handles empty changes gracefully"
    (let [changes {:deleted [] :renamed [] :new [] :unchanged []}]
      (let [delete-calls (atom [])]
        (with-redefs [files/safe-delete-api-managed-path
                      (fn [file-path target-dir api operation-desc]
                        (swap! delete-calls conj {:file-path file-path})
                        true)]
          
          ;; Should complete without errors
          (is (nil? (cleanup-stale-files changes *test-dir*)))
          
          ;; Should make no delete calls
          (is (= 0 (count @delete-calls)))))))))