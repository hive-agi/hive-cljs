(ns demo.app
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]))

(rf/reg-event-db :init (fn [_ _] {:user nil :name "" :country "" :agree false :echo nil}))
(rf/reg-event-db :login (fn [db [_ name]] (assoc db :user name)))
(rf/reg-event-db :set-name (fn [db [_ v]] (assoc db :name v)))
(rf/reg-event-db :set-country (fn [db [_ v]] (assoc db :country v)))
(rf/reg-event-db :toggle-agree (fn [db [_ v]] (assoc db :agree v)))
(rf/reg-event-db :echo (fn [db _] (assoc db :echo (:name db))))

(rf/reg-sub :current-user (fn [db _] (:user db)))
(rf/reg-sub :name (fn [db _] (:name db)))
(rf/reg-sub :country (fn [db _] (:country db)))
(rf/reg-sub :agree (fn [db _] (:agree db)))
(rf/reg-sub :echo (fn [db _] (:echo db)))

(defonce root (atom nil))

(defn view []
  (let [user    @(rf/subscribe [:current-user])
        name    @(rf/subscribe [:name])
        country @(rf/subscribe [:country])
        agree   @(rf/subscribe [:agree])
        echo    @(rf/subscribe [:echo])]
    ;; Controlled inputs dispatch SYNCHRONOUSLY and the checkbox flushes the
    ;; render: React restores a controlled element to its last-rendered value
    ;; on the same tick as the event, while reagent re-renders on the next
    ;; frame — so the DOM flickers back, and Playwright's :check, which
    ;; verifies on that tick, errors with "did not change its state".
    [:div
     [:button {:id "go" :on-click #(rf/dispatch [:login "pedro"])} "log in"]
     (when user [:p {:id "hi"} (str "Hello, " user)])
     [:input {:id "name" :type "text" :value name
              :on-change #(rf/dispatch-sync [:set-name (.. % -target -value)])
              :on-key-down #(when (= "Enter" (.-key %)) (rf/dispatch-sync [:echo]))}]
     [:select {:id "country" :value country
               :on-change #(rf/dispatch-sync [:set-country (.. % -target -value)])}
      [:option {:value ""} "--"]
      [:option {:value "BR"} "Brazil"]
      [:option {:value "PT"} "Portugal"]]
     [:input {:id "agree" :type "checkbox" :checked agree
              :on-change #(do (rf/dispatch-sync [:toggle-agree (.. % -target -checked)]) (r/flush))}]
     (when echo [:p {:id "echo"} (str "echo: " echo)])]))

(defn init []
  (rf/dispatch-sync [:init])
  (when-not @root
    (reset! root (rdc/create-root (.getElementById js/document "app"))))
  (rdc/render @root [view]))
