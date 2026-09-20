# -*- coding: utf-8 -*-
from pathlib import Path
import re

def main():
    p = Path('app/src/main/java/com/persianstt/offline/MainActivity.kt')
    t = p.read_text(encoding='utf-8')

    # Fix any previous bad double-prefix
    t = t.replace('GrammarGrammarVoskEngine', 'GrammarVoskEngine')

    # Word-boundary safe renames (do not touch GrammarVoskEngine)
    def ren(src, dst, text):
        return re.sub(r'(?<![A-Za-z])' + re.escape(src) + r'(?![A-Za-z])', dst, text)

    t = ren('WhisperEngine', 'GrammarVoskEngine', t)
    t = ren('VoskEngine', 'GrammarVoskEngine', t)

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

    if 'DictActivity::class.java' not in t:
        t = t.replace(
            'binding.dictationButton.setOnClickListener { showDictationHelp() }',
            """binding.dictationButton.setOnClickListener { showDictationHelp() }
        binding.dictationButton.setOnLongClickListener {
            startActivity(android.content.Intent(this, DictActivity::class.java))
            true
        }"""
        )

    p.write_text(t, encoding='utf-8')
    print('patched ok')
    print('GrammarVoskEngine', t.count('GrammarVoskEngine'))
    print('GrammarGrammar', t.count('GrammarGrammar'))
    print('WhisperEngine', t.count('WhisperEngine'))
    print('bare VoskEngine', len(re.findall(r'(?<![A-Za-z])VoskEngine(?![A-Za-z])', t)))

if __name__ == '__main__':
    main()
