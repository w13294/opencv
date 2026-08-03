@echo off
set SDKROOT=C:\Users\91299\AppData\Local\Android\Sdk
set SDKMGR=%SDKROOT%\cmdline-tools\latest\bin\sdkmanager.bat
(echo y
echo y
echo y
echo y
echo y
echo y
echo y
echo y
echo y
echo y
echo y
echo y) | "%SDKMGR%" --sdk_root=%SDKROOT% --licenses
echo DONE_EXIT=%ERRORLEVEL%
