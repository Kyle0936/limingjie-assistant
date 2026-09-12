package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthShopItemTextTest {
    @Test
    fun `shop role imprint titles are separated from relics`() {
        assertEquals(
            "坦克型职能印记",
            LabyrinthShopItemText.roleImprintLabel("坦克型职能的选择印记"),
        )
        assertEquals(
            "破防型职能印记",
            LabyrinthShopItemText.roleImprintLabel("破防型职能的随机印记"),
        )
        assertEquals(
            "增益型职能印记",
            LabyrinthShopItemText.roleImprintLabel("增益型 职能的 随机印记"),
        )
    }

    @Test
    fun `ordinary relic names are not role imprints`() {
        assertNull(LabyrinthShopItemText.roleImprintLabel("防守铠甲"))
        assertNull(LabyrinthShopItemText.roleImprintLabel("流烈耳饰"))
        assertNull(LabyrinthShopItemText.roleImprintLabel("终焉邪眼杖"))
        assertFalse(LabyrinthShopItemText.looksLikeRoleImprint("终焉邪眼杖"))
    }

    @Test
    fun `coarse imprint gate can reject relic hypothesis without guessing exact role`() {
        assertTrue(LabyrinthShopItemText.looksLikeRoleImprint("干扰型职能的随机印记"))
        assertTrue(LabyrinthShopItemText.looksLikeRoleImprint("职能的随机印記"))
        assertNull(LabyrinthShopItemText.roleImprintLabel("职能的随机印記"))
    }
}
