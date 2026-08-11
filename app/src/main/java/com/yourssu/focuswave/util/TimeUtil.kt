package com.yourssu.focuswave.util

object TimeUtil {
    fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60

        return when {
            h > 0 -> "${h}시간 ${m}분 ${s}초"
            m > 0 -> "${m}분 ${s}초"
            else -> "${s}초"
        }
    }

}