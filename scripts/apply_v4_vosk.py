# -*- coding: utf-8 -*-
from pathlib import Path

def main():
    p = Path('app/src/main/java/com/persianstt/offline/MainActivity.kt')
    t = p.read_text(encoding='utf-8')

    t = t.replace('WhisperEngine.isReady', 'GrammarVoskEngine.isReady')
    t = t.replace('WhisperEngine.ensureModel', 'GrammarVoskEngine.ensureModel')
    t = t.replace('WhisperEngine.release()', 'GrammarVoskEngine.release()')
    t = t.replace('WhisperEngine.lastError', 'GrammarVoskEngine.lastError')
    t = t.replace('VoskEngine.isReady', 'GrammarVoskEngine.isReady')
    t = t.replace('VoskEngine.ensureModel', 'GrammarVoskEngine.ensureModel')
    t = t.replace('VoskEngine.release()', 'GrammarVoskEngine.release()')
    t = t.replace('VoskEngine.lastError', 'GrammarVoskEngine.lastError')
    t = t.replace('VoskEngine.load', 'GrammarVoskEngine.load')

    t = t.replace(
        'مدل سبک Whisper Tiny (~۱۲۰ مگ) دانلود شود؟ بعد می‌توانید Qwen را هم برای تایپ بگیرید.',
        'مدل فارسی Vosk (vosk-model-small-fa-0.5 حدود ۶۰ مگ) یک‌بار دانلود شود؟ تشخیص با دایره لغات (Grammar) دقیق‌تر است.'
    )
    t = t.replace('.setTitle("دانلود مدل")', '.setTitle("دانلود مدل Vosk")')
    t = t.replace('.setTitle("دانلود موتور Vosk")', '.setTitle("دانلود مدل Vosk")')
    t = t.replace('.setPositiveButton("بله") { _, _ -> startModelDownload() }',
                  '.setPositiveButton("دانلود") { _, _ -> startModelDownload() }')
    t = t.replace('آماده — تایپ با Qwen آفلاین', 'آماده — Vosk + دیکت')
    t = t.replace('آماده — موتور Vosk آفلاین', 'آماده — Vosk + دیکت')
    t = t.replace('شنیدار آماده — برای تایپ بهتر Qwen را دانلود کنید', 'آماده — Vosk + دیکت')

    t = t.replace(
        'if (!OfflineLlm.isReady(this)) {\n            // fallback rules only if AI package missing\n            val e = OfflineAi.addEmojis(current)',
        'if (true) {\n            val e = OfflineAi.addEmojis(current)'
    )

    if 'action_dict' not in t and 'action_settings' in t:
        t = t.replace(
            'R.id.action_settings -> { showSoundSettings(); true }',
            """R.id.action_dict -> {
                startActivity(android.content.Intent(this, DictActivity::class.java))
                true
            }
            R.id.action_settings -> { showSoundSettings(); true }"""
        )

    if 'DictActivity' not in t.split('dictationButton')[0] if 'dictationButton' in t else t:
        pass
    if 'startActivity(android.content.Intent(this, DictActivity::class.java))' not in t:
        t = t.replace(
            'binding.dictationButton.setOnClickListener { showDictationHelp() }',
            """binding.dictationButton.setOnClickListener { showDictationHelp() }
        binding.dictationButton.setOnLongClickListener {
            startActivity(android.content.Intent(this, DictActivity::class.java))
            true
        }"""
        )

    p.write_text(t, encoding='utf-8')
    print('MainActivity routed to GrammarVoskEngine')
    print('GrammarVoskEngine.isReady', t.count('GrammarVoskEngine.isReady'))

if __name__ == '__main__':
    main()
