(ns mirthsync.state-scenarios-simple-test
  (:require [mirthsync.state :as state]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set])
  (:import java.io.File))

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture
  "Creates a temporary directory for testing and cleans it up"
  [test-fn]
  (let [temp-dir (str "/tmp/mirthsync-scenarios-simple-test-" (System/currentTimeMillis))]
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

;; Mock entity builders for realistic scenarios
(defn mock-channel-entity
  "Create a realistic channel entity"
  [id name group file-path]
  {:id id
   :name name
   :group group
   :file-path file-path
   :api :channels
   :enabled true})

(defn mock-code-template-entity
  "Create a realistic code template entity"
  [id name library file-path]
  {:id id
   :name name
   :library library
   :file-path file-path
   :api :code-templates})

(deftest test-healthcare-channel-decommission-scenario
  (testing "Realistic healthcare scenario: pharmacy system decommission"
    ;; Baseline state
    (let [baseline-state {:channels
                         {"ch-001" (mock-channel-entity "ch-001" "ADT Processor" "Patient Data" (str *test-dir* "/Channels/Patient Data/ADT Processor.xml"))
                          "ch-002" (mock-channel-entity "ch-002" "Lab Results Handler" "Lab Integration" (str *test-dir* "/Channels/Lab Integration/Lab Results Handler.xml"))
                          "ch-003" (mock-channel-entity "ch-003" "Pharmacy Orders" "Pharmacy" (str *test-dir* "/Channels/Pharmacy/Pharmacy Orders.xml"))}
                         :code-templates
                         {"ct-001" (mock-code-template-entity "ct-001" "HL7 Parser Utilities" "Common Utils" (str *test-dir* "/CodeTemplates/Common Utils/HL7 Parser Utilities.xml"))
                          "ct-002" (mock-code-template-entity "ct-002" "Database Connectors" "Common Utils" (str *test-dir* "/CodeTemplates/Common Utils/Database Connectors.xml"))}}
          
          ;; State after channel deletion
          after-deletion-state {:channels
                               {"ch-001" (mock-channel-entity "ch-001" "ADT Processor" "Patient Data" (str *test-dir* "/Channels/Patient Data/ADT Processor.xml"))
                                "ch-002" (mock-channel-entity "ch-002" "Lab Results Handler" "Lab Integration" (str *test-dir* "/Channels/Lab Integration/Lab Results Handler.xml"))
                                ;; ch-003 deleted due to system decommission
                                }
                               :code-templates
                               {"ct-001" (mock-code-template-entity "ct-001" "HL7 Parser Utilities" "Common Utils" (str *test-dir* "/CodeTemplates/Common Utils/HL7 Parser Utilities.xml"))
                                "ct-002" (mock-code-template-entity "ct-002" "Database Connectors" "Common Utils" (str *test-dir* "/CodeTemplates/Common Utils/Database Connectors.xml"))}}
          
          changes (state/detect-changes baseline-state after-deletion-state)]
      
      ;; Should detect one deleted channel
      (is (= 1 (count (:deleted changes))))
      (is (= 0 (count (:renamed changes))))
      (is (= 0 (count (:new changes))))
      (is (= 4 (count (:unchanged changes)))) ; 2 channels + 2 templates
      
      ;; Verify the correct channel was deleted
      (let [deleted-channel (first (:deleted changes))]
        (is (= "ch-003" (:id deleted-channel)))
        (is (= "Pharmacy Orders" (:name deleted-channel)))
        (is (= :channels (:api deleted-channel)))))))

(deftest test-healthcare-expansion-scenario
  (testing "Realistic healthcare scenario: adding new integration capabilities"
    (let [baseline-state {:channels
                         {"ch-001" (mock-channel-entity "ch-001" "ADT Processor" "Patient Data" (str *test-dir* "/Channels/Patient Data/ADT Processor.xml"))}
                         :code-templates
                         {"ct-001" (mock-code-template-entity "ct-001" "HL7 Parser Utilities" "Common Utils" (str *test-dir* "/CodeTemplates/Common Utils/HL7 Parser Utilities.xml"))}}
          
          after-expansion-state {:channels
                                {"ch-001" (mock-channel-entity "ch-001" "ADT Processor" "Patient Data" (str *test-dir* "/Channels/Patient Data/ADT Processor.xml"))
                                 ;; New channel added
                                 "ch-006" (mock-channel-entity "ch-006" "Radiology Interface" "Imaging" (str *test-dir* "/Channels/Imaging/Radiology Interface.xml"))}
                                :code-templates
                                {"ct-001" (mock-code-template-entity "ct-001" "HL7 Parser Utilities" "Common Utils" (str *test-dir* "/CodeTemplates/Common Utils/HL7 Parser Utilities.xml"))
                                 ;; New template added
                                 "ct-005" (mock-code-template-entity "ct-005" "DICOM Utilities" "Imaging Utils" (str *test-dir* "/CodeTemplates/Imaging Utils/DICOM Utilities.xml"))}}
          
          changes (state/detect-changes baseline-state after-expansion-state)]
      
      ;; Should detect new entities, no deletions, no renames
      (is (= 0 (count (:deleted changes))))
      (is (= 0 (count (:renamed changes))))
      (is (= 2 (count (:new changes)))) ; 1 channel + 1 template
      (is (= 2 (count (:unchanged changes)))) ; Original entities unchanged
      
      ;; Verify new entities
      (let [new-ids (set (map :id (:new changes)))]
        (is (contains? new-ids "ch-006"))
        (is (contains? new-ids "ct-005"))))))