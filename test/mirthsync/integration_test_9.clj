(ns mirthsync.integration-test-9
  (:require [clojure.test :refer :all]
            [mirthsync.core :refer :all]
            [mirthsync.fixture-tools :refer :all]
            [mirthsync.common-tests :refer :all]))

(use-fixtures :once mirth-9-fixture)

(def baseline-dir "dev-resources/mirth-9-baseline")
(def repo-dir "target/tmp-9")

(deftest integration-with-9
  (test-integration 9 repo-dir baseline-dir))
