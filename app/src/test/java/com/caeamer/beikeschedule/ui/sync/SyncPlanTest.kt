package com.caeamer.beikeschedule.ui.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一键同步的编排规则回归护栏。
 *
 * 这些规则直接决定"会不会拿本机数据覆盖云端（或反过来）"，所以从 ViewModel 里抽成纯函数，
 * 让"云端有无备份 / 本机有无数据 / 探测是否失败"的每条组合都被固定住。
 */
class SyncPlanTest {

    @Test
    fun `云端没有备份时按本机上传`() {
        assertEquals(CloudMode.UPLOAD, defaultCloudMode(probeFailed = false, cloudHasBackup = false, localHasData = true))
        assertEquals(CloudMode.UPLOAD, defaultCloudMode(probeFailed = false, cloudHasBackup = false, localHasData = false))
    }

    @Test
    fun `云端有备份而本机没数据时直接恢复`() {
        assertEquals(CloudMode.RESTORE, defaultCloudMode(probeFailed = false, cloudHasBackup = true, localHasData = false))
    }

    @Test
    fun `两边都有数据时交给用户决定`() {
        val mode = defaultCloudMode(probeFailed = false, cloudHasBackup = true, localHasData = true)
        assertEquals(CloudMode.UNDECIDED, mode)
        assertTrue(needsCloudDecision(mode))
    }

    @Test
    fun `探测失败一律不上传（绝不覆盖云端）`() {
        val mode = defaultCloudMode(probeFailed = true, cloudHasBackup = false, localHasData = true)
        assertEquals(CloudMode.NO_UPLOAD, mode)
        assertFalse(needsCloudDecision(mode))
    }

    @Test
    fun `上传模式的步骤顺序固定且包含上传`() {
        assertEquals(
            listOf(
                SyncStep.IDENTITY,
                SyncStep.CLOUD_TOKEN,
                SyncStep.TIMETABLE,
                SyncStep.GRADES,
                SyncStep.BACKUP,
            ),
            SyncPlanner.steps(CloudMode.UPLOAD),
        )
    }

    @Test
    fun `从云端恢复跳过抓取与上传`() {
        val steps = SyncPlanner.steps(CloudMode.RESTORE)
        assertEquals(listOf(SyncStep.IDENTITY, SyncStep.CLOUD_TOKEN, SyncStep.RESTORE), steps)
        assertFalse(steps.contains(SyncStep.TIMETABLE))
        assertFalse(steps.contains(SyncStep.GRADES))
        assertFalse(steps.contains(SyncStep.BACKUP))
    }

    @Test
    fun `不上传模式不包含上传步骤`() {
        assertFalse(SyncPlanner.steps(CloudMode.NO_UPLOAD).contains(SyncStep.BACKUP))
        assertFalse(SyncPlanner.steps(CloudMode.UNDECIDED).contains(SyncStep.BACKUP))
    }

    @Test
    fun `重试只跑没成功的步骤`() {
        val results = mapOf(
            SyncStep.IDENTITY to SyncStepResult(SyncStep.IDENTITY, SyncStatus.OK),
            SyncStep.CLOUD_TOKEN to SyncStepResult(SyncStep.CLOUD_TOKEN, SyncStatus.FAILED, "超时"),
            SyncStep.TIMETABLE to SyncStepResult(SyncStep.TIMETABLE, SyncStatus.OK),
        )
        assertEquals(
            listOf(SyncStep.CLOUD_TOKEN, SyncStep.GRADES, SyncStep.BACKUP),
            SyncPlanner.retrySteps(CloudMode.UPLOAD, results),
        )
    }

    @Test
    fun `跳过的步骤不会在重试时补跑`() {
        val results = mapOf(
            SyncStep.IDENTITY to SyncStepResult(SyncStep.IDENTITY, SyncStatus.OK),
            SyncStep.CLOUD_TOKEN to SyncStepResult(SyncStep.CLOUD_TOKEN, SyncStatus.OK),
            SyncStep.RESTORE to SyncStepResult(SyncStep.RESTORE, SyncStatus.OK),
            SyncStep.TIMETABLE to SyncStepResult(SyncStep.TIMETABLE, SyncStatus.SKIPPED, "已跳过（用云端数据）"),
            SyncStep.GRADES to SyncStepResult(SyncStep.GRADES, SyncStatus.SKIPPED, "已跳过（用云端数据）"),
        )
        assertTrue(SyncPlanner.retrySteps(CloudMode.RESTORE, results).isEmpty())
    }

    @Test
    fun `失败项重试时仍从身份开始（换学号场景）`() {
        val results = mapOf(
            SyncStep.IDENTITY to SyncStepResult(SyncStep.IDENTITY, SyncStatus.FAILED, "未获取到学号"),
            SyncStep.CLOUD_TOKEN to SyncStepResult(SyncStep.CLOUD_TOKEN, SyncStatus.FAILED, "未取得学号"),
        )
        assertEquals(
            listOf(SyncStep.IDENTITY, SyncStep.CLOUD_TOKEN, SyncStep.TIMETABLE, SyncStep.GRADES),
            SyncPlanner.retrySteps(CloudMode.NO_UPLOAD, results),
        )
    }
}
