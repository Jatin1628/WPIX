package com.surendramaran.yolov9tflite.feedback

import com.surendramaran.yolov9tflite.BoundingBox
import kotlin.math.hypot

class ObjectClassifier(
    private val motionThreshold: Float = 0.035f
) {
    private val defaultMovingClasses = setOf(
        "person", "car", "bike", "bicycle",
        "dog", "cat", "horse", "cow", "sheep", "bird"
    )

    private var previousCentersByClass: Map<String, MutableList<Pair<Float, Float>>> = emptyMap()

    data class Result(
        val moving: List<BoundingBox>,
        val stationary: List<BoundingBox>
    )

    fun classify(boxes: List<BoundingBox>): Result {
        if (boxes.isEmpty()) {
            previousCentersByClass = emptyMap()
            return Result(emptyList(), emptyList())
        }

        val previous = previousCentersByClass.mapValues { (_, v) -> v.toMutableList() }.toMutableMap()
        val moving = ArrayList<BoundingBox>(boxes.size)
        val stationary = ArrayList<BoundingBox>(boxes.size)
        val currentCentersByClass = mutableMapOf<String, MutableList<Pair<Float, Float>>>()

        for (box in boxes) {
            val cls = box.clsName.lowercase()
            val center = box.cx to box.cy
            currentCentersByClass.getOrPut(cls) { mutableListOf() }.add(center)

            val movedByFrameDelta = hasMovedFromPrevious(cls, center, previous[cls])
            val isMoving = movedByFrameDelta || defaultMovingClasses.contains(cls)

            if (isMoving) moving.add(box) else stationary.add(box)
        }

        previousCentersByClass = currentCentersByClass
        return Result(moving = moving, stationary = stationary)
    }

    private fun hasMovedFromPrevious(
        clsName: String,
        center: Pair<Float, Float>,
        previousCenters: MutableList<Pair<Float, Float>>?
    ): Boolean {
        if (previousCenters.isNullOrEmpty()) return false

        var bestIdx = -1
        var bestDistance = Float.MAX_VALUE
        for (i in previousCenters.indices) {
            val (px, py) = previousCenters[i]
            val d = hypot(center.first - px, center.second - py)
            if (d < bestDistance) {
                bestDistance = d
                bestIdx = i
            }
        }

        if (bestIdx >= 0) {
            // Remove matched center so each previous box maps to one current box.
            previousCenters.removeAt(bestIdx)
        }

        return bestDistance > motionThreshold
    }
}

