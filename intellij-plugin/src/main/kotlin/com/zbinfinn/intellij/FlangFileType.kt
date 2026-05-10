package com.zbinfinn.intellij

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

class FlangFileType private constructor() : LanguageFileType(FlangLanguage) {
    override fun getName(): String = "DiamondFire Flang"

    override fun getDescription(): String = "DiamondFire Flang source file"

    override fun getDefaultExtension(): String = "fl"

    override fun getIcon(): Icon? = null

    companion object {
        @JvmField
        val INSTANCE = FlangFileType()
    }
}
