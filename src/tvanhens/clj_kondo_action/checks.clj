(ns tvanhens.clj-kondo-action.checks
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clj-http.client :as http]))

(s/fdef get-env
  :args (s/cat :name #{"GITHUB_REPOSITORY"
                       "GITHUB_WORKFLOW"
                       "GITHUB_SHA"})
  :ret string?)

(defn- get-env
  [name]
  (System/getenv name))

(defn build-request
  [req]
  (merge req
         {:headers      (merge (:headers req)
                               {:Accept        "application/vnd.github.antiope-preview+json"
                                :Authorization (format "token %s" (System/getenv "GITHUB_TOKEN"))})
          :as           :json
          :content-type :json}))

(defn- GET
  [url req]
  (http/get url (build-request req)))

(defn- PATCH
  [url req]
  (http/patch url (build-request req)))

(defn- repository
  []
  (get-env "GITHUB_REPOSITORY"))

(defn- check-name
  []
  (get-env "GITHUB_WORKFLOW"))

(defn- sha
  []
  (get-env "GITHUB_SHA"))

(s/def ::url
  (s/with-gen
    (s/and string?
           #(.startsWith % "https://"))
    #(sgen/fmap (fn [s] (str "https://" s)) (s/gen string?))))

(s/def :github.check-runs/total_count #{1})
(s/def :github.check-runs/check_runs
  (s/and (s/coll-of (s/keys :req-un [::url]))
         not-empty))

(s/def :github.check-runs.resp/status #{200})
(s/def :github.check-runs.resp/body
  (s/keys :req-un [:github.check-runs/total_count
                   :github.check-runs/check_runs]))

(s/fdef check-runs
  :args (s/cat :repository string?
               :sha string?
               :check-name string?)
  :ret (s/keys :req-un [:github.check-runs.resp/status
                        :github.check-runs.resp/body]))

(defn- check-runs
  [repository sha check-name]
  (GET (format "https://api.github.com/repos/%s/commits/%s/check-runs"
               repository
               sha)
       {:query-params {#_:check_name #_check-name}}))

(defn- current-run
  []
  (let [repository (repository)
        sha        (sha)
        check-name (check-name)
        runs       (check-runs repository sha check-name)]
    (if-let [run (and (= 200 (:status runs))
                      (= 1 (get-in runs [:body :total_count]))
                      (-> runs :body :check_runs first))]
      run
      (throw (ex-info "Unable to find check_run"
                      {:check-runs runs
                       :repository repository
                       :sha        sha
                       :check-name check-name})))))

(def ^:private finding-level->annotation-level
  {:warning "warning"
   :error   "failure"
   :info    "notice"})

(defn- format-annotation
  [finding]
  {:path             (:filename finding)
   :start_line       (:row finding)
   :end_line         (:row finding)
   :start_column     (:col finding)
   :end_column       (:col finding)
   :message          (:message finding)
   :title            (some-> finding (:type) (name))
   :annotation_level (some-> finding (:level) (finding-level->annotation-level))})

(defn- format-output
  [result]
  (let [{:keys [error warning info duration]} (:summary result)]
    {:title       "clj-kondo"
     :summary     (format "Finished in %s ms. There were %s errors %s warnings and %s notices."
                          duration error warning info)
     :annotations (map format-annotation (:findings result))}))

(s/def :github.check-run.annotation/path string?)
(s/def :github.check-run.annotation/title string?)
(s/def :github.check-run.annotation/annotation_level #{"notice" "warning" "failure"})
(s/def :github.check-run.annotation/message string?)
(s/def :github.check-run.annotation/start_line pos-int?)
(s/def :github.check-run.annotation/end_line pos-int?)
(s/def :github.check-run.annotation/start_column pos-int?)
(s/def :github.check-run.annotation/end_column pos-int?)

(s/def :github.check-run/title string?)
(s/def :github.check-run/summary
  (s/and string?
         #(not (.contains % "null"))))
(s/def :github.check-run/annotations
  (s/coll-of (s/keys :req-un [:github.check-run.annotation/path
                              :github.check-run.annotation/title
                              :github.check-run.annotation/message
                              :github.check-run.annotation/annotation_level
                              :github.check-run.annotation/start_line
                              :github.check-run.annotation/end_line
                              :github.check-run.annotation/start_column
                              :github.check-run.annotation/end_column])))

(s/fdef update-run
  :args (s/cat :run (s/keys :req-un [::url])
               :updated-run (s/keys :req-un [:github.check-run/title
                                             :github.check-run/summary
                                             :github.check-run/annotations]))
  :ret #{{:status 200}})

(defn- update-run
  [run updated-run]
  (PATCH (:url run) {:form-params {:output updated-run}}))

(s/def ::int?
  (s/with-gen int? #(sgen/choose 0 5)))

(s/def :clj-kondo.result.summary/error ::int?)
(s/def :clj-kondo.result.summary/info ::int?)
(s/def :clj-kondo.result.summary/warning ::int?)
(s/def :clj-kondo.result.summary/duration ::int?)

(s/def :clj-kondo.result/summary
  (s/keys :req-un [:clj-kondo.result.summary/error
                   :clj-kondo.result.summary/info
                   :clj-kondo.result.summary/warning
                   :clj-kondo.result.summary/duration]))

(s/def :clj-kondo.finding/filename string?)
(s/def :clj-kondo.finding/message string?)
(s/def :clj-kondo.finding/level #{:warning :info :error})
(s/def :clj-kondo.finding/type keyword?)
(s/def :clj-kondo.finding/row pos-int?)
(s/def :clj-kondo.finding/col pos-int?)
(s/def :clj-kondo.result/finding
  (s/keys :req-un [:clj-kondo.finding/filename
                   :clj-kondo.finding/message
                   :clj-kondo.finding/level
                   :clj-kondo.finding/type
                   :clj-kondo.finding/row
                   :clj-kondo.finding/col]))
(s/def :clj-kondo.result/findings
  (s/coll-of :clj-kondo.result/finding))
(s/def :clj-kondo/result
  (s/with-gen
    (s/and (s/keys :req-un [:clj-kondo.result/summary
                            :clj-kondo.result/findings])
           #(let [{:keys [error warning info]} (:summary %)]
              (= (+ error warning info)
                 (count (:findings %)))))
    #(sgen/bind (s/gen :clj-kondo.result/summary)
                (fn [{:keys [error info warning] :as summary}]
                  (sgen/hash-map
                    :summary (sgen/return summary)
                    :findings
                    (s/gen
                      (s/coll-of :clj-kondo.result/finding
                                 :count (+ error info warning))))))))

(s/fdef annotate-run
  :args (s/cat :result :clj-kondo/result))

(defn annotate-run
  [result]
  (let [run                       (current-run)
        {:keys [status] :as resp} (update-run run (format-output result))]
    (when-not (= 200 status)
      (throw (ex-info "Failed to update check run" {:response resp})))))
