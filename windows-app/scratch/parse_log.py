
import re
log = open(r'C:\Users\91299\Desktop\opencv\opencv\scratch\detector2.log', encoding='utf-8', errors='ignore').read()
# 找 TargetDetector 段
import re
total = 0
hits = 0
recent = []
for m in re.finditer(r'(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+).*TargetDetector: (.*)$', log, re.MULTILINE):
    total += 1
    if 'Candidates' in m.group(2):
        hits += 1
        recent.append(m.group(1) + ' ' + m.group(2))
print(f"total TargetDetector lines: {total}")
print(f"candidates lines: {hits}")
print(f"\n--- last 40 candidates results ---")
for r in recent[-40:]:
    print(r)
