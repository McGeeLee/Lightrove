package com.mcgeelee.photos.data

import kotlin.math.sqrt

/** Pure numerical helpers. Invalid/mismatched vectors never produce a false match. */
object VectorMath {
    fun normalize(vector: FloatArray): FloatArray? {
        if (vector.isEmpty() || vector.any { !it.isFinite() }) return null
        val squaredNorm = vector.fold(0.0) { sum, value -> sum + value.toDouble() * value }
        if (squaredNorm <= 0.0 || !squaredNorm.isFinite()) return null
        val norm = sqrt(squaredNorm)
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    fun cosine(left: FloatArray, right: FloatArray): Float? {
        if (left.isEmpty() || left.size != right.size) return null
        var dot = 0.0
        var leftSquared = 0.0
        var rightSquared = 0.0
        for (i in left.indices) {
            val a = left[i].toDouble()
            val b = right[i].toDouble()
            if (!a.isFinite() || !b.isFinite()) return null
            dot += a * b
            leftSquared += a * a
            rightSquared += b * b
        }
        if (leftSquared == 0.0 || rightSquared == 0.0) return null
        return (dot / (sqrt(leftSquared) * sqrt(rightSquared))).coerceIn(-1.0, 1.0).toFloat()
    }
}
