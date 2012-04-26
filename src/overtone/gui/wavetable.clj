(ns overtone.gui.wavetable
  (:use [overtone.sc server buffer]
        [overtone.config log]
        [overtone.music time]
        [overtone.studio wavetable]
        [seesaw core graphics color mig meta]
        [clojure stacktrace])
  (:require [seesaw.bind :as bind]))

(def ^{:private true} WAVE-Y-PADDING 10)

;; TODO: figure out a nicer way to pull out the wavetable conversions to make this more general
;; purpose.
(defn- paint-wavetable
  "Paint the dial widget group"
  [wavetable? buf c g]
  (try
    (let [w (width c)
          h (height c)
          line-style (style :foreground "#FFFFFF" :stroke 1.0 :cap :round :dashes [10.0 20.0])
          data (if wavetable?
                 (wavetable->signal (buffer-data buf))
                 (buffer-data buf))
                 x-array (int-array w)
          y-array (int-array w)
          step (/ (:size buf) w (if wavetable? 2 1))
          y-scale h] ;(- h (* 2 WAVE-Y-PADDING))]

      (draw g (line 0 (/ h 2) w (/ h 2)) line-style)

      (dotimes [i w]
        (aset ^ints x-array i i)
        (aset ^ints y-array i (int (* y-scale 0.5
                                (+ 1.0 (* -1 (nth data (unchecked-multiply i step))))))))

      (.setColor g (color 0 140 236))
      (.setStroke g (stroke :width 2.0))
      (.drawPolyline g x-array y-array w))
    (catch Exception ex
      (warning (str "Error in paint-wavetable: " ex (with-out-str (clojure.stacktrace/print-stack-trace ex)))))))

(defn waveform-panel
  "Creates a swing panel that displays the waveform in a buffer."
  [wavetable? buf]
  (let [display (canvas :id :waveform-canvas
                        :background "#000000"
                        :paint (partial paint-wavetable wavetable? buf))]
    (put-meta! display :buf buf)
    (border-panel :hgap 5 :vgap 5 :border 5
                  :center display
                  :minimum-size [64 :by 48])))

(defn- editor-drag-handler
  [wavetable? buf last-drag event]
  (try
    (let [source (.getComponent event)
          w (width source)
          h (height source)
          cx (.getX event)
          cy (.getY event)]
      (when (and (>= cx 0) (< cx w) (>= cy 0) (< cy h))
        (let [buf-size (if wavetable?
                         (/ (:size buf) 2)
                         (:size buf))
              step (/ buf-size (float w))
              [last-x last-y] @last-drag

              x1 (max 0 (min last-x cx))
              x2 cx
              x3 (min w (max last-x cx))

              x1-idx (Math/round (* x1 step))
              x2-idx (Math/round (* x2 step))
              x3-idx (Math/round (* x3 step))

              cy-val (* -1 (- (* 2 (* cy (/ 1.0 h))) 1))

              buf-data (if wavetable?
                         (wavetable->signal (buffer-data buf))
                         (buffer-data buf))
              [start-idx y-vals] (if (< x1 x2)
                                   [x1-idx (linear-interpolate (nth buf-data x1-idx) cy-val
                                                        (inc (- x2-idx x1-idx)))]
                                   [x2-idx [cy-val]])
              y-vals (if (> x3-idx x2-idx)
                       (concat y-vals (linear-interpolate cy-val (nth buf-data x3-idx)
                                                   (inc (- x3-idx x2-idx))))
                       y-vals)]
          (reset! last-drag [cx cy])
          (if wavetable?
            (buffer-write! buf (* 2 start-idx) (signal->wavetable y-vals))
            (buffer-write! buf start-idx y-vals))

          ; repaint from the root so thumbnails get redrawn too
          (.repaint (to-root source)))))
        (catch Exception ex
          (warning (str "Error in drag-handler:" ex))
          (.printStackTrace ex))))

(defn- editor-press-handler
  [last-drag event]
  (reset! last-drag [(.getX event) (.getY event)]))

(defn- add-waveform-editor-behaviors
  [buf root wavetable?]
  (let [last-drag (atom nil)]
    (listen (select root [:#waveform-canvas])
            :mouse-dragged (partial editor-drag-handler wavetable? buf last-drag)
            :mouse-pressed (partial editor-press-handler last-drag))))

(defn waveform-editor-panel
  [buf wavetable?]
  (let [wave-pane (waveform-panel wavetable? buf)]
    (add-waveform-editor-behaviors buf wave-pane wavetable?)
    wave-pane))

(defn waveform-editor
  ([buf] (waveform-editor buf false))
  ([buf wavetable?]
   (invoke-now
     (try
       (let [f (frame :title "Waveform Editor"
                      :content (waveform-editor-panel buf wavetable?)
                      :width 1024 :height 760
                      :minimum-size [320 :by 240])]
         (-> f pack! show!)
         (with-meta
           {:frame f :buf buf}
           {:type ::waveform-editor}))
       (catch Exception e
         (.printStackTrace e))))))

(defn- wavetable-thumbnailer
  [table]
  (let [panels (map (partial waveform-panel true) (:waveforms table))
        panels (map #(config! % :class :thumbnail) panels)
        panels (partition 2 (interleave panels (repeat "height 80, width 120")))]
    (scrollable
      (mig-panel :constraints ["gap 2px" "" ""]
                 :items panels)
      :vscroll :never
      :hscroll :as-needed)))

(defn- add-thumbnail-behavior
  [root update-fn]
  (let [thumbnails (select root [:.thumbnail])]
    (doseq [thumb thumbnails]
      (let [buf (get-meta (select thumb [:#waveform-canvas]) :buf)]
        (listen thumb :mouse-clicked (fn [_] (update-fn buf)))))))

(defn wavetable-editor
  ([table] (wavetable-editor table true))
  ([table wavetable?]
   (invoke-now
     (try
       (let [editor  (waveform-editor-panel (first (:waveforms table)) true)
             split (top-bottom-split
                     editor
                     (wavetable-thumbnailer table)
                     :divider-location 0.9)
             change-wave-fn (fn [buf]
                              (invoke-later
                                (try
                                  (let [bounds (.getBounds (select split [:#waveform-canvas]))
                                        loc (.getDividerLocation split)
                                        editor (waveform-editor-panel buf wavetable?)
                                        {:keys [x y width height]} (bean bounds)]
                                    (.setLeftComponent split editor)
                                    (.setBounds editor x y width height)
                                    (.setDividerLocation split loc))
                                  (catch Exception ex
                                    (error "Exception in wave change: " ex)))))
             f (frame :title "Wave Table Editor"
                      :content split
                      :width 1024 :height 760
                      :minimum-size [640 :by 480])]
         (add-thumbnail-behavior f change-wave-fn)
         (-> f pack! show!)
         (with-meta
           {:frame f :wavetable table}
           {:type ::wavetable-editor}))
       (catch Exception e
         (warning (str "Error creating wavetable-editor: " (with-out-str (print-stack-trace e)))))))))
