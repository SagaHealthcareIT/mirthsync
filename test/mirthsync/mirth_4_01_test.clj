(ns mirthsync.mirth-4-01-test
  (:require [clojure.test :refer :all]
            [mirthsync.core :refer :all]
            [mirthsync.fixture-tools :refer :all]
            [mirthsync.common-tests :refer :all]))

(use-fixtures :once mirth-4-01-fixture)

(def baseline-dir "dev-resources/mirth-4-01-baseline")
(def repo-dir "target/tmp-4-01")

(deftest integration-with-4-01
  (test-integration repo-dir baseline-dir))
