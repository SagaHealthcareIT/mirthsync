(ns mirthsync.file-safety-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [mirthsync.files :as files]
            [mirthsync.apis :as apis])  ; Load APIs to register multimethods
  (:import java.io.File))

(defn create-temp-test-structure []
  "Create a temporary directory structure for testing"
  (let [temp-dir (str "/tmp/mirthsync-safety-test-" (System/currentTimeMillis))
        _ (.mkdirs (io/file temp-dir))
        channels-dir (str temp-dir "/Channels")
        _ (.mkdirs (io/file channels-dir))
        code-templates-dir (str temp-dir "/CodeTemplates") 
        _ (.mkdirs (io/file code-templates-dir))
        user-dir (str temp-dir "/UserFiles")
        _ (.mkdirs (io/file user-dir))]
    
    ;; Create test files
    (spit (str temp-dir "/ConfigurationMap.xml") "<config/>")
    (spit (str channels-dir "/Test-Channel.xml") "<channel/>")
    (spit (str code-templates-dir "/Test-Template.xml") "<template/>")
    (spit (str user-dir "/user-file.txt") "user content")
    (spit (str temp-dir "/user-root-file.txt") "user root content")
    (spit (str temp-dir "/../outside-file.txt") "DANGEROUS - outside target")
    
    temp-dir))

(deftest test-path-validation
  (testing "Path validation prevents access outside target directory"
    (let [temp-dir (create-temp-test-structure)]
      
      ;; Should allow deletion of mirthsync files within target
      (is (files/safe-delete-file 
           (str temp-dir "/ConfigurationMap.xml") 
           temp-dir 
           "test deletion"))
      
      ;; Should block deletion of files outside target
      (is (not (files/safe-delete-file 
                (str temp-dir "/../outside-file.txt")
                temp-dir
                "dangerous deletion attempt")))
      
      ;; Should block deletion of user files
      (is (not (files/safe-delete-file 
                (str temp-dir "/user-root-file.txt")
                temp-dir
                "user file deletion attempt")))
      
      ;; Cleanup
      (files/safe-delete-directory-contents temp-dir temp-dir "test cleanup"))))

(deftest test-file-type-validation
  (testing "File type validation only allows mirthsync files"
    (let [temp-dir (create-temp-test-structure)]
      
      ;; Should allow .xml files in known directories
      (is (files/safe-delete-file 
           (str temp-dir "/Channels/Test-Channel.xml")
           temp-dir
           "channel file deletion"))
      
      ;; Should block user .txt files
      (is (not (files/safe-delete-file 
                (str temp-dir "/UserFiles/user-file.txt")
                temp-dir
                "user txt file deletion")))
      
      ;; Cleanup  
      (files/safe-delete-directory-contents temp-dir temp-dir "test cleanup"))))

(deftest test-dry-run-listing
  (testing "Dry run listing shows what would be deleted"
    (let [temp-dir (create-temp-test-structure)
          files-to-delete (files/list-files-that-would-be-deleted 
                          (str temp-dir "/Channels") 
                          temp-dir 
                          "dry run test")]
      
      ;; Should find the channel XML file
      (is (some #(re-find #"Test-Channel\.xml" %) files-to-delete))
      
      ;; Should not include user files
      (is (not (some #(re-find #"user-file\.txt" %) files-to-delete)))
      
      ;; Cleanup
      (files/safe-delete-directory-contents temp-dir temp-dir "test cleanup"))))

(deftest test-directory-scoped-deletion
  (testing "Directory-scoped deletion works within mirthsync directories"
    (let [temp-dir (create-temp-test-structure)]
      
      ;; Should allow deletion of mirthsync directories
      (is (files/safe-delete-directory-contents
           (str temp-dir "/Channels")
           temp-dir
           "Channels directory deletion"))
      
      ;; Should block deletion of user directories
      (is (not (files/safe-delete-directory-contents
                (str temp-dir "/UserFiles")
                temp-dir
                "User directory deletion attempt")))
      
      ;; Cleanup
      (files/safe-delete-directory-contents temp-dir temp-dir "test cleanup"))))