$adb = "C:\Users\91299\Desktop\opencv\opencv\platform-tools\adb.exe"
Write-Host "===CRASH BUFFER==="
& $adb logcat -d -b crash -v time 2>&1 | Out-String -Stream | Select-Object -First 80
Write-Host "===APP LOG (filter)==="
& $adb logcat -d -v time 2>&1 | Out-String -Stream | Select-String -Pattern "TargetTrackerApp|TargetTracker|FATAL|AndroidRuntime|UnsatisfiedLink|RuntimeException|YUV|detector" | Select-Object -First 50