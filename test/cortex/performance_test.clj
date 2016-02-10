(ns cortex.performance-test
  (:require [clojure.core.matrix :as m]
            [cortex.impl.layers :as impl]
            [cortex.core :as core]
            [clojure.test :refer [deftest is are]]
            [clojure.pprint]))


;;The point of this file is to highlight a couple operations in detail that
;;are currently causing perf issues and to investigate solutions to those issues.

(def implementations [:vectorz :clatrix])



(def image-dims [20 20 40 80 160 320])
(def kernel-count 20)
(def channel-counts [1 3 20 50])
(def pad-amounts [0 1 2])

(defn create-conv-layer-config
  [image-dim channel-count pad-amount]
  (impl/create-conv-layer-config
   image-dim image-dim 5 5 pad-amount pad-amount 1 1 channel-count))

(defn create-large-convolution-matrix-and-weights
  [implementation image-dim channel-count pad-amount]
  (let [conv-config (create-conv-layer-config image-dim channel-count pad-amount)
        kernel-size (* 5 5 channel-count)
        left-hand-side (m/array implementation (first (impl/get-gradient-convolution-sequence
                                                       conv-config)))
        right-hand-side (m/array implementation (map #(repeat kernel-size %) (range 1 (+ kernel-count 1))))]
    [left-hand-side right-hand-side]))

(def iter-count 10)

(defn time-matrix-multiply
  [implementation image-dim channel-count]
  (let [[left-hand-side right-hand-side]
        (create-large-convolution-matrix-and-weights implementation
                                                     image-dim channel-count
                                                     0)]
    (print implementation)
    (time
     (dotimes [iter iter-count]
       (m/mmul left-hand-side (m/transpose right-hand-side))))))

(defn clatrix-in-place-mul
  [image-dim channel-count]
  (let [[left-hand-side right-hand-side] (create-large-convolution-matrix-and-weights :clatrix
                                                                                      image-dim channel-count 0)
        left-shape (m/shape left-hand-side)
        right-shape (m/shape right-hand-side)
        ;;because the transpose, the right shape is the
        result (m/array :clatrix [(second left-shape) (second right-shape)])]
    (print "in-place clatrix")
    (time
     (dotimes [iter iter-count]
       (let [left-m (.me left-hand-side)
             right-m (.me (m/transpose right-hand-side))
             result-m (.me result)]
         (.mmuli left-m right-m result-m))))))


;; (deftest matrix-multiply-test
;;   (doseq [image-dim image-dims
;;           channel-count channel-counts]
;;     (println "### image-dim" image-dim " channel-count" channel-count)
;;     (doseq [impl implementations]
;;       (time-matrix-multiply impl image-dim channel-count))
;;     (clatrix-in-place-mul image-dim channel-count)
;;     (println "###")))


;; (deftest roll-unroll-test
;;   (doseq [image-dim image-dims
;;           channel-count channel-counts
;;           pad-amount pad-amounts]
;;     (let [[lhs _] (create-large-convolution-matrix-and-weights :vectorz image-dim channel-count pad-amount)
;;           input-mat (m/array :vectorz (repeat (* image-dim image-dim channel-count) 1.0))
;;           conv-config (create-conv-layer-config image-dim channel-count pad-amount)
;;           [conv-rows input-view padded-backing-matrix] (impl/get-gradient-convolution-sequence conv-config)
;;           input-view-vec (m/as-vector input-view)]
;;       (println "###Image dim: " image-dim "channel count:" channel-count "pad-amount" pad-amount)
;;       (print "in-place unroll time: ")
;;       (time (dotimes [iter (/ iter-count 10)]
;;               (impl/input-vector-to-convolution! input-mat lhs conv-config)))
;;       (print "copying unroll time: ")
;;       (time (dotimes [iter (/ iter-count 10)]
;;               (doall (map m/assign! lhs (impl/create-convolution-rows input-mat conv-config)))))
;;       (print "copying view unroll time: ")
;;       (time (dotimes [iter (/ iter-count 10)]
;;               (impl/sub-vector-assignment! pad-amount pad-amount image-dim
;;                                            channel-count padded-backing-matrix input-mat)
;;               (doall (map m/assign! lhs conv-rows)))))))



;; (deftest assign-to-view-test
;;   (doseq [image-dim (take 5 image-dims)
;;           channel-count channel-counts
;;           pad-amount pad-amounts]
;;     (println "###Image dim: " image-dim "channel count:" channel-count "pad-amount" pad-amount)
;;     (let [input-vector (m/array :vectorz (repeat (* image-dim image-dim channel-count) 1.0))
;;           ;;force vectorz to produce a dense matrix...
;;           padded-backing-matrix (m/array :vectorz (repeat (+ image-dim (* 2 pad-amount))
;;                                                           (repeat (* (+ image-dim (* 2 pad-amount)) channel-count)
;;                                                                   0.0)))
;;           input-mat-view (m/submatrix padded-backing-matrix
;;                                       [[pad-amount image-dim]
;;                                        [(* pad-amount channel-count) (* image-dim channel-count)]])
;;           row-stride (* image-dim channel-count)
;;           backing-row-stride (* (+ image-dim (* 2 pad-amount)) channel-count)
;;           backing-left-offset (* pad-amount channel-count)
;;           backing-top-offset (* pad-amount backing-row-stride)
;;           backing-vector (m/array :vectorz (m/as-vector padded-backing-matrix))
;;           input-submat (m/submatrix input-vector [[0 image-dim] [0 row-stride]])
;;           backing-submat (m/submatrix backing-vector [[pad-amount image-dim]
;;                                                       [(* pad-amount channel-count) row-stride]])]
;;       (print "assign to view: ")
;;       (time (dotimes [iter iter-count]
;;               (m/assign! input-mat-view (m/reshape input-vector [image-dim row-stride]))))

;;       (print "subvector assign: ")
;;       (time (dotimes [iter iter-count]
;;               (impl/sub-vector-assignment! pad-amount pad-amount
;;                                            image-dim channel-count padded-backing-matrix input-vector)))

;;       (print "as-vector assign: ")
;;       (time (dotimes [iter iter-count]
;;               (m/assign! (m/as-vector input-mat-view) input-vector))))))



;; (deftest conv-backward-test
;;   (doseq [image-dim (take 4 image-dims)
;;           channel-count channel-counts
;;           pad-amount pad-amounts]
;;     (let [[lhs weights] (create-large-convolution-matrix-and-weights :vectorz image-dim channel-count pad-amount)
;;           conv-config (create-conv-layer-config image-dim channel-count pad-amount)
;;           bias-gradient (m/zero-array :vectorz [kernel-count])
;;           weight-gradient (m/zero-array :vectorz (m/shape weights))
;;           output-gradient (m/mutable :vectorz (m/zero-array :vectorz [(* kernel-count (m/row-count lhs))]))
;;           [copy-mat final-view padded-backing-matrix] (impl/get-gradient-convolution-sequence conv-config)
;;           copy-mat-rows (into [] copy-mat)]

;;       (println "###Image dim: " image-dim "channel count:" channel-count "pad-amount" pad-amount)
;;       (print "w/ roll: ")
;;       (let [input-gradient (m/mutable :vectorz (m/clone lhs))]
;;         (time (dotimes [iter iter-count]
;;                 (m/mset! input-gradient 0.0)
;;                 (impl/convolution-backward! output-gradient lhs weights weight-gradient bias-gradient
;;                                             input-gradient conv-config)
;;                 (impl/convolution-copying-roll input-gradient copy-mat final-view)))

;;         ;;If we write directly to the vectorz views created from the padded matrix then we don't need to do
;;         (print "no roll: ")
;;         (let [final-input-gradient (m/array :vectorz (repeat (* image-dim image-dim channel-count) 0.0))]
;;           (println (type final-input-gradient))
;;          (time (dotimes [iter (/ iter-count 10)]
;;                  ;;The backing matrix acts as an accumulator so it needs to be cleared
;;                  (m/mset! padded-backing-matrix 0.0)
;;                  (impl/convolution-backward! output-gradient lhs weights weight-gradient bias-gradient
;;                                              copy-mat-rows conv-config)
;;                  (m/assign! final-input-gradient (m/as-vector final-view)))))))))


;;assign to existing vector:
;; ###Image dim:  320 channel count: 20 pad-amount 0
;; w/ roll: "Elapsed time: 7491.494407 msecs"
;; no roll: mikera.vectorz.impl.SparseIndexedVector
;; "Elapsed time: 645.023467 msecs"