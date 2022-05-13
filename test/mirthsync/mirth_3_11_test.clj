(ns mirthsync.mirth-3-11-test
  (:require [clojure.test :refer :all]
            [mirthsync.core :refer :all]
            [mirthsync.fixture-tools :refer :all]
            [mirthsync.common-tests :refer :all]))

(use-fixtures :once mirth-3-11-fixture)

(def baseline-dir "dev-resources/mirth-3-11-baseline")
(def repo-dir "target/tmp-3-11")

(deftest integration-with-3-11
  (test-integration repo-dir baseline-dir))
