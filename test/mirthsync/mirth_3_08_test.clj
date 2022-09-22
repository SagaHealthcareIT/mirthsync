(ns mirthsync.mirth-3-08-test
  (:require [clojure.test :refer :all]
            [mirthsync.core :refer :all]
            [mirthsync.fixture-tools :refer :all]
            [mirthsync.common-tests :refer :all]))

(use-fixtures :once mirth-3-08-fixture)

(def version "3-08")
(def baseline-dir (str "target/test-data/mirth-" version "-baseline"))
(def repo-dir (str "target/tmp-" version))

(deftest integration-with-3-08
  (test-integration repo-dir baseline-dir version))
