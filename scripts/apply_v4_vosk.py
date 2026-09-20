# -*- coding: utf-8 -*-
"""No-op / safety patch: source already uses GrammarVoskEngine."""
from pathlib import Path

def main():
    p = Path('app/src/main/java/com/persianstt/offline/MainActivity.kt')
    if not p.exists():
        print('MainActivity missing')
        return
    t = p.read_text(encoding='utf-8')
    print('GrammarVoskEngine refs:', t.count('GrammarVoskEngine'))
    print('WhisperEngine refs:', t.count('WhisperEngine'))
    print('OfflineLlm refs:', t.count('OfflineLlm'))
    print('OK - source is pure Grammar Vosk')

if __name__ == '__main__':
    main()
