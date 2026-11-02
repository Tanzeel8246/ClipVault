@echo off
title ClipVault Release APK Builder
color 0A
echo ========================================================
echo        ClipVault Mobile - Release APK Builder
echo ========================================================
echo.

cd /d "%~dp0"

echo [1/4] Checking build cache...
if "%1"=="clean" (
    echo Performing full clean of .cxx and build caches...
    if exist "android\app\build\outputs\apk\release" rmdir /s /q "android\app\build\outputs\apk\release"
    if exist "node_modules\expo-modules-core\android\.cxx" rmdir /s /q "node_modules\expo-modules-core\android\.cxx"
    if exist "node_modules\expo-modules-core\android\build" rmdir /s /q "node_modules\expo-modules-core\android\build"
    if exist "node_modules\react-native-screens\android\.cxx" rmdir /s /q "node_modules\react-native-screens\android\.cxx"
    if exist "node_modules\react-native-screens\android\build" rmdir /s /q "node_modules\react-native-screens\android\build"
    if exist "android\.cxx" rmdir /s /q "android\.cxx"
    if exist "android\app\.cxx" rmdir /s /q "android\app\.cxx"
    if exist ".gradle-home\android-build" rmdir /s /q ".gradle-home\android-build"
) else (
    echo Incremental mode: preserving compiled native C++ caches for ultra-fast build.
    if exist "android\app\build\outputs\apk\release" rmdir /s /q "android\app\build\outputs\apk\release"
)

echo [2/4] Setting up clean virtual drive V: (bypasses Windows path spaces and MAX_PATH)...
set VDRIVE=V:
if exist %VDRIVE%\ subst %VDRIVE% /d >nul 2>&1
subst %VDRIVE% "%~dp0."
if errorlevel 1 (
    echo Warning: Could not create virtual drive %VDRIVE%, proceeding with regular path.
    set TARGET_DIR=%~dp0android
) else (
    echo Virtual drive %VDRIVE% successfully created.
    set TARGET_DIR=%VDRIVE%\android
)

echo.
echo [3/4] Compiling Release APK with Native C++ libraries (arm64-v8a)...
echo.

cd /d "%TARGET_DIR%"
call gradlew.bat assembleRelease ^
    -PreactNativeArchitectures=arm64-v8a ^
    -Pclipvault.skipExpoCoreCxx=false ^
    -Pclipvault.skipScreensCxx=false ^
    -x lint ^
    -x lintVitalRelease ^
    --no-daemon

set BUILD_STATUS=%ERRORLEVEL%

cd /d "%~dp0"
if exist %VDRIVE%\ subst %VDRIVE% /d >nul 2>&1

echo.
echo [4/4] Verifying generated APK on disk...
if not exist "%~dp0android\app\build\outputs\apk\release\app-release.apk" (
    color 0C
    echo ========================================================
    echo  ERROR: Build did not produce app-release.apk!
    echo ========================================================
    echo Please inspect the error messages above.
    pause
    exit /b 1
)

echo.
echo ========================================================
echo  BUILD SUCCESSFUL! APK WITH NATIVE C++ LIBRARIES READY!
echo ========================================================
echo.
echo Your Release APK is ready:
echo %~dp0android\app\build\outputs\apk\release\app-release.apk
echo.
echo Opening output folder...
explorer.exe "%~dp0android\app\build\outputs\apk\release"

pause
