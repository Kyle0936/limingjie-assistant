package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/**
 * 装配层共享的最新识别快照：workflow 的帧回调写入，SessionExpiry 终止器的
 * 弹窗/标题检测与坐标映射读取。跨对象共享识别结果，避免重复注册截图帧。
 */
class SessionExpiryFrameTracker {
    @Volatile
    private var scores: Map<String, Double> = emptyMap()
    @Volatile
    private var matches: Map<String, EntryAnchorMatch> = emptyMap()
    @Volatile
    private var pageState: LabyrinthEntryPageState = LabyrinthEntryPageState.UNKNOWN
    @Volatile
    private var width: Int = 0
    @Volatile
    private var height: Int = 0

    /** 由 workflow 的帧处理器在每次识别后调用 */
    fun record(result: LabyrinthEntryFrameResult) {
        scores = result.observation.anchorScores.values
        matches = result.anchorMatches
        pageState = result.observation.state
    }

    /** 记录实际帧尺寸，供坐标映射与支持性判断 */
    fun trackFrame(w: Int, h: Int) {
        width = w
        height = h
    }

    val frameSize: Pair<Int, Int> get() = width to height

    /** 「错误提示」弹窗出现：标题条锚点达到置信度门槛 */
    val popupVisible: Boolean
        get() = (scores[EntryAnchorId.SESSION_ERROR_TITLE] ?: 0.0) >= POPUP_MIN_SCORE

    /** 已回到标题页（点击屏幕开始游戏） */
    val titleReached: Boolean
        get() = pageState == LabyrinthEntryPageState.TITLE_WAITING_TAP

    fun anchorCenter(anchorId: String, minScore: Double = ANCHOR_ACTION_MIN_SCORE): ScreenPoint? =
        matches[anchorId]
            ?.takeIf { it.score >= minScore }
            ?.rect
            ?.let { rect ->
                ScreenPoint(
                    x = rect.left + rect.width / 2f,
                    y = rect.top + rect.height / 2f,
                )
            }

    private companion object {
        const val POPUP_MIN_SCORE = 0.55
        const val ANCHOR_ACTION_MIN_SCORE = 0.45
    }
}
