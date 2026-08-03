$adb = "C:\Users\91299\Desktop\opencv\opencv\platform-tools\adb.exe"
& $adb logcat -c
& $adb shell am start -n com.example.targettracker/.MainActivity
Start-Sleep -Seconds 8
Write-Host "===CRASH BUFFER==="
& $adb logcat -d -b crash -v time 2>&1
Write-Host "===APP DETECT LOG==="
& $adb logcat -d -v time 2>&1 | Select-String -Pattern "TargetTrackerApp|TargetTracker|TargetDetector|FATAL|AndroidRuntime"