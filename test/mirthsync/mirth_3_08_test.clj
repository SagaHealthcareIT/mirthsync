(ns mirthsync.mirth-3-08-test
  (:require [clojure.test :refer :all]
            [mirthsync.core :refer :all]
            [mirthsync.fixture-tools :refer :all]
            [mirthsync.common-tests :refer :all]))

(use-fixtures :once mirth-8-fixture)

(def baseline-dir "dev-resources/mirth-8-baseline")
(def repo-dir "target/tmp-8")

(deftest integration-with-8
  (test-integration 8 repo-dir baseline-dir))
