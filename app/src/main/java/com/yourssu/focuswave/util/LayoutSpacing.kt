package com.yourssu.focuswave.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class LayoutSpacing(
    val padding: Dp,
    val panelHorizontalPadding: Dp,  // 패널
    val panelVerticalPadding: Dp,  // 패널
    val framePadding: Dp,  // 배경화면 프레임 패딩
    val buttonSpacing: Dp, // duration pill, launch 버튼
    val buttonHeight: Dp, // duration pill, launch 버튼
    val buttonPadding: Dp, // duration pill, launch 버튼
    val itemSpacing: Dp,
    val iconSize: Dp,  // 헤더 컴팩트 아이콘
    val iconSpacing: Dp,  // 헤더 컴팩트 아이콘
    val buttonWidthFraction: Float  // duration pill, launch 버튼 가로 화면 차지 비율

) {
    companion object {
        const val COMPACT_WIDTH_DP = 405
        const val COMPACT_HEIGHT_DP = 720
    }
}

fun layoutSpacing(isCompact: Boolean): LayoutSpacing =
    if (isCompact) {
        LayoutSpacing(
            padding = 12.dp,
            panelHorizontalPadding = 12.dp,
            panelVerticalPadding = 8.dp,
            framePadding = 14.dp,
            buttonSpacing = 18.dp,
            buttonHeight = 48.dp,
            buttonPadding = 8.dp,
            itemSpacing = 8.dp,
            iconSize = 16.dp,
            iconSpacing = 3.dp,
            buttonWidthFraction = 1f
        )
    } else {
        LayoutSpacing(
            padding = 16.dp,
            panelHorizontalPadding = 16.dp,
            panelVerticalPadding = 14.dp,
            framePadding = 20.dp,
            buttonSpacing = 54.dp,
            buttonHeight = 60.dp,
            buttonPadding = 10.dp,
            itemSpacing = 12.dp,
            iconSize = 22.dp,
            iconSpacing = 6.dp,
            buttonWidthFraction = 0.78f
        )
    }
