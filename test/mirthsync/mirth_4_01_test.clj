(ns mirthsync.mirth-4-01-test
  (:require [clojure.test :refer :all]
            [mirthsync.core :refer :all]
            [mirthsync.fixture-tools :refer :all]
            [mirthsync.common-tests :refer :all]))

(use-fixtures :once mirth-4-01-fixture)

(def version "4-01")
(def baseline-dir (str "target/test-data/mirth-" version "-baseline"))
(def repo-dir (str "target/tmp-" version))

(deftest integration-with-4-01
  (test-integration repo-dir baseline-dir version))
