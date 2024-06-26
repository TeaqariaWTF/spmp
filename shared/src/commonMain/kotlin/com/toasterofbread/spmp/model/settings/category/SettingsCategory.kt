package com.toasterofbread.spmp.model.settings.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.toasterofbread.composekit.settings.ui.SettingsPageWithItems
import com.toasterofbread.composekit.settings.ui.item.ComposableSettingsItem
import com.toasterofbread.composekit.settings.ui.item.SettingsItem
import com.toasterofbread.composekit.utils.common.blendWith
import com.toasterofbread.spmp.model.settings.SettingsKey
import com.toasterofbread.spmp.platform.AppContext
import com.toasterofbread.composekit.utils.common.amplifyPercent
import com.toasterofbread.composekit.settings.ui.SettingsPage
import com.toasterofbread.composekit.settings.ui.SettingsInterface

sealed class SettingsCategory(id: String) {
    val id: String = id.uppercase()
    abstract val keys: List<SettingsKey>

    abstract fun getPage(): CategoryPage?
    open fun showPage(exporting: Boolean): Boolean = true

    fun getNameOfKey(key: SettingsKey): String =
        "${id}_${(key as Enum<*>).name}"

    fun getKeyOfName(name: String): SettingsKey? {
        val split: List<String> = name.split('_', limit = 2)
        if (split.size != 2 || split[0] != id) {
            return null
        }

        return keys.firstOrNull {
            (it as Enum<*>).name == split[1]
        }
    }

    abstract class CategoryPage(
        val category: SettingsCategory,
        val name: String
    ) {
        abstract fun getTitleItem(context: AppContext): SettingsItem?
        abstract fun openPageOnInterface(context: AppContext, settings_interface: SettingsInterface)
        open fun getItems(context: AppContext): List<SettingsItem>? = null
    }

    protected open inner class SimplePage(
        val title: String,
        val description: String,
        private val getPageItems: (AppContext) -> List<SettingsItem>,
        private val getPageIcon: @Composable () -> ImageVector,
        private val titleBarEndContent: @Composable () -> Unit = {}
    ): CategoryPage(this, title) {
        private var items: List<SettingsItem>? = null

        override fun openPageOnInterface(context: AppContext, settings_interface: SettingsInterface) {
            settings_interface.openPage(
                object : SettingsPageWithItems(
                    getTitle = { title },
                    getItems = { getItems(context) },
                    getIcon = { getPageIcon() }
                ) {
                    @Composable
                    override fun TitleBarEndContent() {
                        titleBarEndContent()
                        super.TitleBarEndContent()
                    }
                }
            )
        }

        override fun getItems(context: AppContext): List<SettingsItem> {
            if (items == null) {
                items = getPageItems(context).filter {
                    it.getKeys().none { key_name ->
                        for (cat in listOf(category) + SettingsCategory.all) {
                            val key: SettingsKey? = cat.getKeyOfName(key_name)
                            if (key != null) {
                                return@none key.isHidden()
                            }
                        }
                        throw RuntimeException("Key not found: $key_name (category: $category)")
                    }
                }
            }
            return items!!
        }

        override fun getTitleItem(context: AppContext): SettingsItem? =
            ComposableSettingsItem { modifier ->
                ElevatedCard(
                    onClick = {
                        openPageOnInterface(context, this)
                    },
                    modifier = modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = theme.background.amplifyPercent(0.03f),
                        contentColor = theme.on_background
                    )
                ) {
                    Row(
                        Modifier.padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(15.dp)
                    ) {
                        Icon(getPageIcon(), null)
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(description, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
    }

    companion object {
        val all: List<SettingsCategory> get() =
            listOf(
                YoutubeAuthSettings,

                SystemSettings,
                BehaviourSettings,
                LayoutSettings,
                PlayerSettings,
                FeedSettings,
                ThemeSettings,
                LyricsSettings,
                DiscordSettings,
                DiscordAuthSettings,
                FilterSettings,
                StreamingSettings,
                ShortcutSettings,
                DesktopSettings,
                MiscSettings,

                YTApiSettings,
                InternalSettings
            ).apply {
                check(distinctBy { it.id }.size == size)
            }

        val with_page: List<SettingsCategory> get() =
            all.filter { it.getPage() != null }

        val pages: List<CategoryPage> get() =
            all.mapNotNull { it.getPage() }

        fun fromId(id: String): SettingsCategory =
            all.firstOrNull { it.id == id } ?: throw RuntimeException(id)

        fun fromIdOrNull(id: String): SettingsCategory? =
            all.firstOrNull { it.id == id }
    }
}
