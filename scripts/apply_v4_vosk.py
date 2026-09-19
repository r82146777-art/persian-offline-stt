# -*- coding: utf-8 -*-
"""Minimal safe replacements: force Vosk instead of Whisper in MainActivity."""
from pathlib import Path

def main():
    p = Path('app/src/main/java/com/persianstt/offline/MainActivity.kt')
    t = p.read_text(encoding='utf-8')
    orig = t

    t = t.replace('WhisperEngine.isReady', 'VoskEngine.isReady')
    t = t.replace('WhisperEngine.ensureModel', 'VoskEngine.ensureModel')
    t = t.replace('WhisperEngine.release()', 'VoskEngine.release()')
    t = t.replace('WhisperEngine.lastError', 'VoskEngine.lastError')

    t = t.replace(
        'مدل سبک Whisper Tiny (~۱۲۰ مگ) دانلود شود؟ بعد می‌توانید Qwen را هم برای تایپ بگیرید.',
        'موتور تشخیص گفتار فارسی آفلاین Vosk (حدود ۵۱ مگابایت) یک‌بار دانلود شود؟ بعد کاملاً بدون اینترنت کار می‌کند.'
    )
    t = t.replace('.setTitle("دانلود مدل")', '.setTitle("دانلود موتور Vosk")')
    t = t.replace('.setPositiveButton("بله") { _, _ -> startModelDownload() }',
                  '.setPositiveButton("دانلود") { _, _ -> startModelDownload() }')
    t = t.replace('آماده — تایپ با Qwen آفلاین', 'آماده — موتور Vosk آفلاین')
    t = t.replace('شنیدار آماده — برای تایپ بهتر Qwen را دانلود کنید', 'آماده — موتور Vosk آفلاین')

    if t == orig:
        print('no changes')
    else:
        p.write_text(t, encoding='utf-8')
        print('patched Whisper->Vosk substitutions')
    print('VoskEngine.isReady', t.count('VoskEngine.isReady'))
    print('WhisperEngine.isReady', t.count('WhisperEngine.isReady'))

if __name__ == '__main__':
    main()
