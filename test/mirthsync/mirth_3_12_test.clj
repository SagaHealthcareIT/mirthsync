(ns mirthsync.mirth-3-12-test
  (:require [clojure.test :refer :all]
            [mirthsync.core :refer :all]
            [mirthsync.fixture-tools :refer :all]
            [mirthsync.common-tests :refer :all]))

(use-fixtures :once mirth-12-fixture)

(def baseline-dir "dev-resources/mirth-3-12-baseline")
(def repo-dir "target/tmp-12")

(deftest integration-with-12
  (test-integration "3-12" repo-dir baseline-dir))
