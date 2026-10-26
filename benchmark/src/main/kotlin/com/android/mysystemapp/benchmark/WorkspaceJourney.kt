package com.android.mysystemapp.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val WORKSPACE_PACKAGE = "com.android.mysystemapp"

internal enum class WorkspaceGroup(val english: String, val vietnamese: String) {
    ASSISTANCE("Assistance", "Hỗ trợ lái"),
    SEATS("Seats", "Ghế ngồi"),
    DOORS("Doors & glass", "Cửa & kính"),
}

internal fun workspaceIntent() =
    Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(WORKSPACE_PACKAGE, "$WORKSPACE_PACKAGE.MainActivity")
    }

internal fun UiDevice.selectWorkspaceGroup(group: WorkspaceGroup) {
    val label = Pattern.compile("^(?:${Pattern.quote(group.english)}|${Pattern.quote(group.vietnamese)})$")
    val selector = By.desc(label)
    check(wait(Until.hasObject(selector), 10_000)) { "Workspace group missing: ${group.english}" }
    findObject(selector).click()
    waitForIdle()
}
