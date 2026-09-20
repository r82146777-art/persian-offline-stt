# -*- coding: utf-8 -*-
"""Force MainActivity onto pure GrammarVoskEngine streaming path."""
from pathlib import Path
import re

def main():
    p = Path('app/src/main/java/com/persianstt/offline/MainActivity.kt')
    t = p.read_text(encoding='utf-8')

    # Identifier renames
    for old, new in [
        ('WhisperEngine', 'GrammarVoskEngine'),
        ('VoskEngine', 'GrammarVoskEngine'),
    ]:
        t = t.replace(old, new)

    # Remove OfflineLlm paths in emoji / edit
    t = t.replace('if (!OfflineLlm.isReady(this)) {', 'if (true) {')
    t = t.replace('OfflineLlm.addEmojis(this@MainActivity, current)', 'OfflineAi.addEmojis(current)')
    t = t.replace('OfflineLlm.correctText(this@MainActivity, current)', 'current')
    t = t.replace('OfflineLlm.isReady(this)', 'false')
    t = t.replace('OfflineLlm.lastError', '"AI disabled"')

    # Dialog text
    t = t.replace(
        'مدل سبک Whisper Tiny (~۱۲۰ مگ) دانلود شود؟ بعد می‌توانید Qwen را هم برای تایپ بگیرید.',
        'مدل فارسی Vosk (vosk-model-small-fa-0.5 حدود ۶۰ مگ) یک‌بار دانلود شود؟ تشخیص با دایره لغات (Grammar) دقیق‌تر است.'
    )
    t = t.replace('.setTitle("دانلود مدل")', '.setTitle("دانلود مدل Vosk")')
    t = t.replace('آماده — تایپ با Qwen آفلاین', 'آماده — Vosk + دیکت')
    t = t.replace('شنیدار آماده — برای تایپ بهتر Qwen را دانلود کنید', 'آماده — Vosk + دیکت')

    # Ensure action_dict exists in menu handler
    if 'R.id.action_dict' not in t and 'R.id.action_settings' in t:
        t = t.replace(
            'R.id.action_settings -> { showSoundSettings(); true }',
            '''R.id.action_dict -> {
                startActivity(android.content.Intent(this, DictActivity::class.java))
                true
            }
            R.id.action_settings -> { showSoundSettings(); true }'''
        )

    # long-press dict button
    if 'DictActivity' not in t or t.count('DictActivity') < 2:
        t = t.replace(
            'binding.dictationButton.setOnClickListener { showDictationHelp() }',
            '''binding.dictationButton.setOnClickListener { showDictationHelp() }
        binding.dictationButton.setOnLongClickListener {
            startActivity(android.content.Intent(this, DictActivity::class.java))
            true
        }'''
        )

    p.write_text(t, encoding='utf-8')
    print('Patched MainActivity -> GrammarVoskEngine')
    print('GrammarVoskEngine:', t.count('GrammarVoskEngine'))
    print('WhisperEngine remaining:', t.count('WhisperEngine'))
    print('OfflineLlm remaining:', t.count('OfflineLlm'))

if __name__ == '__main__':
    main()
