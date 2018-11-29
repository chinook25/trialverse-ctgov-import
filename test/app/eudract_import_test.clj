(ns app.eudract-import-test
  (:require [riveted.core :as vtd]
            [org.drugis.addis.rdf.trig :as trig])
  (:use clojure.test)
  (:use app.eudract-import))

(def xml (vtd/navigator (slurp "test/app/eudract.xml")))
(def hba1c-change-xml (first (vtd/search xml "/result/endPoints/endPoint")))
(def hba1c-below-7-percent (nth (vtd/search xml "/result/endPoints/endPoint") 
                           6))
(defn same-ignoring-order? [coll1 coll2]
  (= (set coll1)
     (set coll2)))

(defn outcomes-one-through-x [x]
  (map #(vector :outcome %) (range 1 (+ x 1))))

(deftest test-find-eudract-id
  (is (= "2013-004502-26"
         (get-eudract-number xml))))

(deftest test-find-nct-id
  (is (= "NCT02305381"
         (get-nct-id xml))))

(deftest test-build-registration
  (let [eudract-id "2013-004502-26"
        reg-uri (trig/iri :ictrp eudract-id)
        built-registration (build-registration reg-uri eudract-id)]
    (is (= [reg-uri '([[:qname :ontology "registry"]
                       [:uri "http://trials.drugis.org/registries#EudraCT"]]
                      [[:qname :ontology "registration_id"]
                       [:lit "2013-004502-26"]]
                      [[:qname :bibo "uri"]
                       [:lit "https://www.clinicaltrialsregister.eu/ctr-search/trial/2013-004502-26/results"]])]
           built-registration))))

(deftest test-find-measurement-moments
  (let [[found-mm-uris found-mm-info] (find-measurement-moments xml)]
    (is (same-ignoring-order?
         (concat (outcomes-one-through-x 8) '([:baseline] [:events]))
         (keys found-mm-uris)))
    (is (=
         '("From baseline to week 30"
           "Baseline"
           "From the first dose of trial product until the end of the post-treatment follow-up period.The follow-up visit was scheduled to take place 5 weeks after the date of last dose of trial product with a visit window of +7 days (maximum 36 weeks)."
           "After 30 weeks treatment"
           "After 30 weeks of treatment")
         (vals found-mm-info)))))

(deftest test-outcome-xml-findable 
  (is (= 8
         (count (vtd/search xml "/result/endPoints/endPoint")))))

(deftest test-build-outcome-uris
  (let [outcome-uris (build-outcome-uris (vtd/search xml "/result/endPoints/endPoint"))]
    (is (= 8 (count outcome-uris))
        (is (same-ignoring-order? 
             (outcomes-one-through-x 8)
             (keys outcome-uris))))))

(deftest test-outcome-results-properties-continuous
  (let [props {:simple     true
               :is-count?  false
               :categories ()
               :param      "MEASURE_TYPE.leastSquares"
               :dispersion "ENDPOINT_DISPERSION.standardError"
               :units      "percentage of glycosylated hemoglobin"}
        found-results-properties (outcome-results-properties props)]
    (is (= '(["least_squares_mean" "value"] ["standard_error" "spread"])
           found-results-properties))))

(deftest test-outcome-results-properties-dichotomous
  (let [props {:simple     true
               :is-count?  false
               :categories ()
               :param      "MEASURE_TYPE.number"
               :dispersion "ENDPOINT_DISPERSION.na"
               :units      "percentage of subjects"}
        found-results-properties (outcome-results-properties props)]
    (is (= '(["percentage" "value"])
           found-results-properties))))

; prereq: outcome-measurement-properties
; prereq: outcome-results-properties
(deftest test-outcome-rdf-least-squares
  (let [outcome-uris        {[:outcome 1] [:qname :instance "outcome-uri"]}
        mm-uris             {[:outcome 1] [:qname :instance "mm-uri"]}
        generated-rdf       (outcome-rdf hba1c-change-xml 1 outcome-uris mm-uris)
        expected-properties '([[:qname :rdf "type"] [:qname :ontology "Endpoint"]]
                              [[:qname :rdfs "label"] [:lit "Change in HbA1c"]] 
                              [[:qname :rdfs "comment"] [:lit "Estimated mean change from baseline in HbA1c at week 30. The post-baseline responses are analysed using a mixed model for repeated measurements with treatment, country and stratification variable (HbA1c level at screening [<= 8.0% or > 8.0%] crossed with use of metformin [yes or no]; 2 by 2 levels) as fixed factors and baseline value as covariate, all nested within visit. Mean estimates are adjusted according to observed baseline distribution. Missing data was imputed using mixed model for repeated measurements. Analysis was performed on full analysis set which included all randomised subjects who had received at least 1 dose of randomised semaglutide or placebo."]] 
                              [[:qname :ontology "is_measured_at"] [:qname :instance "mm-uri"]] 
                              [[:qname :ontology "has_result_property"] [:qname :ontology "sample_size"]]
                              [[:qname :ontology "of_variable"]
                               [:blank ([[:qname :ontology "measurementType"]
                                         [:qname :ontology "continuous"]])]]
                              [[:qname :ontology "has_result_property"] [:qname :ontology "least_squares_mean"]] 
                              [[:qname :ontology "has_result_property"] [:qname :ontology "standard_error"]])]
       (is (every? true? (map = expected-properties (second generated-rdf))))))

(deftest test-outcome-rdf-number
  (let [outcome-uris        {[:outcome 1] [:qname :instance "outcome-uri"]}
        mm-uris             {[:outcome 1] [:qname :instance "mm-uri"]}
        generated-rdf       (outcome-rdf hba1c-below-7-percent 1 outcome-uris mm-uris)
        expected-properties '([[:qname :rdf "type"] [:qname :ontology "Endpoint"]]
                              [[:qname :rdfs "label"]  [:lit "HbA1c below 7.0% (53 mmol/mol) American Diabetes Association (ADA) target"]]
                              [[:qname :rdfs "comment"] [:lit "Percentage of subjects with HbA1C below 7.0% after 30 weeks treatment. Missing data imputedfrom a mixed model for repeated measurements with treatment, country and stratification variable (HbA1c level at screening [<= 8.0% or > 8.0%] crossed with use of metformin [yes or no]; 2 by 2 levels) as fixed factors and baseline value as covariate, all nested within visit. Analysis was performed on full analysis set."]]
                              [[:qname :ontology "is_measured_at"] [:qname :instance "mm-uri"]]
                              [[:qname :ontology "has_result_property"] [:qname :ontology "sample_size"]]
                              [[:qname :ontology "of_variable"]
                               [:blank ([[:qname :ontology "measurementType"]
                                         [:qname :ontology "dichotomous"]])]]
                              [[:qname :ontology "has_result_property"] [:qname :ontology "percentage"]])]
    (doall (map println (second generated-rdf)))
    (is (every? true? (map = expected-properties (second generated-rdf))))))
