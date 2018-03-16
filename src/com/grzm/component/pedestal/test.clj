(ns com.grzm.component.pedestal.test
  (:require [com.stuartsierra.component :as component]
            [com.grzm.component.pedestal :as cp]
            [io.pedestal.http :as http]
            [io.pedestal.test :as test]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.log :as log]
            [clojure.edn :as edn]
            [ring.mock.request :as mock-request]))

(defn- init [system-var init-fn]
  (alter-var-root system-var (init-fn)))

(defn- start [system-var]
  (alter-var-root system-var component/start))

(defn go [system-var init-fn]
  (do (init system-var init-fn)
      (start system-var)))

(defn stop [system-var]
  (alter-var-root system-var component/stop))

(defmacro with-system
  "Wraps a test body and handles starting and stopping the system for the
   test. Wraps the test body in a try-finally block to ensure the system
   stops cleanly, isolating the test and allowing other tests to run."
  [system-var init-fn & body]
  (list 'do
        (list `go system-var init-fn)
        (list 'try (cons 'do body)
              (list 'finally (list `stop system-var)))))

(defn service
  "Returns the service function (:io.pedestal.http/service-fn) from a system.
   Accepts an optional second argument for the keyword used to identify the
   Pedestal component in the system. Otherwise assumes the keyword is :pedestal."
  ([system]
   (service system :pedestal))
  ([system pedestal-key]
   (get-in system [pedestal-key :server ::http/service-fn])))

;; https://github.com/thegeez/w3a/blob/master/src/net/thegeez/w3a/test.clj
;; make ring mock not munge edn
(let [old-fn (get (methods mock-request/body) java.util.Map)]
  (remove-method mock-request/body java.util.Map)
  (defmethod mock-request/body java.util.Map
    [request body]
    (if (-> body meta :edn)
      (mock-request/body request (pr-str body))
      (old-fn request body))))

;; Expose request CSRF tokens in the response header
(def csrf-token-in-response-header
  (interceptor
    {:name ::csrf-token-in-response-header
     :leave (fn [context]
              (assoc-in context [:response :headers "X-TEST-HELPER-CSRF"]
                        (or (get-in context [:request ::csrf/anti-forgery-token])
                            (get-in context [:request :session "__anti-forgery-token"]))))}))

(defn surface-csrf-token [system]
  (update-in system [::cp/pedestal :server ::http/interceptors]
             conj csrf-token-in-response-header))

(defn ring-handler
  "Create a Ring handler from the system for portability with Peridot.
   Accepts an optional second argument for the keyword used to identify the
   Pedestal component in the system. Otherwise assumes the keyword is :pedestal."
  ([system]
   (ring-handler system :pedestal))
  ([system pedestal-key]
   (let [service-fn (-> system
                        surface-csrf-token
                        (service pedestal-key))]
     (fn [{:keys [request-method uri headers body] :as request}]
       (let [options (cond-> []
                       body (into [:body (slurp body)])
                       ;; fix capitalization and filter headers
                       headers (into [:headers
                                      (zipmap
                                        (map #(get
                                                {"content-type" "Content-Type"
                                                 "accept" "Accept"} % %)
                                             (keys headers))
                                        (vals headers))]))
             response (apply test/response-for service-fn request-method uri options)]
         (cond-> response
           (.startsWith ^String (get-in response [:headers "Content-Type"] "")
                        "application/edn")
           (assoc :edn (try (edn/read-string (get response :body))
                            (catch Exception e
                              (log/error :unreadable (get response :body))
                              (throw e))))))))))
