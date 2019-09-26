(ns clj-kondo-action.checks-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is use-fixtures testing]]
            [expound.alpha :as expound]
            [tvanhens.clj-kondo-action.checks :as checks]))

(use-fixtures
  :once
  (fn [f]
    (binding [s/*explain-out* expound/printer]
      (stest/instrument (stest/instrumentable-syms)
                        {:stub `[
                                 checks/create-run
                                 checks/get-env]})
      (f)
      (stest/unstrument))))


(defn pass?
  [result]
  (when-let [ex (->> result
                     (map :failure)
                     (first))]
    (throw ex))
  (some->> result
           (map :clojure.spec.test.check/ret)
           (every? :pass?)))

(deftest annotate-run-test
  (testing "annotate-run spec model is sane"
    (let [results (stest/check `checks/annotate-run
                               {:clojure.spec.test.check/opts
                                {:num-tests 5}})]
      (is (pass? results)))))
