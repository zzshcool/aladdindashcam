package com.aladdin.dashcam.ui

import com.aladdin.dashcam.model.ConnectionStatus
import com.aladdin.dashcam.model.DashCamDeviceStatus
import com.aladdin.dashcam.model.RecordingStatus
import java.util.Locale

data class RecordingTimerDisplay(
    val status: RecordingStatus,
    val displaySeconds: Long,
    val isRecording: Boolean,
    val isUnknown: Boolean,
    val isStopped: Boolean,
    val formattedText: String
)

/**
 * 錄影時間本機單調時鐘推算與動態校正器 (RecordingTimerInterpolator)
 *
 * 核心解決方案：
 * 1. 記錄器控制埠 8081 每 2.5 秒輪詢一次，設備回報頻率低，直接綁定導致 UI 出現 1->4->6->8 跳秒。
 * 2. 以設備回報的真實秒數為基準，使用單調時鐘 (SystemClock.elapsedRealtime) 建立虛擬啟動錨點 (virtualStartTimeMs)，
 *    在本機每秒穩定推算更新 UI，完全不增加控制埠輪詢負擔。
 * 3. 收到新狀態時進行智慧動態校正：
 *    - 正常 1~2 秒網路/輪詢延遲抖動：維持基準與嚴格單調遞增，不無理由倒退。
 *    - 設備超前：向前對齊基準。
 *    - 重新開始錄影 (秒數歸零或大幅變小)：立即重置基準，以新片段開始計時。
 *    - 漂移過大 (> 5s)：平滑強制校正，防止長期時鐘漂移。
 * 4. 停止錄影、斷線、狀態過期 (> 10s)、切換設備時立即清除錨點或標記未知，不繼續假裝錄影正常。
 */
class RecordingTimerInterpolator(
    private val clock: () -> Long = {
        try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val logger: ((String) -> Unit)? = { msg ->
        try {
            android.util.Log.d("RecordingTimer", msg)
        } catch (_: Throwable) {
            // JVM unit test fallback
        }
    }
) {
    // 錨點：虛擬啟動時刻 (本機單調時鐘毫秒數)
    // virtualStartTimeMs = nowMs - (reportedSeconds * 1000L)
    @Volatile
    private var virtualStartTimeMs: Long? = null

    // 最近一次回報的設備秒數
    @Volatile
    private var lastReportedSeconds: Long = -1L

    // 最近一次推算顯示的秒數，保證在同一次錄影中嚴格單調遞增
    @Volatile
    private var lastEmittedSeconds: Long = 0L

    @Volatile
    private var lastLoggedSecond: Long = -1L

    // 當前設備識別 epoch，用於檢測設備切換
    @Volatile
    private var currentEpoch: Long? = null

    // 當前硬體與網路狀態快照
    @Volatile
    private var currentConnectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED

    @Volatile
    private var currentDeviceStatus: DashCamDeviceStatus = DashCamDeviceStatus()

    // 最近一次處理的狀態樣本時間戳 (防止重複送入舊狀態樣本刷新 TTL)
    @Volatile
    private var lastProcessedSampleTimestamp: Long = 0L
    private var lastProcessedSampleSequence: Long = 0L

    // 最近一次收到狀態的單調時鐘時間戳 (完全避免依賴系統 Wall Clock，防範系統時間變更造成過期失效)
    @Volatile
    private var lastStatusMonotonicMs: Long? = null

    fun onConnectionStatusChanged(status: ConnectionStatus) {
        val prev = currentConnectionStatus
        currentConnectionStatus = status
        if (status != ConnectionStatus.CONNECTED) {
            logger?.invoke("連線狀態變更 ($prev -> $status)，重置錄影時間錨點")
            resetAnchor()
            lastStatusMonotonicMs = null
        }
    }

    fun onDeviceSwitched(newEpoch: Long) {
        if (currentEpoch != newEpoch) {
            logger?.invoke("檢測到設備切換 (epoch: $currentEpoch -> $newEpoch)，重置狀態")
            currentEpoch = newEpoch
            resetAnchor()
            lastStatusMonotonicMs = null
            lastProcessedSampleTimestamp = 0L
            lastProcessedSampleSequence = 0L
            currentDeviceStatus = DashCamDeviceStatus()
        }
    }

    fun onDeviceStatus(
        status: DashCamDeviceStatus,
        epoch: Long,
        nowMs: Long = clock(),
        wallClockMs: Long = wallClock()
    ) {
        if (currentEpoch != null && epoch < currentEpoch!!) return
        if (currentEpoch != epoch) {
            onDeviceSwitched(epoch)
        }

        // A07: 舊樣本或無效樣本不得刷新 TTL (lastStatusMonotonicMs)
        // 若為同一份完全相同的舊樣本（時間戳未前進且狀態與秒數完全相同），不得刷新 monotonic 新鮮度
        val sampleTs = status.lastUpdatedTimestamp
        if (sampleTs <= 0L) {
            currentDeviceStatus = status
            lastStatusMonotonicMs = null
            resetAnchor()
            return
        }
        if (status.sampleSequence > 0L) {
            if (status.sampleSequence <= lastProcessedSampleSequence) return
            lastProcessedSampleSequence = status.sampleSequence
        } else {
            val changed = status.recordingStatus != currentDeviceStatus.recordingStatus ||
                status.rawRecordSeconds != currentDeviceStatus.rawRecordSeconds
            if (sampleTs < lastProcessedSampleTimestamp ||
                (sampleTs == lastProcessedSampleTimestamp && (!changed || lastStatusMonotonicMs == null))) return
        }
        lastProcessedSampleTimestamp = sampleTs
        lastStatusMonotonicMs = nowMs
        currentDeviceStatus = status

        val lastMs = lastStatusMonotonicMs
        val isExpired = lastMs == null || (nowMs - lastMs) > 10000L

        if (currentConnectionStatus != ConnectionStatus.CONNECTED ||
            status.recordingStatus != RecordingStatus.RECORDING ||
            isExpired
        ) {
            if (virtualStartTimeMs != null) {
                logger?.invoke("非錄影狀態或狀態已過期 (recordingStatus=${status.recordingStatus}, isExpired=$isExpired)，重置錨點")
                resetAnchor()
            }
            return
        }

        val reportedSeconds = status.rawRecordSeconds
        val candidateStartTimeMs = nowMs - (reportedSeconds * 1000L)
        val currentStartTime = virtualStartTimeMs

        if (currentStartTime == null) {
            // 首次收到有效錄影狀態，建立錨點
            virtualStartTimeMs = candidateStartTimeMs
            lastReportedSeconds = reportedSeconds
            lastEmittedSeconds = reportedSeconds
            logger?.invoke("建立錄影基準錨點: reported=${reportedSeconds}s, virtualStartTimeMs=$candidateStartTimeMs")
        } else {
            val currentProjectedSeconds = ((nowMs - currentStartTime).coerceAtLeast(0L)) / 1000L

            // 判斷 1: 是否為重新開始錄影 (秒數歸零或大幅回退至片段開始 0~3 秒)
            val isRestart = (reportedSeconds < lastReportedSeconds - 3L && reportedSeconds <= 3L) ||
                    (reportedSeconds <= 1L && currentProjectedSeconds > 5L)

            if (isRestart) {
                logger?.invoke("檢測到重新錄影: reported=${reportedSeconds}s (先前=$lastReportedSeconds, 本地推算=$currentProjectedSeconds)，重設基準錨點")
                virtualStartTimeMs = candidateStartTimeMs
                lastReportedSeconds = reportedSeconds
                lastEmittedSeconds = reportedSeconds
                return
            }

            // 判斷 2: 設備回報秒數超前本地推算 (本地跑慢或延遲收到)
            if (reportedSeconds > currentProjectedSeconds) {
                logger?.invoke("設備回報超前: reported=${reportedSeconds}s > 推算=${currentProjectedSeconds}s，向前對齊基準錨點")
                virtualStartTimeMs = candidateStartTimeMs
                lastReportedSeconds = reportedSeconds
                lastEmittedSeconds = maxOf(lastEmittedSeconds, reportedSeconds)
            } else {
                // 判斷 3: 設備回報秒數 <= currentProjectedSeconds
                val lag = currentProjectedSeconds - reportedSeconds
                if (lag > 5L) {
                    // T01: 有界平滑收斂，更新錨點但嚴禁直接倒退 lastEmittedSeconds
                    logger?.invoke("檢測到時鐘漂移超過閾值: lag=${lag}s > 5s，平滑校正錨點，維持單調性 (lastEmitted=$lastEmittedSeconds, reported=$reportedSeconds)")
                    virtualStartTimeMs = candidateStartTimeMs
                    lastReportedSeconds = reportedSeconds
                    // lastEmittedSeconds 不倒退，推算秒數將等待至新錨點時間追上
                } else {
                    // 正常輪詢延遲 (0~5 秒)：保持 virtualStartTimeMs，嚴格單調遞增不倒退
                    lastReportedSeconds = reportedSeconds
                }
            }
        }
    }

    fun getCurrentDisplay(
        nowMs: Long = clock(),
        wallClockMs: Long = wallClock()
    ): RecordingTimerDisplay {
        // T02: 完全依賴 monotonic age 檢查 TTL，不依賴 wallClockMs
        val isExpired = lastStatusMonotonicMs == null || (nowMs - lastStatusMonotonicMs!!) > 10000L

        // 1. 斷線或狀態過期
        if (currentConnectionStatus != ConnectionStatus.CONNECTED || isExpired) {
            return RecordingTimerDisplay(
                status = RecordingStatus.UNKNOWN,
                displaySeconds = 0L,
                isRecording = false,
                isUnknown = true,
                isStopped = false,
                formattedText = "錄影狀態未知"
            )
        }

        // 2. 停錄狀態
        if (currentDeviceStatus.recordingStatus == RecordingStatus.STOPPED) {
            return RecordingTimerDisplay(
                status = RecordingStatus.STOPPED,
                displaySeconds = 0L,
                isRecording = false,
                isUnknown = false,
                isStopped = true,
                formattedText = "已停止錄影"
            )
        }

        // 3. 未知狀態
        if (currentDeviceStatus.recordingStatus != RecordingStatus.RECORDING) {
            return RecordingTimerDisplay(
                status = RecordingStatus.UNKNOWN,
                displaySeconds = 0L,
                isRecording = false,
                isUnknown = true,
                isStopped = false,
                formattedText = "錄影狀態未知"
            )
        }

        // 4. 正常錄影中推算
        val startTime = virtualStartTimeMs
        val rawSeconds = if (startTime != null) {
            ((nowMs - startTime).coerceAtLeast(0L)) / 1000L
        } else {
            currentDeviceStatus.rawRecordSeconds
        }

        val finalSeconds = maxOf(rawSeconds, lastEmittedSeconds)
        lastEmittedSeconds = finalSeconds

        val hrs = finalSeconds / 3600
        val mins = (finalSeconds % 3600) / 60
        val secs = finalSeconds % 60
        val formatted = if (hrs > 0) {
            String.format(Locale.getDefault(), "錄影中 %02d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.getDefault(), "錄影中 %02d:%02d", mins, secs)
        }

        if (finalSeconds != lastLoggedSecond) {
            lastLoggedSecond = finalSeconds
            logger?.invoke("Tick: seconds=$finalSeconds, text=$formatted")
        }

        return RecordingTimerDisplay(
            status = RecordingStatus.RECORDING,
            displaySeconds = finalSeconds,
            isRecording = true,
            isUnknown = false,
            isStopped = false,
            formattedText = formatted
        )
    }

    fun resetAnchor() {
        virtualStartTimeMs = null
        lastReportedSeconds = -1L
        lastEmittedSeconds = 0L
        lastLoggedSecond = -1L
    }
}
