package com.toasterofbread.spmp.ui.layout.contentbar

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import com.toasterofbread.spmp.ui.layout.contentbar.layoutslot.LayoutSlot
import com.toasterofbread.spmp.ui.layout.contentbar.element.ContentBarElement
import com.toasterofbread.composekit.settings.ui.Theme

data class TemplateCustomContentBar(
    val template: CustomContentBarTemplate
): ContentBar() {
    override fun getName(): String = template.getName()
    override fun getDescription(): String? = template.getDescription()
    override fun getIcon(): ImageVector = template.getIcon()

    @Composable
    override fun BarContent(
        slot: LayoutSlot,
        background_colour: Theme.Colour?,
        content_padding: PaddingValues,
        distance_to_page: Dp,
        lazy: Boolean,
        modifier: Modifier
    ): Boolean {
        val elements: List<ContentBarElement> = remember { template.getElements() }
        return CustomBarContent(elements, template.getDefaultHeight(), slot.is_vertical, content_padding, slot, background_colour, modifier)
    }
}
