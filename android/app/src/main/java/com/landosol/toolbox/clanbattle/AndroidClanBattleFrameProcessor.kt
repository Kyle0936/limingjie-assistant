package com.landosol.toolbox.clanbattle

import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.clanbattle.recognition.AndroidPixelImageAdapter
import com.landosol.toolbox.clanbattle.recognition.BattleControlRecognizer
import com.landosol.toolbox.clanbattle.recognition.BattleMenuRecognizer
import com.landosol.toolbox.clanbattle.recognition.BattleScreenRecognizer
import com.landosol.toolbox.clanbattle.recognition.ClanBattleFrameProcessor
import com.landosol.toolbox.clanbattle.recognition.ClanBattleObservation
import com.landosol.toolbox.clanbattle.recognition.EmbeddedControlTemplateLoader
import com.landosol.toolbox.clanbattle.recognition.EmbeddedBattleTemplateLoader
import com.landosol.toolbox.clanbattle.recognition.EmbeddedDigitTemplateLoader

/**
 * Android boundary for the pure Kotlin vision pipeline.
 * The caller transfers ownership of [CapturedFrame.bitmap]; it is always recycled after conversion.
 */
class AndroidClanBattleFrameProcessor(
    private val processor: ClanBattleFrameProcessor,
) {
    fun process(frame: CapturedFrame): ClanBattleObservation = try {
        processor.process(
            image = AndroidPixelImageAdapter.from(frame.bitmap),
            timestampMillis = frame.timestampMillis,
        )
    } finally {
        if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
    }

    companion object {
        fun createDefault(): AndroidClanBattleFrameProcessor {
            val controlTemplates = EmbeddedControlTemplateLoader.load()
            val battleTemplates = EmbeddedBattleTemplateLoader.load()
            return AndroidClanBattleFrameProcessor(
                ClanBattleFrameProcessor(
                    templates = EmbeddedDigitTemplateLoader.load(),
                    screenRecognizer = BattleScreenRecognizer(battleTemplates),
                    controlRecognizer = BattleControlRecognizer(controlTemplates.controls),
                    menuRecognizer = BattleMenuRecognizer(controlTemplates.menu),
                ),
            )
        }
    }
}
