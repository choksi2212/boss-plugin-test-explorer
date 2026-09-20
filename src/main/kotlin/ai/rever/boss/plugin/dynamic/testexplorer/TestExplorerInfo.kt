package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle

/**
 * Test Explorer panel info.
 *
 * Lives in the left bottom sidebar at priority 64. Priority slots:
 *   - 14: git status (left_bottom.top)
 *   - 64: test explorer (this, left_bottom.middle)
 *   - 100+: anything else
 *
 * Priority is the default sort; the user can drag panels to reorder them within a slot.
 */
object TestExplorerInfo : PanelInfo {
    override val id = PanelId("test-explorer", 64)
    override val displayName = "Test Explorer"
    override val icon = Icons.Filled.CheckCircle
    override val defaultSlotPosition = left.bottom
}
