(defproject com.grzm/component.pedestal "0.1.7"
  :description "Pedestal service wrapper for Component systems"
  :url "https://github.com/grzm/component.pedestal"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[com.stuartsierra/component "0.3.1"]
                 [org.clojure/clojure "1.7.0" :scope "provided"]
                 [io.pedestal/pedestal.service "0.5.1" :scope "provided"]
                 [ring/ring-mock "0.3.0"]])
